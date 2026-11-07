package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class AuthorFactorySandboxTest {
    private val fixtures: Path get() = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun load(format: String): LoadedSandbox {
        val root = fixtures.resolve("author-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, "spfactory"))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("Author factory normal content choice did not load")
        }
    }

    @Test fun normal_content_choice_produces_two_products_and_right_release_cancels_the_requested_product() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(load(format).scenario).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals("a", session.selectedId)
            assertEquals(listOf("builder", "c_tank"), session.productionActions.map { it.typeId })
            val builder = card(session, "builder")
            val tank = card(session, "c_tank")
            press(builder, true); assertEquals(3000L, session.observation.balance)
            press(builder, false); assertEquals(2500L, session.observation.balance)
            press(tank, true); press(tank, false)
            assertEquals(2150L, session.observation.balance)
            assertEquals(listOf("builder", "c_tank"), session.selectedFactoryQueue!!.items.map { it.typeId })
            press(tank, true, Input.Buttons.RIGHT); assertEquals(2150L, session.observation.balance)
            press(tank, false, Input.Buttons.RIGHT); assertEquals(2500L, session.observation.balance)
            assertEquals(listOf("builder"), session.selectedFactoryQueue!!.items.map { it.typeId })
            press(tank, true); press(tank, false)
            repeat(1100) { session.tick() }
            assertEquals(listOf("builder", "c_tank"), session.observation.units
                .filter { it.unitId.startsWith("produced:") }.map { it.typeId }.sorted())
            assertEquals(2150L, session.observation.balance)
            assertTrue(session.selectedFactoryQueue!!.items.isEmpty())
        }
    }

    @Test fun a_stale_factory_card_cannot_buy_after_selection_changes_to_an_enemy() {
        SandboxSession(load("json").scenario).use { session ->
            session.select(Position(110.0, 210.0))
            val tank = card(session, "c_tank")
            press(tank, true)
            session.select(Position(350.0, 210.0))
            assertTrue(session.productionActions.isEmpty())
            press(tank, false)
            assertEquals(3000L, session.observation.balance)
            assertTrue(session.observation.factoryQueues.all { it.items.isEmpty() })
        }
    }

    @Test fun a_content_only_seventh_long_named_product_buys_cancels_and_finishes_without_an_action_table_entry() {
        val type = "sp_tank_7_with_long_name"
        SandboxSession(load("extended").scenario).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals(7, session.productionActions.size)
            assertEquals(type, session.productionActions.last().typeId)
            assertEquals(type, session.productionActions.last().title)
            val last = card(session, type)
            press(last, true); press(last, false)
            assertEquals(2650L, session.observation.balance)
            assertEquals(1, session.productionActions.last().count)
            press(last, true, Input.Buttons.RIGHT); press(last, false, Input.Buttons.RIGHT)
            assertEquals(3000L, session.observation.balance)
            assertEquals(0, session.productionActions.last().count)
            press(last, true); press(last, false)
            repeat(600) { session.tick() }
            assertEquals(type, session.observation.units.single { it.unitId.startsWith("produced:") }.typeId)
            assertEquals(2650L, session.observation.balance)
            assertTrue(session.selectedFactoryQueue!!.items.isEmpty())
        }
    }

    private fun card(session: SandboxSession, type: String) = Actor().apply {
        setBounds(752f, 256f, 84f, 64f)
        addListener(ProductionButtonInput({ session }, { true }, typeId = { type }))
    }
    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
