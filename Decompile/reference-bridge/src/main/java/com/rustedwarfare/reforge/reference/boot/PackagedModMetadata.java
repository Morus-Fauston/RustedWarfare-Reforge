package com.rustedwarfare.reforge.reference.boot;

/** Fixed immutable APK mod set whose optional Steam metadata is proven absent. */
public final class PackagedModMetadata {
    private PackagedModMetadata() { }

    public static boolean skipSteamProbe(String raw) {
        if (!GbootBootstrap.isAssetPath(raw)) return false;
        if ("builtin_mods/mega_builders".equals(GbootBootstrap.checkAssetList(raw))) return true;
        throw GbootBootstrap.unsupportedRouteFailure("packaged-mod-steam-not-approved");
    }
}
