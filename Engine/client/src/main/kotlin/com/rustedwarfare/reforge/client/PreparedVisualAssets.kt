package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TileSetDefinition
import java.nio.file.Path
import java.util.Collections

data class VisualUnitImages(val body: Path, val back: Path? = null, val turret: Path? = null,
    val dead: Path? = null, val totalFrames: Int = 1, val logicalImages: UnitImageLogicalSizes? = null,
    val leg: Path? = null, val foot: Path? = null, val rotor: Path? = null,
    val weaponTurrets: Map<String, Path> = emptyMap(), val shadow: Path? = null, val shield: Path? = null,
    val leftWing: Path? = null, val rightWing: Path? = null,
    val beam: Path? = null, val beamStart: Path? = null, val beamEnd: Path? = null, val afterburnTrail: Path? = null,
    val arm1: Path? = null, val arm2: Path? = null, val slotOverlay: Path? = null)

/** Immutable paths and generation identity, with no core or GL ownership. */
class PreparedVisualAssets private constructor(val selection: VisualAssetSelection, val profileId: String, val identity: String,
    units: Map<String, VisualUnitImages>, terrain: Map<String, Path>, private val validate: () -> Unit,
    val resolvedImages: ResolvedResourceImages?, val resolvedSounds: ResolvedResourceSounds?,
    private val boundContent: ContentPackChoice? = null,
    val uiTheme: UiTheme = UiTheme.DEFAULT,
    val uiThemeAssetResolver: UiThemeAssetResolver = UiThemeAssetResolver(),
    val uiThemePatches: List<UiThemePatch> = emptyList()) {
    constructor(selection: VisualAssetSelection, profileId: String, identity: String,
        units: Map<String, VisualUnitImages>, terrain: Map<String, Path>, validate: () -> Unit = {}) :
        this(selection, profileId, identity, units, terrain, validate, null, null, null, UiTheme.DEFAULT, UiThemeAssetResolver(), emptyList())

    val resourcePack: PreparedResourcePack = PreparedResourcePack.fromBindings(BuiltInResourcePacks.forSource(selection.source), units, terrain)
    val units: Map<String, VisualUnitImages> = Collections.unmodifiableMap(resourcePack.unitBindings.mapValues { (_, binding) ->
        binding.bindings().map { _, image ->
            BoundUnitImage(resourcePack.resolve(image.resource), image.logicalSize, image.totalFrames)
        }.visualImages()
    })
    val terrain: Map<String, Path> = Collections.unmodifiableMap(resourcePack.terrainBindings.mapValues { (_, id) -> resourcePack.resolve(id) })
    fun verify() = validate()
    /** Worker-only publication check; GL recovery verifies frozen generations without rereading user sources. */
    fun verifyForPublication() { verify(); resolvedImages?.layers?.forEach { it.verifyInputs() } }

    fun withConfiguration(selection: VisualAssetSelection, profileId: String = this.profileId, identity: String = this.identity) =
        PreparedVisualAssets(selection, profileId, identity, units, terrain, validate, resolvedImages, resolvedSounds, boundContent, uiTheme, uiThemeAssetResolver, uiThemePatches)

    fun withResourceSounds(resolved: ResolvedResourceSounds) =
        PreparedVisualAssets(selection, profileId, identity, units, terrain, validate, resolvedImages, resolved, boundContent, uiTheme, uiThemeAssetResolver, uiThemePatches)

    internal fun isBoundTo(choice: ContentPackChoice): Boolean = boundContent?.unitId == choice.unitId &&
        boundContent.content.sources == choice.content.sources

    internal fun withContentBinding(choice: ContentPackChoice) = PreparedVisualAssets(selection, profileId, identity,
        units, terrain, validate, resolvedImages, resolvedSounds, choice, uiTheme, uiThemeAssetResolver, uiThemePatches)

    fun withUiTheme(theme: UiTheme, assetResolver: UiThemeAssetResolver = this.uiThemeAssetResolver, patches: List<UiThemePatch> = this.uiThemePatches) =
        PreparedVisualAssets(selection, profileId, identity, units, terrain, validate, resolvedImages, resolvedSounds,
            boundContent, theme, assetResolver, patches)

    /** Re-composes resource-pack theme patches on top of any built-in base theme (DARK / WHITE / BONE). */
    fun composeUiTheme(base: UiTheme): UiTheme =
        if (uiThemePatches.isEmpty() && base == UiTheme.DEFAULT) uiTheme
        else UiThemeComposer.compose(base = base, overridesHighToLow = uiThemePatches)

    /** Pure binding of an already checked combination; fixed-generation checks remain explicit. */
    fun withResourceImages(resolved: ResolvedResourceImages, verifyAdditional: () -> Unit = {}): PreparedVisualAssets {
        fun image(id: ResourceId) = requireNotNull(resolved.images[id]) { "RESOURCE_PACK_BINDING_INVALID: $id: Missing resolved resource" }
        val mapped = resourcePack.unitBindings.mapValues { (_, binding) ->
            binding.bindings().map { _, bound ->
                val resolvedImage = image(bound.resource)
                BoundUnitImage(resolvedImage.path,
                    ImageLogicalSize(resolvedImage.declaration.frameWidth, resolvedImage.declaration.frameHeight),
                    resolvedImage.declaration.totalFrames)
            }.visualImages()
        }
        val tiles = resourcePack.terrainBindings.mapValues { (_, id) -> image(id).path }
        return PreparedVisualAssets(selection, profileId, identity, mapped, tiles,
            { this.verify(); verifyAdditional() }, resolved, resolvedSounds?.let { ResourceSoundComposer.compose(resolved) },
            boundContent, uiTheme, uiThemeAssetResolver, uiThemePatches)
    }
}

/** Render-only atlas metadata; replacing it cannot create a core TileSetDefinition. */
data class VisualTileAtlas(
    val firstGid: Int,
    val name: String,
    val tileWidth: Int,
    val tileHeight: Int,
    val imagePath: Path,
    val blockedTileIds: Set<Int>,
    val tileCount: Int,
    val columns: Int,
    val spacing: Int,
    val margin: Int,
    val transparentColor: String?,
    val resourcePoolTileIds: Set<Int>,
    val pixelTileWidth: Int = tileWidth,
    val pixelTileHeight: Int = tileHeight,
    val imageSource: String = imagePath.fileName?.toString() ?: "",
    val tsxSource: String? = null,
    val isPlaceholderFallback: Boolean = false,
) {
    companion object {
        fun from(value: TileSetDefinition) = VisualTileAtlas(
            firstGid = value.firstGid,
            name = value.name,
            tileWidth = value.tileWidth,
            tileHeight = value.tileHeight,
            imagePath = value.imagePath,
            blockedTileIds = value.blockedTileIds,
            tileCount = value.tileCount,
            columns = value.columns,
            spacing = value.spacing,
            margin = value.margin,
            transparentColor = value.transparentColor,
            resourcePoolTileIds = value.resourcePoolTileIds,
            imageSource = value.imageSource,
            tsxSource = value.tsxSource,
        )

        fun normalizeAtlasKey(raw: String): String {
            val cleaned = raw.lowercase()
                .replace('\\', '/')
                .substringAfterLast('/')
                .substringBeforeLast('.')
                .replace(Regex("[^a-z0-9_.-]+"), "-")
                .trim('-', '.')
                .ifEmpty { "tileset" }
            return cleaned
        }
    }

    /** Stable resource-pack override identifier per ADR-013 (e.g. reforge:tileset/longgrass). */
    fun normalizedResourceKey(): String {
        val raw = imageSource.ifBlank { name }
        return "reforge:tileset/${normalizeAtlasKey(raw)}"
    }

    /** Legacy 6 built-in test layout key when matched, or null for arbitrary standard TMX tilesets. */
    fun legacyLayoutOrNull(): String? {
        if (tileWidth != 20 || tileHeight != 20 || spacing != 0 || margin != 0) return null
        return when {
            name == "naval-ground" && columns == 5 && tileCount == 40 && resourcePoolTileIds.isEmpty() &&
                (blockedTileIds.isEmpty() || blockedTileIds == setOf(2)) -> "naval-ground-40"
            columns == 5 && tileCount == 40 && blockedTileIds == setOf(16) && resourcePoolTileIds.isEmpty() -> "controlled-ground-40"
            columns == 7 && tileCount == 35 && blockedTileIds == setOf(7, 8, 21, 22, 28, 29) && resourcePoolTileIds.isEmpty() -> "controlled-rock-35"
            columns == 5 && tileCount == 5 && blockedTileIds == setOf(1, 2) && resourcePoolTileIds == setOf(4) -> "construction-strip-5"
            columns == 2 && tileCount == 2 && blockedTileIds == setOf(1) && resourcePoolTileIds.isEmpty() -> "controlled-strip-2"
            columns == 4 && tileCount == 4 && blockedTileIds == setOf(2, 3) && resourcePoolTileIds.isEmpty() -> "demo-strip-4"
            else -> null
        }
    }

    /** Retained for existing built-in test suite compatibility; dynamic TMX binding uses [legacyLayoutOrNull] & [normalizedResourceKey]. */
    fun supportedLayout(): String = legacyLayoutOrNull()
        ?: throw IllegalArgumentException("VISUAL_MAPPING_UNSUPPORTED: atlas $name ($columns/$tileCount)")
}
