package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.HoverProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class SharedLandingClaimsTest {
    @Test fun overlapping_legacy_orders_keep_separate_parking_rights() = checkReissuedParking(false)
    @Test fun a_shared_order_cannot_grant_parking_rights_to_its_legacy_predecessor() = checkReissuedParking(true)

    private fun checkReissuedParking(shared: Boolean) {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val map = MapDefinition(48, 32, 20, 20, base.map.tileSets, IntArray(1536) { base.map.tileSets.first().firstGid }, BlockingGrid(48, 32, BooleanArray(1536)))
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to tank, "other" to TestContentFactory.unitWith(tank, movement = tank.movement.copy(type = MovementType.HOVER, hover = HoverProfile(),
                slidingMode = true, ignoringBody = true, reverseSpeedPercentage = 0.0))),
            units = listOf(UnitSpawn("a", "p1", "other", Position(80.0, 120.0), 90.0),
                UnitSpawn("b", "p1", "tank", Position(320.0, 240.0), 90.0),
                UnitSpawn("c", "p1", if (shared) "tank" else "other", Position(320.0, 360.0), 90.0)))
        var failStopStep = false
        val policies = ExperimentalPolicies(routePreparationBudget = {
            if (failStopStep) error("discard scheduled stop")
            8
        })
        val init = SimulationContext(policies).initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(init, init.toString()).context
        try {
            val click = Position(440.0, 240.0)
            fun send(prefix: String, vararg ids: String): GroupMoveRequest {
                val g = assertNotNull(ctx.requestGroupMove("p1", ids.toList(), click))
                for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("$prefix-$id", "p1", id, click, ctx.world), g))
                return g
            }
            val old = send("old", "a", "b")
            assertFalse(old.sharedLanding)
            ctx.advance(Step(0, 1.0, "sim-tick"))
            val current = send("new", "b", "c")
            assertEquals(shared, current.sharedLanding)
            var step = 1L
            while (step < 3000 && ctx.observe().units.single { it.unitId == "b" }.moveArrival == null)
                ctx.advance(Step(step++, 1.0, "sim-tick"))
            assertNotNull(ctx.observe().units.single { it.unitId == "b" }.moveArrival)
            // Inspect the published simulation body after the two normal player commands.
            val bodies = SimulationContext::class.java.getDeclaredMethod("getUnits").apply { isAccessible = true }
            fun parkedBody() = (bodies.invoke(ctx) as Map<*, *>)["b"] as MutableUnit
            val parked = parkedBody()
            assertTrue(current.hasParkedMember(parked))
            assertFalse(old.hasParkedMember(parked), "Previous order must not own the reissued member at the same click")
            // Probe the same physical pose with each actual order's parking clearance.
            val clears = SimulationContext::class.java.getDeclaredMethod("groupStopClearsSlots", MutableUnit::class.java)
                .apply { isAccessible = true }
            val probe = MutableUnit("probe", "p1", "tank", tank, parked.position, parked.heading)
            val slot = GroupMoveSlot(probe.position, probe.heading, listOf(probe.position))
            fun canStop(order: GroupMoveRequest): Boolean {
                probe.groupMoveReservation = GroupMoveReservation(order, slot)
                return clears.invoke(ctx, probe) as Boolean
            }
            // Apply the contact solver to the published parking identity at a controlled angle.
            val body = com.rustedwarfare.reforge.core.content.UnitDefinition(tank.id, tank.className,
                10.0, tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale,
                tank.movement, tank.retainedUnconsumed, mass = 100.0)
            fun resolved(order: GroupMoveRequest?): Position {
                val rest = parked.copy(definition = body, position = Position(119.0, 207.0))
                val mover = MutableUnit("probe", "p1", "tank", body, Position(100.0, 200.0), 90.0)
                mover.moveTarget = click
                mover.groupMoveReservation = order?.let { GroupMoveReservation(it, slot) }
                val units = listOf(mover, rest)
                return UnitContact.Translations(1.0) { false }.resolve(mover, Position(102.0, 200.0), units,
                    map, units.associateBy { it.unitId }, { _, _ -> true }, map) { true }.position
            }
            assertEquals(resolved(null), resolved(old))
            assertNotEquals(resolved(null), resolved(current))
            assertFalse(canStop(current), "The current order protects its parked body")
            assertTrue(canStop(old), "The superseded order has no parking clearance claim")
            assertIs<ActionResult.Accepted>(ctx.submit(StopAction("stop", "p1", "b", ctx.world, atStep = step)))
            failStopStep = true
            assertEquals("discard scheduled stop", assertFailsWith<IllegalStateException> {
                ctx.advance(Step(step, 1.0, "sim-tick"))
            }.message)
            assertTrue(current.hasParkedMember(parkedBody()), "A failed step retains the previous parking owner")
            failStopStep = false
            ctx.advance(Step(step, 1.0, "sim-tick"))
            assertFalse(current.hasParkedMember(parkedBody()))
        } finally { ctx.close() }
    }

    @Test fun a_parked_vehicle_from_another_order_at_the_same_click_receives_ordinary_contact() {
        val scene = TestContentFactory.openScenario()
        val map = MapDefinition(40, 32, 20, 20, emptyList(), IntArray(1280), BlockingGrid(40, 32, BooleanArray(1280)))
        val source = scene.unitDefinitions.getValue("tank")
        val tank = com.rustedwarfare.reforge.core.content.UnitDefinition(source.id, source.className,
            10.0, source.displayRadius, source.totalFrames, source.imagePath, source.imageScale,
            source.movement, source.retainedUnconsumed, mass = 100.0)
        val click = Position(400.0, 200.0)
        val world = WorldHandle("contact")
        fun order() = GroupMoveRequest(world, null, "p1", click, setOf("moving", "parked"), true)
        val own = order()
        fun translate(group: GroupMoveRequest?): Position {
            val moving = MutableUnit("moving", "p1", "tank", tank, Position(100.0, 200.0), 90.0)
            val resting = MutableUnit("parked", "p1", "tank", tank, Position(119.0, 207.0), 90.0)
            moving.moveTarget = click
            moving.landingMember = LandingMember(own, claimed = click)
            moving.groupMoveReservation = GroupMoveReservation(own, GroupMoveSlot(click, 90.0, listOf(click)))
            if (group != null) {
                resting.landingMember = LandingMember(group, claimed = resting.position, parked = true)
                resting.moveArrival = MoveArrival(click, resting.position, 0.0, 1, true)
            }
            val units = listOf(moving, resting)
            return UnitContact.Translations(1.0) { false }.resolve(moving, Position(102.0, 200.0), units, map,
                units.associateBy { it.unitId }, { _, _ -> true }, map) { true }.position
        }
        val idle = translate(null)
        assertEquals(idle, translate(order()))
        assertNotEquals(idle, translate(own))
    }
    @Test
    fun reissuing_the_same_click_restores_the_new_orders_claims_after_a_failed_step() {
        fun replay(inject: Boolean): List<List<Any?>> {
            val initial = TestContentFactory.openScenario()
            val map = MapDefinition(40, 32, 20, 20, emptyList(), IntArray(1280), BlockingGrid(40, 32, BooleanArray(1280)))
            val scene = initial.copy(map = map, units = (0..2).map { i ->
                UnitSpawn("u$i", "p1", "tank", Position(100.0, 160.0 + 55.0 * i), 90.0)
            })
            var armed = false
            var tripped = false
            val steering = SteeringPolicy { body, aim, delta, coordinates ->
                if (armed && !tripped && body.landingMember?.claimed != null) {
                    tripped = true
                    error("retry reissued landing")
                }
                AcceleratedSteering.turn(body, aim, delta, coordinates)
            }
            val context = assertIs<InitResult.Accepted>(SimulationContext(ExperimentalPolicies(steering = steering)).initialize(scene)).context
            try {
                val ids = scene.units.map { it.unitId }
                val click = Position(400.0, 260.0)
                fun send(prefix: String) {
                    val group = assertNotNull(context.requestGroupMove("p1", ids, click))
                    for (id in ids) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                        MoveAction("$prefix-$id", "p1", id, click, context.world), group))
                }
                val rows = mutableListOf<List<Any?>>()
                send("old")
                context.enableMotionDiagnostics(context.world)
                context.enablePlanningWorkDiagnostics(context.world)
                var replaced = false
                val reissued = mutableSetOf<String>()
                repeat(2000) { index ->
                    val step = Step(index.toLong(), 1.0, "sim-tick")
                    val frame = try { context.advance(step) } catch (e: IllegalStateException) {
                        if (e.message != "retry reissued landing") throw e
                        assertTrue(context.drainMotionDiagnostics(context.world).isEmpty())
                        context.advance(step)
                    }
                    val motion = context.drainMotionDiagnostics(context.world)
                    val work = context.drainPlanningWorkDiagnostics(context.world).single()
                    if (replaced) for (slice in work.slices) {
                        val first = slice.firstSegment ?: continue
                        assertEquals("new-${first.unitId}", first.actionId)
                        if (first.outcome == "adopt") reissued += first.unitId
                    }
                    rows += listOf(index, work.used, work.slices.map { it.firstSegment }, frame.events,
                        frame.units.map { listOf(it.unitId, it.tasks, it.moveTarget, it.pathPoints.toList()) }, motion.map { listOf(it.unitId, it.originMoveId,
                        it.finalPosition, it.finalHeading, it.landingClaim, it.landingIngress, it.landingParked) })
                    if (!replaced && motion.any { it.landingClaim != null }) {
                        assertTrue(frame.units.any { it.pathPoints.isNotEmpty() && it.moveTarget != null })
                        send("new")
                        replaced = true
                        armed = inject
                    } else if (replaced && motion.count { it.landingParked } == ids.size) {
                        assertEquals(inject, tripped)
                        assertEquals(ids.toSet(), reissued)
                        return rows
                    } else if (replaced) assertTrue(motion.none { it.originMoveId?.startsWith("old-") == true })
                }
                error("reissued group failed to settle")
            } finally { context.close() }
        }
        assertEquals(replay(false), replay(true))
    }

    @Test
    fun separate_orders_competing_for_one_pad_do_not_double_book_and_all_arrive() {
        val opening = TestContentFactory.openScenario()
        val map = MapDefinition(40, 32, 20, 20, emptyList(), IntArray(1280), BlockingGrid(40, 32, BooleanArray(1280)))
        val scene = opening.copy(map = map, units = (0..3).map { index ->
            UnitSpawn("tank-$index", "p1", "tank", Position(100.0, 200.0 + 40.0 * index), 90.0)
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(400.0, 280.0)
            context.enableMotionDiagnostics(context.world)
            for (pair in scene.units.chunked(2)) {
                val group = assertNotNull(context.requestGroupMove("p1", pair.map { it.unitId }, click))
                for (body in pair) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                    MoveAction("order-${body.unitId}", "p1", body.unitId, click, context.world), group))
            }
            repeat(1500) { tick ->
                context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                val samples = context.drainMotionDiagnostics(context.world)
                val claims = samples.mapNotNull { it.landingClaim }
                assertEquals(claims.size, claims.distinct().size, "duplicate claims at step $tick")
                if (samples.count { it.landingParked } == 4) return
            }
            fail("both orders must reach four distinct physical sites ${context.observe().units.map { listOf(it.unitId, it.position, it.moveTarget, it.pathPoints, it.moveArrival) }}")
        } finally { context.close() }
    }
}
