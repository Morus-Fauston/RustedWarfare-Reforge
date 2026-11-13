package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetHeightTest {
    @Test fun native_jet_begins_at_zero_and_accelerates_upward_without_a_helicopter_rotor() {
        val base = AmphibiousJetScene.scenario()
        val context = AmphibiousJetScene.open(base.copy(units = listOf(
            UnitSpawn("jet", "p1", "amphibiousJet", Position(300.0, 250.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
        try {
            assertEquals(0.0, context.observe().units.single().height)
            for ((q, expected) in listOf(.006f, .018f, .036f).withIndex()) {
                context.advance(Step(q.toLong(), 1.0, "sim-tick"))
                assertEquals(expected.toDouble(), context.observe().units.single().height, "step $q")
            }
            for (q in 3L..239L) context.advance(Step(q, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single().height in 18.5..21.5)
            assertEquals(Position(300.0, 250.0), context.observe().units.single().position)
        } finally { context.close() }
    }
}
