package com.rustedwarfare.reforge.reference.boot;

/** Map read normalization only. Existing gates decide every route; no IO here. */
public final class CheckedReadRoute {
    private CheckedReadRoute() { }
    public static String normalize(String raw) {
        if (GbootBootstrap.isAssetPath(raw)) return GbootBootstrap.checkAssetRead(raw);
        return GbootBootstrap.normalizePrivateRaw(raw);
    }
}
