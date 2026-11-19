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
    fun small_landing_slices_reach_the_last_pair_last_entrance_and_deepest_live_ingress() {
        val scene = TestContentFactory.openScenario()
        val def = scene.unitDefinitions.getValue("tank")
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(600.0, 300.0)
            val cars = (0 until 30).map { i -> MutableUnit("car-$i", "p1", def.id, def, Position(50.0, 300.0), 0.0) }
            val group = GroupMoveRequest(context.world, null, "p1", click, cars.mapTo(linkedSetOf()) { it.unitId }, true)
            val pool = SharedLandingSites.forGroup(scene.map, group, cars.first(), cars)
            for ((i, car) in cars.withIndex()) {
                car.moveTarget = click
                car.landingMember = LandingMember(group, ingress = Position(300.0 + i * 10, 300.0))
            }
            val byId = cars.associateBy { it.unitId }
            val entrance = cars.first().landingMember!!.ingress!!
            var deepest: MutableUnit? = null
            for (step in 0 until 8) {
                val slice = PathWorkBudget(4)
                deepest = pool.deepestEntering(cars.first(), entrance, slice, byId::get)
                if (deepest != null) break
            }
            assertEquals(cars.last().unitId, deepest?.unitId)
            // Complete a scan over all unordered pairs with just one unit left on each step.
            var lastPair = false
            repeat(3 * cars.size * cars.size) {
                pool.beginService()
                val slice = PathWorkBudget(512).also { it.take(511) }
                pool.visitApproachingPairs(cars, slice) { a, b ->
                    if (a === cars[cars.lastIndex - 1] && b === cars.last()) lastPair = true
                }
            }
            assertTrue(lastPair)
            val front = cars.first().also { it.position = Position(500.0, 300.0); it.landingMember = LandingMember(group, it.position) }
            val follower = cars.last().also { it.landingMember = LandingMember(group, Position(550.0, 300.0)) }
            var lastEntrance = false
            repeat(3 * cars.size + 3) {
                pool.beginService()
                pool.visitEntrances(listOf(front), cars, PathWorkBudget(1)) { a, b ->
                    if (a === front && b === follower) lastEntrance = true
                }
            }
            assertTrue(lastEntrance)
        } finally { context.close() }
    }

    @Test
    fun an_occupant_entering_the_rectangle_buffer_corner_invalidates_pending_publication() {
        val opening = TestContentFactory.openScenario()
        val source = opening.unitDefinitions.getValue("tank")
        val shape = com.rustedwarfare.reforge.core.content.MovementCollisionShape.Rectangle(102.0, 20.0)
        val unit = com.rustedwarfare.reforge.core.content.UnitDefinition(source.id, source.className,
            source.radius, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale,
            source.movement, emptySet(), movementCollision = shape)
        val map = MapDefinition(64, 48, 20, 20, emptyList(), IntArray(64 * 48),
            BlockingGrid(64, 48, BooleanArray(64 * 48)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(opening.copy(map = map))).context
        try {
            val click = Position(640.0, 600.0)
            val heading = 90.0 - Math.toDegrees(kotlin.math.atan2(12.0, 53.0))
            // The first 113 lattice points reach 60 units. The holder enters
            // the corner of the buffer at that frontier, beyond hard radius + 4.
            val edge = Position(700.0, 600.0)
            val occupied = Position(808.3, 600.0)
            assertTrue(UnitContact.posesOverlap(unit.contactBufferCollision, edge, heading,
                unit.contactBufferCollision, occupied, heading))
            assertTrue(!UnitContact.posesOverlap(shape, edge, heading, shape, occupied, heading))
            val body = MutableUnit("member", "p1", unit.id, unit, Position(300.0, 600.0), heading)
            val holder = MutableUnit("holder", "p2", unit.id, unit, Position(900.0, 600.0), heading)
            val action = MoveAction("corner", "p1", body.unitId, click, context.world)
            val group = GroupMoveRequest(context.world, null, "p1", click, setOf(body.unitId))
            val terrain = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(map)
            // Birth routing bypasses path search and isolates publication validity;
            // vacancy checks still use the actual rectangular hull and buffer.
            val member = MovePlanningMember(action, body,
                MovePlanningPose(body.position, heading, ContactLayer.SURFACE, shape), body.position,
                unit.radius, terrain, null, true, 0)
            val initial = MovePlanningInput(map, listOf(member), listOf(MovePlanningOccupant(holder,
                MovePlanningPose(holder.position, heading, ContactLayer.SURFACE, shape), null, true)), false)
            val current = initial.copy(occupants = listOf(initial.occupants.single().copy(
                pose = MovePlanningPose(occupied, heading, ContactLayer.SURFACE, shape))))
            val planning = MovePlanning()
            planning.enqueue(action, group)
            var snapshots = 0
            var published = false
            val samples = mutableListOf<com.rustedwarfare.reforge.core.path.PlanningWorkSlice>()
            for (step in 0..1000) {
                planning.advance(ExperimentalPolicies().path, PathWorkBudget(128),
                    { _, _, _ -> if (snapshots++ == 0) initial else current },
                    { _, _, _, _, _ -> published = true }, samples)
                if (snapshots > 1) break
            }
            assertTrue(snapshots > 1, "The fixture must reach publication validation")
            assertTrue(samples.last().restarted, "Entering the full buffer corner must restart the stale plan")
            assertTrue(!published, "A stale occupancy snapshot must not publish")
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
                val input = MovePlanningInput(map, members, emptyList(), false)
                for (tick in 0L..12000L) {
                    fun advance(fail: Boolean): List<Position> {
                        val published = mutableListOf<Position>()
                        steps.advance(scene, Step(tick, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                            if (fail) error("observer failure")
                            Observation("plan-$index", origin.world.id, scene.scenarioId, index, time,
                                emptyList(), emptyList(), emptyList())
                        }) { candidate ->
                            val budget = PathWorkBudget(128)
                            candidate.state.movePlanning.advance(ExperimentalPolicies().path, budget, { _, _, _ -> input },
                                { _, _, _, _, plan -> published += assertNotNull(plan.slot).position })
                            assertTrue(budget.used <= 128)
                        }
                        return published
                    }
                    if (retry && tick % 7 == 1L) assertEquals("observer failure",
                        assertFailsWith<IllegalStateException> { advance(true) }.message)
                    val points = advance(false)
                    if (points.isNotEmpty()) {
                        assertEquals(14, points.size)
                        return tick to points
                    }
                }
                error("Expansion did not finish")
            }
            assertEquals(replay(false), replay(true))
        } finally { origin.close() }
    }

    @Test
    fun fourteen_reachable_members_expand_beyond_three_tiles_and_leave_buffer_space_deterministically() {
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
                    assertTrue(sqrt(ScreenPlane.distanceSquared(points[a], points[b])) >= 42.0 - 1e-8,
                        "Two radius-19 hulls each need their two-unit buffer: ${points[a]} / ${points[b]}")
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
