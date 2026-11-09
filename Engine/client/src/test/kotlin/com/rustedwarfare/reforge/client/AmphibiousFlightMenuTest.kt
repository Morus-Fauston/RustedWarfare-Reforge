package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AmphibiousFlightMenuTest {
    @Test fun menu_uses_current_water_eligibility_and_preserves_the_order_while_fly_or_dive_only_changes_intent() {
        val base = AmphibiousSandboxScene.load().scenario
        val input = base.copy(units = listOf(
            UnitSpawn("wet", "p1", "amphibiousJet", Position(500.0, 300.0), 90.0),
            UnitSpawn("dry", "p1", "amphibiousJet", Position(300.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("wet" to 8L, "dry" to 9L), 10L))
        SandboxSession(input).use { session ->
            session.select(Position(300.0, 300.0))
            val dry = assertNotNull(session.actionMenu.flightAction)
            assertEquals("Dive", dry.title)
            assertFalse(dry.available)
            assertNull(session.amphibiousFlight(false))
            session.select(Position(500.0, 300.0))
            assertTrue(assertNotNull(session.actionMenu.flightAction).available)
            assertIs<ActionResult.Accepted>(session.move(Position(650.0, 300.0)))
            val before = session.observation.units.single { it.unitId == "wet" }
            assertIs<ActionResult.Accepted>(session.amphibiousFlight(false))
            val requested = session.observation.units.single { it.unitId == "wet" }
            assertEquals(before.height, requested.height)
            assertEquals(before.tasks, requested.tasks)
            assertEquals(before.pathPoints, requested.pathPoints)
            assertEquals("Fly", session.actionMenu.flightAction?.title)
            assertNull(session.amphibiousFlight(false))
            repeat(300) { session.tick() }
            assertTrue(session.observation.units.single { it.unitId == "wet" }.amphibiousJet!!.underwater)
            assertTrue(assertNotNull(session.actionMenu.selectedUnitInfo).contains("Underwater"))
            assertIs<ActionResult.Accepted>(session.amphibiousFlight(true))
            assertEquals("Dive", session.actionMenu.flightAction?.title)
            assertIs<ActionResult.Accepted>(session.stop())
            assertEquals(true, session.observation.units.single { it.unitId == "wet" }.amphibiousJet?.flyingIntent)
        }
    }
}
