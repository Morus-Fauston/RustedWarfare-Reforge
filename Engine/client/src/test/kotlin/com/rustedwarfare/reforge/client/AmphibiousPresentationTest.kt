package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AmphibiousPresentationTest {
    @Test fun live_wings_follow_authorized_folding_mounts_and_body_height_is_also_the_selection_center() {
        val base = AmphibiousSandboxScene.load().scenario
        val scene = base.copy(units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L))
        val resources = SandboxRenderResources.from(scene)
        val appearance = resources.units.getValue("amphibiousJet")
        SandboxSession(scene).use { session ->
            fun wings() = appearance.amphibiousWings(session.observation.units.single(), session.observation.weapons)
            val open = wings()
            assertEquals(2, open.size)
            assertEquals(Position(507.0, 288.0), open.single { it.slot.name == "LEFT_WING" }.center)
            assertEquals(Position(507.0, 312.0), open.single { it.slot.name == "RIGHT_WING" }.center)
            session.select(Position(500.0, 300.0))
            assertIs<ActionResult.Accepted>(session.amphibiousFlight(false))
            repeat(301) { session.tick() }
            val wet = session.observation.units.single()
            assertEquals(Position(500.0, 308.0), wet.displayedBodyPosition())
            val folded = wings()
            assertEquals(Position(502.0, 301.0), folded.single { it.slot.name == "LEFT_WING" }.center)
            assertEquals(Position(502.0, 315.0), folded.single { it.slot.name == "RIGHT_WING" }.center)
            session.select(Position(500.0, 308.0))
            assertEquals("jet", session.selectedId)
            assertTrue(appearance.amphibiousWings(wet.copy(alive = false), session.observation.weapons).isEmpty())
        }
    }
}
