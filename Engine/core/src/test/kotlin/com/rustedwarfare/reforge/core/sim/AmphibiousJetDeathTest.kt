package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousJetDeathTest {
    @Test fun a_real_hit_preserves_airborne_height_then_the_corpse_falls_and_settles_by_the_landing_terrain() {
        for (liquid in listOf("water", "lava", "lava-cliff")) WaterTestScene.withMap(liquidProperty = liquid) { map ->
            for (x in listOf(100.0, 500.0)) {
                val base = AmphibiousJetScene.scenario()
                val jet = base.unitDefinitions.getValue("amphibiousJet")
                val shot = ProjectileProfile(600.0, 60.0, 1000.0)
                val weapon = NamedWeaponDefinition("probe", "probe", shot, TurretMount(), 10000.0, 0.0, 360.0, 0.0,
                    null, 1.0, canAttackFlyingUnits = true, canAttackUnderwaterUnits = true, warmup = 300.0)
                val probe = UnitDefinition("probe", "OwnedProbe", 6.0, 6.0, 1, jet.imagePath, 1.0,
                    MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                    combat = CombatProfile(10000.0, 600.0, 10000.0, 0.0, 360.0, 0.0, shot, null, null, 1.0,
                        namedWeapons = WeaponCatalog(listOf(weapon))))
                val context = AmphibiousJetScene.open(base.copy(map = map,
                    unitDefinitions = base.unitDefinitions + ("probe" to probe),
                    units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(x, 200.0), 90.0),
                        UnitSpawn("gun", "p2", "probe", Position(200.0, 200.0), 270.0)),
                    combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "gun" to 9L), 10L)))
                try {
                    var q = 0L
                    while (context.observe().units.single { it.unitId == "jet" }.alive && q < 360L)
                        context.advance(Step(q++, 1.0, "sim-tick"))
                    val dead = context.observe().units.single { it.unitId == "jet" }
                    assertFalse(dead.alive)
                    assertTrue(dead.height > 15.0, "Death must preserve the actual altitude for the air-base fall")
                    assertEquals("inactive-unit", assertIs<ActionResult.Rejected>(context.submit(
                        AmphibiousFlightAction("dead-dive", "p1", "jet", false, context.world))).error.code)
                    val next = context.advance(Step(q++, 1.0, "sim-tick")).units.single { it.unitId == "jet" }
                    assertEquals((dead.height.toFloat() - .06f).toDouble(), next.height)
                    assertEquals(.06f, assertNotNull(next.amphibiousJet).sinkSpeed)
                    repeat(400) { context.advance(Step(q++, 1.0, "sim-tick")) }
                    val settled = context.observe().units.single { it.unitId == "jet" }
                    if (x < 400.0) assertEquals(0.0, settled.height)
                    else assertTrue(settled.height < -10.0, "Native air sinking retains its final step past -10 on $liquid")
                    repeat(10) { context.advance(Step(q++, 1.0, "sim-tick")) }
                    assertEquals(settled, context.observe().units.single { it.unitId == "jet" })
                } finally { context.close() }
            }
        }
    }
}
