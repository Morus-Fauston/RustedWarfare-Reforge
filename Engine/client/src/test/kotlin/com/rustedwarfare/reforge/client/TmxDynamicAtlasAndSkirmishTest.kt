package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.Position
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class TmxDynamicAtlasAndSkirmishTest {
    private fun writeSolidPng(path: Path, width: Int, height: Int, rgb: Int): Path {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val argb = (0xFF shl 24) or (rgb and 0xFFFFFF)
        for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, argb)
        Files.createDirectories(path.parent)
        ImageIO.write(img, "png", path.toFile())
        return path
    }

    @Test
    fun loadsBothPackagedMultilayerDemoMapsAndOnlyActivatesUsedAtlases() {
        val builtIns = TilesetAtlasResolver.builtInSkirmishMaps()
        assertTrue(builtIns.size >= 2, "Expected at least 2 packaged built-in skirmish demo maps")

        for (demo in builtIns) {
            assertTrue(Files.isRegularFile(demo.path), "Missing packaged demo map: ${demo.path}")
            val loaded = assertIs<ContentResult.Success<MapDefinition>>(
                ContentLoader.loadMap(demo.path, listOf(TilesetAtlasResolver.placeholderTilesetDir()))
            ).value
            assertTrue(loaded.visualLayers.size >= 3, "Expected Ground + GroundDetails + Items on ${demo.id}")
            assertTrue(loaded.startingUnits.size >= 4, "Expected starting units on ${demo.id}")

            val visualAtlases = loaded.tileSets.map(VisualTileAtlas::from)
            val activeGids = TilesetAtlasResolver.collectActiveVisualGids(loaded.visualLayers)
            val activeAtlases = TilesetAtlasResolver.activeAtlases(visualAtlases, activeGids)
            assertTrue(activeAtlases.none { it.name.contains("units", ignoreCase = true) },
                "Unused units tileset must not be activated for GPU upload")
            assertEquals(1, activeAtlases.size, "Only base_terrain should be active on visual layers")
        }
    }

    @Test
    fun deduplicatesSharedPngTexturesAcrossMultipleTilesetsAndNormalizesTransColor() {
        val dir = Files.createTempDirectory("ui-p001-002-dedup-")
        try {
            val sharedPng = writeSolidPng(dir.resolve("shared.png"), 40, 20, 0x336699)
            val otherPng = writeSolidPng(dir.resolve("other.png"), 20, 20, 0x228833)
            val a1 = VisualTileAtlas(1, "A1", 20, 20, sharedPng, emptySet(), 2, 2, 0, 0, "#008A76", emptySet())
            val a2 = VisualTileAtlas(10, "A2", 20, 20, dir.resolve("./shared.png"), emptySet(), 2, 2, 0, 0, "008a76", emptySet())
            val a3 = VisualTileAtlas(20, "A3", 20, 20, sharedPng, emptySet(), 2, 2, 0, 0, null, emptySet())
            val a4 = VisualTileAtlas(30, "A4", 20, 20, otherPng, emptySet(), 1, 1, 0, 0, null, emptySet())

            val unique = TilesetAtlasResolver.uniqueTextureUploadKeys(listOf(a1, a2, a3, a4))
            assertEquals(3, unique.size, "a1 and a2 share the same normalized path and trans color, so only 3 uploads needed")
            assertEquals("008a76", unique[0].normalizedTransparentColor)
            assertEquals(null, unique[1].normalizedTransparentColor)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun fourTierAtlasResolutionPrioritizesPackOverrideThenDirectPathThenOriginalThenPlaceholder() {
        val dir = Files.createTempDirectory("ui-p001-002-tier-")
        try {
            val directPng = writeSolidPng(dir.resolve("direct.png"), 60, 20, 0x22AA44)
            val overridePng = writeSolidPng(dir.resolve("pack/direct_hd.png"), 120, 40, 0xCC3333)
            val singleRowWithRemainderPng = writeSolidPng(dir.resolve("pack/single_row_rem.png"), 60, 49, 0x1144CC)
            val origRoot = Files.createDirectories(dir.resolve("orig/assets/tilesets/bitmaps"))
            val origPng = writeSolidPng(origRoot.resolve("sand.png"), 60, 40, 0xD2B48C)
            val missingPath = dir.resolve("missing/sand.png")
            val missingLavaPath = dir.resolve("missing/lava.png")

            // Build a real PreparedVisualAssets containing Tier 1 tileset overrides
            val directAtlas = VisualTileAtlas(1, "direct", 20, 20, directPng, emptySet(), 3, 3, 0, 0, null, emptySet(), imageSource = "direct.png")
            val singleRowAtlas = VisualTileAtlas(5, "rem", 20, 20, directPng, emptySet(), 3, 3, 0, 0, null, emptySet(), imageSource = "rem.png")
            val directResId = ResourceId.parse(directAtlas.normalizedResourceKey())
            val singleRowResId = ResourceId.parse(singleRowAtlas.normalizedResourceKey())

            val overrideImg = PreparedResourceImage(
                path = overridePng,
                declaration = ResourceImageDeclaration("direct_hd.png", frameWidth = 60, frameHeight = 20, totalFrames = 1),
                pixelWidth = 120,
                pixelHeight = 40,
            )
            val remImg = PreparedResourceImage(
                path = singleRowWithRemainderPng,
                declaration = ResourceImageDeclaration("single_row_rem.png", frameWidth = 60, frameHeight = 49, totalFrames = 1),
                pixelWidth = 60,
                pixelHeight = 49,
            )
            val packMeta = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER)
            val resolvedImages = ResolvedResourceImages(
                layers = listOf(ResourceImageLayer(packMeta, mapOf(directResId to overrideImg, singleRowResId to remImg))),
                images = mapOf(directResId to overrideImg, singleRowResId to remImg),
                providers = mapOf(directResId to packMeta.id.value, singleRowResId to packMeta.id.value),
            )
            val baseVisuals = PreparedVisualAssets(
                selection = VisualAssetSelection(VisualAssetSource.PLACEHOLDER),
                profileId = "test",
                identity = "test",
                units = emptyMap(),
                terrain = emptyMap(),
            ).withResourceImages(resolvedImages)

            // Tier 1: Pack override takes priority over existing directPng, scales 2x HD tile pixels cleanly
            val tier1 = TilesetAtlasResolver.resolveAtlasImage(directAtlas, baseVisuals, null)
            assertEquals(1, tier1.tier)
            assertEquals(overridePng, tier1.path)
            assertEquals(40, tier1.pixelTileWidth)
            assertEquals(40, tier1.pixelTileHeight)
            // Verify color actually changes when resource pack overrides the tileset (red override vs green direct)
            val directRgb = ImageIO.read(directPng.toFile()).getRGB(0, 0) and 0xFFFFFF
            val overrideRgb = ImageIO.read(tier1.path.toFile()).getRGB(0, 0) and 0xFFFFFF
            assertNotEquals(directRgb, overrideRgb, "Swapping resource pack must change resolved tile pixel color")

            // Tier 1 single-row atlas with remainder pixels must NOT expand tileHeight to full 49px height!
            val tier1Rem = TilesetAtlasResolver.resolveAtlasImage(singleRowAtlas, baseVisuals, null)
            assertEquals(1, tier1Rem.tier)
            assertEquals(20, tier1Rem.pixelTileWidth)
            assertEquals(20, tier1Rem.pixelTileHeight, "Single-row remainder atlas must preserve 20px tile height")

            // Tier 2: Direct map/TSX path when no pack override exists
            val tier2 = TilesetAtlasResolver.resolveAtlasImage(directAtlas, null, null)
            assertEquals(2, tier2.tier)
            assertEquals(directPng, tier2.path)

            // Tier 3: Configured local original assets tilesets/ fallback + path traversal rejection
            val origAtlas = VisualTileAtlas(10, "Sand", 20, 20, missingPath, emptySet(), 6, 3, 0, 0, "008a76", emptySet(), imageSource = "terrain/bitmaps/sand.png")
            val tier3 = TilesetAtlasResolver.resolveAtlasImage(origAtlas, null, dir.resolve("orig/assets/tilesets"))
            assertEquals(3, tier3.tier)
            assertEquals(origPng.toAbsolutePath().normalize(), tier3.path)

            val traversalAtlas = VisualTileAtlas(15, "Evil", 20, 20, missingPath, emptySet(), 1, 1, 0, 0, null, emptySet(), imageSource = "../../../direct.png")
            val traversalRes = TilesetAtlasResolver.resolveAtlasImage(traversalAtlas, null, dir.resolve("orig/assets/tilesets"))
            assertEquals(4, traversalRes.tier, "Path traversal outside original root must be rejected to Tier 4 fallback")

            // Tier 4: Packaged placeholder biome fallback (including ensuring 'dirt2.png' maps to dirt.png, not ridge.png)
            val fallbackAtlas = VisualTileAtlas(20, "Lava", 20, 20, missingLavaPath, emptySet(), 6, 3, 0, 0, null, emptySet(), imageSource = "terrain/bitmaps/lava.png")
            val tier4 = TilesetAtlasResolver.resolveAtlasImage(fallbackAtlas, null, null)
            assertEquals(4, tier4.tier)
            assertTrue(Files.isRegularFile(tier4.path))
            assertEquals("lava.png", tier4.path.fileName.toString())

            val dirt2Atlas = VisualTileAtlas(25, "Dirt2", 20, 20, missingLavaPath, emptySet(), 6, 3, 0, 0, null, emptySet(), imageSource = "terrain/bitmaps/dirt2.png")
            assertEquals("dirt.png", TilesetAtlasResolver.choosePlaceholderBiomeFile(dirt2Atlas))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun viewportCullingMinimapColorCompositingAspectFitAndAll8TiledFlipCombinationsAreAccurate() {
        val projection = MapProjection(2000.0, 2000.0, MapArea(0.0, 0.0, 600.0, 400.0), fillViewport = false)
        projection.centerOn(Position(1000.0, 1000.0))
        projection.zoomAt(300.0, 200.0, -4.0, keepCenter = true)
        val bounds = TerrainTileColorSampler.visibleBounds(projection, 100, 100, 20, 20, padTiles = 1)
        assertTrue(bounds.minCol > 0 && bounds.maxCol < 99, "Expected zoomed viewport to cull outside columns: $bounds")
        assertTrue(bounds.minRow > 0 && bounds.maxRow < 99, "Expected zoomed viewport to cull outside rows: $bounds")

        // Color compositing (normal, resource pool highlight, and fog darkening)
        val colors = mapOf(
            1 to TileAverageColor(60, 120, 60, 1f),
            2 to TileAverageColor(200, 180, 60, 0.5f),
        )
        val layers = listOf(
            MapVisualLayer("Ground", MapVisualLayerKind.GROUND, intArrayOf(1)),
            MapVisualLayer("Items", MapVisualLayerKind.ITEMS, intArrayOf(2)),
        )
        val composite = TerrainTileColorSampler.compositeCellRgb(0, 1, layers, colors, isResourcePool = false)
        assertEquals(130, (composite ushr 24) and 0xFF)
        assertEquals(150, (composite ushr 16) and 0xFF)
        assertEquals(60, (composite ushr 8) and 0xFF)
        val fogged = TerrainTileColorSampler.darkenRgba(composite, 0.45f)
        assertEquals((130 * 0.45f).toInt(), (fogged ushr 24) and 0xFF)

        val poolComposite = TerrainTileColorSampler.compositeCellRgb(0, 1, layers, colors, isResourcePool = true)
        assertTrue(((poolComposite ushr 24) and 0xFF) > ((composite ushr 24) and 0xFF),
            "Resource pool cell must highlight red/gold channel above base terrain")

        // Screen-aspect-compensated minimap area preserves 1:1 physical pixels on 1920x1080 over StretchViewport(960,640)
        val fittedSquare = Minimap.fitArea(
            bounds = MapArea(752.0, 464.0, 184.0, 160.0),
            worldWidth = 2400.0,
            worldHeight = 2400.0,
            stagePixelsPerUnitX = 1920.0 / 960.0,
            stagePixelsPerUnitY = 1080.0 / 640.0,
        )
        val physW = fittedSquare.width * (1920.0 / 960.0)
        val physH = fittedSquare.height * (1080.0 / 640.0)
        assertEquals(physW, physH, 0.5, "Square map must render 1:1 in physical pixels on 1920x1080")

        // All 8 Tiled (D, H, V) flip/rotate combinations
        val H = GroundRules.FLIP_HORIZONTAL_FLAG
        val V = GroundRules.FLIP_VERTICAL_FLAG
        val D = GroundRules.FLIP_DIAGONAL_FLAG
        assertEquals(Triple(0f, 1f, 1f), TerrainTileColorSampler.decodeTiledTransform(0))
        assertEquals(Triple(0f, -1f, 1f), TerrainTileColorSampler.decodeTiledTransform(H))
        assertEquals(Triple(0f, 1f, -1f), TerrainTileColorSampler.decodeTiledTransform(V))
        assertEquals(Triple(180f, 1f, 1f), TerrainTileColorSampler.decodeTiledTransform(H or V))
        assertEquals(Triple(90f, -1f, 1f), TerrainTileColorSampler.decodeTiledTransform(D))
        assertEquals(Triple(-90f, 1f, 1f), TerrainTileColorSampler.decodeTiledTransform(D or H))
        assertEquals(Triple(90f, 1f, 1f), TerrainTileColorSampler.decodeTiledTransform(D or V))
        assertEquals(Triple(-90f, -1f, 1f), TerrainTileColorSampler.decodeTiledTransform(D or H or V))
    }

    @Test
    fun skirmishSelectionStateHandlesInvalidPathsAndMissingOrBrokenTmxGracefully() {
        val dir = Files.createTempDirectory("ui-p001-002-select-")
        try {
            val state = SkirmishMapSelectionState()
            assertFalse(state.selectPath("   ", null))
            assertNull(state.selected)
            assertNull(state.error)

            // Illegal path characters must not throw InvalidPathException
            assertFalse(state.selectPath("C:/bad\u0000path/map*.tmx", null))
            assertNotNull(state.error)

            // Non-existent file records clear error
            assertFalse(state.select(dir.resolve("does_not_exist.tmx"), null))
            assertNotNull(state.error)
            assertNull(state.selected)

            // Malformed TMX records error without throwing
            val broken = Files.writeString(dir.resolve("broken.tmx"), "<not valid xml")
            assertFalse(state.select(broken, null))
            assertNotNull(state.error)
            assertNull(state.selected)

            // Valid built-in demo clears previous error and populates selection
            val demo = TilesetAtlasResolver.builtInSkirmishMaps().first()
            assertTrue(state.select(demo.path, null, demo.displayName))
            assertNull(state.error)
            assertEquals(demo.displayName, state.selected?.displayName)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun skirmishBuilderSkipsUnsupportedUnitsWithNoticeAndBuildsValidScenario() {
        val repo = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val packPath = repo.resolve("SimulationTest/public/inputs/phase-six-a-sandbox/json/package")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packPath)).value
        val demo = TilesetAtlasResolver.builtInSkirmishMaps().first()

        val built = TmxSkirmishScenarioBuilder.build(demo.path, pack.units)
        assertTrue(built.scenario.players.any { it.playerId == "p1" })
        assertTrue(built.scenario.players.any { it.playerId == "p2" })
        assertTrue(built.scenario.units.size >= 4)
        assertEquals(4000L, built.scenario.economyContract?.initialBalances?.get("p1"))

        val syntheticMap = MapDefinition(
            widthTiles = 10,
            heightTiles = 10,
            tileWidth = 20,
            tileHeight = 20,
            tileSets = built.scenario.map.tileSets,
            groundGids = IntArray(100) { 1 },
            blocking = BlockingGrid(10, 10, BooleanArray(100)),
            startingUnits = listOf(
                MapStartingUnit("commandCenter", "0", 2, 2, 50.0, 50.0),
                MapStartingUnit("ladyBug", "1", 5, 5, 110.0, 110.0),
            ),
            triggerInfo = MapTriggerInfo(mapType = "skirmish", ignoredObjectCount = 2),
        )
        val builtSynthetic = TmxSkirmishScenarioBuilder.buildFromMap(demo.path, syntheticMap, pack.units, "Synthetic")
        assertEquals(mapOf("ladyBug" to 1), builtSynthetic.skippedUnitTypes)
        assertEquals(2, builtSynthetic.ignoredTriggerCount)
        assertTrue(builtSynthetic.startupNotices.isNotEmpty())
        assertTrue(builtSynthetic.startupNotices.single().contains("1"))
        assertTrue(builtSynthetic.startupNotices.single().contains("2"))
        assertTrue(builtSynthetic.scenario.units.any { it.ownerPlayerId == "p2" })
    }
}
