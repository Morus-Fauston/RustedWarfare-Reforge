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
        assertTrue(NativeActionPanel.supports(scene.scenarioId), "The real production scene must enable its native panel")
        SandboxSession(scene).use { session ->
            session.select(Position(190.0, 150.0))
            assertIs<ActionResult.Accepted>(session.build("mechFactory", Position(270.0, 150.0)))
            repeat(1201) { session.tick() }
            session.select(Position(270.0, 150.0))
            assertEquals(listOf("builder", "mechGun"), session.productionActions.map { it.typeId })
            val positions = NativeActionPanel.upgrades.getValue(session.selectedUpgrades.single().actionKey)
            val buy = actor(positions.first, UpgradeButtonInput({ session }, { true }, "upgrade_t2", false))
            val cancel = actor(positions.second, UpgradeButtonInput({ session }, { true }, "upgrade_t2", true))
            press(buy, true)
            assertEquals(15000L, session.observation.balance)
            press(buy, false)
            assertEquals(11000L, session.observation.balance)
            press(cancel, true); press(cancel, false)
            assertEquals(15000L, session.observation.balance)
            press(buy, true); press(buy, false)
            repeat(2600) { session.tick() }
            assertEquals(listOf("builder", "mechGun", "mechHeavyMissile"), session.productionActions.map { it.typeId })
            val slots = NativeActionPanel.production.take(session.productionActions.size)
            assertEquals(3, slots.size)
            for (a in slots.indices) for (b in 0 until a) {
                val x = slots[a]; val y = slots[b]
                assertFalse(x.x < y.x + y.width && x.x + x.width > y.x && x.y < y.y + y.height && x.y + x.height > y.y)
            }
            val third = actor(slots[2], ProductionButtonInput({ session }, { true },
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
    private fun actor(bounds: NativeCardBounds, input: InputListener) = Actor().apply {
        setBounds(bounds.x, bounds.y, bounds.width, bounds.height); addListener(input)
    }
    private fun press(actor: Actor, down: Boolean, button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
        this.button = button; pointer = 0; stageX = actor.x + 10f; stageY = actor.y + 10f; touchFocus = false
    })
}
