package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class UnderwaterTargetContractTest {
    @Test
    fun invalid_depth_and_moving_or_producible_targets_reject_and_the_closed_context_remains_reusable() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val units = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/ini/production-package"))).value.units
        val target = units.getValue("navalUnderwaterTarget")
        fun variant(profile: FixedUnderwaterTargetProfile? = target.fixedUnderwaterTarget,
                    movement: MovementProfile = target.movement, durability: DurabilityProfile? = target.durability,
                    combat: CombatProfile? = null, build: BuildProfile? = null,
                    production: ProductionProfile? = null, builder: BuilderProfile? = null): UnitDefinition =
            UnitDefinition(target.id, target.className, target.radius, target.displayRadius, target.totalFrames,
                target.imagePath, target.imageScale, movement, emptySet(), 0, combat = combat, durability = durability,
                build = build, production = production, builder = builder, fixedUnderwaterTarget = profile)
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(600.0, 200.0)).copy(
            unitDefinitions = mapOf("target" to target),
            units = listOf(UnitSpawn("target", "p1", "target", Position(600.0, 200.0), 0.0, -8.0)),
            economyContract = EconomyContract(mapOf("p1" to 0L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val old = context.observe()
        val oldWorld = context.world
        context.close()
        try {
            val invalid = listOf(Double.NaN, Double.POSITIVE_INFINITY, -7.0, 0.0).map {
                variant(profile = FixedUnderwaterTargetProfile(it))
            } + listOf(variant(movement = target.movement.copy(type = MovementType.LAND)),
                variant(movement = target.movement.copy(moveSpeed = .1)),
                variant(movement = target.movement.copy(reverseSpeedPercentage = 1.0)),
                variant(movement = target.movement.copy(flight = FlightProfile(20.0, 0.0, 1.0, 0.0))),
                variant(combat = units.getValue("heavyBattleship").combat),
                variant(build = BuildProfile(6000L, .0004f)),
                variant(production = ProductionProfile(listOf("heavyBattleship"))),
                variant(builder = BuilderProfile(listOf("seaFactory"))), variant(durability = null))
            for (definition in invalid) {
                val result = assertIs<InitResult.Rejected>(context.initialize(input.copy(unitDefinitions = mapOf("target" to definition))))
                assertEquals("unsupported-underwater-target", result.error.code)
                assertEquals(-8.0, old.units.single().height)
            }
            for (height in listOf(-7.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                val result = assertIs<InitResult.Rejected>(context.initialize(input.copy(units = input.units.map { it.copy(height = height) })))
                assertEquals("invalid-spawn", result.error.code)
            }
            assertIs<InitResult.Accepted>(context.initialize(input))
            assertNotSame(oldWorld, context.world)
            assertEquals(-8.0, context.observe().units.single().height)
            assertEquals(1000.0, context.observe().units.single().health)
            assertEquals(-8.0, old.units.single().height)
        } finally { context.close() }
    }
}
