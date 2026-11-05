package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.assertIs

/** Current public package loading plus the qualified movement-only, sight-three transform. */
internal object MechTestScene {
    fun unit(): UnitDefinition {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("mech-production/ini/package"))).value.units.getValue("mechGun")
        return UnitDefinition(source.id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement,
            source.retainedUnconsumed, fogOfWarSightRange = 3, mech = source.mech)
    }

    fun scenario(unit: UnitDefinition = unit(), heading: Double = 90.0,
                 position: Position = Position(100.0, 100.0), blocked: Set<Pair<Int, Int>> = emptySet()): Scenario = Scenario(
        "mech-test", MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600) { 1 },
            BlockingGrid(30, 20, BooleanArray(600) { (it % 30 to it / 30) in blocked })),
        mapOf("mech" to unit), listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
        listOf(UnitSpawn("u1", "p1", "mech", position, heading)),
        CoordinateModel("mech.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("mech.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))

    fun with(unit: UnitDefinition = unit(), mech: MechDefinition? = unit.mech,
             movement: MovementProfile = unit.movement): UnitDefinition = UnitDefinition(
        unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames, unit.imagePath,
        unit.imageScale, movement, unit.retainedUnconsumed, unit.fogOfWarSightRange, mech = mech)

    fun open(scenario: Scenario = scenario()): SimulationContext = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
}
