package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.test.*

class AmphibiousJetProjectionTest {
    @Test fun visible_form_tracks_real_thresholds_while_enemy_cannot_read_future_intent_or_integrator() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                val initial = context.observePlayer(context.world, "p1").units.single()
                val form = assertNotNull(initial.amphibiousJet)
                assertEquals(true, form.flyingIntent)
                assertEquals(0f, form.verticalSpeed)
                assertEquals(MovementType.AIR, form.movementType)
                assertEquals(170.0, form.attackRange)
                assertFalse(form.underwater)
                assertNull(initial.flightPose, "A jet must not publish a rotor flight pose")
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                val requested = assertNotNull(context.observePlayer(context.world, "p1").units.single().amphibiousJet)
                assertEquals(false, requested.flyingIntent)
                assertEquals(MovementType.WATER, requested.movementType)
                assertEquals(100.0, requested.attackRange)
                assertEquals(1.4, requested.maximumSpeed)
                assertFalse(requested.underwater)
                val enemy = context.observePlayer(context.world, "p2")
                val publicForm = assertNotNull(enemy.units.single().amphibiousJet)
                assertNull(publicForm.flyingIntent)
                assertNull(publicForm.verticalSpeed)
                assertTrue(enemy.events.none { it.kind == "amphibious-dive-requested" })
                for (step in 0L..300L) context.advance(Step(step, 1.0, "sim-tick"))
                val submerged = assertNotNull(context.observe().units.single().amphibiousJet)
                assertTrue(submerged.underwater)
                assertFalse(submerged.surfacePresentation)
                assertEquals(.4, submerged.maximumSpeed)
                assertEquals(1f, submerged.wingFold)
            } finally { context.close() }
        }
}
