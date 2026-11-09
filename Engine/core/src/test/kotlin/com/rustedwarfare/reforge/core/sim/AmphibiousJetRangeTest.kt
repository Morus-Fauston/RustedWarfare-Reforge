package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousJetRangeTest {
    @Test fun dive_reduces_actual_weapon_range_before_crossing_the_underwater_threshold() =
        WaterTestScene.withMap { map ->
            val base = AmphibiousJetScene.scenario()
            val jet = base.unitDefinitions.getValue("amphibiousJet")
            val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, jet.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                durability = DurabilityProfile(1000.0, null))
            val context = AmphibiousJetScene.open(base.copy(map = map,
                unitDefinitions = base.unitDefinitions + ("target" to target),
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0),
                    UnitSpawn("target", "p2", "target", Position(650.0, 200.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "target" to 9L), 10L)))
            try {
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                repeat(100) { q ->
                    val row = context.advance(Step(q.toLong(), 1.0, "sim-tick"))
                    assertTrue(row.projectileEvents.none { it.kind == "born" }, "150 must be outside water-mode range 100 at $q")
                }
                assertEquals(1000.0, context.observe().units.single { it.unitId == "target" }.health)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("fly", "p1", "jet", true, context.world)))
                var fired = false
                for (q in 100L..450L) {
                    val before = context.observe().units.single { it.unitId == "jet" }
                    val row = context.advance(Step(q, 1.0, "sim-tick"))
                    if (row.projectileEvents.any { it.kind == "born" }) {
                        assertTrue(before.height >= 0.0, "Fly intent alone cannot grant the AIR range")
                        fired = true
                        break
                    }
                }
                assertTrue(fired, "Returning to AIR range should release both actual side weapons")
            } finally { context.close() }
        }
}
