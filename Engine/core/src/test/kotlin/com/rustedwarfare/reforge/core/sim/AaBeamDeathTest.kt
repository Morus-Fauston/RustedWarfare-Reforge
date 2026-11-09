package com.rustedwarfare.reforge.core.sim

import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AaBeamDeathTest {
    @TempDir lateinit var temporary: Path

    @Test fun real_lethal_air_fire_preserves_height_then_uses_the_custom_slow_fall_branch() = WaterTestScene.withMap { map ->
        val aa = AaBeamScene.definition(temporary)
        for (overWater in listOf(false, true)) for (moving in listOf(false, true)) {
            val x = if (overWater) 480.0 else 100.0
            val spawns = listOf(UnitSpawn("aa", "p1", "aaBeamGunship", Position(x, 150.0), 90.0)) +
                (1..12).map { UnitSpawn("enemy-$it", "p2", "aaBeamGunship", Position(x + 200.0, 150.0), 270.0) }
            val context = AaBeamScene.open(AaBeamScene.scenario(map, aa, spawns))
            try {
                if (moving) assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "aa", Position(x + 150.0, 150.0), context.world)))
                var tick = 0L
                while (context.observe().units.single { it.unitId == "aa" }.alive && tick < 500L)
                    context.advance(Step(tick++, 1.0, "sim-tick"))
                val dead = context.observe().units.single { it.unitId == "aa" }
                assertFalse(dead.alive)
                assertTrue(dead.height > 4.0, "A dead gunship must first fall from its actual altitude")
                val next = context.advance(Step(tick++, 1.0, "sim-tick")).units.single { it.unitId == "aa" }
                val gravity = if (moving) .017f else .06f
                assertEquals((dead.height.toFloat() - gravity).toDouble(), next.height)
                if (moving) assertTrue(next.position.x > dead.position.x) else assertEquals(dead.position, next.position)
                repeat(240) { context.advance(Step(tick++, 1.0, "sim-tick")) }
                val landed = context.observe().units.single { it.unitId == "aa" }
                if (overWater) assertTrue(landed.height <= -10.0) else assertEquals(0.0, landed.height)
            } finally { context.close() }
        }
    }
}
