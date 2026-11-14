package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthorMaintenanceRallyTest {
    @TempDir lateinit var temporary: Path

    @Test fun factory_rally_is_owned_persists_through_birth_and_clears_without_affecting_existing_orders() {
        val units = packageCopy()
        val factory = units.resolve("spfactory.json")
        val document = JsonValue.readJSON(Files.readString(factory)).asObject().set("rally", true)
        Files.writeString(factory, document.toString())
        val loaded = ContentLoader.loadContentPack(temporary)
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val spawns = listOf(UnitSpawn("factory", "p1", "spfactory", Position(210.0, 150.0), 0.0),
            UnitSpawn("guest", "p2", "builder", Position(80.0, 240.0), 90.0))
        val scenario = TestContentFactory.openScenario().copy(unitDefinitions = definitions, units = spawns,
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "guest" to 9L), 10L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            val destination = Position(255.0, 230.0)
            fun set(id: String, player: String, target: Position?, unit: String = "factory") =
                context.submit(UnitAction(id, player, unit, "setRally", context.world, cancelQueued = target == null, target = target))
            assertIs<ActionResult.Rejected>(set("foreign", "p2", destination))
            assertIs<ActionResult.Rejected>(set("nonfactory", "p2", destination, "guest"))
            assertIs<ActionResult.Rejected>(set("outside", "p1", Position(-1.0, 230.0)))
            assertIs<ActionResult.Accepted>(set("set", "p1", destination))
            assertEquals(destination, context.observePlayer(context.world, "p1").factoryQueues.single().rally)
            val foreign = context.observePlayer(context.world, "p2")
            assertTrue(foreign.units.any { it.unitId == "factory" })
            assertTrue(foreign.factoryQueues.isEmpty())
            assertTrue(foreign.events.none { it.kind == "rally-set" })
            assertNull(foreign.units.single { it.unitId == "factory" }.factoryRally)
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("first", "p1", "factory",
                "c_tank", "a".repeat(32), context.world)))
            assertEquals(2650L, context.observePlayer(context.world, "p1").balance)
            val firstId = "produced:p1:${"a".repeat(32)}"
            while (context.observe().units.none { it.unitId == firstId } && step < 1000) advance()
            assertEquals("c_tank", context.observe().units.single { it.unitId == firstId }.typeId)
            assertIs<ActionResult.Accepted>(set("clear", "p1", null))
            assertNull(context.observePlayer(context.world, "p1").factoryQueues.single().rally)
            assertTrue(context.observePlayer(context.world, "p2").events.none { it.kind == "rally-cleared" })
            repeat(200) { advance() }
            val position = context.observe().units.single { it.unitId == firstId }.position
            assertTrue(ScreenPlane.distanceSquared(position, destination) <= 64.0, position.toString())
            assertIs<ActionResult.Accepted>(context.submit(ProduceAction("second", "p1", "factory",
                "c_tank", "b".repeat(32), context.world)))
            val secondId = "produced:p1:${"b".repeat(32)}"
            while (context.observe().units.none { it.unitId == secondId } && step < 2200) advance()
            repeat(120) { advance() }
            val unassigned = context.observe().units.single { it.unitId == secondId }.position
            assertTrue(ScreenPlane.distanceSquared(unassigned, destination) > 225.0)
            assertEquals(2300L, context.observePlayer(context.world, "p1").balance)
        } finally { context.close() }
    }

    @Test fun a_blocked_factory_exit_releases_the_rally_leg_and_a_blocked_rally_drops_its_task() {
        val units = packageCopy()
        val factory = units.resolve("spfactory.json")
        val document = JsonValue.readJSON(Files.readString(factory)).asObject().set("rally", true)
        Files.writeString(factory, document.toString())
        val loaded = ContentLoader.loadContentPack(temporary)
        val definitions = assertIs<ContentResult.Success<LoadedContentPack>>(loaded, loaded.toString()).value.units
        val base = TestContentFactory.openScenario()
        val map = base.map
        val blockedGrid = BlockingGrid(map.widthTiles, map.heightTiles, BooleanArray(map.widthTiles * map.heightTiles) { i ->
            val column = i % map.widthTiles
            val row = i / map.widthTiles
            map.blocking.isBlocked(column, row) || column == 209 / map.tileWidth && row == 240 / map.tileHeight
        })
        val blocked = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
            map.tileSets, map.groundGids, blockedGrid)
        assertTrue(blocked.blocking.isBlocked(209 / map.tileWidth, 240 / map.tileHeight))
        val scene = base.copy(map = blocked, unitDefinitions = definitions,
            units = listOf(UnitSpawn("factory", "p1", "spfactory", Position(210.0, 150.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L),
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            fun advance() = context.advance(Step(step++, 1.0, "sim-tick"))
            val reachable = Position(270.0, 205.0)
            val unreachable = Position(209.0, 240.0)
            for ((attempt, point) in listOf(reachable, unreachable).withIndex()) {
                val id = "produced:p1:${(attempt + 1).toString().repeat(32)}"
                assertIs<ActionResult.Accepted>(context.submit(UnitAction("rally-$attempt", "p1", "factory",
                    "setRally", context.world, target = point)))
                assertIs<ActionResult.Accepted>(context.submit(ProduceAction("buy-$attempt", "p1", "factory",
                    "c_tank", id.substringAfterLast(':'), context.world)))
                val events = (1..790).flatMap { advance().events }
                assertTrue(events.any { it.kind == "path-unreachable" && it.unitId == id })
                val product = context.observe().units.single { it.unitId == id }
                assertTrue(product.tasks.isEmpty(), product.tasks.toString())
                if (attempt == 0) assertTrue(ScreenPlane.distanceSquared(reachable, product.position) <= 64.0, product.toString())
                else assertNull(product.moveTarget)
            }
        } finally { context.close() }
    }

    private fun packageCopy(): Path {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-production/json/package")
        Files.walk(source).use { paths -> paths.forEach { path ->
            val target = temporary.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        return temporary.resolve("units")
    }
}
