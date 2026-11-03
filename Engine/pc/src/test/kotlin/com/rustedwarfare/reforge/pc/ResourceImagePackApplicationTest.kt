package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResourceImagePackApplicationTest {
    @Test
    fun final_content_loader_and_configuration_rebinding_preserve_the_actual_sound_stack() {
        for (weighted in listOf(false, true)) ResourceImagePackApplicationFixture().use { fixture ->
            val choice = changedBuiltinContent(fixture, "sound-content", 1)
            val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("resource-sound-packs/wav")
            val target = fixture.root.resolve("sound-pack")
            Files.walk(source).use { paths -> paths.forEach { path ->
                val destination = target.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            } }
            if (weighted) {
                val metadata = target.resolve("pack.json")
                Files.writeString(metadata, Files.readString(metadata).replace("\"requiresResourceFormat\": 2", "\"requiresResourceFormat\": 3"))
                Files.writeString(target.resolve("sounds.json"), """{"formatVersion":2,
                    "sounds":{"owned:sounds/shot":{"file":"clip.wav"},"owned:sounds/alias":{"file":"clip.wav"}},
                    "events":{"reforge:events/tank-fire":{"candidates":[{"sound":"owned:sounds/shot","weight":2},{"sound":"owned:sounds/alias"}]}}}""")
            }
            val backend = fixture.backend()
            val pack = backend.checkImagePack(target)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val event = ResourceId.parse("reforge:events/tank-fire")
            SandboxLoader().use { loader ->
                loader.requestPack(contentRoot(), choice) { backend.prepareForContent(selected, choice) }
                val loaded = await(loader).getOrThrow()
                val visuals = assertNotNull(loaded.visuals)
                assertEquals(1, visuals.units.getValue("c_tank").totalFrames)
                assertEquals(ImageLogicalSize(60, 20), visuals.units.getValue("c_tank").logicalImages?.body)
                val sounds = assertNotNull(visuals.resolvedSounds)
                assertEquals(pack.metadata.id.value, sounds.eventProviders[event])
                if (weighted) {
                    assertEquals(listOf(2, 1), sounds.events.getValue(event).candidates.map { it.weight })
                    assertEquals(listOf("owned:sounds/shot", "owned:sounds/alias"), sounds.events.getValue(event).candidates.map { it.sound.value })
                    assertEquals(listOf(pack.metadata.id.value, pack.metadata.id.value), sounds.candidateProviders[event])
                } else assertEquals(pack.sounds.values.single().path, sounds.resolve(event)?.path)
                val rebound = visuals.withConfiguration(selected).withResourceImages(assertNotNull(visuals.resolvedImages))
                assertEquals(sounds.events, rebound.resolvedSounds?.events)
                assertEquals(sounds.candidateProviders, rebound.resolvedSounds?.candidateProviders)
                assertEquals(sounds.sounds, rebound.resolvedSounds?.sounds)
                rebound.verifyForPublication()
                assertEquals(choice.content.units.mapValues { definitionValues(it.value) },
                    loaded.scenario.unitDefinitions.mapValues { definitionValues(it.value) })
            }
        }
    }

    @Test
    fun real_pc_preparation_uses_final_content_layout_when_a_content_unit_reuses_a_builtin_id() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val choice = changedBuiltinContent(fixture, "one-frame", 1)
            val body = ResourceId.parse("reforge:units/c_tank/body")
            val pack = fixture.create("owned:final", listOf(OwnPackImage(body.value, frames = 1, width = 60)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            assertFailsWith<IllegalArgumentException> { backend.prepare(selected) }
            val prepared = backend.prepareForContent(selected, choice)
            assertEquals("owned:final", assertNotNull(prepared.resolvedImages).providers[body])
            assertEquals(ImageLogicalSize(60, 20), prepared.units.getValue("c_tank").logicalImages?.body)
            assertEquals(1, prepared.units.getValue("c_tank").totalFrames)
            assertEquals(pack.images.getValue(body).path, prepared.units.getValue("c_tank").body)
            prepared.verifyForPublication()
        }
    }

    @Test
    fun sandbox_load_accepts_a_matching_override_for_changed_builtin_content_without_changing_gameplay() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val choice = changedBuiltinContent(fixture, "one-frame", 1)
            val body = ResourceId.parse("reforge:units/c_tank/body")
            val pack = fixture.create("owned:final", listOf(OwnPackImage(body.value, frames = 1, width = 60)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            SandboxLoader().use { loader ->
                loader.requestPack(contentRoot(), choice) { backend.prepareForContent(selected, choice) }
                val loaded = await(loader).getOrThrow()
                assertEquals(pack.images.getValue(body).path, loaded.renderResources.units.getValue("c_tank").imagePath)
                assertEquals(choice.content.units.mapValues { definitionValues(it.value) },
                    loaded.scenario.unitDefinitions.mapValues { definitionValues(it.value) })
                assertEquals(choice.content.sources, loaded.contentSources)
            }
        }
    }

    @Test
    fun apply_and_restore_recheck_changed_builtin_content_and_reject_hidden_bad_layers_without_overwriting_good_settings() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val choice = changedBuiltinContent(fixture, "one-frame", 1)
            val incompatibleChoice = changedBuiltinContent(fixture, "three-frames", 3)
            val address = "reforge:units/c_tank/body"
            val good = fixture.create("owned:final", listOf(OwnPackImage(address, frames = 1, width = 60)))
            val hidden = fixture.create("owned:hidden", listOf(OwnPackImage(address)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(good.catalogEntry, hidden.catalogEntry), listOf(good.catalogEntry.id)))
            VisualAssetSettingsSession(backend).use { state ->
                state.edit(selected); state.apply(choice); await(state)
                assertTrue(state.canStart, state.error)
                val saved = Files.readAllBytes(fixture.settings)
                state.edit(selected.copy(packs = ResourcePackSelection(selected.packs.catalog, listOf(good.catalogEntry.id, hidden.catalogEntry.id))))
                state.apply(choice); await(state)
                assertTrue(!state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:hidden"), state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                state.restoreSaved(choice); await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(selected, state.selection)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
                state.restoreSaved(incompatibleChoice); await(state)
                assertTrue(!state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:final"), state.error)
                assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            }
            SandboxLoader().use { loader ->
                val bad = selected.copy(packs = ResourcePackSelection(selected.packs.catalog, listOf(good.catalogEntry.id, hidden.catalogEntry.id)))
                loader.requestPack(contentRoot(), choice) { backend.prepareForContent(bad, choice) }
                val result = await(loader)
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("owned:hidden"))
            }
        }
    }

    private fun contentRoot() = repo().resolve("SimulationTest/public/inputs/content-pack-sandboxes/json")

    private fun changedBuiltinContent(fixture: ResourceImagePackApplicationFixture, name: String, frames: Int): ContentPackChoice {
        val source = contentRoot().resolve("package")
        val target = fixture.root.resolve("content-$name")
        Files.walk(source).use { paths -> paths.forEach { path ->
            val destination = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
        } }
        val unit = target.resolve("units/scout.json")
        Files.writeString(unit, Files.readString(unit).replace("amber_scout", "c_tank")
            .replace("\"copyFrom\":", "\"graphics\": {\"total_frames\": $frames}, \"copyFrom\":"))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(target)).value
        return ContentPackChoice(pack, "c_tank")
    }

    @Test
    fun ready_evidence_records_frozen_stack_providers_layouts_and_sources_without_rereading_input() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val body = ResourceId.parse("reforge:units/c_tank/body")
            val future = ResourceId.parse("owned:future/picture")
            val amber = fixture.create("owned:amber", listOf(OwnPackImage(body.value)))
            val blue = fixture.create("owned:blue", listOf(OwnPackImage(body.value), OwnPackImage(future.value, frames = 1, width = 12, height = 12)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(amber.catalogEntry, blue.catalogEntry),
                listOf(blue.catalogEntry.id, amber.catalogEntry.id)))
            val prepared = backend.prepare(selected)
            Files.writeString(blue.manifest.parent.resolve("image0.png"), "Changed after this frozen result was prepared")
            var values: Map<String, Any?> = emptyMap()
            ClientEvidence { kind, fields -> assertEquals("visual-assets-ready", kind); values = fields }.recordVisualAssetsReady(prepared, 23L)
            assertEquals(23L, values["generation"])
            val stack = assertIs<Map<*, *>>(values["resourceStack"])
            assertEquals(listOf("owned:blue", "owned:amber", "reforge:base"), stack["packOrder"])
            val packages = assertIs<List<*>>(stack["packages"])
            val actualBlue = assertIs<Map<*, *>>(packages.first())
            assertEquals(blue.identity, actualBlue["identity"])
            assertEquals(blue.sources.map { mapOf("path" to it.path.toString(), "bytes" to it.bytes, "sha256" to it.sha256) }, actualBlue["sources"])
            val resources = assertIs<Map<*, *>>(stack["resources"])
            val actual = assertIs<Map<*, *>>(resources[body.value])
            assertEquals("owned:blue", actual["providerId"])
            assertEquals(blue.images.getValue(body).path.toString(), actual["path"])
            assertEquals(120, actual["pixelWidth"])
            assertEquals(20, actual["frameWidth"])
            assertEquals(3, actual["totalFrames"])
            assertEquals(true, actual["bound"])
            assertEquals(false, assertIs<Map<*, *>>(resources[future.value])["bound"])
        }
    }

    @Test
    fun asynchronous_apply_validates_current_content_before_saving_and_can_repair_by_disabling_the_bad_layer() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val address = "reforge:units/amber_scout/body"
            val good = fixture.create("owned:good", listOf(OwnPackImage(address)))
            val bad = fixture.create("owned:bad", listOf(OwnPackImage(address, frames = 1)))
            val backend = fixture.backend()
            fixture.store.save(backend.initialSelection)
            val before = Files.readAllBytes(fixture.settings)
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(good.catalogEntry, bad.catalogEntry),
                listOf(good.catalogEntry.id, bad.catalogEntry.id)))
            val root = repo().resolve("SimulationTest/public/inputs/content-pack-sandboxes/json")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val choice = ContentPackChoice(pack, "amber_scout")
            VisualAssetSettingsSession(backend).use { state ->
                state.edit(selected)
                state.apply(choice)
                await(state)
                assertTrue(!state.canStart)
                assertTrue(state.error.orEmpty().contains("owned:bad"), state.error)
                assertContentEquals(before, Files.readAllBytes(fixture.settings))
                val repaired = selected.copy(packs = ResourcePackSelection(selected.packs.catalog, listOf(good.catalogEntry.id)))
                state.edit(repaired)
                state.apply(choice)
                await(state)
                assertTrue(state.canStart, state.error)
                assertEquals(repaired, fixture.store.load())
            }
        }
    }

    @Test
    fun actual_sandbox_loader_rebases_users_after_final_content_images_without_changing_the_gameplay_definitions() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val body = ResourceId.parse("reforge:units/amber_scout/body")
            val hd = fixture.create("owned:custom", listOf(OwnPackImage(body.value)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(hd.catalogEntry), listOf(hd.catalogEntry.id)))
            val root = repo().resolve("SimulationTest/public/inputs/content-pack-sandboxes/json")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val choice = ContentPackChoice(pack, "amber_scout")
            SandboxLoader().use { loader ->
                loader.requestPack(root, choice) { backend.prepare(selected) }
                val loaded = await(loader).getOrThrow()
                val prepared = assertNotNull(loaded.visuals)
                assertEquals("owned:custom", assertNotNull(prepared.resolvedImages).providers.getValue(body))
                assertEquals(hd.images.getValue(body).path, loaded.renderResources.units.getValue("amber_scout").imagePath)
                assertEquals(choice.content.units.mapValues { definitionValues(it.value) },
                    loaded.scenario.unitDefinitions.mapValues { definitionValues(it.value) })
                assertEquals(choice.content.sources, loaded.contentSources)
                assertEquals(20, prepared.units.getValue("amber_scout").logicalImages?.body?.width)
            }
        }
    }

    @Test
    fun a_hidden_user_layout_that_is_invalid_for_the_new_content_cannot_publish_a_world() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val address = "reforge:units/amber_scout/body"
            val good = fixture.create("owned:good", listOf(OwnPackImage(address)))
            val bad = fixture.create("owned:hiddenbad", listOf(OwnPackImage(address, frames = 1)))
            val backend = fixture.backend()
            val selected = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(good.catalogEntry, bad.catalogEntry),
                listOf(good.catalogEntry.id, bad.catalogEntry.id)))
            assertNotNull(backend.prepare(selected).resolvedImages, "An unused future URI can be imported before its content is selected")
            val root = repo().resolve("SimulationTest/public/inputs/content-pack-sandboxes/json")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            SandboxLoader().use { loader ->
                loader.requestPack(root, ContentPackChoice(pack, "amber_scout")) { backend.prepare(selected) }
                val result = await(loader)
                assertTrue(result.isFailure, "Every enabled declaration must fit the final content, including a hidden lower layer")
                assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("owned:hiddenbad"))
                assertEquals(null, loader.pollPrepared())
            }
        }
    }

    @Test
    fun real_pc_preparation_consumes_enabled_users_and_preserves_fixed_generations_and_saved_choices() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val body = ResourceId.parse("reforge:units/c_tank/body")
            val turret = ResourceId.parse("reforge:units/c_tank/turret")
            val amber = fixture.create("owned:amber", listOf(OwnPackImage(body.value)))
            val blue = fixture.create("owned:blue", listOf(OwnPackImage(turret.value, frames = 1, scale = 3)))
            val backend = fixture.backend()
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(amber.catalogEntry, blue.catalogEntry),
                listOf(amber.metadata.id.value, blue.metadata.id.value)))
            val prepared = backend.prepare(selection)
            val resolved = assertNotNull(prepared.resolvedImages, "The platform preparation must actually resolve the enabled stack")
            assertEquals(listOf("owned:amber", "owned:blue", "reforge:base"), resolved.packOrder)
            assertEquals("owned:amber", resolved.providers.getValue(body))
            assertEquals("owned:blue", resolved.providers.getValue(turret))
            assertEquals(amber.images.getValue(body).path, prepared.units.getValue("c_tank").body)
            assertEquals(blue.images.getValue(turret).path, prepared.units.getValue("c_tank").turret)
            assertEquals(20, prepared.units.getValue("c_tank").logicalImages?.body?.width)
            backend.applyPrepared(prepared)
            assertEquals(prepared.selection, fixture.store.load())
            val saved = Files.readAllBytes(fixture.settings)
            val restarted = fixture.backend()
            assertEquals(prepared.selection, restarted.initialSelection)
            assertEquals(resolved.providers, assertNotNull(restarted.prepare(restarted.initialSelection).resolvedImages).providers)
            Files.writeString(amber.manifest.parent.resolve("image0.png"), "Source changed after the checked import")
            prepared.verify()
            assertFailsWith<IllegalArgumentException> { backend.prepare(prepared.selection) }
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
            val disabled = prepared.selection.copy(packs = ResourcePackSelection(prepared.selection.packs.catalog, emptyList()))
            assertEquals(listOf("reforge:base"), assertNotNull(backend.prepare(disabled).resolvedImages).packOrder)
            assertTrue(Files.isRegularFile(prepared.units.getValue("c_tank").body))
        }
    }

    private fun await(loader: SandboxLoader): Result<LoadedSandbox> {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            loader.pollPrepared()?.let { return it }
            Thread.sleep(2)
        }
        error("Actual sandbox worker did not complete in the bounded test")
    }

    private fun await(state: VisualAssetSettingsSession) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (state.poll()) return
            Thread.sleep(2)
        }
        error("Actual resource worker did not complete in the bounded test")
    }

    private fun repo() = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }

    // A successful revalidation reparses fresh UnitDefinition objects. Compare
    // all their current semantic fields, rather than reference identity.
    private fun definitionValues(value: UnitDefinition): List<Any?> = listOf(value.id, value.className, value.radius,
        value.displayRadius, value.totalFrames, value.imagePath, value.imageScale, value.movement, value.retainedUnconsumed,
        value.fogOfWarSightRange, value.combat, value.durability, value.building, value.production, value.build,
        value.directHitTargetKind, value.pointDefense, value.upgrades?.actions, value.income,
        value.builder?.let { listOf(it.buildingTypeIds, it.range, it.preparation, it.decay, it.repairRate) },
        value.construction, value.deathExplosion)
}
