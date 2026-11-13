package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineFailureTest {
    @Test fun unsupported_actions_land_destinations_and_scheduled_duplicates_use_the_existing_command_lifecycle() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0,200.0)).copy(
                unitDefinitions = mapOf("attackSubmarine" to sub, "ordinary" to WaterTestScene.unit(MovementType.HOVER)),
                units = listOf(UnitSpawn("sub","p1","attackSubmarine",Position(500.0,200.0),90.0,-8.0),
                    UnitSpawn("ordinary","p1","ordinary",Position(650.0,200.0),90.0)),
                combatContract = CombatContract(12345,-1,mapOf("sub" to 8L,"ordinary" to 9L),10L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val initial = context.observe().units
                assertEquals("unsupported-submarine-action", assertIs<ActionResult.Rejected>(context.submit(
                    SubmarineDepthAction("unsupported","p1","ordinary",true,context.world))).error.code)
                assertEquals(initial,context.observe().units)
                val land = assertIs<ActionResult.Accepted>(context.submit(MoveAction("dry","p1","sub",Position(100.0,200.0),context.world)))
                assertTrue(land.events.any { it.kind=="path-unreachable" })
                assertNull(context.observe().units.single { it.unitId=="sub" }.moveTarget)
                for (id in listOf("first","duplicate")) assertEquals(2L, assertIs<ActionResult.Accepted>(context.submit(
                    SubmarineDepthAction(id,"p1","sub",true,context.world,2L))).queuedForStep)
                assertEquals(false,context.observe().units.single { it.unitId=="sub" }.submarine!!.surfaceIntent)
                for (q in 0L..2L) context.advance(Step(q,1.0,"sim-tick"))
                val row = context.observe()
                assertEquals(true,row.units.single { it.unitId=="sub" }.submarine!!.surfaceIntent)
                assertIs<ActionResult.Accepted>(row.actionResults.single { it.actionId=="first" })
                assertEquals("submarine-state-unchanged",assertIs<ActionResult.Rejected>(row.actionResults.single { it.actionId=="duplicate" }).error.code)
                assertTrue(row.units.single { it.unitId=="sub" }.position.x>=400.0)
            } finally { context.close() }
            val onLand = scene.copy(units = listOf(scene.units.first().copy(position=Position(100.0,200.0))),
                combatContract=CombatContract(12345,-1,mapOf("sub" to 8L),9L))
            assertIs<InitResult.Rejected>(SimulationContext.initialize(onLand))
        } }
}
