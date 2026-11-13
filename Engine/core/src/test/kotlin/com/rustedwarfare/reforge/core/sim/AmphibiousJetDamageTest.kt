package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetDamageTest {
    @Test fun lightning_applies_half_direct_damage_to_a_real_native_building() {
        val factory = AmphibiousJetScene.scenario().unitDefinitions.getValue("airFactory")
        withJetTarget(factory, Position(100.0, 200.0)) { context ->
            var hit: Observation? = null
            for (q in 0L..80L) {
                val row = context.advance(Step(q, 1.0, "sim-tick"))
                if (row.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "jet" }) { hit = row; break }
            }
            val row = assertNotNull(hit)
            assertEquals(977.5, row.units.single { it.unitId == "target" }.health)
            assertEquals(22.5, row.projectileEvents.single { it.kind == "impact" && it.projectile.sourceUnitId == "jet" }.damage)
        }
    }

    @Test fun lightning_spends_full_shield_damage_but_only_deflects_ten_percent_of_health_damage() {
        withJetTarget(HeavyMechTestScene.declared(), Position(100.0, 200.0)) { context ->
            var hit: Observation? = null
            for (q in 0L..80L) {
                val row = context.advance(Step(q, 1.0, "sim-tick"))
                if (row.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "jet" }) { hit = row; break }
            }
            val row = assertNotNull(hit)
            val target = row.units.single { it.unitId == "target" }
            assertEquals(2459.5, target.health)
            assertEquals(455f, target.shield?.current)
            assertEquals(40.5, row.projectileEvents.single { it.kind == "impact" && it.projectile.sourceUnitId == "jet" }.damage)
        }
    }
}
