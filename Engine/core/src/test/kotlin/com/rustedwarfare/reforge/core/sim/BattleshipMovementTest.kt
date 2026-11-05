package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipMovementTest {
    @Test
    fun loaded_radius_forty_water_hull_moves_without_legs_and_retains_map_circle_bounds() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("naval-production/$format/production-package"))).value
            val source = pack.units.getValue("heavyBattleship")
            // Qualified movement-only transform: actual loaded hull/motion, no pretend combat result.
            val hull = UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement, source.retainedUnconsumed, source.fogOfWarSightRange)
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("ship" to hull), units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0)))
            for (position in listOf(Position(200.0, 200.0), Position(761.0, 200.0))) {
                val bad = assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(units = input.units.map { it.copy(position = position) })))
                assertEquals("invalid-spawn", bad.error.code)
            }
            for (height in listOf(.0001, -.0001, 4.0, -8.0, Double.MAX_VALUE, Double.NaN)) {
                val bad = assertIs<InitResult.Rejected>(SimulationContext.initialize(input.copy(
                    units = input.units.map { it.copy(height = height) })))
                assertEquals("invalid-spawn", bad.error.code, "$format height=$height")
            }
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val bad = assertIs<ActionResult.Accepted>(context.submit(MoveAction("outside", "p1", "ship", Position(770.0, 200.0), context.world)))
                assertTrue(bad.events.any { it.kind == "path-unreachable" })
                context.submit(MoveAction("east", "p1", "ship", Position(700.0, 200.0), context.world))
                var arrivals = 0
                repeat(800) { step ->
                    val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    assertNull(sample.units.single().mechPose)
                    assertTrue(sample.units.single().position.x in 400.0..760.0)
                    arrivals += sample.events.count { it.kind == "movement-arrived" }
                }
                assertEquals(1, arrivals)
                assertTrue(context.observe().units.single().position.x > 690.0)
                assertEquals(0.0, context.observe().units.single().speed)
            } finally { context.close() }
        }
    }
}
