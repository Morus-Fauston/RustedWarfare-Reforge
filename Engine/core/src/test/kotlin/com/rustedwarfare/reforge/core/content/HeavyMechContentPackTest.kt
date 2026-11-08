package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeavyMechContentPackTest {
    @Test fun player_can_load_the_same_four_legged_cliff_movement_slice_through_all_three_content_formats() {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(inputs.resolve("heavy-mech-production/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            assertEquals(setOf("mechHeavyMissile"), pack.units.keys)
            val unit = pack.units.getValue("mechHeavyMissile")
            assertEquals(2500.0, unit.maximumHealth)
            assertEquals(BuildProfile(8500L, .0006f), unit.build)
            assertEquals(22.0, unit.radius)
            assertEquals(19.0, unit.displayRadius)
            assertEquals(1.1, unit.imageScale)
            assertEquals(MovementType.OVER_CLIFF, unit.movement.type)
            assertTrue(unit.movement.slidingMode)
            assertTrue(unit.movement.ignoringBody)
            assertEquals(.61, unit.movement.moveSpeed)
            assertEquals(.07, unit.movement.moveAccelerationSpeed)
            assertEquals(.17, unit.movement.moveDecelerationSpeed)
            assertEquals(1.0, unit.movement.maxTurnSpeed)
            assertEquals(.5, unit.movement.turnAcceleration)
            val legs = assertNotNull(unit.mech).legs
            assertEquals(listOf(-18.0 to 15.0, 18.0 to 15.0, -18.0 to -15.0, 18.0 to -15.0), legs.map { it.x to it.y })
            assertEquals(listOf(-5.0 to 10.0, 5.0 to 10.0, -5.0 to -10.0, 5.0 to -10.0), legs.map { it.attachX to it.attachY })
            assertTrue(legs.all { it.warmUp == 0.0 && it.moveSpeed == 2.9 && it.heightSpeed == .3 })
            assertTrue(legs.all { it.holdMinimum == 5.0 && it.holdWithMovingNeighbour == 19.0 && it.predictionMultiplier == .7 })
            val combat = assertNotNull(unit.combat)
            assertNull(combat.deadImagePath)
            assertEquals(310.0, combat.attackRange)
            assertTrue(combat.multiTargeting)
            val weapons = assertNotNull(combat.namedWeapons)
            assertEquals(listOf("smallLaser", "1", "2", "3"), weapons.ordered.map { it.name })
            val small = weapons.byName.getValue("smallLaser")
            assertTrue(small.canAttackLandUnits)
            assertFalse(small.canAttackFlyingUnits)
            assertEquals(170.0, small.maximumRange)
            assertEquals(90.0, small.shootDelay)
            assertEquals(35.0, small.projectile.directDamage)
            assertEquals("small_turret.png", assertNotNull(small.imagePath).fileName.toString())
            val parent = weapons.byName.getValue("1")
            assertFalse(parent.canShoot)
            assertEquals("turret.png", assertNotNull(parent.imagePath).fileName.toString())
            for ((name, warmup) in listOf("2" to 2.0, "3" to 20.0)) {
                val child = weapons.byName.getValue(name)
                assertEquals("1", child.attachedTo)
                assertTrue(child.slave && child.canShoot && child.canAttackFlyingUnits)
                assertFalse(child.canAttackLandUnits)
                assertEquals(warmup, child.warmup)
                assertEquals(200.0, child.projectile.directDamage)
                assertEquals(.6, child.projectile.speed)
                assertEquals(LinearProjectileAcceleration(8.0, .1), child.projectile.acceleration)
            }
            assertEquals("2", weapons.byName.getValue("3").linkDelayWithTurret)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
