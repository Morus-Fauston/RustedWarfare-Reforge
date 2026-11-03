package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NamedWeaponLinkTest {
    @Test
    fun direct_host_definitions_cannot_bypass_missing_self_or_cyclic_link_validation() {
        for (target in listOf("absent", "follower", "leader")) {
            val input = CombatTestContent.scenario()
            val original = input.unitDefinitions.getValue("c_tank")
            val combat = assertNotNull(original.combat)
            val weapon = assertNotNull(combat.namedWeapons).ordered.single()
            val catalog = WeaponCatalog(listOf(
                weapon.copy(name = "leader", linkDelayWithTurret = if (target == "leader") "follower" else null),
                weapon.copy(name = "follower", linkDelayWithTurret = target)))
            val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement,
                original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
            val result = SimulationContext.initialize(input.copy(unitDefinitions = mapOf("c_tank" to tank)))
            try { assertEquals("unsupported-combat", assertIs<InitResult.Rejected>(result, target).error.code) }
            finally { if (result is InitResult.Accepted) result.context.close() }
        }
    }

    @Test
    fun a_real_leader_shot_clears_only_strictly_below_the_literal_threshold_including_the_linked_warmup() {
        for ((delay, warmup, thirdShots, finalClock) in listOf(
            Case(9000.0, 0.0, 2, 8999.0), Case(9001.0, 0.0, 1, 8998.0),
            Case(9001.0, 1.0, 2, 9000.0), Case(9002.0, 1.0, 1, 8999.0))) {
            val context = open(delay, warmup = warmup)
            try {
                assertEquals(2, shots(context.advance(Step(0, 1.0, "sim-tick"))))
                assertEquals(0, shots(context.advance(Step(1, 1.0, "sim-tick"))))
                val third = context.advance(Step(2, 1.0, "sim-tick"))
                assertEquals(thirdShots, shots(third), "delay=$delay warmup=$warmup")
                assertEquals(finalClock, follower(third).cooldown)
            } finally { context.close() }
        }
    }

    @Test
    fun a_linked_weapon_declared_before_the_leader_waits_until_its_next_own_pass_after_clearing() {
        val context = open(9000.0, reverse = true)
        try {
            assertEquals(2, shots(context.advance(Step(0, 1.0, "sim-tick"))))
            context.advance(Step(1, 1.0, "sim-tick"))
            val third = context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals(1, shots(third), "Clearing must not recursively fire an earlier weapon")
            assertEquals(0.0, follower(third).cooldown)
            val fourth = context.advance(Step(3, 1.0, "sim-tick"))
            assertEquals(1, shots(fourth))
            assertEquals(8999.0, follower(fourth).cooldown)
        } finally { context.close() }
    }

    @Test
    fun a_link_never_fires_a_weapon_without_its_own_target_or_shoot_permission() {
        for (noTarget in listOf(false, true)) {
            val context = open(9000.0, canShoot = noTarget, maximumRange = if (noTarget) 50.0 else null)
            try {
                for (step in 0L..2L) {
                    val observation = context.advance(Step(step, 1.0, "sim-tick"))
                    assertEquals(if (step == 1L) 0 else 1, shots(observation))
                    assertEquals(0.0, follower(observation).cooldown)
                    if (noTarget) assertEquals(null, follower(observation).targetUnitId)
                }
            } finally { context.close() }
        }
    }

    private data class Case(val delay: Double, val warmup: Double, val shots: Int, val clock: Double)
    private fun shots(observation: Observation) = observation.projectileEvents.count {
        it.kind == "born" && it.projectile.sourceUnitId == "a" }
    private fun follower(observation: Observation) = assertNotNull(observation.combat).weapons
        .single { it.unitId == "a" && it.weaponName == "follower" }

    private fun open(delay: Double, reverse: Boolean = false, warmup: Double = 0.0,
                     canShoot: Boolean = true, maximumRange: Double? = null): SimulationContext {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val weapon = assertNotNull(combat.namedWeapons).ordered.single()
        val list = listOf(weapon.copy(name = "leader", shootDelay = 2.0),
            weapon.copy(name = "follower", shootDelay = delay, linkDelayWithTurret = "leader",
                warmup = warmup, warmupNoReset = warmup > 0.0, warmupCallDownRate = 0.0,
                canShoot = canShoot, maximumRange = maximumRange))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange,
            combat.copy(namedWeapons = WeaponCatalog(if (reverse) list.reversed() else list)))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
            }))).context
    }
}
