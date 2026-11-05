package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object HelicopterCombatScene {
    fun unit(): UnitDefinition {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("helicopter-production/json/package"))
        return assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units.getValue("helicopter")
    }
    fun scenario(): Scenario = HelicopterTestScene.scenario(unit = unit()).let { scene ->
        scene.copy(units = listOf(UnitSpawn("h1", "p1", "helicopter", Position(200.0, 200.0), 90.0),
            UnitSpawn("h2", "p2", "helicopter", Position(500.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("h1" to 8L, "h2" to 9L), 11L))
    }
    fun groundAgainstAir(): Scenario {
        val scene = scenario()
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        return scene.copy(unitDefinitions = scene.unitDefinitions + ("c_tank" to tank),
            units = listOf(UnitSpawn("h1", "p1", "c_tank", Position(200.0, 200.0), 90.0),
                UnitSpawn("h2", "p2", "helicopter", Position(280.0, 200.0), 270.0)))
    }
}

class HelicopterCombatTest {
    @Test fun a_real_helicopter_pursues_a_ground_target_and_hits_it_from_flying_height_for_seventeen_damage() {
        val input = HelicopterCombatScene.scenario()
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        val context = HelicopterTestScene.open(input.copy(unitDefinitions = input.unitDefinitions + ("c_tank" to tank),
            units = input.units.map { if (it.unitId == "h2") it.copy(typeId = "c_tank") else it }))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-ground", "p1", "h1", "h2", context.world)))
            val fired = mutableListOf<ProjectileObservation>()
            var hit: Observation? = null
            for (step in 0L..399L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                fired += observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "h1" }.map { it.projectile }
                if (observation.units.single { it.unitId == "h2" }.health!! < 210.0) {
                    hit = observation
                    break
                }
            }
            val damaged = assertNotNull(hit)
            assertTrue(fired.isNotEmpty() && fired.all { it.height >= 4.0 && it.sourceWeaponName == "gun" })
            assertEquals(193.0, damaged.units.single { it.unitId == "h2" }.health)
            assertEquals(0.0, damaged.units.single { it.unitId == "h2" }.height)
            assertEquals(150.0, damaged.units.single { it.unitId == "h1" }.health)
            assertTrue(damaged.events.any { it.kind == "unit-damaged" && it.unitId == "h2" })
        } finally { context.close() }
    }

    @Test fun a_helicopter_killed_while_moving_clears_flight_motion_lands_the_static_wreck_and_reopens_cleanly() {
        val input = HelicopterCombatScene.scenario()
        val source = input.unitDefinitions.getValue("helicopter")
        val combat = checkNotNull(source.combat)
        val projectile = combat.projectile.copy(directDamage = 150.0)
        val gun = checkNotNull(combat.namedWeapons).ordered.single().copy(shootDelay = 1.0, projectile = projectile)
        val lethal = UnitDefinition("lethal-helicopter", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
            source.fogOfWarSightRange, combat = combat.copy(shootDelay = 1.0, projectile = projectile,
                namedWeapons = WeaponCatalog(listOf(gun))), build = source.build, rotor = source.rotor)
        val context = HelicopterTestScene.open(input.copy(unitDefinitions = input.unitDefinitions + ("lethal-helicopter" to lethal),
            units = input.units.map { if (it.unitId == "h2") it.copy(typeId = "lethal-helicopter") else it }))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-under-fire", "p1", "h1", Position(450.0, 200.0), context.world)))
            var deathStep = -1L
            var dead: UnitObservation? = null
            for (step in 0L..399L) {
                val before = context.observe().units.single { it.unitId == "h1" }
                val after = context.advance(Step(step, 1.0, "sim-tick")).units.single { it.unitId == "h1" }
                if (!after.alive) {
                    assertTrue(before.speed > 0.0 && before.height >= 4.0, "The controlled lethal gun must kill during real flight")
                    deathStep = step
                    dead = after
                    break
                }
            }
            val wreck = assertNotNull(dead)
            assertEquals(0.0, wreck.health)
            assertEquals(0.0, wreck.speed)
            assertEquals(Position(0.0, 0.0), assertNotNull(wreck.flightPose).velocity)
            assertEquals(0.0, wreck.height)
            assertEquals(0.0, wreck.flightPose!!.height)
            assertNull(wreck.moveTarget)
            assertTrue(wreck.tasks.isEmpty())
            for (step in deathStep + 1..deathStep + 20) {
                val later = context.advance(Step(step, 1.0, "sim-tick")).units.single { it.unitId == "h1" }
                assertEquals(wreck.position, later.position)
                assertEquals(wreck.flightPose, later.flightPose)
            }
            assertIs<CloseResult.Closed>(context.close())
            val reopened = context.reopen(HelicopterCombatScene.scenario())
            assertIs<InitResult.Accepted>(reopened, reopened.toString())
            val fresh = context.observe().units.single { it.unitId == "h1" }
            assertTrue(fresh.alive)
            assertEquals(150.0, fresh.health)
            assertEquals(0.0, fresh.height)
            assertEquals(0.0, fresh.flightPose!!.heightPhase)
            assertEquals(0.0, fresh.flightPose!!.rotorPhase)
            assertEquals(Position(0.0, 0.0), fresh.flightPose!!.velocity)
        } finally { context.close() }
    }

    @Test fun the_flying_boundary_is_inclusive_for_both_orders_and_actual_ground_or_air_only_shots() {
        for ((height, groundAllowed, airAllowed) in listOf(Triple(3.999, true, false), Triple(4.0, false, true), Triple(4.001, false, true))) {
            for ((airOnly, allowed) in listOf(false to groundAllowed, true to airAllowed)) {
                val input = HelicopterCombatScene.groundAgainstAir()
                val original = input.unitDefinitions.getValue("helicopter")
                val helicopter = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                    original.totalFrames, original.imagePath, original.imageScale,
                    original.movement.copy(flight = FlightProfile(height, 0.0, 0.0, height)), original.retainedUnconsumed,
                    original.fogOfWarSightRange, combat = original.combat, build = original.build, rotor = original.rotor)
                val source = input.unitDefinitions.getValue("c_tank")
                val combat = checkNotNull(source.combat)
                val shooter = if (!airOnly) source else UnitDefinition(source.id, source.className, source.radius,
                    source.displayRadius, source.totalFrames, source.imagePath, source.imageScale, source.movement,
                    source.retainedUnconsumed, source.fogOfWarSightRange,
                    combat.copy(namedWeapons = WeaponCatalog(checkNotNull(combat.namedWeapons).ordered.map {
                        it.copy(canAttackLandUnits = false, canAttackFlyingUnits = true, barrelX = 0.0)
                    })))
                val context = HelicopterTestScene.open(input.copy(unitDefinitions = input.unitDefinitions +
                    ("helicopter" to helicopter) + ("c_tank" to shooter)))
                try {
                    assertEquals(height, context.observe().units.single { it.unitId == "h2" }.height)
                    val order = context.submit(AttackAction("boundary-order", "p1", "h1", "h2", context.world))
                    if (allowed) assertIs<ActionResult.Accepted>(order) else
                        assertEquals("invalid-attack-target", assertIs<ActionResult.Rejected>(order).error.code)
                    val shots = (0L..9L).flatMap { context.advance(Step(it, 1.0, "sim-tick")).projectileEvents }
                        .filter { it.kind == "born" && it.projectile.sourceUnitId == "h1" }
                    assertEquals(allowed, shots.isNotEmpty(), "height=$height airOnly=$airOnly")
                } finally { context.close() }
            }
        }
    }

    @Test fun mixed_named_weapons_apply_the_flying_gate_to_each_gun_after_real_takeoff() {
        val input = HelicopterCombatScene.groundAgainstAir()
        val source = input.unitDefinitions.getValue("c_tank")
        val combat = checkNotNull(source.combat)
        val gun = checkNotNull(combat.namedWeapons).ordered.single()
        val guns = WeaponCatalog(listOf(gun.copy(name = "ground", barrelX = 0.0),
            gun.copy(name = "air", barrelX = 0.0, canAttackLandUnits = false, canAttackFlyingUnits = true)))
        val platform = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange,
            combat.copy(namedWeapons = guns, multiTargeting = true))
        val context = HelicopterTestScene.open(input.copy(unitDefinitions = input.unitDefinitions + ("c_tank" to platform)))
        try {
            val low = mutableListOf<String?>()
            val high = mutableListOf<String?>()
            for (step in 0L..179L) {
                val wasFlying = context.observe().units.single { it.unitId == "h2" }.height >= 4.0
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val gunsFired = observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "h1" }
                    .map { it.projectile.sourceWeaponName }
                (if (wasFlying) high else low) += gunsFired
            }
            assertTrue(low.isNotEmpty() && low.all { it == "ground" }, "Only the ground gun may shoot before takeoff: $low")
            assertTrue(high.isNotEmpty() && high.all { it == "air" }, "Only the air gun may shoot after takeoff: $high")
        } finally { context.close() }
    }

    @Test fun ordinary_and_explicit_named_mount_ground_guns_stop_auto_firing_after_the_target_takes_off() {
        for (explicitMount in listOf(false, true)) {
            val input = HelicopterCombatScene.groundAgainstAir()
            val original = input.unitDefinitions.getValue("c_tank")
            val combat = checkNotNull(original.combat)
            val ground = if (!explicitMount) original else UnitDefinition(original.id, original.className,
                original.radius, original.displayRadius, original.totalFrames, original.imagePath, original.imageScale,
                original.movement, original.retainedUnconsumed, original.fogOfWarSightRange,
                combat.copy(namedWeapons = WeaponCatalog(checkNotNull(combat.namedWeapons).ordered.map { it.copy(barrelX = 0.0) })))
            val context = HelicopterTestScene.open(input.copy(unitDefinitions = input.unitDefinitions + ("c_tank" to ground)))
            try {
                var lowShot = false
                var flying = false
                val wrongShots = mutableListOf<Long>()
                for (step in 0L..179L) {
                    val wasFlying = context.observe().units.single { it.unitId == "h2" }.height >= 4.0
                    val observation = context.advance(Step(step, 1.0, "sim-tick"))
                    val born = observation.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "h1" }
                    if (wasFlying) {
                        flying = true
                        if (born) wrongShots += step
                    } else if (born) lowShot = true
                }
                assertTrue(lowShot, "The ground gun must genuinely work before takeoff; explicitMount=$explicitMount")
                assertTrue(flying)
                assertTrue(wrongShots.isEmpty(), "Ground-only shots after takeoff at $wrongShots; explicitMount=$explicitMount")
            } finally { context.close() }
        }
    }

    @Test fun a_ground_weapon_accepts_the_low_helicopter_but_rejects_a_new_order_after_real_takeoff_crosses_four() {
        val context = HelicopterTestScene.open(HelicopterCombatScene.groundAgainstAir())
        try {
            assertEquals(0.0, context.observe().units.single { it.unitId == "h2" }.height)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("low-target", "p1", "h1", "h2", context.world)))
            var crossed = false
            for (step in 0L..59L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                if (observation.units.single { it.unitId == "h2" }.height >= 4.0) {
                    crossed = true
                    break
                }
            }
            assertTrue(crossed, "The real finite helicopter must pass the flying-height gate")
            val denied = assertIs<ActionResult.Rejected>(context.submit(AttackAction("flying-target", "p1", "h1", "h2", context.world)))
            assertEquals("invalid-attack-target", denied.error.code)
        } finally { context.close() }
    }

    @Test fun real_helicopters_take_off_move_into_range_and_fire_from_their_physical_height_for_seventeen_damage() {
        val context = HelicopterTestScene.open(HelicopterCombatScene.scenario())
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("approach", "p1", "h1", Position(420.0, 200.0), context.world)))
            val births = mutableListOf<ProjectileObservation>()
            var damaged: Observation? = null
            for (step in 0L..399L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                births += observation.projectileEvents.filter { it.kind == "born" }.map { it.projectile }
                if (observation.units.any { it.health!! < 150.0 }) {
                    damaged = observation
                    break
                }
            }
            val hit = assertNotNull(damaged, "A real ordinary projectile must reach a flying helicopter")
            assertTrue(births.isNotEmpty())
            assertTrue(births.all { it.height >= 4.0 && it.sourceWeaponName == "gun" })
            assertTrue(hit.units.filter { it.health!! < 150.0 }.all { it.health == 133.0 })
            assertTrue(hit.events.any { it.kind == "unit-damaged" })
            assertTrue(hit.units.all { it.height >= 4.0 && it.flightPose!!.height == it.height })
        } finally { context.close() }
    }
}
