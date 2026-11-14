package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class AuthoredBlinkInputTest {
    @TempDir lateinit var temporary: Path

    @Test fun unit_card_purchases_blink_and_ground_click_uses_the_units_own_position() {
        val loaded = AaBeamSandboxScene.load()
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            .resolve("hover-production/json/package/units/hoverTank")
        for (name in listOf("tank.png", "tank_dead.png", "shadow.png"))
            Files.copy(fixtures.resolve(name), temporary.resolve(name))
        val declaration = Files.readString(fixtures.resolve("hover.json")).replace(
            "\"name\": \"hoverTank\",", "\"name\": \"experimentalGunship\",").let {
            it.trimEnd().dropLast(1) + """,
                "abilities":[{"name":"jump","type":"blink","range":700,"chargeTicks":210,
                    "cooldownTicks":1500,"unlockCredits":10000,"unlockTicks":3000}]}"""
        }
        val path = temporary.resolve("experimentalGunship.json")
        Files.writeString(path, declaration)
        val unit = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadHoverTankWithSources(path)).value.definition
        val origin = Position(180.0, 160.0)
        val map = loaded.scenario.map
        val blockedColumn = 420 / map.tileWidth
        val blockedRow = 180 / map.tileHeight
        val scene = loaded.scenario.copy(map = MapDefinition(map.widthTiles, map.heightTiles, map.tileWidth,
            map.tileHeight, map.tileSets, map.groundGids, map.blocking, setOf(blockedColumn to blockedRow)),
            unitDefinitions = mapOf(unit.id to unit),
            units = listOf(UnitSpawn("ship", "p1", unit.id, origin, 90.0)),
            economyContract = EconomyContract(mapOf("p1" to 12000L, "p2" to 0L)),
            combatContract = CombatContract(12345, -1, mapOf("ship" to 8L), 9L))
        SandboxSession(scene).use { session ->
            session.select(origin)
            val projection = MapProjection(scene.map.widthTiles.toDouble() * scene.map.tileWidth,
                scene.map.heightTiles.toDouble() * scene.map.tileHeight)
            val input = SandboxInput({ session }, { projection }, { sx, sy -> Position(sx.toDouble(), sy.toDouble()) },
                { true }, { false }, { false }, battlefieldAt = { _, _ -> true })
            fun pressCard(name: String, button: Int = Input.Buttons.LEFT) {
                val actor = Actor().apply { setSize(100f, 32f) }
                val event = InputEvent().also { it.listenerActor = actor }
                val listener = CustomUnitActionButtonInput({ session }, { true }, name, beginTargeting = input::beginGroundAction)
                assertTrue(listener.touchDown(event, 50f, 16f, 0, button))
                listener.touchUp(event, 50f, 16f, 0, button)
            }
            assertEquals("Add blink", session.actionMenu.unitActions.single().title)
            pressCard("addBlink")
            assertEquals(2000L, session.observation.balance)
            repeat(3000) { session.tick() }
            assertTrue(session.actionMenu.unitActions.single().groundTargeted)
            val gridEdge = Position(420.0 - 0.00001, 180.0)
            assertEquals("action-target-impassable", session.previewGroundAction("blink", gridEdge)?.rejection)
            assertEquals("action-target-impassable",
                assertIs<ActionResult.Rejected>(session.unitAction("blink", target = gridEdge)).error.code)
            pressCard("blink")
            assertEquals("blink", input.groundActionName)
            val intended = Position(330.0, 200.0)
            val intendedX = projection.x(intended.x).toInt()
            val intendedY = projection.y(intended.y).toInt()
            val target = assertNotNull(projection.groundPoint(intendedX.toDouble(), intendedY.toDouble()))
            assertEquals(origin, session.previewGroundAction("blink", target)?.source)
            assertTrue(input.touchDown(intendedX, intendedY, 0, Input.Buttons.LEFT))
            assertTrue(input.touchUp(intendedX, intendedY, 0, Input.Buttons.LEFT))
            assertNull(input.groundActionName)
            assertEquals("blink", session.observation.units.single().unitActions!!.queue.single().name)
            repeat(210) { session.tick() }
            assertEquals(Position(target.x.toFloat().toDouble(), target.y.toFloat().toDouble()),
                session.observation.units.single().position)
            assertFalse(session.actionMenu.unitActions.single().available)
        }
    }
}
