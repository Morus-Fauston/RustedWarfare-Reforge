package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusEnergyLifecycleTest {
    @Test fun real_volley_exhaustion_locks_all_launchers_and_conversion_preserves_energy_and_weapon_clocks() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, base.unitDefinitions.getValue("hoverTank").imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(100000.0, null))
        val c = NautilusScene.open(base.copy(unitDefinitions = base.unitDefinitions + ("target" to target), units = listOf(
            UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 180.0), 90.0, -8.0),
            UnitSpawn("target", "p2", "target", Position(650.0, 180.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "target" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", "mother", "target", c.world)))
            var step = 0L
            val launchers = mutableSetOf<String?>()
            while (!c.observe().units.single { it.unitId == "mother" }.energy!!.recharging && step < 800) {
                val o = c.advance(Step(step++, 1.0, "sim-tick"))
                launchers += o.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "mother" }
                    .map { it.projectile.sourceWeaponName }
            }
            assertEquals(setOf<String?>("2", "3", "4", "5", "6", "7"), launchers)
            val before = c.observe()
            val resources = before.units.single { it.unitId == "mother" }
            assertTrue(resources.energy!!.recharging)
            assertTrue(resources.energy.current < 1f)
            val clocks = before.combat!!.weapons.filter { it.unitId == "mother" }.associateBy { it.weaponName }
            assertIs<ActionResult.Accepted>(c.submit(UnitAction("surface", "p1", "mother", "surface", c.world)))
            val converted = c.advance(Step(step++, 1.0, "sim-tick"))
            val parent = converted.units.single { it.unitId == "mother" }
            assertEquals("nautilusSubmarineSurface", parent.typeId)
            assertTrue(parent.energy!!.recharging)
            assertEquals(resources.energy.current + .01f, parent.energy.current)
            assertEquals(resources.health, parent.health)
            for (weapon in converted.combat!!.weapons.filter { it.unitId == "mother" }) {
                assertEquals(maxOf(0.0, clocks.getValue(weapon.weaponName).cooldown - 1.0), weapon.cooldown)
                assertEquals(clocks.getValue(weapon.weaponName).randomCounter, weapon.randomCounter)
            }
            while (c.observe().units.single { it.unitId == "mother" }.energy!!.recharging && step < 1600) {
                val o = c.advance(Step(step++, 1.0, "sim-tick"))
                assertFalse(o.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "mother" })
            }
            assertEquals(EnergyObservation(6f, 6f, false), c.observe().units.single { it.unitId == "mother" }.energy)
            var fired = false
            repeat(120) {
                val o = c.advance(Step(step++, 1.0, "sim-tick"))
                fired = fired || o.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "mother" }
            }
            assertTrue(fired)
        } finally { c.close() }
    }
}
