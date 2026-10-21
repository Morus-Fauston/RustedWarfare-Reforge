package com.rustedwarfare.reforge.reference.instrumentation;

public final class DexEntryTest {
    public static void main(String[] args) {
        String[] yes = {"classes.dex", "classes2.dex", "classes10.dex"};
        String[] no = {"classesX.dex", "classes2X.dex", "classes\\.dex", "classes2\\.dex", "classes.dex/other", "other/classes.dex"};
        for (String name : yes) if (!ReadbackEvidence.isDexEntry(name)) throw new AssertionError("missing " + name);
        for (String name : no) if (ReadbackEvidence.isDexEntry(name)) throw new AssertionError("accepted " + name);
        System.out.println("PASS DexEntry checks=9");
    }
}
