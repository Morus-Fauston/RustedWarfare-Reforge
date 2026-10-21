package com.rustedwarfare.reforge.reference.boot;

/** Timestamp routing for the approved private derivative, not a filesystem fallback. */
public final class AssetMetadata {
    private AssetMetadata() { }

    public static long lastModified(String raw) {
        if (GbootBootstrap.isAssetPath(raw)) {
            GbootBootstrap.checkAssetRead(raw);
            // Packaged route has no mutable-file timestamp; original cache treats zero as uncacheable.
            return 0L;
        }
        return GbootBootstrap.privateLastModified(raw);
    }
}
