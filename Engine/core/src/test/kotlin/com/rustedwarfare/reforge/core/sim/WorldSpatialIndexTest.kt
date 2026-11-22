package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class WorldSpatialIndexTest {

    private fun testUnit(
        id: String,
        owner: String,
        x: Double,
        y: Double,
        radius: Double = 10.0,
        bufferRadius: Double = 14.0,
        movementType: MovementType = MovementType.LAND,
        height: Double = 0.0,
        updateKey: String = id,
        alive: Boolean = true,
        carriedBy: String? = null,
    ): MutableUnit {
        val base = TestContentFactory.openScenario().unitDefinitions.getValue("tank")
        val def = com.rustedwarfare.reforge.core.content.UnitDefinition(
            base.id, base.className, radius, base.displayRadius, base.totalFrames,
            base.imagePath, base.imageScale, base.movement.copy(type = movementType),
            base.retainedUnconsumed,
            movementCollision = MovementCollisionShape.Circle(radius),
        )
        return MutableUnit(
            unitId = id,
            ownerPlayerId = owner,
            typeId = def.id,
            definition = def,
            position = Position(x, y),
            heading = 0.0,
            height = height,
            updateKey = updateKey,
            health = if (alive) def.maximumHealth else 0.0,
            carriedBy = carriedBy,
        )
    }

    @Test
    fun bucket_classification_separates_surface_submerged_and_air() {
        val land = testUnit("u-land", "p1", 100.0, 100.0, movementType = MovementType.LAND, height = 0.0)
        val air = testUnit("u-air", "p1", 100.0, 100.0, movementType = MovementType.AIR, height = 30.0)
        val landedAir = testUnit("u-landed", "p1", 100.0, 100.0, movementType = MovementType.AIR, height = 0.0)
        val subBase = TestContentFactory.openScenario().unitDefinitions.getValue("tank")
        val subDef = com.rustedwarfare.reforge.core.content.UnitDefinition(
            subBase.id, subBase.className, subBase.radius, subBase.displayRadius, subBase.totalFrames,
            subBase.imagePath, subBase.imageScale, subBase.movement.copy(type = MovementType.WATER),
            subBase.retainedUnconsumed,
            submarine = com.rustedwarfare.reforge.core.content.SubmarineProfile.values().first(),
        )
        val sub = MutableUnit("u-sub", "p1", subDef.id, subDef, Position(100.0, 100.0), 0.0, height = -12.0)

        assertEquals(SpatialLayer.SURFACE, WorldSpatialIndex.layerOf(land))
        assertEquals(SpatialLayer.AIR, WorldSpatialIndex.layerOf(air))
        // AIR with flight == null has contactLayerAt == null so it still lands in AIR bucket (not SURFACE)
        assertEquals(SpatialLayer.AIR, WorldSpatialIndex.layerOf(landedAir))
        assertEquals(SpatialLayer.SUBMERGED, WorldSpatialIndex.layerOf(sub))

        val index = WorldSpatialIndex()
        index.syncAll(listOf(land, air, landedAir, sub))
        index.beginStep()

        val surfaceHits = index.queryBounds(SpatialLayer.SURFACE, 80.0, 120.0, 80.0, 120.0)
        val airHits = index.queryBounds(SpatialLayer.AIR, 80.0, 120.0, 80.0, 120.0)
        val subHits = index.queryBounds(SpatialLayer.SUBMERGED, 80.0, 120.0, 80.0, 120.0)

        assertEquals(listOf("u-land"), surfaceHits.map { it.unitId })
        assertEquals(setOf("u-air", "u-landed"), airHits.map { it.unitId }.toSet())
        assertEquals(listOf("u-sub"), subHits.map { it.unitId })
    }

    @Test
    fun dense_250_units_top_k_never_drops_or_returns_null_and_breaks_ties_by_update_key() {
        val units = mutableListOf<MutableUnit>()
        val center = testUnit("center", "p1", 500.0, 500.0, updateKey = "0000")
        units += center
        // 240 units crowded inside a tiny 40x40 area around center, plus exact distance ties.
        for (i in 0 until 240) {
            val col = i % 16
            val row = i / 16
            val x = 480.0 + col * 2.5
            val y = 480.0 + row * 2.5
            units += testUnit("u-$i", "p1", x, y, updateKey = "k-${i.toString().padStart(4, '0')}")
        }
        // Add 4 exact-distance ties at radius 15 from center with deliberately scrambled insertion order
        val ties = listOf(
            testUnit("tie-d", "p1", 515.0, 500.0, updateKey = "t-04"),
            testUnit("tie-b", "p1", 485.0, 500.0, updateKey = "t-02"),
            testUnit("tie-a", "p1", 500.0, 515.0, updateKey = "t-01"),
            testUnit("tie-c", "p1", 500.0, 485.0, updateKey = "t-03"),
        )
        units += ties

        val index = WorldSpatialIndex()
        index.syncAll(units)
        index.beginStep()

        // Request Top-16 neighbors around center -> must return 16 deterministic neighbors, never empty/null
        val top16 = index.queryFrozenTopK(
            observer = center,
            layer = SpatialLayer.SURFACE,
            radius = 80.0,
            limit = 16,
            visible = { true },
        )
        assertEquals(16, top16.size)
        // Verify exact match against brute-force sorted distance then updateKey
        val expected = units.asSequence()
            .filter { it.unitId != center.unitId }
            .filter { hypot(it.position.x - center.position.x, it.position.y - center.position.y) <= 80.0 }
            .sortedWith(compareBy<MutableUnit> {
                val dx = it.position.x - center.position.x
                val dy = it.position.y - center.position.y
                dx * dx + dy * dy
            }.thenBy { it.updateKey })
            .take(16)
            .map { it.unitId }
            .toList()
        assertEquals(expected, top16.map { it.unitId })

        // Verify exact tie breaking at radius 15 on ties-only index
        val tieIndex = WorldSpatialIndex()
        tieIndex.syncAll(listOf(center) + ties)
        tieIndex.beginStep()
        val top2Ties = tieIndex.queryFrozenTopK(center, SpatialLayer.SURFACE, radius = 20.0, limit = 2) { true }
        assertEquals(listOf("tie-a", "tie-b"), top2Ties.map { it.unitId })
    }

    @Test
    fun frozen_snapshot_ignores_mid_step_relocate_while_live_view_tracks_it_and_enforces_visibility() {
        val observer = testUnit("obs", "p1", 100.0, 100.0)
        val ally = testUnit("ally", "p1", 120.0, 100.0)
        val hiddenEnemy = testUnit("enemy-hidden", "p2", 110.0, 100.0)
        val visibleEnemy = testUnit("enemy-visible", "p2", 130.0, 100.0)

        val index = WorldSpatialIndex()
        index.syncAll(listOf(observer, ally, hiddenEnemy, visibleEnemy))
        index.beginStep()

        // Move ally far away in live view mid-step
        ally.position = Position(900.0, 900.0)
        index.relocate(ally)

        // Frozen snapshot must STILL see ally at (120, 100), and must exclude hiddenEnemy!
        val frozenTop = index.queryFrozenTopK(
            observer = observer,
            layer = SpatialLayer.SURFACE,
            radius = 50.0,
            limit = 8,
            visible = { it.unitId != "enemy-hidden" },
        )
        assertEquals(listOf("ally", "enemy-visible"), frozenTop.map { it.unitId })
        assertEquals(Position(120.0, 100.0), frozenTop.first().position)

        // Live view must see ally at (900, 900), NOT near (100, 100)
        val liveNearOrigin = index.queryBounds(SpatialLayer.SURFACE, 80.0, 150.0, 80.0, 150.0).map { it.unitId }.toSet()
        assertFalse("ally" in liveNearOrigin)
        val liveNearFar = index.queryBounds(SpatialLayer.SURFACE, 880.0, 920.0, 880.0, 920.0).map { it.unitId }.toSet()
        assertTrue("ally" in liveNearFar)

        // Swept query from (850, 900) -> (950, 900) must find relocated ally
        val swept = index.querySwept(SpatialLayer.SURFACE, Position(850.0, 900.0), Position(950.0, 900.0), 10.0)
        assertEquals(listOf("ally"), swept.map { it.unitId })
    }

    @Test
    fun fork_and_rollback_restore_index_and_clean_replay_matches_failed_step_retry() {
        val u1 = testUnit("u1", "p1", 100.0, 100.0)
        val u2 = testUnit("u2", "p1", 140.0, 100.0)
        val units = linkedMapOf(u1.unitId to u1, u2.unitId to u2)
        val index = WorldSpatialIndex()
        index.syncAll(units.values)

        // Fork for candidate step with cloned units
        val forkedUnits = units.mapValuesTo(linkedMapOf()) { (_, u) -> u.copy() }
        val forkedIndex = index.fork(forkedUnits)

        val fu1 = forkedUnits.getValue("u1")
        fu1.position = Position(400.0, 400.0)
        forkedIndex.relocate(fu1)
        val u3 = testUnit("u3", "p1", 120.0, 100.0)
        forkedUnits[u3.unitId] = u3
        forkedIndex.insert(u3)

        // Original index must be completely unaffected
        assertEquals(setOf("u1", "u2"), index.queryBounds(SpatialLayer.SURFACE, 80.0, 160.0, 80.0, 120.0).map { it.unitId }.toSet())
        assertTrue(index.queryBounds(SpatialLayer.SURFACE, 380.0, 420.0, 380.0, 420.0).isEmpty())
        index.validate(units)

        // Forked index must reflect mutations
        assertEquals(setOf("u2", "u3"), forkedIndex.queryBounds(SpatialLayer.SURFACE, 80.0, 160.0, 80.0, 120.0).map { it.unitId }.toSet())
        assertEquals(listOf("u1"), forkedIndex.queryBounds(SpatialLayer.SURFACE, 380.0, 420.0, 380.0, 420.0).map { it.unitId })
        forkedIndex.validate(forkedUnits)

        // Integration check: failed candidate step + retry vs clean replay in SimulationContext
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600)))
        val spawns = (0 until 12).map { i ->
            UnitSpawn("t${i.toString().padStart(2, '0')}", "p1", "tank", Position(80.0 + (i % 4) * 24.0, 100.0 + (i / 4) * 24.0), 90.0)
        }
        val scenario = base.copy(map = map, units = spawns, unitDefinitions = mapOf("tank" to tank))

        val cleanCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        val retryCtx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            spawns.forEach { s ->
                assertIs<ActionResult.Accepted>(cleanCtx.submit(MoveAction("m-${s.unitId}", "p1", s.unitId, Position(460.0, 140.0), cleanCtx.world)))
                assertIs<ActionResult.Accepted>(retryCtx.submit(MoveAction("m-${s.unitId}", "p1", s.unitId, Position(460.0, 140.0), retryCtx.world)))
            }
            for (step in 0L until 25L) {
                if (step == 10L) {
                    // Trigger an invalid step rollback (NaN delta)
                    val failed = runCatching { retryCtx.advance(Step(step, Double.NaN, "sim-tick")) }
                    assertTrue(failed.isFailure)
                }
                val obsClean = cleanCtx.advance(Step(step, 1.0, "sim-tick"))
                val obsRetry = retryCtx.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(obsClean.units.map { Triple(it.unitId, it.position, it.heading) },
                    obsRetry.units.map { Triple(it.unitId, it.position, it.heading) },
                    "Step $step diverged after failed candidate rollback")
            }
        } finally {
            cleanCtx.close()
            retryCtx.close()
        }
    }

    @Test
    fun query_frozen_topk_with_buffer_padding_finds_neighbors_across_cell_boundary() {
        val index = WorldSpatialIndex(cellSize = 48.0)
        // Observer at x=47.0 with radius=10.0 (bufferRadius=12.0); cell 0 is [0,48).
        // Candidate at x=96.5 (in cell 2: [96,144)) with radius=10.0 (bufferRadius=12.0).
        // radius = 26.0 -> radius + candBuf = 38.0 (47+38=85 -> cell 1 only!),
        // whereas radius + obsBuf + candBuf = 26 + 12 + 12 = 50.0 (47+50=97 -> reaches cell 2, dist = 49.5 <= 50.0!).
        val obs = testUnit("obs", "p1", 47.0, 20.0, radius = 10.0)
        val cand = testUnit("cand", "p2", 96.5, 20.0, radius = 10.0)
        val tooFar = testUnit("far", "p2", 97.5, 20.0, radius = 10.0)
        index.insert(obs)
        index.insert(cand)
        index.insert(tooFar)
        index.beginStep()

        val found = index.queryFrozenTopK(
            observer = obs,
            layer = SpatialLayer.SURFACE,
            radius = 26.0,
            limit = 8,
            includeBufferPadding = true,
        ) { true }

        assertEquals(listOf("cand"), found.map { it.unitId })
    }

    @Test
    fun query_frozen_bounds_all_layers_center_box_and_max_radius_shrink_on_remove() {
        val index = WorldSpatialIndex(cellSize = 48.0)
        val giant = testUnit("giant", "p1", 50.0, 50.0, radius = 40.0)
        val small = testUnit("small", "p1", 120.0, 50.0, radius = 6.0)
        val air = testUnit("air", "p2", 125.0, 50.0, radius = 6.0, movementType = MovementType.AIR, height = 30.0)
        index.insert(giant)
        index.insert(small)
        index.insert(air)
        index.beginStep()

        val frozenBox = index.queryFrozenBounds(SpatialLayer.SURFACE, 110.0, 130.0, 40.0, 60.0)
        assertEquals(listOf("small"), frozenBox.map { it.unitId })

        val allCenter = index.queryAllLayersCenterBox(Position(120.0, 50.0), 10.0)
        assertEquals(listOf("air", "small"), allCenter.map { it.unitId })

        val allBounds = index.queryAllLayersBounds(115.0, 130.0, 45.0, 55.0)
        assertEquals(listOf("air", "small"), allBounds.map { it.unitId })

        val visited = mutableListOf<String>()
        index.forEachInBoundsUnordered(SpatialLayer.SURFACE, 115.0, 125.0, 45.0, 55.0) { u ->
            visited += u.unitId
            false
        }
        assertEquals(listOf("small"), visited)

        // Removing the giant shrinks maxHardRadius/maxBufferRadius and still validates cleanly
        index.remove("giant")
        index.validate(mapOf("small" to small, "air" to air))
        assertEquals(listOf("small"), index.queryBounds(SpatialLayer.SURFACE, 114.0, 126.0, 44.0, 56.0).map { it.unitId })
    }

    @Test
    fun layer_transition_and_death_removal_keep_index_consistent() {
        val index = WorldSpatialIndex(cellSize = 48.0)
        val flyer = testUnit("flyer", "p1", 100.0, 100.0, radius = 8.0, movementType = MovementType.AIR, height = 20.0)
        index.insert(flyer)
        assertEquals(listOf("flyer"), index.allInLayer(SpatialLayer.AIR).map { it.unitId })

        // Die -> relocate removes from spatial index
        flyer.health = 0.0
        index.relocate(flyer)
        assertTrue(index.allInLayer(SpatialLayer.AIR).isEmpty())
        index.validate(mapOf("flyer" to flyer))
    }
}
