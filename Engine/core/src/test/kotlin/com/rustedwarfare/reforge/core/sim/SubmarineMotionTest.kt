package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineMotionTest {
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
