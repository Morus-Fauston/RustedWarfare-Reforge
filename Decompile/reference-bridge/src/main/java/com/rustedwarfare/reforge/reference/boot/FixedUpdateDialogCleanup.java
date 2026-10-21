package com.rustedwarfare.reforge.reference.boot;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Looper;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only the original MainMenu update popup; all UI changes occur on the main thread. */
public final class FixedUpdateDialogCleanup {
    private static final Map<Dialog, Activity> pending = new IdentityHashMap<Dialog, Activity>();
    private static final Map<Dialog, Activity> observed = new IdentityHashMap<Dialog, Activity>();
    private static final ArrayList<Dialog> order = new ArrayList<Dialog>();
    private static boolean closed;
    private static int dismissedCount, lateCount;
    private static Throwable first;

    private FixedUpdateDialogCleanup() { }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static void gate() {
        require(Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper(), "fixed-dialog-main-thread");
        GbootBootstrap.requireBackendPublished();
        BatchSettings.name();
    }

    private static void remember(Throwable error) {
        if (first == null) first = error;
        else if (first != error) first.addSuppressed(error);
    }

    /** Called only at the single reviewed Builder.show return site; does not alter the popup. */
    public static synchronized void track(Context context, Dialog dialog) throws Throwable {
        try {
            gate();
            require(context instanceof Activity && context.getClass().getName().equals("com.corrodinggames.rts.appFramework.MainMenuActivity")
                && context.getPackageName().equals("com.corrodinggames.rts") && dialog != null, "fixed-dialog-owner");
            Activity activity = (Activity) context;
            if (observed.containsKey(dialog)) {
                require(observed.get(dialog) == activity, "fixed-dialog-owner-changed");
                if (first != null) throw first;
                return;
            }
            require(observed.size() < 8, "fixed-dialog-count-limit");
            observed.put(dialog, activity); pending.put(dialog, activity); order.add(dialog);
            if (closed) { lateCount++; dismiss(dialog); }
        } catch (Throwable error) { remember(error); }
        if (first != null) throw first;
    }

    private static void dismiss(Dialog dialog) {
        dialog.dismiss();
        require(!dialog.isShowing(), "fixed-dialog-still-showing");
        pending.remove(dialog); dismissedCount++;
    }

    /** Main-thread teardown: attempt every recorded popup, preserve the first cleanup failure. */
    public static synchronized void close() throws Throwable {
        try {
            gate();
            if (!closed) {
                closed = true;
                for (Dialog dialog : order) {
                    if (!pending.containsKey(dialog)) continue;
                    try { dismiss(dialog); } catch (Throwable error) { remember(error); }
                }
            }
        } catch (Throwable error) { remember(error); }
        if (first != null) throw first;
    }

    /** Own counters only; no framework/window state is read from a worker thread. */
    public static synchronized Map<String, Object> status() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("closed", closed); raw.put("observedCount", observed.size()); raw.put("dismissedCount", dismissedCount);
        raw.put("lateCount", lateCount); raw.put("pendingCount", pending.size());
        raw.put("firstError", first == null ? null : first.getClass().getName() + ":" + first.getMessage());
        return raw;
    }
}
