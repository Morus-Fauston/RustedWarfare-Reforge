package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.LoadedContentSet
import com.rustedwarfare.reforge.core.sim.ActionResult
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class EngineOnlyPackTest {
    @TempDir lateinit var temporary: Path

    private fun sample(name: String): Path =
        Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("engine-only-packs/$name")

    private fun load(path: Path): LoadedContentPack {
        val result = ContentLoader.loadContentPack(path)
        return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
    }

    @Test fun clean_install_plays_package_opening_through_construction_production_and_shield_combat() {
        val pack = load(sample("field-base"))
        val choice = ContentPackChoice(pack, "field_worker")
        val root = temporary.resolve("no-default-content")
        val scene = contentPackScenario(root, choice)
        assertFalse(Files.exists(root))
        assertEquals("field-base", scene.scenarioId)
        assertEquals(listOf("worker", "rival"), scene.units.map { it.unitId })
        assertEquals(3500L, scene.economyContract?.initialBalances?.get("p1"))
        SandboxSession(scene).use { session ->
            assertEquals(3500L, session.observation.balance)
            session.select(Position(110.0, 210.0))
            assertEquals(listOf("field_factory"), session.constructionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.build("field_factory", Position(190.0, 210.0)))
            repeat(1200) { session.tick() }
            val factory = session.observation.units.single { it.typeId == "field_factory" }
            assertEquals(1f, factory.constructionProgress)
            assertEquals(2800L, session.observation.balance)
            session.select(factory.position)
            assertEquals(listOf("field_tank"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("field_tank"))
            repeat(750) { session.tick() }
            assertEquals(2450L, session.observation.balance)
            val tank = session.observation.units.single { it.ownerPlayerId == "p1" && it.typeId == "field_tank" }
            assertEquals(60f, tank.shield?.maximum)
            val rival = session.observation.units.single { it.unitId == "rival" }
            session.select(tank.position)
            assertIs<ActionResult.Accepted>(session.command(rival.position))
            var absorbed = false
            repeat(500) {
                if (!absorbed) {
                    session.tick()
                    absorbed = session.observation.units.any { unit ->
                        unit.unitId in setOf(tank.unitId, rival.unitId) && unit.shield?.let { it.current < it.maximum } == true
                    }
                }
            }
            assertTrue(absorbed, "Actual opposing fire should drain the declared shield")
        }
    }

    @Test fun extension_requires_base_and_replaces_runtime_id_without_changing_the_opening() {
        val base = load(sample("field-base"))
        val extension = load(sample("field-extension"))
        val missing = assertIs<ContentResult.Failure>(ContentLoader.composeContentPacks(listOf(extension)))
        assertEquals(ContentErrorCode.BAD_REFERENCE, missing.error.code)
        assertEquals("dependencies", missing.error.field)
        val result = ContentLoader.composeContentPacks(listOf(extension, base))
        val combined = assertIs<ContentResult.Success<LoadedContentSet>>(result, result.toString()).value
        val origin = combined.provenance.getValue("field_tank")
        assertEquals("field-extension", origin.packageId)
        assertEquals(listOf("field-base"), origin.replacedPackageIds)
        assertEquals("field-extension", combined.provenance.getValue("field_escort").packageId)
        assertEquals("field-extension", combined.provenance.getValue("field_factory").packageId)
        val scene = contentPackScenario(temporary, ContentPackChoice(combined, "field_escort"))
        assertEquals("field-base", scene.scenarioId)
        assertEquals(95f, scene.unitDefinitions.getValue("field_tank").shield?.maximum)
        assertEquals(120f, scene.unitDefinitions.getValue("field_escort").shield?.maximum)
        assertEquals("field_tank", scene.units.single { it.unitId == "rival" }.typeId)
        SandboxSession(scene).use { session ->
            session.select(Position(110.0, 210.0))
            assertIs<ActionResult.Accepted>(session.build("field_factory", Position(190.0, 210.0)))
            repeat(1200) { session.tick() }
            val factory = session.observation.units.single { it.typeId == "field_factory" }
            session.select(factory.position)
            assertEquals(listOf("field_tank", "field_escort"), session.productionActions.map { it.typeId })
            assertIs<ActionResult.Accepted>(session.produce("field_escort"))
            repeat(750) { session.tick() }
            assertEquals(2450L, session.observation.balance)
            assertEquals(120f, session.observation.units.single { it.typeId == "field_escort" }.shield?.maximum)
        }
        val duplicate = assertIs<ContentResult.Failure>(ContentLoader.composeContentPacks(listOf(base, extension, extension)))
        assertEquals(ContentErrorCode.IDENTITY_CONFLICT, duplicate.error.code)
    }

    @Test fun opening_tracks_the_exact_map_and_all_package_owned_atlas_inputs() {
        val root = temporary.resolve("base")
        val source = sample("field-base")
        Files.walk(source).use { files ->
            files.forEach { input ->
                val output = root.resolve(source.relativize(input))
                if (Files.isDirectory(input)) Files.createDirectories(output) else Files.copy(input, output)
            }
        }
        val map = root.resolve("maps/field.tmx")
        val tmx = Files.readString(map)
        val inline = Regex("<tileset[^>]*>.*?</tileset>").find(tmx)!!.value
        val tiles = root.resolve("maps/field.tsx")
        Files.writeString(tiles, inline.replace(" firstgid=\"1\"", ""))
        Files.writeString(map, tmx.replace(inline, "<tileset firstgid=\"1\" source=\"field.tsx\"/>"))
        val pack = load(root)
        val atlas = root.resolve("assets/terrain.png")
        assertTrue(pack.sources.map { it.path }.containsAll(listOf(map, tiles, atlas)))
        val checked = assertNotNull(pack.opening)
        assertEquals(atlas, checked.map.tileSets.single().imagePath)
        Files.writeString(tiles, Files.readString(tiles) + "\n")
        assertIs<ContentResult.Failure>(pack.verifyStable())
        Files.copy(atlas, temporary.resolve("outside.png"))
        Files.writeString(tiles, Files.readString(tiles).replace("../assets/terrain.png", "../../outside.png"))
        val outside = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
        assertEquals(ContentErrorCode.BAD_REFERENCE, outside.error.code)

        Files.writeString(tiles, Files.readString(tiles).replace("../../outside.png", "../assets/terrain.png"))
        // A large tail after IHDR is irrelevant to the ordinary geometry check.
        java.io.RandomAccessFile(atlas.toFile(), "rw").use { it.setLength(17L * 1024 * 1024) }
        assertIs<ContentResult.Success<*>>(ContentLoader.loadMap(map))
        val oversized = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
        assertEquals("opening.map", oversized.error.field)
    }
}
