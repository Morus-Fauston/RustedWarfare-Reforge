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
            var nearTime = 0
            var stable = 0
            var previous = context.observe().units.single()
            for (step in 0L until 2500L) {
                val localBefore = context.localMotionSnapshots(context.world)
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val value = observation.units.single()
                val searches = context.drainPathDiagnostics(context.world)
                println("A1,$step,$value,${observation.events},$searches,pre=$localBefore,post=${context.localMotionSnapshots(context.world)}")
                assertTrue(value.position.x in 11.0..589.0 && value.position.y in 11.0..389.0, "step=$step circle outside")
                if (observation.events.any { it.kind == "movement-arrived" }) {
                    val distance = hypot(previous.position.x - goal.x, previous.position.y - goal.y)
                    if (distance < 41.0) nearTime++
                    val capture = if (nearTime > 340) 36.0 else if (nearTime > 240) 16.0 else 7.0
                    assertTrue(distance < capture, "Normal clear outside active strict radius")
                    assertEquals(listOf(goal), previous.pathPoints)
                    assertNull(value.moveTarget)
                    arrived = true
                } else if (!arrived && hypot(previous.position.x - goal.x, previous.position.y - goal.y) < 41.0) {
                    nearTime++
                }
                if (arrived) {
                    assertNull(value.moveTarget)
                    assertTrue(value.pathPoints.isEmpty())
                    assertEquals(previous.heading, value.heading)
                    assertEquals(maxOf(0.0, previous.speed - 0.022), value.speed, 1e-9)
                    stable = if (value.speed == 0.0 && value.position == previous.position) stable + 1 else 0
                    if (stable >= 18) {
                        println("A1 normal clear and stable tail; final target distance=${hypot(value.position.x - goal.x, value.position.y - goal.y)}")
                        break
                    }
                }
                previous = value
            }
            assertTrue(arrived, "A1 original 2500-step budget exhausted: ${context.observe().units.single()}")
            assertTrue(stable >= 18, "A1 missing stopped tail within original budget")
        } finally { context.close() }
    }
}
