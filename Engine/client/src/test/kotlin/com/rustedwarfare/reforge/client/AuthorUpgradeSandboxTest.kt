package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class AuthorUpgradeSandboxTest {
    private fun load(format: String, sourceRoot: Path? = null, unitId: String = "spfactory"): Scenario {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val root = sourceRoot ?: fixtures.resolve("author-upgrade/$format")
        val parsed = ContentLoader.loadContentPack(root.resolve("package"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(parsed, parsed.toString()).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, unitId))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow().scenario }
                Thread.sleep(5)
            }
            error("Author upgrade normal content choice did not load")
        }
    }

    @Test fun an_actually_built_author_factory_advertises_its_upgrade_only_after_construction_finishes() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("author-upgrade/ini")
        val root = Files.createTempDirectory("rwr-author-upgrade-construction-").toAbsolutePath().normalize()
        try {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val destination = root.resolve(source.relativize(file)).normalize()
                require(destination.startsWith(root))
                if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
            } }
            for (name in listOf("base-constructor.ini", "spbuilder.ini"))
                Files.copy(fixtures.resolve("author-construction/ini/package/units/$name"), root.resolve("package/units/$name"))
            val worker = root.resolve("package/units/spbuilder.ini")
            Files.writeString(worker, Files.readString(worker).replace("workshop", "spfactory"))
            val manifest = root.resolve("package/pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"units\": [",
                "\"units\": [{\"type\":\"constructor\",\"file\":\"units/spbuilder.ini\"},"))
            SandboxSession(load("ini", root, "spbuilder")).use { session ->
                session.select(Position(110.0, 210.0))
                assertIs<ActionResult.Accepted>(session.build("spfactory", Position(190.0, 210.0)))
                var steps = 0
                while (session.observation.units.none { it.typeId == "spfactory" } && steps++ < 400) session.tick()
                val site = session.observation.units.single { it.typeId == "spfactory" }
                assertTrue(site.constructionProgress < 1f)
                assertEquals(2300L, session.observation.balance)
                session.select(site.position)
                assertEquals(site.unitId, session.selectedId)
                assertTrue(session.productionActions.isEmpty())
                assertTrue(session.actionMenu.upgrades.isEmpty(), "An unfinished author factory must not offer an actionable upgrade")
                while (session.observation.units.single { it.unitId == site.unitId }.constructionProgress < 1f && steps++ < 2200)
                    session.tick()
                assertEquals(1f, session.observation.units.single { it.unitId == site.unitId }.constructionProgress)
                assertEquals("spfactoryt2", session.actionMenu.upgrades.single().actionKey)
                assertIs<ActionResult.Accepted>(session.upgrade("spfactoryt2"))
                assertEquals(200L, session.observation.balance)
                repeat(300) { session.tick() }
                assertEquals("spfactoryt2", session.observation.units.single { it.unitId == site.unitId }.typeId)
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { file ->
                require(file.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(file)
            } }
        }
    }

    @Test fun normal_author_upgrade_action_reports_its_real_duration_and_pays_cancels_then_exposes_new_products() {
        for (format in listOf("ini", "json", "hjson")) SandboxSession(load(format)).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals(listOf("builder"), session.productionActions.map { it.typeId })
            val action = session.actionMenu.upgrades.single()
            assertEquals("spfactoryt2", action.actionKey)
            assertEquals("spfactoryt2", action.title)
            assertEquals(2100L, action.price)
            assertTrue(action.tooltip.contains("4.2s"), action.tooltip)
            val buy = Actor().apply {
                setBounds(0f, 0f, 84f, 64f)
                addListener(FactoryUpgradeInput({ session }, { true }, "spfactoryt2"))
            }
            press(buy, true); assertEquals(3000L, session.observation.balance)
            press(buy, false); assertEquals(900L, session.observation.balance)
            session.tick()
            assertTrue(session.selectedUpgradeQueue!!.items.single().progress > 0f)
            assertTrue(session.actionMenu.upgradeQueueText.contains("spfactoryt2"), session.actionMenu.upgradeQueueText)
            press(buy, true, Input.Buttons.RIGHT); press(buy, false, Input.Buttons.RIGHT)
            assertEquals(3000L, session.observation.balance)
            assertTrue(session.selectedUpgradeQueue!!.items.isEmpty())
            press(buy, true); press(buy, false)
            repeat(300) { session.tick() }
            assertEquals("spfactoryt2", session.observation.units.single { it.unitId == "a" }.typeId)
            assertTrue(session.selectedUpgrades.isEmpty())
            assertEquals(listOf("builder", "c_tank"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("c_tank"))
            assertEquals(550L, session.observation.balance)
            repeat(600) { session.tick() }
            assertEquals("c_tank", session.observation.units.single { it.unitId.startsWith("produced:") }.typeId)
        }
    }

    @Test fun an_author_upgrade_press_cannot_survive_selection_round_trips_or_a_completed_conversion() {
        SandboxSession(load("json")).use { session ->
            session.select(Position(110.0, 210.0))
            val card = Actor().apply {
                setBounds(0f, 0f, 84f, 64f)
                addListener(FactoryUpgradeInput({ session }, { true }, "spfactoryt2"))
            }
            press(card, true)
            session.select(Position(350.0, 210.0))
            session.select(Position(110.0, 210.0))
            press(card, false)
            assertEquals(3000L, session.observation.balance)
            assertTrue(session.selectedUpgradeQueue!!.items.isEmpty())
            press(card, true)
            assertIs<ActionResult.Accepted>(session.upgrade("spfactoryt2"))
            repeat(300) { session.tick() }
            press(card, false)
            assertEquals(900L, session.observation.balance)
            assertEquals("spfactoryt2", session.observation.units.single { it.unitId == "a" }.typeId)
            assertTrue(session.selectedUpgrades.isEmpty())
        }
    }

    @Test fun a_content_only_long_product_list_can_upgrade_and_unlock_an_eighth_product_without_card_registration() {
        SandboxSession(load("extended")).use { session ->
            session.select(Position(110.0, 210.0))
            assertEquals(7, session.productionActions.size)
            assertEquals("spfactoryt2", session.actionMenu.upgrades.single().actionKey)
            val upgrade = Actor().apply {
                setBounds(0f, 0f, 84f, 64f)
                addListener(FactoryUpgradeInput({ session }, { true }, "spfactoryt2"))
            }
            press(upgrade, true); press(upgrade, false)
            assertEquals(900L, session.observation.balance)
            repeat(300) { session.tick() }
            assertEquals(8, session.productionActions.size)
            assertTrue(session.selectedUpgrades.isEmpty())
            val type = "sp_tank_8_after_upgrade"
            assertEquals(type, session.productionActions.last().typeId)
            val product = Actor().apply {
                setBounds(0f, 0f, 84f, 64f)
                addListener(ProductionButtonInput({ session }, { true }, typeId = { type }))
            }
            press(product, true); press(product, false)
            assertEquals(550L, session.observation.balance)
            press(product, true, Input.Buttons.RIGHT); press(product, false, Input.Buttons.RIGHT)
            assertEquals(900L, session.observation.balance)
            press(product, true); press(product, false)
            repeat(600) { session.tick() }
            assertEquals(type, session.observation.units.single { it.unitId.startsWith("produced:") }.typeId)
            assertEquals(550L, session.observation.balance)
        }
    }

    private fun press(actor: Actor, down: Boolean, mouseButton: Int = Input.Buttons.LEFT) {
        val event = InputEvent().apply {
            type = if (down) InputEvent.Type.touchDown else InputEvent.Type.touchUp
            stageX = 8f; stageY = 8f; pointer = 0; button = mouseButton
            touchFocus = false
        }
        actor.fire(event)
    }
}
