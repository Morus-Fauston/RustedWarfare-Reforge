package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.*

class BattleshipContentTest {
    @Test
    fun three_formats_preserve_water_body_five_weapons_and_three_projectile_profiles() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("naval-production/$format/production-package"))).value
            val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.composeContentPacks(listOf(pack))).value
            assertEquals(setOf("heavyBattleship", "seaFactory", "seaFactoryT2", "navalUnderwaterTarget", "builder", "c_tank", "helicopter"), set.units.keys)
            val ship = set.units.getValue("heavyBattleship")
            assertEquals(4000.0, ship.maximumHealth)
            assertEquals(40.0, ship.radius)
            assertEquals(MovementProfile(MovementType.WATER, .7, .02, .05, 1.0, .05, false, false, 1.0), ship.movement)
            assertEquals(BuildProfile(6000L, .0004f), ship.build)
            assertNull(ship.mech)
            assertTrue(ship.combat!!.multiTargeting)
            val weapons = ship.combat!!.namedWeapons!!.ordered
            assertEquals(listOf("1", "2", "3", "4", "5"), weapons.map { it.name })
            assertEquals(listOf(false, false, true, true, true), weapons.map { it.invisible })
            assertEquals(listOf("1", "1", "2", "3", "3"), weapons.map { it.projectileName })
            assertEquals(TurretMount(0.0, 26.0), weapons[0].mount)
            assertEquals(listOf(-145.0, 125.0, 180.0, 180.0, 0.0), weapons.map { it.idleDirectionReversing })
            assertEquals(listOf(FireSoundProfile.NAVAL_MAIN, FireSoundProfile.NAVAL_MAIN, null,
                FireSoundProfile.NAVAL_MISSILE, FireSoundProfile.NAVAL_MISSILE), weapons.map { it.fireSound })
            assertEquals(30.0, weapons[1].warmup)
            assertEquals("4", weapons[4].attachedTo)
            assertEquals("4", weapons[4].linkDelayWithTurret)
            assertFalse(weapons[4].slave)
            assertEquals(110f, weapons[0].projectile.ground!!.areaDamage)
            assertEquals(45f, weapons[0].projectile.ground!!.areaRadius)
            assertEquals(LinearProjectileAcceleration(1.2, .1), weapons[2].projectile.acceleration)
            assertTrue(weapons[2].projectile.nonDeflectable)
            assertEquals(LinearProjectileAcceleration(3.0, .1), weapons[3].projectile.acceleration)
            assertEquals(CustomBallisticProfile(0f, 4f), weapons[3].projectile.ballistic)
            assertFalse(weapons[3].projectile.retargetOnDeath)
            assertTrue("transportSlotsNeeded" in ship.retainedUnconsumed)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
