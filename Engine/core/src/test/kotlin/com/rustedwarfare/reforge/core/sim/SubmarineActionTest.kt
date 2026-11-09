package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineActionTest {
    @Test fun surface_and_dive_change_only_intent_preserve_move_and_use_the_physical_transition() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = pack.units,
                units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", Position(500.0, 200.0), 90.0, -8.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L), 9L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val world = context.world
                val denied = assertIs<ActionResult.Rejected>(context.submit(SubmarineDepthAction("intrude", "p2", "sub", true, world)))
                assertEquals("not-owner", denied.error.code)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "sub", Position(650.0, 250.0), world)))
                val before = context.observe().units.single()
                val action = context.submit(SubmarineDepthAction("surface", "p1", "sub", true, world))
                assertIs<ActionResult.Accepted>(action, action.toString())
                val after = context.observe().units.single()
                assertEquals(before.height, after.height)
                assertEquals(before.tasks, after.tasks)
                assertEquals(before.pathPoints, after.pathPoints)
                assertEquals(before.moveTarget, after.moveTarget)
                assertEquals("submarine-state-unchanged", assertIs<ActionResult.Rejected>(context.submit(
                    SubmarineDepthAction("repeat", "p1", "sub", true, world))).error.code)
                context.advance(Step(0L, 1.0, "sim-tick"))
                assertEquals((-8f + .003f).toDouble(), context.observe().units.single().height)
                for (q in 1L..250L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(1.0, context.observe().units.single().height)
                assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("dive", "p1", "sub", false, world)))
                assertEquals(1.0, context.observe().units.single().height)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "sub", world)))
                for (q in 251L..501L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(-8.0, context.observe().units.single().height)
                assertTrue(context.observe().units.single().tasks.isEmpty())
            } finally { context.close() }
        } }
}
