package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class MultilayerConstructionPreviewTest {

    private fun repoRoot(): Path =
        System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }

    private fun loadPhaseSixUnits(): Map<String, UnitDefinition> {
        val packPath = repoRoot().resolve("SimulationTest/public/inputs/phase-six-a-sandbox/json/package")
        return assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(packPath)).value.units
    }

    private fun buildScenario(map: MapDefinition, units: Map<String, UnitDefinition>, spawns: List<UnitSpawn>): Scenario =
        Scenario(
            scenarioId = "multilayer-preview-test",
            map = map,
            unitDefinitions = units,
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            units = spawns,
            coordinateModel = CoordinateModel("preview.coords", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            timeModel = TimeModel("preview.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { idx, s -> s.unitId to (idx + 1L) }.toMap(), 10L),
            economyContract = EconomyContract(mapOf("p1" to 20000L, "p2" to 0L)),
        )

    private fun writeMultilayerTestMap(dir: Path): Path {
        // Tileset local IDs (firstgid=1 -> GID = local + 1):
        // 0 (gid 1): grass (land)
        // 1 (gid 2): shallow water (water)
        // 2 (gid 3): deep water (water)
        // 3 (gid 4): res_pool (Items overlay)
        // 4 (gid 5): water-bridge (Items overlay over water)
        // 5 (gid 6): large-rock (Items obstacle)
        // 6 (gid 7): pathing override grass (land)
        // 7 (gid 8): pathing override cliff (blocked for land)
        val tsx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tileset name="test_multi" tilewidth="20" tileheight="20" tilecount="8" columns="8">
             <image source="dummy.png" width="160" height="20"/>
             <tile id="1"><properties><property name="water" value=""/></properties></tile>
             <tile id="2"><properties><property name="water" value=""/></properties></tile>
             <tile id="3"><properties><property name="res_pool" value=""/></properties></tile>
             <tile id="4"><properties><property name="water-bridge" value=""/></properties></tile>
             <tile id="5"><properties><property name="large-rock" value=""/></properties></tile>
             <tile id="7"><properties><property name="large-cliff" value=""/></properties></tile>
            </tileset>
        """.trimIndent()
                Files.writeString(dir.resolve("test_multi.tsx"), tsx)
        // Write a real dummy.png so TmxReader validates imagePath cleanly
        val dummyImg = java.awt.image.BufferedImage(160, 20, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        javax.imageio.ImageIO.write(dummyImg, "png", dir.resolve("dummy.png").toFile())

        val w = 32
        val h = 24
        val ground = IntArray(w * h) { 1 } // default grass (gid 1)
        val items = IntArray(w * h) { 0 }
        val pathOverride = IntArray(w * h) { 0 }

        // Water band: cols 14..29, rows 2..21 (shallow water cols 14..21 gid 2, deep water cols 22..29 gid 3)
        for (r in 2..21) {
            for (c in 14..21) ground[r * w + c] = 2
            for (c in 22..29) ground[r * w + c] = 3
        }
        // Items layer res_pool at (5, 5) — ground underneath is plain grass (gid 1)!
        items[5 * w + 5] = 4

        // Items layer water-bridge patch across shallow water: cols 15..19, rows 4..8 (5x5 bridge for 3x3 turret/factory check)
        for (r in 4..8) for (c in 15..19) items[r * w + c] = 5

        // Items layer large-rock obstacle at (8, 12) — ground underneath is grass (gid 1)
        items[12 * w + 8] = 6

        // PathingOverride: turn grass at (5, 16) into cliff (gid 8), and turn deep water at (25, 15..18) into passable land (gid 7)
        pathOverride[16 * w + 5] = 8
        for (r in 14..18) for (c in 24..28) pathOverride[r * w + c] = 7

        // Far fog-test mineral pool at (28, 22) (on grass, far from worker at (4, 4))
        items[22 * w + 28] = 4

        fun csv(arr: IntArray): String = (0 until h).joinToString(",") { row ->
            (0 until w).joinToString(",") { col -> arr[row * w + col].toString() }
        }

        val tmx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <map version="1.2" orientation="orthogonal" renderorder="right-down" width="$w" height="$h" tilewidth="20" tileheight="20" infinite="0">
             <tileset firstgid="1" source="test_multi.tsx"/>
             <layer name="Ground" width="$w" height="$h"><data encoding="csv">${csv(ground)}</data></layer>
             <layer name="Items" width="$w" height="$h"><data encoding="csv">${csv(items)}</data></layer>
             <layer name="PathingOverride" width="$w" height="$h"><data encoding="csv">${csv(pathOverride)}</data></layer>
            </map>
        """.trimIndent()
        val tmxPath = dir.resolve("multi_preview.tmx")
        Files.writeString(tmxPath, tmx)
        return tmxPath
    }

    @Test
    fun previewMatchesCoreForItemsPoolBridgeObstaclePathingOverrideAndSeaFactory() {
        val dir = Files.createTempDirectory("ui-p002-001-preview-")
        try {
            val tmxPath = writeMultilayerTestMap(dir)
            val mapRes = ContentLoader.loadMap(tmxPath); val map = assertIs<ContentResult.Success<MapDefinition>>(mapRes, "Failed to load map: $mapRes").value
            val units = loadPhaseSixUnits()

            // Verify merged MapDefinition truth first
            assertTrue(map.isResourcePool(5, 5), "Items res_pool must be detected by MapDefinition")
            assertFalse(map.isFoundationBlocked(17, 6, MovementType.LAND), "Items water-bridge must allow land foundation")
            assertTrue(map.isFoundationBlocked(8, 12, MovementType.LAND), "Items rock must block land foundation")
            assertTrue(map.isFoundationBlocked(5, 16, MovementType.LAND), "PathingOverride cliff must block land foundation")
            assertFalse(map.isFoundationBlocked(26, 16, MovementType.LAND), "PathingOverride land over water must allow land foundation")

            val workerPos = Position(70.0, 90.0)
            val scene = buildScenario(map, units, listOf(UnitSpawn("w1", "p1", "builder", workerPos, 0.0)))

            SandboxSession(scene, options = SandboxOptions(SandboxMode.NO_FOG)).use { session ->
                session.select(workerPos)

                // 1. Extractor on Items-layer res_pool (5, 5) -> center (110, 110) must be CLEAR (previously wrongly BLOCKED!)
                val poolCenter = Position(5 * 20.0 + 10.0, 5 * 20.0 + 10.0)
                val extOnPool = assertNotNull(session.previewConstruction("extractorT1", poolCenter))
                assertEquals(PlacementState.CLEAR, extOnPool.state, "Extractor on Items res_pool must preview CLEAR")

                // 2. Extractor on plain grass (2, 2) must be BLOCKED
                val extOnGrass = assertNotNull(session.previewConstruction("extractorT1", Position(50.0, 50.0)))
                assertEquals(PlacementState.BLOCKED, extOnGrass.state, "Extractor on non-pool grass must preview BLOCKED")

                // 3. Ordinary land building (turret / landFactory) overlapping Items res_pool (5, 5) must be BLOCKED
                val turretOnPool = assertNotNull(session.previewConstruction("c_turret_t1", poolCenter))
                assertEquals(PlacementState.BLOCKED, turretOnPool.state, "Ordinary building on Items res_pool must preview BLOCKED")

                // 4. Ordinary land building on Items rock (8, 12) must be BLOCKED
                val rockPos = Position(8 * 20.0 + 10.0, 12 * 20.0 + 10.0)
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("c_turret_t1", rockPos)?.state,
                    "Land building on Items rock must preview BLOCKED")

                // 5. Ordinary land building on Items water-bridge (17, 6) must be CLEAR
                val bridgePos = Position(17 * 20.0 + 10.0, 6 * 20.0 + 10.0)
                assertEquals(PlacementState.CLEAR, session.previewConstruction("c_turret_t1", bridgePos)?.state,
                    "Land building on Items water-bridge must preview CLEAR")

                // 6. Ordinary land building on raw shallow water (17, 15) must be BLOCKED
                val shallowWaterPos = Position(17 * 20.0 + 10.0, 15 * 20.0 + 10.0)
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("c_turret_t1", shallowWaterPos)?.state,
                    "Land building on shallow water must preview BLOCKED")

                // 7. PathingOverride: cliff on grass (5, 16) -> BLOCKED; land override on deep water (26, 16) -> CLEAR
                val overrideCliffPos = Position(5 * 20.0 + 10.0, 16 * 20.0 + 10.0)
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("c_turret_t1", overrideCliffPos)?.state,
                    "Land building on PathingOverride cliff must preview BLOCKED")
                val overrideLandPos = Position(26 * 20.0 + 10.0, 16 * 20.0 + 10.0)
                assertEquals(PlacementState.CLEAR, session.previewConstruction("c_turret_t1", overrideLandPos)?.state,
                    "Land building on PathingOverride land over water must preview CLEAR")

                // 8. seaFactory: CLEAR on shallow water (17, 15) and deep water (26, 7), BLOCKED on dry land (3, 10)
                assertEquals(PlacementState.CLEAR, session.previewConstruction("seaFactory", shallowWaterPos)?.state,
                    "seaFactory on shallow water must preview CLEAR")
                val deepWaterPos = Position(26 * 20.0 + 10.0, 7 * 20.0 + 10.0)
                assertEquals(PlacementState.CLEAR, session.previewConstruction("seaFactory", deepWaterPos)?.state,
                    "seaFactory on deep water must preview CLEAR")
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("seaFactory", Position(70.0, 210.0))?.state,
                    "seaFactory on dry land must preview BLOCKED")

                // 9. Actually issue build order for extractor on Items res_pool and confirm core accepts & spawns it
                assertIs<ActionResult.Accepted>(session.build("extractorT1", poolCenter))
                repeat(40) { session.tick() }
                assertTrue(
                    session.observation.units.any { it.ownerPlayerId == "p1" && it.typeId == "extractorT1" },
                    "Core must accept and construct extractor on Items-layer res_pool"
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unexploredFogCellsNeverLeakItemsResourcePoolOrTerrainTruth() {
        val dir = Files.createTempDirectory("ui-p002-001-fog-")
        try {
            val tmxPath = writeMultilayerTestMap(dir)
            val mapRes = ContentLoader.loadMap(tmxPath); val map = assertIs<ContentResult.Success<MapDefinition>>(mapRes, "Failed to load map: $mapRes").value
            val units = loadPhaseSixUnits()

            val workerPos = Position(50.0, 50.0)
            val scene = buildScenario(map, units, listOf(UnitSpawn("w1", "p1", "builder", workerPos, 0.0)))

            SandboxSession(scene, options = SandboxOptions(SandboxMode.MEMORY)).use { session ->
                session.select(workerPos)

                // Far unseen mineral pool at (28, 22) and far unseen non-pool grass at (24, 22) must BOTH preview UNEXPLORED
                val hiddenPoolPos = Position(28 * 20.0 + 10.0, 22 * 20.0 + 10.0)
                val hiddenPlainPos = Position(24 * 20.0 + 10.0, 22 * 20.0 + 10.0)
                val hiddenDeepWaterPos = Position(26 * 20.0 + 10.0, 18 * 20.0 + 10.0)

                assertFalse(session.frame.terrain[22 * map.widthTiles + 28].known, "Target pool cell must be unexplored")
                assertFalse(session.frame.terrain[22 * map.widthTiles + 24].known, "Target plain cell must be unexplored")
                assertFalse(session.frame.terrain[18 * map.widthTiles + 26].known, "Target water cell must be unexplored")

                // Zero fog leak: extractor preview on unexplored pool and unexplored plain MUST both be UNEXPLORED (never BLOCKED/CLEAR!)
                assertEquals(PlacementState.UNEXPLORED, session.previewConstruction("extractorT1", hiddenPoolPos)?.state,
                    "Extractor preview on unexplored Items pool must be UNEXPLORED (no fog leak)")
                assertEquals(PlacementState.UNEXPLORED, session.previewConstruction("extractorT1", hiddenPlainPos)?.state,
                    "Extractor preview on unexplored plain grass must be UNEXPLORED (no fog leak)")

                // Ordinary building and seaFactory on unexplored cells must also return UNEXPLORED
                assertEquals(PlacementState.UNEXPLORED, session.previewConstruction("c_turret_t1", hiddenPoolPos)?.state,
                    "Turret preview on unexplored Items pool must be UNEXPLORED (no fog leak)")
                assertEquals(PlacementState.UNEXPLORED, session.previewConstruction("seaFactory", hiddenDeepWaterPos)?.state,
                    "seaFactory preview on unexplored deep water must be UNEXPLORED (no fog leak)")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun builtInDemoMapsUse3x3MineralPoolsAndAllowExtractorOnCenters() {
        val units = loadPhaseSixUnits()
        for (demo in TilesetAtlasResolver.builtInSkirmishMaps()) {
            val built = TmxSkirmishScenarioBuilder.build(demo.path, units)
            val map = built.scenario.map
            val poolCenters = (0 until map.heightTiles).flatMap { r ->
                (0 until map.widthTiles).mapNotNull { c -> if (map.isResourcePool(c, r)) (c to r) else null }
            }
            assertEquals(8, poolCenters.size, "${demo.id} should have exactly 8 mineral pool centers")

            val itemsLayer = assertNotNull(map.visualLayers.firstOrNull { it.kind == MapVisualLayerKind.ITEMS })
            for ((cx, cy) in poolCenters) {
                // Verify 3x3 crater tiles (GIDs 21..29) surround center (GID 25)
                assertEquals(25, itemsLayer.cleanGidAt(cy * map.widthTiles + cx),
                    "${demo.id} pool center ($cx,$cy) should use 3x3 center GID 25")
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val expectedGid = 21 + (dy + 1) * 3 + (dx + 1)
                        assertEquals(expectedGid, itemsLayer.cleanGidAt((cy + dy) * map.widthTiles + (cx + dx)),
                            "${demo.id} pool ($cx,$cy) neighbor ($dx,$dy) should match 3x3 GID $expectedGid")
                        if (dx != 0 || dy != 0) {
                            assertFalse(map.isResourcePool(cx + dx, cy + dy),
                                "Only center cell of 3x3 pool should be marked res_pool")
                        }
                    }
                }
            }

            // Verify extractor preview returns CLEAR on first pool center and ordinary building returns BLOCKED
            val firstPool = poolCenters.first()
            val poolPos = Position(firstPool.first * 20.0 + 10.0, firstPool.second * 20.0 + 10.0)
            val workerPos = Position(firstPool.first * 20.0 + 50.0, firstPool.second * 20.0 + 10.0)
            val testScene = buildScenario(map, units, listOf(UnitSpawn("w1", "p1", "builder", workerPos, 0.0)))
            SandboxSession(testScene, options = SandboxOptions(SandboxMode.NO_FOG)).use { session ->
                session.select(workerPos)
                assertEquals(PlacementState.CLEAR, session.previewConstruction("extractorT1", poolPos)?.state,
                    "${demo.id} extractor preview on 3x3 pool center must be CLEAR")
                assertEquals(PlacementState.BLOCKED, session.previewConstruction("c_turret_t1", poolPos)?.state,
                    "${demo.id} ordinary building preview on 3x3 pool center must be BLOCKED")
            }
        }
    }
}




