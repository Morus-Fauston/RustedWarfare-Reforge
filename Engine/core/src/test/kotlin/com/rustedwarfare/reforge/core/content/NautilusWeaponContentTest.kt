package com.rustedwarfare.reforge.core.content

import com.rustedwarfare.reforge.core.sim.NautilusScene
import kotlin.test.*

class NautilusWeaponContentTest {
    @Test fun all_formats_load_six_energy_launchers_and_distinct_torpedo_and_surface_missile_profiles() {
        for (format in listOf("ini", "json", "hjson")) {
            val definitions = NautilusScene.definitions(format)
            for (id in listOf("nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand")) {
                val sub = id == "nautilusSubmarine"
                val combat = assertNotNull(definitions.getValue(id).combat)
                assertEquals(240.0, combat.attackRange)
                val catalog = combat.namedWeapons!!
                val launcher = catalog.ordered.last()
                assertEquals("botTurret", launcher.name)
                assertEquals(TurretMount(10.0, 22.0), launcher.mount)
                assertFalse(launcher.canShoot)
                val weapons = catalog.ordered.dropLast(1)
                assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), weapons.map { it.name })
                assertFalse(weapons.first().canShoot)
                assertEquals("scoutBotProjectile", weapons.first().projectileName)
                assertEquals(0.0, weapons.first().projectile.life)
                assertEquals(5.0, weapons.first().projectile.speed)
                assertEquals(listOf(TurretMount(10.0, 22.0), TurretMount(-10.0, 22.0), TurretMount(-10.0, 14.0),
                    TurretMount(10.0, 14.0), TurretMount(10.0, 4.0), TurretMount(-10.0, 4.0)), weapons.drop(1).map { it.mount })
                assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), weapons.drop(1).map { it.warmup })
                assertTrue(weapons.drop(1).all { it.energyUsage == 1f && it.shootDelay == 300.0 && it.turnSpeed == 40.0 })
                assertTrue(weapons.drop(2).all { it.linkDelayWithTurret == "2" })
                val projectile = weapons[1].projectile
                assertEquals(if (sub) 50.0 else 35.0, projectile.directDamage)
                assertEquals(if (sub) 250.0 else 240.0, projectile.life)
                assertEquals(if (sub) .15 else .3, projectile.speed)
                assertEquals(LinearProjectileAcceleration(if (sub) 1.2 else 6.0, .1), projectile.acceleration)
                assertEquals(CustomBallisticProfile(40f, if (sub) -2f else 50f), projectile.ballistic)
                assertEquals(if (sub) null else OrdinarySplashProfile(180f, 55f), projectile.splash)
                assertTrue(projectile.retargetOnDeath)
                assertTrue(weapons.drop(1).all { it.canAttackFlyingUnits == !sub && it.canAttackUnderwaterUnits == sub })
            }
        }
    }
}
