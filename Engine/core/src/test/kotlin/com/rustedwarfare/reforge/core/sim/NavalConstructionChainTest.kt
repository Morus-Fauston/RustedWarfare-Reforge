package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.LoadedContentSet
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import java.nio.file.Path
import kotlin.test.*

class NavalConstructionChainTest {
    @Test fun a_shore_builder_constructs_t1_upgrades_normally_and_produces_a_ship_with_the_complete_budget_chain() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) {
            withProducedBattleship(SeaFactoryScene.scenario(map, format)) { context, ship, tick ->
                repeat(450) { tick() }
                assertTrue(context.observe().units.single { it.unitId == ship }.position.y > 270.0)
                assertEquals(0f, context.observe().production!!.units.single { it.unitId == ship }.birthRemaining)
                assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            }
        }
    }

    @Test fun a_produced_rectangular_ship_passes_the_channel_and_keeps_its_side_clear_of_the_bank() =
        withinNarrowWaterway { map ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            for (format in listOf("json", "hjson")) {
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                    fixtures.resolve("phase-seven-single-ship/$format/package"))).value
                val composed = assertIs<ContentResult.Success<LoadedContentSet>>(
                    ContentLoader.composeContentPacks(listOf(pack))).value
                assertEquals(MovementCollisionShape.Rectangle(102.0, 20.0),
                    composed.units.getValue("heavyBattleship").movementCollision)
                assertEquals(setOf("heavyBattleship", "seaFactory", "seaFactoryT2", "builder", "attackSubmarine"),
                    composed.units.keys)
                assertIs<ContentResult.Success<Unit>>(composed.verifyStable())
                val scenario = WaterTestScene.scenario(map, MovementType.WATER,
                    Position(450.0, 150.0)).copy(unitDefinitions = composed.units)
                withProducedBattleship(scenario) { context, ship, tick ->
                    fun observeShip() = context.observePlayer(context.world, "p1").units.single { it.unitId == ship }
                    context.enableMotionDiagnostics(context.world)
                    repeat(450) { tick() }
                    assertEquals(0f, context.observe().production!!.units.single { it.unitId == ship }.birthRemaining)
                    val launch = observeShip()
                    val exitTrace = context.drainMotionDiagnostics(context.world).filter { it.unitId == ship }
                    if (launch.position.y <= 270.0) fail(
                        "Ship did not leave the factory: pose=${launch.position}/${launch.heading}; " +
                            "earliest corrected=${exitTrace.firstOrNull { it.correctionTimeAfter > 0.0 }}; " +
                            "last=${exitTrace.lastOrNull()}"
                    )
                    assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("through-channel", "p1", ship, Position(450.0, 540.0), context.world)))
                    repeat(650) { tick() }
                    val crossing = observeShip()
                    val trace = context.drainMotionDiagnostics(context.world).filter { it.unitId == ship }
                    if (crossing.position.y <= 525.0) fail(
                        "Ship should reach the far end of the channel: launch=${launch.position}/${launch.heading}; " +
                            "final=${crossing.position}/${crossing.heading}; " +
                            "earliest corrected=${trace.firstOrNull { it.correctionTimeAfter > 0.0 }}; last=${trace.lastOrNull()}"
                    )
                    assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("toward-bank", "p1", ship, Position(478.0, 380.0), context.world)))
                    repeat(650) {
                        tick()
                        val point = observeShip().position
                        if (point.y in 340.0..460.0) {
                            assertTrue(point.x <= 470.0 + 1e-9, "The hull crosses the bank at $point")
                        }
                    }
                }
            }
        }

    @Test fun the_same_channel_blocks_a_produced_ini_ship_with_its_default_circular_hull() =
        withinNarrowWaterway { map ->
            withProducedBattleship(SeaFactoryScene.scenario(map, "ini")) { context, ship, tick ->
                repeat(450) { tick() }
                val launch = context.observePlayer(context.world, "p1").units.single { it.unitId == ship }
                assertTrue(launch.position.y > 270.0, "The circular ship must still leave the factory")
                val move = assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("too-wide", "p1", ship, Position(450.0, 540.0), context.world)))
                assertTrue(move.events.isEmpty())
                var stopped: MoveArrival? = null
                repeat(1250) {
                    tick()
                    val unit = context.observe().units.single { it.unitId == ship }
                    assertTrue(unit.position.y < 340.0, "The circular hull entered the narrow channel: ${unit.position}")
                    stopped = unit.moveArrival ?: stopped
                }
                assertEquals(Position(450.0, 540.0), assertNotNull(stopped).originalTarget)
                assertTrue(stopped.distance > 0.0)
            }
        }

    private fun withinNarrowWaterway(action: (MapDefinition) -> Unit) = WaterTestScene.withMap { shore ->
        val firstRow = shore.groundGids.take(shore.widthTiles)
        val height = 32
        val map = MapDefinition(shore.widthTiles, height, shore.tileWidth, shore.tileHeight, shore.tileSets,
            IntArray(shore.widthTiles * height) { firstRow[it % shore.widthTiles] },
            BlockingGrid(shore.widthTiles, height, BooleanArray(shore.widthTiles * height)),
            buildSet {
                for (row in 17..22) for (column in 20 until shore.widthTiles)
                    if (column !in 21..23) add(column to row)
            })
        action(map)
    }

    private fun withProducedBattleship(base: Scenario, action: (SimulationContext, String, () -> Unit) -> Unit) {
        val scene = base.copy(
            units = listOf(UnitSpawn("worker", "p1", "builder", Position(390.0, 150.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("worker" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var q = 0L
            val factory = "constructed:p1:${"a".repeat(32)}"
            val ship = "produced:p1:${"b".repeat(32)}"
            fun tick() { context.advance(Step(q++, 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", "worker", "seaFactory",
                Position(450.0, 150.0), "a".repeat(32), context.world)))
            tick()
            assertEquals(11000L, context.observePlayer(context.world, "p1").balance)
            assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                UpgradeAction("unfinished-upgrade", "p1", factory, "upgrade_t2", context.world))).error.code)
            assertEquals("factory-under-construction", assertIs<ActionResult.Rejected>(context.submit(
                ProduceAction("unfinished-product", "p1", factory, "heavyBattleship", "b".repeat(32), context.world))).error.code)
            val constructionLimit = q + 1700L
            while (context.observe().units.single { it.unitId == factory }.constructionProgress < 1f && q < constructionLimit) tick()
            assertEquals(1f, context.observe().units.single { it.unitId == factory }.constructionProgress)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("worker-stop", "p1", "worker", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(UpgradeAction("upgrade", "p1", factory, "upgrade_t2", context.world)))
            assertEquals(9000L, context.observePlayer(context.world, "p1").balance)
            val upgradeLimit = q + 2700L
            while (context.observe().units.single { it.unitId == factory }.typeId != "seaFactoryT2" && q < upgradeLimit) tick()
            assertEquals("seaFactoryT2", context.observe().units.single { it.unitId == factory }.typeId)
            assertEquals(1000.0, context.observe().units.single { it.unitId == factory }.health)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("ship", "p1", factory, "heavyBattleship", "b".repeat(32), context.world)))
            assertEquals(3000L, context.observePlayer(context.world, "p1").balance)
            val productLimit = q + 2600L
            while (context.observe().units.none { it.unitId == ship } && q < productLimit) tick()
            assertEquals(Position(450.0, 170.0), context.observe().units.single { it.unitId == ship }.position)
            action(context, ship, ::tick)
        } finally {
            context.close()
        }
    }
}
