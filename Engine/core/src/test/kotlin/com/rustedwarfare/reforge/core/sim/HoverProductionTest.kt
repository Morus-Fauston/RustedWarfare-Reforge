package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

internal object HoverProductionScene {
    fun scenario(format: String = "json"): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val result = ContentLoader.loadContentPacks(listOf(fixtures.resolve("hover-production/$format/package")))
        val content = assertIs<ContentResult.Success<LoadedContentSet>>(result, result.toString()).value
        val tiles = TileSetDefinition(1, "shore", 20, 20, Path.of("unused.png"), setOf(2),
            3, 3, waterTileIds = setOf(1), hoverBlockedTileIds = setOf(2))
        val gids = IntArray(1200) { if (it % 40 in 20..25) 2 else 1 }
        val map = MapDefinition(40, 30, 20, 20, listOf(tiles), gids,
            BlockingGrid(40, 30, BooleanArray(1200) { it % 40 in 20..25 }))
        val base = TestContentFactory.openScenario()
        return base.copy(scenarioId = "hover-normal-production", map = map, unitDefinitions = content.units,
            units = listOf(UnitSpawn("center", "p1", "commandCenter", Position(130.0, 130.0), 0.0),
                UnitSpawn("enemy", "p2", "c_tank", Position(650.0, 350.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("center" to 8L, "enemy" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 5000L, "p2" to 0L)))
    }
}

class HoverProductionTest {
    @Test fun normal_start_builds_a_paid_factory_and_hover_then_crosses_water_stops_and_attacks() {
        for (format in listOf("json", "ini", "hjson")) {
            val init = SimulationContext.initialize(HoverProductionScene.scenario(format))
            val context = assertIs<InitResult.Accepted>(init, "$format: $init").context
            try {
                var step = 0L
                fun tick() = context.advance(Step(step++, 1.0, "sim-tick"))
                val workerNonce = "a".repeat(32)
                val worker = "produced:p1:$workerNonce"
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("worker", "p1", "center", "builder", workerNonce, context.world)))
                assertEquals(4500L, context.observePlayer(context.world, "p1").balance)
                while (context.observe().units.none { it.unitId == worker } && step < 600) tick()
                assertEquals("builder", context.observe().units.single { it.unitId == worker }.typeId)
                repeat(180) { tick() }
                val factory = "constructed:p1:${"b".repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("factory", "p1", worker, "landFactory",
                    Position(270.0, 190.0), "b".repeat(32), context.world)))
                while (context.observe().units.none { it.unitId == factory && it.constructionProgress == 1f } && step < 2600) tick()
                assertEquals(1f, context.observe().units.single { it.unitId == factory }.constructionProgress)
                val hoverNonce = "c".repeat(32)
                val id = "produced:p1:$hoverNonce"
                val balance = context.observePlayer(context.world, "p1").balance!!
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("hover", "p1", factory, "hoverTank", hoverNonce, context.world)))
                assertEquals(balance - 450L, context.observePlayer(context.world, "p1").balance)
                val birthStart = step
                while (context.observe().units.none { it.unitId == id } && step < birthStart + 510) tick()
                assertEquals(501L, step - birthStart)
                val birth = context.observe().units.single { it.unitId == id }
                assertEquals(Position(270.0, 195.0), birth.position)
                assertEquals(0.0, birth.height)
                assertEquals(30f, context.observe().production!!.units.single { it.unitId == id }.birthRemaining)
                repeat(180) { tick() }
                assertTrue(context.observe().units.single { it.unitId == id }.position.y > 240)
                assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", id, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("water", "p1", id, Position(490.0, 350.0), context.world)))
                repeat(600) { tick() }
                val water = context.observePlayer(context.world, "p1").units.single { it.unitId == id }
                assertTrue(water.position.x in 480.0..500.0 && water.position.y in 340.0..360.0, water.toString())
                assertTrue(water.height in 2.5..5.5)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", id, context.world)))
                assertNull(context.observePlayer(context.world, "p1").units.single { it.unitId == id }.moveTarget)
                assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", id, "enemy", context.world)))
                var fired = false
                repeat(450) {
                    val observed = tick()
                    fired = fired || observed.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == id }
                }
                assertTrue(fired)
                assertTrue(context.observe().units.single { it.unitId == "enemy" }.health!! < 210)
            } finally { context.close() }
        }
    }
}
