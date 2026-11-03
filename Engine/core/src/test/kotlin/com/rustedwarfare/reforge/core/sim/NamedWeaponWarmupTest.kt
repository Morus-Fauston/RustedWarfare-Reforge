package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NamedWeaponWarmupTest {
    @Test
    fun one_ready_weapon_keeps_a_disqualified_siblings_warmup_until_the_unit_becomes_inactive() {
        val context = open(withCloserTarget = true) { weapon -> listOf(
            weapon.copy(name = "near", warmup = 2.0, shootDelay = 10.0),
            weapon.copy(name = "far", warmup = 2.0, shootDelay = 10.0, minimumRange = 100.0)) }
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("first", "p1", "a", "b", context.world)))
            assertEquals(listOf(1.0, 1.0), states(context.advance(Step(0, 1.0, "sim-tick"))).map { it.warmup })
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("closer", "p1", "a", "c", context.world)))
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(null, states(second)[1].targetUnitId)
            assertEquals(listOf(2.0, 1.0), states(second).map { it.warmup })
            assertEquals(listOf(0.0, 1.0), states(context.advance(Step(2, 1.0, "sim-tick"))).map { it.warmup })
            assertEquals(listOf(0.0, 0.0), states(context.advance(Step(3, 1.0, "sim-tick"))).map { it.warmup })
        } finally { context.close() }
    }

    @Test
    fun no_reset_transfer_uses_the_unclamped_crossing_value_and_negative_cooldown_approaches_zero() {
        val context = open { weapon -> listOf(weapon.copy(name = "continuous", warmup = 0.5,
            warmupNoReset = true, shootDelay = 10.0, warmupShootDelayTransfer = 10.0,
            warmupCallDownRate = 0.0)) }
        try {
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, shots(first))
            assertEquals(1.0, states(first).single().warmup, "No extra ratio clamp is allowed before transfer")
            assertEquals(-9.0, states(first).single().cooldown)
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(0, shots(second), "A negative cooldown is nonzero and must not fire again")
            assertEquals(0.5, states(second).single().warmup)
            assertEquals(-8.0, states(second).single().cooldown)
        } finally { context.close() }
    }

    @Test
    fun ordinary_warmup_crossing_waits_one_pass_while_no_reset_fires_and_accumulates_during_cooldown() {
        val context = open { weapon -> listOf(
            weapon.copy(name = "ordinary", warmup = 2.0, shootDelay = 10.0),
            weapon.copy(name = "continuous", warmup = 2.0, shootDelay = 10.0,
                warmupNoReset = true, warmupShootDelayTransfer = 4.0, warmupCallDownRate = 0.0)) }
        try {
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(1, shots(first), "Only noReset may fire on the first eligible pass")
            assertEquals(listOf(1.0, 1.0), states(first).map { it.warmup })
            assertEquals(listOf(0.0, 7.0), states(first).map { it.cooldown })
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(0, shots(second), "Crossing the ordinary threshold must not fire in that pass")
            assertEquals(listOf(2.0, 2.0), states(second).map { it.warmup })
            assertEquals(listOf(0.0, 6.0), states(second).map { it.cooldown })
            val third = context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals(1, shots(third))
            assertEquals(listOf(0.0, 2.0), states(third).map { it.warmup })
            assertEquals(listOf(9.0, 5.0), states(third).map { it.cooldown })
        } finally { context.close() }
    }

    private fun shots(observation: Observation) = observation.projectileEvents.count {
        it.kind == "born" && it.projectile.sourceUnitId == "a" }

    private fun states(observation: Observation) = assertNotNull(observation.combat).weapons.filter { it.unitId == "a" }

    private fun open(withCloserTarget: Boolean = false,
                     weapons: (NamedWeaponDefinition) -> List<NamedWeaponDefinition>): SimulationContext {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val catalog = WeaponCatalog(weapons(assertNotNull(combat.namedWeapons).ordered.single()))
        val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val units = base.units.map { if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it } +
            if (withCloserTarget) listOf(UnitSpawn("c", "p2", "c_tank", Position(170.0, 210.0), 270.0)) else emptyList()
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to tank), units = units,
            combatContract = if (withCloserTarget) assertNotNull(base.combatContract)
                .copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)) else base.combatContract))).context
    }
}
