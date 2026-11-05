package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechGaitObservationTest {
    @Test
    fun authorized_feet_keep_their_world_positions_then_warm_lift_swing_and_land_after_stop() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val declared = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        val mech = UnitDefinition(declared.id, declared.className, declared.radius, declared.displayRadius,
            declared.totalFrames, declared.imagePath, declared.imageScale, declared.movement,
            declared.retainedUnconsumed, declared.fogOfWarSightRange, mech = declared.mech)
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            root.resolve("weapon-relations/mounted/ini/maps/p001-controlled.tmx"))).value
        val scene = Scenario("p008-mech-gait", map, mapOf(mech.id to mech),
            listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("m", "p1", mech.id, Position(210.0, 210.0), 90.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val initial = assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            assertEquals(90.0, initial.forwardHeading)
            assertEquals(listOf(Position(210.0, 202.0), Position(210.0, 218.0)), initial.legs.map { it.foot })
            assertEquals(listOf(Position(210.0, 207.0), Position(210.0, 213.0)), initial.legs.map { it.anchor })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "m", Position(500.0, 210.0), context.world)))
            val poses = (0L..9L).map { step ->
                context.advance(Step(step, 1.0, "sim-tick"))
                assertNotNull(context.observePlayer(context.world, "p1").units.single().mechPose)
            }
            val heights = listOf(.1, .1, .1, .1, .1, .1, .75, 1.4, 2.05, 2.05)
            poses.forEachIndexed { i, pose -> assertEquals(heights[i], pose.legs.first().height, 1e-12, "q=${i + 1}") }
            assertTrue(poses.take(9).all { it.legs.first().foot == Position(210.0, 202.0) })
            assertEquals(212.8, poses.last().legs.first().foot.x, 1e-12)
            assertEquals(202.0, poses.last().legs.first().foot.y, 1e-12)
            assertEquals(.1, initial.legs.first().height, "Earlier authorized snapshots remain stable")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "m", context.world)))
            for (step in 10L..109L) context.advance(Step(step, 1.0, "sim-tick"))
            val last = context.observePlayer(context.world, "p1").units.single()
            assertEquals(0.0, last.speed)
            assertTrue(assertNotNull(last.mechPose).legs.all { it.height == 0.0 && it.phase == MechLegPhase.PLANTED })
        } finally { context.close() }
    }
}
