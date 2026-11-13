package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetReverseTest {
    @Test fun native_reverse_ratio_is_inherited_bc_rather_than_the_aZ_axis_multiplier() {
        val jet = AmphibiousJetScene.scenario().unitDefinitions.getValue("amphibiousJet")
        assertEquals(.6, jet.movement.reverseSpeedPercentage)
    }

    @Test fun a_short_single_move_behind_the_jet_reverses_with_native_ratio_in_both_physical_modes() {
        WaterTestScene.withMap { map -> for (underwater in listOf(false, true)) {
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                if (underwater) assertIs<ActionResult.Accepted>(context.submit(
                    AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                for (q in 0L..399L) context.advance(Step(q, 1.0, "sim-tick"))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("back", "p1", "jet", Position(450.0, 200.0), context.world)))
                for (q in 400L..409L) context.advance(Step(q, 1.0, "sim-tick"))
                val jet = context.observe().units.single()
                assertTrue(jet.position.x < 500, "Short backward command should reverse, underwater=$underwater: $jet")
                assertEquals(90.0, jet.heading)
                assertEquals(if (underwater) -.24 else -.84, jet.speed, 1e-9)
            } finally { context.close() }
        } }
    }
}
