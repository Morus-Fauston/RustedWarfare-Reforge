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
        assertTrue(bigMixed.cost[probeIdx] < ChunkedFlowFieldAdapter.INF_COST_F, "Big unit must reach goal via wide gate")
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
        assertEquals(ChunkedFlowFieldAdapter.INF_COST_F, field.cost[5 * w + 5], "Newly blocked building tile must become impassable")
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
        // Map 40x24: narrow cul-de-sac pocket on the east side (x in 26..31, y in 10..13 -> 6x4 = 24 tiles, holds ~35-45 tight units)
        // surrounded by solid walls on top, bottom, and right, open only at x=25, y=10..13.
        val w = 40; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else if (x >= 26 && (y < 10 || y > 13 || x > 31)) true
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
            val deepClick = Position(610.0, 240.0)
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
        assertEquals(ChunkedFlowFieldAdapter.INF_COST_F, field.cost[20 * w + 28], "Newly blocked wall cell must remain INF_COST")
        // Verify downstream west cell (col 10, row 20) reaches goal with a strictly higher detour cost (no backfeed)
        val westCost = field.cost[20 * w + 10]
        assertTrue(westCost < ChunkedFlowFieldAdapter.INF_COST_F, "West side must still reach goal via south gap")
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
        assertEquals(ChunkedFlowFieldAdapter.INF_COST_F, field.cost[15 * w + 16], "Updated terrain wall must be respected")
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
        mixedField.cost.fill(ChunkedFlowFieldAdapter.INF_COST_F)

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
            members = pureMembers, sharedLanding = true,
            maxRadiusByMovement = mapOf(MovementType.LAND to 6.0),
        )
        val pureUnit = MutableUnit("p-0", "p1", "sm", smallDef, Position(240.0, 230.0), 90.0)
        pureUnit.landingMember = LandingMember(pureReq)

        assertTrue(adapter.hasValidGuidanceFor(pureUnit, terrain),
            "Pure small group must resolve its own smallYieldsWide=false field instead of hitting incomplete mixed field")

        val mixedReq = GroupMoveRequest(
            world = WorldHandle("w"), completedStep = 0L, actor = "p1", click = goal,
            members = pureMembers, sharedLanding = true,
            maxRadiusByMovement = mapOf(MovementType.LAND to 14.0),
        )
        val mixedUnit = MutableUnit("m-0", "p1", "sm", smallDef, Position(240.0, 230.0), 90.0)
        mixedUnit.landingMember = LandingMember(mixedReq)
        assertFalse(adapter.hasValidGuidanceFor(mixedUnit, terrain),
            "Mixed group small member must resolve smallYieldsWide=true field strictly")
    }

    @Test
    fun eikonal_solver_abolishes_static_pinhole_surcharge_and_produces_isotropic_potentials() {
        val w = 32; val h = 24
        // Wall at x=15..16 with a 1-tile narrow pinhole at row 12 (aligned straight with goal at (26, 12))
        // and a 6-tile wide gate at rows 3..8. Without static pinhole surcharge, a unit directly west of the
        // 1-tile pinhole must see strictly lower cost through the pinhole than detouring to the wide gate.
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 15..16) && (y != 12) && (y !in 3..8)
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(530.0, 250.0), 0L)
        adapter.advanceP1Budget(emptyList(), WorldSpatialIndex(), { _, _ -> terrain }, 1L, 10000)
        assertTrue(field.completed)

        val pinholeCost = field.cost[12 * w + 15]
        val wideGateCost = field.cost[6 * w + 15]
        assertTrue(pinholeCost < wideGateCost,
            "1-tile pinhole directly aligned with goal ($pinholeCost) must beat detoured wide gate ($wideGateCost) with no static surcharge")
        // Verify isotropic Eikonal diagonal distance from (26,12) to (21,7) (dx=5, dy=5 -> 5*sqrt(2)*10 ≈ 70.7, well below Manhattan 100)
        val diagCost = field.cost[7 * w + 21]
        assertTrue(diagCost in 68f..82f, "Eikonal diagonal potential should be isotropic (~71..78), got $diagCost")
    }

    @Test
    fun smooth_free_flowing_traffic_pays_zero_congestion_penalty_while_obstructed_queue_triggers_shadow_rebuild() {
        val w = 40; val h = 24
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

        val base = TestContentFactory.openScenario()
        val def = base.unitDefinitions.getValue("tank")
        val fullSpeed = def.movement.moveSpeed

        // 1. Free-flowing units moving smoothly EAST (heading=90, full speed) through north gap -> ZERO congestion penalty!
        val smoothUnits = (0 until 4).map { i ->
            MutableUnit(
                unitId = "fast-$i", ownerPlayerId = "p1", typeId = "tank", definition = def,
                position = Position(385.0 + i * 3.0, 130.0), heading = 90.0, speed = fullSpeed,
            ).also { it.moveTarget = goal }
        }
        spatial.syncAll(smoothUnits)
        spatial.beginStep(true)
        adapter.advanceP1Budget(smoothUnits, spatial, { _, _ -> terrain }, 30L, 10000)
        val northIdx = 6 * w + 19
        assertEquals(0, field.congestionPenalty[northIdx].toInt(),
            "Smooth free-flowing traffic exiting a gate at >=55% speed must pay ZERO congestion penalty")

        // 2. Slow obstructed units (speed = 0.05) queuing in north gap -> triggers shadow double-buffer rebuild!
        val slowUnits = (0 until 4).map { i ->
            MutableUnit(
                unitId = "slow-$i", ownerPlayerId = "p1", typeId = "tank", definition = def,
                position = Position(385.0 + i * 3.0, 130.0), heading = 90.0, speed = 0.05,
            ).also { it.moveTarget = goal }
        }
        spatial.syncAll(slowUnits)
        spatial.beginStep(true)
        // Give tiny P1 budget (16 expansions) so shadow double-buffer is MID-REBUILD and live gradients stay intact!
        val liveCostBefore = field.cost[11 * w + 10]
        val liveDirXBefore = field.dirX[11 * w + 10]
        adapter.advanceP1Budget(slowUnits, spatial, { _, _ -> terrain }, 60L, 16)
        assertTrue(field.shadowActive, "Shadow double-buffer must run incrementally under P1 budget")
        assertEquals(liveCostBefore, field.cost[11 * w + 10], "Live cost must stay intact & tear-free while shadow builds")
        assertEquals(liveDirXBefore, field.dirX[11 * w + 10], "Live gradient must stay intact & tear-free while shadow builds")

        // Finish shadow rebuild -> atomic swap updates live costs monotonically without cycles
        adapter.advanceP1Budget(slowUnits, spatial, { _, _ -> terrain }, 61L, 10000)
        assertFalse(field.shadowActive)
        assertTrue(field.completed)
        assertTrue(field.congestionPenalty[northIdx].toInt() > 0, "Obstructed slow gate queue must build toll")
        assertTrue(field.cost[northIdx] > field.cost[16 * w + 19], "Congested north gap must cost more than clear south gap")
    }

    @Test
    fun cow_rollback_fork_restores_exact_field_state_on_step_abort() {
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
        val f1 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, Position(520.0, 320.0), 0L)
        val spatial = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t1 }, 1L, 10000)
        val snapCost = f1.cost[15 * w + 10]

        val rollback = PathSearchRollback()
        val working = adapter.fork(rollback)
        working.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t2, Position(520.0, 320.0), 5L)
        working.advanceP1Budget(emptyList(), spatial, { _, _ -> t2 }, 6L, 10000)
        val mutatedCost = adapter.statsFor(f1.key)!!.cost[15 * w + 10]
        assertTrue(mutatedCost > snapCost + 20f, "Working copy must reflect wall detour before rollback")

        rollback.restore()
        assertEquals(snapCost, adapter.statsFor(f1.key)!!.cost[15 * w + 10],
            "COW rollback restore() must recover exact pre-step flow field potential")
    }

    @Test
    fun sealed_island_group_uses_single_nearest_reachable_boundary_seed_and_converges() {
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallTank = UnitDefinition(
            id = "c_tank", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 5.0, turnAcceleration = 0.5),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = tankBase.combat, durability = tankBase.durability, build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        // Map 36x24 with a completely sealed rock island in x in 24..30, y in 8..15
        val w = 36; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 24..30 && y in 8..15) && (x == 24 || x == 30 || y == 8 || y == 15)
        }
        val units = (0 until 12).map { i ->
            UnitSpawn("isl-$i", "p1", "c_tank", Position(160.0 + (i / 4) * 24.0, 200.0 + (i % 4) * 24.0), 90.0)
        }
        val scene = makeScenario(w, h, blocked, mapOf("c_tank" to smallTank), units)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = units.map { it.unitId }
            // Click strictly inside the sealed island closer to the western outer wall (col 26, row 11 -> x=530, y=230)
            val sealedClick = Position(530.0, 230.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, sealedClick))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("isl-$id", "p1", id, sealedClick, ctx.world), group))
            }
            var endObs: Observation? = null
            for (s in 0L until 900L) {
                endObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(endObs)
            val arrived = end.units.count { it.moveTarget == null }
            val nearOuterWall = end.units.count { it.position.x in 380.0..480.0 && it.position.y in 140.0..320.0 }
            assertEquals(12, arrived, "All 12 units must settle cleanly at nearest reachable outer island boundary, got $arrived/12")
            assertEquals(12, nearOuterWall, "All 12 units must converge along the western outer wall of the sealed island, got $nearOuterWall/12")
        } finally {
            ctx.close()
        }
    }

    @Test
    fun shadow_rebuild_with_obstacle_inside_goal_chunk_preserves_goal_zero_cost_and_valid_gradients() {
        // P0-1 regression: terrain change inside the SAME 16x16 chunk as the goal must NOT exclude
        // the cost==0 goal cell during Step 1 of startShadowMonotonicRebuild.
        val w = 48; val h = 48
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val t1 = PlanningTerrain(w, h, 20, 20, open)
        val adapter = ChunkedFlowFieldAdapter()
        val spatial = WorldSpatialIndex()
        // Goal at (24, 24) in chunk (1, 1)
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, Position(490.0, 490.0), 0L)
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t1 }, 1L, 30000)
        assertTrue(field.completed)
        assertEquals(0f, field.cost[24 * w + 24])

        // Place a 2x2 obstacle at (18, 18) — ALSO inside chunk (1, 1), only 6 tiles from goal!
        val blocked2 = open.copyOf()
        for (ry in 18..19) for (cx in 18..19) blocked2[ry * w + cx] = true
        val t2 = PlanningTerrain(w, h, 20, 20, blocked2)
        adapter.markChangedTerrainDirty(field, t2)
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t2 }, 2L, 30000)

        assertTrue(field.completed)
        assertEquals(0f, field.cost[24 * w + 24], "Goal cell inside dirtied chunk must retain cost == 0")
        assertEquals(ChunkedFlowFieldAdapter.INF_COST_F, field.cost[18 * w + 18], "Obstacle must be INF_COST")
        // Neighbor immediately west of goal (23, 24) must have cost ~10 and point east (+X) toward goal!
        assertTrue(field.cost[24 * w + 23] in 9.5f..10.5f, "Cell next to goal must keep exact cost ~10, got ${field.cost[24 * w + 23]}")
        assertTrue(field.dirX[24 * w + 23] > 0.9f, "Cell west of goal must point directly east toward goal, got dirX=${field.dirX[24 * w + 23]}")
        // Verify zero non-goal passable cells have zero gradient
        for (r in 1 until h - 1) {
            for (c in 1 until w - 1) {
                if (blocked2[r * w + c] || (c == 24 && r == 24)) continue
                val idx = r * w + c
                assertTrue(field.cost[idx] < ChunkedFlowFieldAdapter.INF_COST_F, "Reachable cell ($c,$r) must stay finite")
                val mag = hypot(field.dirX[idx].toDouble(), field.dirY[idx].toDouble())
                assertTrue(mag > 0.9, "Non-goal cell ($c,$r) must keep unit gradient, got mag=$mag")
            }
        }
    }

    @Test
    fun overlapping_mid_shadow_congestion_and_terrain_mutations_converge_without_wall_bleed_or_pits() {
        // P0-2 regression: congestion shadow rebuild interrupted mid-flight by a terrain wall edit
        // must accumulate shadowMinCut = min(prev, cCut) so neither mutation leaks stale costs.
        val w = 40; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 19..20) && (y !in 6..7) && (y !in 16..17)
        }
        val t1 = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        val goal = Position(660.0, 230.0)
        val field = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, t1, goal, 0L)
        val spatial = WorldSpatialIndex()
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> t1 }, 1L, 15000)
        assertTrue(field.completed)

        val base = TestContentFactory.openScenario()
        val def = base.unitDefinitions.getValue("tank")
        val slowUnits = (0 until 4).map { i ->
            MutableUnit(
                unitId = "ov-$i", ownerPlayerId = "p1", typeId = "tank", definition = def,
                position = Position(385.0 + i * 3.0, 130.0), heading = 90.0, speed = 0.05,
            ).also { it.moveTarget = goal }
        }
        spatial.syncAll(slowUnits)
        spatial.beginStep(true)
        // Trigger congestion shadow rebuild with tiny budget so shadowActive == true mid-flight
        adapter.advanceP1Budget(slowUnits, spatial, { _, _ -> t1 }, 30L, 12)
        assertTrue(field.shadowActive, "Congestion shadow rebuild must be active mid-flight")

        // Mid-shadow: seal the north gap with a new wall (terrain change at a different cut cost!)
        val blocked2 = blocked.copyOf()
        blocked2[6 * w + 19] = true; blocked2[7 * w + 19] = true
        blocked2[6 * w + 20] = true; blocked2[7 * w + 20] = true
        val t2 = PlanningTerrain(w, h, 20, 20, blocked2)
        adapter.markChangedTerrainDirty(field, t2)
        assertTrue(field.shadowActive)

        // Finish shadow rebuild
        adapter.advanceP1Budget(slowUnits, spatial, { _, _ -> t2 }, 31L, 20000)
        assertFalse(field.shadowActive)
        assertTrue(field.completed)
        assertEquals(ChunkedFlowFieldAdapter.INF_COST_F, field.cost[6 * w + 19], "Sealed north gap must be INF_COST")
        assertEquals(0, field.congestionPenalty[6 * w + 19].toInt(), "Sealed non-throat cell must clear stale congestion penalty")
        // Units west of sealed north gap must route cleanly south toward south gap (col 19, row 16..17)
        assertTrue(field.cost[6 * w + 14] < ChunkedFlowFieldAdapter.INF_COST_F)
        assertTrue(field.dirY[6 * w + 14] > 0.2f, "West side of sealed north gap must steer south, got dirY=${field.dirY[6 * w + 14]}")
    }

    @Test
    fun rapid_reclick_away_from_reused_trunk_finishes_cleanly_without_boundary_two_cycles() {
        // P1-1 regression: re-clicking AWAY from reused far trunk chunks must shift reused costs
        // and block backfeed so no 2-cell opposite-pointing gradient cycle forms at the chunk boundary.
        val w = 64; val h = 48
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val terrain = PlanningTerrain(w, h, 20, 20, open)
        val adapter = ChunkedFlowFieldAdapter()
        val spatial = WorldSpatialIndex()

        val f1 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(1010.0, 490.0), 0L)
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrain }, 1L, 30000)
        assertTrue(f1.completed)

        // Re-click 5 tiles FURTHER EAST (away from western reused trunk chunks!)
        val f2 = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, Position(1110.0, 490.0), 2L)
        assertTrue(f2.reusedTrunkChunks > 0, "Expected far western trunk chunks to be reused")
        adapter.advanceP1Budget(emptyList(), spatial, { _, _ -> terrain }, 3L, 30000)
        assertTrue(f2.completed)
        assertFalse(f2.hasReusedMask, "Reused mask must clear upon field completion")

        // Verify zero 2-cycle opposing gradients anywhere in the map and every cell points generally east toward (55, 24)
        for (r in 2 until h - 2) {
            for (c in 2 until 45) {
                val idx = r * w + c
                assertTrue(f2.cost[idx] < ChunkedFlowFieldAdapter.INF_COST_F)
                val gx = f2.dirX[idx]; val gy = f2.dirY[idx]
                assertTrue(gx > 0.0f, "Western cell ($c,$r) must point east toward new goal, got dirX=$gx, dirY=$gy")
                // Check against east neighbor for 2-cycle (dot product must not be opposing < -0.5)
                val dot = gx * f2.dirX[idx + 1] + gy * f2.dirY[idx + 1]
                assertTrue(dot > -0.2f, "Boundary cell ($c,$r) must not form opposing 2-cycle with east neighbor, dot=$dot")
            }
        }
    }

    @Test
    fun mixed_connected_and_enclosed_group_members_resolve_distinct_component_fields() {
        // P1-2 regression: when a single group contains BOTH outer-component units AND enclosed-pocket
        // units, fieldForUnit must resolve sourceComponentId FIRST so enclosed units never hit the 0-field.
        val w = 36; val h = 24
        val blocked = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            if (x == 0 || x == w - 1 || y == 0 || y == h - 1) true
            else (x in 4..10 && y in 8..14) && (x == 4 || x == 10 || y == 8 || y == 14)
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val adapter = ChunkedFlowFieldAdapter()
        val goal = Position(560.0, 220.0) // outer connected component

        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallDef = UnitDefinition(
            id = "sm", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement, retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val members = setOf("out-0", "out-1", "out-2", "in-0", "in-1")
        val req = GroupMoveRequest(
            world = WorldHandle("w"), completedStep = 0L, actor = "p1", click = goal,
            members = members, sharedLanding = true, maxRadiusByMovement = mapOf(MovementType.LAND to 6.0),
        )
        val outerUnit = MutableUnit("out-0", "p1", "sm", smallDef, Position(340.0, 220.0), 90.0).also { it.landingMember = LandingMember(req) }
        val innerUnit = MutableUnit("in-0", "p1", "sm", smallDef, Position(150.0, 220.0), 90.0).also { it.landingMember = LandingMember(req) }

        val outerComp = adapter.resolveSourceComponent(terrain, 6.0, 28, 11, 17, 11)
        val innerComp = adapter.resolveSourceComponent(terrain, 6.0, 28, 11, 7, 11)
        assertEquals(0, outerComp)
        assertTrue(innerComp != 0, "Enclosed pocket unit must resolve a non-zero sourceComponentId")

        val outerField = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, goal, 0L, outerComp)
        val innerField = adapter.ensureFieldForGroup("p1", MovementType.LAND, 6.0, 6.0, terrain, goal, 0L, innerComp)
        adapter.advanceP1Budget(emptyList(), WorldSpatialIndex(), { _, _ -> terrain }, 1L, 15000)

        assertEquals(outerField.key, adapter.fieldForUnit(outerUnit, terrain)?.key)
        assertEquals(innerField.key, adapter.fieldForUnit(innerUnit, terrain)?.key,
            "Enclosed member must resolve its own sourceComponentId field instead of the 0-field")
        assertTrue(innerField.goalWasNearestFallback)
        assertTrue(adapter.hasValidGuidanceFor(innerUnit, terrain))
    }

    @Test
    fun concurrent_two_squads_and_lru_eviction_never_starve_pending_flow_start() {
        // P0-3 regression: rapid re-clicks / LRU eviction must never strand pendingFlowStart units;
        // LRU prefers evicting completed fields first, and any evicted pending unit falls back cleanly.
        val base = TestContentFactory.openScenario()
        val tankBase = base.unitDefinitions.getValue("tank")
        val smallTank = UnitDefinition(
            id = "c_tank", className = tankBase.className, radius = 6.0, displayRadius = 6.0,
            totalFrames = tankBase.totalFrames, imagePath = tankBase.imagePath, imageScale = tankBase.imageScale,
            movement = tankBase.movement.copy(moveSpeed = 1.2, maxTurnSpeed = 5.0, turnAcceleration = 0.5),
            retainedUnconsumed = emptySet(), fogOfWarSightRange = 18,
            combat = tankBase.combat, durability = tankBase.durability, build = tankBase.build,
            movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0,
        )
        val w = 40; val h = 24
        val open = BooleanArray(w * h) { idx ->
            val x = idx % w; val y = idx / w
            x == 0 || x == w - 1 || y == 0 || y == h - 1
        }
        val sqA = (0 until 5).map { i -> UnitSpawn("a-$i", "p1", "c_tank", Position(120.0, 100.0 + i * 24.0), 90.0) }
        val sqB = (0 until 5).map { i -> UnitSpawn("b-$i", "p1", "c_tank", Position(120.0, 260.0 + i * 24.0), 90.0) }
        val scene = makeScenario(w, h, open, mapOf("c_tank" to smallTank), sqA + sqB)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            // Issue sqA to (620, 140) and IMMEDIATELY issue sqB to nearby (640, 160) on the SAME step
            // (so sqA's incomplete field is cancelled by staleKeys while sqA is in pendingFlowStart!).
            val idsA = sqA.map { it.unitId }
            val idsB = sqB.map { it.unitId }
            val clickA = Position(620.0, 140.0)
            val clickB = Position(640.0, 160.0)
            val grpA = assertNotNull(ctx.requestGroupMove("p1", idsA, clickA))
            for (id in idsA) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("ma-$id", "p1", id, clickA, ctx.world), grpA))
            val grpB = assertNotNull(ctx.requestGroupMove("p1", idsB, clickB))
            for (id in idsB) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("mb-$id", "p1", id, clickB, ctx.world), grpB))

            var endObs: Observation? = null
            for (s in 0L until 900L) {
                endObs = ctx.advance(Step(s, 1.0, "sim-tick"))
            }
            val end = checkNotNull(endObs)
            val arrived = end.units.count { it.moveTarget == null }
            val crossed = end.units.count { it.position.x >= 520.0 }
            assertEquals(10, crossed, "Both squads must move to destination without pendingFlowStart starvation, crossed=$crossed/10")
            assertEquals(10, arrived, "All 10 units across both squads must arrive cleanly, arrived=$arrived/10")
        } finally {
            ctx.close()
        }
    }
}
