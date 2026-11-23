package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineMotionTest {
    @Test fun a_surface_ship_avoids_a_diving_neighbor_from_the_step_start_in_either_update_order() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val start = Position(500.0, 190.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start)
            val ship = scene.unitDefinitions.getValue("water-test")
            fun advance(subId: String, initialDepth: Double): Pair<Position, Double> {
                val input = scene.copy(
                    unitDefinitions = pack.units + (ship.id to ship),
                    units = listOf(UnitSpawn("observer", "p1", ship.id, start, 90.0),
                        UnitSpawn(subId, "p1", "attackSubmarine", Position(523.0, 196.0), 90.0, initialDepth)),
                    combatContract = CombatContract(12345, -1, mapOf("observer" to 8L, subId to 9L), 10L))
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
                try {
                    val before = context.observePlayer(context.world, "p1").units.single { it.unitId == subId }
                    assertEquals(initialDepth, before.height)
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "observer",
                        Position(650.0, start.y), context.world)))
                    val after = context.advance(Step(0, 1.0, "sim-tick"))
                    assertEquals(initialDepth == -1.0, Event("submarine-threshold-crossed", subId) in after.events)
                    assertTrue(after.units.single { it.unitId == subId }.submarine!!.underwater)
                    val observer = after.units.single { it.unitId == "observer" }
                    return observer.position to observer.heading
                } finally { context.close() }
            }
            val clear = advance("sub", -8.0)
            val observerFirst = advance("z-sub", -1.0)
            val submarineFirst = advance("a-sub", -1.0)
            assertNotEquals(clear, observerFirst, "The surface ship must react before hard contact")
            assertEquals(observerFirst, submarineFirst, "Diving during the tick must preserve the surface encounter")
        } }

    @Test fun a_native_spawn_starts_at_zero_then_uses_binary32_acceleration_to_dive_to_minus_eight() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = pack.units,
                units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", Position(500.0, 200.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L), 9L))
            val result = SimulationContext.initialize(input)
            val context = assertIs<InitResult.Accepted>(result, result.toString()).context
            try {
                assertEquals(0.0, context.observe().units.single().height)
                context.advance(Step(0L, 1.0, "sim-tick"))
                assertEquals((-.003f).toDouble(), context.observe().units.single().height)
                var height = -.003f
                var verticalSpeed = .003f
                for (q in 1L..240L) {
                    val desiredSpeed = if (kotlin.math.abs(height - -8f) > 2f) .08f else .02f
                    verticalSpeed = when {
                        verticalSpeed > desiredSpeed + .003f -> verticalSpeed - .003f
                        verticalSpeed < desiredSpeed - .003f -> verticalSpeed + .003f
                        else -> desiredSpeed
                    }
                    height = maxOf(-8f, height - verticalSpeed)
                    context.advance(Step(q, 1.0, "sim-tick"))
                    assertEquals(height.toDouble(), context.observe().units.single().height, "step $q")
                }
                assertEquals(-8.0, context.observe().units.single().height)
            } finally { context.close() }
        } }
}
