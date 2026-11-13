package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class HeavyMechProductionSandboxTest {
    @Test fun the_large_cliff_band_uses_the_existing_rock_atlas_instead_of_the_tree_strip() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format, "builder")
            val map = loaded.scenario.map
            val cliffs = map.groundGids.filter { GroundRules.cliff(map.tileSets, it) }.distinct()
            assertTrue(cliffs.isNotEmpty())
            for (gid in cliffs) assertEquals("controlled-rock-35",
                loaded.renderResources.tileSets.last { it.firstGid <= gid }.supportedLayout())
        }
    }

    @Test
    fun checked_heavy_choices_open_the_same_builder_first_scene_without_a_free_factory_or_hero() {
        for (format in listOf("ini", "json", "hjson")) for (choice in listOf("builder", "mechFactory", "mechFactoryT2", "mechHeavyMissile")) {
            val loaded = load(format, choice)
            assertEquals("p008-heavy-mech-production", loaded.scenario.scenarioId)
            assertEquals(16000L, loaded.scenario.economyContract!!.initialBalances.getValue("p1"))
            assertEquals(listOf("builder"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertEquals(setOf("mechGun", "helicopter"), loaded.scenario.units.filter { it.ownerPlayerId == "p2" }.map { it.typeId }.toSet())
            assertTrue(loaded.scenario.units.all { it.position.x in 22.0..578.0 && it.position.y in 22.0..378.0 })
        }
    }

    @Test
    fun real_cards_upgrade_in_place_produce_four_legs_and_reopen_with_clean_funds_queues_and_selection() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = load(format, "builder")
            SandboxSession(loaded.scenario).use { session ->
                session.select(Position(190.0, 150.0))
                val card = session.constructionActions.single()
                assertEquals("mechFactory", card.typeId)
                assertEquals(1000L, card.price)
                assertIs<ActionResult.Accepted>(session.build(card.typeId, Position(270.0, 150.0)))
                session.tick()
                assertEquals(15000L, session.observation.balance)
                repeat(1200) { session.tick() }
                val factory = session.observation.units.single { it.typeId == "mechFactory" }
                assertEquals(1f, factory.constructionProgress)
                session.select(factory.position)
                assertEquals(listOf("builder", "mechGun"), session.productionActions.map { it.typeId })
                val upgrade = session.actionMenu.upgrades.single()
                assertEquals("upgrade_t2", upgrade.profile.actionKey)
                assertEquals("Mech factory T2", upgrade.title)
                assertEquals(4000L, upgrade.profile.price)
                assertIs<ActionResult.Accepted>(session.upgrade("upgrade_t2"))
                assertEquals(11000L, session.observation.balance)
                repeat(2600) { session.tick() }
                val converted = session.observation.units.single { it.unitId == factory.unitId }
                assertEquals("mechFactoryT2", converted.typeId)
                assertEquals(factory.position, converted.position)
                assertEquals(factory.unitId, session.selectedId)
                assertTrue(session.selectedUpgrades.isEmpty())
                assertEquals(listOf("builder", "mechGun", "mechHeavyMissile"), session.productionActions.map { it.typeId })
                val heavyCard = session.productionActions.single { it.typeId == "mechHeavyMissile" }
                assertEquals("Heavy missile mech", heavyCard.title)
                assertEquals(8500L, heavyCard.price)
                assertIs<ActionResult.Accepted>(session.produce(heavyCard.typeId))
                assertEquals(2500L, session.observation.balance)
                repeat(1900) { session.tick() }
                val product = session.observation.units.single { it.typeId == "mechHeavyMissile" && it.ownerPlayerId == "p1" }
                assertEquals(4, product.mechPose!!.legs.size)
                assertTrue(product.position.y > 200.0)
                session.select(product.position)
                assertEquals(product.unitId, session.selectedId)
                assertIs<ActionResult.Accepted>(session.move(Position(410.0, 270.0)))
                var cliffSpeed = false
                repeat(400) {
                    session.tick()
                    val moving = session.observation.units.single { it.unitId == product.unitId }
                    if (moving.position.x in 342.0..378.0 && kotlin.math.abs(moving.speed - .427) < 1e-10) cliffSpeed = true
                }
                assertTrue(cliffSpeed, "Normal produced heavy must use the reviewed large-cliff speed")
                assertTrue(session.observation.units.single { it.unitId == product.unitId }.position.x > 400.0)
                assertIs<ActionResult.Accepted>(session.move(Position(270.0, 270.0)))
                repeat(400) { session.tick() }
                assertTrue(session.observation.units.single { it.unitId == product.unitId }.position.x < 300.0)
                assertIs<ActionResult.Accepted>(session.stop())
                assertNull(session.observation.units.single { it.unitId == product.unitId }.moveTarget)
            }
            SandboxSession(load(format, "builder").scenario).use { reopened ->
                assertNull(reopened.selectedId)
                assertEquals(16000L, reopened.observation.balance)
                assertEquals(listOf("builder"), reopened.observation.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
                assertTrue(reopened.observation.factoryQueues.isEmpty())
                assertTrue(reopened.observation.upgradeQueues.isEmpty())
            }
        }
    }

    private fun load(format: String, choice: String): LoadedSandbox {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val root = fixtures.resolve("heavy-mech-production/$format")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(pack, choice))
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The actual heavy production scene did not load")
        }
    }
}
