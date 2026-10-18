package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;
import java.io.File;

/** Public mutation early gates only; no initialized Android Context or file mutation. */
public final class GbootMutationGateTest {
    private interface Action { void run(); }

    private static final class UnreadableFile extends File {
        UnreadableFile() { super("unread-path"); }
        @Override public String getPath() {
            throw new AssertionError("Early gate must precede File.getPath");
        }
    }

    private static FatalLatch.Failure failure(String code, Action action) {
        try {
            action.run();
            throw new AssertionError("Expected fatal " + code);
        } catch (FatalLatch.Failure error) {
            if (!code.equals(error.code()))
                throw new AssertionError("Expected " + code + ", got " + error.code());
            return error;
        }
    }

    private static void invoke(String api, File source, File destination) {
        if ("delete".equals(api)) GbootBootstrap.denyFileDelete();
        else if ("rename".equals(api)) GbootBootstrap.renamePrivateBridgeFile(source, destination);
        else throw new IllegalArgumentException("Unknown API " + api);
    }

    private static void invokeInitial(String member) {
        if ("delete".equals(member)) invoke("delete", null, null);
        else if ("rename-null".equals(member)) invoke("rename", null, null);
        else if ("rename-relative".equals(member)) invoke("rename", new File("../source"), new File("destination"));
        else if ("rename-unreadable".equals(member)) invoke("rename", new UnreadableFile(), new UnreadableFile());
        else throw new IllegalArgumentException("Unknown member " + member);
    }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("scenario member required");
        String scenario = args[0], member = args[1];
        FatalLatch.Failure first;
        if ("not-ready".equals(scenario)) {
            first = failure("roots-not-ready", () -> invokeInitial(member));
        } else if ("prefailed".equals(scenario)) {
            first = failure("state-decode-failed", () -> GbootBootstrap.requireStateDecoded(false));
            if (failure(first.code(), () -> invokeInitial(member)) != first)
                throw new AssertionError("Initial member replaced first failure");
        } else throw new IllegalArgumentException("Unknown scenario " + scenario);
        int checks = "prefailed".equals(scenario) ? 2 : 1;
        for (String selected : new String[] {"delete", "rename-null", "rename-relative", "rename-unreadable"}) {
            if (failure(first.code(), () -> invokeInitial(selected)) != first)
                throw new AssertionError("First failure identity changed for " + selected);
            checks++;
        }
        for (File[] pair : new File[][] {
                {null, new UnreadableFile()}, {new UnreadableFile(), null},
                {new File("/absolute/source"), new File("/absolute/destination")}}) {
            if (failure(first.code(), () -> invoke("rename", pair[0], pair[1])) != first)
                throw new AssertionError("Rename argument variant replaced first failure");
            checks++;
        }
        if (failure(first.code(), () -> GbootBootstrap.requireStateDecoded(false)) != first)
            throw new AssertionError("Mutation failure was not retained by the shared latch");
        checks++;
        System.out.println("PASS GbootMutationGate " + scenario + " " + member + " checks=" + checks
                + " (no initialized Context; no mutation success/provider claim)");
    }
}
