package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class HeavyMechNativePanelTest {
    @Test fun the_real_native_panel_supports_paid_factory_upgrade_and_the_third_product_on_release_and_cancel() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val root = fixtures.resolve("heavy-mech-production/json")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        val scene = contentPackScenario(root, ContentPackChoice(pack, "builder"))
        // Capability-driven HUD action list (UI-P001-005)
        SandboxSession(scene).use { session ->
            session.select(Position(190.0, 150.0))
            assertIs<ActionResult.Accepted>(session.build("mechFactory", Position(270.0, 150.0)))
            repeat(1201) { session.tick() }
            session.select(Position(270.0, 150.0))
            assertEquals(listOf("builder", "mechGun"), session.productionActions.map { it.typeId })
            val buyRect = com.badlogic.gdx.math.Rectangle(0f, 0f, 100f, 52f)
            val buy = actor(buyRect, FactoryUpgradeInput({ session }, { true }, "upgrade_t2"))
            press(buy, true)
            assertEquals(15000L, session.observation.balance)
            press(buy, false)
            assertEquals(11000L, session.observation.balance)
            press(buy, true, Input.Buttons.RIGHT); press(buy, false, Input.Buttons.RIGHT)
            assertEquals(15000L, session.observation.balance)
            press(buy, true); press(buy, false)
            repeat(2600) { session.tick() }
            assertEquals(listOf("builder", "mechGun", "mechHeavyMissile"), session.productionActions.map { it.typeId })
            val hudKeys = HudActionProjection.projectActionList(session, null, false, false, false, null, false).exclusiveKeys
            assertTrue(hudKeys.map { it.id }.containsAll(listOf("produce:builder", "produce:mechGun", "produce:mechHeavyMissile")))
            val third = actor(com.badlogic.gdx.math.Rectangle(0f, 0f, 100f, 52f), ProductionButtonInput({ session }, { true },
                typeId = { session.productionActions.getOrNull(2)?.typeId }))
            press(third, true)
            assertEquals(11000L, session.observation.balance)
            press(third, false)
            assertEquals(2500L, session.observation.balance)
            assertEquals(listOf("mechHeavyMissile"), session.observation.factoryQueues.single().items.map { it.typeId })
            press(third, true, Input.Buttons.RIGHT)
            assertEquals(2500L, session.observation.balance)
            press(third, false, Input.Buttons.RIGHT)
            assertEquals(11000L, session.observation.balance)
            assertTrue(session.observation.factoryQueues.single().items.isEmpty())
        }
    }
    private fun actor(bounds: com.badlogic.gdx.math.Rectangle, input: InputListener) = Actor().apply {
        setBounds(bounds.x, bounds.y, bounds.width, bounds.height); addListener(input)
    }
    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
