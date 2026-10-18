package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Public failure ordering only; real Context and AssetManager behavior needs Android. */
public final class GbootPackagedAssetBindingTest {
    private interface Action { void run(); }

    private static FatalLatch.Failure failure(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            if (!code.equals(error.code()))
                throw new AssertionError("Expected " + code + ", got " + error.code());
            return error;
        }
    }

    private static void same(FatalLatch.Failure first, Action action) {
        if (failure(first.code(), action) != first)
            throw new AssertionError("First failure identity changed");
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("One fresh-JVM scenario is required");
        FatalLatch.Failure first;
        if ("bind-before-roots".equals(args[0])) {
            first = failure("roots-not-ready", () -> GbootBootstrap.bindPackagedAssetIndex(null));
        } else if ("list-before-bind".equals(args[0])) {
            first = failure("roots-not-ready", () -> GbootBootstrap.listPackagedAssets(null, null));
            same(first, () -> GbootBootstrap.bindPackagedAssetIndex(null));
        } else if ("prefailed".equals(args[0])) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
        } else throw new IllegalArgumentException("Unknown scenario " + args[0]);
        same(first, () -> GbootBootstrap.bindPackagedAssetIndex(null));
        for (String name : new String[] {null, "", "plain", "assets/plain", "../outside"}) {
            same(first, () -> GbootBootstrap.listPackagedAssets(null, name));
        }
        same(first, () -> GbootBootstrap.requireRootsReady());
        same(first, () -> GbootBootstrap.requireBackendPublished());
        same(first, () -> GbootBootstrap.init(null));
        same(first, () -> GbootBootstrap.privateRoot());
        same(first, () -> GbootBootstrap.requirePlatformSettingsCommitted(false));
        System.out.println("PASS GbootPackagedAssetBinding " + args[0]
                + " (no initialized Android Context; no successful manager binding proof)");
    }
}
