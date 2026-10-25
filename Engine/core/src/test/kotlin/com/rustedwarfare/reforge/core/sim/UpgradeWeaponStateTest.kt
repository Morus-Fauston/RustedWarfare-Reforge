package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import kotlin.math.*
import kotlin.test.*

class UpgradeWeaponStateTest {
    private fun scene(): Scenario {
        val base = UpgradeTestContent.scenario()
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = UpgradeTestContent.variant(tank, id = "dummy", combat = null,
            durability = DurabilityProfile(10000.0, tank.combat!!.deadImagePath))
        return base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            units = base.units + UnitSpawn("z", "p2", target.id, Position(350.0, 150.0), 270.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("t" to 11L, "z" to 12L)))
    }

    @Test fun basic_shot_parity_survives_conversion_and_first_gun_shots_use_the_prior_bit_then_alternate() {
        val parities = mutableSetOf<Boolean>()
        for (start in listOf(0L, 20L)) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
            try {
                var step = 0L
                var baseShots = 0
                while (step < start) baseShots += c.advance(Step(step++, 1.0, "sim-tick")).projectileEvents.count { it.kind == "born" }
                assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("gun", "p1", "t", "upgrade_gun", c.world)))
                while (c.observe().units.first { it.unitId == "t" }.typeId == "c_turret_t1" && step < 1100L) {
                    val observed = c.advance(Step(step++, 1.0, "sim-tick"))
                    baseShots += observed.projectileEvents.count { it.kind == "born" }
                    assertEquals(baseShots % 2 != 0, observed.combat!!.weapons.single().oddShot)
                }
                assertEquals("c_turret_t2_gun", c.observe().units.first { it.unitId == "t" }.typeId)
                var bit = baseShots % 2 != 0
                parities += bit
                val shots = mutableListOf<ProjectileObservation>()
                while (shots.size < 2 && step < 1200L) {
                    val born = c.advance(Step(step++, 1.0, "sim-tick")).projectileEvents.singleOrNull { it.kind == "born" } ?: continue
                    val shot = born.projectile
                    // Independent projection onto the ScreenPlane right vector, relative to the fixed mount pivot.
                    val radians = Math.toRadians(shot.heading)
                    val forwardX = sin(radians); val forwardY = -cos(radians)
                    val dx = shot.position.x - 236.0 - forwardX * 22.0
                    val dy = shot.position.y - 150.0 - forwardY * 22.0
                    assertEquals(if (bit) 3.0 else -3.0, dx * cos(radians) + dy * sin(radians), 1e-9)
                    bit = !bit
                    assertEquals(bit, c.observe().combat!!.weapons.single().oddShot)
                    shots += shot
                }
                assertEquals(2, shots.size)
            } finally { c.close() }
        }
        assertEquals(setOf(false, true), parities)
    }

    @Test fun conversion_after_the_normal_update_preserves_target_rng_offset_idle_and_weapon_rotation() {
        for (branch in listOf("upgrade_gun", "upgrade_artillery")) {
            val scene = scene()
            val actual = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(actual.submit(UpgradeAction("convert", "p1", "t", branch, actual.world)))
                var step = 0L
                while (actual.observe().units.first { it.unitId == "t" }.typeId == "c_turret_t1" && step < 2600L) {
                    actual.advance(Step(step, 1.0, "sim-tick")); control.advance(Step(step++, 1.0, "sim-tick"))
                }
                val a = actual.observe().combat!!.weapons.single()
                val b = control.observe().combat!!.weapons.single()
                assertEquals(b.copy(pivot = a.pivot, muzzle = a.muzzle,
                    cooldown = minOf(b.cooldown, if (branch == "upgrade_gun") 20.0 else 220.20001220703125)), a)
                val unit = actual.observe().units.first { it.unitId == "t" }
                assertEquals(control.observe().units.first { it.unitId == "t" }.turretHeading, unit.turretHeading)
                assertTrue(a.targetUnitId != null)
                assertTrue(a.randomCounter != 0)
                val oldShots = actual.observe().projectiles
                assertTrue(oldShots.isNotEmpty(), "The completion slot must contain real earlier basic-turret shots")
                assertEquals(control.observe().projectiles, oldShots)
                assertTrue(oldShots.all { it.ground == null })
                val oldIds = oldShots.map { it.objectId }.toSet()
                repeat(25) {
                    val upgraded = actual.advance(Step(step, 1.0, "sim-tick"))
                    val unchanged = control.advance(Step(step++, 1.0, "sim-tick"))
                    assertEquals(unchanged.projectiles.filter { it.objectId in oldIds }, upgraded.projectiles.filter { it.objectId in oldIds })
                    assertEquals(unchanged.projectileEvents.filter { it.projectile.objectId in oldIds },
                        upgraded.projectileEvents.filter { it.projectile.objectId in oldIds })
                }
            } finally { actual.close(); control.close() }
        }
    }
}
