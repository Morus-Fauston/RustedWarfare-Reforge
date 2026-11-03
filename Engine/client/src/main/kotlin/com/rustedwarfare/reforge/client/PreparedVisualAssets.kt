package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TileSetDefinition
import java.nio.file.Path
import java.util.Collections

data class VisualUnitImages(val body: Path, val back: Path? = null, val turret: Path? = null,
    val dead: Path? = null, val totalFrames: Int = 1, val logicalImages: UnitImageLogicalSizes? = null,
    val leg: Path? = null, val foot: Path? = null, val rotor: Path? = null)

/** Immutable paths and generation identity, with no core or GL ownership. */
class PreparedVisualAssets private constructor(val selection: VisualAssetSelection, val profileId: String, val identity: String,
    units: Map<String, VisualUnitImages>, terrain: Map<String, Path>, private val validate: () -> Unit,
    val resolvedImages: ResolvedResourceImages?, val resolvedSounds: ResolvedResourceSounds?) {
    constructor(selection: VisualAssetSelection, profileId: String, identity: String,
        units: Map<String, VisualUnitImages>, terrain: Map<String, Path>, validate: () -> Unit = {}) :
        this(selection, profileId, identity, units, terrain, validate, null, null)

    val resourcePack: PreparedResourcePack = PreparedResourcePack.fromBindings(BuiltInResourcePacks.forSource(selection.source), units, terrain)
    val units: Map<String, VisualUnitImages> = Collections.unmodifiableMap(resourcePack.unitBindings.mapValues { (_, binding) ->
        VisualUnitImages(resourcePack.resolve(binding.body), binding.back?.let(resourcePack::resolve),
            binding.turret?.let(resourcePack::resolve), binding.dead?.let(resourcePack::resolve), binding.totalFrames, binding.logicalImages,
            binding.leg?.let(resourcePack::resolve), binding.foot?.let(resourcePack::resolve), binding.rotor?.let(resourcePack::resolve))
    })
    val terrain: Map<String, Path> = Collections.unmodifiableMap(resourcePack.terrainBindings.mapValues { (_, id) -> resourcePack.resolve(id) })
    fun verify() = validate()
    /** Worker-only publication check; GL recovery verifies frozen generations without rereading user sources. */
    fun verifyForPublication() { verify(); resolvedImages?.layers?.forEach { it.verifyInputs() } }

    fun withConfiguration(selection: VisualAssetSelection, profileId: String = this.profileId, identity: String = this.identity) =
        PreparedVisualAssets(selection, profileId, identity, units, terrain, validate, resolvedImages, resolvedSounds)

    fun withResourceSounds(resolved: ResolvedResourceSounds) =
        PreparedVisualAssets(selection, profileId, identity, units, terrain, validate, resolvedImages, resolved)

    /** Pure binding of an already checked combination; fixed-generation checks remain explicit. */
    fun withResourceImages(resolved: ResolvedResourceImages, verifyAdditional: () -> Unit = {}): PreparedVisualAssets {
        fun image(id: ResourceId) = requireNotNull(resolved.images[id]) { "RESOURCE_PACK_BINDING_INVALID: $id: Missing resolved resource" }
        fun size(image: PreparedResourceImage) = ImageLogicalSize(image.declaration.frameWidth, image.declaration.frameHeight)
        val mapped = resourcePack.unitBindings.mapValues { (_, binding) ->
            val body = image(binding.body)
            val back = binding.back?.let(::image)
            val turret = binding.turret?.let(::image)
            val dead = binding.dead?.let(::image)
            val leg = binding.leg?.let(::image)
            val foot = binding.foot?.let(::image)
            val rotor = binding.rotor?.let(::image)
            VisualUnitImages(body.path, back?.path, turret?.path, dead?.path, body.declaration.totalFrames,
                UnitImageLogicalSizes(size(body), back?.let(::size), turret?.let(::size), dead?.let(::size), leg?.let(::size), foot?.let(::size), rotor?.let(::size)),
                leg?.path, foot?.path, rotor?.path)
        }
        val tiles = resourcePack.terrainBindings.mapValues { (_, id) -> image(id).path }
        return PreparedVisualAssets(selection, profileId, identity, mapped, tiles,
            { this.verify(); verifyAdditional() }, resolved, resolvedSounds?.let { ResourceSoundComposer.compose(resolved) })
    }
}

/** Render-only atlas metadata; replacing it cannot create a core TileSetDefinition. */
data class VisualTileAtlas(val firstGid: Int, val name: String, val tileWidth: Int, val tileHeight: Int,
    val imagePath: Path, val blockedTileIds: Set<Int>, val tileCount: Int, val columns: Int,
    val spacing: Int, val margin: Int, val transparentColor: String?, val resourcePoolTileIds: Set<Int>,
    val pixelTileWidth: Int = tileWidth, val pixelTileHeight: Int = tileHeight) {
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
