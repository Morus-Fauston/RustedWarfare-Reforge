package com.rustedwarfare.reforge.reference.boot;

/** One fixed packaged TSX basename alias; never resolves arbitrary traversal. */
public final class PackagedTilesetReference {
    private PackagedTilesetReference() { }
    public static String name(String raw) {
        GbootBootstrap.requireBackendPublished();
        if ("../../decoration.tsx".equals(raw)) {
            GbootBootstrap.checkAssetRead("tilesets/decoration.tsx");
            return "decoration.tsx";
        }
        return raw;
    }
}
