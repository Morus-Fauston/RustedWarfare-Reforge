package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCatalog
import kotlin.test.*

class WeaponAssemblyRuntimeTest {
    @Test fun units_sharing_two_inherited_slots_keep_their_clocks_random_state_and_parity_independent() {
        val base = CombatTestContent.scenario()
        val original = base.unitDefinitions.getValue("c_tank")
        val combat = assertNotNull(original.combat)
        val gun = assertNotNull(combat.namedWeapons).ordered.single()
        val catalog = WeaponCatalog(listOf(
            gun.copy(name = "left", mount = TurretMount(), shootDelay = 2.0, turnSpeed = 0.0, turnAcceleration = 0.0),
            gun.copy(name = "right", mount = TurretMount(), shootDelay = 5.0, turnSpeed = 0.0, turnAcceleration = 0.0)))
        val definition = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement,
            original.retainedUnconsumed, original.fogOfWarSightRange, combat.copy(namedWeapons = catalog))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf("c_tank" to definition),
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("a2", "p1", "c_tank", Position(110.0, 210.0), 270.0),
                UnitSpawn("b", "p2", "c_tank", Position(220.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "a2" to 10L), 11L)))).context
        try {
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val births = first.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "a" }
            assertEquals(listOf("left", "right"), births.map { it.projectile.sourceWeaponName })
            fun states(sample: Observation, id: String) = assertNotNull(sample.combat).weapons.filter { it.unitId == id }
            assertEquals(listOf(1.0, 4.0), states(first, "a").map { it.cooldown })
            assertTrue(states(first, "a").all { it.randomCounter > 0 && it.oddShot })
            assertTrue(states(first, "a2").all { it.cooldown == 0.0 && it.randomCounter == 0 && !it.oddShot })
            val second = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(listOf(0.0, 3.0), states(second, "a").map { it.cooldown })
            assertTrue(states(second, "a2").all { it.cooldown == 0.0 && it.randomCounter == 0 && !it.oddShot })
        } finally { context.close() }
    }
}
