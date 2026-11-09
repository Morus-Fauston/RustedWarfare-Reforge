package com.rustedwarfare.reforge.core.sim

import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamProjectileTest {
    @TempDir lateinit var temporary: Path

    @Test fun one_instant_beam_remains_visible_without_free_damage_and_is_rearmed_by_the_next_paid_shot() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0))))
        try {
            var tick = 0L
            var frame = context.observe()
            while (frame.projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == "aa" } && tick < 200L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            val first = frame.projectiles.single { it.sourceUnitId == "aa" }
            assertEquals("AA_BEAM", first.appearance.name)
            val endpoint = assertNotNull(first.beam)
            assertEquals(frame.units.single { it.unitId == "enemy" }.height, endpoint.endHeight)
            assertTrue(kotlin.math.abs(endpoint.end.x - 350.0) <= 8.8)
            assertTrue(kotlin.math.abs(endpoint.end.y - 150.0) <= 8.8)
            val player = context.observePlayer(context.world, "p1")
            assertNotNull(player.projectiles.single { it.appearance.name == "AA_BEAM" && it.position == first.position }.beam)
            assertEquals(2478.0, frame.units.single { it.unitId == "enemy" }.health)
            assertEquals(14.0, first.remainingLife)
            assertEquals(5.51f, frame.units.single { it.unitId == "aa" }.energy!!.current)
            repeat(3) {
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                assertEquals(2478.0, frame.units.single { it.unitId == "enemy" }.health)
                assertEquals(first.objectId, frame.projectiles.single { it.sourceUnitId == "aa" }.objectId)
                assertTrue(frame.projectileEvents.none { it.kind == "impact" && it.projectile.sourceUnitId == "aa" })
            }
            while (frame.projectileEvents.none { it.kind == "rearmed" && it.projectile.sourceUnitId == "aa" } && tick < 220L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            val next = frame.projectiles.single { it.sourceUnitId == "aa" }
            assertEquals(first.objectId, next.objectId)
            assertEquals(2456.0, frame.units.single { it.unitId == "enemy" }.health)
            assertEquals(14.0, next.remainingLife)
            assertTrue(next.age > first.age, "Reuse must not reset the whole object")
        } finally { context.close() }
    }

    @Test fun the_radius_sweep_moves_the_display_endpoint_and_is_used_by_the_next_turret_aim() = WaterTestScene.withMap { map ->
        val context = AaBeamScene.open(AaBeamScene.scenario(map, AaBeamScene.definition(temporary), listOf(
            UnitSpawn("aa", "p1", "aaBeamGunship", Position(150.0, 150.0), 90.0),
            UnitSpawn("enemy", "p2", "aaBeamGunship", Position(350.0, 150.0), 270.0))))
        try {
            var tick = 0L
            var frame = context.observe()
            while (frame.projectiles.none { it.sourceUnitId == "aa" } && tick < 200L)
                frame = context.advance(Step(tick++, 1.0, "sim-tick"))
            val first = frame.projectiles.single { it.sourceUnitId == "aa" }
            assertNotEquals(Position(0.0, 0.0), first.offset, "The qualified radius sweep must reach the display")
            val next = context.advance(Step(tick, 1.0, "sim-tick"))
            val shot = next.projectiles.single { it.sourceUnitId == "aa" }
            assertNotEquals(first.beam!!.end, shot.beam!!.end)
            assertEquals(first.offset, next.combat!!.weapons.single { it.unitId == "aa" }.offset,
                "alsoChangeTurretAim consumes the preceding cached beam offset")
            assertEquals(2478.0, next.units.single { it.unitId == "enemy" }.health)
        } finally { context.close() }
    }
}
