package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path
import kotlin.test.*

class TestScenarioLaunchTest {
    private val fixtures: Path get() = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
    private val seed: Path get() = fixtures.parent.resolve("scenarios/dual-ship-production.json")

    @Test fun choosing_the_dual_ship_seed_starts_its_paired_map_pack_and_normal_builder() {
        val choice = TestScenarioChoice.open(seed)
        assertEquals("public.author-naval-chain.v1", choice.id)
        assertEquals("spbuilder", choice.content.unitId)
        SandboxLoader().use { loader ->
            loader.requestTestScenario(choice)
            val loaded = await(loader)
            assertEquals(choice.content, loaded.contentPack)
            assertEquals("public.author-naval-chain.v1", loaded.scenario.scenarioId)
            SandboxSession(loaded.scenario).use { session ->
                assertEquals(3000L, session.observation.balance)
                assertEquals(listOf("a", "b", "c"), session.observation.units.map { it.unitId })
                assertTrue(session.observation.units.all { it.typeId == "spbuilder" })
                session.select(Position(110.0, 210.0))
                assertEquals("a", session.selectedId)
                assertEquals(listOf("spdock"), session.constructionActions.map { it.typeId })
                assertIs<ActionResult.Accepted>(session.move(Position(170.0, 150.0)))
                repeat(180) { session.tick() }
                assertIs<ActionResult.Accepted>(session.build("spdock", Position(250.0, 150.0)))
                repeat(600) { session.tick() }
                assertEquals(2300L, session.observation.balance)
                assertEquals(1, session.observation.units.count { it.typeId == "spdock" && it.constructionProgress == 1f })
            }
        }
    }

    @Test fun a_changed_platform_intent_cannot_silently_disagree_with_the_core_action() {
        val copy = copiedSeed()
        val document = com.badlogic.gdx.utils.JsonReader().parse(java.nio.file.Files.readString(copy))
        document.get("intents")[1].get("position").remove("x")
        document.get("intents")[1].get("position").addChild("x", com.badlogic.gdx.utils.JsonValue(171.0))
        java.nio.file.Files.writeString(copy, document.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
        val failure = assertFailsWith<IllegalArgumentException> { TestScenarioChoice.open(copy) }
        assertTrue(failure.message.orEmpty().contains("TEST_SCENE_INTENT_MISMATCH"), failure.message)
    }

    @Test fun missing_scene_files_disable_start_and_a_repaired_choice_can_be_selected_again() {
        val copy = copiedSeed()
        val menu = TestScenarioMenu()
        val image = copy.parent.parent.resolve("inputs/author-naval-chain/json/package/units/builder.png")
        val saved = java.nio.file.Files.readAllBytes(image)
        java.nio.file.Files.write(image, byteArrayOf(1))
        menu.choose(copy)
        assertTrue(menu.selected)
        assertFalse(menu.canStart)
        assertNull(menu.choice)
        assertTrue(menu.error.orEmpty().contains("TEST_SCENE_CHANGED"), menu.error)
        java.nio.file.Files.write(image, saved)
        menu.choose(copy)
        assertTrue(menu.canStart)
        assertNull(menu.error)
        assertNotNull(menu.choice)
        java.nio.file.Files.write(image, byteArrayOf(2))
        SandboxLoader().use { loader ->
            loader.requestTestScenario(assertNotNull(menu.choice))
            assertFailsWith<IllegalArgumentException> { await(loader) }
        }
        menu.useCustomContent()
        assertFalse(menu.selected)
        assertTrue(menu.canStart)
        assertNull(menu.choice)
    }

    @Test fun a_seed_cannot_pair_a_unit_id_with_a_different_content_package_entry() {
        val copy = copiedSeed()
        val document = com.badlogic.gdx.utils.JsonReader().parse(java.nio.file.Files.readString(copy))
        val references = document.get("content").get("unitVariants")
        references[0].remove("path"); references[0].remove("sha256")
        references[0].addChild("path", com.badlogic.gdx.utils.JsonValue(references[1].getString("path")))
        references[0].addChild("sha256", com.badlogic.gdx.utils.JsonValue(references[1].getString("sha256")))
        java.nio.file.Files.writeString(copy, document.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
        val menu = TestScenarioMenu(); menu.choose(copy)
        assertFalse(menu.canStart)
        assertTrue(menu.error.orEmpty().contains("TEST_SCENE_INVALID"), menu.error)
    }

    @Test fun a_missing_paired_map_explains_the_problem_and_allows_repair() {
        val copy = copiedSeed()
        val map = copy.parent.parent.resolve("inputs/author-naval-chain/json/maps/p001-controlled.tmx")
        val saved = java.nio.file.Files.readAllBytes(map)
        java.nio.file.Files.delete(map)
        val menu = TestScenarioMenu(); menu.choose(copy)
        assertFalse(menu.canStart)
        assertTrue(menu.error.orEmpty().contains("Missing or inaccessible paired file"), menu.error)
        java.nio.file.Files.write(map, saved)
        menu.choose(copy)
        assertTrue(menu.canStart)
        assertNull(menu.error)
    }

    private fun copiedSeed(): Path {
        val home = java.nio.file.Files.createTempDirectory("owned-test-scene-").toAbsolutePath().normalize()
        val input = fixtures.resolve("author-naval-chain/json")
        java.nio.file.Files.walk(input).use { files -> files.forEach { source ->
            val target = home.resolve("inputs/author-naval-chain/json").resolve(input.relativize(source))
            if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(target)
            else java.nio.file.Files.copy(source, target)
        } }
        val target = home.resolve("scenarios/scene.json")
        java.nio.file.Files.createDirectories(target.parent)
        java.nio.file.Files.copy(seed, target)
        return target
    }

    private fun await(loader: SandboxLoader): LoadedSandbox {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it.getOrThrow() }
            Thread.sleep(2)
        }
        error("Test scenario did not finish loading")
    }
}
