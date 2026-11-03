package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class MechFactoryProductionTest {
    @Test
    fun a_builder_really_builds_the_l1_factory_and_its_paid_mech_exits_selects_moves_stops_and_fires() {
        for (format in listOf("ini", "json", "hjson")) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(format))).context
            val c = context
            try {
                var step = 0L
                fun tick() = c.advance(Step(step++, 1.0, "sim-tick"))
                val factory = "constructed:p1:${"a".repeat(32)}"
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "worker", "mechFactory",
                    Position(270.0, 150.0), "a".repeat(32), c.world)))
                tick()
                assertEquals(2000L, c.observePlayer(c.world, "p1").balance)
                assertEquals(0f, c.observe().units.single { it.unitId == factory }.constructionProgress)
                val nonce = "b".repeat(32)
                assertIs<ActionResult.Rejected>(c.submit(ProduceAction("unfinished", "p1", factory, "mechGun", nonce, c.world)))
                while (c.observe().units.single { it.unitId == factory }.constructionProgress < 1f && step < 1300) tick()
                assertEquals(1f, c.observe().units.single { it.unitId == factory }.constructionProgress)
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", factory, "mechGun", nonce, c.world)))
                assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
                val id = "produced:p1:$nonce"
                while (c.observe().units.none { it.unitId == id } && step < 2300) tick()
                val born = c.observe().units.single { it.unitId == id }
                assertEquals(Position(277.0, 149.0), born.position)
                assertEquals(180.0, born.heading)
                assertEquals(45f, c.observe().production!!.units.single { it.unitId == id }.birthRemaining)
                assertEquals(factory, c.observe().production!!.units.single { it.unitId == id }.producerId)
                assertNotNull(born.mechPose)
                val angle = Math.toRadians(187.0)
                assertEquals(277.0 + sin(angle) * 120.0, born.moveTarget!!.x, 1e-10)
                assertEquals(149.0 - cos(angle) * 120.0, born.moveTarget!!.y, 1e-10)
                repeat(180) { tick() }
                val exited = c.observe().units.single { it.unitId == id }
                assertTrue(exited.position.y > 220.0, "The product must actually leave the footprint: $exited")
                assertEquals(0f, c.observe().production!!.units.single { it.unitId == id }.birthRemaining)
                assertIs<ActionResult.Accepted>(c.submit(SelectAction("select", "p1", id, c.world)))
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", id, Position(410.0, 270.0), c.world)))
                repeat(20) { tick() }
                assertTrue(c.observe().units.single { it.unitId == id }.speed != 0.0)
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", id, c.world)))
                assertNull(c.observe().units.single { it.unitId == id }.moveTarget)
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", id, "target", c.world)))
                var fired = false
                repeat(600) {
                    val view = tick()
                    fired = fired || view.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == id }
                }
                assertTrue(fired, "The produced mech must fire through actual combat")
                assertTrue(c.observe().units.single { it.unitId == "target" }.health!! < 500.0)
                assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
            } finally { c.close() }
        }
    }

    private fun scene(format: String): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("mech-production/$format")
        val set = assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root.resolve("production-package")))).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))).value
        return Scenario("p008-mech-production", map, set.units,
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("worker", "p1", "builder", Position(190.0, 150.0), 90.0),
                UnitSpawn("target", "p2", "mechGun", Position(500.0, 310.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L, "target" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
    }
}
