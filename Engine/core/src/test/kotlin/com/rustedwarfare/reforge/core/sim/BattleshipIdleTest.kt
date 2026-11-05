package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class BattleshipIdleTest {
    @Test
    fun real_named_turrets_use_distinct_reverse_idle_then_return_to_forward_idle() = WaterTestScene.withMap { map ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ship = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/ini/production-package"))).value.units.getValue("heavyBattleship")
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("ship" to ship), units = listOf(UnitSpawn("ship", "p1", "ship", Position(500.0, 200.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L), 9L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.submit(MoveAction("reverse", "p1", "ship", Position(450.0, 200.0), context.world))
            repeat(250) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val reverse = context.observePlayer(context.world, "p1").weapons.associateBy { it.weaponName }
            assertEquals(305.0, reverse.getValue("1").turretHeading, 1e-9)
            assertEquals(215.0, reverse.getValue("2").turretHeading, 1e-9)
            assertNull(context.observe().units.single().mechPose)
            context.submit(MoveAction("forward", "p1", "ship", Position(650.0, 200.0), context.world))
            repeat(350) { context.advance(Step(it + 250L, 1.0, "sim-tick")) }
            val forward = context.observePlayer(context.world, "p1").weapons.associateBy { it.weaponName }
            assertEquals(90.0, forward.getValue("1").turretHeading, 1e-9)
            assertEquals(90.0, forward.getValue("2").turretHeading, 1e-9)
            assertTrue(context.observe().projectiles.isEmpty())
            assertEquals(305.0, reverse.getValue("1").turretHeading, 1e-9)
        } finally { context.close() }
    }
}
