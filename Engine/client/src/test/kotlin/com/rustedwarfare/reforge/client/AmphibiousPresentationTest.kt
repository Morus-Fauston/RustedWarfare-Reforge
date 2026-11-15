package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class AmphibiousPresentationTest {
    @Test fun a_falling_dropship_draws_above_a_ship_until_impact() {
        val ship = UnitObservation("ship", "p1", "heavyBattleship", Position(500.0, 300.0),
            90.0, 0.0, 0.0, null, emptyList(), MovementState.IDLE)
        val pose = FlightPose(Position(0.0, 0.0), 20.0, 0.0, 0.0, 0.0, 0.0)
        val falling = ship.copy(unitId = "dropship", typeId = "dropship", position = Position(500.0, 320.0),
            alive = false, health = 0.0, height = 20.0, flightPose = pose)
        assertEquals(ship.position, falling.displayedBodyPosition())
        assertEquals(listOf(ship, falling), unitDrawOrder(listOf(falling, ship)))
        for (height in listOf(0.0, -10.0)) {
            val wreck = falling.copy(position = ship.position, height = height, flightPose = pose.copy(height = height))
            assertEquals(listOf(wreck, ship), unitDrawOrder(listOf(ship, wreck)))
        }
    }

    @Test fun a_surface_jet_draws_above_the_ship_and_wreck_in_either_observation_order() {
        val base = AmphibiousSandboxScene.load().scenario
        val scene = base.copy(units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 300.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L))
        SandboxSession(scene).use { session ->
            val jet = session.observation.units.single()
            val ship = jet.copy(unitId = "ship", typeId = "battleship", amphibiousJet = null)
            val wreck = ship.copy(unitId = "wreck", alive = false)
            for (input in listOf(listOf(jet, ship, wreck), listOf(wreck, ship, jet))) {
                assertEquals(listOf(wreck, ship, jet), unitDrawOrder(input))
            }
            val submerged = jet.copy(amphibiousJet = assertNotNull(jet.amphibiousJet).copy(surfacePresentation = false))
            assertEquals(listOf(wreck, submerged, ship), unitDrawOrder(listOf(ship, submerged, wreck)))
        }
    }

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
