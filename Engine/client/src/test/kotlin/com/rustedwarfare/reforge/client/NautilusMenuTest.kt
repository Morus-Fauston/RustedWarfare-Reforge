package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NautilusMenuTest {
    @Test fun the_selected_mobile_producer_shows_its_effective_time_and_cargo_without_an_unload_action_or_selectable_carried_bodies() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { s ->
            s.select(Position(500.0, 300.0))
            assertEquals("Nautilus", s.actionMenu.selectedTitle)
            assertNull(s.actionMenu.transportAction)
            assertNull(s.unload(true))
            val action = s.productionActions.single()
            assertEquals("hoverTank", action.typeId)
            assertEquals(.004f, action.rate)
            assertTrue(action.tooltip.contains("4.2s"))
            repeat(5) { assertIs<ActionResult.Accepted>(s.produce("hoverTank")) }
            repeat(1260) { s.tick() }
            val observed = s.observation.units.single()
            assertEquals("mother", observed.unitId)
            val manifest = assertNotNull(observed.transport)
            assertEquals(4, manifest.passengerCount)
            assertEquals(4, manifest.passengerIds!!.size)
            assertContains(assertNotNull(desktopPrimaryHeading(s.actionMenu, observed, s.selectedIds)),
                "Cargo ${manifest.occupiedSlots}/${manifest.capacity} · ${manifest.passengerCount}")
            assertEquals(1f, s.productionActions.single().progress)
            assertTrue(s.actionMenu.selectedUnitInfo!!.contains("Cargo 4 / 3"))
            assertTrue(s.actionMenu.selectedUnitInfo!!.contains("Production waiting: cargo full"))
            assertIs<ActionResult.Accepted>(s.cancelProduction("hoverTank"))
            assertEquals(0, s.productionActions.single().count)
            assertTrue(s.actionMenu.selectedUnitInfo!!.contains("Production waiting").not())
            assertNull(s.actionMenu.transportAction)
        }
    }
}
