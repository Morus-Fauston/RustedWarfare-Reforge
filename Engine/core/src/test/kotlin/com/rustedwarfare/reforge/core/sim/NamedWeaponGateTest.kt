package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NamedWeaponGateTest {
    @Test
    fun idle_direction_sets_each_initial_heading_and_the_local_arc_origin() {
        val context = open(heading = 60.0) { weapon -> listOf(
            weapon.copy(name = "aligned", idleDirection = 30.0, limitingAngle = 0.0, turnSpeed = 0.0),
            weapon.copy(name = "outside", idleDirection = 31.0, limitingAngle = 0.0, turnSpeed = 0.0)) }
        try {
            assertEquals(listOf(90.0, 91.0), assertNotNull(context.observe().combat).weapons
                .filter { it.unitId == "a" }.map { it.turretHeading })
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            assertEquals(listOf("b", null), assertNotNull(observation.combat).weapons
                .filter { it.unitId == "a" }.map { it.targetUnitId })
        } finally { context.close() }
    }

    @Test
    fun limiting_angle_accepts_the_endpoint_and_rejects_outside_independently_of_aim_tolerance() {
        val context = open(heading = 60.0) { weapon -> listOf(
            weapon.copy(name = "endpoint", limitingAngle = 30.0, turnSpeed = 90.0),
            weapon.copy(name = "narrow", limitingAngle = 29.99, turnSpeed = 90.0)) }
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf("b", null), states.map { it.targetUnitId })
            assertEquals(listOf(90.0, 60.0), states.map { it.turretHeading })
        } finally { context.close() }
    }

    @Test
    fun a_non_shooting_weapon_still_tracks_its_target_without_creating_a_projectile_or_cooldown() {
        val context = open { weapon -> listOf(weapon.copy(name = "base", canShoot = false),
            weapon.copy(name = "gun")) }
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf("b", "b"), states.map { it.targetUnitId })
            assertEquals(listOf(86.0, 86.0), states.map { it.turretHeading })
            assertEquals(0.0, states.first().cooldown)
        } finally { context.close() }
    }

    @Test
    fun each_aim_tolerance_is_strict_and_does_not_use_the_old_global_five_degree_gate() {
        val context = open { weapon -> listOf(weapon.copy(name = "boundary", aimMaxAngle = 4.0),
            weapon.copy(name = "allowed", aimMaxAngle = 4.01)) }
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf(86.0, 86.0), states.map { it.turretHeading })
            assertEquals(0.0, states.first().cooldown)
        } finally { context.close() }
    }

    private fun open(heading: Double = 85.0,
                     weapons: (NamedWeaponDefinition) -> List<NamedWeaponDefinition>): SimulationContext {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val catalog = WeaponCatalog(weapons(assertNotNull(combat.namedWeapons).ordered.single()
            .copy(turnSpeed = 1.0, turnAcceleration = 0.0)))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "a") it.copy(heading = heading) else it.copy(position = Position(220.0, 210.0))
            }))).context
    }
}
