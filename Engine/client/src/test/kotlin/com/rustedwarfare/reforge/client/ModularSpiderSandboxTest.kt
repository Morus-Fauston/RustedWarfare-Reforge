package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.sim.ActionResult
import java.nio.file.Path
import kotlin.test.*

class ModularSpiderSandboxTest {
    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun source(format: String): Pair<Path, LoadedContentPack> {
        val root = fixtures.resolve("modular-spider-start/$format")
        val result = ContentLoader.loadContentPack(root.resolve("opening-package"))
        return root to assertIs<ContentResult.Success<LoadedContentPack>>(result).value
    }

    @Test fun a_slot_module_cannot_be_chosen_as_an_independent_starting_unit() {
        val (_, pack) = source("ini")
        assertFailsWith<IllegalArgumentException> { ContentPackChoice(pack, "modularSpider_emptySlot") }
        assertFailsWith<IllegalArgumentException> { ContentPackChoice(pack, "modularSpider_gunturret") }
    }

    @Test fun the_native_panel_exposes_clickable_slot_and_module_cards() {
        val (root, pack) = source("ini")
        val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
        assertTrue(NativeActionPanel.supports(scene.scenarioId))
        SandboxSession(scene).use { session ->
            repeat(100) { session.tick() }
            session.select(session.observation.units.single { it.typeId == "modularSpider" }.position)
            fun bounds() {
                val cards = session.actionMenu.unitActions.map { NativeActionPanel.customBounds(it.name, naval = false) }
                assertTrue(cards.all { it.x >= 752f && it.x + it.width <= 928f && it.y >= 184f && it.y + it.height <= 340f })
                for ((index, a) in cards.withIndex()) for (b in cards.drop(index + 1)) {
                    assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x ||
                        a.y + a.height <= b.y || b.y + b.height <= a.y, "Overlapping attachment cards: $a and $b")
                }
            }
            fun click(name: String) {
                val bounds = NativeActionPanel.customActions.getValue(name)
                val actor = Actor().apply { setSize(bounds.width, bounds.height) }
                val event = InputEvent().also { it.listenerActor = actor }
                val input = CustomUnitActionButtonInput({ session }, { true }, name)
                assertTrue(input.touchDown(event, bounds.width / 2, bounds.height / 2, 0, Input.Buttons.LEFT))
                input.touchUp(event, bounds.width / 2, bounds.height / 2, 0, Input.Buttons.LEFT)
            }
            bounds()
            click("selectSlot1")
            assertEquals(listOf("Add Gun T1", "Add Laser", "Add AA"), session.actionMenu.unitActions
                .filter { it.name.startsWith("build") }.map { it.title })
            bounds()
            val balance = checkNotNull(session.observation.balance)
            click("buildGun")
            assertEquals(balance - 2000, session.observation.balance)
            session.tick()
            bounds()
            click("cancel")
            session.tick()
            assertEquals(balance, session.observation.balance)
            click("selectSlotBuild")
            assertTrue(session.actionMenu.unitActions.none { it.name == "buildGun" })
        }
    }

    @Test fun releasing_a_module_card_after_switching_slots_does_not_spend_on_the_new_slot() {
        val (root, pack) = source("ini")
        SandboxSession(contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))).use { session ->
            repeat(100) { session.tick() }
            session.select(session.observation.units.single { it.typeId == "modularSpider" }.position)
            assertIs<ActionResult.Accepted>(session.unitAction("selectSlot1"))
            val before = session.observation.balance
            val actor = Actor().apply { setSize(84f, 28f) }
            val event = InputEvent().also { it.listenerActor = actor }
            val input = CustomUnitActionButtonInput({ session }, { true }, "buildSmallLaser")
            assertTrue(input.touchDown(event, 12f, 12f, 0, Input.Buttons.LEFT))
            assertIs<ActionResult.Accepted>(session.unitAction("selectSlot2"))
            input.touchUp(event, 12f, 12f, 0, Input.Buttons.LEFT)
            assertEquals(before, session.observation.balance)
        }
    }

    @Test fun all_formats_open_a_normal_spider_and_its_selectable_slot_menu() {
        for (format in listOf("ini", "json", "hjson")) {
            val (root, pack) = source(format)
            val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
            assertEquals("p009-modular-spider-start", scene.scenarioId)
            assertEquals(listOf("modularSpider"), scene.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            SandboxSession(scene).use { session ->
                assertFalse(session.canDeploy)
                assertEquals(5000L, session.observation.balance)
                repeat(100) { session.tick() }
                val spider = session.observation.units.single { it.typeId == "modularSpider" }
                assertEquals(8, assertNotNull(spider.mechPose).legs.size)
                session.select(spider.position)
                assertEquals(spider.unitId, session.selectedId)
                assertEquals(6, session.actionMenu.unitActions.count { it.name.matches(Regex("selectSlot[1-6]")) })
                assertIs<ActionResult.Accepted>(session.unitAction("selectSlot1"))
                assertEquals("Add Gun T1", session.actionMenu.unitActions.single { it.name == "buildGun" }.title)
                val credits = checkNotNull(session.observation.balance)
                assertIs<ActionResult.Accepted>(session.unitAction("buildGun"))
                assertEquals(credits - 2000L, session.observation.balance)
                session.tick()
                assertEquals("Cancel", session.actionMenu.unitActions.single { it.name == "cancel" }.title)
                assertIs<ActionResult.Accepted>(session.unitAction("cancel"))
                session.tick()
                assertTrue(session.actionMenu.unitActions.single { it.name == "buildGun" }.cooldownRemaining > 0)
            }
        }
    }
}
