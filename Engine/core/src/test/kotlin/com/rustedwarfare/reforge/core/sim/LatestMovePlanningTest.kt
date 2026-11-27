package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.navigation.PathSearchReason
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LatestMovePlanningTest {
    @Test
    fun failed_steps_do_not_change_the_completion_step_of_reused_maneuver_searches() {
        val width = 18
        val height = 10
        val terrain = PlanningTerrain(width, height, 20, 20,
            BooleanArray(width * height) { it % width == 9 })
        val origin = Position(121.0, 101.0)
        val hull = RectangularDeparture(MovementCollisionShape.Rectangle(62.0, 20.0), 90.0, false, true)
        fun replay(retry: Boolean): List<Int> {
            val cache = PathClearanceCache()
            val policy = ClearanceAStarPolicy.DEFAULT
            val used = mutableListOf<Int>()
            for (target in listOf(Position(290.0, 101.0), Position(290.0, 121.0))) {
                var active = policy.beginExact(terrain, origin, target, 0.0, hull)
                while (!active.finished) {
                    if (retry) {
                        val rollback = PathSearchRollback()
                        val abandoned = active.fork(rollback)
                        abandoned.advance(PathWorkBudget(512), cache.fork())
                        rollback.restore()
                    }
                    val rollback = PathSearchRollback()
                    val candidate = active.fork(rollback)
                    val budget = PathWorkBudget(512)
                    candidate.advance(budget, cache.fork())
                    rollback.commit()
                    active = candidate
                    used += budget.used
                }
                assertEquals(null, active.path)
            }
            return used
        }
        assertEquals(replay(false), replay(true))
    }

    @Test
    fun stopping_one_member_after_a_repeated_selection_never_publishes_its_saved_move() =
        WaterTestScene.withMap { map ->
            val result = replay(map, reissue = true, stopped = "b")
            assertEquals(setOf("a", "c"), result.firstOwnMotion.keys)
            assertEquals(setOf("latest-a", "latest-c"), result.publishedActions)
            assertTrue("b" !in result.destinations)
        }

    @Test
    fun continual_same_point_clicks_do_not_starve_the_latest_command() =
        WaterTestScene.withMap { map ->
            val single = replay(map, reissue = false)
            val repeated = replay(map, reissue = true, repeatThrough = 14L)
            assertEquals(single.destinations, repeated.destinations)
            assertTrue(repeated.firstOwnMotion.values.max() <= single.firstOwnMotion.values.max() + 1L)
            assertTrue(repeated.publishedActions.all { it.startsWith("latest-") })
        }

    @Test
    fun changing_only_part_of_a_selection_cannot_publish_saved_targets_for_those_members() =
        WaterTestScene.withMap { map ->
            val result = replay(map, reissue = true, replacement = Position(650.0, 200.0),
                reissueMembers = listOf("a", "c"))
            assertEquals(setOf("latest-a", "first-b", "latest-c"), result.publishedActions)
        }

    @Test
    fun alternative_destinations_do_not_repeat_all_the_same_failed_maneuver_geometry() =
        WaterTestScene.withMap { base ->
            val width = 24
            val height = 12
            val land = BooleanArray(width * height) { it % width == 12 }
            val map = MapDefinition(width, height, 20, 20, base.tileSets,
                IntArray(width * height) { if (land[it]) base.groundGids.first() else base.groundGids[base.widthTiles - 1] },
                BlockingGrid(width, height, BooleanArray(width * height)))
            val fixture = WaterTestScene.unit(MovementType.WATER)
            val ship = UnitDefinition("heavyBattleship", fixture.className, 40.0, 40.0, fixture.totalFrames,
                fixture.imagePath, fixture.imageScale, fixture.movement, emptySet(),
                movementCollision = MovementCollisionShape.Rectangle(102.0, 20.0), mass = 40_000.0)
            val ids = listOf("a", "b", "c")
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(120.0, 60.0)).copy(
                unitDefinitions = mapOf(ship.id to ship), units = ids.mapIndexed { index, id ->
                    UnitSpawn(id, "p1", ship.id, Position(120.0, 60.0 + 60.0 * index), 90.0)
                })
            val initialized = SimulationContext.initialize(scene)
            val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                context.enablePathDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                val target = Position(390.0, 120.0)
                val group = assertNotNull(context.requestGroupMove("p1", ids, target))
                ids.forEach { id -> assertIs<ActionResult.Accepted>(context.submitGroupMove(
                    MoveAction("bay-$id", "p1", id, target, context.world), group)) }
                var finishedAt: Long? = null
                for (tick in 0L..12000L) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    context.drainPlanningWorkDiagnostics(context.world).forEach { assertTrue(it.used in 0..512) }
                    context.drainPathDiagnostics(context.world)
                    assertTrue(frame.events.none { it.kind == "group-destination-unavailable" })
                    if (frame.events.count { it.kind == "movement-started" } == ids.size) {
                        assertTrue(frame.units.all { assertNotNull(it.moveTarget).x < 240.0 })
                        finishedAt = tick; break
                    }
                }
                println("separated-bay publication=$finishedAt")
                assertNotNull(finishedAt, "Repeated alternatives must reuse completed maneuver geometry")
            } finally { context.close() }
        }

    @Test
    fun the_same_selection_and_exact_target_keep_progress_but_publish_only_the_latest_command() =
        WaterTestScene.withMap { map ->
            val single = replay(map, reissue = false)
            val repeated = replay(map, reissue = true)
            assertEquals(single.firstOwnMotion, repeated.firstOwnMotion,
                "An unchanged same-point click must not add a fresh planning round")
            assertEquals(single.destinations, repeated.destinations)
            assertTrue(repeated.publishedActions.all { it.startsWith("latest-") })
        }

    @Test
    fun a_nearby_different_click_uses_its_new_coordinates_and_never_publishes_the_old_command() =
        WaterTestScene.withMap { map ->
            val old = Position(640.0, 190.0)
            val latest = Position(640.0, 195.0)
            val changed = replay(map, reissue = true, target = old, replacement = latest)
            val control = replay(map, reissue = false, target = latest)
            assertEquals(control.destinations, changed.destinations)
            assertTrue(latest in changed.destinations.values)
            assertTrue(old !in changed.destinations.values)
            assertTrue(changed.publishedActions.all { it.startsWith("latest-") })
        }

    private data class Result(val firstOwnMotion: Map<String, Long>, val destinations: Map<String, Position>,
                              val publishedActions: Set<String>)

    private fun replay(map: MapDefinition, reissue: Boolean, target: Position = Position(640.0, 190.0),
                       replacement: Position = target, stopped: String? = null, repeatThrough: Long = 2L,
                       reissueMembers: List<String> = listOf("a", "b", "c")): Result {
        val ids = listOf("a", "b", "c")
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(475.0, 140.0)).copy(
            units = ids.mapIndexed { index, id ->
                UnitSpawn(id, "p1", "water-test", Position(475.0, 140.0 + 45.0 * index), 90.0)
            })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            fun submit(prefix: String, click: Position, selected: List<String> = ids) {
                val group = assertNotNull(context.requestGroupMove("p1", selected, click))
                for (id in selected) {
                    val accepted = assertIs<ActionResult.Accepted>(context.submitGroupMove(
                        MoveAction("$prefix-$id", "p1", id, click, context.world), group))
                    assertEquals("$prefix-$id", accepted.actionId)
                    assertTrue(accepted.events.isEmpty())
                }
            }
            submit("first", target)
            val first = linkedMapOf<String, Long>()
            val destinations = linkedMapOf<String, Position>()
            val published = linkedSetOf<String>()
            for (tick in 0L..900L) {
                if (reissue && tick in 2L..repeatThrough) {
                    assertTrue(context.observe().units.all { it.moveTarget == null })
                    submit(if (tick == 2L) "latest" else "latest-$tick", replacement, reissueMembers)
                }
                if (stopped != null && tick == 3L) {
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", stopped, context.world)))
                }
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                for (sample in context.drainMotionDiagnostics(context.world)) {
                    val ownMotion = sample.finalPosition != sample.positionBefore || sample.finalHeading != sample.headingBefore
                    if (ownMotion && sample.steeringInvoked) {
                        assertEquals(Position(0.0, 0.0), sample.pushedDisplacement)
                        first.putIfAbsent(sample.unitId, tick)
                    }
                }
                for (sample in context.drainPathDiagnostics(context.world)) {
                    if (sample.reason == PathSearchReason.USER_MOVE) published += sample.actionId
                }
                for (work in context.drainPlanningWorkDiagnostics(context.world)) {
                    assertTrue(work.used in 0..512)
                    assertEquals(work.used, work.slices.sumOf { it.workUnits })
                }
                for (unit in frame.units) unit.moveTarget?.let { destinations[unit.unitId] = it }
                if (stopped != null) {
                    val halted = frame.units.single { it.unitId == stopped }
                    assertEquals(Position(475.0, 140.0 + 45.0 * ids.indexOf(stopped)), halted.position)
                    assertEquals(null, halted.moveTarget)
                }
                if (first.size == ids.size - (if (stopped == null) 0 else 1)) {
                    println("same-point reissue=$reissue firstOwnMotion=$first published=$published")
                    return Result(first, destinations, published)
                }
            }
            error("Not every actionable member moved: $first")
        } finally { context.close() }
    }
}
