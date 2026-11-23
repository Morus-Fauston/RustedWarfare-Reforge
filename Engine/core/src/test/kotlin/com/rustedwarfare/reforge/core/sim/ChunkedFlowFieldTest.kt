package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChunkedFlowFieldTest {

    private fun makeScenario(
        w: Int,
        h: Int,
        blocked: BooleanArray,
        defs: Map<String, UnitDefinition>,
        units: List<UnitSpawn>,
    ): Scenario {
        val base = TestContentFactory.openScenario()
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        return base.copy(
            map = map,
            unitDefinitions = defs,
            units = units,
            players = listOf(Player("p1", PlayerControl.LOCAL, "allies")),
            combatContract = CombatContract(
                worldSeed = 4242,
                initialFrameCounter = -1,
                unitObjectIds = units.mapIndexed { idx, u -> u.unitId to (idx + 1L) }.toMap(),
                nextObjectId = units.size + 10L,
            ),
        )
    }

    @Test
    fun small_unit_flow_field_adds_wide_corridor_yield_cost_and_prefers_narrow_side_gap() {
        val w = 40; val h = 24
        // Wall at x=19..20 with TWO gates:
        // - Upper WIDE gate (rows 4..9, 6 tiles = 120u -> fits large radius=14 units!)
        // - Lower NARROW gate (rows 15..16, 2 tiles = 40u -> fits small radius=6, NOT large!)
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 19..20) && (y !in 4..9) && (y !in 15..16)
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        // Goal at (32, 11) — roughly equidistant from upper wide gate (row 6.5) and lower narrow gate (row 15.5)
        val goal = Position(650.0, 230.0)
        val smallMixed = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 14.0, terrain, goal, 0L)
        val bigMixed = adapter.ensureFieldForGroup("p1", MovementType.LAND, 14.0, 14.0, terrain, goal, 0L)
        assertTrue(smallMixed.key.smallYieldsWideCorridor, "Small unit in mixed group must enable wide-corridor yield")
        assertFalse(bigMixed.key.smallYieldsWideCorridor, "Big unit must not add wide-corridor yield surcharge to itself")

        // Build full wavefront
        val dummyIdx = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), dummyIdx, { _, _ -> terrain }, 1L, 10000)
        assertTrue(smallMixed.completed && bigMixed.completed)

        // Compare integration cost at the symmetric midpoint just west of the wall (col 12, row 11):
        // Small unit field should route down toward the narrow gate (dirY > 0) because wide gate has yield surcharge;
        // Big unit field CANNOT pass the 2-tile narrow gate so it MUST route up toward the wide gate (dirY < 0)!
        val probeIdx = 11 * w + 12
        assertTrue(bigMixed.cost[probeIdx] < ChunkedFlowFieldAdapter.INF_COST, "Big unit must reach goal via wide gate")
        assertTrue(bigMixed.dirY[probeIdx] < -0.1f, "Big unit must steer north toward wide gate, got dirY=${bigMixed.dirY[probeIdx]}")
        assertTrue(smallMixed.cost[15 * w + 19] < smallMixed.cost[6 * w + 19],
            "Narrow gate tile cost (${smallMixed.cost[15 * w + 19]}) should beat wide gate tile cost (${smallMixed.cost[6 * w + 19]}) for small units")
    }

    @Test
    fun local_terrain_change_marks_only_affected_chunks_dirty_and_rebuilds_locally() {
        val w = 48; val h = 48 // 3x3 chunks (16x16 each)
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val t1 = PlanningTerrain(w, h, 20, 20, open)
        val adapter = ChunkedFlowFieldAdapter()
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, Position(840.0, 840.0), 0L)
        val dummyIdx = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), dummyIdx, { _, _ -> t1 }, 1L, 20000)
        assertTrue(field.completed)
        val expBefore = field.totalExpansions

        // Place a new building near top-left chunk (col 5, row 5) — far from goal chunk (2, 2)
        val blocked2 = open.copyOf()
        blocked2[5 * w + 5] = true
        blocked2[5 * w + 6] = true
        blocked2[6 * w + 5] = true
        blocked2[6 * w + 6] = true
        val t2 = PlanningTerrain(w, h, 20, 20, blocked2)

        adapter.markChangedTerrainDirty(field, t2)
        assertEquals(1, field.dirtyRebuildCount)
        assertFalse(field.completed)
        // Far goal chunk (2, 2) must remain BUILT and NOT dirty!
        val farChunkIdx = 2 * field.chunksX + 2
        assertTrue(field.chunkBuilt[farChunkIdx] && !field.chunkDirty[farChunkIdx],
            "Far unaffected goal chunk must remain clean during localized building dirty rebuild")

        val rebuildExp = adapter.advanceP1Budget(emptyList(), dummyIdx, { _, _ -> t2 }, 2L, 20000)
        assertTrue(field.completed)
        assertTrue(rebuildExp < expBefore,
            "Localized dirty rebuild expansions ($rebuildExp) must be strictly less than full-map build ($expBefore)")
        assertEquals(ChunkedFlowFieldAdapter.INF_COST, field.cost[5 * w + 5], "Newly blocked building tile must become impassable")
    }

    @Test
    fun rapid_reclick_cancels_incomplete_field_and_reuses_far_trunk_chunks() {
        val w = 64; val h = 48 // 4x3 chunks
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val terrain = PlanningTerrain(w, h, 20, 20, open)
        val adapter = ChunkedFlowFieldAdapter()
        val dummyIdx = WorldSpatialIndex()

        // First click built completely at (56, 24)
        val f1 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(1130.0, 490.0), 0L)
        adapter.advanceP1Budget(emptyList(), dummyIdx, { _, _ -> terrain }, 1L, 30000)
        assertTrue(f1.completed)

        // Rapid re-click nearby at (58, 25) -> should reuse far western trunk chunks!
        val f2 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(1170.0, 510.0), 2L)
        assertTrue(f2.reusedTrunkChunks > 0, "Nearby re-click must reuse far valid trunk chunks, got ${f2.reusedTrunkChunks}")

        // Another rapid re-click BEFORE f2 finishes -> must cancel incomplete f2 immediately!
        val countBefore = adapter.activeFieldCount()
        val f3 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(1190.0, 520.0), 3L)
        assertEquals(countBefore, adapter.activeFieldCount(), "Incomplete old build f2 must be cancelled on rapid re-click")
        assertNotNull(adapter.statsFor(f3.key))
    }

    @Test
    fun cul_de_sac_100_units_fill_interior_and_overflow_settle_outside_without_infinite_pushing() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallTank = UnitDefinition(
            id = "c_tank", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.1, maxTurnSpeed = 5.0, turnAcceleration = 0.5),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = tankBase.combat, durability = tankBase.durability, build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        // Map 40x24: narrow cul-de-sac pocket on the east side (x in 26..34, y in 10..13 -> 9x4 = 36 tiles, holds ~15-20 units)
        // surrounded by solid walls on top, bottom, and right, open only at x=25, y=10..13.
        val w = 40; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x >= 26 && (y < 10 || y > 13 || x > 34)) true
            else false
        }
        val units = (0 until 60).map { i ->
            val col = i / 10
            val row = i % 10
            UnitSpawn(String.format("u-%02d", i), "p1", "c_tank", Position(380.0 - col * 24.0, 140.0 + row * 22.0), 90.0)
        }
        val scene = makeScenario(w, h, blocked, mapOf("c_tank" to smallTank), units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = units.map { it.unitId }
            // Click deep inside the small cul-de-sac pocket
            val deepClick = Position(650.0, 240.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, deepClick))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("cds-$id", "p1", id, deepClick, ctx.world), group))
            }
            var lastObs: Observation? = null
            for (s in 0L until 1800L) {
                lastObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(lastObs)
            val arrived = end.units.count { it.moveTarget == null }
            val insidePocket = end.units.count { it.position.x >= 520.0 && it.position.y in 200.0..280.0 }
            val outsideOverflow = end.units.count { it.position.x < 520.0 && it.moveTarget == null }
            println("cul-de-sac 60: arrived=$arrived/60 insidePocket=$insidePocket outsideOverflow=$outsideOverflow")
            assertTrue(insidePocket >= 8, "Pocket interior must be filled from inside out (>=8 units), got $insidePocket")
            assertTrue(arrived >= 55, "At least 55/60 units must cleanly settle (interior + overflow outside mouth), got $arrived")
            assertTrue(outsideOverflow >= 10, "Overflow units must settle outside cul-de-sac mouth (>=10), got $outsideOverflow")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun mixed_14_unit_group_through_rock_gates_reaches_14_of_14_without_stragglers() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val small = UnitDefinition(
            id = "small", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 5.0, turnAcceleration = 0.5),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = tankBase.combat, durability = tankBase.durability, build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val large = UnitDefinition(
            id = "large", className = tankBase.className, radius = 14.0, displayRadius = 14.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 0.75, maxTurnSpeed = 3.0, turnAcceleration = 0.3),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = tankBase.combat, durability = tankBase.durability, build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(14.0), mass = 24000.0,
        )
        val w = 48; val h = 28
        // Staggered narrow + wide gate wall similar to mixed rock-field 14-unit scenarios
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 22..23) && (y !in 6..10) && (y !in 18..19)
        }
        val units = buildList {
            for (i in 0 until 4) {
                add(UnitSpawn("big-$i", "p1", "large", Position(220.0 - (i / 2) * 38.0, 220.0 + (i % 2) * 44.0), 90.0))
            }
            for (i in 0 until 10) {
                add(UnitSpawn("sm-$i", "p1", "small", Position(140.0 - (i / 5) * 24.0, 170.0 + (i % 5) * 32.0), 90.0))
            }
        }
        val scene = makeScenario(w, h, blocked, mapOf("small" to small, "large" to large), units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = units.map { it.unitId }
            val click = Position(780.0, 260.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, click))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("m14-$id", "p1", id, click, ctx.world), group))
            }
            var endObs: Observation? = null
            for (s in 0L until 1800L) {
                endObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(endObs)
            val arrived = end.units.count { it.moveTarget == null }
            val crossed = end.units.count { it.position.x > 480.0 }
            println("mixed-14: arrived=$arrived/14 crossed=$crossed/14")
            assertEquals(14, crossed, "All 14 mixed units must cross the rock barrier")
            assertEquals(14, arrived, "All 14 mixed units must settle cleanly at destination without stragglers")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun hysteresis_soft_congestion_splits_multi_gap_traffic_and_decays_without_oscillation() {
        val w = 40; val h = 24
        // Wall at x=19..20 with TWO identical narrow gaps: north (rows 6..7) and south (rows 16..17)
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 19..20) && (y !in 6..7) && (y !in 16..17)
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        val goal = Position(660.0, 230.0)
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, goal, 0L)
        val spatial = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrain }, 1L, 10000)
        assertTrue(field.completed)

        val northGapIdx = 6 * w + 19
        val southGapIdx = 16 * w + 19
        assertEquals(0, field.congestionPenalty[northGapIdx].toInt())
        assertEquals(0, field.congestionPenalty[southGapIdx].toInt())

        // Pack 4 friendly units into the north gap tile (col 19, row 6 -> x=390, y=130)
        val base = TestContentFactory.openScenario()
        val def = base.unitDefinitions.getValue("tank")
        val crowdedUnits = (0 until 4).map { i ->
            MutableUnit(
                unitId = "crowd-$i", ownerPlayerId = "p1", typeId = "tank", definition = def,
                position = Position(385.0 + i * 2.0, 128.0 + (i % 2) * 2.0), heading = 90.0,
            )
        }
        spatial.syncAll(crowdedUnits)
        spatial.beginStep(true)

        // Trigger congestion refresh at step 35 (>= 30 ticks interval)
        adapter.advanceP1Budget(crowdedUnits, spatial, { _, _ -> terrain }, 35L, 10000)
        val penAfterCrowding = field.congestionPenalty[northGapIdx].toInt()
        assertTrue(penAfterCrowding >= 6, "Crowded north gap must accumulate hysteresis congestion penalty (>=6), got $penAfterCrowding")
        assertEquals(0, field.congestionPenalty[southGapIdx].toInt(), "Empty south gap must stay at 0 penalty")
        assertTrue(field.cost[northGapIdx] > field.cost[southGapIdx],
            "North gap cost (${field.cost[northGapIdx]}) must exceed clear south gap cost (${field.cost[southGapIdx]}) to split flow")

        // Clear units and verify smooth hysteresis decay (not abrupt zero-jump in a single tick)
        spatial.syncAll(emptyList())
        spatial.beginStep(true)
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrain }, 70L, 10000)
        val penAfterFirstDecay = field.congestionPenalty[northGapIdx].toInt()
        assertTrue(penAfterFirstDecay in 1 until penAfterCrowding,
            "Hysteresis decay must step down smoothly (expected 1..${penAfterCrowding - 1}, got $penAfterFirstDecay)")
    }

    @Test
    fun chunked_flow_field_fork_preserves_rollback_isolation_and_owner_fog_isolation() {
        val w = 32; val h = 32
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val walled = open.copyOf()
        for (y in 4..26) walled[y * w + 16] = true
        val terrainP1 = PlanningTerrain(w, h, 20, 20, open)
        val terrainP2 = PlanningTerrain(w, h, 20, 20, walled)

        val adapter = ChunkedFlowFieldAdapter()
        val f1 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrainP1, Position(520.0, 320.0), 0L)
        val f2 = adapter.ensureFieldForGroup("p2", MovementType.LAND, 6.0, 6.0, terrainP2, Position(520.0, 320.0), 0L)
        val spatial = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrainP1 }, 1L, 10000)
        assertTrue(f1.completed && f2.completed)

        // Owner fog isolation: p1 has open map (direct low cost across x=16), p2 sees wall at x=16 (detour higher cost)
        val westCell = 15 * w + 10
        assertTrue(f2.cost[westCell] > f1.cost[westCell] + 20,
            "p2 field (fog-known wall, cost=${f2.cost[westCell]}) must stay strictly isolated from p1 field (open, cost=${f1.cost[westCell]})")

        // Rollback isolation: mutating a forked adapter must not corrupt the original snapshot
        val forked = adapter.fork()
        val f1SnapCost = f1.cost[westCell]
        forked.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrainP2, Position(520.0, 320.0), 10L)
        forked.advanceP1Budget(emptyList(), spatial, { _, _ -> terrainP2 }, 11L, 10000)
        assertEquals(f1SnapCost, f1.cost[westCell], "Original adapter must remain unaffected when forked copy mutates")
    }

    @Test
    fun mid_build_terrain_obstruction_filters_stale_heap_and_prevents_backfeed_loops() {
        // P1-1 regression: terrain obstruction added while wavefront is mid-build must purge dirty-chunk
        // heap entries, reset inFrontier, and rebuild cleanly without stale INF_COST pops or back-feed loops.
        val w = 48; val h = 48
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val t1 = PlanningTerrain(w, h, 20, 20, open)
        val adapter = ChunkedFlowFieldAdapter()
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, Position(840.0, 840.0), 0L)
        val spatial = WorldSpatialIndex()
        // Advance only partially so heapSize > 0 (mid-build!)
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t1 }, 1L, 350)
        assertFalse(field.completed, "Field must still be mid-build before obstruction arrives")
        assertTrue(field.heapSize > 0)

        // Drop a solid vertical wall across col 28 (rows 1..40) blocking the direct corridor mid-build
        val walled = open.copyOf()
        for (y in 1..40) walled[y * w + 28] = true
        val t2 = PlanningTerrain(w, h, 20, 20, walled)
        adapter.markChangedTerrainDirty(field, t2)

        // Finish build and verify blocked wall tiles stay INF_COST and west-side tiles route cleanly around row 41..46
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t2 }, 2L, 30000)
        assertTrue(field.completed, "Field must complete cleanly after mid-build obstruction")
        assertEquals(ChunkedFlowFieldAdapter.INF_COST, field.cost[20 * w + 28], "Newly blocked wall cell must remain INF_COST")
        // Verify downstream west cell (col 10, row 20) reaches goal with a strictly higher detour cost (no backfeed)
        val westCost = field.cost[20 * w + 10]
        assertTrue(westCost < ChunkedFlowFieldAdapter.INF_COST, "West side must still reach goal via south gap")
        assertTrue(field.dirY[20 * w + 24] > 0.05f, "Cells west of the new wall must steer south around it, got dirY=${field.dirY[20 * w + 24]}")
    }

    @Test
    fun pending_fast_start_units_trigger_terrain_change_detection_before_publication() {
        // P1-2 regression: units with landingMember != null & moveTarget == null (waiting in P0/P1 queue)
        // must still trigger terrainLookup in advanceP1Budget so fast-start never uses stale terrain.
        val w = 32; val h = 32
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val walled = open.copyOf()
        for (y in 4..26) walled[y * w + 16] = true
        val t1 = PlanningTerrain(w, h, 20, 20, open)
        val t2 = PlanningTerrain(w, h, 20, 20, walled)

        val adapter = ChunkedFlowFieldAdapter()
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, Position(520.0, 320.0), 0L)
        val base = TestContentFactory.openScenario()
        val def = base.unitDefinitions.getValue("tank")
        val pendingUnit = MutableUnit(
            unitId = "pending-1", ownerPlayerId = "p1", typeId = "tank", definition = def,
            position = Position(120.0, 320.0), heading = 90.0,
        )
        // Pending fast-start state: moveTarget == null, MOVE task queued
        pendingUnit.taskQueue = pendingUnit.taskQueue.append(UnitTask(UnitTaskKind.MOVE, "act-1", Position(520.0, 320.0)))

        val spatial = WorldSpatialIndex()
        adapter.advanceP1Budget(listOf(pendingUnit), spatial, { _, _ -> t2 }, 1L, 20000)
        assertTrue(field.completed)
        assertEquals(1, field.dirtyRebuildCount, "Pending MOVE unit must trigger terrain change detection")
        assertEquals(ChunkedFlowFieldAdapter.INF_COST, field.cost[15 * w + 16], "Updated terrain wall must be respected")
    }

    @Test
    fun pure_small_group_and_mixed_group_sharing_same_goal_resolve_distinct_yield_fields() {
        // P1-3 regression: pure small group (smallYieldsWide=false) must NOT falsely hit a mixed group's
        // cached smallYieldsWide=true field (or vice versa) when both target the same goal cell.
        val w = 40; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 19..20) && (y !in 4..9) && (y !in 15..16)
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        val goal = Position(650.0, 230.0)
        // Register mixed yield field (incomplete!) AND pure small field (completed!)
        val mixedField = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 14.0, terrain, goal, 0L)
        val pureField = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, goal, 0L)
        assertTrue(mixedField.key.smallYieldsWideCorridor)
        assertFalse(pureField.key.smallYieldsWideCorridor)

        // Complete ONLY pureField via direct budget on pureField (leave mixedField unbuilt at cost=INF)
        val spatial = WorldSpatialIndex()
        // Advance both then reset mixedField to simulate an incomplete mixed build coexisting with completed pure build
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrain }, 1L, 30000)
        mixedField.cost.fill(ChunkedFlowFieldAdapter.INF_COST)

        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallDef = UnitDefinition(
            id = "sm", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement, retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val pureMembers = (0 until 5).map { "p-$it" }.toSet()
        val pureReq = GroupMoveRequest(
            world = WorldHandle("w"), completedStep = 0L, actor = "p1", click = goal,
            members = pureMembers,
            maxRadiusByMovement = mapOf(MovementType.LAND to 6.0),
        )
        val pureUnit = MutableUnit("p-0", "p1", "sm", smallDef, Position(240.0, 230.0), 90.0)
        pureUnit.landingMember = LandingMember(pureReq)

        assertTrue(adapter.hasValidGuidanceFor(pureUnit, terrain),
            "Pure small group must resolve its own smallYieldsWide=false field instead of hitting incomplete mixed field")

        val mixedReq = GroupMoveRequest(
            world = WorldHandle("w"), completedStep = 0L, actor = "p1", click = goal,
            members = pureMembers,
            maxRadiusByMovement = mapOf(MovementType.LAND to 14.0),
        )
        val mixedUnit = MutableUnit("m-0", "p1", "sm", smallDef, Position(240.0, 230.0), 90.0)
        mixedUnit.landingMember = LandingMember(mixedReq)
        assertFalse(adapter.hasValidGuidanceFor(mixedUnit, terrain),
            "Mixed group small member must resolve smallYieldsWide=true field strictly")
    }
}
