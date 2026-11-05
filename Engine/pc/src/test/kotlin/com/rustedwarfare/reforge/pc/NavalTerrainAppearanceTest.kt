package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class NavalTerrainAppearanceTest {
    @Test fun real_naval_loader_and_default_settings_bind_the_owned_shore_atlas_without_reusing_the_land_only_layout() {
        val (loaded, choice) = load()
        val user = Files.createTempDirectory("naval-terrain-settings-")
        val backend = PcVisualAssetSettings(VisualAssetSettingsStore(user.resolve("settings.properties")), user.resolve("cache"), user,
            WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), user))
        val prepared = backend.prepareForContent(backend.initialSelection, choice)
        val atlas = SandboxRenderResources.from(loaded.scenario).withVisuals(prepared).tileSets.single()
        assertEquals("naval-ground-40", atlas.supportedLayout())
        val source = loaded.scenario.map.tileSets.single().imagePath
        assertNotEquals(source, atlas.imagePath, "A published scene must use the checked fixed generation, not the mutable map source")
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(atlas.imagePath))
        val image = assertNotNull(ImageIO.read(atlas.imagePath.toFile()))
        assertEquals(100 to 160, image.width to image.height)
        assertNotEquals(image.getRGB(10, 10), image.getRGB(30, 10), "Dry and water cells must be visibly distinct")
        assertNotEquals(image.getRGB(30, 10), image.getRGB(70, 10), "The bridge over water must retain a separate mark")
        assertTrue(loaded.scenario.map.waterBlocking.isBlocked(5, 5))
        assertFalse(loaded.scenario.map.waterBlocking.isBlocked(25, 5))
    }

    @Test fun explicit_checked_naval_atlas_override_preserves_logical_sampling_and_rejects_multi_frame_terrain() {
        val (loaded, choice) = load()
        ResourceImagePackApplicationFixture().use { fixture ->
            val id = ResourceId.parse("reforge:terrain/naval-ground-40")
            val backend = fixture.backend()
            fun prepare(pack: PreparedImagePack) = backend.prepareForContent(backend.initialSelection.copy(
                packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id))), choice)
            val good = fixture.create("owned:naval-atlas", listOf(OwnPackImage(id.value, frames = 1, width = 100, height = 160, scale = 2)))
            val prepared = prepare(good)
            val atlas = SandboxRenderResources.from(loaded.scenario).withVisuals(prepared).tileSets.single()
            assertEquals(assertNotNull(prepared.resolvedImages).images.getValue(id).path, atlas.imagePath)
            assertNotEquals(loaded.scenario.map.tileSets.single().imagePath, atlas.imagePath)
            assertEquals(20 to 20, atlas.tileWidth to atlas.tileHeight)
            assertEquals(40 to 40, atlas.pixelTileWidth to atlas.pixelTileHeight)
            val bad = fixture.create("owned:naval-multi-frame", listOf(OwnPackImage(id.value, frames = 2, width = 100, height = 160, scale = 2)))
            val error = assertFailsWith<IllegalArgumentException> {
                val rejected = prepare(bad)
                SandboxRenderResources.from(loaded.scenario).withVisuals(rejected)
            }.message.orEmpty()
            assertTrue(error.startsWith("RESOURCE_PACK_COMBINATION_INVALID"), error)
            assertTrue(error.contains(id.value) && error.contains("expected=100x160/1") && error.contains("actual=100x160/2"), error)
        }
    }

    private fun load(): Pair<LoadedSandbox, ContentPackChoice> {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val folder = fixtures.resolve("naval-production/json")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(folder.resolve("production-package"))).value
        val choice = ContentPackChoice(pack, "builder")
        val loaded = SandboxLoader().use { loader ->
            loader.requestPack(folder, choice)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                loader.pollPrepared()?.let { return@use it.getOrThrow() }
                Thread.sleep(5)
            }
            error("The real naval scene did not load")
        }
        return loaded to choice
    }
}
