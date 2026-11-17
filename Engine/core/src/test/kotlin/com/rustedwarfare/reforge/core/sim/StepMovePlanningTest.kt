package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StepMovePlanningTest {
    @Test
    fun failed_candidate_steps_restore_the_active_search_before_retry() {
        val width = 32
        val height = 24
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height) { it % width == 16 && it / width !in 18..20 }))
        val scenario = TestContentFactory.openScenario().copy(map = map)
        val terrain = PlanningTerrain.fromNoFog(map)
        val start = Position(50.0, 70.0)
        val target = Position(550.0, 70.0)
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            fun replay(retry: Boolean): Pair<Long, List<Position>> {
                val steps = CandidateSteps()
                val action = MoveAction("test", "p1", "u1", target, origin.world)
                steps.current.movePlanning.enqueue(action, null)
                if (retry) {
                    val abandoned = steps.current.movePlanning.fork(PathSearchRollback())
                    abandoned.cancel(action.unitId)
                    assertTrue(!abandoned.pending(action.unitId))
                }
                val member = MovePlanningMember(action, null, MovePlanningPose(start, 0.0, ContactLayer.SURFACE,
                    MovementCollisionShape.Circle(0.0)), start, 0.0, terrain, null, false, 0)
                val input = MovePlanningInput(map, listOf(member), emptyList(), true)
                val policy = ExperimentalPolicies().path
                for (step in 0L..1200L) {
                    fun advance(fail: Boolean): PlannedMove? {
                        var planned: PlannedMove? = null
                        steps.advance(scenario, Step(step, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                            if (fail) error("observer failed after planning")
                            Observation("plan-$index", origin.world.id, scenario.scenarioId, index, time,
                                emptyList(), emptyList(), emptyList())
                        }) { candidate ->
                            candidate.state.movePlanning.advance(policy, PathWorkBudget(8), { queued, _, _ ->
                                assertEquals(listOf(action), queued)
                                input
                            },
                                { _, _, _, _, result -> planned = result })
                        }
                        return planned
                    }
                    if (retry && step % 3L == 1L) {
                        assertEquals("observer failed after planning", assertFailsWith<IllegalStateException> {
                            advance(fail = true)
                        }.message)
                        assertTrue(steps.current.movePlanning.pending("u1"))
                    }
                    val result = advance(fail = false) ?: continue
                    assertTrue(assertNotNull(result.counters).expanded > 100, "Fixture must advance a real frontier")
                    val points = assertNotNull(result.slot).path
                    assertEquals(target, points.last())
                    return step to points
                }
                error("The exact route did not finish under the test budget")
            }
            assertEquals(replay(retry = false), replay(retry = true))
        } finally { origin.close() }
    }

    @Test
    fun boarding_releases_a_pending_move_for_either_loading_direction() =
        WaterTestScene.withMap { map ->
            for (direction in LoadDirection.entries) {
                val context = DropshipScene.open(DropshipScene.scenario(map, listOf(
                    UnitSpawn("craft", "p1", "dropship", Position(190.0, 150.0), 90.0),
                    UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
                    UnitSpawn("b", "p1", "builder", Position(200.0, 250.0), 90.0))))
                try {
                    val requestTarget = Position(270.0, 180.0)
                    val request = assertNotNull(context.requestGroupMove("p1", listOf("a", "b"), requestTarget))
                    assertIs<ActionResult.Accepted>(context.submitGroupMove(move(context, "a", requestTarget, "group"), request))
                    val waiting = context.advance(Step(0, 1.0, "sim-tick"))
                    assertTrue(waiting.events.none { it.kind == "movement-started" })
                    val (actor, target) = if (direction == LoadDirection.INTO) "a" to "craft" else "craft" to "a"
                    assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", actor, target, direction, context.world)))
                    val destination = Position(250.0, 250.0)
                    assertIs<ActionResult.Accepted>(context.submit(move(context, "b", destination, "new")))
                    val next = context.advance(Step(1, 1.0, "sim-tick"))
                    assertEquals("craft", next.units.single { it.unitId == "a" }.carriedBy)
                    assertEquals(listOf(Event("movement-started", "b")), next.events.filter { it.kind == "movement-started" }, "$direction")
                    assertEquals(destination, next.units.single { it.unitId == "b" }.moveTarget)
                } finally { context.close() }
            }
        }

    @Test
    fun a_move_replaced_by_stop_at_its_scheduled_step_never_publishes_a_route() =
        WaterTestScene.withMap { map ->
            withShips(map) { context ->
                val action = move(context, "a", Position(640.0, 180.0), "scheduled").copy(atStep = 0)
                assertIs<ActionResult.Accepted>(context.submit(action))
                assertIs<ActionResult.Accepted>(context.submit(StopAction("halt", "p1", "a", context.world, atStep = 0)))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(frame.events.none { it.kind in setOf("movement-started", "path-unreachable") })
                val stopped = frame.units.first { it.unitId == "a" }
                assertEquals(null, stopped.moveTarget)
                assertTrue(stopped.tasks.isEmpty() && stopped.pathPoints.isEmpty())
            }
        }

    @Test
    fun a_selection_receives_all_receipts_before_any_route_and_resumes_across_steps() =
        WaterTestScene.withMap { map ->
            fun replay(): List<Pair<Long, List<Pair<Position, Position?>>>> = withShips(map) { context ->
                context.enablePathDiagnostics(context.world)
                val click = Position(640.0, 190.0)
                val selection = assertNotNull(context.requestGroupMove("p1", ships, click))
                for (id in ships) {
                    val result = assertIs<ActionResult.Accepted>(
                        context.submitGroupMove(move(context, id, click, "group"), selection))
                    assertTrue(result.events.none { it.kind == "movement-started" }, "Receipt completed a route for $id")
                }
                assertEquals(emptyList(), context.drainPathDiagnostics(context.world))
                assertTrue(context.observe().units.all { it.moveTarget == null && it.pathPoints.isEmpty() })
                val frames = mutableListOf<Pair<Long, List<Pair<Position, Position?>>>>()
                for (tick in 0L..900L) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    frames += tick to frame.units.map { it.position to it.moveTarget }
                    if (frame.units.all { it.moveTarget != null }) break
                }
                assertTrue(frames.first().second.all { it.second == null }, "The entire selection searched in one step")
                val planned = frames.last().second.map { assertNotNull(it.second) }
                assertEquals(ships.size, planned.toSet().size)
                frames
            }
            assertEquals(replay(), replay())
        }

    @Test
    fun a_knowledge_change_restarts_only_the_affected_players_pending_plan() =
        WaterTestScene.withMap { map ->
            val click = Position(640.0, 190.0)
            fun run(observer: String?): Pair<Long, List<Position>> = withShips(map) { context ->
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false))
                submitGroup(context, click)
                context.advance(Step(0, 1.0, "sim-tick"))
                if (observer != null) context.acceptVisibleTerrain(context.world, observer,
                    listOf(VisibleTerrainCell(32, 9, true, 1)))
                awaitStart { context.advance(Step(it, 1.0, "sim-tick")) }
            }
            val original = run(null)
            assertTrue(click in original.second)
            assertEquals(original, run("p2"))
            val changed = run("p1")
            assertTrue(changed.first > original.first)
            assertTrue(click !in changed.second)
        }

    @Test
    fun an_occupied_destination_is_rechecked_and_a_distant_spawn_does_not_restart_the_plan() =
        WaterTestScene.withMap { map ->
            val click = Position(640.0, 190.0)
            fun run(spawn: Position?): Pair<Long, List<Position>> = OfflineSandboxHost(shipScene(map), deploymentEnabled = true).use { host ->
                val context = host.context
                submitGroup(context, click)
                host.advance(Step(0, 1.0, "sim-tick"))
                if (spawn != null) {
                    val receipt = host.submit(SandboxCommandRequest(1, context.world, host.generation,
                        "/spawn water-test ${spawn.x.toInt()} ${spawn.y.toInt()} p1"))
                    assertEquals(SandboxCommandStatus.QUEUED, receipt.status)
                }
                awaitStart { host.advance(Step(it, 1.0, "sim-tick")) }.also {
                    if (spawn != null) assertEquals(SandboxCommandStatus.APPLIED, host.receipt(1)?.status)
                }
            }
            val original = run(null)
            assertTrue(click in original.second)
            assertEquals(original, run(Position(765.0, 310.0)))
            val occupied = run(click)
            assertTrue(occupied.first > original.first)
            assertTrue(click !in occupied.second)
        }

    @Test
    fun a_contact_push_during_planning_rebases_the_route_to_the_actual_pose() =
        WaterTestScene.withMap { map ->
            val base = shipScene(map)
            val old = base.unitDefinitions.getValue("water-test")
            val ship = UnitDefinition(old.id, old.className, old.radius, old.displayRadius, old.totalFrames,
                old.imagePath, old.imageScale, old.movement.copy(moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0),
                emptySet(), mass = 3000.0)
            val a = base.units.first { it.unitId == "a" }.position
            val scene = base.copy(unitDefinitions = mapOf(ship.id to ship), units = base.units +
                UnitSpawn("pusher", "p1", ship.id, Position(a.x - 12, a.y), 90.0))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                context.enablePathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(move(context, "pusher", Position(520.0, a.y), "push")))
                submitGroup(context, Position(640.0, 190.0))
                val pressed = context.advance(Step(0, 1.0, "sim-tick")).units.first { it.unitId == "a" }
                assertTrue(pressed.position.x > a.x)
                assertEquals(null, pressed.moveTarget)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-pusher", "p1", "pusher", context.world)))
                context.drainPathDiagnostics(context.world)
                awaitStart { context.advance(Step(it, 1.0, "sim-tick")) }
                val rebased = context.drainPathDiagnostics(context.world).single { it.actionId == "group-a" }
                assertEquals(pressed.position, rebased.searchStart)
            } finally {
                context.close()
            }
        }

    private fun submitGroup(context: SimulationContext, click: Position) {
        val selection = assertNotNull(context.requestGroupMove("p1", ships, click))
        for (id in ships) assertIs<ActionResult.Accepted>(context.submitGroupMove(move(context, id, click, "group"), selection))
    }

    private fun awaitStart(advance: (Long) -> Observation): Pair<Long, List<Position>> {
        for (tick in 1L..900L) {
            val frame = advance(tick)
            val destinations = frame.units.filter { it.unitId in ships }.mapNotNull { it.moveTarget }
            if (destinations.size == ships.size) return tick to destinations
        }
        error("The selection did not receive its routes")
    }

    @Test
    fun replacing_one_pending_member_prevents_its_obsolete_route_from_starting() =
        WaterTestScene.withMap { map ->
            withShips(map) { context ->
                val first = Position(630.0, 190.0)
                val replacement = Position(540.0, 130.0)
                val selection = assertNotNull(context.requestGroupMove("p1", ships, first))
                for (id in ships) assertIs<ActionResult.Accepted>(
                    context.submitGroupMove(move(context, id, first, "first"), selection))
                assertTrue(context.observe().units.all { it.moveTarget == null })
                val initial = context.advance(Step(0, 1.0, "sim-tick"))
                assertTrue(initial.units.all { it.moveTarget == null })
                val accepted = assertIs<ActionResult.Accepted>(context.submit(move(context, "a", replacement, "second")))
                assertTrue(accepted.events.none { it.kind == "movement-started" })
                var starts = 0
                var othersReady = false
                for (tick in 1L..900L) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    val replaced = frame.units.first { it.unitId == "a" }
                    if (Event("movement-started", "a") in frame.events) starts++
                    if (replaced.moveTarget != null) assertEquals(replacement, replaced.moveTarget)
                    othersReady = frame.units.filter { it.unitId != "a" }.all { it.moveTarget != null }
                    if (starts > 0 && othersReady) break
                }
                assertEquals(1, starts)
                assertTrue(othersReady)
            }
        }

    private fun move(context: SimulationContext, unitId: String, target: Position, prefix: String) =
        MoveAction("$prefix-$unitId", "p1", unitId, target, context.world)

    private fun shipScene(map: MapDefinition): Scenario {
        val start = Position(475.0, 140.0)
        val base = WaterTestScene.scenario(map, MovementType.WATER, start)
        return base.copy(units = ships.mapIndexed { i, id ->
            UnitSpawn(id, "p1", "water-test", Position(start.x, start.y + i * 45.0), 90.0)
        })
    }

    private fun <T> withShips(map: MapDefinition, action: (SimulationContext) -> T): T {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(shipScene(map))).context
        try {
            return action(context)
        } finally {
            context.close()
        }
    }

    private val ships = listOf("a", "b", "c")
}
