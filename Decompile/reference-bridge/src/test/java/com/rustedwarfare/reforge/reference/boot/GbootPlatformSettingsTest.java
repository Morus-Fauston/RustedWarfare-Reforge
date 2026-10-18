package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Public failure gates only; no Android Context or disk-success mock. */
public final class GbootPlatformSettingsTest {
    private interface Action { void run(); }

    private static FatalLatch.Failure failure(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            if (!code.equals(error.code())) throw new AssertionError("Unexpected failure " + error.code());
            return error;
        }
    }

    public static void main(String[] args) {
        if (!"boolean".equals(args[0])) {
            FatalLatch.Failure first;
            if ("context-not-ready".equals(args[0])) {
                first = failure("roots-not-ready", () -> GbootBootstrap.requirePlatformSettingsContext(null));
            } else if ("open-not-ready".equals(args[0])) {
                first = failure("roots-not-ready", () -> GbootBootstrap.openPlatformSettings(null));
            } else if ("prefailed".equals(args[0])) {
                first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
            } else throw new IllegalArgumentException("Unknown scenario " + args[0]);
            if (failure(first.code(), () -> GbootBootstrap.requirePlatformSettingsContext(null)) != first
                    || failure(first.code(), () -> GbootBootstrap.openPlatformSettings(null)) != first
                    || failure(first.code(), () -> GbootBootstrap.requirePlatformSettingsCommitted(true)) != first
                    || failure(first.code(), () -> GbootBootstrap.requirePlatformSettingsCommitted(false)) != first)
                throw new AssertionError("First failure identity changed");
            System.out.println("PASS GbootPlatformSettings " + args[0] + " (5 failure calls; no initialized Android Context)");
            return;
        }
        GbootBootstrap.requirePlatformSettingsCommitted(true);
        FatalLatch.Failure first;
        try {
            GbootBootstrap.requirePlatformSettingsCommitted(false);
            throw new AssertionError("A false platform commit must stop the session");
        } catch (FatalLatch.Failure error) {
            first = error;
            if (!"settings-commit-failed".equals(error.code()))
                throw new AssertionError("Unexpected commit error " + error.code());
        }
        for (boolean value : new boolean[] {true, false}) {
            try {
                GbootBootstrap.requirePlatformSettingsCommitted(value);
                throw new AssertionError("A failed session cannot resume");
            } catch (FatalLatch.Failure error) {
                if (error != first) throw new AssertionError("First failure identity changed");
            }
        }
        System.out.println("PASS GbootPlatformSettings boolean (4 public calls; no Android disk claim)");
    }
}
