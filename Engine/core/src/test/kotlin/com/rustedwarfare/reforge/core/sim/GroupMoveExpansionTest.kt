package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupMoveExpansionTest {
    @Test
    fun sliced_landing_work_visits_every_driver_in_order() {
        val scene = TestContentFactory.openScenario()
        val def = scene.unitDefinitions.getValue("tank")
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val cars = (0 until 30).map { MutableUnit("car-$it", "p1", def.id, def, Position(50.0, 300.0), 0.0) }
            val group = GroupMoveRequest(ctx.world, null, "p1", Position(600.0, 300.0), cars.mapTo(linkedSetOf()) { it.unitId })
            val pool = SharedLandingSites.forGroup(scene.map, group, cars)
            val visited = mutableListOf<String>()
            repeat(cars.size) { pool.visitDriving(cars, PathWorkBudget(1)) { car -> visited += car.unitId } }
            assertEquals(cars.map { it.unitId }, visited)
        } finally { ctx.close() }
    }

    @Test
    fun a_long_hull_reserves_its_actual_arrival_orientation() {
        val scene = TestContentFactory.openScenario()
        val source = scene.unitDefinitions.getValue("tank")
        val shape = com.rustedwarfare.reforge.core.content.MovementCollisionShape.Rectangle(80.0, 16.0)
        val hull = com.rustedwarfare.reforge.core.content.UnitDefinition(source.id, source.className,
            source.radius, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale,
            source.movement, emptySet(), movementCollision = shape)
        val small = TestContentFactory.unitWith(source, radius = 6.0)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val site = Position(420.0, 300.0)
            val long = MutableUnit("long", "p1", hull.id, hull, Position(200.0, 100.0), 0.0)
            val car = MutableUnit("car", "p1", small.id, small, Position(250.0, 150.0), 0.0)
            val group = GroupMoveRequest(context.world, null, "p1", site, setOf(long.unitId, car.unitId))
            val index = LandingOccupancy()
            long.moveTarget = site
            val units = listOf(long, car).associateBy { it.unitId }
            for (heading in listOf(0.0, 90.0)) {
                long.landingMember = LandingMember(group, claimed = site, claimHeading = heading)
                index.update(long)
                assertEquals(heading == 0.0, index.leavesReservedSitesClear(car, units, Position(452.0, 300.0)))
                assertEquals(heading == 90.0, index.leavesReservedSitesClear(car, units, Position(420.0, 332.0)))
            }
        } finally { context.close() }
    }

    @Test
    fun abandoned_steps_restore_expanding_candidates_searches_and_assignment() {
        val opening = TestContentFactory.openScenario()
        val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 19.0)
        val map = MapDefinition(64, 48, 20, 20, emptyList(), IntArray(64 * 48),
            BlockingGrid(64, 48, BooleanArray(64 * 48)))
        val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = (0 until 14).map {
            UnitSpawn("u$it", "p1", "tank", Position(80.0 + it * 80.0, 100.0), 180.0)
        })
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val target = Position(640.0, 600.0)
            val group = assertNotNull(origin.requestGroupMove("p1", scene.units.map { it.unitId }, target))
            val terrain = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(map)
            val members = scene.units.map { spawn ->
                val action = MoveAction("go-${spawn.unitId}", "p1", spawn.unitId, target, origin.world)
                val body = MutableUnit(spawn.unitId, "p1", "tank", tank, spawn.position, spawn.heading)
                MovePlanningMember(action, body, MovePlanningPose(spawn.position, 0.0, ContactLayer.SURFACE,
                    tank.movementCollision), spawn.position, 19.0, terrain, HullRoute.Circle(19.0), false, 0)
            }
            fun replay(retry: Boolean): Pair<Long, List<Position>> {
                val steps = CandidateSteps()
                members.forEach { steps.current.movePlanning.enqueue(it.action, group) }
                val destinations = linkedMapOf<String, Position>()
                for (tick in 0L..12000L) {
                    fun advance(fail: Boolean): Map<String, Position> {
                        val published = linkedMapOf<String, Position>()
                        steps.advance(scene, Step(tick, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                            if (fail) error("observer failure")
                            Observation("plan-$index", origin.world.id, scene.scenarioId, index, time,
                                emptyList(), emptyList(), emptyList())
                        }) { candidate ->
                            val budget = PathWorkBudget(128)
                            candidate.state.movePlanning.advance(ExperimentalPolicies().path, budget, { actions, _, _ ->
                                MovePlanningInput(map, members.filter { it.action in actions }, emptyList(), false)
                            }, { member, _, _, _, plan -> published[member.action.unitId] = assertNotNull(plan.slot).position })
                            assertTrue(budget.used <= 128)
                        }
                        return published
                    }
                    if (retry && tick % 7 == 1L) assertEquals("observer failure",
                        assertFailsWith<IllegalStateException> { advance(true) }.message)
                    destinations.putAll(advance(false))
                    if (destinations.size == members.size) {
                        return tick to members.map { destinations.getValue(it.action.unitId) }
                    }
                }
                error("Expansion did not finish")
            }
            assertEquals(replay(false), replay(true))
        } finally { origin.close() }
    }

    @Test
    fun fourteen_reachable_members_expand_beyond_three_tiles_and_park_without_hard_overlap_deterministically() {
        data class Receipt(val step: Long, val trace: List<Int>, val positions: List<Position>)
        fun replay(inject: Boolean): Receipt {
            val opening = TestContentFactory.openScenario()
            val tank = TestContentFactory.unitWith(opening.unitDefinitions.getValue("tank"), radius = 19.0)
            val map = MapDefinition(64, 48, 20, 20, emptyList(), IntArray(64 * 48),
                BlockingGrid(64, 48, BooleanArray(64 * 48)))
            val scene = opening.copy(map = map, unitDefinitions = mapOf("tank" to tank), units = (0 until 14).map {
                UnitSpawn("u$it", "p1", "tank", Position(80.0 + it * 80.0, 100.0), 180.0)
            })
            var injected = false
            val steering = SteeringPolicy { body, aim, delta, coordinates ->
                if (inject && !injected && body.landingMember?.claimed != null) {
                    injected = true
                    error("injected after shared claim")
                }
                AcceleratedSteering.turn(body, aim, delta, coordinates)
            }
            val initialized = SimulationContext(ExperimentalPolicies(steering = steering)).initialize(scene)
            val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
            try {
                context.enablePlanningWorkDiagnostics(context.world)
                val click = Position(640.0, 600.0)
                val ids = scene.units.map { it.unitId }
                val group = assertNotNull(context.requestGroupMove("p1", ids, click))
                ids.forEach { id -> assertIs<ActionResult.Accepted>(context.submitGroupMove(
                    MoveAction("select-$id", "p1", id, click, context.world), group)) }
                assertTrue(context.observe().units.all { it.moveTarget == null })
                var frame: Observation? = null
                val arrived = mutableSetOf<String>()
                val trace = mutableListOf<Int>()
                context.enableMotionDiagnostics(context.world)
                for (step in 0L..6000L) {
                    val tick = Step(step, 1.0, "sim-tick")
                    val next = try { context.advance(tick) } catch (failure: IllegalStateException) {
                        if (failure.message != "injected after shared claim") throw failure
                        assertTrue(context.drainMotionDiagnostics(context.world).isEmpty())
                        context.advance(tick)
                    }
                    val motion = context.drainMotionDiagnostics(context.world)
                    trace += listOf(next.events, motion.map { listOf(it.unitId, it.finalPosition, it.finalHeading,
                        it.steeringAim, it.commandTargetBefore, it.landingClaim, it.landingIngress, it.landingParked) }).hashCode()
                    assertTrue(next.events.none { it.kind == "group-destination-unavailable" }, "Reachable member lost its slot at $step")
                    next.events.filter { it.kind == "movement-arrived" }.mapTo(arrived) { requireNotNull(it.unitId) }
                    if (arrived.size == ids.size && next.units.all { it.moveTarget == null }) { frame = next; break }
                }
                val planned = assertNotNull(frame, "All fourteen members must occupy destinations: $arrived; poses=${context.observe().units.map { listOf(it.unitId, it.position, it.moveTarget, it.pathPoints.firstOrNull()) }}")
                val points = planned.units.map { it.position }
                assertTrue(points.any { ScreenPlane.distanceSquared(it, click) > 60.0 * 60.0 })
                for (a in points.indices) for (b in 0 until a) {
                    assertTrue(sqrt(ScreenPlane.distanceSquared(points[a], points[b])) >= tank.movementCollision.boundingRadius * 2 - 1e-8,
                        "Parked bodies must remain disjoint: ${points[a]} / ${points[b]}")
                }
                val work = context.drainPlanningWorkDiagnostics(context.world)
                assertTrue(work.size > 1)
                assertTrue(work.all { it.used <= it.limit })
                println("Expanded fourteen: settled=${planned.stepIndex} targets=$points maxWork=${work.maxOf { it.used }}")
                assertEquals(inject, injected)
                trace += work.hashCode()
                return Receipt(planned.stepIndex, trace, points)
            } finally { context.close() }
        }
        assertEquals(replay(false), replay(true))
    }
}
