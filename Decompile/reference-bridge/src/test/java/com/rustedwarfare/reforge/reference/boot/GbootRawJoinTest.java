package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Public early gates only. Initialized raw routes require a real Android probe. */
public final class GbootRawJoinTest {
    private interface Action { void run(); }

    private static FatalLatch.Failure failure(String code, Action action) {
        try {
            action.run();
            throw new AssertionError("Expected " + code);
        } catch (FatalLatch.Failure error) {
            if (!code.equals(error.code()))
                throw new AssertionError("Expected " + code + ", got " + error.code());
            return error;
        }
    }

    private static void invoke(String api, String base, String value) {
        if ("join".equals(api)) GbootBootstrap.joinCheckedReadRoute(base, value);
        else if ("target".equals(api)) GbootBootstrap.joinedContainmentKey(value);
        else if ("units".equals(api)) GbootBootstrap.joinedUnitsContainmentKey(value);
        else if ("directory".equals(api)) GbootBootstrap.joinedDirectoryContainmentKey(value);
        else throw new IllegalArgumentException("Unknown API " + api);
    }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("scenario API required");
        String api = args[1];
        FatalLatch.Failure first;
        if ("not-ready".equals(args[0])) {
            first = failure("roots-not-ready", () -> invoke(api, null, null));
        } else if ("prefailed".equals(args[0])) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
        } else throw new IllegalArgumentException("Unknown scenario " + args[0]);
        int checks = 1;
        for (String member : new String[] {"join", "target", "units", "directory"}) {
            for (String value : new String[] {null, "", "assets/", "foo..png", "../outside"}) {
                if (failure(first.code(), () -> invoke(member, "", value)) != first)
                    throw new AssertionError("First failure identity changed for " + member);
                checks++;
            }
        }
        System.out.println("PASS GbootRawJoin " + args[0] + " " + api
                + " checks=" + checks + " (no initialized Android Context)");
    }
}
