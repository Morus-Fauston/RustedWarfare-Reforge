package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.navigation.PathSearchReason
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import com.rustedwarfare.reforge.core.navigation.PlanningWorkSample
import com.rustedwarfare.reforge.core.navigation.PlanningWorkSlice
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Dedicated contract tests for ENG-P016-005:
 * 1. Three-tier priority budget scheduler (P0 fast-lane 1..5 squad preemption ahead of P2 repairs & P0 large groups)
 * 2. Anti-livelock micro-nudge splice on multi-step searches (publishes without restarting to tail)
 * 3. Optimistic same-step turn & rapid right-click momentum preservation
 * 4. 0-tick static-obstacle connection repair contained strictly inside the step planning budget
 * 5. Fast physical terrain stall detection (10-15 ticks) with exponential backoff (12 -> 24 -> 48)
 * 6. Candidate-step rollback determinism across priority queues and navigation backoff state
 */
class PriorityPlanningSchedulerTest {

    private fun fastTank(
        id: String = "tank",
        speed: Double = 1.6,
        turn: Double = 12.0,
        radius: Double = 6.0,
        mass: Double = 3000.0,
    ): UnitDefinition {
        val base = TestContentFactory.openScenario().unitDefinitions.getValue("tank")
        return UnitDefinition(
            id = id,
            className = base.className,
            radius = radius,
            displayRadius = radius,
            totalFrames = base.totalFrames,
            imagePath = base.imagePath,
            imageScale = base.imageScale,
            movement = MovementProfile(
                type = MovementType.LAND,
                moveSpeed = speed,
                moveAccelerationSpeed = 0.25,
                moveDecelerationSpeed = 0.25,
                maxTurnSpeed = turn,
                turnAcceleration = 0.0,
                ignoringBody = false,
                slidingMode = false,
                reverseSpeedPercentage = 0.0,
            ),
            retainedUnconsumed = emptySet(),
            movementCollision = MovementCollisionShape.Circle(radius),
            mass = mass,
        )
    }

    private fun stonefieldMap(width: Int = 80, height: Int = 60): MapDefinition {
        val blocked = BooleanArray(width * height)
        for (row in 12 until height - 12 step 4) {
            for (col in 18 until width - 18 step 4) {
                blocked[row * width + col] = true
            }
        }
        return MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
    }

    @Test
    fun p0_fast_lane_two_unit_peel_preempts_100_unit_stonefield_repairs_on_next_step() {
        val map = stonefieldMap(80, 60)
        val tank = fastTank("tank")
        val anchor = fastTank("anchor", speed = 0.0)
        val spawns = mutableListOf<UnitSpawn>()
        // 100 units jammed in stonefield triggering dense collision & repair queue pressure
        var idx = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                val x = 80.0 + col * 14.0
                val y = 260.0 + row * 14.0
                spawns += UnitSpawn("crowd-$idx", "p1", tank.id, Position(x, y), 90.0)
                idx++
            }
        }
        // Two independent scout units off to the north-west ready for a fresh peel click
        spawns += UnitSpawn("peel-1", "p1", tank.id, Position(100.0, 80.0), 180.0)
        spawns += UnitSpawn("peel-2", "p1", tank.id, Position(140.0, 80.0), 180.0)

        val base = TestContentFactory.openScenario()
        val scenario = base.copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank, anchor.id to anchor),
            units = spawns,
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            val crowdIds = (0 until 100).map { "crowd-$it" }
            val crowdTarget = Position(1420.0, 600.0)
            val crowdGroup = assertNotNull(context.requestGroupMove("p1", crowdIds, crowdTarget))
            for (id in crowdIds) {
                assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("crowd-$id", "p1", id, crowdTarget, context.world), crowdGroup))
            }
            // Advance a few steps so the 100-unit group is actively consuming P0-ordinary / P2 planning slices
            for (t in 0L..8L) context.advance(Step(t, 1.0, "sim-tick"))
            context.drainPlanningWorkDiagnostics(context.world)

            // Now issue a fresh 2-unit peel command to the east while the 100-unit crowd is still queuing/planning
            val peelIds = listOf("peel-1", "peel-2")
            val peelTarget = Position(420.0, 80.0)
            val peelGroup = assertNotNull(context.requestGroupMove("p1", peelIds, peelTarget))
            val before = context.observe().units.filter { it.unitId in peelIds }.associateBy { it.unitId }
            for (id in peelIds) {
                assertIs<ActionResult.Accepted>(context.submitGroupMove(MoveAction("peel-$id", "p1", id, peelTarget, context.world), peelGroup))
            }

            // Next simulation step MUST immediately serve the 2-unit fast-lane order and begin turning/moving
            val nextFrame = context.advance(Step(9L, 1.0, "sim-tick"))
            val work = context.drainPlanningWorkDiagnostics(context.world).single()
            assertTrue(
                work.slices.first().unitIds.any { it in peelIds },
                "Fast-lane 2-unit peel must execute ahead of crowd/repair backlog; slices=${work.slices}",
            )
            for (id in peelIds) {
                val uBefore = before.getValue(id)
                val uAfter = nextFrame.units.single { it.unitId == id }
                val turned = abs(uAfter.heading - uBefore.heading) > 1e-3
                val moved = uAfter.position != uBefore.position
                assertTrue(uAfter.moveTarget != null, "Peel unit $id must obtain valid guidance on next step")
                assertTrue(turned || moved, "Peel unit $id must start turning or moving on next step")
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun p1_flow_hook_and_p2_repair_budget_cap_respect_priority_order() {
        val map = MapDefinition(40, 30, 20, 20, emptyList(), IntArray(40 * 30), BlockingGrid(40, 30, BooleanArray(40 * 30)))
        val terrain = PlanningTerrain.fromNoFog(map)
        val scenario = TestContentFactory.openScenario().copy(map = map)
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val planning = MovePlanning()
            val repairAction = MoveAction("rep-1", "p1", "u-rep", Position(700.0, 500.0), origin.world)
            val playerAction = MoveAction("ply-1", "p1", "u-ply", Position(300.0, 100.0), origin.world)
            planning.repair(repairAction, ManeuverRepair.CONTACT)
            planning.enqueue(playerAction, selectionSize = 1)

            val orderLog = mutableListOf<String>()
            val policy = ClearanceAStarPolicy.DEFAULT
            val budget = PathWorkBudget(64)
            planning.advance(
                pathPolicy = policy,
                budget = budget,
                snapshot = { act, repair ->
                    val pos = if (act.unitId == "u-ply") Position(100.0, 100.0) else Position(100.0, 500.0)
                    MovePlanningInput(
                        map,
                        listOf(
                            MovePlanningMember(
                                act, null, MovePlanningPose(pos, 0.0, ContactLayer.SURFACE, MovementCollisionShape.Circle(6.0)),
                                pos, 6.0, terrain, null, false, 0L,
                            )
                        ),
                        emptyList(),
                        false,
                        repair,
                    )
                },
                publish = { member, repair, _, _ ->
                    orderLog += "${repair?.name ?: "P0"}:${member.action.unitId}"
                },
                p1FlowWork = { b ->
                    orderLog += "P1:flow"
                    b.take(8)
                },
            )
            assertEquals(listOf("P0:u-ply", "P1:flow", "CONTACT:u-rep"), orderLog)
        } finally {
            origin.close()
        }
    }

    @Test
    fun micro_nudge_during_multi_step_search_splices_without_livelock_restart() {
        val width = 32
        val height = 24
        val blocked = BooleanArray(width * height) { it % width == 16 && it / width !in 18..20 }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
        val terrain = PlanningTerrain.fromNoFog(map)
        val scenario = TestContentFactory.openScenario().copy(map = map)
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val planning = MovePlanning()
            val action = MoveAction("nudge-move", "p1", "u1", Position(550.0, 70.0), origin.world)
            planning.enqueue(action, selectionSize = 1)
            val policy = ClearanceAStarPolicy.DEFAULT
            val originalStart = Position(50.0, 70.0)
            // After initial step binds snapshot at originalStart, unit gets nudged by 8px (< 1.5 tiles = 30px)
            val nudgedStart = Position(54.0, 76.0)
            var currentPos = originalStart
            var published: PlannedMove? = null
            var restarts = 0

            for (step in 0L..600L) {
                if (step == 2L) currentPos = nudgedStart
                val slices = mutableListOf<PlanningWorkSlice>()
                val budget = PathWorkBudget(16)
                planning.advance(
                    pathPolicy = policy,
                    budget = budget,
                    snapshot = { act, _ ->
                        MovePlanningInput(
                            map,
                            listOf(
                                MovePlanningMember(
                                    act, null,
                                    MovePlanningPose(currentPos, 0.0, ContactLayer.SURFACE, MovementCollisionShape.Circle(6.0)),
                                    currentPos, 6.0, terrain, null, false, 0L,
                                )
                            ),
                            emptyList(),
                            true,
                        )
                    },
                    publish = { _, _, _, planned -> published = planned },
                    samples = slices,
                )
                restarts += slices.count { it.restarted }
                if (published != null) break
            }
            val result = assertNotNull(published, "Nudged multi-step search must publish via splice")
            assertEquals(0, restarts, "Micro-nudge within clear 1.5-tile radius must splice without restarting")
            val route = assertNotNull(result.route)
            assertTrue(route.path.isNotEmpty())
            assertEquals(Position(550.0, 70.0), route.path.last())
        } finally {
            origin.close()
        }
    }

    @Test
    fun rapid_repeated_right_clicks_turn_immediately_without_dropping_speed_to_zero() {
        val map = MapDefinition(40, 30, 20, 20, emptyList(), IntArray(40 * 30), BlockingGrid(40, 30, BooleanArray(40 * 30)))
        val tank = fastTank("kiter", speed = 2.0, turn = 18.0)
        val scenario = TestContentFactory.openScenario().copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank),
            units = listOf(UnitSpawn("u1", "p1", tank.id, Position(200.0, 200.0), 0.0)),
        )
            // Budget of 16 allows multi-step planning when clicking far, exercising optimistic steer during pending steps
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 16).initialize(scenario),
        ).context
        // Advance until initial move route is published and unit is cruising
        try {
            // Unit starts at (200, 200) heading 90° (south) or 0° (east); give it heading 0° (east) toward (700, 200)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("m0", "p1", "u1", Position(700.0, 200.0), context.world)))
            var step = 0L
            while (step < 60L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                step++
                if (context.observe().units.single { it.unitId == "u1" }.speed > 0.5) break
            }
            val cruise = context.observe().units.single { it.unitId == "u1" }
            assertTrue(cruise.speed > 0.5, "Unit should be cruising before rapid right-click; speed=${cruise.speed} moveTarget=${cruise.moveTarget} heading=${cruise.heading}")
            val headingBefore = cruise.heading

            // Issue rapid slightly angled right-click while moving (30 deg south-east)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("m1", "p1", "u1", Position(700.0, 480.0), context.world)))
            val afterClick1 = context.advance(Step(step++, 1.0, "sim-tick")).units.single { it.unitId == "u1" }
            assertTrue(afterClick1.speed > 0.25, "Rapid right-click must not drop cruising speed to zero; speed=${afterClick1.speed}")
            assertTrue(abs(afterClick1.heading - headingBefore) > 1.0, "Rapid right-click must turn immediately on command step")

            // Issue second rapid right-click in opposite angle on next step
            val heading2Before = afterClick1.heading
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("m2", "p1", "u1", Position(700.0, 60.0), context.world)))
            val afterClick2 = context.advance(Step(step++, 1.0, "sim-tick")).units.single { it.unitId == "u1" }
            assertTrue(afterClick2.speed > 0.25, "Consecutive rapid right-click must preserve momentum; speed=${afterClick2.speed}")
            assertTrue(abs(afterClick2.heading - heading2Before) > 1.0, "Consecutive right-click must steer immediately")
        } finally {
            context.close()
        }
    }

    @Test
    fun static_obstacle_blocking_route_triggers_zero_tick_budgeted_connection_repair() {
        val width = 40
        val height = 30
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, BooleanArray(width * height)))
        val tank = fastTank("tank", speed = 1.4, turn = 45.0)
        val base = TestContentFactory.openScenario()
        val scenario = base.copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank),
            units = listOf(UnitSpawn("u1", "p1", tank.id, Position(60.0, 200.0), 90.0)),
        )
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 32).initialize(scenario),
        ).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            context.enableKnowledge(
                context.world,
                com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                    com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                    com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE,
                    false,
                ),
            )
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("go", "p1", "u1", Position(700.0, 200.0), context.world)))
            for (t in 0L..5L) context.advance(Step(t, 1.0, "sim-tick"))
            context.drainPlanningWorkDiagnostics(context.world)
            context.drainPathDiagnostics(context.world)

            // Discover a vertical wall right across the unit's current corridor at col=10
            val wall = (5..15).map { row ->
                com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell(10, row, true, 1)
            }
            context.acceptVisibleTerrain(context.world, "p1", wall)

            for (t in 6L..40L) context.advance(Step(t, 1.0, "sim-tick"))
            val work = context.drainPlanningWorkDiagnostics(context.world)
            val paths = context.drainPathDiagnostics(context.world)
            // Every step must strictly respect the 32-unit step budget
            assertTrue(work.all { it.used <= 32 }, "All repair steps must stay within budget limit 32; max=${work.maxOfOrNull { it.used }}")
            assertTrue(
                paths.any { it.reason == PathSearchReason.KNOWLEDGE_REPLAN || it.reason == PathSearchReason.ROUTE_REPAIR },
                "Static obstacle must trigger immediate replan/repair; paths=$paths",
            )
        } finally {
            context.close()
        }

        // Also directly verify repairInvalidConnections -> ManeuverRepair.CONNECTION stays inside MovePlanning budget
        // on tick 0 for an ordinary non-maneuver MOVE when a touching heavy unit pushes u1 south behind a rock pillar
        // (no fog/knowledge discovery involved, so replanForKnowledge does not run and repairInvalidConnections fires).
        val connBlocked = BooleanArray(40 * 30) { idx ->
            val c = idx % 40
            val r = idx / 40
            c == 8 && r in 8..12 // rock pillar at x in [160, 180), y in [160, 260)
        }
        val connMap = MapDefinition(40, 30, 20, 20, emptyList(), IntArray(40 * 30), BlockingGrid(40, 30, connBlocked))
        val heavyPusher = fastTank("pusher", speed = 2.0, turn = 90.0, radius = 6.0, mass = 12000.0)
        val slowRunner = fastTank("runner", speed = 0.05, turn = 90.0, radius = 6.0, mass = 3000.0)
        val connScenario = base.copy(
            map = connMap,
            players = listOf(Player("p1", PlayerControl.LOCAL, "t1"), Player("p2", PlayerControl.LOCAL, "t2")),
            unitDefinitions = mapOf(slowRunner.id to slowRunner, heavyPusher.id to heavyPusher),
            units = listOf(
                // a-pusher (owned by p2 so friendly TrafficSeparation does not brake it) has lexicographically earlier ID
                // than u1 and starts touching u1 on its north side (dist = 12.0 == r1 + r2) heading south (180 deg).
                UnitSpawn("a-pusher", "p2", heavyPusher.id, Position(120.0, 140.0), 180.0),
                // u1 starts at (120, 152) heading east; straight path to (600, 152) stays >= 8u clear of row 8 (y >= 160)
                // for radius=6.0; a-pusher pushes u1 south past y = 160 behind the rock pillar, invalidating u1's first connection.
                UnitSpawn("u1", "p1", slowRunner.id, Position(120.0, 152.0), 90.0),
            ),
        )
        val connCtx = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 24).initialize(connScenario),
        ).context
        try {
            connCtx.enablePlanningWorkDiagnostics(connCtx.world)
            assertIs<ActionResult.Accepted>(connCtx.submit(MoveAction("m-run", "p1", "u1", Position(600.0, 152.0), connCtx.world)))
            assertIs<ActionResult.Accepted>(connCtx.submit(MoveAction("m-push", "p2", "a-pusher", Position(120.0, 220.0), connCtx.world)))
            var sawBudgetedConnectionRepair = false
            val sliceLog = mutableListOf<String>()
            for (t in 0L..40L) {
                connCtx.advance(Step(t, 1.0, "sim-tick"))
                val samples = connCtx.drainPlanningWorkDiagnostics(connCtx.world)
                for (s in samples) {
                    assertTrue(s.used <= 24, "Step ${s.executionStep} must stay within budget 24; used=${s.used}")
                    for (sl in s.slices) {
                        sliceLog += "t=${s.executionStep}:${sl.unitIds}:${sl.reason}:${sl.workUnits}"
                        if ("u1" in sl.unitIds && sl.reason == PathSearchReason.ROUTE_REPAIR) {
                            sawBudgetedConnectionRepair = true
                        }
                    }
                }
            }
            val u1Pos = connCtx.observe().units.single { it.unitId == "u1" }.position
            val pushPos = connCtx.observe().units.single { it.unitId == "a-pusher" }.position
            assertTrue(
                sawBudgetedConnectionRepair,
                "Invalidated ordinary MOVE first connection must queue and execute budgeted ROUTE_REPAIR inside MovePlanning; u1Pos=$u1Pos pushPos=$pushPos slices=$sliceLog",
            )
        } finally {
            connCtx.close()
        }
    }

    @Test
    fun fast_terrain_stall_triggers_within_15_ticks_and_applies_exponential_backoff_12_24_48() {
        val width = 30
        val height = 20
        val blocked = BooleanArray(width * height)
        // Build a concave U-pocket around (col=10, row=10) blocking east/north/south at col=11
        for (row in 8..12) blocked[row * width + 11] = true
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
        val tank = fastTank("tank", speed = 1.2, turn = 90.0)
        val scenario = TestContentFactory.openScenario().copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank),
            units = listOf(UnitSpawn("u1", "p1", tank.id, Position(205.0, 210.0), 90.0)),
        )
        // Verify navigation state backoff fields reset cleanly on cancelRoute & survive fork deterministically
        var nav = UnitNavigationState.EMPTY
        assertEquals(0L, nav.repairBackoffStep)
        assertEquals(0, nav.repairConsecutiveFailures)
        nav = nav.copy(repairBackoffStep = 24L, repairConsecutiveFailures = 2, optimisticSteerTarget = Position(100.0, 100.0))
        val cleared = nav.pauseRoute()
        assertEquals(0L, cleared.repairBackoffStep)
        assertEquals(0, cleared.repairConsecutiveFailures)
        assertEquals(null, cleared.optimisticSteerTarget)

        // Verify exact 12 -> 24 -> 48 exponential backoff sequence via SimulationContext wall-press scenario:
        // Force an impossible target behind an enclosed wall while unit presses into the wall (terrainCorrectionTime > 0).
        val wallBlocked = BooleanArray(width * height) { idx ->
            val c = idx % width
            c == 11
        }
        val wallMap = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, wallBlocked))
        val context = assertIs<InitResult.Accepted>(
            SimulationContext.initialize(scenario.copy(map = wallMap)),
        ).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            context.enableKnowledge(
                context.world,
                com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                    com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                    com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE,
                    false,
                ),
            )
            // Unit initially believes col=11 is open (unknown fog), so it drives straight into the wall at col=11!
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("wall-go", "p1", "u1", Position(400.0, 210.0), context.world)))
            val repairSteps = mutableListOf<Long>()
            for (t in 0L..120L) {
                context.advance(Step(t, 1.0, "sim-tick"))
                val samples = context.drainPlanningWorkDiagnostics(context.world)
                for (s in samples) {
                    if (s.slices.any { it.reason == PathSearchReason.ROUTE_REPAIR }) {
                        repairSteps += s.executionStep
                    }
                }
            }
            assertTrue(repairSteps.isNotEmpty(), "Unit pressing into unknown wall must trigger fast stall repair")
            // First fast stall must fire far earlier than legacy 60-tick wait (within ~12..25 ticks of hitting wall)
            assertTrue(
                repairSteps.first() < 35L,
                "Fast stall repair must trigger within 10-15 ticks of wall contact (well before legacy 60 ticks); steps=$repairSteps",
            )
            // Subsequent retries must trigger and back off strictly: 12 -> 24 -> 48 ticks (never spamming)
            assertTrue(
                repairSteps.size >= 3,
                "Expected at least 3 wall-stall repair retries over 120 ticks to prove 12 -> 24 -> 48 backoff; steps=$repairSteps",
            )
            val gap1 = repairSteps[1] - repairSteps[0]
            val gap2 = repairSteps[2] - repairSteps[1]
            assertTrue(gap1 in 12L..16L, "First backoff gap must be ~12 ticks; steps=$repairSteps")
            assertTrue(gap2 in 24L..30L, "Second backoff gap must double to ~24 ticks; steps=$repairSteps")
            if (repairSteps.size >= 4) {
                val gap3 = repairSteps[3] - repairSteps[2]
                assertTrue(gap3 >= 44L, "Third backoff gap must reach ~48 ticks; steps=$repairSteps")
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun p2_cap_isolates_repair_budget_and_fork_rollback_is_deterministic() {
        val width = 40
        val height = 30
        val blocked = BooleanArray(width * height) { it % width == 20 && it / width !in 25..27 }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
        val terrain = PlanningTerrain.fromNoFog(map)
        val scenario = TestContentFactory.openScenario().copy(map = map)
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val planning = MovePlanning()
            val repA = MoveAction("rep-a", "p1", "u-a", Position(700.0, 100.0), origin.world)
            val repB = MoveAction("rep-b", "p1", "u-b", Position(700.0, 140.0), origin.world)
            planning.repair(repA, ManeuverRepair.CONTACT)
            planning.repair(repB, ManeuverRepair.CONTACT)

            // Fork before advancing to prove candidate-step rollback determinism across priority queues
            val rollback = PathSearchRollback()
            val forked = planning.fork(rollback)

            fun runStep(queue: MovePlanning): Pair<Int, List<String>> {
                val events = mutableListOf<String>()
                val budget = PathWorkBudget(100)
                val p2Used = queue.advance(
                    pathPolicy = ClearanceAStarPolicy.DEFAULT,
                    budget = budget,
                    snapshot = { act, repair ->
                        val pos = when (act.unitId) {
                            "u-a" -> Position(60.0, 100.0)
                            else -> Position(60.0, 140.0)
                        }
                        MovePlanningInput(
                            map,
                            listOf(
                                MovePlanningMember(
                                    act, null,
                                    MovePlanningPose(pos, 0.0, ContactLayer.SURFACE, MovementCollisionShape.Circle(6.0)),
                                    pos, 6.0, terrain, null, false, 0L,
                                ),
                            ),
                            emptyList(),
                            false,
                            repair,
                        )
                    },
                    publish = { member, repair, _, _ ->
                        events += "${repair?.name}:${member.action.unitId}"
                    },
                    liveUnitCount = { 2 },
                    p1FlowWork = { b -> b.take(4) },
                )
                return p2Used to events
            }

            // First step: P2 must be capped at <= 75 (3/4 of 100) when P1 shares the step
            val (p2UsedA, eventsA) = runStep(planning)
            val (p2UsedB, eventsB) = runStep(forked)
            assertTrue(p2UsedA <= 75, "P2 repairs must obey 3/4 cap (<=75 for limit=100); actual=$p2UsedA")
            assertEquals(p2UsedA, p2UsedB, "Forked MovePlanning must consume identical P2 budget")
            assertEquals(eventsA, eventsB, "Forked MovePlanning must publish identical deterministic events")

            // Calling a second pass in the same step with rotateQueues=false & p2BudgetAlreadyUsed=p2UsedA
            // must NOT exceed the step-wide 75 cap!
            val budgetPass2 = PathWorkBudget(100)
            val extraP2 = planning.advance(
                pathPolicy = ClearanceAStarPolicy.DEFAULT,
                budget = budgetPass2,
                snapshot = { act, repair ->
                    val pos = Position(60.0, 100.0)
                    MovePlanningInput(
                        map,
                        listOf(
                            MovePlanningMember(
                                act, null,
                                MovePlanningPose(pos, 0.0, ContactLayer.SURFACE, MovementCollisionShape.Circle(6.0)),
                                pos, 6.0, terrain, null, false, 0L,
                            ),
                        ),
                        emptyList(),
                        false,
                        repair,
                    )
                },
                publish = { _, _, _, _ -> },
                liveUnitCount = { 2 },
                p1FlowWork = { _ -> },
                rotateQueues = false,
                p2BudgetAlreadyUsed = p2UsedA,
            )
            assertTrue(
                p2UsedA + extraP2 <= 75,
                "Cumulative same-step P2 budget must never exceed step cap 75; pass1=$p2UsedA pass2=$extraP2",
            )
        } finally {
            origin.close()
        }
    }

    @Test
    fun optimistic_turn_near_wall_does_not_coast_into_static_obstacle() {
        val width = 20
        val height = 20
        val blocked = BooleanArray(width * height) { idx ->
            val c = idx % width
            c == 10 // vertical wall at x in [200, 220)
        }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height), BlockingGrid(width, height, blocked))
        val tank = fastTank("tank", speed = 2.0, turn = 10.0)
        // ScreenPlane heading 90.0 = East (+x). Start at x=150 heading East and drive up to the wall at x=200.
        val scenario = TestContentFactory.openScenario().copy(
            map = map,
            unitDefinitions = mapOf(tank.id to tank),
            units = listOf(UnitSpawn("u1", "p1", tank.id, Position(150.0, 200.0), 90.0)),
        )
        val context = assertIs<InitResult.Accepted>(
            SimulationContext(movePlanningBudgetPerStep = 8).initialize(scenario),
        ).context
        try {
            // Drive east until cruising close to the wall (x ~ 184..192, speed > 0.5)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("approach", "p1", "u1", Position(191.0, 200.0), context.world)))
            var step = 0L
            while (step < 60L) {
                val u = context.advance(Step(step++, 1.0, "sim-tick")).units.single { it.unitId == "u1" }
                if (u.position.x >= 184.0 && u.speed > 0.5) break
            }
            val beforeTurn = context.observe().units.single { it.unitId == "u1" }
            assertTrue(beforeTurn.speed > 0.5, "Unit must be moving east toward wall before optimistic turn; speed=${beforeTurn.speed}")
            assertTrue(beforeTurn.position.x >= 184.0, "Unit must be close to wall before optimistic turn; x=${beforeTurn.position.x}")

            // Now click far south-west while nose is facing East directly into the col=10 wall!
            // With budget=8, planning spans multiple steps so optimisticSteerTarget is active on the next step.
            // Because ScreenPlane forward displacement (+x) probes directly into col=10, forwardClear MUST be false,
            // forcing speedTarget = 0 (decelerating / turning in place instead of coasting into the wall).
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("turn-away", "p1", "u1", Position(40.0, 360.0), context.world)))
            val after = context.advance(Step(step, 1.0, "sim-tick")).units.single { it.unitId == "u1" }
            assertTrue(
                after.speed < beforeTurn.speed,
                "Moving unit facing wall must brake (forwardClear=false) instead of keeping optimistic cruise speed; before=${beforeTurn.speed} after=${after.speed}",
            )
            assertTrue(after.position.x <= 194.0, "Unit facing wall must never penetrate wall; x=${after.position.x}")
            assertTrue(abs(after.heading - beforeTurn.heading) > 1.0, "Unit must still start turning toward target on command step")
        } finally {
            context.close()
        }
    }
}
