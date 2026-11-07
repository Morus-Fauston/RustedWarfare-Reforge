package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import java.nio.file.Files
import kotlin.test.*

class ManagedResourcePackTest {
    @Test fun an_authentic_receipt_from_a_different_validation_rule_version_requires_reimport() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val pack = fixture.backend().checkImagePack(finishedSource(fixture))
            val before = pack.manifest.parent
            val receipt = com.badlogic.gdx.utils.JsonReader().parse(Files.readString(before.resolve("receipt.json")))
            receipt.get("rules").set(2L, "2")
            val bytes = receipt.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json).toByteArray()
            val other = before.resolveSibling(visualHash(bytes) + "-" + java.util.UUID.randomUUID())
            require(other.startsWith(fixture.root))
            Files.createDirectory(other)
            Files.list(before).use { paths -> paths.forEach { Files.copy(it, other.resolve(it.fileName)) } }
            Files.write(other.resolve("receipt.json"), bytes)
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(Files.readAllBytes(before.parent.parent.resolve("acceptance.key")), "HmacSHA256"))
            Files.write(other.resolve("receipt.mac"), mac.doFinal(bytes))
            assertTrue(assertFailsWith<IllegalArgumentException> { fixture.backend().checkImagePack(other.resolve("pack.json")) }
                .message.orEmpty().contains("rules differ"))
            pack.verify()
        }
    }

    @Test fun different_authored_content_and_start_unit_rebind_without_reimporting_the_accepted_pack() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val backend = fixture.backend()
            val pack = backend.checkImagePack(finishedSource(fixture))
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            backend.applyPrepared(backend.prepare(selection))
            val root = java.nio.file.Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
            val content = assertIs<com.rustedwarfare.reforge.core.content.ContentResult.Success<com.rustedwarfare.reforge.core.content.LoadedContentPack>>(
                com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(root.resolve("content-pack-sandboxes/json/package"))).value
            val restarted = fixture.backend()
            for (unit in listOf("amber_scout", "amber_guard")) {
                val prepared = restarted.prepareForContent(selection, ContentPackChoice(content, unit))
                prepared.verifyForPublication()
                assertTrue(unit in prepared.units)
                assertEquals(pack.identity, prepared.resolvedImages?.layers?.single { it.metadata.id.value == pack.metadata.id.value }?.identity)
                assertEquals(0, restarted.preparationDiagnostics["wholePackImports"])
                assertEquals(emptyList<String>(), restarted.preparationDiagnostics["externalSourceTreeRoots"])
            }
        }
    }

    @Test fun cancellation_competing_publication_and_atomic_move_failure_keep_the_saved_version_startable() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = finishedSource(fixture)
            val first = fixture.backend()
            val pack = first.checkImagePack(source)
            val selected = first.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            first.applyPrepared(first.prepare(selected))
            val saved = Files.readAllBytes(fixture.settings)
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<java.util.concurrent.CancellationException> { fixture.backend().checkImagePack(source) }
            } finally { Thread.interrupted() }
            java.nio.channels.FileChannel.open(pack.manifest.parent.parent.parent.resolve("publish.lock"), java.nio.file.StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    assertTrue(assertFailsWith<IllegalArgumentException> { fixture.backend().checkImagePack(source) }.message.orEmpty().contains("CACHE_BUSY"))
                }
            }
            val brokenPublisher = PcVisualAssetSettings(fixture.store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root), imageExtractionMove = { from, to, replace ->
                    if (from.fileName.toString().startsWith("pending-")) throw java.io.IOException("Injected unsupported atomic publication")
                    moveVisualAtomically(from, to, replace)
                })
            assertFailsWith<java.io.IOException> { brokenPublisher.checkImagePack(source) }
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            fixture.backend().prepare(selected).verifyForPublication()
            assertNotEquals(pack.manifest, fixture.backend().checkImagePack(source).manifest)
        }
    }

    @Test fun changed_receipts_and_missing_used_files_cannot_restore_a_saved_acceptance() {
        for (target in listOf("receipt.json", "receipt.mac", "pack.json", "used-image")) {
            ResourceImagePackApplicationFixture().use { fixture ->
                val source = finishedSource(fixture)
                val backend = fixture.backend()
                val pack = backend.checkImagePack(source)
                val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
                backend.applyPrepared(backend.prepare(selected))
                val saved = Files.readAllBytes(fixture.settings)
                val file = if (target == "used-image") pack.images.values.single().path else pack.manifest.parent.resolve(target)
                if (target == "used-image") Files.delete(file) else Files.writeString(file, "Forged package-provided acceptance")
                assertFails { fixture.backend().prepare(selected) }
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
        }
    }

    @Test fun initial_import_rejects_bad_layout_and_missing_media_even_with_a_self_reported_receipt() {
        for (missing in listOf(false, true)) ResourceImagePackApplicationFixture().use { fixture ->
            val source = finishedSource(fixture)
            Files.writeString(source.resolve("receipt.json"), """{"qualified":true,"rules":1}""")
            if (missing) Files.delete(source.resolve("image0.png"))
            else Files.writeString(source.resolve("images.json"), Files.readString(source.resolve("images.json")).replace("\"frameWidth\":20", "\"frameWidth\":21"))
            assertFailsWith<IllegalArgumentException> { fixture.backend().checkImagePack(source) }
            assertFalse(Files.exists(fixture.settings))
        }
    }

    @Test fun finished_audio_must_already_be_canonical_pcm16_instead_of_being_converted_on_import() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = finishedSource(fixture)
            val canonical = PcmAudio(1, 8000, byteArrayOf(0, 0, 1, 0)).wavBytes()
            val extended = canonical + "JUNK".toByteArray() + byteArrayOf(0, 0, 0, 0)
            java.nio.ByteBuffer.wrap(extended, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(extended.size - 8)
            Files.write(source.resolve("clip.wav"), extended)
            Files.writeString(source.resolve("sounds.json"), """{"formatVersion":1,"sounds":{"owned:clip":{"file":"clip.wav"}},"events":{"reforge:events/tank-fire":{"sound":"owned:clip"}}}""")
            assertFailsWith<IllegalArgumentException> { fixture.backend().checkImagePack(source) }
            Files.write(source.resolve("clip.wav"), canonical)
            val accepted = fixture.backend().checkImagePack(source)
            assertContentEquals(canonical, Files.readAllBytes(accepted.sounds.values.single().path))
        }
    }

    @Test fun ordinary_menu_reimport_replaces_the_accepted_version_and_requires_explicit_enable_and_apply() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = finishedSource(fixture)
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                state.importImagePack(source); await(state)
                val first = state.selection.packs.catalog.single()
                state.setEnabled(first.id, true); state.apply(); await(state)
                val saved = Files.readAllBytes(fixture.settings)
                state.importImagePack(source); await(state)
                assertNull(state.error)
                assertEquals(1, state.selection.packs.catalog.size)
                val replacement = state.selection.packs.catalog.single()
                assertNotEquals(first.manifest, replacement.manifest)
                assertEquals(first.identity, replacement.identity)
                assertFalse(replacement.id in state.selection.packs.enabled)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                state.setEnabled(replacement.id, true); state.apply(); await(state)
                assertNull(state.error)
                assertEquals(replacement, fixture.store.load().packs.catalog.single())
            }
        }
    }

    @Test fun saved_menu_catalog_and_match_bindings_do_not_scan_unused_accepted_media() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = fixture.create("owned:finished", listOf(OwnPackImage("reforge:units/c_tank/body"), OwnPackImage("owned:unused"))).manifest.parent
            val manifest = source.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4"))
            val backend = fixture.backend()
            val pack = backend.checkImagePack(source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            backend.applyPrepared(backend.prepare(selected))
            Files.writeString(pack.images.getValue(ResourceId.parse("owned:unused")).path, "Unread unused-media sentinel")
            VisualAssetSettingsSession(fixture.backend()).use { state ->
                await(state)
                assertNull(state.error)
                val visuals = state.prepareForLoad()()
                visuals.verifyForPublication()
                assertEquals(pack.images.getValue(ResourceId.parse("reforge:units/c_tank/body")).path, visuals.units.getValue("c_tank").body)
            }
            assertFailsWith<IllegalArgumentException> { pack.verify() }
        }
    }

    @Test fun a_changed_accepted_image_is_rejected_and_reimport_can_publish_a_new_fixed_version() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = finishedSource(fixture)
            val backend = fixture.backend()
            val pack = backend.checkImagePack(source)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = backend.prepare(selected)
            backend.applyPrepared(prepared)
            val saved = Files.readAllBytes(fixture.settings)
            val image = pack.images.values.single().path
            Files.write(image, Files.readAllBytes(image).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFailsWith<IllegalArgumentException> { fixture.backend().prepare(selected) }
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            val repaired = fixture.backend().checkImagePack(source)
            assertNotEquals(pack.manifest, repaired.manifest)
            repaired.verify()
            assertTrue(Files.isRegularFile(image), "Bad fixed versions remain as evidence")
        }
    }

    @Test fun accepted_finished_pack_survives_source_removal_and_a_new_settings_instance() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val source = fixture.create("owned:finished", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent
            val manifest = source.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4"))
            val first = fixture.backend()
            val pack = first.checkImagePack(source)
            val selected = first.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.metadata.id.value)))
            val prepared = first.prepare(selected)
            first.applyPrepared(prepared)
            Files.move(source, source.resolveSibling("removed-source"))
            val restarted = fixture.backend()
            val reused = restarted.prepare(restarted.initialSelection)
            reused.verifyForPublication()
            assertEquals(pack.catalogEntry, restarted.initialSelection.packs.catalog.single())
            assertEquals(prepared.units.getValue("c_tank").body, reused.units.getValue("c_tank").body)
            assertEquals(3, reused.units.getValue("c_tank").totalFrames)
            assertEquals(ImageLogicalSize(20, 20), reused.units.getValue("c_tank").logicalImages?.body)
            assertEquals("MANAGED_REUSE", restarted.lastPreparationPath?.name)
        }
    }

    private fun finishedSource(fixture: ResourceImagePackApplicationFixture): java.nio.file.Path {
        val source = fixture.create("owned:finished", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent
        val manifest = source.resolve("pack.json")
        Files.writeString(manifest, Files.readString(manifest).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4"))
        return source
    }

    private fun await(state: VisualAssetSettingsSession) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (state.poll()) return
            Thread.sleep(2)
        }
        error("Resource menu worker exceeded its bounded wait")
    }
}
