package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import com.rustedwarfare.reforge.core.path.PlanningWorkSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RouteContinuationTest {
    @Test
    fun contact_repair_rechecks_live_orders_before_stopping_a_long_stalled_move() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val definition = WaterTestScene.unit(MovementType.WATER)
            val target = Position(10.0, 30.0)
            val start = Position(10.0, 82.0)
            val follower = MutableUnit("follower", "p1", definition.id, definition, start, 0.0).apply {
                stalledRouteTicks = 480L
                moveTarget = target
                pathPoints = mutableListOf(target)
                taskQueue = taskQueue.append(UnitTask(UnitTaskKind.MOVE, "follow", target))
            }
            val leader = MutableUnit("leader", "p1", definition.id, definition, Position(10.0, 70.0), 0.0).apply {
                stalledRouteTicks = 480L
                taskQueue = taskQueue.append(UnitTask(UnitTaskKind.MOVE, "lead", Position(10.0, 22.0)))
            }
            fun commandLeader(id: String, goal: Position) {
                leader.moveTarget = goal
                leader.pathPoints = mutableListOf(goal)
                leader.taskQueue = leader.taskQueue.replaceHead(UnitTask(UnitTaskKind.MOVE, id, goal))
            }
            fun pose(unit: MutableUnit) = MovePlanningPose(
                unit.position, unit.heading, ContactLayer.SURFACE, definition.movementCollision)
            val action = MoveAction("follow", "p1", follower.unitId, target, WorldHandle("contact-repair"))
            val terrain = PlanningTerrain.fromNoFog(map, MovementType.WATER)
            val member = MovePlanningMember(action, follower, pose(follower), start, definition.radius,
                terrain, HullRoute.forUnit(follower, maneuver = true), false, 0L)
            fun blocker() = MovePlanningOccupant(leader.copy(), pose(leader), null, idle = false, obstructsRoute = true)
            val policy = ExperimentalPolicies().path
            fun repair(occupants: List<MovePlanningOccupant>): PlannedMove {
                val queue = MovePlanning()
                var result: PlannedMove? = null
                queue.repair(action, ManeuverRepair.CONTACT)
                repeat(40) {
                    queue.advance(policy, PathWorkBudget(512), { _, _, reason ->
                        MovePlanningInput(map, listOf(member), occupants, false, reason)
                    }, { _, _, _, _, planned -> result = planned })
                    result?.let { return it }
                }
                error("Contact repair never finished")
            }
            val clearGoal = Position(10.0, 22.0)
            val opposingGoal = Position(10.0, 110.0)
            commandLeader("lead", clearGoal)
            val waiting = repair(listOf(blocker()))
            assertEquals(null, waiting.slot)
            assertTrue(!waiting.nearest)
            assertEquals(target, assertNotNull(repair(emptyList()).slot).position)

            commandLeader("opposing", opposingGoal)
            val opposed = repair(listOf(blocker()))
            assertTrue(opposed.nearest)
            assertEquals(start, assertNotNull(opposed.slot).position)

            val delayed = object : PathPolicy by policy {
                override fun beginExact(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                                        hull: HullRoute?, occupancy: RouteOccupancy?, counters: PathSearchCounters?): ExactPathSearch {
                    val search = policy.beginExact(map, start, target, radius, hull, occupancy, counters)
                    return object : ExactPathSearch by search {
                        private var first = true
                        override fun advance(budget: PathWorkBudget, cache: PathClearanceCache?) {
                            if (first) {
                                first = false
                                budget.take(budget.limit - budget.used)
                            } else search.advance(budget, cache)
                        }
                    }
                }
            }
            val queue = MovePlanning()
            val publications = mutableListOf<PlannedMove>()
            val work = mutableListOf<PlanningWorkSlice>()
            queue.repair(action, ManeuverRepair.CONTACT)
            fun tick() = queue.advance(delayed, PathWorkBudget(512), { _, _, reason ->
                MovePlanningInput(map, listOf(member), listOf(blocker()), false, reason)
            }, { _, _, _, _, result -> publications += result }, work)
            tick()
            assertTrue(publications.isEmpty())
            commandLeader("clear", clearGoal)
            repeat(10) { if (work.none { it.restarted }) tick() }
            assertTrue(work.any { it.restarted }, work.toString())
            assertTrue(publications.isEmpty(), publications.toString())
        }

    @Test
    fun continuously_blocked_commands_can_end_when_the_other_unit_also_has_a_move() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val first = Position(10.0, 110.0)
            val second = Position(10.0, 50.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, first).copy(units = listOf(
                UnitSpawn("northbound", "p1", "water-test", first, 0.0),
                UnitSpawn("southbound", "p1", "water-test", second, 180.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            // Isolate low-level hard-contact movement-blocked -> ROUTE_REPAIR -> movement-nearest-reached fallback contract.
            context.trafficSeparationEnabled = false
            try {
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("north", "p1", "northbound",
                    Position(10.0, 30.0), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("south", "p1", "southbound",
                    Position(10.0, 130.0), context.world)))
                val frames = (0L..800L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                val stalled = frames.first { frame -> frame.events.any { it.kind == "movement-blocked" } }
                assertTrue(stalled.units.all { it.moveTarget != null })
                val retries = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
                assertEquals(setOf("north", "south"), retries.map { it.actionId }.toSet())
                val stopped = frames.flatMap { frame ->
                    frame.events.filter { it.kind == "movement-nearest-reached" }.map { frame to it }
                }
                assertEquals(setOf("northbound", "southbound"), stopped.map { it.second.unitId }.toSet())
                assertTrue(stopped.all { it.first.stepIndex > stalled.stepIndex })
                assertTrue(stopped.all { (frame, event) ->
                    val unit = frame.units.single { it.unitId == event.unitId }
                    unit.moveArrival?.originalTarget == if (event.unitId == "northbound") Position(10.0, 30.0) else Position(10.0, 130.0)
                })
                assertTrue(frames.last().units.all { it.moveTarget == null && it.tasks.isEmpty() && it.speed == 0.0 })
                assertEquals(frames[780].units.map { it.position }, frames.last().units.map { it.position })
            } finally {
                context.close()
            }
        }

    @Test
    fun continuation_work_prefers_the_nearer_handoff_and_uses_stable_unit_order_for_equal_times() =
        WaterTestScene.withMap { base ->
            val rows = listOf("b-tied" to 310.0, "z-near" to 70.0, "a-tied" to 190.0)
            val spawns = rows.map { (id, y) ->
                UnitSpawn(id, "p1", "water-test", Position(if (id == "z-near") 54.0 else 50.0, y), 90.0)
            }
            val obstacles = rows.flatMap { (_, y) ->
                val row = (y / base.tileHeight).toInt()
                listOf(5 to row, 5 to (row + 1))
            }.toSet()
            // Fit all three circle route publications in the same step so only handoff priority
            // can distinguish the tied units. The geometry around the routes stays the same.
            val width = 14
            val height = 18
            val water = base.groundGids[base.widthTiles - 1]
            val map = MapDefinition(width, height, base.tileWidth, base.tileHeight, base.tileSets,
                IntArray(width * height) { water },
                BlockingGrid(width, height, BooleanArray(width * height)), obstacles)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, spawns.first().position).copy(units = spawns)
            val context = assertIs<InitResult.Accepted>(SimulationContext(ExperimentalPolicies(
                routePreparationBudget = { 1 })).initialize(scene)).context
            try {
                context.enableRouteContinuationDiagnostics(context.world)
                for ((id, y) in rows) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("move-$id", "p1", id, Position(170.0, y), context.world)))
                val published = context.advance(Step(0L, 1.0, "sim-tick"))
                assertEquals(rows.map { it.first }.toSet(), published.events.filter { it.kind == "movement-started" }.map { it.unitId }.toSet())
                val firstAnchors = published.units.associate { it.unitId to it.pathPoints.first() }
                for (tick in 1L..4L) context.advance(Step(tick, 1.0, "sim-tick"))
                val samples = context.drainRouteContinuationDiagnostics(context.world).filter {
                    it.anchor == firstAnchors.getValue(it.unitId)
                }
                val requests = samples.filter { it.stage == RouteContinuationStage.REQUESTED }
                assertEquals(listOf("z-near", "a-tied", "b-tied"), requests.map { it.unitId })
                assertTrue(requests[0].estimatedRemainingTicks < requests[1].estimatedRemainingTicks)
                assertEquals(requests[1].estimatedRemainingTicks, requests[2].estimatedRemainingTicks)
                val ready = samples.filter { it.stage == RouteContinuationStage.READY }
                assertEquals(requests.map { it.unitId }, ready.map { it.unitId })
                assertEquals(listOf(0L, 1L, 2L), ready.map { it.stepIndex })
                println("Ready order=$ready")
            } finally {
                context.close()
            }
        }

    @Test
    fun a_sustained_visible_contact_uses_a_legal_detour_and_finishes_the_same_move() =
        WaterTestScene.withMap { map ->
            val start = Position(450.0, 190.0)
            val occupied = Position(510.0, 190.0)
            val target = Position(570.0, 190.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(units = listOf(
                UnitSpawn("mover", "p1", "water-test", start, 90.0),
                UnitSpawn("occupant", "p2", "water-test", occupied, 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-detour", "p1", "mover", target, context.world)))
                val frames = (0L..1150L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                fun Observation.mover() = units.single { it.unitId == "mover" }
                val arrived = frames.singleOrNull { Event("movement-arrived", "mover") in it.events }
                assertTrue(arrived != null, "Mover must route smoothly around visible occupant and arrive, last mover=${frames.last().mover()}")
                assertTrue(ScreenPlane.distanceSquared(arrived.mover().position, target) < 49.0)
                assertTrue(frames.all { frame ->
                    frame.units.single { it.unitId == "occupant" }.position == occupied &&
                        ScreenPlane.distanceSquared(frame.mover().position, occupied) >= 144.0 - 1e-8
                })
                assertTrue(frames.none { Event("path-unreachable", "mover") in it.events })
                println("Open water smooth avoidance arrival=${arrived.stepIndex}")
            } finally {
                context.close()
            }
        }

    @Test
    fun a_blocked_single_channel_stops_at_the_nearest_pose_without_crossing_the_occupant() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val goal = Position(10.0, 30.0)
            val start = Position(10.0, 110.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(units = listOf(
                UnitSpawn("mover", "p1", "water-test", start, 0.0),
                UnitSpawn("occupant", "p2", "water-test", Position(10.0, 70.0), 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("through-channel", "p1", "mover", goal, context.world)))
                val frames = (0L..800L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                val stop = frames.single { Event("movement-nearest-reached", "mover") in it.events }
                val mover = stop.units.single { it.unitId == "mover" }
                val receipt = checkNotNull(mover.moveArrival)
                assertEquals(goal, receipt.originalTarget)
                assertEquals(null, mover.moveTarget)
                assertEquals(82.0, mover.position.y, 1e-6)
                assertEquals(0.0, mover.speed)
                assertTrue(frames.all { it.units.single { unit -> unit.unitId == "mover" }.position.y >= 82.0 - 1e-6 })
                val samples = context.drainPathDiagnostics(context.world)
                assertTrue(samples.any { it.actionId == "through-channel" && it.outcome == PathSearchOutcome.FOUND })
            } finally {
                context.close()
            }
        }

    @Test
    fun a_late_continuation_holds_at_a_safe_join_and_then_resumes_the_original_command() =
        WaterTestScene.withMap { base ->
            val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
                base.tileSets, base.groundGids, base.blocking, setOf(25 to 9, 25 to 10))
            val start = Position(450.0, 190.0)
            val goal = Position(570.0, 190.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start)
            val delayedStep = 220L
            fun run(blockUntil: Long): Triple<List<Position>, List<Observation>, List<RouteContinuationSample>> {
                val context = assertIs<InitResult.Accepted>(SimulationContext(ExperimentalPolicies(
                    routePreparationBudget = { step -> if (step < blockUntil) 0 else 8 })).initialize(scene)).context
                try {
                    context.enableRouteContinuationDiagnostics(context.world)
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-order", "p1", "w1", goal, context.world)))
                    val publication = context.advanceToMoveOutcome(0L, "w1")
                    val initial = publication.units.single().pathPoints
                    assertTrue(initial.size > 2, "Detour needs a preparable handoff")
                    val frames = listOf(publication) + ((publication.stepIndex + 1L)..900L).map {
                        context.advance(Step(it, 1.0, "sim-tick"))
                    }
                    return Triple(initial, frames, context.drainRouteContinuationDiagnostics(context.world))
                } finally {
                    context.close()
                }
            }
            val early = run(0)
            val late = run(delayedStep)
            assertEquals(early.first, late.first)
            val waiting = late.second.subList(200, 220)
            val waitingPosition = waiting.first().units.single().position
            val joinRadius = checkNotNull(scene.movementContract).pathPointRadius
            assertTrue(waitingPosition != start)
            assertTrue(ScreenPlane.distanceSquared(waitingPosition, late.first.first()) < joinRadius * joinRadius,
                "Waited at $waitingPosition for join ${late.first.first()}")
            assertTrue(waiting.all { frame ->
                val mover = frame.units.single()
                mover.position == waitingPosition && mover.moveTarget == goal && mover.pathPoints == late.first &&
                    Event("movement-blocked", "w1") !in frame.events
            }, "A delayed successor should leave the same move and route active at the safe join")
            assertTrue(early.second[219].units.single().pathPoints != early.first)
            fun firstJoin(samples: List<RouteContinuationSample>) = samples.filter {
                it.anchor == early.first[0] && it.successor == early.first[1]
            }.also { rows ->
                assertTrue(rows.all { it.originMoveId == "one-order" && it.unitId == "w1" })
            }
            val preparedEarly = firstJoin(early.third)
            val preparedLate = firstJoin(late.third)
            assertEquals(listOf(RouteContinuationStage.REQUESTED, RouteContinuationStage.READY,
                RouteContinuationStage.HANDED_OFF), preparedEarly.map { it.stage })
            assertTrue(preparedEarly[0].estimatedRemainingTicks > 0)
            assertTrue(preparedEarly[1].stepIndex < preparedEarly[2].stepIndex)
            assertEquals(listOf(RouteContinuationStage.REQUESTED, RouteContinuationStage.WAITING,
                RouteContinuationStage.READY, RouteContinuationStage.HANDED_OFF), preparedLate.map { it.stage })
            assertEquals(delayedStep, preparedLate[2].stepIndex)
            assertEquals(delayedStep, preparedLate[3].stepIndex)
            assertTrue(preparedLate[1].stepIndex < delayedStep)
            println("First join early=$preparedEarly late=$preparedLate")
            for ((label, frames) in listOf("early" to early.second, "late" to late.second)) {
                for (step in listOf(0, 200, 219, 240, 400, 600, 800, 900)) {
                    val unit = frames[step].units.single()
                    println("$label step=$step position=${unit.position} heading=${unit.heading} speed=${unit.speed} path=${unit.pathPoints} events=${frames[step].events}")
                }
                val terminal = frames.single { Event("movement-arrived", "w1") in it.events }
                assertEquals(null, terminal.units.single().moveTarget)
                assertTrue(ScreenPlane.distanceSquared(terminal.units.single().position, goal) < 49.0)
            }
            val repeat = run(delayedStep)
            assertEquals(late.second.map { it.units.single().position }, repeat.second.map { it.units.single().position })
            assertEquals(late.third, repeat.third)
            println("Route join at ${late.first.first()}, delayed preparation at $delayedStep, hold at $waitingPosition")
        }

    @Test
    fun a_temporary_contact_near_the_goal_preserves_the_move_until_the_occupant_clears() =
        WaterTestScene.withMap { water ->
            val channel = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
                water.tileSets, water.groundGids, water.blocking, buildSet {
                    for (row in 0 until water.heightTiles) for (col in 20 until water.widthTiles) {
                        if (row != 9 && !(col == 24 && row in 10..12)) add(col to row)
                    }
                })
            for ((originX, goalX, releaseStep) in listOf(Triple(459.0, 502.0, 256L), Triple(460.0, 494.9, 101L))) {
                val origin = Position(originX, 190.0)
                val goal = Position(goalX, 190.0)
                val base = WaterTestScene.scenario(channel, MovementType.WATER, origin)
                val ordinary = base.unitDefinitions.getValue("water-test")
                val crossing = UnitDefinition("crossing", ordinary.className, ordinary.radius, ordinary.displayRadius,
                    ordinary.totalFrames, ordinary.imagePath, ordinary.imageScale,
                    ordinary.movement.copy(moveSpeed = 2.0, moveAccelerationSpeed = 1.0, maxTurnSpeed = 180.0),
                    emptySet())
                val scene = base.copy(
                    unitDefinitions = base.unitDefinitions + (crossing.id to crossing),
                    players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                    units = listOf(UnitSpawn("mover", "p1", "water-test", origin, 90.0),
                        UnitSpawn("occupant", "p2", crossing.id, Position(490.0, 217.0), 0.0)))
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    fun Observation.mover() = units.single { it.unitId == "mover" }
                    val arrived = Event("movement-arrived", "mover")
                    val blocked = Event("movement-blocked", "mover")
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("through-after-clear", "p1", "mover", goal, context.world)))
                    val first = context.advance(Step(0, 1.0, "sim-tick"))
                    assertEquals(goal, first.mover().moveTarget)
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter", "p2", "occupant",
                        Position(490.0, 190.0), context.world)))
                    val waiting = (1L until releaseStep).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    assertTrue(waiting.any { blocked in it.events }, "goal=$goal events=${waiting.flatMap { it.events }.distinct()} " +
                        "end=${waiting.last().mover()}")
                    assertTrue(waiting.last().mover().position.x < goal.x, waiting.last().mover().position.toString())
                    val premature = waiting.firstOrNull {
                        arrived in it.events || Event("movement-nearest-reached", "mover") in it.events
                    }
                    assertEquals(null, premature?.stepIndex, "A temporary blockage ended the original move: " +
                        "${premature?.events} at ${premature?.units?.map { it.position }}")
                    assertEquals(goal, waiting.last().mover().moveTarget)

                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("clear", "p2", "occupant",
                        Position(490.0, 238.0), context.world)))
                    val released = (releaseStep..(releaseStep + 204)).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    val completion = released.single { arrived in it.events }
                    assertEquals(null, completion.mover().moveTarget)
                    assertTrue(completion.mover().position.x > goal.x - 7.0)
                    println("Temporary contact goal=$goal firstBlocked=${waiting.first { blocked in it.events }.stepIndex} clear=$releaseStep arrival=${completion.stepIndex}")
                } finally {
                    context.close()
                }
            }
        }
}
