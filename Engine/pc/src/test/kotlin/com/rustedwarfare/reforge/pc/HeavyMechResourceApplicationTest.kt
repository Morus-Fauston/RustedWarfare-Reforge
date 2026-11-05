package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class HeavyMechResourceApplicationTest {
    @Test fun real_hd_png_import_apply_and_final_loader_keep_the_named_turret_size_and_failed_apply_keeps_the_saved_generation() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val id = ResourceId.parse("reforge:units/mechheavymissile/weapons/smalllaser/turret")
            val good = fixture.create("owned:heavy-hd", listOf(OwnPackImage(id.value, frames = 1, width = 11, height = 24)))
            val bad = fixture.create("owned:heavy-bad", listOf(OwnPackImage(id.value, frames = 1, width = 12, height = 24)))
            val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("heavy-mech-production/json")
            val content = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val choice = ContentPackChoice(content, "mechHeavyMissile")
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(good.catalogEntry), listOf(good.catalogEntry.id)))
            VisualAssetSettingsSession(backend).use { state ->
                state.edit(selected); state.apply(choice); await(state)
                assertTrue(state.canStart, state.error)
                val saved = Files.readAllBytes(fixture.settings)
                val prepared = backend.prepareForContent(selected, choice)
                val actual = assertNotNull(prepared.resolvedImages).images.getValue(id)
                assertEquals("owned:heavy-hd", prepared.resolvedImages!!.providers.getValue(id))
                assertEquals(22, ImageIO.read(actual.path.toFile()).width)
                assertEquals(48, ImageIO.read(actual.path.toFile()).height)
                val cachedBytes = Files.readAllBytes(actual.path)
                SandboxLoader().use { loader ->
                    loader.requestPack(root, choice) { backend.prepareForContent(selected, choice) }
                    val loaded = await(loader).getOrThrow()
                    assertEquals(content.sources, loaded.contentSources)
                    val appearance = loaded.renderResources.units.getValue("mechHeavyMissile")
                    val small = appearance.weaponImages.getValue("smallLaser")
                    assertEquals(actual.path, small.imagePath)
                    assertEquals(ImageLogicalSize(11, 24), small.logicalSize)
                    assertEquals(1.0, small.imageScale)
                    val parent = appearance.weaponImages.getValue("1")
                    assertEquals(content.units.getValue("mechHeavyMissile").combat!!.turretImagePath, parent.imagePath)
                    assertEquals(ImageLogicalSize(35, 28), parent.logicalSize)
                    assertEquals(setOf("smallLaser", "1"), appearance.weaponImages.filterValues { !it.invisible }.keys)
                    SandboxSession(loaded.scenario).use { session ->
                        val view = session.observation
                        val unit = view.units.single { it.unitId == "a" }
                        assertEquals(4, unit.mechPose!!.legs.size)
                        assertEquals(listOf("smallLaser", "1", "2", "3"), view.weapons.filter { it.unitId == "a" }.map { it.weaponName })
                    }
                }
                val broken = selected.copy(packs = ResourcePackSelection(listOf(bad.catalogEntry, good.catalogEntry),
                    listOf(bad.catalogEntry.id, good.catalogEntry.id)))
                state.edit(broken); state.apply(choice); await(state)
                assertFalse(state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:heavy-bad"), state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                assertContentEquals(cachedBytes, Files.readAllBytes(actual.path))
                prepared.verifyForPublication()
                assertEquals(selected, fixture.store.load())
                state.edit(selected); state.apply(choice); await(state)
                assertTrue(state.canStart, state.error)
            }
        }
    }

    private fun await(state: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) { if (state.poll()) return; Thread.sleep(2) }
        error("Actual Settings worker timed out")
    }
    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) { loader.pollPrepared()?.let { return it }; Thread.sleep(2) }
        error("Actual SandboxLoader worker timed out")
    }
}
