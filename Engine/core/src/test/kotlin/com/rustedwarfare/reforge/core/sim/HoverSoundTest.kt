package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HoverSoundTest {
    @Test fun hover_fire_uses_its_own_event_and_original_pitch_range_after_authorization() {
        val base = HoverProductionScene.scenario()
        val source = base.copy(units = listOf(UnitSpawn("hover", "p1", "hoverTank", Position(100.0, 300.0), 90.0),
            UnitSpawn("enemy", "p2", "c_tank", Position(200.0, 300.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("hover" to 8L, "enemy" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "hover", "enemy", context.world)))
            val heard = mutableListOf<AuthorizedSound>()
            repeat(100) { step ->
                context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                heard += context.consumeSounds(context.world, "p1", SoundListener(Position(150.0, 300.0), 300f)).sounds
            }
            val hover = heard.filter { it.eventId == "reforge:events/hover-fire" }
            assertTrue(hover.isNotEmpty(), heard.toString())
            assertTrue(hover.all { it.pitch in 1.23f..1.37f && it.volume == .3f })
        } finally { context.close() }
    }
}
