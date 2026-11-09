package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class LandingCraftUnloadMenuTest {
    @Test fun a_loaded_carrier_can_start_cancel_and_restart_unloading_without_restoring_the_hidden_passenger_selection() {
        val base = LandingCraftSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(360.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(375.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "craft" to 9L), 10L))
        SandboxSession(input).use { session ->
            session.select(Position(360.0, 150.0))
            assertIs<ActionResult.Accepted>(session.command(Position(375.0, 150.0)))
            session.select(Position(375.0, 150.0))
            assertIs<ActionResult.Accepted>(session.unload(true))
            repeat(10) { session.tick() }
            assertIs<ActionResult.Accepted>(session.unload(false))
            repeat(30) { session.tick() }
            assertTrue(session.observation.units.none { it.unitId == "worker" })
            assertIs<ActionResult.Accepted>(session.unload(true))
            repeat(31) { session.tick() }
            assertTrue(session.observation.units.any { it.unitId == "worker" })
            assertEquals("craft", session.selectedId)
            assertNull(session.unload(true))
        }
    }
}
