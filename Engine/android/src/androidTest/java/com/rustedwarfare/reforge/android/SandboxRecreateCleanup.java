package com.rustedwarfare.reforge.android;

import java.util.function.Consumer;

/** TEST-only cleanup orchestration, independent of the recreation business verdict. */
public final class SandboxRecreateCleanup {
    public static void poll(Runnable finishOwned,Runnable attach,Runnable read,Consumer<Throwable> failure) {
        attempt(finishOwned,failure);
        attempt(attach,failure);
        attempt(read,failure);
    }
    private static void attempt(Runnable action,Consumer<Throwable> failure) {
        try { action.run(); } catch(Throwable problem) { failure.accept(problem); }
    }
    public static boolean resolved(boolean requested,int creations,boolean attached,boolean oldReleased,boolean currentReleased) {
        return requested ? creations==2&&attached&&oldReleased&&currentReleased : currentReleased;
    }
}
