package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ResourceImagePackMenuSessionTest {
    private val body = "reforge:units/c_tank/body"

    @Test
    fun restoring_an_old_valid_stack_rechecks_the_current_content_and_does_not_rewrite_it() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val incompatible = fixture.create("owned:old-layout", listOf(OwnPackImage("reforge:units/amber_scout/body", frames = 1)))
            val first = fixture.backend()
            val saved = first.initialSelection.copy(packs = ResourcePackSelection(listOf(incompatible.catalogEntry), listOf(incompatible.catalogEntry.id)))
            first.applyPrepared(first.prepare(saved))
            val before = Files.readAllBytes(fixture.settings)
            val root = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
            val content = assertIs<com.rustedwarfare.reforge.core.content.ContentResult.Success<com.rustedwarfare.reforge.core.content.LoadedContentPack>>(
                com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(root.resolve("SimulationTest/public/inputs/content-pack-sandboxes/json/package"))).value
            val choice = ContentPackChoice(content, "amber_scout")
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                await(state)
                state.restoreSaved(choice); await(state)
                assertFalse(state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:old-layout"), state.error)
                assertEquals(saved.packs.enabled, state.selection.packs.enabled, "Failed restore must not silently disable an active pack")
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                state.setEnabled(incompatible.catalogEntry.id, false)
                state.apply(choice); await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(emptyList(), fixture.store.load().packs.enabled)
            }
        }
    }

    @Test
    fun initial_preview_uses_the_common_resolver_without_saving_or_reading_a_disabled_original_source() {
        ResourceImagePackApplicationFixture().use { fixture ->
            var originalReads = 0
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), prepareOriginal = {
                    originalReads++
                    throw IllegalArgumentException("Owned original preview rejection")
                })
            fixture.store.save(backend.initialSelection)
            val before = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend).use { state ->
                assertTrue(state.ensurePreviews())
                await(state)
                assertEquals(0, originalReads)
                assertNotNull(state.rows.single { it.id == "reforge:base" }.icon)
                assertTrue(state.canStart, state.error)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                assertFalse(state.ensurePreviews(), "Completed previews must not restart every frame")
                state.setEnabled("reforge:original-android-1.15-176", true)
                assertFalse(state.ensurePreviews(), "Editing a candidate must still require explicit Apply")
                state.apply(); await(state)
                assertEquals(1, originalReads)
                assertFalse(state.canStart)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test
    fun imports_do_not_enable_or_save_but_explicit_order_apply_and_restore_use_real_packages() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val amber = fixture.create("owned:amber", listOf(OwnPackImage(body)))
            val blue = fixture.create("owned:blue", listOf(OwnPackImage(body, color = 0xff2040c0.toInt())))
            val actual = fixture.backend()
            val uiThread = Thread.currentThread()
            val backend = object : VisualAssetSettings by actual {
                override fun checkImagePack(path: Path): PreparedImagePack {
                    assertNotEquals(uiThread, Thread.currentThread(), "Import must leave the UI thread")
                    return actual.checkImagePack(path)
                }
            }
            fixture.store.save(backend.initialSelection)
            val before = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend).use { state ->
                state.importImagePack(amber.manifest); await(state)
                assertEquals(emptyList(), state.selection.packs.enabled)
                assertEquals(amber.catalogEntry, state.selection.packs.catalog.single())
                assertTrue(state.canStart)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                state.importImagePack(blue.manifest); await(state)
                assertEquals(blue.metadata.name, state.rows.single { it.id == blue.catalogEntry.id }.metadata?.name)
                assertNotNull(state.rows.single { it.id == blue.catalogEntry.id }.icon)
                var menu: Map<String, Any?> = emptyMap()
                ClientEvidence { kind, fields -> assertEquals("visual-assets-menu", kind); menu = fields }.recordVisualAssetMenu(state)
                assertEquals(emptyList<String>(), menu["enabledIds"])
                assertEquals(listOf("owned:amber", "owned:blue"), menu["catalogIds"])
                val entries = assertIs<List<*>>(menu["rows"]).map { assertIs<Map<*, *>>(it) }
                assertEquals(blue.metadata.name, entries.single { it["id"] == "owned:blue" }["name"])
                assertEquals(true, entries.last()["enabled"])
                assertEquals(true, menu["canStart"])
                assertTrue(state.ensurePreviews())
                await(state)
                assertTrue(state.hasUnsavedCatalog, "Generating previews must not mark imported entries as saved")
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                state.setEnabled(amber.catalogEntry.id, true)
                state.setEnabled(blue.catalogEntry.id, true)
                assertEquals(listOf("owned:blue", "owned:amber"), state.selection.packs.enabled)
                state.movePack("owned:amber", -1)
                assertEquals(listOf("owned:amber", "owned:blue"), state.selection.packs.enabled)
                assertFalse(state.canStart)
                state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(state.selection, fixture.store.load())
                val saved = Files.readAllBytes(fixture.settings)
                state.setEnabled("owned:amber", false)
                state.restoreSaved(); await(state)
                assertEquals(listOf("owned:amber", "owned:blue"), state.selection.packs.enabled)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings), "Restore validates and selects, without rewriting the saved file")
                assertEquals("reforge:base", state.rows.last().id)
                assertTrue(state.rows.last().enabled)
                assertFailsWith<IllegalArgumentException> { state.setEnabled("reforge:base", false) }
            }
        }
    }

    @Test
    fun unrelated_failed_import_keeps_the_good_combination_but_refresh_disables_the_changed_item() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val first = fixture.create("owned:one", listOf(OwnPackImage(body)))
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                state.importImagePack(first.manifest); await(state)
                state.setEnabled("owned:one", true); state.apply(); await(state)
                val selected = state.selection
                val saved = Files.readAllBytes(fixture.settings)
                state.importImagePack(fixture.root.resolve("unavailable/pack.json")); await(state)
                assertNotNull(state.diagnostic)
                assertEquals(selected, state.selection)
                assertTrue(state.canStart, "An unrelated import failure must not invalidate the already applied combination")
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                val duplicate = fixture.root.resolve("duplicate")
                Files.walk(first.manifest.parent).use { paths -> paths.forEach { source ->
                    val target = duplicate.resolve(first.manifest.parent.relativize(source)).normalize()
                    require(target.startsWith(duplicate))
                    if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
                } }
                state.importImagePack(duplicate); await(state)
                assertTrue(state.diagnostic.orEmpty().contains("Duplicate"), state.diagnostic)
                assertEquals(selected, state.selection)
                assertTrue(state.canStart)
                val changed = fixture.create("owned:one", listOf(OwnPackImage(body, color = 0xff2060d0.toInt())))
                state.importImagePack(changed.manifest); await(state)
                assertEquals(emptyList(), state.selection.packs.enabled)
                assertEquals(changed.catalogEntry, state.selection.packs.catalog.single())
                assertFalse(state.canStart)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                state.setEnabled("owned:one", true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val goodFile = Files.readAllBytes(fixture.settings)
                Files.writeString(changed.manifest.parent.resolve("image0.png"), "Now invalid")
                state.importImagePack(changed.manifest); await(state)
                assertFalse(state.canStart, "Failure of the currently enabled source must remain blocking")
                assertEquals(listOf("owned:one"), state.selection.packs.enabled)
                assertContentEquals(goodFile, Files.readAllBytes(fixture.settings))
                state.setEnabled("owned:one", false); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
            }
        }
    }

    @Test
    fun startup_checks_saved_catalog_without_rewriting_and_disabled_missing_sources_do_not_block_base() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val good = fixture.create("owned:good", listOf(OwnPackImage(body)))
            val missing = fixture.create("owned:missing", listOf(OwnPackImage(body)))
            val selected = fixture.backend().initialSelection.copy(packs = ResourcePackSelection(
                listOf(good.catalogEntry, missing.catalogEntry), listOf("owned:good")))
            fixture.store.save(selected)
            val before = Files.readAllBytes(fixture.settings)
            Files.delete(missing.manifest.parent.resolve("image0.png"))
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                assertTrue(state.busy)
                await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(good.metadata.name, state.rows.single { it.id == "owned:good" }.metadata?.name)
                assertNotNull(state.rows.single { it.id == "owned:missing" }.error)
                assertFalse(state.rows.single { it.id == "owned:missing" }.canEnable)
                assertFailsWith<IllegalStateException> { state.setEnabled("owned:missing", true) }
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
            }
            fixture.store.save(selected.copy(packs = ResourcePackSelection(selected.packs.catalog, listOf("owned:missing"))))
            val badSaved = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                await(state)
                assertFalse(state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:missing"))
                assertEquals(listOf("owned:missing"), state.selection.packs.enabled)
                assertContentEquals(badSaved, Files.readAllBytes(fixture.settings))
                state.setEnabled("owned:missing", false); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
            }
        }
    }

    @Test
    fun cancelled_late_import_cannot_add_enable_or_save_over_a_newer_import() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val old = fixture.create("owned:old", listOf(OwnPackImage(body)))
            val latest = fixture.create("owned:latest", listOf(OwnPackImage(body)))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val actual = fixture.backend()
            val backend = object : VisualAssetSettings by actual {
                override fun checkImagePack(path: Path): PreparedImagePack {
                    if (path == old.manifest) {
                        entered.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* late result deliberately ignores cancellation */ }
                        return old
                    }
                    return actual.checkImagePack(path)
                }
            }
            fixture.store.save(backend.initialSelection)
            val before = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend).use { state ->
                try {
                    state.importImagePack(old.manifest)
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    state.cancel()
                    state.importImagePack(latest.manifest)
                    release.countDown(); await(state)
                    assertEquals(listOf("owned:latest"), state.selection.packs.catalog.map { it.id })
                    assertEquals(emptyList(), state.selection.packs.enabled)
                    assertContentEquals(before, Files.readAllBytes(fixture.settings))
                } finally { release.countDown() }
            }
        }
    }

    private fun await(state: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) { if (state.poll()) return; Thread.sleep(2) }
        error("Resource menu worker did not finish")
    }
}
