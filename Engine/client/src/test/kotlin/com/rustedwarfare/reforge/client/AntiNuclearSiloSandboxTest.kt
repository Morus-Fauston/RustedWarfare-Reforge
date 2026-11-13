package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AntiNuclearSiloSandboxTest {
    @Test
    fun a_normal_builder_can_construct_and_stock_the_ground_interceptor_in_each_format() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("nuclear-silo/$format")
            val result = ContentLoader.loadContentPack(root.resolve("interception-package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format $result").value
            val scene = contentPackScenario(root, ContentPackChoice(pack, "commandCenter"))
            assertTrue(NativeActionPanel.supports(scene.scenarioId))
            SandboxSession(scene).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format: ${session.feedback}")
                }
                val type = "antiNukeLauncherC"
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertEquals(15000L, session.constructionActions.single { it.typeId == type }.price)
                val point = Position(250.0, 150.0)
                assertIs<ActionResult.Accepted>(session.build(type, point))
                until(1800) { session.observation.units.any { it.typeId == type && it.constructionProgress == 1f } }
                session.select(point)
                assertEquals("Anti-nuke silo", session.actionMenu.selectedTitle)
                val card = session.actionMenu.unitActions.single()
                assertEquals("Build interceptor", card.title)
                assertTrue(card.tooltip.contains("4000"))
                val bounds = NativeActionPanel.customActions.getValue(card.name)
                val actor = Actor().also { it.setSize(bounds.width, bounds.height) }
                val listener = CustomUnitActionButtonInput({ session }, { true }, card.name)
                fun click(button: Int) {
                    val event = InputEvent().also { it.listenerActor = actor }
                    assertTrue(listener.touchDown(event, 10f, 10f, 0, button))
                    listener.touchUp(event, 10f, 10f, 0, button)
                }
                val balance = checkNotNull(session.observation.balance)
                click(Input.Buttons.LEFT)
                assertEquals(balance - 4000, session.observation.balance)
                click(Input.Buttons.RIGHT)
                assertEquals(balance, session.observation.balance)
                click(Input.Buttons.LEFT)
                until(1430) { session.observation.units.single { it.typeId == type }.unitActions?.stockpile == 1 }
                assertTrue(checkNotNull(session.actionMenu.selectedUnitInfo).contains("Ammo 1"))
            }
        }
    }
}
