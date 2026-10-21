package com.rustedwarfare.reforge.reference.boot;

import android.app.Dialog;
import android.app.Activity;
import android.os.Looper;
import com.corrodinggames.rts.appFramework.MainMenuActivity;
import java.util.Map;

/** Self-owned JVM lifecycle seam, never ART/WindowManager proof. One fresh JVM per case. */
public final class FixedUpdateDialogCleanupTest {
    static void require(boolean ok, Object reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Throwable {
        if (!args[0].equals("unbound")) BatchSettings.configure("125b9de7-c3b9-42ee-bfc2-3159c10a9f51");
        String mode = args[0]; Activity activity = new MainMenuActivity(); Dialog a = new Dialog(), b = new Dialog();
        if (mode.equals("empty")) {
            FixedUpdateDialogCleanup.close();
            Map<String, Object> status = FixedUpdateDialogCleanup.status();
            require(Boolean.TRUE.equals(status.get("closed")) && status.get("observedCount").equals(0) && status.get("pendingCount").equals(0), status);
            System.out.println("PASS self-owned empty dialog cleanup"); return;
        }
        if (mode.equals("wrong-owner")) activity = new Activity();
        if (mode.equals("wrong-package")) android.content.Context.packageName = "other";
        if (mode.equals("off-main")) Looper.onMain = false;
        if (mode.equals("unpublished")) GbootBootstrap.published = false;
        if (mode.equals("wrong-owner") || mode.equals("wrong-package") || mode.equals("off-main") || mode.equals("unpublished") || mode.equals("unbound")) {
            Throwable error = null; try { FixedUpdateDialogCleanup.track(activity, a); } catch (Throwable failure) { error = failure; }
            require(error != null && a.dismissCalls == 0, "invalid registration changed dialog");
            System.out.println("PASS self-owned dialog rejection " + mode); return;
        }
        FixedUpdateDialogCleanup.track(activity, a); FixedUpdateDialogCleanup.track(activity, a);
        FixedUpdateDialogCleanup.track(activity, b);
        require(a.showing && b.showing && a.dismissCalls == 0 && b.dismissCalls == 0, "track modified original popup");
        if (mode.equals("owner-changed")) {
            Throwable first = null;
            try { FixedUpdateDialogCleanup.track(new MainMenuActivity(), a); } catch (Throwable error) { first = error; }
            require(first != null && first.getMessage().equals("fixed-dialog-owner-changed") && a.dismissCalls == 0, "dialog ownership changed");
            try { FixedUpdateDialogCleanup.close(); throw new AssertionError("owner failure cleared"); }
            catch (Throwable error) { require(error == first && !a.showing && !b.showing, "first error/cleanup lost"); }
            System.out.println("PASS self-owned exact Activity relation"); return;
        }
        if (mode.equals("failure")) a.failure = new IllegalStateException("first-dismiss-failure");
        if (mode.equals("ineffective")) a.ineffective = true;
        Throwable first = null;
        try { FixedUpdateDialogCleanup.close(); } catch (Throwable error) { first = error; }
        if (mode.equals("failure") || mode.equals("ineffective")) {
            require(first != null && a.dismissCalls == 1 && b.dismissCalls == 1 && !b.showing, "did not attempt every dialog");
            if (mode.equals("failure")) require(first == a.failure, "first error replaced");
            try { FixedUpdateDialogCleanup.close(); throw new AssertionError("failure reset"); }
            catch (Throwable error) { require(error == first && a.dismissCalls == 1, "failure was retried or replaced"); }
            require(((Integer) FixedUpdateDialogCleanup.status().get("pendingCount")) == 1, "failed dialog not preserved");
        } else {
            require(first == null && !a.showing && !b.showing && a.dismissCalls == 1 && b.dismissCalls == 1, first);
            FixedUpdateDialogCleanup.close(); require(a.dismissCalls == 1, "repeated close side effect");
            Dialog late = new Dialog(); FixedUpdateDialogCleanup.track(activity, late);
            require(!late.showing && late.dismissCalls == 1, "late popup leaked");
            if (mode.equals("late-failure")) {
                Dialog broken = new Dialog(); broken.failure = new IllegalStateException("late-first");
                try { FixedUpdateDialogCleanup.track(activity, broken); throw new AssertionError("late failure hidden"); }
                catch (Throwable error) { require(error == broken.failure, "late first error replaced"); }
                require(FixedUpdateDialogCleanup.status().get("pendingCount").equals(1)
                    && FixedUpdateDialogCleanup.status().get("firstError").toString().contains("late-first"), "late failure not retained");
                System.out.println("PASS self-owned late dialog failure"); return;
            }
            Map<String, Object> status = FixedUpdateDialogCleanup.status();
            require(Boolean.TRUE.equals(status.get("closed")) && status.get("observedCount").equals(3)
                && status.get("dismissedCount").equals(3) && status.get("lateCount").equals(1)
                && status.get("pendingCount").equals(0) && status.get("firstError") == null, status);
        }
        System.out.println("PASS self-owned dialog lifecycle " + mode);
    }
}
