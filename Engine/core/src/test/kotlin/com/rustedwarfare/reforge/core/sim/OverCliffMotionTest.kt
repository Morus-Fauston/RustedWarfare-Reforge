package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class OverCliffMotionTest {
    private fun terrain(largeCliff: Boolean): Scenario {
        val base = HeavyMechTestScene.scenario(heading = 90.0)
        val image = base.unitDefinitions.values.single().imagePath
        val tiles = TileSetDefinition(1, "cliff-ground", 20, 20, image, setOf(0), 1, 1,
            hoverBlockedTileIds = if (largeCliff) setOf(0) else emptySet(), overCliffBlockedTileIds = emptySet())
        return base.copy(map = MapDefinition(40, 30, 20, 20, listOf(tiles), IntArray(1200) { 1 },
            BlockingGrid(40, 30, BooleanArray(1200) { true })))
    }

    @Test fun moving_heavy_crosses_large_cliffs_at_point_427_but_ordinary_cliffs_at_point_61() {
        for ((largeCliff, expectedSpeed) in listOf(true to .427, false to .61)) {
            val context = HeavyMechTestScene.open(terrain(largeCliff))
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "heavy", Position(600.0, 200.0), context.world)))
                for (step in 0L..19L) context.advance(Step(step, 1.0, "sim-tick"))
                val moving = context.observe().units.single()
                assertEquals(expectedSpeed, moving.speed, 1e-10)
                assertTrue(moving.position.x > 205.0)
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "heavy", context.world)))
                for (step in 20L..29L) context.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(0.0, context.observe().units.single().speed)
                assertNull(context.observe().units.single().moveTarget)
            } finally { context.close() }
        }
    }

    @Test fun stopping_retains_the_instance_multiplier_and_resuming_uses_it_before_the_next_custom_update() {
        // A declared high acceleration isolates the retained speed cap at the public simulation seam.
        // No live unit, health, balance or progress is changed by this test.
        val base = terrain(true)
        val source = base.unitDefinitions.values.single()
        val unit = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement.copy(moveAccelerationSpeed = .5),
            source.retainedUnconsumed, mech = source.mech)
        val context = HeavyMechTestScene.open(base.copy(unitDefinitions = mapOf(unit.id to unit)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "heavy", Position(600.0, 200.0), context.world)))
            // The custom multiplier follows the base movement update; it is not pre-applied.
            assertEquals(.61, context.advance(Step(0, 1.0, "sim-tick")).units.single().speed, 1e-10)
            assertEquals(.427, context.advance(Step(1, 1.0, "sim-tick")).units.single().speed, 1e-10)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "heavy", context.world)))
            for (step in 2L..5L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(0.0, context.observe().units.single().speed)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("resume", "p1", "heavy", Position(600.0, 200.0), context.world)))
            assertEquals(.427, context.advance(Step(6, 1.0, "sim-tick")).units.single().speed, 1e-10)
        } finally { context.close() }
    }
}
