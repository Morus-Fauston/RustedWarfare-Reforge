package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class LocalMotionMaintenanceTest {
    @Test
    fun local_progress_time_advances_only_under_positive_propulsion_and_resets_on_handoff_or_replacement() {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        // Block cell (4, 2) -> [80,100) x [40,60) so route from (50, 50) to (130, 50) detours via intermediate waypoints
        val blocked = BooleanArray(12 * 8) { idx -> idx == (2 * 12 + 4) }
        val map = MapDefinition(12, 8, 20, 20, emptyList(), IntArray(12 * 8), BlockingGrid(12, 8, blocked))
        val unitDef = TestContentFactory.unitWith(
            tank,
            radius = 6.0,
            movement = tank.movement.copy(
                moveSpeed = 2.0,
                moveAccelerationSpeed = 0.5,
                moveDecelerationSpeed = 0.5,
                maxTurnSpeed = 10.0,
                turnAcceleration = 0.0,
            ),
        )
        // Start heading 180.0 (south) while first waypoint is north-east -> initial turn has closed propulsion (fraction == 0)
        val context = assertIs<InitResult.Accepted>(
            SimulationContext.initialize(
                source.copy(
                    map = map,
                    unitDefinitions = mapOf("tank" to unitDef),
                    units = listOf(UnitSpawn("u1", "p1", "tank", Position(50.0, 50.0), 180.0)),
                ),
            ),
        ).context
        try {
            context.enableMotionDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("detour", "p1", "u1", Position(130.0, 50.0), context.world)))

            val step0 = context.advance(Step(0L, 1.0, "sim-tick"))
            assertTrue(step0.units.single().pathPoints.size >= 2)
            val trace0 = context.drainMotionDiagnostics(context.world).single()
            assertEquals(0.0, trace0.targetFraction, "Large initial heading error must close propulsion")
            assertEquals(0.0, trace0.localProgressAfter, "Closed propulsion must pause localProgressTime at 0.0")

            var sawPositiveProgress = false
            var sawWaypointHandoffReset = false
            var prevPointsCount = step0.units.single().pathPoints.size
            for (step in 1L..160L) {
                val obs = context.advance(Step(step, 1.0, "sim-tick"))
                val trace = context.drainMotionDiagnostics(context.world).single()
                val curCount = obs.units.single().pathPoints.size
                if (trace.targetFraction > 0.0 && curCount == prevCountOr(prevPointsCount)) {
                    if (trace.localProgressAfter > trace.localProgressBefore) {
                        sawPositiveProgress = true
                    }
                }
                if (curCount in 1 until prevPointsCount) {
                    assertEquals(0.0, trace.localProgressAfter, "Waypoint handoff must reset localProgressTime to 0.0")
                    sawWaypointHandoffReset = true
                }
                prevPointsCount = curCount
                if (obs.events.any { it.kind == "movement-arrived" }) break
            }
            assertTrue(sawPositiveProgress, "Active propulsion must increment localProgressTime")
            assertTrue(sawWaypointHandoffReset, "Multi-waypoint route must reset localProgressTime upon handoff")
        } finally {
            context.close()
        }
    }

    private fun prevCountOr(v: Int): Int = v
}
