package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NamedWeaponMountTest {
    @Test
    fun an_independent_child_rechecks_its_parent_relative_arc_after_the_parent_turns_in_the_same_pass() {
        val input = scenario { weapon -> listOf(
            weapon.copy(name = "child", attachedTo = "base", limitingAngle = 0.0, maximumRange = 100.0,
                turnSpeed = 0.0, turnAcceleration = 0.0),
            weapon.copy(name = "base", minimumRange = 100.0, canShoot = false,
                turnSpeed = 90.0, turnAcceleration = 0.0)) }
        val original = input.unitDefinitions.getValue("c_tank")
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, assertNotNull(original.combat).copy(multiTargeting = true))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = input.units.map {
                if (it.unitId == "b") it.copy(position = Position(110.0, 100.0)) else it
            } + UnitSpawn("c", "p2", "c_tank", Position(170.0, 210.0), 270.0),
            combatContract = assertNotNull(input.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L))))).context
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associateBy { it.weaponName }
            assertEquals(0.0, states.getValue("base").turretHeading)
            assertEquals(90.0, states.getValue("child").turretHeading)
            assertEquals(0, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" },
                "The completed parent turn moved the independent child's local arc away from its previously selected target")
        } finally { context.close() }
    }

    @Test
    fun a_slave_uses_its_parents_ready_gate_even_when_its_own_aim_tolerance_is_zero() {
        for (parentTolerance in listOf(0.0, 5.0)) {
            val input = scenario { weapon -> listOf(
                weapon.copy(name = "child", attachedTo = "base", slave = true, aimMaxAngle = 0.0),
                weapon.copy(name = "base", canShoot = false, aimMaxAngle = parentTolerance)) }
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(units = input.units.map {
                if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
            }))).context
            try {
                val observation = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (parentTolerance == 0.0) 0 else 1,
                    observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            } finally { context.close() }
        }
    }

    @Test
    fun a_slave_with_zero_turn_speed_follows_a_later_declared_parents_completed_turn_before_firing() {
        val input = scenario { weapon -> listOf(
            weapon.copy(name = "child", attachedTo = "base", slave = true, turnSpeed = 0.0,
                turnAcceleration = 0.0, aimMaxAngle = 0.0, mount = TurretMount(0.0, 10.0)),
            weapon.copy(name = "base", canShoot = false, turnSpeed = 90.0, turnAcceleration = 0.0)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(units = input.units.map {
            if (it.unitId == "b") it.copy(position = Position(110.0, 100.0)) else it
        }))).context
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }
            assertEquals(listOf(0.0, 0.0), states.map { it.turretHeading })
            val shot = observation.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "a" }.projectile
            assertEquals("b", shot.targetUnitId)
            assertEquals(Position(110.0, 180.0), shot.position)
        } finally { context.close() }
    }

    @Test
    fun slave_copies_its_parents_target_heading_and_readiness_but_attachment_alone_keeps_independent_aim() {
        for (slave in listOf(false, true)) {
            val input = scenario { weapon -> listOf(
                weapon.copy(name = "child", attachedTo = "base", slave = slave,
                    mount = TurretMount(0.0, 10.0), turnSpeed = 90.0, turnAcceleration = 0.0),
                weapon.copy(name = "base", minimumRange = 100.0,
                    turnSpeed = 90.0, turnAcceleration = 0.0, canShoot = false)) }
            val original = input.unitDefinitions.getValue("c_tank")
            val combat = assertNotNull(original.combat)
            val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement,
                original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(multiTargeting = true))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(
                unitDefinitions = mapOf("c_tank" to tank), units = input.units.map {
                    if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
                } + UnitSpawn("c", "p2", "c_tank", Position(120.0, 150.0), 270.0),
                combatContract = assertNotNull(input.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L))))).context
            try {
                val observation = context.advance(Step(0, 1.0, "sim-tick"))
                val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associateBy { it.weaponName }
                assertEquals("b", states.getValue("base").targetUnitId)
                assertEquals(if (slave) "b" else "c", states.getValue("child").targetUnitId, "slave=$slave")
                assertEquals(if (slave) 90.0 else 0.0, states.getValue("child").turretHeading)
                val shots = observation.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                assertEquals(1, shots.size)
                assertEquals(if (slave) "b" else "c", shots.single().projectile.targetUnitId)
                assertEquals(if (slave) Position(140.0, 210.0) else Position(120.0, 190.0), shots.single().projectile.position)
            } finally { context.close() }
        }
    }

    @Test
    fun a_real_child_shot_starts_recoil_on_a_non_shooting_parent_without_firing_the_parent() {
        val input = scenario { weapon -> listOf(
            weapon.copy(name = "base", canShoot = false),
            weapon.copy(name = "child", attachedTo = "base", slave = true, mount = TurretMount(0.0, 10.0))) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.copy(units = input.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        }))).context
        try {
            val observation = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, observation.projectileEvents.count { it.kind == "born" && it.projectile.sourceUnitId == "a" })
            val states = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }.associateBy { it.weaponName }
            assertEquals(74.0, states.getValue("base").cooldown)
            assertEquals(Position(109.5, 210.0), states.getValue("base").pivot)
            assertEquals(Position(119.0, 210.0), states.getValue("child").pivot)
        } finally { context.close() }
    }

    @Test
    fun a_forward_named_parent_supplies_the_child_base_and_its_rotating_local_coordinate_frame() {
        val input = scenario { weapon -> listOf(
            weapon.copy(name = "child", attachedTo = "base", mount = TurretMount(0.0, 20.0),
                turnSpeed = 0.0, turnAcceleration = 0.0, canShoot = false),
            weapon.copy(name = "base", mount = TurretMount(0.0, 10.0),
                turnSpeed = 90.0, turnAcceleration = 0.0, canShoot = false)) }
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            fun states(observation: Observation) = assertNotNull(observation.combat).weapons
                .filter { it.unitId == "a" }.associateBy { it.weaponName }
            val initial = states(context.observe())
            assertEquals(Position(120.0, 210.0), initial.getValue("base").pivot)
            assertEquals(Position(140.0, 210.0), initial.getValue("child").pivot)
            val rotated = states(context.advance(Step(0, 1.0, "sim-tick")))
            assertEquals(0.0, rotated.getValue("base").turretHeading)
            assertEquals(90.0, rotated.getValue("child").turretHeading, "Attached is not implicitly slave")
            assertEquals(Position(120.0, 190.0), rotated.getValue("child").pivot)
            assertEquals(Position(140.0, 190.0), rotated.getValue("child").muzzle)
        } finally { context.close() }
    }

    @Test
    fun direct_host_input_cannot_bypass_self_missing_or_nested_attachment_validation() {
        for (kind in listOf("self", "missing", "nested")) {
            val input = scenario { weapon ->
                val base = weapon.copy(name = "base")
                val child = weapon.copy(name = "child", attachedTo = when (kind) {
                    "self" -> "child"
                    "missing" -> "absent"
                    else -> "base"
                })
                listOf(base, child) + if (kind == "nested") listOf(weapon.copy(name = "nested", attachedTo = "child")) else emptyList()
            }
            val result = SimulationContext.initialize(input)
            try { assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result, kind).error.code) }
            finally { if (result is InitResult.Accepted) result.context.close() }
        }
    }

    private fun scenario(weapons: (NamedWeaponDefinition) -> List<NamedWeaponDefinition>): Scenario {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val catalog = WeaponCatalog(weapons(assertNotNull(combat.namedWeapons).ordered.single()))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        return base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(120.0, 110.0)) else it
        })
    }
}
