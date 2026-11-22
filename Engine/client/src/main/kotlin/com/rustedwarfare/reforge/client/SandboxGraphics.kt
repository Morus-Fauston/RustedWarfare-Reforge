package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.XmlReader
import com.rustedwarfare.reforge.core.content.GroundRules
import com.rustedwarfare.reforge.core.content.MapVisualLayerKind
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.UnitObservation
import java.nio.file.Path
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ProjectileAppearance
import kotlin.math.abs

internal fun unitDrawOrder(units: List<UnitObservation>): List<UnitObservation> {
    fun layer(unit: UnitObservation): Int = when {
        unit.flightPose != null && unit.height > 0.0 -> 3
        !unit.alive -> 0
        unit.amphibiousJet?.surfacePresentation == false -> 1
        unit.amphibiousJet?.surfacePresentation == true || unit.flightPose != null -> 3
        else -> 2
    }
    val byId = units.associateBy { it.unitId }
    return units.sortedWith(compareBy({ unit ->
        val host = unit.attachment?.let { byId[it.parentUnitId] }
        layer(host ?: unit)
    }, { it.attachment != null }))
}

class SandboxGraphics private constructor(
    private val resources: SandboxRenderResources,
    fullBattlefield: Boolean,
    private val uiTheme: UiTheme = UiTheme.DEFAULT,
) : AutoCloseable {
    val projection = MapProjection(resources.widthTiles.toDouble() * resources.tileWidth,
        resources.heightTiles.toDouble() * resources.tileHeight,
        if (fullBattlefield) MapArea(0.0, 0.0, 960.0, 640.0) else MapArea(16.0, 64.0, 704.0, 512.0),
        fillViewport = fullBattlefield)
    private val minimapBounds = if (fullBattlefield) DESKTOP_MINIMAP_AREA else MapArea(752.0, 456.0, 176.0, 112.0)
    var minimapWellArea: MapArea = minimapBounds
        private set
    val minimap = Minimap(projection.worldWidth, projection.worldHeight,
        Minimap.fitCentered(minimapBounds, projection.worldWidth, projection.worldHeight))

    /** Updates minimap stage bounds inside the right-rail opaque well (HUD Spec §6: width=232, height clamped [0.5, 1.0]*w). */
    fun updateMinimapAspect(screenWidth: Int, screenHeight: Int, stageWidth: Float, stageHeight: Float) {
        if (screenWidth <= 0 || screenHeight <= 0 || stageWidth <= 0f || stageHeight <= 0f) return
        val pxPerUnitX = screenWidth.toDouble() / stageWidth.toDouble()
        val pxPerUnitY = screenHeight.toDouble() / stageHeight.toDouble()
        val sideW = UiTokens.SIDEBAR_WIDTH.coerceAtMost((stageWidth - UiTokens.SCREEN_MARGIN * 2f).coerceAtLeast(160f))
        val wellH = HudSidebarProjection.minimapHeightRef(projection.worldWidth, projection.worldHeight, sideW)
            .coerceAtMost((stageHeight * 0.45f).coerceAtLeast(80f))
        val wellBounds = MapArea(
            left = (stageWidth - sideW).coerceAtLeast(0f).toDouble(),
            bottom = (stageHeight - wellH).coerceAtLeast(0f).toDouble(),
            width = sideW.toDouble(),
            height = wellH.toDouble(),
        )
        minimapWellArea = wellBounds
        minimap.updateArea(Minimap.fitCentered(wellBounds, projection.worldWidth, projection.worldHeight, pxPerUnitX, pxPerUnitY))
    }
    private val owned = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val tileAverageColors = mutableMapOf<Int, TileAverageColor>()
    internal var loadedTerrainTextureCount: Int = 0
        private set
    private var minimapPixmap: Pixmap? = null
    private var minimapTexture: Texture? = null
    private var minimapVisibilityState: ByteArray? = null
    private var minimapGroundSnapshot: IntArray? = null
    private val resourcePoolFlags: BooleanArray = run {
        val count = resources.widthTiles * resources.heightTiles
        BooleanArray(count) { index ->
            val layers = resources.visualLayers
            layers.any { layer ->
                val clean = layer.cleanGidAt(index)
                val atlas = TilesetAtlasResolver.ownerOf(resources.tileSets, clean)
                atlas != null && (clean - atlas.firstGid) in atlas.resourcePoolTileIds
            }
        }
    }
    private val units = mutableMapOf<String, TextureRegion>()
    private val compositeUnitIcons = mutableMapOf<String, TextureRegion>()
    private val turrets = mutableMapOf<String, TextureRegion>()
    private val namedTurrets = mutableMapOf<Pair<String, String>, TextureRegion>()
    private data class Impact(val position: Position, val height: Double, val started: Long, val large: Boolean)
    private val impacts = mutableMapOf<String, Impact>()
    private data class FlameParticle(val position: Position, val height: Double, val started: Long, val smoke: Boolean)
    private val flameParticles = mutableMapOf<String, FlameParticle>()
    private var impactSample: String? = null
    private val corpses = mutableMapOf<String, TextureRegion>()
    private val backs = mutableMapOf<String, TextureRegion>()
    private val legs = mutableMapOf<String, TextureRegion>()
    private val feet = mutableMapOf<String, TextureRegion>()
    private val rotors = mutableMapOf<String, TextureRegion>()
    private val shadows = mutableMapOf<String, TextureRegion>()
    private val shields = mutableMapOf<String, TextureRegion>()
    private val wings = mutableMapOf<Pair<String, UnitImageSlot>, TextureRegion>()
    private val beamImages = mutableMapOf<Path, TextureRegion>()
    private val trailImages = mutableMapOf<Path, TextureRegion>()
    private val afterburnTrails = AfterburnTrailPresentation()
    private val nuclearPresentation = NuclearPresentation()
    private data class BlinkFlash(val from: Position, val to: Position, val step: Long)
    private val blinkOrigins = mutableMapOf<String, Position>()
    private val blinkFlashes = mutableMapOf<String, BlinkFlash>()
    private var blinkSample: String? = null
    private var playerColorShader: ShaderProgram? = null
    fun unitIcon(typeId: String): TextureRegion? = compositeUnitIcons[typeId]
        ?: resources.units[typeId]?.iconWeaponName?.let { namedTurrets[typeId to it] }
        ?: units[typeId]

    companion object {
        internal val DESKTOP_MINIMAP_AREA = MapArea(752.0, 464.0, 184.0, 160.0)

        fun load(
            resources: SandboxRenderResources,
            root: Path,
            fullBattlefield: Boolean = false,
            uiTheme: UiTheme = UiTheme.DEFAULT,
        ): SandboxGraphics =
            loadPrepared(resources, root, intArrayOf(), fullBattlefield, uiTheme)

        /** Consume worker-bound metadata; GL owns texture upload, completeness checks and release. */
        fun load(
            loaded: LoadedSandbox,
            root: Path,
            fullBattlefield: Boolean = false,
            uiTheme: UiTheme? = null,
        ): SandboxGraphics =
            loadPrepared(
                loaded.renderResources,
                root,
                loaded.scenario.map.groundGids,
                fullBattlefield,
                uiTheme ?: loaded.visuals?.uiTheme ?: UiTheme.DEFAULT,
            )

        fun load(scenario: Scenario, root: Path, visuals: PreparedVisualAssets? = null): SandboxGraphics {
            val resources = SandboxRenderResources.from(scenario)
            return loadPrepared(
                visuals?.let { resources.withVisuals(it) } ?: resources,
                root,
                scenario.map.groundGids,
                false,
                visuals?.uiTheme ?: UiTheme.DEFAULT,
            )
        }

        private fun loadPrepared(resources: SandboxRenderResources, root: Path, ground: IntArray,
                                 fullBattlefield: Boolean, uiTheme: UiTheme = UiTheme.DEFAULT): SandboxGraphics {
            val graphics = SandboxGraphics(resources, fullBattlefield, uiTheme)
            try {
                val activeGids = LinkedHashSet<Int>(256)
                if (resources.visualLayers.isNotEmpty()) {
                    activeGids.addAll(TilesetAtlasResolver.collectActiveVisualGids(resources.visualLayers))
                }
                for (gid in ground) {
                    val clean = GroundRules.stripFlipFlags(gid)
                    if (clean != 0) activeGids.add(clean)
                }
                graphics.loadImages(root, activeGids)
                for (gid in activeGids) {
                    require(graphics.tiles.containsKey(gid)) { "Missing tile image for gid=$gid" }
                }
                graphics.playerColorShader = MatchPlayerColors.createPlayerColorShader()
                return graphics
            } catch (failure: Exception) {
                try { graphics.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private fun texture(path: Path, transparency: String? = null): Texture {
        val pixmap = Pixmap(Gdx.files.absolute(path.toString()))
        try {
            if (transparency != null) {
                val rgb = transparency.removePrefix("#").toInt(16)
                pixmap.blending = Pixmap.Blending.None
                for (y in 0 until pixmap.height) for (x in 0 until pixmap.width) {
                    if (pixmap.getPixel(x, y) ushr 8 == rgb) pixmap.drawPixel(x, y, 0)
                }
            }
            return Texture(pixmap).also { owned += it; it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } finally { pixmap.dispose() }
    }

    private fun loadParsedTiles(activeGids: Set<Int>) {
        // ADR-013: Only load atlases actually referenced by at least one non-zero GID in visual layers.
        // If activeGids is empty (e.g. direct unit-test call with empty ground), load all declared atlases.
        val atlasesToLoad = if (activeGids.isEmpty()) resources.tileSets
        else TilesetAtlasResolver.activeAtlases(resources.tileSets, activeGids)

        data class CachedAtlasUpload(val texture: Texture, val pixmap: Pixmap)
        val uploaded = linkedMapOf<TilesetAtlasResolver.TextureUploadKey, CachedAtlasUpload>()
        try {
            for (definition in atlasesToLoad) {
                val key = TilesetAtlasResolver.textureUploadKey(definition)
                val normalizedPath = key.path
                val normalizedTrans = key.normalizedTransparentColor
                val cached = uploaded.getOrPut(key) {
                    val pixmap = Pixmap(Gdx.files.absolute(normalizedPath.toString()))
                    try {
                        if (normalizedTrans != null) {
                            val rgb = normalizedTrans.toIntOrNull(16)
                                ?: throw IllegalArgumentException("Invalid transparent color: ${definition.transparentColor}")
                            pixmap.blending = Pixmap.Blending.None
                            for (y in 0 until pixmap.height) for (x in 0 until pixmap.width) {
                                if (pixmap.getPixel(x, y) ushr 8 == rgb) pixmap.drawPixel(x, y, 0)
                            }
                        }
                        val tex = Texture(pixmap).also {
                            owned += it
                            it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                        }
                        loadedTerrainTextureCount++
                        CachedAtlasUpload(tex, pixmap)
                    } catch (failure: Throwable) {
                        pixmap.dispose()
                        throw failure
                    }
                }
                val effectiveMargin = if (definition.isPlaceholderFallback) 0 else definition.margin
                val effectiveSpacing = if (definition.isPlaceholderFallback) 0 else definition.spacing
                val cols = definition.columns.coerceAtLeast(1)
                val stepX = (definition.pixelTileWidth + effectiveSpacing).coerceAtLeast(1)
                val stepY = (definition.pixelTileHeight + effectiveSpacing).coerceAtLeast(1)
                val availCols = ((cached.pixmap.width - 2 * effectiveMargin + effectiveSpacing) / stepX).coerceAtLeast(1)
                val availRows = ((cached.pixmap.height - 2 * effectiveMargin + effectiveSpacing) / stepY).coerceAtLeast(1)
                val maxCapacity = availCols * availRows
                for (index in 0 until definition.tileCount) {
                    val gid = definition.firstGid + index
                    if (activeGids.isNotEmpty() && gid !in activeGids) continue
                    // When an atlas fell back to a smaller placeholder biome strip (e.g. 60x40 = 6 tiles),
                    // wrap tile index cleanly within the fallback texture bounds so no out-of-bounds UV occurs.
                    val fitsDirect = !definition.isPlaceholderFallback && cols <= availCols && (index / cols) < availRows
                    val sampleIdx = if (fitsDirect) index else (index % maxCapacity)
                    val sampleCols = if (fitsDirect) cols else availCols
                    val rx = (effectiveMargin + (sampleIdx % sampleCols) * stepX).coerceIn(0, (cached.pixmap.width - 1).coerceAtLeast(0))
                    val ry = (effectiveMargin + (sampleIdx / sampleCols) * stepY).coerceIn(0, (cached.pixmap.height - 1).coerceAtLeast(0))
                    val regionW = definition.pixelTileWidth.coerceAtMost(cached.pixmap.width - rx).coerceAtLeast(1)
                    val regionH = definition.pixelTileHeight.coerceAtMost(cached.pixmap.height - ry).coerceAtLeast(1)
                    // Original misc.tsx tile id=9 (random tree marker) contains a red '?' editor placeholder;
                    // substitute clean tree tile id=10 from the same misc atlas when present so live maps look clean.
                    val isMiscRandomTreeMarker = !definition.isPlaceholderFallback &&
                        index == 9 &&
                        (definition.name.equals("misc", ignoreCase = true) ||
                            definition.imageSource.replace('\\', '/').substringAfterLast('/').equals("misc.png", ignoreCase = true)) &&
                        definition.tileCount > 10 &&
                        (10 / sampleCols) < availRows
                    val finalRx = if (isMiscRandomTreeMarker) {
                        (effectiveMargin + (10 % sampleCols) * stepX).coerceIn(0, (cached.pixmap.width - 1).coerceAtLeast(0))
                    } else rx
                    val finalRy = if (isMiscRandomTreeMarker) {
                        (effectiveMargin + (10 / sampleCols) * stepY).coerceIn(0, (cached.pixmap.height - 1).coerceAtLeast(0))
                    } else ry
                    val finalW = definition.pixelTileWidth.coerceAtMost(cached.pixmap.width - finalRx).coerceAtLeast(1)
                    val finalH = definition.pixelTileHeight.coerceAtMost(cached.pixmap.height - finalRy).coerceAtLeast(1)
                    tiles[gid] = TextureRegion(cached.texture, finalRx, finalRy, finalW, finalH)
                    tileAverageColors[gid] = TerrainTileColorSampler.samplePixmapTile(cached.pixmap, finalRx, finalRy, finalW, finalH)
                }
            }
        } finally {
            uploaded.values.forEach { it.pixmap.dispose() }
        }
    }

    private fun loadLegacyTiles(root: Path) {
        val mapFile = root.resolve("maps/p001-controlled.tmx")
        val mapXml = XmlReader().parse(Gdx.files.absolute(mapFile.toString()))
        for (reference in mapXml.getChildrenByName("tileset")) {
            val firstGid = reference.getIntAttribute("firstgid")
            val definition = resources.tileSets.single { it.firstGid == firstGid }
            val xml = reference.getAttribute("source", null)?.let {
                XmlReader().parse(Gdx.files.absolute(mapFile.parent.resolve(it).normalize().toString()))
            } ?: reference
            val image = xml.getChildByName("image") ?: error("Missing tileset image: $mapFile gid=$firstGid")
            val texture = texture(definition.imagePath, image.getAttribute("trans", null))
            val w = definition.tileWidth
            val h = definition.tileHeight
            val margin = xml.getIntAttribute("margin", 0)
            val spacing = xml.getIntAttribute("spacing", 0)
            val imageWidth = image.getIntAttribute("width", texture.width)
            val imageHeight = image.getIntAttribute("height", texture.height)
            val columns = xml.getIntAttribute("columns", (imageWidth - 2 * margin + spacing) / (w + spacing))
            val count = xml.getIntAttribute("tilecount", columns * ((imageHeight - 2 * margin + spacing) / (h + spacing)))
            require(columns > 0 && count > 0) { "Invalid image layout: ${definition.imagePath}" }
            for (index in 0 until count) tiles[firstGid + index] = TextureRegion(texture,
                margin + index % columns * (w + spacing), margin + index / columns * (h + spacing), w, h)
        }
    }

    private fun loadImages(root: Path, activeGids: Set<Int>) {
        // Parsed content carries its immutable atlas layout across initial load and GL recovery.
        // Retain the legacy constructor's unspecified-layout behavior for existing callers.
        if (resources.tileSets.all { it.columns > 0 && it.tileCount != Int.MAX_VALUE }) loadParsedTiles(activeGids)
        else loadLegacyTiles(root)
        val turretImages = mutableMapOf<Path, TextureRegion>()
        fun turretRegion(path: Path) = turretImages.getOrPut(path) { TextureRegion(texture(path)) }
        for ((id, definition) in resources.units) {
            val texture = texture(definition.imagePath)
            require(texture.width % definition.totalFrames == 0) { "Invalid unit image frames: ${definition.imagePath}" }
            units[id] = TextureRegion(texture, 0, 0, texture.width / definition.totalFrames, texture.height)
            definition.turretImagePath?.let { turrets[id] = turretRegion(it) }
            for ((name, weapon) in definition.weaponImages)
                if (!weapon.invisible) weapon.imagePath?.let { namedTurrets[id to name] = turretRegion(it) }
            definition.deadImagePath?.let { corpses[id] = TextureRegion(texture(it)) }
            definition.backImagePath?.let { backs[id] = TextureRegion(texture(it)) }
            definition.legImagePath?.let { legs[id] = TextureRegion(texture(it)) }
            definition.footImagePath?.let { feet[id] = TextureRegion(texture(it)) }
            definition.rotorImagePath?.let { rotors[id] = TextureRegion(texture(it)) }
            definition.shadowImagePath?.let { shadows[id] = TextureRegion(texture(it)) }
            definition.shieldImagePath?.let { shields[id] = TextureRegion(texture(it)) }
            definition.leftWingImagePath?.let { wings[id to UnitImageSlot.LEFT_WING] = TextureRegion(texture(it)) }
            definition.rightWingImagePath?.let { wings[id to UnitImageSlot.RIGHT_WING] = TextureRegion(texture(it)) }
            definition.arm1ImagePath?.let { wings[id to UnitImageSlot.ARM1] = TextureRegion(texture(it)) }
            definition.arm2ImagePath?.let { wings[id to UnitImageSlot.ARM2] = TextureRegion(texture(it)) }
            definition.slotOverlayImagePath?.let { wings[id to UnitImageSlot.SLOT_OVERLAY] = TextureRegion(texture(it)) }
            for (path in listOfNotNull(definition.beamImagePath, definition.beamStartImagePath, definition.beamEndImagePath)) {
                beamImages[path] = TextureRegion(texture(path))
            }
            definition.beamImagePath?.let { beamImages.getValue(it).texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.Repeat) }
            definition.afterburnTrailImagePath?.let { trailImages.getOrPut(it) { TextureRegion(texture(it)) } }
        }
        buildCompositeUnitIcons()
    }

    private fun buildCompositeUnitIcons() {
        val rawCache = mutableMapOf<Pair<Path, Int>, CompositeUnitIconBuilder.RgbaBuffer?>()
        val loader: (Path, Int) -> CompositeUnitIconBuilder.RgbaBuffer? = { path, totalFrames ->
            rawCache.getOrPut(path to totalFrames) {
                val pm = Pixmap(Gdx.files.absolute(path.toString()))
                try {
                    val frames = totalFrames.coerceAtLeast(1)
                    val frameW = if (pm.width % frames == 0) pm.width / frames else pm.width
                    CompositeUnitIconBuilder.RgbaBuffer.fromPixmap(pm, 0, 0, frameW, pm.height)
                } finally {
                    pm.dispose()
                }
            }
        }
        val localPlayerRgb = resources.playerColorRgb("p1")
        for ((id, definition) in resources.units) {
            val layers = CompositeUnitIconBuilder.planLayers(definition, loader)
            val composed = CompositeUnitIconBuilder.compose(layers, localPlayerRgb) ?: continue
            val pixmap = CompositeUnitIconBuilder.toPixmap(composed)
            val tex = try {
                Texture(pixmap).also {
                    it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                    owned.add(it)
                }
            } finally {
                pixmap.dispose()
            }
            compositeUnitIcons[id] = TextureRegion(tex)
        }
    }

    fun draw(batch: SpriteBatch, frame: SandboxFrame, construction: ConstructionPreview? = null,
        selectedIds: Set<String> = emptySet()) {
        val bounds = TerrainTileColorSampler.visibleBounds(
            projection, resources.widthTiles, resources.heightTiles, resources.tileWidth, resources.tileHeight, padTiles = 1,
        )
        val drawW = (resources.tileWidth * projection.scale).toFloat()
        val drawH = (resources.tileHeight * projection.scale).toFloat()
        val layers = resources.visualLayers
        val hasVisualLayers = layers.isNotEmpty() && frame.terrain.size == resources.widthTiles * resources.heightTiles
        if (hasVisualLayers) {
            val groundLayer = layers.firstOrNull { it.kind == MapVisualLayerKind.GROUND }
            for (layer in layers) {
                for (row in bounds.minRow..bounds.maxRow) {
                    val rowOffset = row * resources.widthTiles
                    val y = projection.y((row + 1.0) * resources.tileHeight)
                    for (col in bounds.minCol..bounds.maxCol) {
                        val index = rowOffset + col
                        val cell = frame.terrain[index]
                        if (!cell.known) continue
                        val rawGid = if (layer === groundLayer) {
                            val cellGid = cell.gid ?: continue
                            val cleanCell = GroundRules.stripFlipFlags(cellGid)
                            val layerRaw = layer.rawGidAt(index)
                            if (GroundRules.stripFlipFlags(layerRaw) == cleanCell) layerRaw else cellGid
                        } else {
                            layer.rawGidAt(index)
                        }
                        val cleanGid = GroundRules.stripFlipFlags(rawGid)
                        if (cleanGid == 0) continue
                        val region = tiles[cleanGid] ?: continue
                        val shade = if (cell.visible) 1f else 0.3f
                        batch.setColor(shade, shade, shade, 1f)
                        val x = projection.x(col.toDouble() * resources.tileWidth)
                        val flags = GroundRules.flipFlags(rawGid)
                        if (flags == 0) {
                            batch.draw(region, x, y, drawW, drawH)
                        } else {
                            val (rot, sx, sy) = TerrainTileColorSampler.decodeTiledTransform(flags)
                            batch.draw(region, x, y, drawW * 0.5f, drawH * 0.5f, drawW, drawH, sx, sy, rot)
                        }
                    }
                }
            }
        } else {
            for (cell in frame.terrain) {
                if (cell.column !in bounds.minCol..bounds.maxCol || cell.row !in bounds.minRow..bounds.maxRow) continue
                val rawGid = cell.gid ?: continue
                val cleanGid = GroundRules.stripFlipFlags(rawGid)
                if (cleanGid == 0) continue
                val region = tiles[cleanGid] ?: continue
                val shade = if (cell.visible) 1f else 0.3f
                batch.setColor(shade, shade, shade, 1f)
                val x = projection.x(cell.column.toDouble() * resources.tileWidth)
                val y = projection.y((cell.row + 1.0) * resources.tileHeight)
                val flags = GroundRules.flipFlags(rawGid)
                if (flags == 0) {
                    batch.draw(region, x, y, drawW, drawH)
                } else {
                    val (rot, sx, sy) = TerrainTileColorSampler.decodeTiledTransform(flags)
                    batch.draw(region, x, y, drawW * 0.5f, drawH * 0.5f, drawW, drawH, sx, sy, rot)
                }
            }
        }
        batch.color = Color.WHITE
        for (drawing in afterburnTrails.project(frame.observation, resources)) {
            val region = trailImages.getValue(requireNotNull(drawing.images.afterburnTrailImagePath))
            val size = drawing.images.drawSize(UnitImageSlot.AFTERBURN_TRAIL, region.regionWidth, region.regionHeight)
            val width = (size.width * projection.scale * drawing.scale).toFloat()
            val height = (size.height * projection.scale * drawing.scale).toFloat()
            batch.setColor(1f, 227f / 255f, 43f / 255f, drawing.alpha)
            batch.draw(region, projection.x(drawing.center.x) - width / 2, projection.y(drawing.center.y) - height / 2,
                width / 2, height / 2, width, height, 1f, 1f, -drawing.heading.toFloat())
        }
        batch.color = Color.WHITE
        val visibleWeapons = frame.observation.weapons.groupBy { it.unitId }
        val mechFrames = MechPresentation.project(frame.observation, projection, resources).associateBy { it.unitId }
        val flightFrames = FlightPresentation.project(frame.observation, projection).associateBy { it.unitId }
        val waterFrames = WaterPresentation.project(frame.observation, projection).associateBy { it.unitId }
        val shader = playerColorShader
        var activePlayerRgb: Int? = null
        fun enablePlayerColor(rgb: Int) {
            if (shader == null) return
            if (batch.shader !== shader) {
                batch.shader = shader
                activePlayerRgb = null
            }
            if (activePlayerRgb != rgb) {
                batch.flush()
                shader.setUniformf(
                    MatchPlayerColors.UNIFORM_PLAYER_COLOR,
                    ((rgb ushr 16) and 0xFF) / 255f,
                    ((rgb ushr 8) and 0xFF) / 255f,
                    (rgb and 0xFF) / 255f,
                    1f,
                )
                activePlayerRgb = rgb
            }
        }
        fun disablePlayerColor() {
            if (batch.shader != null) {
                batch.shader = null
                activePlayerRgb = null
            }
        }
        try {
            for (unit in unitDrawOrder(frame.observation.units)) {
                val definition = resources.units.getValue(unit.typeId)
                if (!definition.isDrawn(unit)) continue
                val unitAlpha = if (unit.alive && unit.constructionProgress < 1f) .45f else 1f
                batch.setColor(1f, 1f, 1f, unitAlpha)
                val mech = mechFrames[unit.unitId]
                val flight = flightFrames[unit.unitId]
                val water = waterFrames[unit.unitId]
                val hover = HoverPresentation.project(unit, projection)
                val heightAdjustedCenter = unit.takeIf { it.amphibiousJet != null || it.attachment != null }?.displayedBodyPosition()
                val jetHeading = unit.amphibiousJet?.let {
                    visibleWeapons[unit.unitId]?.singleOrNull { it.weaponName == "body" }?.turretHeading ?: unit.heading
                }
                if (unit.alive && unit.flightPose != null && flight == null) continue
                if (unit.waterPose != null && water == null) continue
                if (unit.hoverPose != null && hover == null) continue

                val hasShadow = (flight?.drawShadow == true) || (hover?.drawShadow == true) ||
                    (unit.amphibiousJet != null && unit.alive && unit.height > 0.0) || (mech?.drawShadow == true)
                if (hasShadow && shadows[unit.typeId] != null) {
                    disablePlayerColor()
                }
                if (flight?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                    val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                    val sw = (size.width * projection.scale).toFloat()
                    val sh = (size.height * projection.scale).toFloat()
                    batch.draw(shadow, projection.x(unit.position.x) - sw / 2, projection.y(unit.position.y) - sh / 2,
                        sw / 2, sh / 2, sw, sh, 1f, 1f, -unit.heading.toFloat())
                }
                if (hover?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                    val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                    val sw = (size.width * projection.scale).toFloat()
                    val sh = (size.height * projection.scale).toFloat()
                    batch.draw(shadow, hover.shadowX - sw / 2, hover.shadowY - sh / 2,
                        sw / 2, sh / 2, sw, sh, 1f, 1f, hover.rotation)
                }
                if (unit.amphibiousJet != null && unit.alive && unit.height > 0.0) shadows[unit.typeId]?.let { shadow ->
                    val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                    val sw = (size.width * projection.scale).toFloat()
                    val sh = (size.height * projection.scale).toFloat()
                    batch.draw(shadow, projection.x(unit.position.x) - sw / 2, projection.y(unit.position.y) - sh / 2,
                        sw / 2, sh / 2, sw, sh, 1f, 1f,
                        -definition.amphibiousImageHeading(UnitImageSlot.SHADOW, requireNotNull(jetHeading)).toFloat())
                }
                if (mech?.drawShadow == true) shadows[unit.typeId]?.let { shadow ->
                    val size = definition.drawSize(UnitImageSlot.SHADOW, shadow.regionWidth, shadow.regionHeight)
                    val sw = (size.width * projection.scale).toFloat()
                    val sh = (size.height * projection.scale).toFloat()
                    batch.draw(shadow, mech.shadowX - sw / 2, mech.shadowY - sh / 2,
                        sw / 2, sh / 2, sw, sh, 1f, 1f, mech.rotation)
                }

                val ownerRgb = resources.playerColorRgb(unit.ownerPlayerId)
                val corpse = if (!unit.alive) corpses[unit.typeId] else null
                val region = corpse ?: units.getValue(unit.typeId)
                val slot = if (corpse != null) UnitImageSlot.DEAD else UnitImageSlot.BODY
                if (unit.alive) {
                    enablePlayerColor(ownerRgb)
                } else {
                    disablePlayerColor()
                }

                mech?.legs?.forEach { leg ->
                    val shaft = legs.getValue(unit.typeId)
                    val foot = feet.getValue(unit.typeId)
                    val vertical = leg.shaft
                    if (vertical != null) {
                        if (vertical.sourceHeight > 0) {
                            val crop = TextureRegion(shaft)
                            val span = shaft.v2 - shaft.v
                            crop.setRegion(shaft.u, shaft.v + span * vertical.sourceY / vertical.sourceImageHeight,
                                shaft.u2, shaft.v + span * (vertical.sourceY + vertical.sourceHeight) / vertical.sourceImageHeight)
                            batch.draw(crop, vertical.x - vertical.width / 2, vertical.y - vertical.height / 2,
                                vertical.width / 2, vertical.height / 2, vertical.width, vertical.height, 1f, 1f, vertical.rotation)
                        }
                    } else {
                        val shaftSize = definition.drawSize(UnitImageSlot.LEG, shaft.regionWidth, shaft.regionHeight)
                        val thickness = (shaftSize.height * projection.scale).toFloat()
                        batch.draw(shaft, leg.anchorX, leg.anchorY - thickness / 2, 0f, thickness / 2,
                            leg.length, thickness, 1f, 1f, leg.rotation)
                    }
                    val footSize = definition.drawSize(UnitImageSlot.FOOT, foot.regionWidth, foot.regionHeight)
                    val fw = (footSize.width * projection.scale).toFloat()
                    val fh = (footSize.height * projection.scale).toFloat()
                    batch.draw(foot, leg.footX - fw / 2, leg.footY - fh / 2, fw / 2, fh / 2,
                        fw, fh, 1f, 1f, leg.footRotation)
                }
                if (unit.alive) backs[unit.typeId]?.let { back ->
                    val size = definition.drawSize(UnitImageSlot.BACK, back.regionWidth, back.regionHeight)
                    val bw = (size.width * projection.scale).toFloat()
                    val bh = (size.height * projection.scale).toFloat()
                    batch.draw(back, projection.x(unit.position.x) - bw / 2, projection.y(unit.position.y) - bh / 2,
                        bw / 2, bh / 2, bw, bh, 1f, 1f, -unit.heading.toFloat())
                }
                val size = definition.drawSize(slot, region.regionWidth, region.regionHeight)
                val w = (size.width * projection.scale).toFloat()
                val h = (size.height * projection.scale).toFloat()
                val imageHeading = jetHeading?.let { definition.amphibiousImageHeading(slot, it) } ?: unit.heading
                batch.draw(region, (flight?.bodyX ?: mech?.bodyX ?: water?.bodyX ?: hover?.bodyX ?: projection.x(heightAdjustedCenter?.x ?: unit.position.x)) - w / 2,
                    (flight?.bodyY ?: mech?.bodyY ?: water?.bodyY ?: hover?.bodyY ?: projection.y(heightAdjustedCenter?.y ?: unit.position.y)) - h / 2,
                    w / 2, h / 2, w, h, 1f, 1f, flight?.bodyRotation ?: mech?.rotation ?: water?.bodyRotation ?: hover?.rotation ?: -imageHeading.toFloat())
                if (unit.alive && unit.unitId in selectedIds) {
                    wings[unit.typeId to UnitImageSlot.SLOT_OVERLAY]?.let { overlay ->
                        disablePlayerColor()
                        val overlaySize = definition.drawSize(UnitImageSlot.SLOT_OVERLAY, overlay.regionWidth, overlay.regionHeight)
                        val width = (overlaySize.width * projection.scale).toFloat()
                        val height = (overlaySize.height * projection.scale).toFloat()
                        val center = unit.displayedBodyPosition()
                        batch.setColor(1f, 1f, 1f, .55f)
                        batch.draw(overlay, projection.x(center.x) - width / 2, projection.y(center.y) - height / 2,
                            width / 2, height / 2, width, height, 1f, 1f, -unit.heading.toFloat())
                        batch.setColor(1f, 1f, 1f, unitAlpha)
                        enablePlayerColor(ownerRgb)
                    }
                }
                for (drawing in definition.amphibiousWings(unit, visibleWeapons[unit.unitId].orEmpty()) + definition.nuclearDoors(unit)) {
                    val wing = wings.getValue(unit.typeId to drawing.slot)
                    val wingSize = definition.drawSize(drawing.slot, wing.regionWidth, wing.regionHeight)
                    val ww = (wingSize.width * projection.scale).toFloat()
                    val wh = (wingSize.height * projection.scale).toFloat()
                    batch.draw(wing, projection.x(drawing.center.x) - ww / 2, projection.y(drawing.center.y) - wh / 2,
                        ww / 2, wh / 2, ww, wh, 1f, 1f, -drawing.heading.toFloat())
                }
                flight?.let { pose ->
                    rotors[unit.typeId]?.let { rotor ->
                        val rotorSize = definition.drawSize(UnitImageSlot.ROTOR, rotor.regionWidth, rotor.regionHeight)
                        val rw = (rotorSize.width * projection.scale).toFloat()
                        val rh = (rotorSize.height * projection.scale).toFloat()
                        batch.draw(rotor, pose.rotorX - rw / 2, pose.rotorY - rh / 2, rw / 2, rh / 2,
                            rw, rh, 1f, 1f, pose.rotorRotation)
                    }
                }
                for (drawing in definition.turretDrawings(unit, visibleWeapons[unit.unitId].orEmpty())) {
                    val turret = drawing.weaponName?.let { namedTurrets.getValue(unit.typeId to it) } ?: turrets.getValue(unit.typeId)
                    val pivot = drawing.pivot
                    val turretSize = drawing.drawSize(turret.regionWidth, turret.regionHeight)
                    val tw = (turretSize.width * projection.scale).toFloat()
                    val th = (turretSize.height * projection.scale).toFloat()
                    batch.draw(turret, projection.x(pivot.x) - tw / 2, projection.y(pivot.y) - th / 2,
                        tw / 2, th / 2, tw, th, 1f, 1f, -drawing.heading.toFloat())
                }
                ShieldPresentation.image(unit, shields)?.let { drawing ->
                    disablePlayerColor()
                    val shield = drawing.resource
                    val shieldSize = definition.drawSize(UnitImageSlot.SHIELD, shield.regionWidth, shield.regionHeight)
                    val sw = (shieldSize.width * projection.scale).toFloat()
                    val sh = (shieldSize.height * projection.scale).toFloat()
                    val center = unit.displayedBodyPosition()
                    batch.setColor(1f, 1f, 1f, drawing.opacity)
                    batch.draw(shield, projection.x(center.x) - sw / 2, projection.y(center.y) - sh / 2, sw, sh)
                    batch.color = Color.WHITE
                }
            }
        } finally {
            disablePlayerColor()
            batch.color = Color.WHITE
        }
        for (drawing in BeamPresentation.project(frame.observation, resources)) {
            val x = projection.x(drawing.start.x)
            val y = projection.y(drawing.start.y)
            val endX = projection.x(drawing.end.x)
            val endY = projection.y(drawing.end.y)
            val length = kotlin.math.hypot(endX - x, endY - y)
            if (!listOf(x, y, endX, endY, length).all { it.isFinite() } || length == 0f) continue
            val images = drawing.images
            val body = beamImages.getValue(requireNotNull(images.beamImagePath))
            val size = images.drawSize(UnitImageSlot.BEAM, body.regionWidth, body.regionHeight)
            val width = (size.width * projection.scale).toFloat()
            val tileHeight = (size.height * projection.scale).toFloat()
            val repeated = TextureRegion(body)
            val phase = drawing.textureOffset / size.height.toFloat()
            repeated.setRegion(body.u, body.v + phase, body.u2, body.v + phase + length / tileHeight)
            val angle = Math.toDegrees(kotlin.math.atan2((endY - y).toDouble(), (endX - x).toDouble())).toFloat() - 90f
            batch.draw(repeated, x - width / 2, y, width / 2, 0f, width, length, 1f, 1f, angle)
            for ((path, slot, point) in listOf(
                Triple(requireNotNull(images.beamStartImagePath), UnitImageSlot.BEAM_START, x to y),
                Triple(requireNotNull(images.beamEndImagePath), UnitImageSlot.BEAM_END, endX to endY))) {
                val cap = beamImages.getValue(path)
                val capSize = images.drawSize(slot, cap.regionWidth, cap.regionHeight)
                val cw = (capSize.width * projection.scale).toFloat()
                val ch = (capSize.height * projection.scale).toFloat()
                batch.draw(cap, point.first - cw / 2, point.second - ch / 2, cw, ch)
            }
        }
        construction?.let { preview ->
            val definition = resources.units.getValue(preview.typeId)
            when (preview.state) {
                PlacementState.CLEAR -> batch.setColor(.6f, 1f, .6f, .5f)
                PlacementState.BLOCKED -> batch.setColor(1f, .4f, .4f, .5f)
                PlacementState.UNEXPLORED -> batch.setColor(1f, .85f, .4f, .5f)
            }
            for ((slot, region) in listOf(UnitImageSlot.BACK to backs[preview.typeId], UnitImageSlot.BODY to units[preview.typeId])) {
                if (region == null) continue
                val size = definition.drawSize(slot, region.regionWidth, region.regionHeight)
                val w = (size.width * projection.scale).toFloat()
                val h = (size.height * projection.scale).toFloat()
                batch.draw(region, projection.x(preview.position.x) - w / 2f,
                    projection.y(preview.position.y) - h / 2f, w, h)
            }
        }
        batch.color = Color.WHITE
    }

    internal fun drawSandboxPick(shapes: ShapeRenderer, frame: SandboxFrame, preview: SandboxPickPreview) {
        shapes.color = when (preview.mode) {
            is SandboxPick.Spawn -> Color.GOLD
            SandboxPick.Destroy -> Color.RED
            is SandboxPick.Health -> Color.CYAN
        }
        val x = preview.cursor.x.toFloat()
        val y = preview.cursor.y.toFloat()
        shapes.line(x - 7f, y, x - 2f, y)
        shapes.line(x + 2f, y, x + 7f, y)
        shapes.line(x, y - 7f, x, y - 2f)
        shapes.line(x, y + 2f, x, y + 7f)
        val target = frame.observation.units.firstOrNull { it.unitId == preview.targetId } ?: return
        val center = target.displayedBodyPosition()
        val radius = (resources.units.getValue(target.typeId).displayRadius * projection.scale).toFloat() + 4f
        shapes.circle(projection.x(center.x), projection.y(center.y), radius, 32)
    }

    /** Draws a subtle translucent rust fill under area-targeted cursor previews (e.g. nuclear blast radius, Spec §8.2). */
    fun drawGroundActionAreaFill(shapes: ShapeRenderer, preview: GroundActionPreview) {
        if (preview.areaRadius <= 0.0) return
        val x = projection.x(preview.position.x)
        val y = projection.y(preview.position.y)
        val r = (preview.areaRadius * projection.scale).toFloat()
        if (r <= 1f) return
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        if (preview.valid) {
            shapes.setColor(UiTokens.RUST.r, UiTokens.RUST.g, UiTokens.RUST.b, 0.14f)
        } else {
            shapes.setColor(UiTokens.FOE.r, UiTokens.FOE.g, UiTokens.FOE.b, 0.14f)
        }
        shapes.circle(x, y, r, 72)
        shapes.end()
    }

    fun drawOverlay(shapes: ShapeRenderer, frame: SandboxFrame, selectedIds: Set<String>, box: Pair<Position, Position>? = null,
                    construction: ConstructionPreview? = null, groundAction: GroundActionPreview? = null,
                    primaryId: String? = null) {
        shapes.color = Color(0.45f, 0.5f, 0.47f, 1f)
        shapes.rect(projection.left.toFloat(), projection.bottom.toFloat(), projection.width.toFloat(), projection.height.toFloat())
        for (factory in frame.observation.factoryQueues.filter { it.factoryId in selectedIds }) {
            val target = factory.rally ?: continue
            val unit = frame.observation.units.firstOrNull { it.unitId == factory.factoryId } ?: continue
            val x = projection.x(target.x); val y = projection.y(target.y)
            shapes.color = Color.CYAN
            shapes.line(projection.x(unit.position.x), projection.y(unit.position.y), x, y)
            shapes.line(x, y - 6f, x, y + 8f)
            shapes.line(x, y + 8f, x + 9f, y + 4f)
            shapes.line(x + 9f, y + 4f, x, y + 1f)
        }
        val selectedSlots = frame.observation.units.asSequence().filter { it.unitId in selectedIds }
            .associate { it.unitId to it.attachmentOverview?.selectedSlot }
        for (unit in frame.observation.units) {
            if (!resources.units.getValue(unit.typeId).isDrawn(unit)) continue
            val attachment = unit.attachment
            val showSlot = attachment != null && attachment.parentUnitId in selectedSlots
            if (attachment?.empty == true && !showSlot) continue
            val isSelected = MatchPlayerColors.shouldDrawSelectionRing(unit.unitId, selectedIds)
            val ringColor = if (unit.alive) resources.selectionRingColor(frame.observation.playerId, unit.ownerPlayerId) else Color.DARK_GRAY
            shapes.color = ringColor
            val radius = (resources.units.getValue(unit.typeId).displayRadius * projection.scale).toFloat()
            val center = unit.displayedBodyPosition()
            val x = projection.x(center.x)
            val y = projection.y(center.y)
            if (!x.isFinite() || !y.isFinite()) continue
            unit.constructionTargetId?.let { targetId ->
                frame.observation.units.firstOrNull { it.unitId == targetId && it.alive }?.let { target ->
                    shapes.color = if (unit.tasks.firstOrNull()?.kind ==
                        com.rustedwarfare.reforge.core.sim.UnitTaskKind.RECLAIM) Color.RED else Color.BLUE
                    shapes.line(x, y, projection.x(target.position.x), projection.y(target.position.y))
                    shapes.color = ringColor
                }
            }
            if (showSlot) {
                val active = selectedSlots[attachment.parentUnitId] == attachment.slot
                shapes.color = if (active) Color.CYAN else Color.WHITE
                shapes.circle(x, y, (if (active) 8f else 6f) * projection.scale.toFloat(), 16)
            } else if (attachment == null && isSelected) {
                val ringRadius = radius + 3.5f
                shapes.color = ringColor
                shapes.circle(x, y, ringRadius, 36)
                if (unit.unitId == primaryId && selectedIds.size > 1) {
                    shapes.color = Color(0.95f, 0.98f, 0.96f, 0.9f)
                    shapes.circle(x, y, ringRadius + 1.5f, 36)
                    shapes.color = ringColor
                }
            }
            val health = unit.health
            val maximum = unit.maxHealth
            if (MatchPlayerColors.shouldDrawHealthBar(unit.alive, attachment?.empty == true, health, maximum, isSelected)) {
                val ratio = (health!! / maximum!!).toFloat().coerceIn(0f, 1f)
                shapes.color = ringColor
                shapes.line(x - radius, y + radius + 4f, x - radius + 2f * radius * ratio, y + radius + 4f)
            }
            if (unit.alive) unit.shield?.let { shield ->
                if (MatchPlayerColors.shouldDrawShieldBar(unit.alive, shield.current, shield.maximum, isSelected)) {
                    shapes.color = Color.CYAN
                    shapes.line(x - radius, y + radius + 8f,
                        x - radius + 2f * radius * (shield.current / shield.maximum).coerceIn(0f, 1f), y + radius + 8f)
                    shapes.color = ringColor
                }
            }
            if (MatchPlayerColors.shouldDrawConstructionBar(unit.alive, unit.constructionProgress)) {
                shapes.color = Color.CYAN
                shapes.line(x - radius, y - radius - 9f, x - radius + 2f * radius * unit.constructionProgress.coerceIn(0f, 1f), y - radius - 9f)
                shapes.color = ringColor
            }
            if (unit.alive) unit.pointDefense?.let { defense ->
                if (MatchPlayerColors.shouldDrawPointDefenseBar(unit.alive, defense.energy, defense.exhausted, isSelected)) {
                    shapes.color = if (defense.exhausted) Color.ORANGE else Color.CYAN
                    shapes.line(x - radius, y + radius + 8f, x - radius + 2f * radius * defense.energy.coerceIn(0f, 1f), y + radius + 8f)
                    shapes.color = ringColor
                }
            }
            if (unit.alive) unit.energy?.let { energy ->
                if (MatchPlayerColors.shouldDrawEnergyBar(unit.alive, energy.current, energy.maximum, energy.recharging, isSelected)) {
                    shapes.color = if (energy.recharging) Color.ORANGE else Color.CYAN
                    shapes.line(x - radius, y + radius + 8f,
                        x - radius + 2f * radius * (energy.current / energy.maximum).coerceIn(0f, 1f), y + radius + 8f)
                    shapes.color = ringColor
                }
            }
            if (unit.alive && frame.observation.scenarioId == "p006-construction") {
                frame.observation.factoryQueues.firstOrNull { it.factoryId == unit.unitId }?.items?.firstOrNull()?.let { head ->
                    shapes.color = Color(.68f, .3f, .82f, 1f)
                    shapes.line(x - radius, y - radius - 5f, x - radius + 2f * radius * head.progress, y - radius - 5f)
                    shapes.color = ringColor
                }
            }
            if (isSelected) {
                if (unit.alive && unit.constructionProgress >= 1f && unit.ownerPlayerId == frame.observation.playerId)
                    resources.units.getValue(unit.typeId).repairRange?.let { range ->
                        shapes.color = Color(.25f, .65f, .45f, 1f)
                        shapes.circle(projection.x(unit.position.x), projection.y(unit.position.y),
                            (range * projection.scale).toFloat(), 96)
                    }
                if (unit.ownerPlayerId == frame.observation.playerId) {
                    shapes.color = Color.CYAN
                    var stored: Position? = null
                    for (task in unit.tasks) if (task.kind == com.rustedwarfare.reforge.core.sim.UnitTaskKind.PATROL) {
                        val point = checkNotNull(task.target)
                        val px = projection.x(point.x); val py = projection.y(point.y)
                        shapes.circle(px, py, 4f, 16)
                        stored?.let { shapes.line(projection.x(it.x), projection.y(it.y), px, py) }
                        stored = point
                    }
                    shapes.color = ringColor
                }
                shapes.color = ringColor
                var previous = unit.position
                for (point in unit.pathPoints) {
                    shapes.line(projection.x(previous.x), projection.y(previous.y), projection.x(point.x), projection.y(point.y))
                    previous = point
                }
                unit.moveTarget?.let { target ->
                    val tx = projection.x(target.x); val ty = projection.y(target.y)
                    shapes.line(tx - 5, ty, tx + 5, ty); shapes.line(tx, ty - 5, tx, ty + 5)
                }
            }
        }
        construction?.let { preview ->
            shapes.color = when (preview.state) {
                PlacementState.CLEAR -> Color.GREEN
                PlacementState.BLOCKED -> Color.RED
                PlacementState.UNEXPLORED -> Color.GRAY
            }
            for ((col, row) in preview.cells) shapes.rect(projection.x(col * 20.0), projection.y((row + 1) * 20.0),
                (20 * projection.scale).toFloat(), (20 * projection.scale).toFloat())
        }
        groundAction?.let { preview ->
            val blastColor = if (preview.valid) UiTokens.RUST else UiTokens.FOE
            shapes.color = blastColor
            val sourceX = projection.x(preview.source.x); val sourceY = projection.y(preview.source.y)
            if (preview.minimumRange > 0.0) {
                shapes.setColor(blastColor.r, blastColor.g, blastColor.b, 0.35f)
                shapes.circle(sourceX, sourceY, (preview.minimumRange * projection.scale).toFloat(), 96)
                shapes.color = blastColor
            }
            if (preview.maximumRange > 0.0)
                shapes.circle(sourceX, sourceY, (preview.maximumRange * projection.scale).toFloat(), 192)
            val x = projection.x(preview.position.x); val y = projection.y(preview.position.y)
            if (preview.areaRadius > 0.0) {
                val outerR = (preview.areaRadius * projection.scale).toFloat()
                drawDashedCircle(shapes, x, y, outerR, dashArcPx = 9f, gapArcPx = 6f)
                drawDashedCircle(shapes, x, y, outerR + 1f, dashArcPx = 9f, gapArcPx = 6f)
                val innerR = ((preview.areaRadius - 30.0) * projection.scale).toFloat()
                if (innerR > 4f) {
                    shapes.setColor(blastColor.r, blastColor.g, blastColor.b, 0.35f)
                    drawDashedCircle(shapes, x, y, innerR, dashArcPx = 6f, gapArcPx = 6f)
                    shapes.color = blastColor
                }
            }
            val cross = if (preview.areaRadius > 0.0) 14f else 7f
            shapes.line(x - cross, y, x + cross, y); shapes.line(x, y - cross, x, y + cross)
        }
        drawBlink(shapes, frame.observation)
        for (effect in nuclearPresentation.project(frame.observation)) {
            val x=projection.x(effect.center.x)
            val y=projection.y(effect.center.y)+(effect.height*projection.scale).toFloat()
            val radius=(effect.radius*projection.scale).toFloat()
            when (effect.kind) {
                NuclearEffectKind.TRAIL -> {
                    shapes.setColor(.55f,.52f,.44f,effect.alpha)
                    shapes.circle(x,y,radius,12)
                }
                NuclearEffectKind.LAUNCH -> {
                    shapes.setColor(1f,.72f,.2f,effect.alpha)
                    shapes.circle(x,y,radius,32)
                    shapes.line(x-radius,y,x+radius,y);shapes.line(x,y-radius,x,y+radius)
                }
                NuclearEffectKind.INTERCEPTION -> {
                    shapes.setColor(1f, .94f, .7f, effect.alpha)
                    shapes.circle(x, y, radius, 24)
                    shapes.circle(x, y, radius * .5f, 16)
                }
                NuclearEffectKind.EXPLOSION -> {
                    shapes.setColor(1f,.88f,.48f,effect.alpha)
                    shapes.circle(x,y,radius,64)
                    shapes.circle(x,y,radius*.65f,48)
                    for (i in 0..7) {
                        val a=i*Math.PI/4
                        val dx=(kotlin.math.cos(a)*radius).toFloat();val dy=(kotlin.math.sin(a)*radius).toFloat()
                        shapes.line(x+dx*.25f,y+dy*.25f,x+dx,y+dy)
                    }
                }
            }
        }
        shapes.color = Color.YELLOW
        for (projectile in frame.observation.projectiles) {
            if (projectile.beam != null) continue
            val x = projection.x(projectile.position.x)
            val y = projection.y(projectile.position.y) + (projectile.height * projection.scale).toFloat()
            val areaRadius = projectile.areaRadius
            val appearance = projectile.appearance
            if (appearance == ProjectileAppearance.MECH_FLAME) continue
            if (appearance == ProjectileAppearance.NUCLEAR && areaRadius == null) {
                shapes.color = Color.DARK_GRAY
                shapes.ellipse(x - 5f, projection.y(projectile.position.y) - 2f, 10f, 4f, 16)
                shapes.color = Color.LIGHT_GRAY
                shapes.line(x, y - 4f, x, y + 5f)
            }
            shapes.setColor(appearance.red / 255f, appearance.green / 255f, appearance.blue / 255f, 1f)
            val lightning = projectile.lightning
            if (lightning != null) shapes.line(x, y, projection.x(lightning.end.x),
                projection.y(lightning.end.y) + (lightning.endHeight * projection.scale).toFloat())
            else if (areaRadius == null) shapes.circle(x, y, appearance.drawSize, 8)
            else if (areaRadius > 0f) shapes.circle(x, y, (areaRadius * projection.scale).toFloat(), 48)
        }
        val now = System.nanoTime()
        if (impactSample != frame.observation.sampleId) {
            impactSample = frame.observation.sampleId
            for (event in frame.observation.projectileEvents) {
                if (event.appearance == ProjectileAppearance.MECH_FLAME) {
                    val smoke = event.kind == "born" && (event.token.hashCode() and Int.MAX_VALUE) % 10 < 3
                    if (smoke || event.kind == "impact")
                        flameParticles[event.token + event.kind] = FlameParticle(event.position, event.height, now, smoke)
                    continue
                }
                if (event.kind != "impact" || event.beam != null) continue
                val large = event.appearance == ProjectileAppearance.BOMBER_BOMB
                val height = if (large || event.appearance == ProjectileAppearance.BOMBER_DEPTH_CHARGE) event.height else 0.0
                impacts[event.token] = Impact(event.position, height, now, large)
            }
        }
        fun duration(impact: Impact) = if (impact.large) 400_000_000L else 180_000_000L
        impacts.entries.removeAll { now - it.value.started > duration(it.value) }
        shapes.color = Color.ORANGE
        for (impact in impacts.values) {
            val fraction = (now - impact.started).toDouble() / duration(impact)
            val radius = (3.0 + 9.0 * fraction) * if (impact.large) 2.6 * projection.scale else 1.0
            shapes.circle(projection.x(impact.position.x), projection.y(impact.position.y) + (impact.height * projection.scale).toFloat(),
                radius.toFloat(), 16)
        }
        shapes.color = Color.CYAN
        for (beam in resources.defenseBeams(frame.observation)) {
            shapes.line(projection.x(beam.start.x), projection.y(beam.start.y), projection.x(beam.end.x), projection.y(beam.end.y))
        }
        shapes.color = Color.GREEN
        for (beam in resources.nanoBeams(frame.observation)) {
            shapes.line(projection.x(beam.start.x), projection.y(beam.start.y), projection.x(beam.end.x), projection.y(beam.end.y))
        }
        box?.let { (start, end) ->
            val x1 = projection.x(start.x); val x2 = projection.x(end.x)
            val y1 = projection.y(start.y); val y2 = projection.y(end.y)
            shapes.color = Color(0.45f, 0.91f, 0.65f, 1f)
            shapes.rect(minOf(x1, x2), minOf(y1, y2), abs(x1 - x2), abs(y1 - y2))
        }
    }

    fun drawFlameEffects(shapes: ShapeRenderer, frame: SandboxFrame) {
        val now = System.nanoTime()
        flameParticles.values.removeAll { now - it.started > 350_000_000L }
        val shots = frame.observation.projectiles.filter { it.appearance == ProjectileAppearance.MECH_FLAME }
        if (shots.isEmpty() && flameParticles.isEmpty()) return
        val scale = projection.scale.toFloat()
        val gl = Gdx.gl
        val wasBlending = gl.glIsEnabled(GL20.GL_BLEND)
        gl.glEnable(GL20.GL_BLEND)
        gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        try {
            for (shot in shots) {
                val x = projection.x(shot.position.x)
                val y = projection.y(shot.position.y) + shot.height.toFloat() * scale
                shapes.setColor(.92f, .25f, .035f, .64f)
                shapes.circle(x, y, 4.1f * scale, 12)
                shapes.setColor(1f, .67f, .18f, .94f)
                shapes.circle(x, y, 2.5f * scale, 10)
                shapes.setColor(1f, .94f, .64f, 1f)
                shapes.circle(x, y, 1.1f * scale, 8)
            }
            for (particle in flameParticles.values) {
                val age = ((now - particle.started).toDouble() / 350_000_000L).toFloat()
                val x = projection.x(particle.position.x)
                val y = projection.y(particle.position.y) + (particle.height.toFloat() + 6f * age) * scale
                if (particle.smoke) shapes.setColor(.36f, .29f, .24f, .65f * (1f - age))
                else shapes.setColor(1f, .44f + .25f * (1f - age), .1f, 1f - age)
                shapes.circle(x, y, (3f + age * 6f) * scale, 12)
            }
        } finally {
            shapes.end()
            if (!wasBlending) gl.glDisable(GL20.GL_BLEND)
        }
    }

    private fun syncMinimapTexture(frame: SandboxFrame): Texture? {
        val w = resources.widthTiles
        val h = resources.heightTiles
        if (w <= 0 || h <= 0) return null
        val cellCount = w * h
        val pixmap = minimapPixmap ?: Pixmap(w, h, Pixmap.Format.RGBA8888).also {
            it.blending = Pixmap.Blending.None
            it.setColor(0f, 0f, 0f, 1f)
            it.fill()
            minimapPixmap = it
        }
        val stateCache = minimapVisibilityState ?: ByteArray(cellCount) { -1 }.also { minimapVisibilityState = it }
        val gidCache = minimapGroundSnapshot ?: IntArray(cellCount) { -1 }.also { minimapGroundSnapshot = it }
        var dirty = minimapTexture == null

        for (cell in frame.terrain) {
            val col = cell.column
            val row = cell.row
            if (col !in 0 until w || row !in 0 until h) continue
            val index = row * w + col
            val stateByte: Byte = when {
                !cell.known -> 0
                cell.visible -> 2
                else -> 1
            }
            val cleanGid = cell.gid?.let { GroundRules.stripFlipFlags(it) } ?: 0
            if (stateCache[index] == stateByte && gidCache[index] == cleanGid) continue
            stateCache[index] = stateByte
            gidCache[index] = cleanGid
            val rgba = when (stateByte.toInt()) {
                0 -> 0x000000FF // Unexplored -> solid black
                else -> {
                    val full = TerrainTileColorSampler.compositeCellRgb(
                        cellIndex = index,
                        groundGid = cleanGid,
                        visualLayers = resources.visualLayers,
                        tileColors = tileAverageColors,
                        isResourcePool = index < resourcePoolFlags.size && resourcePoolFlags[index],
                    )
                    if (stateByte.toInt() == 2) full else TerrainTileColorSampler.darkenRgba(full, 0.45f)
                }
            }
            pixmap.drawPixel(col, row, rgba)
            dirty = true
        }

        val tex = minimapTexture
        if (tex == null) {
            val created = Texture(pixmap).also {
                it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                owned += it
                minimapTexture = it
            }
            return created
        }
        if (dirty) {
            tex.draw(pixmap, 0, 0)
        }
        return tex
    }

    fun drawMinimap(shapes: ShapeRenderer, frame: SandboxFrame, batch: SpriteBatch? = null) {
        val view = minimap.frame(frame)
        val area = minimap.area
        val well = minimapWellArea
        if (batch != null) {
            // Opaque black well backdrop (HUD Spec §6: letterboxed when map aspect exceeds [0.5, 1.0])
            shapes.begin(ShapeRenderer.ShapeType.Filled)
            shapes.color = Color.BLACK
            shapes.rect(well.left.toFloat(), well.bottom.toFloat(), well.width.toFloat(), well.height.toFloat())
            shapes.end()
            val tex = syncMinimapTexture(frame)
            if (tex != null) {
                batch.begin()
                batch.color = Color.WHITE
                batch.draw(tex, area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
                batch.end()
            }
            shapes.begin(ShapeRenderer.ShapeType.Filled)
        } else {
            shapes.begin(ShapeRenderer.ShapeType.Filled)
            shapes.color = Color.BLACK
            shapes.rect(area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
            val width = (area.width / resources.widthTiles).toFloat()
            val height = (area.height / resources.heightTiles).toFloat()
            for (cell in view.terrain) {
                if (!cell.known) continue
                val index = cell.row * resources.widthTiles + cell.column
                val rgba = TerrainTileColorSampler.compositeCellRgb(
                    cellIndex = index,
                    groundGid = cell.gid ?: 0,
                    visualLayers = resources.visualLayers,
                    tileColors = tileAverageColors,
                    isResourcePool = index in resourcePoolFlags.indices && resourcePoolFlags[index],
                )
                val finalRgba = if (cell.visible) rgba else TerrainTileColorSampler.darkenRgba(rgba, 0.45f)
                shapes.setColor(
                    ((finalRgba ushr 24) and 0xFF) / 255f,
                    ((finalRgba ushr 16) and 0xFF) / 255f,
                    ((finalRgba ushr 8) and 0xFF) / 255f,
                    1f,
                )
                shapes.rect(minimap.x(cell.column.toDouble() * resources.tileWidth),
                    minimap.y((cell.row + 1.0) * resources.tileHeight), width, height)
            }
        }
        for (unit in view.units) {
            shapes.color = resources.playerColor(unit.owner)
            shapes.rect(minimap.x(unit.position.x) - 2f, minimap.y(unit.position.y) - 2f, 4f, 4f)
        }
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        val topLeft = checkNotNull(projection.clippedWorld(projection.mapArea.left, projection.mapArea.bottom + projection.mapArea.height))
        val bottomRight = checkNotNull(projection.clippedWorld(projection.mapArea.left + projection.mapArea.width, projection.mapArea.bottom))
        val vx1 = minimap.x(topLeft.x).coerceIn(area.left.toFloat() + 1f, (area.left + area.width).toFloat() - 2f)
        val vx2 = minimap.x(bottomRight.x).coerceIn(vx1 + 1f, (area.left + area.width).toFloat() - 1f)
        val vy1 = minimap.y(bottomRight.y).coerceIn(area.bottom.toFloat() + 1f, (area.bottom + area.height).toFloat() - 2f)
        val vy2 = minimap.y(topLeft.y).coerceIn(vy1 + 1f, (area.bottom + area.height).toFloat() - 1f)
        shapes.setColor(0.95f, 0.98f, 0.96f, 1f)
        shapes.rect(vx1, vy1, vx2 - vx1, vy2 - vy1)
        shapes.end()
    }

    private fun drawDashedCircle(shapes: ShapeRenderer, cx: Float, cy: Float, radius: Float, dashArcPx: Float, gapArcPx: Float) {
        if (radius <= 1f) return
        val circumference = (2.0 * kotlin.math.PI * radius).toFloat()
        val stepPx = (dashArcPx + gapArcPx).coerceAtLeast(2f)
        val count = (circumference / stepPx).toInt().coerceIn(16, 180)
        val period = (2.0 * kotlin.math.PI / count).toFloat()
        val dashAngle = period * (dashArcPx / stepPx)
        for (i in 0 until count) {
            val a0 = i * period
            val a1 = a0 + dashAngle
            val mid = (a0 + a1) * 0.5f
            val x0 = cx + kotlin.math.cos(a0.toDouble()).toFloat() * radius
            val y0 = cy + kotlin.math.sin(a0.toDouble()).toFloat() * radius
            val xm = cx + kotlin.math.cos(mid.toDouble()).toFloat() * radius
            val ym = cy + kotlin.math.sin(mid.toDouble()).toFloat() * radius
            val x1 = cx + kotlin.math.cos(a1.toDouble()).toFloat() * radius
            val y1 = cy + kotlin.math.sin(a1.toDouble()).toFloat() * radius
            shapes.line(x0, y0, xm, ym)
            shapes.line(xm, ym, x1, y1)
        }
    }

    private fun drawBlink(shapes: ShapeRenderer, observation: PlayerObservation) {
        val step = observation.stepIndex
        fun charge(unit: UnitObservation) = unit.unitActions?.queue?.firstOrNull { it.name == "blinkQueued" ||
            it.name == "blink" && unit.attachmentOverview == null }
        if (observation.sampleId != blinkSample) {
            blinkSample = observation.sampleId
            for (unit in observation.units) {
                if (charge(unit) != null) blinkOrigins[unit.unitId] = unit.position
                if (observation.events.any { it.kind == "teleported" && it.unitId == unit.unitId } &&
                    blinkFlashes[unit.unitId]?.step != step) {
                    val origin = blinkOrigins.remove(unit.unitId) ?: unit.position
                    blinkFlashes[unit.unitId] = BlinkFlash(origin, unit.position, step)
                }
            }
            val charging = observation.units.filter { it.alive && charge(it) != null }.mapTo(hashSetOf()) { it.unitId }
            blinkOrigins.keys.retainAll(charging)
            blinkFlashes.values.removeAll { step - it.step >= 36 }
        }
        shapes.setColor(.07f, .93f, .95f, 1f)
        for (unit in observation.units) {
            val charge = charge(unit) ?: continue
            val radius = (24 + charge.progress * 25) * projection.scale.toFloat()
            val x = projection.x(unit.position.x)
            val y = projection.y(unit.position.y)
            shapes.circle(x, y, radius, 40)
            shapes.circle(x, y, radius * .78f, 32)
        }
        for (flash in blinkFlashes.values) {
            val progress = (step - flash.step) / 36f
            shapes.setColor(.07f, .93f, .95f, 1f - progress)
            val radius = (20 + 38 * progress) * projection.scale.toFloat()
            for (point in listOf(flash.from, flash.to))
                shapes.circle(projection.x(point.x), projection.y(point.y), radius, 40)
        }
    }

    override fun close() {
        var failure: Exception? = null
        owned.forEach { texture ->
            try { texture.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        minimapPixmap?.let { pm ->
            try { pm.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        playerColorShader?.let { sp ->
            try { sp.dispose() } catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        }
        playerColorShader = null
        minimapPixmap = null; minimapTexture = null; minimapVisibilityState = null; minimapGroundSnapshot = null
        owned.clear(); tiles.clear(); tileAverageColors.clear(); loadedTerrainTextureCount = 0
        units.clear(); compositeUnitIcons.clear(); turrets.clear(); namedTurrets.clear(); corpses.clear(); backs.clear()
        legs.clear(); feet.clear(); impacts.clear()
        failure?.let { throw it }
    }
}
