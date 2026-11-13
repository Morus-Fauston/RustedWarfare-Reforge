package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class DropshipUnloadMenuTest {
    @Test fun the_native_panel_names_the_transport_and_exposes_cargo_height_and_cancellable_unload() {
        val base = DropshipSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("worker", "p1", "builder", Position(350.0, 150.0), 90.0),
            UnitSpawn("ship", "p1", "dropship", Position(370.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "ship" to 9L), 10L))
        SandboxSession(input).use { session ->
            session.select(Position(350.0, 150.0))
            assertIs<ActionResult.Accepted>(session.command(Position(370.0, 150.0)))
            session.select(Position(370.0, 150.0))
            assertTrue(NativeActionPanel.supports(base.scenarioId))
            assertEquals("Transport", session.actionMenu.selectedTitle)
            val info = assertNotNull(session.selectedUnitInfo)
            assertTrue(info.contains("Passengers 1"))
            assertTrue(info.contains("Height 0.00"), info)
            assertIs<ActionResult.Accepted>(session.unload(true))
            repeat(10) { session.tick() }
            assertIs<ActionResult.Accepted>(session.unload(false))
            repeat(30) { session.tick() }
            assertTrue(session.observation.units.none { it.unitId == "worker" })
            assertIs<ActionResult.Accepted>(session.unload(true))
            repeat(31) { session.tick() }
            assertTrue(session.observation.units.any { it.unitId == "worker" })
            assertEquals("ship", session.selectedId)
        }
    }
}
