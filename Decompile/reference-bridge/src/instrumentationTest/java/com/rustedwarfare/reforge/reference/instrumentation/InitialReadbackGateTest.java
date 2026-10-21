package com.rustedwarfare.reforge.reference.instrumentation;

public final class InitialReadbackGateTest {
    public static void main(String[] args) {
        InitialReadbackGate.requireInitial(false, true, true, 0, 1, true, true, true, false, true);
        int rejected = 0;
        for (int count : new int[] {0, 2, 256}) {
            try { InitialReadbackGate.requireInitial(false, true, true, 0, count, true, true, true, false, true); }
            catch (IllegalArgumentException expected) { rejected++; }
        }
        if (rejected != 3) throw new AssertionError("post table size accepted");
        System.out.println("PASS InitialReadbackGate actual-adapter-gate 4");
    }
}
