package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.badlogic.gdx.utils.JsonReader
import kotlin.test.*

class PcOriginalImageExtractionTest {
    @Test fun a_failed_menu_extraction_preserves_the_current_applied_pack_and_good_settings() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            VisualAssetSettingsSession(backend).use { state ->
                state.extractOriginalImages(source); await(state)
                state.setEnabled(state.selectedPackId, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val selected = state.selection; val saved = Files.readAllBytes(fixture.settings)
                state.extractOriginalImages(fixture.root.resolve("missing PC source")); await(state)
                assertTrue(state.canStart, state.error)
                assertNull(state.error)
                assertTrue(state.diagnostic.orEmpty().contains("SOURCE_VERSION_UNSUPPORTED"), state.diagnostic)
                assertEquals(selected, state.selection)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun cancelled_or_closed_real_extraction_cannot_accept_its_deliberately_late_result_over_a_newer_menu_operation() {
        for (close in listOf(false, true)) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val latest = fixture.create("owned:latest", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
            val output = fixture.cache.resolveSibling("extracted-original-packs")
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    if (to.startsWith(output) && to.parent.fileName.toString() == "generations") {
                        worker.set(Thread.currentThread()); entered.countDown()
                        // A real filesystem boundary returns late despite the UI cancellation.
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    }
                    moveVisualAtomically(from, to, replace)
                })
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val state = VisualAssetSettingsSession(backend)
            try {
                state.extractOriginalImages(source)
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (close) state.close() else {
                    state.cancel()
                    assertTrue(state.canStart, state.error)
                    state.importImagePack(latest.manifest)
                }
                release.countDown()
                if (close) {
                    worker.get().join(5000)
                    assertFalse(worker.get().isAlive)
                    assertFalse(state.poll())
                    assertTrue(state.selection.packs.catalog.isEmpty())
                } else {
                    await(state)
                    assertEquals(listOf("owned:latest"), state.selection.packs.catalog.map { it.id })
                    assertEquals("owned:latest", state.selectedPackId)
                    assertTrue(state.canStart, state.error)
                }
                assertTrue(state.selection.packs.enabled.isEmpty())
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            } finally {
                release.countDown(); state.close(); worker.get()?.join(5000)
            }
        }
    }

    @Test fun actual_menu_extracts_on_the_worker_and_accepts_a_disabled_unsaved_pack_then_preserves_a_repeated_enabled_cache_hit() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val before = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend).use { state ->
                assertTrue(state.supportsOriginalImageExtraction)
                state.extractOriginalImages(source)
                assertTrue(state.busy); assertFalse(state.canStart)
                assertEquals("Extracting verified PC images...", state.operationLabel)
                await(state)
                val entry = state.selection.packs.catalog.single()
                assertEquals(entry.id, state.selectedPackId)
                assertTrue(state.selection.packs.enabled.isEmpty())
                assertTrue(state.hasUnsavedCatalog)
                assertFalse(state.hasUnappliedChanges)
                assertTrue(state.canStart, state.error)
                assertNotNull(state.rows.single { it.id == entry.id }.icon)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                state.setEnabled(entry.id, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val selected = state.selection
                val saved = Files.readAllBytes(fixture.settings)
                state.extractOriginalImages(source); await(state)
                assertEquals(selected, state.selection)
                assertEquals(listOf(entry), state.selection.packs.catalog)
                assertFalse(state.hasUnsavedCatalog)
                assertFalse(state.hasUnappliedChanges)
                assertTrue(state.canStart, state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun failure_switching_ready_keeps_the_previous_complete_generation_and_saved_settings_unchanged() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val first = backend.extractOriginalImagePack(source)
            val ready = first.manifest.parent.parent.parent.resolve("ready.json")
            val oldReady = Files.readAllBytes(ready)
            val pngPath = source.resolve("assets/tank.png")
            val changed = ImageIO.read(pngPath.toFile()).also { it.setRGB(0, 0, 0xff703080.toInt()) }
            assertTrue(ImageIO.write(changed, "png", pngPath.toFile()))
            val revised = PcOriginalImageProfile(profile.id, profile.jarBytes, profile.jarSha256, profile.images.map {
                if (it.path == "assets/tank.png") it.copy(bytes = Files.size(pngPath).toInt(), sha256 = visualHash(Files.readAllBytes(pngPath))) else it
            })
            var attemptedReadySwitch = false
            val failing = PcOriginalImageExtractor(fixture.cache, revised) { from, to, replace ->
                if (to == ready) { attemptedReadySwitch = true; throw java.io.IOException("Owned ready-switch rejection") }
                moveVisualAtomically(from, to, replace)
            }
            val error = assertFailsWith<java.io.IOException> { failing.extract(source) }
            assertTrue(error.message.orEmpty().contains("Owned ready-switch rejection"), error.message)
            assertTrue(attemptedReadySwitch)
            assertContentEquals(oldReady, Files.readAllBytes(ready))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            first.verify(); first.verifySources()
        }
    }

    @Test fun interruption_and_source_identity_change_before_commit_never_publish_a_ready_package() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val output = fixture.cache.resolveSibling("extracted-original-packs")
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<java.util.concurrent.CancellationException> { PcOriginalImageExtractor(fixture.cache, profile).extract(source) }
            } finally { Thread.interrupted() }
            assertTrue(Files.notExists(output))
            val jar = source.resolve("game-lib.jar")
            val changed = Files.readAllBytes(jar).also { it[0] = (it[0].toInt() xor 1).toByte() }
            var reachedGenerationCommit = false
            val changing = PcOriginalImageExtractor(fixture.cache, profile) { from, to, replace ->
                if (to.startsWith(output) && to.parent.fileName.toString() == "generations") {
                    reachedGenerationCommit = true; Files.write(jar, changed)
                }
                moveVisualAtomically(from, to, replace)
            }
            val error = assertFailsWith<IllegalArgumentException> { changing.extract(source) }
            assertTrue(reachedGenerationCommit)
            assertTrue(error.message.orEmpty().contains("SOURCE_VERSION_UNSUPPORTED"), error.message)
            assertTrue(error.message.orEmpty().contains("changed before publication"), error.message)
            assertTrue(Files.notExists(output.resolve(profile.id).resolve("ready.json")))
            assertContentEquals(changed, Files.readAllBytes(jar))
        }
    }

    @Test fun repeated_extraction_reuses_a_complete_fixed_package_but_rebuilds_corrupted_output_without_overwriting_the_old_generation() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            val first = backend.extractOriginalImagePack(source)
            val hit = backend.extractOriginalImagePack(source)
            assertEquals(first.catalogEntry, hit.catalogEntry, "Repeated extraction must reuse the same checked ordinary package")
            val proof = JsonReader().parse(Files.readString(first.manifest.parent.resolve("extraction.json")))
            val changed = first.manifest.parent.resolve(proof.get("originalImages").first { it.getString("format") == "PNG" }.getString("renderableFile"))
            val corruption = "Owned intentional generated-output corruption".toByteArray()
            Files.write(changed, corruption)
            val rebuilt = backend.extractOriginalImagePack(source)
            assertNotEquals(first.manifest, rebuilt.manifest)
            assertNotEquals(first.catalogEntry.id, rebuilt.catalogEntry.id)
            assertContentEquals(corruption, Files.readAllBytes(changed), "Repair must preserve the failed old generation")
            rebuilt.verify(); rebuilt.verifySources()
            first.verify()
            assertFailsWith<IllegalArgumentException> { first.verifySources() }
            assertContentEquals(Files.readAllBytes(source.resolve("assets/tank.png")),
                Files.readAllBytes(rebuilt.manifest.parent.resolve("original/assets/tank.png")))
        }
    }

    @Test fun qualified_static_package_keeps_existing_aliases_and_its_static_layout_while_a_copy_without_certificate_has_no_layout_authority() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, raw) = ownedInstallation(fixture.root)
            val profile = staticProfile(raw)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            val pack = backend.extractOriginalImagePack(source)
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = backend.prepare(selection)
            val cTank = ResourceId.parse("reforge:units/c_tank/body")
            val demoTank = ResourceId.parse("reforge:units/demo_tank/body")
            assertEquals(8, assertNotNull(prepared.resolvedImages).images.getValue(cTank).declaration.frameWidth)
            assertEquals(6, assertNotNull(prepared.resolvedImages).images.getValue(cTank).declaration.frameHeight)
            assertEquals(1, prepared.units.getValue("c_tank").totalFrames)
            assertEquals(pack.metadata.id.value, assertNotNull(prepared.resolvedImages).providers[demoTank])
            val manifest = JsonReader().parse(Files.readString(pack.manifest.parent.resolve("extraction.json")))
            assertEquals(1, manifest.getInt("mappedOutputFiles")); assertEquals(2, manifest.getInt("mappedResourceImages"))
            prepared.verifyForPublication()
            val certifiedCopy = fixture.root.resolve("complete-certified-copy")
            Files.walk(pack.manifest.parent).use { paths -> paths.forEach { path ->
                val target = certifiedCopy.resolve(pack.manifest.parent.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val certified = backend.checkImagePack(certifiedCopy)
            val copiedPrepared = backend.prepare(selection.copy(packs = ResourcePackSelection(listOf(certified.catalogEntry), listOf(certified.metadata.id.value))))
            assertEquals(1, copiedPrepared.units.getValue("c_tank").totalFrames)
            copiedPrepared.verifyForPublication()
            val copy = fixture.root.resolve("ordinary-copy")
            Files.walk(pack.manifest.parent).use { paths -> paths.forEach { path ->
                if (path.fileName.toString() == "extraction.json") return@forEach
                val target = copy.resolve(pack.manifest.parent.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val ordinary = backend.checkImagePack(copy)
            val error = assertFailsWith<IllegalArgumentException> { backend.prepare(selection.copy(packs =
                ResourcePackSelection(listOf(ordinary.catalogEntry), listOf(ordinary.metadata.id.value)))) }
            assertTrue(error.message.orEmpty().contains("Layout differs"), error.message)
        }
    }

    @Test fun changing_static_pixels_and_forging_the_certificate_hashes_still_rejects_apply_and_keeps_saved_settings() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, raw) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = staticProfile(raw))
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val pack = backend.extractOriginalImagePack(source)
            val certificate = pack.manifest.parent.resolve("extraction.json")
            val proof = JsonReader().parse(Files.readString(certificate))
            val filename = proof.get("mappedImages").get(0).getString("file")
            val file = pack.manifest.parent.resolve(filename)
            val changed = ImageIO.read(file.toFile()).also { it.setRGB(0, 0, 0xff123456.toInt()) }
            assertTrue(ImageIO.write(changed, "png", file.toFile()))
            val output = proof.get("outputs").first { it.getString("file") == filename }
            output.remove("sha256"); output.addChild("sha256", com.badlogic.gdx.utils.JsonValue(visualHash(Files.readAllBytes(file))))
            output.remove("bytes"); output.addChild("bytes", com.badlogic.gdx.utils.JsonValue(Files.size(file)))
            Files.writeString(certificate, proof.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json))
            val changedPack = backend.checkImagePack(pack.manifest)
            VisualAssetSettingsSession(backend).use { state ->
                state.edit(backend.initialSelection.copy(packs = ResourcePackSelection(listOf(changedPack.catalogEntry), listOf(changedPack.metadata.id.value))))
                state.apply(); await(state)
                assertFalse(state.canStart)
                assertTrue(state.error.orEmpty().contains("SOURCE_GENERATED_PACKAGE_INVALID"), state.error)
                assertTrue(state.error.orEmpty().contains("Static pixels differ"), state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun a_qualified_static_package_cannot_replace_a_custom_final_contents_changed_animation_layout() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, raw) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = staticProfile(raw))
            val pack = backend.extractOriginalImagePack(source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val contentSource = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("content-pack-sandboxes/json/package")
            val target = fixture.root.resolve("changed-final-content")
            Files.walk(contentSource).use { paths -> paths.forEach { path ->
                val file = target.resolve(contentSource.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(file) else Files.copy(path, file)
            } }
            val unit = target.resolve("units/scout.json")
            val original = Files.readString(unit)
            val modified = original.replace("amber_scout", "c_tank").replace("\"copyFrom\":", "\"graphics\": {\"total_frames\":1}, \"copyFrom\":")
            assertNotEquals(original, modified); Files.writeString(unit, modified)
            val content = assertIs<com.rustedwarfare.reforge.core.content.ContentResult.Success<com.rustedwarfare.reforge.core.content.LoadedContentPack>>(
                com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(target)).value
            val failure = assertFailsWith<IllegalArgumentException> { backend.prepareForContent(selected, ContentPackChoice(content, "c_tank")) }
            assertTrue(failure.message.orEmpty().contains("Layout differs"), failure.message)
            assertTrue(failure.message.orEmpty().contains("expected=60x20/1"), failure.message)
        }
    }

    private fun staticProfile(raw: PcOriginalImageProfile): PcOriginalImageProfile {
        val image = raw.images.first { it.path == "assets/tank.png" }
        val mapping = VisualAssetProfile.parse("""{"schemaVersion":1,"profileId":"owned.pc.static.v1",
            "identity":{"manifestPath":"game-lib.jar","manifestBytes":${raw.jarBytes},"manifestSha256":"${raw.jarSha256}"},
            "sources":[{"id":"tank","path":"assets/tank.png","bytes":${image.bytes},"sha256":"${image.sha256}","width":8,"height":6}],
            "unitOutputs":{"tank":{"file":"units/tank.png","operation":"crop","source":"tank","rect":[0,0,8,6],"target":[8,6],"frames":1}},
            "terrainOutputs":{},"unitBindings":{"c_tank":{"body":"tank"},"demo_tank":{"body":"tank"}}}""".toByteArray())
        return PcOriginalImageProfile(raw.id, raw.jarBytes, raw.jarSha256, raw.images, mapping)
    }

    private fun await(state: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!state.poll()) { check(System.nanoTime() < deadline); Thread.sleep(2) }
    }

    @Test fun added_image_or_renamed_png_signature_rejects_the_whole_qualified_collection_without_publishing() {
        for (extra in listOf("res/extra.png", "assets/hidden-image.bin")) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            Files.copy(source.resolve("assets/tank.png"), source.resolve(extra))
            val failure = assertFailsWith<IllegalArgumentException> { backend.extractOriginalImagePack(source) }
            for (part in listOf("SOURCE_IMAGE_SET_CHANGED", extra)) assertTrue(failure.message.orEmpty().contains(part), failure.message)
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertTrue(Files.notExists(fixture.cache.resolveSibling("extracted-original-packs")))
        }
    }

    @Test fun qualified_owned_installation_extracts_every_original_and_imports_renderable_images_without_enabling_or_saving() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedInstallation(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val pack = backend.extractOriginalImagePack(source)
            val generated = pack.manifest.parent
            assertTrue(generated.startsWith(fixture.cache.resolveSibling("extracted-original-packs")))
            assertFalse(generated.startsWith(fixture.cache))
            assertEquals(1, pack.metadata.requiresResourceFormat)
            for (file in listOf("assets/tank.png", "res/water cloud.jpg", "assets/author.xcf"))
                assertContentEquals(Files.readAllBytes(source.resolve(file)), Files.readAllBytes(generated.resolve("original/$file")))
            val pngId = ResourceId.parse("reforge:original/pc/6173736574732f74616e6b2e706e67")
            val jpgId = ResourceId.parse("reforge:original/pc/7265732f776174657220636c6f75642e6a7067")
            assertEquals(8, pack.images.getValue(pngId).pixelWidth)
            val jpg = pack.images.getValue(jpgId)
            assertEquals(4, jpg.pixelWidth); assertEquals(3, jpg.pixelHeight)
            assertEquals(0xff808080.toInt(), ImageIO.read(jpg.path.toFile()).getRGB(0, 0))
            val manifest = JsonReader().parse(Files.readString(generated.resolve("extraction.json")))
            assertTrue(manifest.getBoolean("complete"))
            assertEquals("owned.pc-images.v1", manifest.getString("profileId"))
            assertEquals(3, manifest.get("originalImages").size)
            assertEquals(2, manifest.getInt("renderableSourceImages"))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertTrue(backend.initialSelection.packs.enabled.isEmpty())
            pack.verify(); pack.verifySources()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = backend.prepare(selected)
            assertEquals(pack.metadata.id.value, assertNotNull(prepared.resolvedImages).providers[pngId])
            prepared.verifyForPublication()
            assertContentEquals(saved, Files.readAllBytes(fixture.settings), "Preparing the generated pack still must not save without Apply")
        }
    }

    private fun ownedInstallation(root: Path): Pair<Path, PcOriginalImageProfile> {
        val source = Files.createDirectories(root.resolve("owned PC 安装"))
        for (folder in listOf("assets", "res", "font")) Files.createDirectories(source.resolve(folder))
        val jar = "Owned PC source identity, never executable".toByteArray()
        Files.write(source.resolve("game-lib.jar"), jar)
        val png = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 8) for (y in 0 until 6) png.setRGB(x, y, if (x < 4) 0xffe0a020.toInt() else 0xff2060c0.toInt())
        assertTrue(ImageIO.write(png, "png", source.resolve("assets/tank.png").toFile()))
        val jpg = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 4) for (y in 0 until 3) jpg.setRGB(x, y, 0xff808080.toInt())
        assertTrue(ImageIO.write(jpg, "jpeg", source.resolve("res/water cloud.jpg").toFile()))
        Files.write(source.resolve("assets/author.xcf"), "Owned opaque author image fixture".toByteArray())
        fun image(path: String, format: String, width: Int? = null, height: Int? = null): PcOriginalImageSource {
            val bytes = Files.readAllBytes(source.resolve(path))
            return PcOriginalImageSource(path, bytes.size, visualHash(bytes), format, width, height)
        }
        return source to PcOriginalImageProfile("owned.pc-images.v1", jar.size, visualHash(jar), listOf(
            image("assets/tank.png", "PNG", 8, 6), image("res/water cloud.jpg", "JPEG", 4, 3), image("assets/author.xcf", "XCF")))
    }

    @Test fun actual_settings_backend_rejects_unknown_pc_image_build_with_observed_identity_and_preserves_good_settings() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val good = backend.prepare(backend.initialSelection)
            backend.applyPrepared(good)
            val saved = Files.readAllBytes(fixture.settings)
            val source = Files.createDirectories(fixture.root.resolve("unknown PC 安装"))
            val jar = source.resolve("game-lib.jar")
            val bytes = "Owned unknown PC code identity, never loaded".toByteArray()
            Files.write(jar, bytes)
            val failure = assertFailsWith<IllegalArgumentException> { backend.extractOriginalImagePack(source) }
            for (part in listOf("SOURCE_VERSION_UNSUPPORTED", "game-lib.jar", visualHash(bytes)))
                assertTrue(failure.message.orEmpty().contains(part), failure.message)
            assertContentEquals(bytes, Files.readAllBytes(jar))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertTrue(Files.notExists(fixture.cache.resolveSibling("extracted-original-packs")), "An unknown source must not create a formal package")
            good.verifyForPublication()
        }
    }
}
