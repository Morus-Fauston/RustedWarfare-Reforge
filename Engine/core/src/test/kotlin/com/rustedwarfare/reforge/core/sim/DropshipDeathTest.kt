package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class DropshipDeathTest {
    @Test fun combat_death_releases_passengers_and_preserves_altitude_for_the_air_base_fall() {
        for (liquid in listOf("water", "lava", "lava-cliff")) WaterTestScene.withMap(liquidProperty = liquid) { map ->
            for (x in listOf(100.0, 405.0)) {
                val base = DropshipScene.scenario(map, listOf(
                    UnitSpawn("z-air", "p1", "dropship", Position(x, 200.0), 90.0),
                    UnitSpawn("a-crew", "p1", "builder", Position(if (x < 400.0) x + 10.0 else 390.0, 200.0), 90.0),
                    UnitSpawn("gun", "p2", "probe", Position(200.0, 200.0), 270.0)))
                val shot = ProjectileProfile(600.0, 60.0, 1000.0)
                val weapon = NamedWeaponDefinition("probe", "probe", shot, TurretMount(), 10000.0, 0.0, 360.0, 0.0,
                    null, 1.0, canAttackFlyingUnits = true, warmup = 50.0)
                val probe = UnitDefinition("probe", "OwnedProbe", 6.0, 6.0, 1, base.unitDefinitions.getValue("dropship").imagePath, 1.0,
                    MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                    combat = CombatProfile(10000.0, 600.0, 10000.0, 0.0, 360.0, 0.0, shot, null, null, 1.0,
                        namedWeapons = WeaponCatalog(listOf(weapon))))
                val context = DropshipScene.open(base.copy(unitDefinitions = base.unitDefinitions + ("probe" to probe),
                    combatContract = CombatContract(12345, -1, mapOf("z-air" to 8L, "a-crew" to 9L, "gun" to 10L), 11L)))
                try {
                    assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "a-crew", "z-air", LoadDirection.INTO, context.world)))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("fly", "p1", "z-air", Position(740.0, 200.0), context.world)))
                    var step = 0L
                    while (context.observe().units.single { it.unitId == "z-air" }.alive && step < 100L)
                        context.advance(Step(step++, 1.0, "sim-tick"))
                    val death = context.observe()
                    val dead = death.units.single { it.unitId == "z-air" }
                    assertFalse(dead.alive)
                    assertTrue(dead.height > 10.0, "Air death must retain altitude: $dead")
                    assertEquals(0, dead.transport!!.passengerCount)
                    assertNull(death.units.single { it.unitId == "a-crew" }.carriedBy)
                    assertEquals(-1.0, death.units.single { it.unitId == "a-crew" }.health)
                    val following = context.advance(Step(step++, 1.0, "sim-tick"))
                    assertEquals((dead.height.toFloat() - .06f).toDouble(), following.units.single { it.unitId == "z-air" }.height)
                    assertFalse(following.units.single { it.unitId == "a-crew" }.alive)
                    repeat(500) { context.advance(Step(step++, 1.0, "sim-tick")) }
                    val settled = context.observe().units.single { it.unitId == "z-air" }
                    if (x < 400.0) assertEquals(0.0, settled.height) else assertTrue(settled.height < -10.0)
                    repeat(10) { context.advance(Step(step++, 1.0, "sim-tick")) }
                    assertEquals(settled, context.observe().units.single { it.unitId == "z-air" })
                } finally { context.close() }
            }
        }
    }
}
