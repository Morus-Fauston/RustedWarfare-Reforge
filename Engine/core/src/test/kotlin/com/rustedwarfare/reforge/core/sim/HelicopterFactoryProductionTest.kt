package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object HelicopterFactoryScene {
    fun scenario(format: String = "json"): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val loaded = ContentLoader.loadContentPacks(listOf(fixtures.resolve("helicopter-production/$format/production-package")))
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(loaded, loaded.toString()).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixtures.resolve("helicopter-production/$format/maps/p001-controlled.tmx"))).value
        return Scenario("p008-helicopter-production", map, content.units,
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0),
                UnitSpawn("target", "p2", "helicopter", Position(500.0, 310.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "target" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
    }
}

class HelicopterFactoryProductionTest {
    @Test fun air_can_take_off_inside_a_live_factory_without_allowing_a_ground_spawn_in_its_footprint() {
        val base = HelicopterFactoryScene.scenario()
        val input = base.copy(units = base.units + listOf(
            UnitSpawn("factory", "p1", "airFactory", Position(270.0, 150.0), 0.0),
            UnitSpawn("hero", "p1", "helicopter", Position(270.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "target" to 9L, "factory" to 10L, "hero" to 11L), 12L))
        val context = HelicopterTestScene.open(input)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave-building", "p1", "hero", Position(360.0, 150.0), context.world)))
            val steps = (0L..149L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(steps.last().units.single { it.unitId == "hero" }.position.x > 315.0)
            assertTrue(steps.flatMap { it.events }.none { it.unitId == "hero" && it.kind in setOf("movement-blocked", "path-unreachable") })
        } finally { context.close() }
        val ground = SimulationContext.initialize(input.copy(units = input.units.map {
            if (it.unitId == "hero") it.copy(typeId = "builder") else it
        }))
        if (ground is InitResult.Accepted) ground.context.close()
        assertIs<InitResult.Rejected>(ground, "A ground unit must still respect the live building footprint")
    }

    @Test fun a_builder_really_builds_the_air_factory_and_its_paid_helicopter_exits_moves_stops_and_fights() {
      for (format in listOf("ini", "json", "hjson")) {
        val context = HelicopterTestScene.open(HelicopterFactoryScene.scenario(format))
        try {
            var step = 0L
            fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
            val factory = "constructed:p1:${"a".repeat(32)}"
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build-air", "p1", "worker", "airFactory",
                Position(270.0, 150.0), "a".repeat(32), context.world)))
            tick()
            assertEquals(2000L, context.observePlayer(context.world, "p1").balance)
            assertEquals(0f, context.observe().units.single { it.unitId == factory }.constructionProgress)
            val nonce = "b".repeat(32)
            assertIs<ActionResult.Rejected>(context.submit(ProduceAction("unfinished", "p1", factory, "helicopter", nonce, context.world)))
            while (context.observe().units.single { it.unitId == factory }.constructionProgress < 1f && step < 1300) tick()
            assertEquals(1f, context.observe().units.single { it.unitId == factory }.constructionProgress)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("produce-helicopter", "p1", factory, "helicopter", nonce, context.world)))
            assertEquals(1300L, context.observePlayer(context.world, "p1").balance)
            val id = "produced:p1:$nonce"
            while (context.observe().units.none { it.unitId == id } && step < 2300) tick()
            val born = context.observe().units.single { it.unitId == id }
            assertEquals(Position(270.0, 155.0), born.position)
            assertEquals(180.0, born.heading)
            assertEquals(Position(269.0, 240.0), born.moveTarget)
            assertEquals(0.0, born.height)
            assertEquals(0.0, assertNotNull(born.flightPose).rotorPhase)
            assertEquals(30f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
            assertEquals(factory, context.observe().production!!.units.single { it.unitId == id }.producerId)
            repeat(180) { tick() }
            val exited = context.observe().units.single { it.unitId == id }
            assertTrue(exited.position.y > 220.0)
            assertTrue(exited.height >= 4.0)
            assertEquals(0f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select-helicopter", "p1", id, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-helicopter", "p1", id, Position(410.0, 270.0), context.world)))
            repeat(20) { tick() }
            assertTrue(context.observe().units.single { it.unitId == id }.speed > 0.0)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-helicopter", "p1", id, context.world)))
            assertNull(context.observe().units.single { it.unitId == id }.moveTarget)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-helicopter", "p1", id, "target", context.world)))
            var fired = false
            repeat(600) {
                val observation = tick()
                fired = fired || observation.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == id }
            }
            assertTrue(fired)
            assertTrue(context.observe().units.single { it.unitId == "target" }.health!! < 150.0)
            assertEquals(1300L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
      }
    }
}
