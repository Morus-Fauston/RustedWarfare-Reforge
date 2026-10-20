package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class AutomaticArrivalBudgetTest {
    @Test fun original_a1_fixture_reaches_the_goal_within_2500_steps_inside_the_map() {
        // Same physical input and 2500-step budget as AutomaticVisionPerf.hiddenDiscoveryFixture.
        val unit = UnitDefinition("tank", "tank", 11.0, 11.0, 1, Path.of("unused-tank.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.05, 0.02, 3.0, 0.01, false, false), emptySet(), 3)
        val source = TestContentFactory.openScenario().copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600) { 1 },
                BlockingGrid(30, 20, BooleanArray(600) { it % 30 == 14 && it / 30 in 1..15 })),
            unitDefinitions = mapOf("tank" to unit),
            units = listOf(UnitSpawn("u0", "p1", "tank", Position(50.0, 70.0), 90.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source)).context
        try {
            context.enablePathDiagnostics(context.world)
            val goal = Position(430.0, 70.0)
            context.submit(MoveAction("scheduled", "p1", "u0", goal, context.world, atStep = 0))
            var arrived = false
            for (step in 0L until 2500L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val value = observation.units.single()
                val searches = context.drainPathDiagnostics(context.world)
                println("A1,$step,$value,${observation.events},$searches")
                assertTrue(value.position.x in 11.0..589.0 && value.position.y in 11.0..389.0, "step=$step circle outside")
                if (observation.events.any { it.kind == "movement-arrived" }) {
                    assertTrue(hypot(value.position.x - goal.x, value.position.y - goal.y) <= 4.0)
                    arrived = true
                    break
                }
            }
            assertTrue(arrived, "A1 original 2500-step budget exhausted: ${context.observe().units.single()}")
        } finally { context.close() }
    }
}
