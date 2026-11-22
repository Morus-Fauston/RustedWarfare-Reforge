package com.rustedwarfare.reforge.core.content

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TmxMultilayerMapLoadTest {
    private fun writeFakePng(path: Path, width: Int, height: Int) {
        DataOutputStream(Files.newOutputStream(path)).use { out ->
            out.writeLong(0x89504E470D0A1A0AuL.toLong())
            out.writeInt(13)
            out.writeInt(0x49484452)
            out.writeInt(width)
            out.writeInt(height)
            out.writeByte(8)
            out.writeByte(6)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
        }
    }

    private fun encodeLayer(gids: IntArray, compression: String?): String {
        val raw = ByteBuffer.allocate(gids.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (gid in gids) raw.putInt(gid)
        val payload = when (compression) {
            null -> raw.array()
            "gzip" -> ByteArrayOutputStream().use { baos ->
                GZIPOutputStream(baos).use { it.write(raw.array()) }
                baos.toByteArray()
            }
            "zlib" -> ByteArrayOutputStream().use { baos ->
                DeflaterOutputStream(baos).use { it.write(raw.array()) }
                baos.toByteArray()
            }
            else -> error("Unsupported test compression: $compression")
        }
        return Base64.getEncoder().encodeToString(payload)
    }

    @Test
    fun parsesBase64RawGzipZlibAndPreservesFlipFlags() {
        val dir = Files.createTempDirectory("ui-p001-001-enc-")
        try {
            val png = dir.resolve("ground.png")
            writeFakePng(png, 60, 40) // 3x2 = 6 tiles

            val hFlip = GroundRules.FLIP_HORIZONTAL_FLAG
            val vFlip = GroundRules.FLIP_VERTICAL_FLAG
            val dFlip = GroundRules.FLIP_DIAGONAL_FLAG
            val rawGround = intArrayOf(
                1,
                1 or hFlip,
                2 or vFlip,
                1 or hFlip or vFlip or dFlip,
            )
            val details = intArrayOf(0, 3 or dFlip, 0, 0)
            val details2 = intArrayOf(0, 0, 1 or hFlip, 0)

            for (compression in listOf(null, "gzip", "zlib")) {
                val compAttr = compression?.let { """ compression="$it"""" } ?: ""
                val tmx = dir.resolve("map-${compression ?: "raw"}.tmx")
                Files.writeString(
                    tmx,
                    """<map version="1.2" orientation="orthogonal" renderorder="right-down" width="2" height="2" tilewidth="20" tileheight="20" infinite="0">
                      <tileset firstgid="1" name="ground" tilewidth="20" tileheight="20">
                        <image source="ground.png"/>
                        <tile id="1"><properties><property name="water" value=""/></properties></tile>
                      </tileset>
                      <layer name="Ground" width="2" height="2"><data encoding="base64"$compAttr>${encodeLayer(rawGround, compression)}</data></layer>
                      <layer name="GroundDetails" width="2" height="2"><data encoding="base64"$compAttr>${encodeLayer(details, compression)}</data></layer>
                      <layer name="GroundDetails2" width="2" height="2"><data encoding="base64"$compAttr>${encodeLayer(details2, compression)}</data></layer>
                    </map>""".trimIndent(),
                )
                val loaded = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(tmx)).value
                assertEquals(listOf(1, 1, 2, 1), loaded.groundGids.toList())
                assertEquals(3, loaded.visualLayers.size)
                assertEquals(
                    listOf(MapVisualLayerKind.GROUND, MapVisualLayerKind.GROUND_DETAILS, MapVisualLayerKind.GROUND_DETAILS_2),
                    loaded.visualLayers.map { it.kind },
                )
                assertEquals(hFlip, loaded.visualLayers[0].flipFlagsAt(1))
                assertEquals(vFlip, loaded.visualLayers[0].flipFlagsAt(2))
                assertEquals(hFlip or vFlip or dFlip, loaded.visualLayers[0].flipFlagsAt(3))
                assertEquals(1, loaded.visualLayers[0].cleanGidAt(3))
                assertEquals(dFlip, loaded.visualLayers[1].flipFlagsAt(1))
                assertEquals(3, loaded.visualLayers[1].cleanGidAt(1))
                assertEquals(hFlip, loaded.visualLayers[2].flipFlagsAt(2))
                assertTrue(loaded.blocking.isBlocked(0, 1))
                assertFalse(loaded.waterBlocking.isBlocked(0, 1))
                assertTrue(loaded.isWater(0, 1))
                assertFalse(loaded.blocking.isBlocked(1, 0))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun supportsNonDivisibleAtlasDimensionsOmittedImageSizeAndConditionalDefaultWaterFallback() {
        val dir = Files.createTempDirectory("ui-p001-001-atlas-")
        try {
            writeFakePng(dir.resolve("misc.png"), 79, 160) // 3x8 = 24
            writeFakePng(dir.resolve("units.png"), 200, 665) // spacing=1, margin=1 -> 9x31 = 279
            writeFakePng(dir.resolve("shallowwater.png"), 60, 40) // 3x2 = 6
            writeFakePng(dir.resolve("water.png"), 60, 20) // 3x1 = 3

            Files.writeString(
                dir.resolve("misc.tsx"),
                """<tileset name="misc" tilewidth="20" tileheight="20">
                  <image source="misc.png"/>
                  <tile id="4"><properties><property name="res_pool" value=""/></properties></tile>
                  <tile id="9"><properties><property name="unit" value="tree"/><property name="team" value="none"/></properties></tile>
                </tileset>""",
            )
            Files.writeString(
                dir.resolve("units.tsx"),
                """<tileset name="units" tilewidth="20" tileheight="20" spacing="1" margin="1" tilecount="279" columns="9">
                  <image source="units.png" width="200" height="665"/>
                  <tile id="1"><properties><property name="unit" value="builder"/><property name="team" value="0"/></properties></tile>
                  <tile id="2"><properties><property name="customUnit" value="heavySpider"/><property name="team" value="1"/></properties></tile>
                </tileset>""",
            )
            Files.writeString(
                dir.resolve("shallow.tsx"),
                """<tileset name="Shallow Water" tilewidth="20" tileheight="20">
                  <image source="shallowwater.png" trans="008a76"/>
                </tileset>""",
            )
            // Explicit property on tile id=0 suppresses the default water fallback.
            Files.writeString(
                dir.resolve("water-override.tsx"),
                """<tileset name="Water Explicit" tilewidth="20" tileheight="20">
                  <image source="water.png"/>
                  <tile id="0"><properties><property name="cliff" value=""/></properties></tile>
                </tileset>""",
            )

            val tmx = dir.resolve("test.tmx")
            Files.writeString(
                tmx,
                """<map orientation="orthogonal" renderorder="right-down" width="3" height="1" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" source="shallow.tsx"/>
                  <tileset firstgid="7" source="water-override.tsx"/>
                  <tileset firstgid="10" source="misc.tsx"/>
                  <tileset firstgid="34" source="units.tsx"/>
                  <layer name="Ground" width="3" height="1"><data encoding="csv">1,2,7</data></layer>
                </map>""",
            )
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(tmx)).value
            val shallowTs = map.tileSets[0]
            val explicitTs = map.tileSets[1]
            val miscTs = map.tileSets[2]
            val unitsTs = map.tileSets[3]
            assertEquals(3, shallowTs.columns)
            assertEquals(6, shallowTs.tileCount)
            assertEquals(setOf(0), shallowTs.waterTileIds)
            assertTrue(explicitTs.waterTileIds.isEmpty(), "Explicit tile 0 properties must suppress default water fallback")
            assertEquals(setOf(0), explicitTs.blockedTileIds)
            assertEquals(3, miscTs.columns)
            assertEquals(24, miscTs.tileCount)
            assertEquals(setOf(4), miscTs.resourcePoolTileIds)
            assertEquals(setOf(9), miscTs.treeTileIds)
            assertEquals(9, unitsTs.columns)
            assertEquals(279, unitsTs.tileCount)
            assertTrue(map.blocking.isBlocked(0, 0))
            assertFalse(map.waterBlocking.isBlocked(0, 0))
            assertFalse(map.blocking.isBlocked(1, 0))
            assertTrue(map.blocking.isBlocked(2, 0))
            assertTrue(map.waterBlocking.isBlocked(2, 0))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun mergesItemsObjectsPathingOverrideBlockBuildingsDynamicObstaclesAndExtractsUnits() {
        val dir = Files.createTempDirectory("ui-p001-001-layers-")
        try {
            writeFakePng(dir.resolve("atlas.png"), 240, 40) // 12x2 = 24 tiles
            // local ids (firstgid=1 => gid = local+1):
            // 0 (gid 1): plain land
            // 1 (gid 2): water
            // 2 (gid 3): res_pool
            // 3 (gid 4): large-rock (hard blocks land, hover, over-cliff, water)
            // 4 (gid 5): unit=tree (visual tree + trees block: blocks land, hover, over-cliff, over-cliff-water)
            // 5 (gid 6): water-bridge
            // 6 (gid 7): unit=commandCenter, team=0
            // 7 (gid 8): customUnit=customMech, team=1
            // 8 (gid 9): unknown decorative property
            // 9 (gid 10): block-buildings (only blocks foundation, NOT unit movement)
            // 10 (gid 11): lava
            // 11 (gid 12): large-cliff (blocks land & hover, passable to over-cliff)
            val tmx = dir.resolve("multi.tmx")
            Files.writeString(
                tmx,
                """<map version="1.2" orientation="orthogonal" renderorder="right-down" width="6" height="2" tilewidth="20" tileheight="20" infinite="0">
                  <properties><property name="author" value="reforge"/></properties>
                  <tileset firstgid="1" name="atlas" tilewidth="20" tileheight="20">
                    <image source="atlas.png" width="240" height="40"/>
                    <tile id="1"><properties><property name="water" value=""/></properties></tile>
                    <tile id="2"><properties><property name="res_pool" value=""/></properties></tile>
                    <tile id="3"><properties><property name="large-rock" value=""/></properties></tile>
                    <tile id="4"><properties><property name="unit" value="tree"/><property name="team" value="none"/></properties></tile>
                    <tile id="5"><properties><property name="water-bridge" value=""/></properties></tile>
                    <tile id="6"><properties><property name="unit" value="commandCenter"/><property name="team" value="0"/></properties></tile>
                    <tile id="7"><properties><property name="customUnit" value="customMech"/><property name="team" value="1"/></properties></tile>
                    <tile id="8"><properties><property name="custom-decor" value="abc"/></properties></tile>
                    <tile id="9"><properties><property name="block-buildings" value=""/></properties></tile>
                    <tile id="10"><properties><property name="lava" value=""/></properties></tile>
                    <tile id="11"><properties><property name="large-cliff" value=""/></properties></tile>
                  </tileset>
                  <layer name="Ground" width="6" height="2">
                    <data encoding="csv">1,1,2,1,1,11,1,2,1,1,1,9</data>
                  </layer>
                  <layer name="Objects" width="6" height="2">
                    <data encoding="csv">3,4,6,5,7,6,0,0,10,6,12,0</data>
                  </layer>
                  <layer name="PathingOverride" width="6" height="2">
                    <data encoding="csv">0,0,0,0,0,0,4,1,0,0,0,0</data>
                  </layer>
                  <layer name="set" width="6" height="2">
                    <data encoding="csv">4,4,4,4,4,4,4,4,4,4,4,4</data>
                  </layer>
                  <layer name="set-disabled" width="6" height="2">
                    <data encoding="csv">4,4,4,4,4,4,4,4,4,4,4,4</data>
                  </layer>
                  <layer name="Units" width="6" height="2">
                    <data encoding="csv">0,0,0,0,0,0,0,0,8,0,0,5</data>
                  </layer>
                  <objectgroup name="Triggers">
                    <object id="1" name="map_info" x="10" y="10" width="40" height="40">
                      <properties>
                        <property name="type" value="skirmish"/>
                        <property name="fog" value="los"/>
                      </properties>
                    </object>
                    <object id="2" name="wave_spawn" type="unitAdd" x="50" y="50"/>
                  </objectgroup>
                </map>""".trimIndent(),
            )
            val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(tmx)).value

            // (0,0): res_pool on Items
            assertTrue(map.isResourcePool(0, 0))
            assertFalse(map.blocking.isBlocked(0, 0))
            assertTrue(map.isFoundationBlocked(0, 0, MovementType.LAND))

            // (1,0): large-rock on Items
            assertTrue(map.blocking.isBlocked(1, 0))
            assertTrue(map.hoverBlocking.isBlocked(1, 0))
            assertTrue(map.overCliffBlocking.isBlocked(1, 0))

            // (2,0): water on Ground + water-bridge on Items -> passable to land & water, NOT liquid
            assertFalse(map.blocking.isBlocked(2, 0))
            assertFalse(map.waterBlocking.isBlocked(2, 0))
            assertFalse(map.isWater(2, 0))
            assertFalse(map.isLiquid(2, 0))
            assertFalse(map.isOverWater(2, 0))

            // (3,0): unit=tree on Items -> visual preserved, blocks land, hover, over-cliff, over-cliff-water (aligned with DEC-P008-002 trees)
            val itemsVisual = map.visualLayers.single { it.kind == MapVisualLayerKind.ITEMS }
            assertEquals(5, itemsVisual.cleanGidAt(3))
            assertTrue(map.blocking.isBlocked(3, 0))
            assertTrue(map.hoverBlocking.isBlocked(3, 0))
            assertTrue(map.overCliffBlocking.isBlocked(3, 0))
            assertTrue(map.overCliffWaterBlocking.isBlocked(3, 0))

            // (4,0): commandCenter on Items -> extracted as starting unit, cleared from Items visual layer
            assertEquals(0, itemsVisual.cleanGidAt(4))

            // (5,0): lava on Ground + water-bridge on Items -> bridge MUST NOT clear underlying lava block!
            assertTrue(map.blocking.isBlocked(5, 0))
            assertTrue(map.hoverBlocking.isBlocked(5, 0))
            assertTrue(map.overCliffBlocking.isBlocked(5, 0))

            // (0,1): PathingOverride = large-rock
            assertTrue(map.blocking.isBlocked(0, 1))
            assertTrue(map.hoverBlocking.isBlocked(0, 1))

            // (1,1): Ground=water + PathingOverride=land -> land passable, water blocked, NOT liquid
            assertFalse(map.blocking.isBlocked(1, 1))
            assertTrue(map.waterBlocking.isBlocked(1, 1))
            assertFalse(map.isWater(1, 1))
            assertFalse(map.isOverWater(1, 1))
            assertFalse(map.isLiquid(1, 1))

            // (2,1): block-buildings on Items -> land movement ALLOWED, building foundation BLOCKED
            assertFalse(map.blocking.isBlocked(2, 1))
            assertFalse(map.hoverBlocking.isBlocked(2, 1))
            assertTrue(map.isFoundationBlocked(2, 1, MovementType.LAND))
            assertTrue(map.isFoundationBlocked(2, 1, MovementType.AIR))

            // (3,1): plain land on Ground + water-bridge on Items -> land passable, ships STILL blocked!
            assertFalse(map.blocking.isBlocked(3, 1))
            assertTrue(map.waterBlocking.isBlocked(3, 1))

            // (4,1): large-cliff on Items -> isCliff true, blocks land/hover, allows over-cliff
            assertTrue(map.isCliff(4, 1))
            assertTrue(map.blocking.isBlocked(4, 1))
            assertTrue(map.hoverBlocking.isBlocked(4, 1))
            assertFalse(map.overCliffBlocking.isBlocked(4, 1))

            // withDynamicObstacles preserves multilayer overrides (e.g. BuildingTerrain.collisionMap)
            val withBuilding = map.withDynamicObstacles(setOf(0 to 0))
            assertTrue(withBuilding.blocking.isBlocked(0, 0))
            assertTrue(withBuilding.hoverBlocking.isBlocked(0, 0))
            assertTrue(withBuilding.hoverBlocking.isBlocked(1, 0), "Items rock must remain hover-blocked after dynamic obstacle overlay")
            assertFalse(withBuilding.blocking.isBlocked(2, 0), "Water-bridge must remain passable after unrelated dynamic obstacle overlay")
            assertFalse(withBuilding.isWater(2, 0))
            assertTrue(withBuilding.isResourcePool(0, 0))
            assertTrue(withBuilding.isCliff(4, 1))

            // Starting units & diagnostics (including non-unit tree tile on Units layer)
            assertEquals(2, map.startingUnits.size)
            assertEquals("commandCenter", map.startingUnits[0].unitType)
            assertFalse(map.startingUnits[0].isCustomUnit)
            assertEquals("customMech", map.startingUnits[1].unitType)
            assertTrue(map.startingUnits[1].isCustomUnit)
            assertEquals("1", map.startingUnits[1].team)
            assertEquals("skirmish", map.triggerInfo.mapType)
            assertEquals("los", map.triggerInfo.fog)
            assertEquals(1, map.triggerInfo.ignoredObjectCount)
            assertEquals("reforge", map.mapProperties["author"])
            assertTrue(map.ignoredDiagnostics.any { it.contains("custom-decor") })
            assertTrue(map.ignoredDiagnostics.any { it.contains("wave_spawn") })
            assertTrue(map.ignoredDiagnostics.any { it.contains("Units layer") })

            // Immutability & defensive copy checks
            assertFailsWith<UnsupportedOperationException> { (map.visualLayers as MutableList<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.startingUnits as MutableList<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.ignoredDiagnostics as MutableList<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.mapProperties as MutableMap<*, *>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.triggerInfo.properties as MutableMap<*, *>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.tileSets[0].treeTileIds as MutableSet<*>).clear() }
            assertFailsWith<UnsupportedOperationException> { (map.tileSets[0].unitTiles as MutableMap<*, *>).clear() }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsZipBombCorruptStreamsFlippedZeroGidAndPathTraversal() {
        val dir = Files.createTempDirectory("ui-p001-001-sec-")
        try {
            val subDir = Files.createDirectories(dir.resolve("maps"))
            val tilesetRoot = Files.createDirectories(dir.resolve("tilesets"))
            writeFakePng(tilesetRoot.resolve("ground.png"), 40, 40)
            writeFakePng(dir.resolve("outside.png"), 40, 40)

            // 1. Decompression bomb: declared 2x2 (16 bytes), payload decompresses to 64 bytes
            val oversized = encodeLayer(IntArray(16) { 1 }, "gzip")
            val bombTmx = subDir.resolve("bomb.tmx")
            Files.writeString(
                bombTmx,
                """<map orientation="orthogonal" renderorder="right-down" width="2" height="2" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" name="g" tilewidth="20" tileheight="20"><image source="ground.png"/></tileset>
                  <layer name="Ground" width="2" height="2"><data encoding="base64" compression="gzip">$oversized</data></layer>
                </map>""",
            )
            val bombFailure = assertIs<ContentResult.Failure>(ContentLoader.loadMap(bombTmx, listOf(tilesetRoot)))
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, bombFailure.error.code)

            // 2. Corrupt gzip stream
            val corruptTmx = subDir.resolve("corrupt.tmx")
            Files.writeString(
                corruptTmx,
                """<map orientation="orthogonal" renderorder="right-down" width="2" height="2" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" name="g" tilewidth="20" tileheight="20"><image source="ground.png"/></tileset>
                  <layer name="Ground" width="2" height="2"><data encoding="base64" compression="gzip">AAAA</data></layer>
                </map>""",
            )
            val corruptFailure = assertIs<ContentResult.Failure>(ContentLoader.loadMap(corruptTmx, listOf(tilesetRoot)))
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, corruptFailure.error.code)

            // 3. Flip flag set on GID 0
            val flippedZero = encodeLayer(intArrayOf(GroundRules.FLIP_HORIZONTAL_FLAG, 1, 1, 1), null)
            val flipZeroTmx = subDir.resolve("flip-zero.tmx")
            Files.writeString(
                flipZeroTmx,
                """<map orientation="orthogonal" renderorder="right-down" width="2" height="2" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" name="g" tilewidth="20" tileheight="20"><image source="ground.png"/></tileset>
                  <layer name="Ground" width="2" height="2"><data encoding="base64">$flippedZero</data></layer>
                </map>""",
            )
            val flipZeroFailure = assertIs<ContentResult.Failure>(ContentLoader.loadMap(flipZeroTmx, listOf(tilesetRoot)))
            assertEquals(ContentErrorCode.BAD_REFERENCE, flipZeroFailure.error.code)

            // 4. Path traversal escaping allowed map & tileset roots
            val traversalTmx = subDir.resolve("traversal.tmx")
            Files.writeString(
                traversalTmx,
                """<map orientation="orthogonal" renderorder="right-down" width="1" height="1" tilewidth="20" tileheight="20">
                  <tileset firstgid="1" name="g" tilewidth="20" tileheight="20"><image source="../outside.png"/></tileset>
                  <layer name="Ground" width="1" height="1"><data encoding="csv">1</data></layer>
                </map>""",
            )
            val traversalFailure = assertIs<ContentResult.Failure>(ContentLoader.loadMap(traversalTmx, listOf(tilesetRoot)))
            assertEquals(ContentErrorCode.BAD_REFERENCE, traversalFailure.error.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadsOptionalOriginalSkirmishMapsWhenConfiguredViaSystemProperty() {
        val configured = System.getProperty("reforge.original.assets.dir") ?: return
        val originalRoot = Path.of(configured)
        val skirmishDir = originalRoot.resolve("maps/skirmish")
        val tilesetRoot = originalRoot.resolve("tilesets")
        if (!Files.isDirectory(skirmishDir) || !Files.isDirectory(tilesetRoot)) return
        val maps = Files.list(skirmishDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmx") }.sorted().toList()
        }
        assertTrue(maps.isNotEmpty())
        var totalStartingUnits = 0
        var totalResourcePools = 0
        for (mapFile in maps) {
            val res = ContentLoader.loadMap(mapFile, listOf(tilesetRoot))
            val loaded = assertIs<ContentResult.Success<MapDefinition>>(
                res,
                "Failed to load original skirmish map: ${mapFile.fileName}: ${(res as? ContentResult.Failure)?.error?.describe()}",
            ).value
            assertTrue(loaded.widthTiles > 0 && loaded.heightTiles > 0)
            assertTrue(loaded.visualLayers.isNotEmpty())
            totalStartingUnits += loaded.startingUnits.size
            for (row in 0 until loaded.heightTiles) {
                for (col in 0 until loaded.widthTiles) {
                    if (loaded.isResourcePool(col, row)) totalResourcePools++
                }
            }
        }
        assertTrue(totalStartingUnits > 0, "Expected starting units extracted from original skirmish maps")
        assertTrue(totalResourcePools > 0, "Expected resource pools detected in original skirmish maps")
    }
}
