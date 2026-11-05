package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

/** Loads the real public package, then isolates motion using the existing movement-only scene seam. */
internal object HelicopterTestScene {
    fun unit(): UnitDefinition {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPack(fixtures.resolve("helicopter-production/json/package"))
        val source = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units.getValue("helicopter")
        return UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement,
            source.retainedUnconsumed, fogOfWarSightRange = 3, rotor = source.rotor)
    }

    fun scenario(unit: UnitDefinition = unit(), heading: Double = 90.0,
                 position: Position = Position(200.0, 200.0), blocked: Set<Pair<Int, Int>> = emptySet()): Scenario = Scenario(
        "helicopter-test", MapDefinition(40, 30, 20, 20, emptyList(), IntArray(1200) { 1 },
            BlockingGrid(40, 30, BooleanArray(1200) { (it % 40 to it / 40) in blocked })),
        mapOf("helicopter" to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
        listOf(UnitSpawn("h1", "p1", "helicopter", position, heading)),
        CoordinateModel("helicopter.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("helicopter.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))

    fun open(scenario: Scenario = scenario()): SimulationContext {
        val result = SimulationContext.initialize(scenario)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}

class HelicopterSimulationTest {
    @Test fun a_nonfinite_derived_height_difference_rolls_back_phase_height_and_observation_together() {
        val original = HelicopterTestScene.unit()
        val extreme = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale,
            original.movement.copy(flight = FlightProfile(0.0, Double.MAX_VALUE, 0.0, Double.MAX_VALUE)),
            original.retainedUnconsumed, original.fogOfWarSightRange, rotor = original.rotor)
        val context = HelicopterTestScene.open(HelicopterTestScene.scenario(unit = extreme))
        try {
            repeat(90) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val before = context.observe()
            // At phase182 the desired height is negative; MAX_VALUE minus that height overflows.
            // The configured rate is zero, so a min(rate, overflow) must not silently hide the failed derivation.
            assertFailsWith<IllegalStateException> { context.advance(Step(90, 1.0, "sim-tick")) }
            assertSame(before, context.observe())
            assertEquals(180.0, assertNotNull(context.observe().units.single().flightPose).heightPhase)
            assertEquals(Double.MAX_VALUE, context.observe().units.single().height)
        } finally { context.close() }
    }

    @Test fun unsupported_explicit_air_spawn_height_is_rejected_instead_of_silently_discarded() {
        val source = HelicopterTestScene.scenario()
        for (height in listOf(5.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = SimulationContext.initialize(source.copy(units = listOf(source.units.single().copy(height = height))))
            if (result is InitResult.Accepted) result.context.close()
            assertEquals("invalid-spawn", assertIs<InitResult.Rejected>(result, "height=$height").error.code)
        }
        val ground = TestContentFactory.openScenario()
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(ground.copy(
            units = ground.units.map { it.copy(height = 5.0) }))).context
        try {
            assertEquals(5.0, context.observe().units.single().height)
            assertEquals(5.0, context.advance(Step(0, 1.0, "sim-tick")).units.single().height)
            assertNull(context.observe().units.single().flightPose)
        } finally { context.close() }
    }

    @Test fun a_helicopter_crosses_a_full_height_ground_wall_on_the_direct_route() {
        val context = HelicopterTestScene.open(HelicopterTestScene.scenario(blocked = (0 until 30).map { 15 to it }.toSet()))
        try {
            val target = Position(600.0, 200.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("cross-wall", "p1", "h1", target, context.world)))
            val ordered = context.observe().units.single()
            assertEquals(target, ordered.moveTarget)
            assertEquals(listOf(target), ordered.pathPoints)
            val flight = (0L..199L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(flight.last().units.single().position.x > 400.0)
            assertTrue(flight.all { kotlin.math.abs(it.units.single().position.y - 200.0) < 1e-10 })
            assertTrue(flight.flatMap { it.events }.none { it.kind in setOf("movement-blocked", "movement-cancelled", "path-unreachable") })
        } finally { context.close() }
    }

    @Test fun movement_starts_before_body_alignment_and_stop_clears_the_order_before_the_inertial_tail() {
        val context = HelicopterTestScene.open(HelicopterTestScene.scenario(heading = 0.0))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "h1", Position(600.0, 200.0), context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            // Qualified independent equal-direction witness: .03 * 1.41, independent of the body's .4 degree turn.
            assertEquals(200.0423, first.position.x, 1e-10)
            assertEquals(200.0, first.position.y, 1e-10)
            assertEquals(.4, first.heading, 1e-10)
            assertEquals(.0423, assertNotNull(first.flightPose).velocity.x, 1e-10)
            for (step in 1L..69L) context.advance(Step(step, 1.0, "sim-tick"))
            val moving = context.observe().units.single()
            assertEquals(1.8, moving.speed, 1e-10)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "h1", context.world)))
            val stopped = context.observe().units.single()
            assertNull(stopped.moveTarget)
            assertEquals(moving.position, stopped.position)
            assertEquals(moving.flightPose, stopped.flightPose)
            val coast = context.advance(Step(70, 1.0, "sim-tick")).units.single()
            assertEquals(1.7154, coast.speed, 1e-10) // 1.8 - .06 * 1.41.
            assertTrue(coast.position.x > moving.position.x)
            assertEquals(MovementState.DECELERATING, coast.movementState)
            val tail = (71L..100L).map { context.advance(Step(it, 1.0, "sim-tick")).units.single() }
            assertTrue(tail.takeLast(5).all { it.speed == 0.0 && it.position == tail.last().position })
            assertEquals(Position(0.0, 0.0), assertNotNull(tail.last().flightPose).velocity)
            assertEquals(MovementState.IDLE, tail.last().movementState)
            assertNotEquals(stopped.height, tail.last().height)
            assertNotEquals(stopped.flightPose!!.rotorPhase, tail.last().flightPose!!.rotorPhase)
        } finally { context.close() }
    }

    @Test fun the_owner_observes_the_complete_rotor_pose_without_mutating_the_initial_snapshot() {
        val context = HelicopterTestScene.open()
        try {
            val initial = assertNotNull(context.observePlayer(context.world, "p1").units.single().flightPose)
            assertEquals(Position(0.0, 0.0), initial.velocity)
            assertEquals(0.0, initial.height)
            assertEquals(0.0, initial.heightPhase)
            assertEquals(0.0, initial.rotorPhase)
            assertEquals(90.0, initial.rotorHeading)
            assertEquals(.1, initial.rotorHeightOffset)
            for (step in 0L..2L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                val observed = context.observePlayer(context.world, "p1").units.single()
                val pose = assertNotNull(observed.flightPose)
                assertEquals(observed.height, pose.height)
                assertEquals((step + 1) * 2.0, pose.heightPhase)
                assertEquals((step + 1) * 30.0, pose.rotorPhase)
                assertEquals(90.0 + (step + 1) * 30.0, pose.rotorHeading)
                assertEquals(0.0, pose.rotorHeightOffset)
                assertEquals(pose, context.observe().units.single().flightPose)
            }
            assertEquals(0.0, initial.height)
            assertEquals(0.0, initial.rotorPhase)
            assertEquals(.1, initial.rotorHeightOffset)
        } finally { context.close() }
    }

    @Test fun an_idle_helicopter_takes_off_to_the_literal_first_three_physical_heights() {
        val context = HelicopterTestScene.open()
        try {
            assertEquals(0.0, context.observe().units.single().height)
            for ((step, height) in listOf(.24, .4752, .705696).withIndex()) {
                val unit = context.advance(Step(step.toLong(), 1.0, "sim-tick")).units.single()
                assertEquals(height, unit.height, 1e-10)
                assertEquals(Position(200.0, 200.0), unit.position)
                assertEquals(MovementState.IDLE, unit.movementState)
            }
        } finally { context.close() }
    }
}
