package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class LandingCraftCommandTest {
    @Test fun a_passenger_command_on_a_friendly_carrier_boards_and_clears_the_existing_selection() {
        val base = LandingCraftSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(380.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(395.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "craft" to 9L), 10L))
        val records = mutableListOf<Pair<String, Map<String, Any?>>>()
        SandboxSession(input, ClientEvidence { kind, fields -> records += kind to fields }).use { session ->
            session.select(Position(380.0, 150.0))
            assertEquals("worker", session.selectedId)
            assertIs<ActionResult.Accepted>(session.command(Position(395.0, 150.0)))
            assertNull(session.selectedId)
            assertTrue(session.observation.units.none { it.unitId == "worker" })
            session.select(Position(395.0, 150.0))
            assertTrue(assertNotNull(session.selectedUnitInfo).contains("Cargo 1 / 4"))
            assertTrue(records.any { it.first == "action" && it.second["type"] == "LoadAction" &&
                it.second["targetUnitId"] == "craft" && it.second["direction"] == "INTO" })
        }
    }

    @Test fun a_carrier_command_on_a_friendly_passenger_uses_load_up() {
        val base = LandingCraftSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(360.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(390.0, 150.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "craft" to 9L), 10L))
        SandboxSession(input).use { session ->
            session.select(Position(390.0, 150.0))
            assertIs<ActionResult.Accepted>(session.command(Position(360.0, 150.0)))
            repeat(200) { session.tick() }
            assertTrue(session.observation.units.none { it.unitId == "worker" })
            assertEquals(listOf("worker"), session.observation.units.single { it.unitId == "craft" }.transport!!.passengerIds)
        }
    }
}
