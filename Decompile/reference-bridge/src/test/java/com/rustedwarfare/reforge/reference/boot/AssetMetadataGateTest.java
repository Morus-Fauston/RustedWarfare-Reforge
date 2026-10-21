package com.rustedwarfare.reforge.reference.boot;

import com.rustedwarfare.reforge.reference.io.FatalLatch;

/** Real Gboot early failure and first-fatal checks; no initialized Android claim. */
public final class AssetMetadataGateTest {
    public static void main(String[] args) {
        FatalLatch.Failure first;
        try {
            if ("prior".equals(args[0])) GbootBootstrap.requireStateDecoded(false);
            else AssetMetadata.lastModified("builtin_mods/mega_builders/mod-info.txt");
            throw new AssertionError("missing rejection");
        } catch (FatalLatch.Failure failure) {
            first = failure;
            String expected = "prior".equals(args[0]) ? "state-decode-failed" : "roots-not-ready";
            if (!expected.equals(first.code())) throw new AssertionError(first.code());
        }
        for (String raw : new String[] {null, "", "/private/owned", "builtin_mods/mega_builders/mod-info.txt"}) {
            try { AssetMetadata.lastModified(raw); throw new AssertionError("resumed failed process"); }
            catch (FatalLatch.Failure failure) {
                if (failure != first) throw new AssertionError("first failure replaced");
            }
        }
        System.out.println("PASS real Gboot early gate " + args[0]);
    }
}
