package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusTorpedoTest {
    @Test fun surfaced_ballistic_missiles_really_hit_shields_and_apply_area_damage_to_a_nearby_ground_unit() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val target = UnitDefinition("target", "OwnedTarget", 6.0, 6.0, 1, base.unitDefinitions.getValue("hoverTank").imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            durability = DurabilityProfile(1000.0, null), shield = ShieldProfile(500f, 0f))
        val neighbour = UnitDefinition("neighbour", "OwnedTarget", 6.0, 6.0, 1, target.imagePath, 1.0, target.movement,
            emptySet(), durability = DurabilityProfile(1000.0, null))
        val c = NautilusScene.open(base.copy(unitDefinitions = base.unitDefinitions + mapOf("target" to target, "neighbour" to neighbour),
            units = listOf(UnitSpawn("mother", "p1", "nautilusSubmarineSurface", Position(500.0, 180.0), 90.0, .1),
                UnitSpawn("target", "p2", "target", Position(650.0, 180.0), 270.0),
                UnitSpawn("neighbour", "p2", "neighbour", Position(660.0, 180.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "target" to 9L, "neighbour" to 10L), 11L)))
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", "mother", "target", c.world)))
            var step = 0L
            var hit = false
            while (!hit && step < 240) {
                val observation = c.advance(Step(step++, 1.0, "sim-tick"))
                hit = observation.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "mother" }
            }
            assertTrue(hit)
            val units = c.observe().units.associateBy { it.unitId }
            assertEquals(1000.0, units.getValue("target").health)
            assertTrue(units.getValue("target").shield!!.current < 500f)
            assertTrue(units.getValue("neighbour").health!! < 1000.0)
            assertTrue(units.getValue("mother").energy!!.current < 6f)
        } finally { c.close() }
    }

    @Test fun submerged_energy_launchers_emit_real_ballistic_torpedoes_and_damage_a_water_contact_target() = WaterTestScene.withMap { map ->
        val spawns = listOf(UnitSpawn("mother", "p1", "nautilusSubmarine", Position(500.0, 180.0), 90.0, -8.0),
            UnitSpawn("target", "p2", "hoverTank", Position(650.0, 180.0), 270.0))
        val c = NautilusScene.open(NautilusScene.scenario(map).copy(units = spawns,
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "target" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", "mother", "target", c.world)))
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            val shot = first.projectiles.single { it.sourceUnitId == "mother" }
            assertEquals("2", shot.sourceWeaponName)
            val born = first.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "mother" }.projectile
            assertEquals(-8.0, born.height)
            assertEquals(-7.849999904632568, shot.height) // First old-speed vertical step: binary32 -8 + .15.
            assertEquals(.25f, shot.ballistic!!.speed)
            assertNotNull(shot.ballistic)
            assertEquals(5.01f, first.units.single { it.unitId == "mother" }.energy!!.current)
            var hit = false
            for (step in 1L..200L) {
                val observation = c.advance(Step(step, 1.0, "sim-tick"))
                if (observation.projectileEvents.any { it.kind == "impact" && it.projectile.sourceUnitId == "mother" }) hit = true
            }
            assertTrue(hit, "A born projectile alone does not prove impact")
            assertTrue(c.observe().units.single { it.unitId == "target" }.health!! < 150.0)
        } finally { c.close() }
    }
}
