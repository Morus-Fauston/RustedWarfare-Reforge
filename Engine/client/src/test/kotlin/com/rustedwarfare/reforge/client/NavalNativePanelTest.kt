package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.*

class NavalNativePanelTest {
    @Test fun builder_sea_factory_press_and_release_selects_legal_water_construction() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(NavalSandboxScene.load(format).scenario).use { session ->
            session.select(Position(390.0, 150.0))
            val action = session.actionMenu.construction.single()
            assertEquals("seaFactory", action.typeId)
            var chosen: String? = null
            val card = actor(NativeActionPanel.production.first(),
                ConstructionButtonInput({ session }, { true }, action.typeId, { chosen = it }))
            press(card, true)
            assertNull(chosen)
            assertEquals(12000L, session.observation.balance)
            press(card, false)
            assertEquals("seaFactory", chosen)
            assertIs<ActionResult.Accepted>(session.build(assertNotNull(chosen), Position(450.0, 150.0)))
            session.tick()
            assertEquals(11000L, session.observation.balance)
            assertEquals(listOf("seaFactory"), session.observation.units.filter { it.typeId == "seaFactory" }.map { it.typeId })
        }
    }

    @Test fun naval_cards_have_their_real_names_and_actor_release_buys_or_cancels_the_correct_work() {
        val base = NavalSandboxScene.load().scenario
        assertTrue(NativeActionPanel.supports(base.scenarioId))
        SandboxSession(base).use { session ->
            session.select(Position(390.0, 150.0))
            assertEquals("Sea factory", session.constructionActions.single().title)
        }
        // Explicit prebuilt short input-isolation scenes; the normal full production chain is separate.
        for (tier in listOf("seaFactory", "seaFactoryT2")) {
            val input = base.copy(units = listOf(UnitSpawn("factory", "p1", tier, Position(450.0, 150.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("factory" to 8L), 9L))
            SandboxSession(input).use { session ->
                session.select(Position(450.0, 150.0))
                if (tier == "seaFactory") {
                    assertEquals(listOf("attackSubmarine"), session.productionActions.map { it.typeId })
                    val upgrade = session.actionMenu.upgrades.single()
                    assertEquals("Sea factory T2", upgrade.title)
                    assertEquals(2000L, upgrade.profile.price)
                    val bounds = NativeActionPanel.upgrades.getValue("upgrade_t2")
                    val buy = actor(bounds.first, UpgradeButtonInput({ session }, { true }, "upgrade_t2", false))
                    val cancel = actor(bounds.second, UpgradeButtonInput({ session }, { true }, "upgrade_t2", true))
                    press(buy, true); assertEquals(12000L, session.observation.balance)
                    press(buy, false); assertEquals(10000L, session.observation.balance)
                    press(cancel, true); assertEquals(10000L, session.observation.balance)
                    press(cancel, false); assertEquals(12000L, session.observation.balance)
                    assertTrue(session.observation.upgradeQueues.single().items.isEmpty())
                } else {
                    assertEquals(listOf("heavyBattleship", "attackSubmarine"), session.productionActions.map { it.typeId })
                    val product = session.productionActions.single { it.typeId == "heavyBattleship" }
                    assertEquals("heavyBattleship", product.typeId)
                    assertEquals("Heavy battleship", product.title)
                    assertEquals(6000L, product.price)
                    val buy = actor(NativeActionPanel.production.first(), ProductionButtonInput({ session }, { true },
                        typeId = { session.productionActions.first().typeId }))
                    press(buy, true); assertEquals(12000L, session.observation.balance)
                    press(buy, false); assertEquals(6000L, session.observation.balance)
                    assertEquals(listOf("heavyBattleship"), session.observation.factoryQueues.single().items.map { it.typeId })
                    press(buy, true, Input.Buttons.RIGHT); assertEquals(6000L, session.observation.balance)
                    press(buy, false, Input.Buttons.RIGHT); assertEquals(12000L, session.observation.balance)
                    assertTrue(session.observation.factoryQueues.single().items.isEmpty())
                }
            }
        }
    }
    private fun actor(bounds: NativeCardBounds, input: InputListener) = Actor().apply {
        setBounds(bounds.x, bounds.y, bounds.width, bounds.height); addListener(input)
    }
    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
