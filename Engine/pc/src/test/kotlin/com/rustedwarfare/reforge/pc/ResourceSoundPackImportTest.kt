package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ResourcePackSelection
import com.rustedwarfare.reforge.client.VisualAssetSettingsSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.test.*

class ResourceSoundPackImportTest {
    @Test fun actual_weighted_pack_import_requires_capability_three_and_preserves_both_declared_candidates() {
        OwnedSoundInputs().use { inputs ->
            val root = inputs.source("wav")
            val soundFile = Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".wav") }.findFirst().orElseThrow() }
            Files.writeString(root.resolve("sounds.json"), """{"formatVersion":2,
                "sounds":{"owned:sounds/shot":{"file":"${soundFile.fileName}"},"owned:sounds/alias":{"file":"${soundFile.fileName}"}},
                "events":{"reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/shot","weight":2},{"sound":"owned:sounds/alias"}],"append":true}}}""")
            val importer = PcResourcePackImporter(inputs.cache)
            val error = assertFailsWith<IllegalArgumentException> { importer.load(root) }
            assertTrue(error.message.orEmpty().contains("sounds.json"), error.message)
            assertTrue(error.message.orEmpty().contains("capability 3"), error.message)
            val metadata = root.resolve("pack.json")
            Files.writeString(metadata, Files.readString(metadata).replace("\"requiresResourceFormat\": 2", "\"requiresResourceFormat\": 3"))
            val prepared = importer.load(root)
            val event = prepared.soundEvents.getValue(ResourceId.parse("reforge:events/tank-fire"))
            assertEquals(listOf("owned:sounds/shot", "owned:sounds/alias"), event.candidates.map { it.sound.value })
            assertEquals(listOf(2, 1), event.candidates.map { it.weight })
            assertTrue(event.append)
            assertEquals(prepared.sounds.getValue(ResourceId.parse("owned:sounds/shot")),
                prepared.sounds.getValue(ResourceId.parse("owned:sounds/alias")))
            prepared.verify(); prepared.verifySources()
        }
    }

    @Test fun hidden_bad_event_reference_blocks_apply_preserves_saved_settings_and_can_be_disabled_for_repair() {
        OwnedSoundInputs().use { inputs ->
            val badManifest = inputs.source("ogg").resolve("sounds.hjson")
            val text = Files.readString(badManifest)
            val changed = text.replace("\"sound\": \"owned:sounds/shot\"", "\"sound\": \"owned:sounds/missing\"")
            assertNotEquals(text, changed); Files.writeString(badManifest, changed)
            val settings = inputs.root.resolve("settings.properties")
            val store = VisualAssetSettingsStore(settings)
            val backend = PcVisualAssetSettings(store, inputs.cache, inputs.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), inputs.root))
            val good = backend.checkImagePack(inputs.source("wav")); val bad = backend.checkImagePack(inputs.source("ogg"))
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(good.catalogEntry, bad.catalogEntry), listOf(good.metadata.id.value)))
            VisualAssetSettingsSession(backend).use { state ->
                state.edit(selected); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                val saved = Files.readAllBytes(settings)
                state.edit(selected.copy(packs = ResourcePackSelection(selected.packs.catalog, listOf(good.metadata.id.value, bad.metadata.id.value))))
                state.apply(); await(state)
                assertFalse(state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:sound-ogg"), state.error)
                assertTrue(state.error.orEmpty().contains("sounds.hjson"), state.error)
                assertTrue(state.error.orEmpty().contains("owned:sounds/missing"), state.error)
                assertContentEquals(saved, Files.readAllBytes(settings))
                state.edit(selected); state.apply(); await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(selected, store.load())
                assertEquals("owned:sound-wav", assertNotNull(backend.prepare(state.selection).resolvedSounds)
                    .eventProviders[ResourceId.parse("reforge:events/tank-fire")])
            }
        }
    }

    private fun await(state: VisualAssetSettingsSession) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (state.poll()) return
            Thread.sleep(2)
        }
        error("Actual settings worker did not finish")
    }
    @Test fun real_settings_prepare_resolves_user_sound_and_base_fallback_in_the_same_order_as_images() {
        OwnedSoundInputs().use { inputs ->
            val backend = PcVisualAssetSettings(VisualAssetSettingsStore(inputs.root.resolve("settings.properties")),
                inputs.cache, inputs.root, WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), inputs.root))
            val event = ResourceId.parse("reforge:events/tank-fire")
            val baseline = backend.prepare(backend.initialSelection)
            val baseSounds = assertNotNull(baseline.resolvedSounds)
            assertEquals(listOf("reforge:base"), baseSounds.packOrder)
            assertEquals("reforge:base", baseSounds.eventProviders[event])
            val baseWave = baseSounds.sounds.getValue(baseSounds.events.getValue(event).sound)
            assertEquals(3528, PcmAudioDecoder.decode(Files.readAllBytes(baseWave.path), baseWave.path.toString()).frames)
            val imported = backend.checkImagePack(inputs.source("wav"))
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(imported.catalogEntry), listOf(imported.metadata.id.value)))
            val prepared = backend.prepare(selected)
            val sounds = assertNotNull(prepared.resolvedSounds)
            assertEquals(assertNotNull(prepared.resolvedImages).packOrder, sounds.packOrder)
            assertEquals(listOf("owned:sound-wav", "reforge:base"), sounds.packOrder)
            assertEquals("owned:sound-wav", sounds.eventProviders[event])
            val id = ResourceId.parse("owned:sounds/shot")
            assertEquals(id, sounds.events.getValue(event).sound)
            assertEquals(imported.sounds.getValue(id), sounds.sounds.getValue(id))
            prepared.verifyForPublication()
            val disabled = backend.prepare(selected.copy(packs = ResourcePackSelection(selected.packs.catalog, emptyList())))
            assertEquals("reforge:base", assertNotNull(disabled.resolvedSounds).eventProviders[event])
            assertTrue(id !in assertNotNull(disabled.resolvedSounds).sounds)
        }
    }
    @Test fun unsupported_sound_capability_ambiguous_declarations_and_unused_broken_audio_reject_import() {
        val changes: List<Pair<String, (Path) -> Unit>> = listOf(
            "capability 2" to { root ->
                val file = root.resolve("pack.json")
                Files.writeString(file, Files.readString(file).replace("\"requiresResourceFormat\": 2", "\"requiresResourceFormat\": 1"))
            },
            "at most one" to { root -> Files.copy(root.resolve("sounds.json"), root.resolve("sounds.hjson")); Unit },
            "owned:sounds/unused" to { root ->
                Files.writeString(root.resolve("broken.wav"), "not audio")
                val file = root.resolve("sounds.json")
                Files.writeString(file, Files.readString(file).replace("\"owned:sounds/shot\": {",
                    "\"owned:sounds/unused\": {\"file\":\"broken.wav\"}, \"owned:sounds/shot\": {"))
            },
            "without parent" to { root ->
                val file = root.resolve("sounds.json")
                Files.writeString(file, Files.readString(file).replace("clip.wav", "../outside.wav"))
            },
        )
        for ((reason, change) in changes) OwnedSoundInputs().use { inputs ->
            change(inputs.source("wav"))
            val error = assertFailsWith<IllegalArgumentException> { PcResourcePackImporter(inputs.cache).load(inputs.source("wav")) }
            assertTrue(error.message.orEmpty().contains(reason, ignoreCase = true), error.message)
        }
    }

    @Test fun sound_declaration_discovery_is_rechecked_but_unlisted_files_do_not_change_the_pack() {
        OwnedSoundInputs().use { inputs ->
            val root = inputs.source("wav")
            val pack = PcResourcePackImporter(inputs.cache).load(root)
            Files.writeString(root.resolve("unlisted.wav"), "This is not a declared resource")
            pack.verify(); pack.verifySources()
            val added = root.resolve("sounds.hjson")
            Files.copy(root.resolve("sounds.json"), added)
            assertFailsWith<IllegalArgumentException> { pack.verifySources() }
            pack.verify()
            require(added.isAbsolute && added.startsWith(inputs.root))
            Files.delete(added)
            pack.verifySources()
        }
    }
    @Test fun logical_aliases_of_one_audio_file_share_one_frozen_sound_path() {
        OwnedSoundInputs().use { inputs ->
            val manifest = inputs.source("wav").resolve("sounds.json")
            val text = Files.readString(manifest)
            val changed = text.replace("\"owned:sounds/shot\": {", "\"owned:sounds/alias\": {\"file\":\"clip.wav\"}, \"owned:sounds/shot\": {")
            assertNotEquals(text, changed)
            Files.writeString(manifest, changed)
            val pack = PcResourcePackImporter(inputs.cache).load(inputs.source("wav"))
            val first = pack.sounds.getValue(ResourceId.parse("owned:sounds/shot"))
            val alias = pack.sounds.getValue(ResourceId.parse("owned:sounds/alias"))
            assertEquals(first, alias)
            assertEquals(5, pack.sources.size)
            pack.verify(); pack.verifySources()
        }
    }
    @Test fun sound_sources_and_frozen_generations_are_verified_independently() {
        OwnedSoundInputs().use { inputs ->
            val importer = PcResourcePackImporter(inputs.cache)
            val pack = importer.load(inputs.source("wav"))
            val id = ResourceId.parse("owned:sounds/shot")
            val oldPath = pack.sounds.getValue(id).path
            val fixed = Files.readAllBytes(oldPath)
            val source = inputs.source("wav").resolve("clip.wav")
            val original = Files.readAllBytes(source)
            Files.writeString(source, "broken input after checking")
            pack.verify()
            assertFailsWith<IllegalArgumentException> { pack.verifySources() }
            assertFailsWith<IllegalArgumentException> { importer.load(inputs.source("wav")) }
            assertContentEquals(fixed, Files.readAllBytes(oldPath))
            val changed = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(source, changed)
            val next = importer.load(inputs.source("wav"))
            assertNotEquals(pack.identity, next.identity)
            assertNotEquals(oldPath, next.sounds.getValue(id).path)
            pack.verify(); next.verify()
            assertFailsWith<IllegalArgumentException> { pack.verifySources() }
            Files.write(oldPath, fixed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFailsWith<IllegalArgumentException> { pack.verify() }
            next.verify(); next.verifySources()
        }
    }
    @Test fun cancellation_during_source_recheck_is_not_reclassified_as_broken_audio() {
        OwnedSoundInputs().use { inputs ->
            val pack = PcResourcePackImporter(inputs.cache).load(inputs.source("wav"))
            try {
                Thread.currentThread().interrupt()
                assertFailsWith<CancellationException> { pack.verifySources() }
            } finally { Thread.interrupted() }
            pack.verify(); pack.verifySources()
        }
    }
    @Test fun actual_pc_import_prepares_wav_and_vorbis_in_the_existing_fixed_resource_generation() {
        OwnedSoundInputs().use { inputs ->
            val importer = PcResourcePackImporter(inputs.cache)
            for (kind in listOf("wav", "ogg")) {
                val pack = importer.load(inputs.source(kind))
                assertEquals(2, pack.metadata.requiresResourceFormat)
                val id = ResourceId.parse("owned:sounds/shot")
                val sound = pack.sounds.getValue(id)
                assertEquals(id, pack.soundEvents.getValue(ResourceId.parse("reforge:events/tank-fire")).sound)
                assertEquals(1, sound.channels); assertEquals(22050, sound.sampleRate); assertEquals(3528, sound.frames)
                assertTrue(sound.path.toString().endsWith(".wav"))
                assertTrue(sound.path.startsWith(inputs.cache))
                assertTrue(!sound.path.startsWith(inputs.source(kind)))
                val decoded = PcmAudioDecoder.decode(Files.readAllBytes(sound.path), sound.path.toString())
                assertEquals(3528, decoded.frames)
                assertEquals(5, pack.sources.size, "Metadata, image/sound declarations, icon and audio bytes must be captured")
                pack.verify(); pack.verifySources()
                val hit = importer.load(inputs.source(kind))
                assertEquals(pack.catalogEntry, hit.catalogEntry)
                assertEquals(sound.path, hit.sounds.getValue(id).path, "A valid fixed-generation cache hit must be reused")
            }
        }
    }

    private class OwnedSoundInputs : AutoCloseable {
        val root = Files.createTempDirectory("resource-sound-import-").toAbsolutePath().normalize()
        val cache = root.resolve("cache")
        init {
            val public = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs")
            for (kind in listOf("wav", "ogg")) {
                val from = public.resolve(kind); val to = source(kind)
                Files.walk(from).use { paths -> paths.forEach { file ->
                    val target = to.resolve(from.relativize(file))
                    if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
                } }
            }
        }
        fun source(kind: String) = root.resolve("source-$kind")
        override fun close() {
            require(root.isAbsolute && root.fileName.toString().startsWith("resource-sound-import-"))
            root.toFile().deleteRecursively()
        }
    }
}
