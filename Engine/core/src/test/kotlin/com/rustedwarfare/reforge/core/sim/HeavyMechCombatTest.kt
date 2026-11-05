package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HeavyMechCombatTest {
    @Test fun an_ordinary_missile_applies_direct_and_full_area_damage_to_the_same_air_target() {
        val heavy = HeavyMechTestScene.declared()
        val source = HelicopterCombatScene.unit()
        // Isolate the ordinary splash rule with a durable, high AIR target. Production uses the original heli separately.
        val target = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale,
            source.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), source.retainedUnconsumed,
            source.fogOfWarSightRange, durability = DurabilityProfile(1000.0, null), rotor = source.rotor)
        val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
            unitDefinitions = mapOf(heavy.id to heavy, "target" to target),
            units = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("air", "p2", "target", Position(450.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "air" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "air", context.world)))
            var damaged: Observation? = null
            for (step in 0L..159L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                if (assertNotNull(observation.units.single { it.unitId == "air" }.health) < 1000.0) {
                    damaged = observation
                    break
                }
            }
            val hit = assertNotNull(damaged)
            assertEquals(1, hit.projectileEvents.count { it.kind == "impact" })
            assertEquals(760.0, hit.units.single { it.unitId == "air" }.health,
                "The same target receives direct200 and area40 at the impact center")
        } finally { context.close() }
    }

    @Test fun air_weapons_keep_global_range_and_their_distinct_warmups_and_accelerating_missiles() {
        val heavy = HeavyMechTestScene.declared()
        val source = HelicopterCombatScene.unit()
        // Explicit fixed-height mechanism target, derived from the loaded AIR body; not a normal takeoff scenario.
        val hovering = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale,
            source.movement.copy(flight = FlightProfile(5.0, 0.0, 0.0, 5.0)), source.retainedUnconsumed,
            source.fogOfWarSightRange, durability = DurabilityProfile(150.0, null), rotor = source.rotor)
        val context = HeavyMechTestScene.open(HeavyMechTestScene.scenario(unit = heavy, heading = 90.0).copy(
            unitDefinitions = mapOf(heavy.id to heavy, "hovering" to hovering),
            units = listOf(UnitSpawn("heavy", "p1", heavy.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("air", "p2", "hovering", Position(450.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "air" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("air-attack", "p1", "heavy", "air", context.world)))
            val born = linkedMapOf<String, Long>()
            var tracked: Long? = null
            var previous: Position? = null
            val distances = mutableListOf<Double>()
            for (step in 0L..24L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(Position(200.0, 200.0), observation.units.single { it.unitId == "heavy" }.position,
                    "The 170 ground range must not replace the 310 AIR range")
                for (event in observation.projectileEvents.filter { it.kind == "born" }) {
                    val name = assertNotNull(event.projectile.sourceWeaponName)
                    assertTrue(name in setOf("2", "3"))
                    born.putIfAbsent(name, step)
                    if (name == "2" && tracked == null) {
                        tracked = event.projectile.objectId
                        previous = event.projectile.position
                    }
                }
                val projectile = observation.projectiles.firstOrNull { it.objectId == tracked }
                if (projectile != null && distances.size < 4) {
                    val before = assertNotNull(previous)
                    distances += kotlin.math.hypot(projectile.position.x - before.x, projectile.position.y - before.y)
                    previous = projectile.position
                }
            }
            assertEquals(mapOf("2" to 2L, "3" to 20L), born)
            assertEquals(4, distances.size)
            for ((actual, expected) in distances.zip(listOf(.6, .7, .8, .9))) assertEquals(expected, actual, 1e-10)
        } finally { context.close() }
    }

    @Test fun attacking_a_ground_target_inside_global_range_still_approaches_the_small_laser_range() {
        val source = HeavyMechTestScene.declared()
        val combat = assertNotNull(source.combat)
        // Controlled passive target preserves the loaded health and geometry; no original-unit claim.
        val passive = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed,
            source.fogOfWarSightRange, combat = combat.copy(namedWeapons = WeaponCatalog(
                assertNotNull(combat.namedWeapons).ordered.map { it.copy(canShoot = false) })), mech = source.mech)
        val scene = HeavyMechTestScene.scenario(unit = source, heading = 90.0).copy(
            unitDefinitions = mapOf(source.id to source, "passive" to passive),
            units = listOf(UnitSpawn("heavy", "p1", source.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("target", "p2", "passive", Position(400.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "target" to 9L), 10L))
        val context = HeavyMechTestScene.open(scene)
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "heavy", "target", context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(200.0987, first.units.single { it.unitId == "heavy" }.position.x, 1e-10,
                "The global 310 range cannot stop ground pursuit outside the small laser's 170")
            assertTrue(first.projectileEvents.none { it.kind == "born" })
            var fired = false
            var damaged = false
            for (step in 1L..199L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                for (event in observation.projectileEvents.filter { it.kind == "born" }) {
                    assertEquals("smallLaser", event.projectile.sourceWeaponName)
                    assertEquals("heavy", event.projectile.sourceUnitId)
                    fired = true
                }
                damaged = damaged || assertNotNull(observation.units.single { it.unitId == "target" }.health) < 2500.0
            }
            assertTrue(fired)
            assertTrue(damaged)
            assertTrue(context.observe().units.single { it.unitId == "heavy" }.position.x >= 230.0)
        } finally { context.close() }
    }
}
