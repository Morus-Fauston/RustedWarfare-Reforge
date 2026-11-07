package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.*
import kotlin.test.*

class PcOriginalResourceExtractionTest {
    @Test fun interrupted_finished_publication_preserves_settings_and_the_incomplete_stage_is_not_importable() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val normal = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            normal.applyPrepared(normal.prepare(normal.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            var stage: Path? = null
            val interrupted = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    if (from.parent.fileName.toString() == "prepared-original-packs") {
                        stage = from; throw java.util.concurrent.CancellationException("Owned export cancellation")
                    }
                    moveVisualAtomically(from, to, replace)
                })
            assertFailsWith<java.util.concurrent.CancellationException> { interrupted.extractOriginalPreparedResourcePack(source) }
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertFailsWith<IllegalArgumentException> { normal.checkImagePack(assertNotNull(stage).resolve("runtime")) }
            val retried = normal.extractAndCheckOriginalResources(source)
            assertTrue(retried.clientHeld); retried.verify()
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
        }
    }

    @Test fun finished_extraction_menu_keeps_the_saved_choice_when_runtime_or_qualification_is_damaged() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            fun backend() = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            VisualAssetSettingsSession(backend()).use { menu ->
                menu.extractOriginalResources(source); await(menu)
                assertNull(menu.error); assertTrue(menu.diagnostic.orEmpty().startsWith("Extracted"), menu.diagnostic)
                val choice = menu.selection.packs.catalog.single()
                assertFalse(choice.id in menu.selection.packs.enabled)
                menu.setEnabled(choice.id, true); menu.apply(); await(menu)
                assertNull(menu.error); val saved = Files.readAllBytes(fixture.settings)
                val external = backend().extractOriginalPreparedResourcePack(source)
                val imageFile = external.manifest.parent.resolve(JsonReader().parse(Files.readString(external.manifest.parent.resolve("images.json")))
                    .get("images").first().getString("file"))
                val originalImage = Files.readAllBytes(imageFile)
                val changed = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
                for (x in 0 until 8) for (y in 0 until 6) changed.setRGB(x, y, 0xffaa3311.toInt())
                assertTrue(ImageIO.write(changed, "png", imageFile.toFile()))
                menu.importImagePack(external.manifest); await(menu)
                assertTrue(menu.diagnostic.orEmpty().contains("Finished media or mapping differs"), menu.diagnostic)
                assertEquals(listOf(choice), menu.selection.packs.catalog)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                Files.write(imageFile, originalImage)
                val proof = external.manifest.parent.parent.resolve("qualification/resource-extraction.json")
                val bytes = Files.readAllBytes(proof); Files.writeString(proof, "{}")
                menu.importImagePack(external.manifest); await(menu)
                assertEquals(listOf(choice), menu.selection.packs.catalog)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                Files.write(proof, bytes)
                menu.importImagePack(external.manifest); await(menu)
                assertTrue(menu.diagnostic.orEmpty().startsWith("Imported"), menu.diagnostic)
            }
        }
    }

    @Test fun complete_menu_extraction_outputs_a_finished_pack_and_can_import_it_in_another_client() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val result = backend.extractOriginalPreparedResourcePack(source)
            assertEquals(4, result.metadata.requiresResourceFormat)
            assertTrue(Files.isRegularFile(result.manifest.parent.resolve("images.json")))
            assertTrue(Files.isRegularFile(result.manifest.parent.resolve("sounds.json")))
            val otherCache = fixture.root.resolve("other-client-cache")
            val other = PcVisualAssetSettings(fixture.store, otherCache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val imported = other.checkImagePack(result.manifest)
            assertTrue(imported.clientHeld)
            assertEquals(result.images.keys, imported.images.keys)
            assertEquals(result.soundEvents, imported.soundEvents)
            val selection = other.initialSelection.copy(packs = ResourcePackSelection(listOf(imported.catalogEntry), listOf(imported.metadata.id.value)))
            other.applyPrepared(other.prepare(selection))
            val parked = result.manifest.parent.parent.resolveSibling("parked-finished-generation")
            require(parked.startsWith(fixture.root) && result.manifest.parent.parent.startsWith(fixture.root))
            Files.move(result.manifest.parent.parent, parked)
            val restarted = PcVisualAssetSettings(fixture.store, otherCache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            restarted.prepare(restarted.initialSelection).verifyForPublication()
            assertEquals("MANAGED_REUSE", restarted.lastPreparationPath?.name)
        }
    }

    @Test fun passive_legacy_catalog_read_keeps_the_saved_identity_until_explicit_import() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            fun backend() = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val legacy = backend().extractOriginalResourcePack(source)
            val selected = backend().initialSelection.copy(packs = ResourcePackSelection(listOf(legacy.catalogEntry), listOf(legacy.metadata.id.value)))
            fixture.store.save(selected)
            val saved = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend()).use { menu ->
                await(menu)
                assertNull(menu.error)
                assertEquals(listOf(legacy.catalogEntry), menu.selection.packs.catalog)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                menu.importImagePack(legacy.manifest); await(menu)
                assertNull(menu.error)
                assertNotEquals(legacy.manifest, menu.selection.packs.catalog.single().manifest)
                assertFalse(legacy.metadata.id.value in menu.selection.packs.enabled)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun client_held_original_acceptance_cannot_survive_an_incompatible_independent_mapping_basis() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val legacy = backend.extractOriginalResourcePack(source)
            val accepted = backend.checkImagePack(legacy.manifest)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(accepted.catalogEntry), listOf(accepted.metadata.id.value)))
            backend.applyPrepared(backend.prepare(selected))
            val saved = Files.readAllBytes(fixture.settings)
            val incompatible = PcOriginalResourceProfile(profile.id, profile.images, profile.sounds,
                mapOf("reforge:events/tank-fire" to "res/raw/unused.wav"))
            val newer = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = incompatible)
            val failure = assertFailsWith<IllegalArgumentException> { newer.prepare(selected) }
            assertTrue(failure.message.orEmpty().contains("Independent original qualification differs"), failure.message)
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            backend.prepare(selected).verifyForPublication()
        }
    }

    @Test fun a_legacy_certificate_change_at_publication_is_rejected_without_changing_the_saved_version_and_can_retry() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            fun backend() = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val good = backend()
            val legacy = good.extractOriginalResourcePack(source)
            val accepted = good.checkImagePack(legacy.manifest)
            val selection = good.initialSelection.copy(packs = ResourcePackSelection(listOf(accepted.catalogEntry), listOf(accepted.metadata.id.value)))
            good.applyPrepared(good.prepare(selection))
            val saved = Files.readAllBytes(fixture.settings)
            val certificate = legacy.manifest.parent.resolve("resource-extraction.json")
            val original = Files.readAllBytes(certificate)
            val changed = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    moveVisualAtomically(from, to, replace)
                    if (from.fileName.toString().startsWith("pending-")) Files.writeString(certificate, "{}")
                })
            try {
                val failure = assertFailsWith<IllegalArgumentException> { changed.checkImagePack(legacy.manifest) }
                assertTrue(failure.message.orEmpty().contains("SOURCE_CHANGED"), failure.message)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                backend().prepare(selection).verifyForPublication()
            } finally { Files.write(certificate, original) }
            backend().checkImagePack(legacy.manifest).verify()
        }
    }

    @Test fun normal_import_migrates_a_qualified_legacy_pack_and_restarts_without_the_package_or_installation() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            fun backend() = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val legacy = backend().extractOriginalResourcePack(source)
            val before = Files.walk(legacy.manifest.parent).use { files -> files.filter { Files.isRegularFile(it) }
                .toList().associate { it to visualHash(Files.readAllBytes(it)) } }
            VisualAssetSettingsSession(backend()).use { menu ->
                menu.importImagePack(legacy.manifest); await(menu)
                assertNull(menu.error); assertTrue(menu.diagnostic.orEmpty().startsWith("Imported"), menu.diagnostic)
                val migrated = menu.selection.packs.catalog.single()
                assertNotEquals(legacy.manifest, migrated.manifest)
                assertFalse(migrated.id in menu.selection.packs.enabled)
                menu.setEnabled(migrated.id, true); menu.apply(); await(menu)
                assertNull(menu.error)
                before.forEach { (path, hash) -> assertEquals(hash, visualHash(Files.readAllBytes(path))) }
                val parked = fixture.root.resolve("parked-legacy-package")
                require(parked.isAbsolute && parked.startsWith(fixture.root) && legacy.manifest.parent.startsWith(fixture.root))
                Files.move(legacy.manifest.parent, parked)
                val parkedSource = fixture.root.resolve("parked-installation")
                require(parkedSource.isAbsolute && parkedSource.startsWith(fixture.root) && source.startsWith(fixture.root))
                Files.move(source, parkedSource)
                val restarted = backend()
                val ready = restarted.prepare(restarted.initialSelection)
                ready.verifyForPublication()
                val layer = ready.resolvedImages!!.layers.single { it.metadata.id.value == migrated.id }
                assertEquals(4, layer.metadata.requiresResourceFormat)
                assertEquals(legacy.images.keys, layer.images.keys)
                assertEquals(legacy.sounds.mapValues { it.value.sha256 }, layer.sounds.mapValues { it.value.sha256 })
                assertEquals(legacy.soundEvents, layer.soundEvents)
                assertEquals("MANAGED_REUSE", restarted.lastPreparationPath?.name)
                assertEquals(0, restarted.preparationDiagnostics["wholePackImports"])
                assertEquals(emptyList<String>(), restarted.preparationDiagnostics["externalSourceTreeRoots"])
            }
        }
    }

    @Test fun reviewed_output_data_is_bound_to_the_producer_and_the_whole_trusted_profile() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (_, profile) = ownedSource(fixture.root)
            val file = "renderable/${visualHash("assets/picture.png".toByteArray())}.png"
            val audio = profile.sounds.joinToString(",") { source ->
                """{"file":"sounds/${visualHash(source.path.toByteArray())}.wav","bytes":48,"sha256":"${"b".repeat(64)}","channels":1,"sampleRate":22050,"frames":2}"""
            }
            val producer = mapOf("self-owned-producer-v1" to "c".repeat(64))
            val bytes = """{"schemaVersion":1,"producers":{"self-owned-producer-v1":"${"c".repeat(64)}"},
                "profiles":[{"id":"${profile.id}","basis":"${PcTrustedOutputIndex.basis(profile)}",
                "images":[{"file":"$file","bytes":100,"sha256":"${"a".repeat(64)}","pixelSha256":"${"d".repeat(64)}","width":8,"height":6}],
                "mapped":[],"sounds":[$audio]}]}""".toByteArray()
            val trusted = PcTrustedOutputIndex.fromReviewed(bytes, producer)
            assertEquals("a".repeat(64), assertNotNull(trusted.forProfile(profile)).images.getValue(file).sha256)
            assertNull(PcTrustedOutputIndex.fromReviewed(bytes, mapOf("self-owned-producer-v1" to "e".repeat(64))).forProfile(profile))
            val wrongImages = PcOriginalImageProfile(profile.images.id, profile.images.jarBytes, "0".repeat(64), profile.images.images)
            assertNull(trusted.forProfile(PcOriginalResourceProfile(profile.id, wrongImages, profile.sounds, profile.events)))
            assertNull(PcTrustedOutputIndex.fromReviewed(bytes.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"").toByteArray(), producer).forProfile(profile))
        }
    }

    @Test fun rehashing_certificates_cannot_certify_renderable_pixels_different_from_the_trusted_original() {
        for (complete in listOf(true, false)) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val first = if (complete) backend.extractOriginalResourcePack(source) else backend.extractOriginalImagePack(source)
            val badRoot = Files.createDirectory(fixture.root.resolve("forged raw pixels"))
            Files.walk(first.manifest.parent).use { files -> files.forEach { file ->
                val target = badRoot.resolve(first.manifest.parent.relativize(file)).normalize()
                require(target.startsWith(badRoot))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            fun read(name: String) = JsonReader().parse(Files.readString(badRoot.resolve(name)))
            fun set(row: JsonValue, key: String, value: JsonValue) { row.remove(key); row.addChild(key, value) }
            fun write(name: String, value: JsonValue) = Files.writeString(badRoot.resolve(name), value.toJson(OutputType.json))
            fun rehash(proof: JsonValue) { for (row in proof.get("outputs")) {
                val bytes = Files.readAllBytes(badRoot.resolve(row.getString("file")))
                set(row, "bytes", JsonValue(bytes.size.toLong())); set(row, "sha256", JsonValue(visualHash(bytes)))
            } }
            val images = read("extraction.json")
            val renderable = images.get("originalImages").single().getString("renderableFile")
            val changed = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 6) changed.setRGB(x, y, 0xffd02040.toInt())
            assertTrue(ImageIO.write(changed, "png", badRoot.resolve(renderable).toFile()))
            rehash(images); write("extraction.json", images)
            if (complete) {
                val resources = read("resource-extraction.json")
                set(resources, "imageExtractionSha256", JsonValue(visualHash(Files.readAllBytes(badRoot.resolve("extraction.json")))))
                rehash(resources); write("resource-extraction.json", resources)
            }
            val failure = assertFailsWith<IllegalArgumentException> {
                // Complete legacy packs qualify before migration; image-only retains its old path.
                val imported = backend.checkImagePack(badRoot)
                val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(imported.catalogEntry), listOf(imported.catalogEntry.id)))
                backend.prepare(selected)
            }
            assertTrue(failure.message.orEmpty().contains("Raw image pixels differ"), failure.message)
        }
    }

    @Test fun forged_output_hashes_cannot_certify_a_changed_unmapped_canonical_sound_or_overwrite_the_good_saved_combination() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val first = backend.extractOriginalResourcePack(source)
            VisualAssetSettingsSession(backend).use { state ->
                state.importImagePack(first.manifest); await(state)
                state.setEnabled(first.catalogEntry.id, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val saved = Files.readAllBytes(fixture.settings)
                val forged = Files.createDirectory(fixture.root.resolve("forged complete source"))
                Files.walk(first.manifest.parent).use { files -> files.forEach { file ->
                    val target = forged.resolve(first.manifest.parent.relativize(file)).normalize()
                    require(target.startsWith(forged))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
                fun read(name: String) = JsonReader().parse(Files.readString(forged.resolve(name)))
                fun set(row: JsonValue, key: String, value: JsonValue) { row.remove(key); row.addChild(key, value) }
                fun write(name: String, row: JsonValue) = Files.writeString(forged.resolve(name), row.toJson(OutputType.json))
                fun rehash(row: JsonValue) { for (entry in row.get("outputs")) {
                    val bytes = Files.readAllBytes(forged.resolve(entry.getString("file")))
                    set(entry, "bytes", JsonValue(bytes.size.toLong())); set(entry, "sha256", JsonValue(visualHash(bytes)))
                } }
                val metadata = read("pack.json")
                set(metadata, "id", JsonValue("reforge:pc-original-resources-${java.util.UUID.randomUUID()}")); write("pack.json", metadata)
                val proof = read("resource-extraction.json")
                val unused = proof.get("originalSounds").single { it.getString("path") == "res/raw/unused.wav" }
                val canonical = forged.resolve(unused.getString("canonicalFile")); val pcm = Files.readAllBytes(canonical)
                pcm[44] = (pcm[44].toInt() xor 1).toByte(); Files.write(canonical, pcm)
                set(unused, "canonicalSha256", JsonValue(visualHash(pcm)))
                val imageProof = read("extraction.json"); rehash(imageProof); write("extraction.json", imageProof)
                set(proof, "imageExtractionSha256", JsonValue(visualHash(Files.readAllBytes(forged.resolve("extraction.json")))))
                rehash(proof); write("resource-extraction.json", proof)
                state.importImagePack(forged); await(state)
                assertTrue(state.canStart, state.error)
                assertTrue(state.diagnostic.orEmpty().contains("SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID"), state.diagnostic)
                assertTrue(state.diagnostic.orEmpty().contains("res/raw/unused.wav"), state.diagnostic)
                assertEquals(1, state.selection.packs.catalog.size)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                state.prepareForLoad()().verifyForPublication()
                assertEquals(listOf(first.catalogEntry.id), fixture.store.load().packs.enabled)
            }
        }
    }

    @Test fun ready_switch_failure_or_late_original_source_change_preserves_the_last_good_complete_pointer_and_applied_resources() {
        for (mode in listOf("ready", "source")) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            var armed = false
            var restore: Pair<Path, ByteArray>? = null
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    if (armed && mode == "ready" && to.fileName.toString() == "ready.json" && to.parent.fileName.toString() == profile.id)
                        throw java.io.IOException("owned complete ready switch failure")
                    moveVisualAtomically(from, to, replace)
                    if (armed && to.parent.fileName.toString() == "generations" && to.parent.parent.fileName.toString() == profile.id) {
                        val (pointer, bytes) = requireNotNull(restore)
                        Files.write(pointer, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
                        if (mode == "source") Files.write(source.resolve("game-lib.jar"), "Owned source changed by the simulated external writer".toByteArray())
                    }
                })
            val first = backend.extractOriginalResourcePack(source)
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(first.catalogEntry), listOf(first.catalogEntry.id)))
            val good = backend.prepare(selection); backend.applyPrepared(good)
            val saved = Files.readAllBytes(fixture.settings)
            val ready = first.manifest.parent.parent.parent.resolve("ready.json"); val previous = Files.readAllBytes(ready)
            val parked = fixture.root.resolve("parked-ready-$mode.json")
            require(ready.isAbsolute && ready.normalize().startsWith(fixture.root) && parked.isAbsolute && parked.normalize().startsWith(fixture.root))
            Files.move(ready, parked); restore = ready to previous; armed = true
            val error = assertFails { backend.extractOriginalResourcePack(source) }
            assertTrue(error.message.orEmpty().contains(if (mode == "ready") "owned complete ready switch failure" else "SOURCE_VERSION_UNSUPPORTED"), error.message)
            assertContentEquals(previous, Files.readAllBytes(ready)); assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            good.verifyForPublication()
            backend.prepare(selection).verifyForPublication()
        }
    }

    @Test fun an_already_interrupted_complete_request_does_not_publish_over_a_good_generation() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val first = backend.extractOriginalResourcePack(source)
            val ready = first.manifest.parent.parent.parent.resolve("ready.json"); val previous = Files.readAllBytes(ready)
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<java.util.concurrent.CancellationException> { backend.extractOriginalResourcePack(source) }
            } finally { Thread.interrupted() }
            assertContentEquals(previous, Files.readAllBytes(ready)); first.verify(); first.verifySources()
        }
    }

    @Test fun every_qualified_sound_is_decoded_even_when_it_has_no_current_event_mapping() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, raw) = ownedSource(fixture.root)
            val bytes = "Owned invalid PCM payload with a qualified fixture hash".toByteArray()
            Files.write(source.resolve("res/raw/unused.wav"), bytes)
            val sounds = raw.sounds.map { if (it.path == "res/raw/unused.wav") it.copy(bytes = bytes.size, sha256 = visualHash(bytes)) else it }
            val profile = PcOriginalResourceProfile(raw.id, raw.images, sounds, raw.events)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val error = assertFailsWith<IllegalArgumentException> { backend.extractOriginalResourcePack(source) }
            assertTrue(error.message.orEmpty().contains("RESOURCE_PACK_AUDIO_INVALID"), error.message)
            assertTrue(error.message.orEmpty().contains("res/raw/unused.wav"), error.message)
            assertTrue(Files.notExists(fixture.cache.resolveSibling("extracted-original-packs").resolve(profile.id).resolve("ready.json")))
            assertTrue(Files.notExists(fixture.settings))
            backend.extractOriginalImagePack(source).verify()
        }
    }

    @Test fun failed_complete_extraction_leaves_the_applied_good_selection_startable_and_the_saved_bytes_unchanged() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            VisualAssetSettingsSession(backend).use { state ->
                state.extractOriginalResources(source); await(state)
                state.setEnabled(state.selectedPackId, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val selected = state.selection; val saved = Files.readAllBytes(fixture.settings)
                state.extractOriginalResources(fixture.root.resolve("missing complete source")); await(state)
                assertTrue(state.canStart, state.error); assertNull(state.error)
                assertTrue(state.diagnostic.orEmpty().contains("SOURCE_VERSION_UNSUPPORTED"), state.diagnostic)
                assertEquals(selected, state.selection)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun cancelled_or_closed_complete_worker_cannot_publish_its_deliberately_late_result_over_a_newer_import() {
        for (close in listOf(false, true)) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val latest = fixture.create("owned:latest-full", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    if (to.parent.fileName.toString() == "generations" && to.parent.parent.fileName.toString() == profile.id) {
                        worker.set(Thread.currentThread()); entered.countDown()
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    }
                    moveVisualAtomically(from, to, replace)
                })
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val state = VisualAssetSettingsSession(backend)
            try {
                state.extractOriginalResources(source)
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (close) state.close() else {
                    state.cancel()
                    assertTrue(state.canStart, state.error)
                    assertTrue(state.diagnostic.orEmpty().startsWith("Resource extraction cancelled."), state.diagnostic)
                    state.importImagePack(latest.manifest)
                }
                release.countDown()
                if (close) {
                    val target = assertNotNull(worker.get()); target.join(5000)
                    assertFalse(target.isAlive); assertFalse(state.poll())
                    assertTrue(state.selection.packs.catalog.isEmpty())
                } else {
                    await(state)
                    assertEquals(listOf("owned:latest-full"), state.selection.packs.catalog.map { it.id })
                    assertEquals("owned:latest-full", state.selectedPackId); assertTrue(state.canStart, state.error)
                }
                assertTrue(state.selection.packs.enabled.isEmpty())
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            } finally {
                release.countDown(); state.close(); worker.get()?.join(5000)
            }
        }
    }

    @Test fun actual_session_extracts_complete_resources_on_its_worker_without_enabling_or_saving_and_preserves_a_repeated_enabled_hit() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val before = Files.readAllBytes(fixture.settings)
            VisualAssetSettingsSession(backend).use { state ->
                assertTrue(state.supportsOriginalResourceExtraction)
                state.extractOriginalResources(source)
                assertTrue(state.busy); assertFalse(state.canStart)
                assertEquals("Extracting verified PC pictures and sounds...", state.operationLabel)
                await(state)
                val entry = state.selection.packs.catalog.single()
                assertEquals(entry.id, state.selectedPackId)
                assertTrue(state.selection.packs.enabled.isEmpty())
                assertTrue(state.hasUnsavedCatalog); assertFalse(state.hasUnappliedChanges)
                assertTrue(state.canStart, state.error)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                state.setEnabled(entry.id, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val selected = state.selection; val saved = Files.readAllBytes(fixture.settings)
                state.extractOriginalResources(source); await(state)
                assertEquals(selected, state.selection)
                assertEquals(listOf(entry), state.selection.packs.catalog)
                assertFalse(state.hasUnsavedCatalog); assertFalse(state.hasUnappliedChanges)
                assertTrue(state.canStart, state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun repeated_complete_extraction_reuses_the_same_good_entry_and_rebuilds_a_corrupt_full_generation_without_deleting_it() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val first = backend.extractOriginalResourcePack(source)
            val hit = backend.extractOriginalResourcePack(source)
            assertEquals(first.catalogEntry, hit.catalogEntry, "A complete good cache hit must not create a duplicate menu entry")
            val damaged = first.manifest.parent.resolve("original/res/raw/unused.wav")
            val corrupt = Files.readAllBytes(damaged)
            corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
            Files.write(damaged, corrupt)
            val repaired = backend.extractOriginalResourcePack(source)
            assertNotEquals(first.catalogEntry, repaired.catalogEntry)
            assertContentEquals(corrupt, Files.readAllBytes(damaged), "Retain the bad generation for diagnosis")
            assertContentEquals(Files.readAllBytes(source.resolve("res/raw/unused.wav")),
                Files.readAllBytes(repaired.manifest.parent.resolve("original/res/raw/unused.wav")))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            repaired.verify(); repaired.verifySources()
            assertEquals(repaired.catalogEntry, backend.extractOriginalResourcePack(source).catalogEntry)
        }
    }

    @Test fun a_corrupted_unmapped_original_after_generation_move_cannot_replace_the_last_complete_ready_or_good_settings() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            var corrupt = false
            var orphan: Path? = null
            var restoreReady: Pair<Path, ByteArray>? = null
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile,
                imageExtractionMove = { from, to, replace ->
                    moveVisualAtomically(from, to, replace)
                    if (corrupt && to.parent.fileName.toString() == "generations" && to.parent.parent.fileName.toString() == profile.id) {
                        orphan = to
                        // Force a real publication path despite the good-cache fast path. The old
                        // pointer is back, byte-for-byte valid, before the tested moved-output fault.
                        val (readyPath, pointer) = requireNotNull(restoreReady)
                        Files.write(readyPath, pointer, java.nio.file.StandardOpenOption.CREATE_NEW)
                        val unused = to.resolve("original/res/raw/unused.wav")
                        val bytes = Files.readAllBytes(unused)
                        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        Files.write(unused, bytes)
                    }
                })
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val first = backend.extractOriginalResourcePack(source)
            val ready = first.manifest.parent.parent.parent.resolve("ready.json")
            val previousReady = Files.readAllBytes(ready)
            val parked = fixture.root.resolve("parked-complete-ready.json")
            require(ready.isAbsolute && ready.normalize().startsWith(fixture.root) && parked.isAbsolute && parked.normalize().startsWith(fixture.root))
            Files.move(ready, parked)
            restoreReady = ready to previousReady
            corrupt = true
            val error = assertFailsWith<IllegalArgumentException> { backend.extractOriginalResourcePack(source) }
            assertTrue(error.message.orEmpty().contains("SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID"), error.message)
            assertTrue(error.message.orEmpty().contains("original/res/raw/unused.wav"), error.message)
            assertContentEquals(previousReady, Files.readAllBytes(ready))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertTrue(Files.exists(assertNotNull(orphan).resolve("resource-extraction.json")), "Keep the failed generation as evidence")
            first.verify(); first.verifySources()
            backend.checkImagePack(first.manifest).verify()
        }
    }

    @Test fun the_complete_package_keeps_the_reviewed_static_image_contract_when_manually_enabled_and_applied_with_its_sound() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, raw) = ownedSource(fixture.root)
            val picture = raw.images.images.single()
            val mapping = VisualAssetProfile.parse("""{"schemaVersion":1,"profileId":"owned.complete.static.v1",
                "identity":{"manifestPath":"game-lib.jar","manifestBytes":${raw.images.jarBytes},"manifestSha256":"${raw.images.jarSha256}"},
                "sources":[{"id":"tank","path":"assets/picture.png","bytes":${picture.bytes},"sha256":"${picture.sha256}","width":8,"height":6}],
                "unitOutputs":{"tank":{"file":"units/tank.png","operation":"crop","source":"tank","rect":[0,0,8,6],"target":[8,6],"frames":1}},
                "terrainOutputs":{},"unitBindings":{"c_tank":{"body":"tank"},"demo_tank":{"body":"tank"}}}""".toByteArray())
            val images = PcOriginalImageProfile(raw.images.id, raw.images.jarBytes, raw.images.jarSha256, raw.images.images, mapping)
            val profile = PcOriginalResourceProfile(raw.id, images, raw.sounds, raw.events)
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val pack = backend.extractOriginalResourcePack(source)
            PcFrozenResourceInputs.capture(listOf(pack.manifest.parent)).use { inputs ->
                val proof = assertNotNull(PcOriginalImageContracts.qualify(pack, images,
                    ResourceImageLayer(BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER), emptyMap()),
                    fixture.cache, ::moveVisualAtomically, inputs))
                PcOriginalResourceContracts.verify(pack, profile, inputs, proof)
                val otherPack = backend.checkImagePack(pack.manifest)
                assertFailsWith<IllegalArgumentException> { PcOriginalResourceContracts.verify(otherPack, profile, inputs, proof) }
                PcFrozenResourceInputs.capture(listOf(pack.manifest.parent)).use { otherInputs ->
                    assertFailsWith<IllegalArgumentException> { PcOriginalResourceContracts.verify(pack, profile, otherInputs, proof) }
                }
                inputs.verifyCurrent()
            }
            VisualAssetSettingsSession(backend).use { state ->
                state.importImagePack(pack.manifest); await(state)
                assertTrue(state.selection.packs.enabled.isEmpty())
                assertTrue(Files.notExists(fixture.settings))
                state.setEnabled(pack.catalogEntry.id, true); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val ready = backend.prepare(state.selection)
                val body = ready.resolvedImages!!.images.getValue(ResourceId.parse("reforge:units/c_tank/body"))
                assertEquals(8, body.declaration.frameWidth); assertEquals(6, body.declaration.frameHeight); assertEquals(1, body.declaration.totalFrames)
                assertEquals(pack.metadata.id.value, ready.resolvedImages!!.providers[ResourceId.parse("reforge:units/c_tank/body")])
                assertEquals(pack.metadata.id.value, ready.resolvedSounds!!.eventProviders[ResourceId.parse("reforge:events/tank-fire")])
                assertEquals(listOf(pack.catalogEntry.id), fixture.store.load().packs.enabled)
                ready.verifyForPublication()
            }
        }
    }

    @Test fun official_music_is_listed_as_excluded_without_copying_or_registering_it_as_a_sound_effect() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val name = "assets/music/starting/owned.ogg"
            Files.createDirectories(source.resolve(name).parent)
            Files.copy(Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/ogg/clip.ogg"), source.resolve(name))
            val original = Files.readAllBytes(source.resolve(name))
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val pack = backend.extractOriginalResourcePack(source)
            assertEquals(2, pack.sounds.size)
            val proof = JsonReader().parse(Files.readString(pack.manifest.parent.resolve("resource-extraction.json")))
            val excluded = proof.get("excludedMusic")
            assertEquals(1, excluded.size)
            assertEquals(name, excluded[0].getString("path")); assertEquals(original.size.toLong(), excluded[0].getLong("bytes"))
            assertEquals("music-out-of-scope", excluded[0].getString("reason"))
            assertTrue(Files.notExists(pack.manifest.parent.resolve("original/$name")))
            assertContentEquals(original, Files.readAllBytes(source.resolve(name)))
            assertTrue(pack.soundEvents.keys.none { it.value.contains("music") })
        }
    }

    @Test fun complete_extraction_refuses_extra_audio_even_when_a_valid_ogg_is_renamed_to_an_unrelated_extension() {
        for (extra in listOf("res/raw/extra.ogg", "assets/hidden.bin")) ResourceImagePackApplicationFixture().use { fixture ->
            val (source, profile) = ownedSource(fixture.root)
            val clip = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/ogg/clip.ogg")
            Files.copy(clip, source.resolve(extra))
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            val good = backend.prepare(backend.initialSelection); backend.applyPrepared(good)
            val saved = Files.readAllBytes(fixture.settings)
            val error = assertFailsWith<IllegalArgumentException> { backend.extractOriginalResourcePack(source) }
            assertTrue(error.message.orEmpty().contains("SOURCE_SOUND_SET_CHANGED"), error.message)
            assertTrue(error.message.orEmpty().contains(extra), error.message)
            assertTrue(Files.notExists(fixture.cache.resolveSibling("extracted-original-packs").resolve(profile.id).resolve("ready.json")))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings)); good.verifyForPublication()
        }
    }

    @Test fun a_qualified_owned_installation_produces_all_pictures_and_sounds_as_a_disabled_ordinary_pack_with_only_its_known_event() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = Files.createDirectories(fixture.root.resolve("owned complete PC source"))
            for (folder in listOf("assets", "res/raw", "font")) Files.createDirectories(source.resolve(folder))
            val jar = "Owned complete source identity, never executable".toByteArray()
            Files.write(source.resolve("game-lib.jar"), jar)
            val image = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 8) for (y in 0 until 6) image.setRGB(x, y, 0xff2050a0.toInt())
            assertTrue(ImageIO.write(image, "png", source.resolve("assets/picture.png").toFile()))
            val imageBytes = Files.readAllBytes(source.resolve("assets/picture.png"))
            val images = PcOriginalImageProfile("owned.pc-images.v1", jar.size, visualHash(jar), listOf(
                PcOriginalImageSource("assets/picture.png", imageBytes.size, visualHash(imageBytes), "PNG", 8, 6)))
            val public = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs")
            val sounds = listOf("ogg" to "res/raw/tank_firing.ogg", "wav" to "res/raw/unused.wav").map { (kind, path) ->
                Files.copy(public.resolve("$kind/clip.$kind"), source.resolve(path))
                val bytes = Files.readAllBytes(source.resolve(path))
                PcOriginalSoundSource(path, bytes.size, visualHash(bytes), if (kind == "ogg") "OGG_VORBIS" else "PCM16_WAV", 1, 22050)
            }
            val profile = PcOriginalResourceProfile("owned.pc-resources.v1", images, sounds,
                mapOf("reforge:events/tank-fire" to "res/raw/tank_firing.ogg"))
            val sourceBefore = Files.walk(source).use { files -> files.filter { Files.isRegularFile(it) }.toList().associateWith { Files.readAllBytes(it) } }
            val backend = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), resourceExtractionProfile = profile)
            backend.applyPrepared(backend.prepare(backend.initialSelection))
            val saved = Files.readAllBytes(fixture.settings)
            val pack = backend.extractOriginalResourcePack(source)
            assertTrue(pack.metadata.id.value.startsWith("reforge:pc-original-resources-"))
            assertEquals(2, pack.metadata.requiresResourceFormat)
            assertEquals(1, pack.images.size)
            assertEquals(2, pack.sounds.size)
            assertEquals(setOf(ResourceId.parse("reforge:events/tank-fire")), pack.soundEvents.keys)
            val selectedSound = pack.soundEvents.getValue(ResourceId.parse("reforge:events/tank-fire")).sound
            assertEquals("reforge:original/pc/sounds/7265732f7261772f74616e6b5f666972696e672e6f6767", selectedSound.value)
            for (sound in pack.sounds.values) {
                assertEquals(1, sound.channels); assertEquals(22050, sound.sampleRate); assertEquals(3528, sound.frames)
                assertTrue(sound.path.startsWith(fixture.cache) && sound.path.toString().endsWith(".wav"))
            }
            val proof = JsonReader().parse(Files.readString(pack.manifest.parent.resolve("resource-extraction.json")))
            assertTrue(proof.getBoolean("complete")); assertEquals(2, proof.get("originalSounds").size)
            assertEquals(1, proof.get("mappedSoundEvents").size); assertEquals(1, proof.get("unmappedSounds").size)
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertTrue(backend.initialSelection.packs.enabled.isEmpty())
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val prepared = backend.prepare(selected)
            assertEquals(pack.metadata.id.value, prepared.resolvedSounds?.eventProviders?.get(ResourceId.parse("reforge:events/tank-fire")))
            prepared.verifyForPublication(); pack.verify(); pack.verifySources()
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            for ((path, bytes) in sourceBefore) assertContentEquals(bytes, Files.readAllBytes(path))
            val legacy = backend.extractOriginalImagePack(source)
            assertTrue(legacy.sounds.isEmpty()); assertEquals(1, legacy.metadata.requiresResourceFormat)
        }
    }

    @Test fun complete_resource_extraction_rejects_unknown_pc_identity_and_preserves_the_applied_good_configuration() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val good = backend.prepare(backend.initialSelection)
            backend.applyPrepared(good)
            val saved = Files.readAllBytes(fixture.settings)
            val source = Files.createDirectories(fixture.root.resolve("unknown complete PC source"))
            val jar = source.resolve("game-lib.jar")
            val bytes = "Owned unknown complete-resource source, never loaded".toByteArray()
            Files.write(jar, bytes)
            val failure = assertFailsWith<IllegalArgumentException> { backend.extractOriginalResourcePack(source) }
            for (part in listOf("SOURCE_VERSION_UNSUPPORTED", "game-lib.jar", visualHash(bytes)))
                assertTrue(failure.message.orEmpty().contains(part), failure.message)
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            assertContentEquals(bytes, Files.readAllBytes(jar))
            assertTrue(Files.notExists(fixture.cache.resolveSibling("extracted-original-packs")))
            good.verifyForPublication()
        }
    }

    private fun ownedSource(root: Path): Pair<Path, PcOriginalResourceProfile> {
        val source = Files.createDirectories(root.resolve("owned complete source"))
        for (folder in listOf("assets", "res/raw", "font")) Files.createDirectories(source.resolve(folder))
        val jar = "Owned complete source qualification, no executable code".toByteArray()
        Files.write(source.resolve("game-lib.jar"), jar)
        val picture = BufferedImage(8, 6, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 8) for (y in 0 until 6) picture.setRGB(x, y, 0xff2050a0.toInt())
        assertTrue(ImageIO.write(picture, "png", source.resolve("assets/picture.png").toFile()))
        val bytes = Files.readAllBytes(source.resolve("assets/picture.png"))
        val images = PcOriginalImageProfile("owned.pc-images.v1", jar.size, visualHash(jar), listOf(
            PcOriginalImageSource("assets/picture.png", bytes.size, visualHash(bytes), "PNG", 8, 6)))
        val public = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs")
        val sounds = listOf("ogg" to "res/raw/tank_firing.ogg", "wav" to "res/raw/unused.wav").map { (kind, path) ->
            Files.copy(public.resolve("$kind/clip.$kind"), source.resolve(path))
            val data = Files.readAllBytes(source.resolve(path))
            PcOriginalSoundSource(path, data.size, visualHash(data), if (kind == "ogg") "OGG_VORBIS" else "PCM16_WAV", 1, 22050)
        }
        return source to PcOriginalResourceProfile("owned.pc-resources.v1", images, sounds,
            mapOf("reforge:events/tank-fire" to "res/raw/tank_firing.ogg"))
    }

    private fun await(state: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!state.poll()) { assertTrue(System.nanoTime() < deadline, "Actual settings worker timed out"); Thread.sleep(2) }
    }
}
