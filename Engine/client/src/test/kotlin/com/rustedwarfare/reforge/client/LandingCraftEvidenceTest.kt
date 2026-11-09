package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class LandingCraftEvidenceTest {
    @Test fun the_player_evidence_keeps_manifest_capacity_timer_and_exit_reference_without_reintroducing_hidden_cargo() {
        val base = LandingCraftSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("a", "p1", "builder", Position(360.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(375.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "craft" to 9L), 10L))
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(input, ClientEvidence { kind, fields -> records += kind to fields }).use { session ->
            session.select(Position(360.0, 150.0))
            session.command(Position(375.0, 150.0))
            session.select(Position(375.0, 150.0))
            assertIs<ActionResult.Accepted>(session.unload(true))
            val sample = records.last { it.first == "observation" }.second["sample"] as Map<*, *>
            val units = sample["units"] as List<*>
            assertEquals(1, units.size)
            val carrier = units.single() as Map<*, *>
            val state = assertNotNull(carrier["transport"] as? Map<*, *>)
            assertEquals(listOf("a"), state["passengerIds"])
            assertEquals(1, state["occupiedSlots"])
            assertEquals(4, state["capacity"])
            assertEquals(true, state["unloading"])
            assertEquals(30f, state["unloadRemaining"])
            repeat(31) { session.tick() }
            val released = session.observation.units.single { it.unitId == "a" }.fields()
            val exit = assertNotNull(released["transportExit"] as? Map<*, *>)
            assertEquals("craft", exit["previousCarrierId"])
            assertTrue((exit["remaining"] as Float) > 0f)
        }
    }
}
