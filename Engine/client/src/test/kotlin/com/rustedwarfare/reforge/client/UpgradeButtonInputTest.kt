package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class UpgradeButtonInputTest {
    @Test fun each_existing_branch_pays_only_on_release_and_its_separate_cancel_endpoint_refunds_once() {
        for ((key, price) in listOf("upgrade_gun" to 1000.0, "upgrade_artillery" to 1600.0)) {
            SandboxSession(scene()).use { session ->
                session.select(Position(190.0, 170.0))
                val buy = card(UpgradeButtonInput({ session }, { true }, key))
                val cancel = card(UpgradeButtonInput({ session }, { true }, key, cancel = true))
                fire(buy, InputEvent.Type.touchDown)
                assertEquals(4000.0, session.observation.credits)
                assertTrue(session.selectedUpgradeQueue!!.items.isEmpty())
                fire(buy, InputEvent.Type.touchUp)
                assertEquals(4000.0 - price, session.observation.credits)
                assertEquals(key, session.selectedUpgradeQueue!!.items.single().actionKey)
                fire(buy, InputEvent.Type.touchUp)
                assertEquals(4000.0 - price, session.observation.credits)
                fire(cancel, InputEvent.Type.touchDown)
                assertEquals(1, session.selectedUpgradeQueue!!.items.size)
                fire(cancel, InputEvent.Type.touchUp)
                assertEquals(4000.0, session.observation.credits)
                assertTrue(session.selectedUpgradeQueue!!.items.isEmpty())
                fire(cancel, InputEvent.Type.touchUp)
                assertEquals(4000.0, session.observation.credits)
            }
        }
    }

    @Test fun stale_presses_cannot_retarget_after_selection_round_trips_reopen_drag_cancel_or_inactivity_and_fresh_presses_work() {
        for (key in listOf("upgrade_gun", "upgrade_artillery")) {
            val sessions = mutableListOf<SandboxSession>()
            fun open() = SandboxSession(scene()).also { it.select(Position(190.0, 170.0)); sessions += it }
            var current = open()
            var active = true
            val input = UpgradeButtonInput({ current }, { active }, key)
            val buy = card(input)
            fun unchanged() {
                assertEquals(4000.0, current.observation.credits)
                assertTrue(current.observation.upgradeQueues.all { it.items.isEmpty() })
            }
            try {
                fire(buy, InputEvent.Type.touchDown)
                current.select(Position(390.0, 170.0))
                fire(buy, InputEvent.Type.touchUp); unchanged()
                current.select(Position(190.0, 170.0))
                fire(buy, InputEvent.Type.touchDown)
                current.select(Position(390.0, 170.0)); current.select(Position(190.0, 170.0))
                fire(buy, InputEvent.Type.touchUp); unchanged()
                fire(buy, InputEvent.Type.touchDown)
                current.close(); current = open()
                fire(buy, InputEvent.Type.touchUp); unchanged()
                fire(buy, InputEvent.Type.touchDown)
                fire(buy, InputEvent.Type.touchDragged, x = 100f)
                fire(buy, InputEvent.Type.touchDragged)
                fire(buy, InputEvent.Type.touchUp); unchanged()
                fire(buy, InputEvent.Type.touchDown); input.cancel()
                fire(buy, InputEvent.Type.touchUp); unchanged()
                fire(buy, InputEvent.Type.touchDown); active = false
                fire(buy, InputEvent.Type.touchUp); active = true; unchanged()
                fire(buy, InputEvent.Type.touchDown, button = Input.Buttons.RIGHT)
                fire(buy, InputEvent.Type.touchUp, button = Input.Buttons.RIGHT); unchanged()
                fire(buy, InputEvent.Type.touchDown, pointer = 1)
                fire(buy, InputEvent.Type.touchUp, pointer = 1); unchanged()
                fire(buy, InputEvent.Type.touchDown)
                fire(buy, InputEvent.Type.touchUp)
                assertEquals(key, current.selectedUpgradeQueue!!.items.single().actionKey)
                val paid = current.observation.credits
                val cancelInput = UpgradeButtonInput({ current }, { active }, key, cancel = true)
                val cancel = card(cancelInput)
                fire(cancel, InputEvent.Type.touchDown)
                current.select(Position(390.0, 170.0)); current.select(Position(190.0, 170.0))
                fire(cancel, InputEvent.Type.touchUp)
                assertEquals(paid, current.observation.credits)
                assertEquals(1, current.selectedUpgradeQueue!!.items.size)
                fire(cancel, InputEvent.Type.touchDown); cancelInput.cancel()
                fire(cancel, InputEvent.Type.touchUp)
                assertEquals(paid, current.observation.credits)
                fire(cancel, InputEvent.Type.touchDown); fire(cancel, InputEvent.Type.touchUp)
                unchanged()
            } finally { sessions.forEach { it.close() } }
        }
    }

    @Test fun unsupported_keys_unselected_objects_and_empty_cancel_endpoints_do_not_own_an_order() {
        SandboxSession(scene()).use { session ->
            val selectedBuy = card(UpgradeButtonInput({ session }, { true }, "upgrade_gun"))
            assertFalse(fire(selectedBuy, InputEvent.Type.touchDown))
            fire(selectedBuy, InputEvent.Type.touchUp)
            session.select(Position(190.0, 170.0))
            for ((key, cancellation) in listOf("upgrade_t2" to false, "upgrade_gun" to true)) {
                val actor = card(UpgradeButtonInput({ session }, { true }, key, cancellation))
                assertFalse(fire(actor, InputEvent.Type.touchDown))
                fire(actor, InputEvent.Type.touchUp)
            }
            assertEquals(4000.0, session.observation.credits)
            assertTrue(session.observation.upgradeQueues.all { it.items.isEmpty() })
        }
    }

    @Test fun real_completion_invalidates_a_held_press_even_when_the_same_unit_id_remains_selected() {
        for (key in listOf("upgrade_gun", "upgrade_artillery")) {
            SandboxSession(scene()).use { session ->
                session.select(Position(190.0, 170.0))
                val actor = card(UpgradeButtonInput({ session }, { true }, key))
                fire(actor, InputEvent.Type.touchDown)
                assertIs<ActionResult.Accepted>(session.upgrade(key))
                val paid = session.observation.credits
                var ticks = 0
                while (session.observation.units.single { it.unitId == "a" }.typeId == "c_turret_t1" && ticks++ < 3000)
                    session.tick()
                assertEquals(if (key == "upgrade_gun") "c_turret_t2_gun" else "c_turret_t1_artillery",
                    session.observation.units.single { it.unitId == "a" }.typeId)
                assertEquals("a", session.selectedId)
                fire(actor, InputEvent.Type.touchUp)
                assertEquals(paid, session.observation.credits)
                assertTrue(session.selectedUpgrades.isEmpty())
                assertFalse(fire(actor, InputEvent.Type.touchDown))
            }
        }
    }

    private fun card(input: UpgradeButtonInput) = Actor().apply {
        setBounds(0f, 0f, 84f, 40f); addListener(input)
    }

    private fun fire(actor: Actor, kind: InputEvent.Type, x: Float = 10f, pointer: Int = 0,
                     button: Int = Input.Buttons.LEFT) = actor.fire(InputEvent().apply {
        type = kind; this.pointer = pointer; this.button = button
        stageX = x; stageY = 10f; touchFocus = false
    })

    private fun scene(): Scenario {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }.resolve(
                ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/defense-input")
        val base = ControlledScenario.load(root)
        val ground = base.map.tileSets.first { 2 in it.blockedTileIds }.firstGid
        val units = listOf(UnitSpawn("a", "p1", "c_turret_t1", Position(190.0, 170.0), 0.0),
            UnitSpawn("b", "p1", "c_turret_t1", Position(390.0, 170.0), 0.0))
        return base.copy(map = MapDefinition(40, 25, 20, 20, base.map.tileSets, IntArray(1000) { ground },
            BlockingGrid(40, 25, BooleanArray(1000))), units = units,
            economyContract = EconomyContract(mapOf("p1" to 4000L, "p2" to 0L)),
            combatContract = CombatContract(1, -1, units.mapIndexed { i, u -> u.unitId to i + 1L }.toMap(), 3L))
    }
}
