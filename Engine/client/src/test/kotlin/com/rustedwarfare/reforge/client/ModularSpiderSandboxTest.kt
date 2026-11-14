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
import kotlin.test.*

class ModularSpiderSandboxTest {
    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    private fun source(format: String): Pair<Path, LoadedContentPack> {
        val root = fixtures.resolve("modular-spider-start/$format")
        val result = ContentLoader.loadContentPack(root.resolve("opening-package"))
        return root to assertIs<ContentResult.Success<LoadedContentPack>>(result).value
    }

    @Test fun the_build_page_exposes_normal_site_work_and_yields_to_a_slot_page() {
        for (format in listOf("ini", "json", "hjson")) {
            val (root, pack) = source(format)
            val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
            SandboxSession(scene).use { session ->
                repeat(100) { session.tick() }
                session.select(Position(190.0, 150.0))
                val build = session.constructionActions.associateBy { it.typeId }
                assertEquals(setOf("builder", "hovercraft", "c_turret_t1", "c_turret_t1_artillery", "extractorT1",
                    "fabricatorT1", "landFactory", "mechFactory", "airFactory", "seaFactory", "laserDefence",
                    "repairbay", "bomber"), build.keys)
                assertEquals(500L, build.getValue("builder").price)
                assertTrue(session.canRepair)
                assertIs<ActionResult.Accepted>(session.unitAction("selectSlot1"))
                assertTrue(session.constructionActions.isEmpty())
                assertIs<ActionResult.Accepted>(session.unitAction("selectSlotBuild"))
                assertIs<ActionResult.Accepted>(session.build("builder", Position(260.0, 200.0)))
            }
        }
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
            fun purchaseMenu() = session.actionMenu.unitActions.any { it.name == "buildFabricator" }
            fun bounds() {
                val cards = session.actionMenu.unitActions.map {
                    NativeActionPanel.customBounds(it.name, naval = false, spiderPurchaseMenu = purchaseMenu())
                }
                assertTrue(cards.all { it.x >= 752f && it.x + it.width <= 928f && it.y >= 84f && it.y + it.height <= 340f })
                if (purchaseMenu()) {
                    assertTrue(cards.all { it.y >= NativeActionPanel.spiderPurchaseStopY + 40f })
                    val reopen = NativeActionPanel.spiderPurchaseReopen
                    val exit = NativeActionPanel.spiderPurchaseExit
                    assertTrue(reopen.y + reopen.height <= NativeActionPanel.spiderPurchaseStopY)
                    assertTrue(exit.y + exit.height <= NativeActionPanel.spiderPurchaseStopY)
                    assertTrue(reopen.x + reopen.width <= exit.x)
                }
                for ((index, a) in cards.withIndex()) for (b in cards.drop(index + 1)) {
                    assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x ||
                        a.y + a.height <= b.y || b.y + b.height <= a.y, "Overlapping attachment cards: $a and $b")
                }
            }
            fun click(name: String) {
                val bounds = NativeActionPanel.customBounds(name, naval = false, spiderPurchaseMenu = purchaseMenu())
                val actor = Actor().apply { setSize(bounds.width, bounds.height) }
                val event = InputEvent().also { it.listenerActor = actor }
                val input = CustomUnitActionButtonInput({ session }, { true }, name)
                assertTrue(input.touchDown(event, bounds.width / 2, bounds.height / 2, 0, Input.Buttons.LEFT))
                input.touchUp(event, bounds.width / 2, bounds.height / 2, 0, Input.Buttons.LEFT)
            }
            bounds()
            click("selectSlot1")
            assertEquals(listOf("Add Gun T1", "Add Laser", "Add AA", "Add Lightning", "Add Artillery",
                "Add Fabricator", "Add Shield", "Add Speed", "Add Laser guard", "Add Anti-nuke", "Add Blink"), session.actionMenu.unitActions
                .filter { it.name.startsWith("build") }.map { it.title })
            bounds()
            val balance = checkNotNull(session.observation.balance)
            assertFalse(session.actionMenu.unitActions.single { it.name == "buildLightning" }.available)
            assertFalse(session.actionMenu.unitActions.single { it.name == "buildShieldGen" }.available)
            assertFalse(session.actionMenu.unitActions.single { it.name == "buildSpeed" }.available)
            click("buildArtillery")
            assertEquals(balance - 5000, session.observation.balance)
            session.tick()
            bounds()
            click("cancel")
            session.tick()
            assertEquals(balance, session.observation.balance)
            click("selectSlotBuild")
            assertTrue(session.actionMenu.unitActions.none { it.name == "buildArtillery" })
            click("selectSlot2")
            click("buildFabricator")
            assertEquals(balance - 3000, session.observation.balance)
            session.tick()
            val upgrade = session.actionMenu.unitActions.single { it.name == "upgradeT2" }
            assertEquals("Fab T2", upgrade.title)
            assertTrue(upgrade.tooltip.contains("Fabricator T2") && upgrade.tooltip.contains("Cost 5000 / 8s"))
            assertEquals("modularSpider_fabricatorT2", upgrade.iconTypeId)
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

    @Test fun the_interceptor_card_exposes_its_own_stock_and_non_cancellable_switch_on_a_distinct_row() {
        val (root, pack) = source("ini")
        val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
        SandboxSession(scene).use { session ->
            repeat(2800) { session.tick() }
            session.select(session.observation.units.single { it.typeId == "modularSpider" }.position)
            assertIs<ActionResult.Accepted>(session.unitAction("selectSlot2"))
            assertIs<ActionResult.Accepted>(session.unitAction("buildAntinuke"))
            session.tick()
            val menu = session.actionMenu
            val ammo = menu.unitActions.single { it.name == "buildAntiNuke" }
            val switch = menu.unitActions.single { it.name == "isEnabled" }
            assertEquals("Ammo 0", ammo.title)
            assertEquals("Disable", switch.title)
            assertTrue(ammo.tooltip.contains("Online") && ammo.tooltip.contains("Cost 3000"))
            assertFalse(switch.cancellable)
            val cards = menu.unitActions.map {
                NativeActionPanel.customBounds(it.name, naval = false, spiderInterceptor = true)
            }
            assertTrue(cards.all { it.y >= 184f }, "Interceptor cards must clear the Stop and Patrol controls")
            for ((index, a) in cards.withIndex()) for (b in cards.drop(index + 1)) {
                assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x ||
                    a.y + a.height <= b.y || b.y + b.height <= a.y, "Overlapping interceptor cards: $a and $b")
            }
        }
    }

    @Test fun blink_preview_uses_the_selected_slot_and_rejects_range_and_map_edges() {
        val (root, pack) = source("ini")
        val scene = contentPackScenario(root, ContentPackChoice(pack, "modularSpider"))
        SandboxSession(scene).use { session ->
            repeat(14900) { session.tick() }
            val parent = session.observation.units.single { it.typeId == "modularSpider" }
            session.select(parent.position)
            assertIs<ActionResult.Accepted>(session.unitAction("selectSlot2"))
            assertIs<ActionResult.Accepted>(session.unitAction("buildBlink"))
            session.tick()
            val view = session.actionMenu.unitActions.single { it.name == "blink" }
            assertTrue(view.groundTargeted && view.available)
            val slot = session.observation.units.single { it.unitId == view.attachmentContext?.first }
            val legal = assertNotNull(session.previewGroundAction("blink", slot.position))
            assertEquals(slot.position, legal.source)
            assertTrue(legal.valid)
            val map = MapProjection(scene.map.widthTiles.toDouble() * scene.map.tileWidth,
                scene.map.heightTiles.toDouble() * scene.map.tileHeight)
            val x = map.x(slot.position.x).toInt()
            val y = map.y(slot.position.y).toInt()
            var covered = false
            val input = SandboxInput({ session }, { map }, { screenX, screenY -> Position(screenX.toDouble(), screenY.toDouble()) },
                { true }, { false }, { false }, battlefieldAt = { _, _ -> !covered })
            assertTrue(input.beginGroundAction("blink"))
            assertTrue(input.touchDown(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            covered = true
            assertTrue(input.touchUp(x, y, 0, com.badlogic.gdx.Input.Buttons.LEFT))
            assertEquals("blink", input.groundActionName)
            assertTrue(session.actionMenu.unitActions.single { it.name == "blink" }.available)
            assertEquals("action-target-out-of-range", session.previewGroundAction("blink",
                com.rustedwarfare.reforge.core.sim.Position(slot.position.x + 701.0, slot.position.y))?.rejection)
            assertEquals("action-target-impassable", session.previewGroundAction("blink",
                com.rustedwarfare.reforge.core.sim.Position(-1.0, slot.position.y))?.rejection)
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
