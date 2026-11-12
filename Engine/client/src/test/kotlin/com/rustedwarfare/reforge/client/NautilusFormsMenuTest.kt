package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NautilusFormsMenuTest {
    @Test fun surface_and_dive_cards_describe_the_real_motion_lock_and_surface_exposes_ordinary_unload() {
        val base = NautilusSandboxScene.scenario()
        val scene = base.copy(units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L), 9L))
        SandboxSession(scene).use { s ->
            s.select(Position(500.0, 300.0))
            val surface = s.actionMenu.unitActions.single()
            assertEquals("Surface", surface.title)
            assertTrue(surface.tooltip.contains("Movement locked"))
            assertIs<ActionResult.Accepted>(s.unitAction("surface"))
            s.tick()
            assertEquals("Nautilus (Surface)", s.actionMenu.selectedTitle)
            assertEquals("Dive", s.actionMenu.unitActions.single { it.name == "dive" }.title)
            assertNotNull(s.actionMenu.transportAction)
            assertIs<ActionResult.Accepted>(s.produce("hoverTank"))
            repeat(255) { s.tick() }
            assertTrue(s.observation.units.any { it.typeId == "hoverTank" })
            assertIs<ActionResult.Accepted>(s.unitAction("dive"))
            repeat(135) { s.tick() }
            assertEquals("Nautilus", s.actionMenu.selectedTitle)
            assertNull(s.actionMenu.transportAction)
        }
    }
}
