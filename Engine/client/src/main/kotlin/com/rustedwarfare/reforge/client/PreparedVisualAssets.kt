package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TileSetDefinition
import java.nio.file.Path
import java.util.Collections

data class VisualUnitImages(val body: Path, val back: Path? = null, val turret: Path? = null,
    val dead: Path? = null, val totalFrames: Int = 1)

/** Immutable paths and generation identity, with no core or GL ownership. */
class PreparedVisualAssets(val selection: VisualAssetSelection, val profileId: String, val identity: String,
    units: Map<String, VisualUnitImages>, terrain: Map<String, Path>, private val validate: () -> Unit = {}) {
    val units: Map<String, VisualUnitImages> = Collections.unmodifiableMap(LinkedHashMap(units))
    val terrain: Map<String, Path> = Collections.unmodifiableMap(LinkedHashMap(terrain))
    fun verify() = validate()
}

/** Render-only atlas metadata; replacing it cannot create a core TileSetDefinition. */
data class VisualTileAtlas(val firstGid: Int, val name: String, val tileWidth: Int, val tileHeight: Int,
    val imagePath: Path, val blockedTileIds: Set<Int>, val tileCount: Int, val columns: Int,
    val spacing: Int, val margin: Int, val transparentColor: String?, val resourcePoolTileIds: Set<Int>) {
    companion object {
        fun from(value: TileSetDefinition) = VisualTileAtlas(value.firstGid, value.name, value.tileWidth, value.tileHeight,
            value.imagePath, value.blockedTileIds, value.tileCount, value.columns, value.spacing, value.margin,
            value.transparentColor, value.resourcePoolTileIds)
    }

    fun supportedLayout(): String {
        require(tileWidth == 20 && tileHeight == 20 && spacing == 0 && margin == 0) { "VISUAL_MAPPING_UNSUPPORTED: atlas $name" }
        return when {
            columns == 5 && tileCount == 40 && blockedTileIds == setOf(16) && resourcePoolTileIds.isEmpty() -> "controlled-ground-40"
            columns == 7 && tileCount == 35 && blockedTileIds == setOf(7, 8, 21, 22, 28, 29) && resourcePoolTileIds.isEmpty() -> "controlled-rock-35"
            columns == 5 && tileCount == 5 && blockedTileIds == setOf(1, 2) && resourcePoolTileIds == setOf(4) -> "construction-strip-5"
            columns == 2 && tileCount == 2 && blockedTileIds == setOf(1) && resourcePoolTileIds.isEmpty() -> "controlled-strip-2"
            columns == 4 && tileCount == 4 && blockedTileIds == setOf(2, 3) && resourcePoolTileIds.isEmpty() -> "demo-strip-4"
            else -> throw IllegalArgumentException("VISUAL_MAPPING_UNSUPPORTED: atlas $name ($columns/$tileCount)")
        }
    }
}
