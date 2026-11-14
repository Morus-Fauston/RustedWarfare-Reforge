package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import org.hjson.JsonValue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AuthorFactoryRallyInputTest {
    @TempDir lateinit var temporary: Path

    @Test fun mixed_repair_leaves_the_ineligible_workers_move_order_intact() {
        val fixture = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-construction/json")
        val root = temporary.resolve("mixed")
        Files.walk(fixture.resolve("package")).use { entries -> entries.forEach { source ->
            val dest = root.resolve(fixture.resolve("package").relativize(source))
            if (Files.isDirectory(source)) Files.createDirectories(dest) else Files.copy(source, dest)
        } }
        val units = root.resolve("units")
        val unitTemplate = Files.readString(units.resolve("spbuilder.json"))
        fun write(name: String, field: String) =
            Files.writeString(units.resolve("$name.json"), JsonValue.readJSON(unitTemplate).asObject()
                .set("name", name).set(field, true).toString())
        write("site_worker", "canRepairBuildings")
        write("unit_worker", "canRepairUnits")
        Files.writeString(root.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1","id":"mixed-care","name":"Mixed care",
            "units":[{"type":"constructor","file":"units/site_worker.json"},
                     {"type":"constructor","file":"units/unit_worker.json"},
                     {"type":"factory","file":"units/workshop.json"}]
        }""")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
        val map = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(
            fixture.resolve("maps/p001-controlled.tmx"))).value
        val gun = UnitDefinition("gun", "CustomUnitMetadata", 6.0, 6.0, 1, pack.units.getValue("site_worker").imagePath,
            1.0, pack.units.getValue("site_worker").movement, emptySet(),
            combat = CombatProfile(900.0, 115.0, 400.0, 0.0, 15.0, 15.0,
                ProjectileProfile(30.0, 45.0, 8.0, instant = true), null, null, 1.0))
        val scene = contentPackScenario(fixture, ContentPackChoice(pack, "site_worker")).copy(
            map = map, unitDefinitions = pack.units + (gun.id to gun),
            units = listOf(UnitSpawn("structure", "p1", "workshop", Position(190.0, 210.0), 90.0),
                UnitSpawn("site", "p1", "site_worker", Position(110.0, 195.0), 90.0),
                UnitSpawn("mobile", "p1", "unit_worker", Position(110.0, 235.0), 90.0),
                UnitSpawn("enemy", "p2", gun.id, Position(275.0, 210.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("structure" to 8L, "site" to 9L, "mobile" to 10L, "enemy" to 11L), 12L))
        SandboxSession(scene).use { session ->
            repeat(50) { if (session.observation.units.single { u -> u.unitId == "structure" }.health == 1200.0) session.tick() }
            assertTrue(session.observation.units.single { it.unitId == "structure" }.health!! < 1200)
            session.select(Position(110.0, 235.0))
            assertIs<ActionResult.Accepted>(session.move(Position(110.0, 280.0)))
            val keep = session.observation.units.single { it.unitId == "mobile" }.tasks
            assertEquals(UnitTaskKind.MOVE, keep.single().kind)
            session.selectBox(Position(90.0, 175.0), Position(130.0, 250.0))
            assertEquals(setOf("site", "mobile"), session.selectedIds)
            assertIs<ActionResult.Accepted>(session.command(Position(190.0, 210.0)))
            assertEquals("structure", session.observation.units.single { it.unitId == "site" }.tasks.single().targetUnitId)
            assertEquals(keep, session.observation.units.single { it.unitId == "mobile" }.tasks)
            val batch = assertNotNull(session.lastCommandBatch)
            assertEquals(1, batch.accepted)
            assertEquals(1, batch.skipped)
        }
    }

    @Test fun the_owned_factory_card_sets_a_battlefield_point_and_right_click_clears_it() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("author-production/json")
        val fixture = root.resolve("package")
        Files.walk(fixture).use { entries -> entries.forEach { input ->
            val output = temporary.resolve(fixture.relativize(input))
            if (Files.isDirectory(input)) Files.createDirectories(output) else Files.copy(input, output)
        } }
        val factory = temporary.resolve("units/spfactory.json")
        val text = JsonValue.readJSON(Files.readString(factory)).asObject().set("rally", true)
        Files.writeString(factory, text.toString())
        val result = ContentLoader.loadContentPack(temporary)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        val loaded = SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "spfactory"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var prepared = loader.pollPrepared()
            while (prepared == null && System.nanoTime() < deadline) {
                Thread.sleep(5)
                prepared = loader.pollPrepared()
            }
            checkNotNull(prepared).getOrThrow()
        }
        SandboxSession(loaded.scenario).use { session ->
            val projection = MapProjection(loaded.scenario.map.widthTiles.toDouble() * loaded.scenario.map.tileWidth,
                loaded.scenario.map.heightTiles.toDouble() * loaded.scenario.map.tileHeight)
            val input = SandboxInput({ session }, { projection }, { x, y -> Position(x.toDouble(), y.toDouble()) },
                { true }, { false }, { false }, battlefieldAt = { _, _ -> true })
            session.select(Position(110.0, 210.0))
            val action = session.actionMenu.unitActions.single { it.name == "setRally" }
            assertTrue(action.available && action.groundTargeted)
            assertFalse(action.cancellable)
            val actor = Actor().apply { setSize(100f, 32f) }
            val listener = CustomUnitActionButtonInput({ session }, { true }, action.name, beginTargeting = input::beginGroundAction)
            fun press(button: Int) {
                val event = InputEvent().also { it.listenerActor = actor }
                assertTrue(listener.touchDown(event, 50f, 16f, 0, button))
                listener.touchUp(event, 50f, 16f, 0, button)
            }
            press(Input.Buttons.LEFT)
            assertEquals("setRally", input.groundActionName)
            val x = projection.x(210.0).toInt()
            val y = projection.y(230.0).toInt()
            val point = assertNotNull(projection.toWorld(x.toDouble(), y.toDouble()))
            assertTrue(input.touchDown(x, y, 0, Input.Buttons.LEFT))
            assertTrue(input.touchUp(x, y, 0, Input.Buttons.LEFT))
            assertNull(input.groundActionName)
            assertEquals(point, session.selectedFactoryQueue?.rally)
            assertTrue(session.actionMenu.unitActions.single { it.name == "setRally" }.cancellable)
            press(Input.Buttons.RIGHT)
            assertNull(session.selectedFactoryQueue?.rally)
            assertFalse(session.actionMenu.unitActions.single { it.name == "setRally" }.cancellable)
            session.select(Position(350.0, 210.0))
            assertTrue(session.actionMenu.unitActions.none { it.name == "setRally" })
            assertEquals(3000L, session.observation.balance)
        }
    }
}
