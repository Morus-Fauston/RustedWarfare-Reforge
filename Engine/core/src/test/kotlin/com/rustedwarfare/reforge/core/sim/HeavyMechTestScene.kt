package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

/** Existing public movement-only seam; production and armed consumption have separate real chains. */
internal object HeavyMechTestScene {
    fun declared(): UnitDefinition {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val result = ContentLoader.loadContentPack(inputs.resolve("heavy-mech-production/json/package"))
        return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value.units.getValue("mechHeavyMissile")
    }

    fun unit(): UnitDefinition {
        val source = declared()
        return UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement,
            source.retainedUnconsumed, fogOfWarSightRange = 3, mech = source.mech)
    }

    fun scenario(unit: UnitDefinition = unit(), heading: Double = 0.0,
        position: Position = Position(200.0, 200.0), blocked: Set<Pair<Int, Int>> = emptySet()) = Scenario(
        "heavy-mech-test", MapDefinition(40, 30, 20, 20, listOf(TileSetDefinition(1, "test-ground", 20, 20, unit.imagePath, setOf(1))), IntArray(1200) { if (it % 40 to it / 40 in blocked) 2 else 1 },
            BlockingGrid(40, 30, BooleanArray(1200) { (it % 40 to it / 40) in blocked }), blocked),
        mapOf(unit.id to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
        listOf(UnitSpawn("heavy", "p1", unit.id, position, heading)),
        CoordinateModel("heavy.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("heavy.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))

    fun open(scenario: Scenario = scenario()): SimulationContext {
        val result = SimulationContext.initialize(scenario)
        return assertIs<InitResult.Accepted>(result, result.toString()).context
    }
}
