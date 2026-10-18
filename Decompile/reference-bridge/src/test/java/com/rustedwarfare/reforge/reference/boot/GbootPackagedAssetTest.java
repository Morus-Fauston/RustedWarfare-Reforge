package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Public early-failure tests; initialized Android behavior belongs to the APP probe. */
public final class GbootPackagedAssetTest {
    private interface Action { void run(); }

    private static FatalLatch.Failure failure(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            if (!code.equals(error.code())) throw new AssertionError("Unexpected failure " + error.code());
            return error;
        }
    }

    public static void main(String[] args) {
        FatalLatch.Failure first;
        if ("not-ready-root".equals(args[0])) {
            first = failure("roots-not-ready", () -> GbootBootstrap.listPackagedAssets(null, ""));
        } else if ("not-ready-null".equals(args[0])) {
            first = failure("roots-not-ready", () -> GbootBootstrap.listPackagedAssets(null, null));
        } else if ("prefailed".equals(args[0])) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
        } else throw new IllegalArgumentException("Unknown scenario " + args[0]);
        for (String name : new String[] {"", null, "assets/plain", "../outside"}) {
            if (failure(first.code(), () -> GbootBootstrap.listPackagedAssets(null, name)) != first)
                throw new AssertionError("First failure identity changed");
        }
        System.out.println("PASS GbootPackagedAsset " + args[0] + " (no initialized Android Context)");
    }
}
