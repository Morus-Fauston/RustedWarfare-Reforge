package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class HoverMotionTest {
    @Test fun invalid_hover_profiles_fail_initialization_without_publishing_a_world() = withHoverPackage { root ->
        val source = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("hoverTank")
        val base = TestContentFactory.openScenario()
        val profiles = listOf(
            source.movement.copy(type = MovementType.LAND),
            source.movement.copy(hover = HoverProfile(heightDrift = 5.0)),
            source.movement.copy(hover = HoverProfile(phaseSpeed = Double.NaN)),
            source.movement.copy(slidingMode = false),
            source.movement.copy(flight = FlightProfile(20.0, 0.0, .1, 0.0)),
            source.movement.copy(reverseSpeedPercentage = 1.0))
        for (movement in profiles) {
            val unit = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
                source.totalFrames, source.imagePath, source.imageScale, movement, emptySet())
            val rejected = assertIs<InitResult.Rejected>(SimulationContext.initialize(base.copy(
                unitDefinitions = mapOf(source.id to unit), units = listOf(UnitSpawn("h", "p1", source.id, Position(100.0, 100.0), 0.0)))))
            assertEquals("unsupported-hover", rejected.error.code)
        }
    }

    @Test fun native_hover_rises_from_zero_and_moves_without_waiting_for_body_alignment() = withHoverPackage { root ->
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
        val source = pack.units.getValue("hoverTank")
        val unit = UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement, emptySet())
        val base = TestContentFactory.openScenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = mapOf(source.id to unit), units = listOf(UnitSpawn("h", "p1", source.id, Position(100.0, 100.0), 0.0))))).context
        try {
            assertEquals(0.0, context.observePlayer(context.world, "p1").units.single().height)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "h", Position(200.0, 100.0), context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(.1, first.height, 1e-12)
            assertEquals(100.0564, first.position.x, 1e-10)
            assertEquals(100.0, first.position.y, 1e-10)
            assertNull(first.flightPose)
            assertNull(first.waterPose)
            repeat(29) { context.advance(Step(it + 1L, 1.0, "sim-tick")) }
            assertEquals(3.0, context.observePlayer(context.world, "p1").units.single().height, 1e-10)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "h", context.world)))
            assertNull(context.observe().units.single().moveTarget)
            repeat(40) { context.advance(Step(it + 30L, 1.0, "sim-tick")) }
            val stopped = context.observePlayer(context.world, "p1").units.single()
            assertEquals(0.0, stopped.speed)
            assertNotEquals(3.0, stopped.height)
        } finally { context.close() }
    }
}
