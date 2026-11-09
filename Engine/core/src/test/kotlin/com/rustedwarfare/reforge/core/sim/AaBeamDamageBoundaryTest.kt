package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamDamageBoundaryTest {
    @TempDir lateinit var temporary: Path

    private fun target(id: String, health: Double, shield: Float? = null): UnitDefinition {
        val heli = HelicopterCombatScene.unit()
        return UnitDefinition(id, "OwnedAirTarget", 1.0, 1.0, 1, heli.imagePath, 1.0,
            heli.movement.copy(flight = FlightProfile(20.0, 0.0, 0.0, 20.0)), emptySet(),
            durability = DurabilityProfile(health, null), rotor = heli.rotor,
            shield = shield?.let { ShieldProfile(it, 0f) })
    }

    @Test fun one_paid_22_damage_hit_consumes_16_point_5_shield_with_the_exact_spill_boundary() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        for ((shield, expectedHp, expectedShield) in listOf(Triple(1000f,1000.0,983.5f),
            Triple(16.5f,1000.0,0f), Triple(16f,994.0,0f))) {
            val body = target("target", 1000.0, shield)
            val base = AaBeamScene.scenario(map, aa, listOf(
                UnitSpawn("aa", "p1", aa.id, Position(150.0, 150.0), 90.0),
                UnitSpawn("target", "p2", body.id, Position(350.0, 150.0), 270.0)))
            val context = AaBeamScene.open(base.copy(unitDefinitions = base.unitDefinitions + (body.id to body)))
            try {
                var tick = 0L
                var frame = context.observe()
                while (frame.projectileEvents.none { it.kind == "impact" } && tick < 200L)
                    frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                val damaged = frame.units.single { it.unitId == "target" }
                assertEquals(expectedHp, damaged.health)
                assertEquals(expectedShield, damaged.shield!!.current)
                assertEquals(5.51f, frame.units.single { it.unitId == "aa" }.energy!!.current)
            } finally { context.close() }
        }
    }

    @Test fun target_death_does_not_grant_free_damage_and_a_new_paid_target_reuses_the_live_beam() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        for (hasSecond in listOf(false, true)) {
            val first = target("fragile", 22.0)
            val second = target("durable", 1000.0)
            val spawns = listOf(UnitSpawn("aa", "p1", aa.id, Position(150.0,150.0),90.0),
                UnitSpawn("first", "p2", first.id, Position(350.0,150.0),270.0)) +
                if (hasSecond) listOf(UnitSpawn("second","p2",second.id,Position(370.0,150.0),270.0)) else emptyList()
            val base = AaBeamScene.scenario(map, aa, spawns)
            val context = AaBeamScene.open(base.copy(unitDefinitions = base.unitDefinitions + mapOf(first.id to first, second.id to second)))
            try {
                var tick = 0L
                var frame = context.observe()
                while (frame.projectileEvents.none { it.kind == "born" } && tick < 200L)
                    frame = context.advance(Step(tick++,1.0,"sim-tick"))
                val shot = frame.projectiles.single()
                assertFalse(frame.units.single { it.unitId == "first" }.alive)
                repeat(3) {
                    frame = context.advance(Step(tick++,1.0,"sim-tick"))
                    assertTrue(frame.projectileEvents.none { it.kind == "impact" })
                    if (hasSecond) assertEquals(1000.0,frame.units.single { it.unitId == "second" }.health)
                }
                if (hasSecond) {
                    while (frame.projectileEvents.none { it.kind == "rearmed" } && tick < 240L)
                        frame = context.advance(Step(tick++,1.0,"sim-tick"))
                    val reused = frame.projectiles.single()
                    assertEquals(shot.objectId,reused.objectId)
                    assertEquals("second",reused.targetUnitId)
                    assertEquals(978.0,frame.units.single { it.unitId == "second" }.health)
                    assertTrue(frame.units.single { it.unitId == "aa" }.energy!!.current < 5.51f)
                } else {
                    repeat(15) {
                        frame = context.advance(Step(tick++,1.0,"sim-tick"))
                        assertTrue(frame.projectileEvents.none { it.kind == "impact" || it.kind == "rearmed" })
                    }
                    assertTrue(frame.projectiles.isEmpty())
                }
            } finally { context.close() }
        }
    }
}
