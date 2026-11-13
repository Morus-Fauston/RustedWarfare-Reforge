package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

internal object BunkerSandboxScene {
    fun load(format: String = "json"): LoadedSandbox {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("bunker-production/$format")
        val content = assertIs<ContentResult.Success<LoadedContentPack>>(
            ContentLoader.loadContentPack(root.resolve("production-package"))).value
        return SandboxLoader().use { loader ->
            loader.requestPack(root, ContentPackChoice(content, "commandCenter"))
            val deadline = System.nanoTime() + 5_000_000_000L
            var result: LoadedSandbox? = null
            while (result == null && System.nanoTime() < deadline) {
                result = loader.pollPrepared()?.getOrThrow()
                if (result == null) Thread.sleep(5)
            }
            assertNotNull(result)
        }
    }
}

class BunkerSandboxTest {
    @Test fun the_normal_content_baseline_accepts_the_qualified_92_pixel_deployed_shield_layout() {
        val loaded = BunkerSandboxScene.load()
        val images = loaded.scenario.unitDefinitions.mapValues { it.value.imageBindings().visualImages() }
        val visuals = PreparedVisualAssets(VisualAssetSelection(), "owned-bunker", "owned-bunker", images, emptyMap()) {}
        val base = ResourceImagePreparation.baseLayer(visuals, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
        val id = ResourceId.parse("reforge:units/mechbunkerdeployed/shield")
        val source = base.images.getValue(id)
        val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:qualified-shield","name":"Owned layout check","description":"Qualified logical layout only","icon":"$id"}""".toByteArray(), "pack.json")
        val layer = ResourceImageLayer(metadata, mapOf(id to PreparedResourceImage(source.path,
            ResourceImageDeclaration("shield.png", 92, 92, 1), 92, 92)))
        val resolved = ResourceImageComposer.compose(base, listOf(layer))
        assertEquals("owned:qualified-shield", resolved.providers.getValue(id))
    }

    @Test fun three_formats_start_normally_then_pay_for_builder_factory_and_bunker_before_deploying_and_recovering_the_same_selection() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = BunkerSandboxScene.load(format)
            assertEquals("p009-bunker-production", loaded.scenario.scenarioId)
            assertEquals(listOf("commandCenter"), loaded.scenario.units.filter { it.ownerPlayerId == "p1" }.map { it.typeId })
            assertTrue(NativeActionPanel.supports(loaded.scenario.scenarioId))
            SandboxSession(loaded.scenario).use { session ->
                fun until(limit: Int, ready: () -> Boolean) {
                    repeat(limit) { if (!ready()) session.tick() }
                    assertTrue(ready(), "$format: ${session.feedback}")
                }
                session.select(Position(130.0, 130.0))
                assertIs<ActionResult.Accepted>(session.produce("builder"))
                until(600) { session.observation.units.any { it.typeId == "builder" } }
                session.select(session.observation.units.single { it.typeId == "builder" }.position)
                assertIs<ActionResult.Accepted>(session.build("mechFactory", Position(270.0, 150.0)))
                until(3000) { session.observation.units.any { it.typeId == "mechFactory" && it.constructionProgress == 1f } }
                session.select(Position(270.0, 150.0))
                assertTrue(session.productionActions.any { it.typeId == "mechBunker" && it.price == 4500L })
                assertIs<ActionResult.Accepted>(session.produce("mechBunker"))
                until(1700) { session.observation.units.any { it.typeId == "mechBunker" && it.ownerPlayerId == "p1" } }
                repeat(100) { session.tick() }
                val born = session.observation.units.single { it.typeId == "mechBunker" && it.ownerPlayerId == "p1" }
                session.select(born.displayedBodyPosition())
                assertEquals("Mobile turret", session.actionMenu.selectedTitle)
                assertEquals("Deploy", session.actionMenu.unitActions.single().title)
                assertIs<ActionResult.Accepted>(session.stop())
                assertIs<ActionResult.Accepted>(session.unitAction("deploy"))
                session.tick()
                assertTrue(session.actionMenu.unitActions.single().tooltip.contains("0%"))
                assertTrue(session.actionMenu.unitActions.single().tooltip.contains("Movement locked"))
                until(167) { session.observation.units.any { it.unitId == born.unitId && it.typeId == "mechBunkerDeployed" } }
                assertEquals("Deployed turret", session.actionMenu.selectedTitle)
                assertEquals("Undeploy", session.actionMenu.unitActions.single().title)
                assertIs<ActionResult.Accepted>(session.unitAction("undeploy"))
                until(102) { session.observation.units.any { it.unitId == born.unitId && it.typeId == "mechBunker" } }
                assertEquals("Mobile turret", session.actionMenu.selectedTitle)
            }
        }
    }
}
