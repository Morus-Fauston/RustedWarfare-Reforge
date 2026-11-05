package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MechReverseMovementTest {
    @Test
    fun loaded_mech_reverses_toward_a_target_behind_it_and_stop_keeps_the_existing_inertia_contract() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val declared = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        // The qualified movement-only transform isolates propulsion, not a substitute simulation.
        val mech = UnitDefinition(declared.id, declared.className, declared.radius, declared.displayRadius,
            declared.totalFrames, declared.imagePath, declared.imageScale, declared.movement,
            declared.retainedUnconsumed, declared.fogOfWarSightRange, mech = declared.mech)
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            root.resolve("weapon-relations/mounted/ini/maps/p001-controlled.tmx"))).value
        val scene = Scenario("p008-mech-reverse", map, mapOf(mech.id to mech),
            listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("m", "p1", mech.id, Position(210.0, 210.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("reverse", "p1", "m", Position(210.0, 330.0), context.world)))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(0.0, first.heading, "A full-speed reverse mech keeps its north-facing body")
            assertEquals(-.056, first.speed, 1e-12)
            assertEquals(210.056, first.position.y, 1e-12)
            for (step in 1L..9L) context.advance(Step(step, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "m", context.world)))
            val after = context.advance(Step(10, 1.0, "sim-tick")).units.single()
            assertNull(after.moveTarget)
            assertTrue(after.speed < 0.0, "Stop clears the command and allows existing signed inertia to settle")
            for (step in 11L..40L) context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(0.0, context.observe().units.single().speed)
        } finally { context.close() }
    }
}
