package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousJetTargetTest {
    @Test fun actual_underwater_threshold_selects_water_targets_and_rejects_dry_or_flying_targets() =
        WaterTestScene.withMap { map ->
            val base = AmphibiousJetScene.scenario()
            val jet = base.unitDefinitions.getValue("amphibiousJet")
            val target = UnitDefinition("surface", "OwnedTarget", 6.0, 6.0, 1, jet.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                durability = DurabilityProfile(1000.0, null))
            val underwater = UnitDefinition("underwater", "OwnedUnderwaterTarget", 6.0, 6.0, 1, jet.imagePath, 1.0,
                target.movement.copy(type = MovementType.WATER), emptySet(), durability = DurabilityProfile(1000.0, null),
                fixedUnderwaterTarget = FixedUnderwaterTargetProfile())
            val context = AmphibiousJetScene.open(base.copy(map = map,
                unitDefinitions = base.unitDefinitions + mapOf("surface" to target, "underwater" to underwater),
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0),
                    UnitSpawn("wet", "p2", "surface", Position(650.0, 200.0), 0.0),
                    UnitSpawn("dry", "p2", "surface", Position(300.0, 200.0), 0.0),
                    UnitSpawn("deep", "p2", "underwater", Position(650.0, 260.0), 0.0, -8.0),
                    UnitSpawn("air", "p2", "amphibiousJet", Position(720.0, 200.0), 0.0)),
                combatContract = CombatContract(12345, -1,
                    mapOf("jet" to 8L, "wet" to 9L, "dry" to 10L, "deep" to 11L, "air" to 12L), 13L)))
            try {
                fun attack(id: String) = context.submit(AttackAction("attack-$id-${context.observe().stepIndex}", "p1", "jet", id, context.world))
                assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(attack("deep")).error.code)
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                assertIs<ActionResult.Accepted>(attack("air"), "Intent alone cannot change attack categories")
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "jet", context.world)))
                for (q in 0L..300L) context.advance(Step(q, 1.0, "sim-tick"))
                assertTrue(assertNotNull(context.observe().units.single { it.unitId == "jet" }.amphibiousJet).underwater)
                assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(attack("air")).error.code)
                assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(attack("dry")).error.code)
                assertIs<ActionResult.Accepted>(attack("wet"))
                assertIs<ActionResult.Accepted>(attack("deep"))
            } finally { context.close() }
        }
}
