package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerCombatTest {
    @Test fun the_aa_body_waits_two_ticks_then_hits_air_for_direct_and_area_damage_without_firing_the_nano() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val engineer = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/package"))).value
        val source = HelicopterCombatScene.unit()
        // Radius one removes the random aim offset so this checks direct100 + center area40.
        // This passive fixed-height mechanism target is distinct from normal helicopter evidence.
        val target = UnitDefinition("air-target", source.className, 1.0, 1.0,
            source.totalFrames, source.imagePath, source.imageScale,
            source.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(),
            source.fogOfWarSightRange, durability = DurabilityProfile(1000.0, null), rotor = source.rotor)
        val base = CombatTestContent.scenario()
        val input = base.copy(unitDefinitions = base.unitDefinitions + engineer.units + (target.id to target),
            units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0),
                UnitSpawn("air", "p2", target.id, Position(300.0, 210.0), 270.0),
                UnitSpawn("ground", "p2", "c_tank", Position(210.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "air" to 9L, "ground" to 10L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
        val opened = SimulationContext.initialize(input)
        val c = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            assertIs<ActionResult.Rejected>(c.submit(AttackAction("ground", "p1", "engineer", "ground", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("air", "p1", "engineer", "air", c.world)))
            val births = mutableListOf<Long>()
            var hit = false
            for (step in 0L..89L) {
                val row = c.advance(Step(step, 1.0, "sim-tick"))
                for (event in row.projectileEvents.filter { it.kind == "born" && it.projectile.sourceUnitId == "engineer" }) {
                    births += step
                    assertEquals("body", event.projectile.sourceWeaponName)
                    val sound = row.soundTriggers.single { it.eventId == "reforge:events/naval-missile-fire" }
                    assertEquals(.2f, sound.volume)
                }
                assertEquals(Position(110.0, 210.0), row.units.single { it.unitId == "engineer" }.position)
                assertTrue(row.combat!!.weapons.single { it.unitId == "engineer" && it.weaponName == "nanoTurret" }.targetUnitId == null)
                val health = row.units.single { it.unitId == "air" }.health
                if (health != 1000.0) { assertEquals(860.0, health); hit = true }
            }
            assertEquals(listOf(2L), births)
            assertTrue(hit)
            assertEquals(210.0, c.observe().units.single { it.unitId == "ground" }.health)
        } finally { c.close() }
    }
}
