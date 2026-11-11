package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusFormsHeightTest {
    @Test fun non_air_forms_use_their_own_height_targets_without_changing_energy_or_movement_class() = WaterTestScene.withMap { map ->
        // Reviewed custom/j non-AIR branch: lower-bound lift .2, then fixed approach .005;
        // submerged additionally falls .03 on its first update. Float literals fix operation rounding.
        for ((type, firstHeight) in listOf("nautilusSubmarine" to (-.005f - .03f),
            "nautilusSubmarineSurface" to .005f, "nautilusSubmarineLand" to (.2f + .005f))) {
            val scenario = NautilusScene.scenario(map).let { s -> s.copy(units = s.units.map { it.copy(typeId = type) }) }
            val c = NautilusScene.open(scenario)
            try {
                c.advance(Step(0, 1.0, "sim-tick"))
                val unit = c.observe().units.single()
                assertEquals(firstHeight.toDouble(), unit.height)
                assertEquals(EnergyObservation(6f, 6f, false), unit.energy)
                assertEquals(Position(500.0, 180.0), unit.position)
            } finally { c.close() }
        }
    }
}
