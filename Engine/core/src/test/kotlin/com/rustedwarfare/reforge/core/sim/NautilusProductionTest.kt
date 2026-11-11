package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object NautilusScene {
    fun definitions(format: String = "ini"): Map<String, UnitDefinition> {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        return assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("nautilus-production/$format/package"))).value.units
    }
    fun scenario(map: MapDefinition, format: String = "ini"): Scenario =
        WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 180.0)).copy(
            unitDefinitions = definitions(format),
            units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 180.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)))
    fun open(scenario: Scenario): SimulationContext {
        val result = SimulationContext.initialize(scenario)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class NautilusProductionTest {
    @Test fun a_paid_product_completes_while_the_mother_moves_and_uses_the_shared_birth_and_passenger_identity() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            val c = NautilusScene.open(NautilusScene.scenario(map, format))
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
                assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", "mother", Position(680.0, 180.0), c.world)))
                var step = 0L
                var parentAtCompletion = c.observe().units.single()
                while (c.observe().units.size == 1 && step < 260) {
                    parentAtCompletion = c.observe().units.single()
                    c.advance(Step(step++, 1.0, "sim-tick"))
                }
                assertEquals(250L, step) // .002 base rate x current factory speed 2, float32 accumulation.
                val observation = c.observe()
                val mother = observation.units.single { it.unitId == "mother" }
                val child = observation.units.single { it.unitId != "mother" }
                assertTrue(mother.position.x > 590.0)
                assertEquals("produced:p1:${"a".repeat(32)}", child.unitId)
                assertEquals("mother", child.carriedBy)
                assertEquals(150.0, child.health)
                assertEquals(parentAtCompletion.height, child.height)
                assertEquals(listOf(child.unitId), mother.transport!!.passengerIds)
                assertEquals(3, mother.transport.capacity)
                assertFalse(mother.transport.canUnload)
                assertEquals(EnergyObservation(6f, 6f, false), mother.energy)
                assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
                val birth = observation.production!!.units.single()
                assertEquals(9L, birth.objectId)
                assertEquals("mother", birth.producerId)
                assertTrue(birth.updateKey.startsWith("~produced/"))
            } finally { c.close() }
        }
    }
}
