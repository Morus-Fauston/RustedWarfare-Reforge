package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Each scenario uses a fresh JVM; only uninitialized and first-failure public seams. */
public final class GbootMetadataRouteTest {
    private interface Action { void run(); }
    private static FatalLatch.Failure failure(String code, Action action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (FatalLatch.Failure error) {
            if (!code.equals(error.code())) throw new AssertionError("expected=" + code + " actual=" + error.code());
            return error;
        }
    }
    public static void main(String[] args) {
        String scenario = args[0];
        FatalLatch.Failure first;
        if (scenario.equals("not-ready")) {
            first = failure("roots-not-ready", () -> GbootBootstrap.privateLastModified("/private/owned"));
        } else if (scenario.equals("null-before-ready")) {
            first = failure("roots-not-ready", () -> GbootBootstrap.privateLastModified(null));
        } else if (scenario.equals("prefailed")) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
        } else throw new IllegalArgumentException("Unknown scenario " + scenario);
        if (failure(first.code(), () -> GbootBootstrap.privateLastModified("/private/owned")) != first
                || failure(first.code(), () -> GbootBootstrap.privateLastModified(null)) != first)
            throw new AssertionError("First failure object identity must survive metadata calls");
        System.out.println("PASS GbootMetadataRoute " + scenario + " (no initialized Context or backend runtime)");
    }
}
