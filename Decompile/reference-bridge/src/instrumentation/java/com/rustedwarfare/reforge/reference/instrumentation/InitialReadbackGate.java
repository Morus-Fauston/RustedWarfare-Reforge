package com.rustedwarfare.reforge.reference.instrumentation;

/** Called with values collected from the complete original table, before success. */
public final class InitialReadbackGate {
    private InitialReadbackGate() { }

    public static void requireInitial(boolean loading, boolean ready, boolean mapReady, int preSize, int postSize,
            boolean sameInstance, boolean worldMatches, boolean registered, boolean destroyed, boolean counted) {
        InitialProtocol.require(postSize == 1, "initial-complete-table-not-one");
        InitialProtocol.requireSnapshot(loading, ready, mapReady, preSize, postSize, sameInstance, registered, destroyed, counted);
        InitialProtocol.require(worldMatches, "initial-world-mismatch");
    }
}
