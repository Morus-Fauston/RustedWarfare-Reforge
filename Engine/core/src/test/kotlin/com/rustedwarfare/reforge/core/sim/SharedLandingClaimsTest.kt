package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class SharedLandingClaimsTest {
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
