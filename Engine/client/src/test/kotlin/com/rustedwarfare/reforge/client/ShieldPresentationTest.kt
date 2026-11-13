package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class ShieldPresentationTest {
    @Test fun the_default_shield_draw_gate_and_hit_opacity_use_only_the_authorized_unit_state() {
        SandboxSession(HeavyMechClientScene.scenario()).use { session ->
            val unit = session.observation.units.single()
            assertEquals(.49f, ShieldPresentation.opacity(unit))
            assertEquals(.54f, ShieldPresentation.opacity(unit.copy(shield = ShieldObservation(250f, 500f, 25f))))
            assertEquals(.99f, ShieldPresentation.opacity(unit.copy(shield = ShieldObservation(500f, 500f, 500f))))
            assertNull(ShieldPresentation.opacity(unit.copy(shield = ShieldObservation(0f, 500f, 40f))))
            assertNull(ShieldPresentation.opacity(unit.copy(alive = false)))
            assertNull(ShieldPresentation.opacity(unit.copy(shield = null)))
        }
    }
    @Test fun selecting_the_real_heavy_shows_shield_capacity_separately_from_health() {
        SandboxSession(HeavyMechClientScene.scenario()).use { session ->
            session.select(Position(200.0, 200.0))
            assertTrue(assertNotNull(session.selectedUnitInfo).contains("Shield 500 / 500"))
            assertTrue(session.selectedUnitInfo!!.contains("2500 / 2500"))
        }
    }

    @Test fun authorized_session_evidence_keeps_the_exact_initial_shield_state() {
        SandboxSession(HeavyMechClientScene.scenario()).use { session ->
            val fields = session.observation.units.single().fields()
            assertEquals(mapOf("current" to 500f, "maximum" to 500f, "displayDamage" to 0f), fields["shield"])
        }
    }
}
