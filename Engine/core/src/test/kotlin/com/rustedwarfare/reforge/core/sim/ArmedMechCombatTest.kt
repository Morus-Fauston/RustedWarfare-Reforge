package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class ArmedMechCombatTest {
    @Test
    fun armed_reverse_keeps_the_real_main_body_for_twenty_five_later_updates_then_turns_in_the_positive_half_turn_direction() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val mech = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        val scene = MechTestScene.scenario(position = Position(400.0, 100.0)).copy(unitDefinitions = mapOf("mech" to mech),
            combatContract = CombatContract(12345, -1, mapOf("u1" to 8L), 9L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("reverse", "p1", "u1", Position(50.0, 100.0), context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            for (step in 1L..25L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(90.0, context.observePlayer(context.world, "p1").weapons.single().turretHeading)
            }
            context.advance(Step(26, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p1")
            assertEquals(90.3, view.weapons.single().turretHeading, 1e-12)
            assertEquals(90.3, assertNotNull(view.units.single().mechPose).forwardHeading, 1e-12)
            assertEquals(90.0, view.units.single().heading)
        } finally { context.close() }
    }

    @Test
    fun armed_upper_body_follows_previous_motion_intent_while_legs_keep_the_walkers_frame() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val mech = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        val scene = MechTestScene.scenario(heading = 0.0).copy(unitDefinitions = mapOf("mech" to mech),
            combatContract = CombatContract(12345, -1, mapOf("u1" to 8L), 9L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "u1", Position(500.0, 100.0), context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, assertNotNull(first.mechPose).forwardHeading)
            context.advance(Step(1, 1.0, "sim-tick"))
            val view = context.observePlayer(context.world, "p1")
            val second = view.units.single()
            assertEquals(1.5, second.heading, 1e-12)
            assertEquals(.3, view.weapons.single().turretHeading, 1e-12)
            assertEquals(.3, assertNotNull(second.mechPose).forwardHeading, 1e-12)
            assertEquals(0.0, second.speed)
            assertTrue(second.mechPose!!.legs.all { it.heading == 0.0 })
        } finally { context.close() }
    }

    @Test
    fun real_mech_warms_fires_a_zero_speed_shot_then_accelerates_it_before_hitting_and_killing_a_mech() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val mech = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        val combat = assertNotNull(mech.combat)
        val quiet = UnitDefinition(mech.id, mech.className, mech.radius, mech.displayRadius,
            mech.totalFrames, mech.imagePath, mech.imageScale, mech.movement, mech.retainedUnconsumed,
            mech.fogOfWarSightRange, combat = combat.copy(namedWeapons = WeaponCatalog(
                assertNotNull(combat.namedWeapons).ordered.map { it.copy(canShoot = false) })), mech = mech.mech)
        val scene = MechTestScene.scenario().copy(unitDefinitions = mapOf("mech" to mech, "quiet" to quiet),
            units = listOf(UnitSpawn("u1", "p1", "mech", Position(100.0, 100.0), 90.0),
                UnitSpawn("u2", "p2", "quiet", Position(200.0, 100.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("u1" to 8L, "u2" to 9L), 10L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var shot: ProjectileObservation? = null
            var birthStepPosition: Position? = null
            var bornAt = -1L
            for (step in 0L..20L) {
                val state = context.advance(Step(step, 1.0, "sim-tick"))
                val born = state.projectileEvents.firstOrNull { it.kind == "born" && it.projectile.sourceUnitId == "u1" }
                if (born != null) {
                    shot = born.projectile
                    birthStepPosition = state.projectiles.single { it.objectId == born.projectile.objectId }.position
                    bornAt = step
                    break
                }
            }
            val first = assertNotNull(shot)
            assertEquals(7L, bornAt, "Steps zero through six warm the weapon; the following update fires")
            assertEquals("mainBody", first.sourceWeaponName)
            assertEquals(first.position, birthStepPosition, "The existing new-object batch moves with speed zero in its birth step")
            val positions = listOf(first.position) + (1L..4L).map { offset ->
                context.advance(Step(bornAt + offset, 1.0, "sim-tick")).projectiles.single { it.objectId == first.objectId }.position
            }
            val distances = positions.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
            for ((expected, actual) in listOf(1.5, 3.0, 4.5, 6.0).zip(distances)) assertEquals(expected, actual, 1e-10)
            var sawDamage = false
            var sawDeath = false
            for (step in bornAt + 5..1100L) {
                val state = context.advance(Step(step, 1.0, "sim-tick"))
                val target = state.units.single { it.unitId == "u2" }
                if (assertNotNull(target.health) < 500.0) sawDamage = true
                if (!target.alive) {
                    sawDeath = state.events.any { it.kind == "unit-died" && it.unitId == "u2" }
                    assertEquals(0.0, target.speed)
                    assertTrue(target.tasks.isEmpty())
                    break
                }
            }
            assertTrue(sawDamage)
            assertTrue(sawDeath)
        } finally { context.close() }
    }
}
