package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisualAssetSelectionTest {
    @Test fun optional_previews_report_the_current_stage_and_can_be_cancelled_before_start() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val phase = java.util.concurrent.atomic.AtomicReference<String?>()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override val preparationStatus: String? get() = phase.get()
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                phase.set("Checking pictures...")
                try {
                    entered.countDown(); release.await()
                    return PreparedVisualAssets(selection, "owned", "owned", emptyMap(), emptyMap())
                } finally { phase.set(null); stopped.countDown() }
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            try {
                assertTrue(state.ensurePreviews())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals("Checking pictures...", state.operationLabel)
                phase.set("Checking sounds...")
                assertEquals("Checking sounds...", state.operationLabel)
                state.cancel()
                assertTrue(stopped.await(5, TimeUnit.SECONDS))
                assertNull(state.operationLabel); assertNull(state.preparationStatus)
                assertTrue(state.canStart)
                release.countDown()
                state.prepareForLoad()()
                assertNull(state.preparationStatus)
            } finally { release.countDown() }
        }
    }

    @Test fun loading_reports_the_current_stage_and_cancellation_clears_it_before_retry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val phase = java.util.concurrent.atomic.AtomicReference<String?>()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override val preparationStatus: String? get() = phase.get()
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                phase.set("Checking pictures...")
                try {
                    entered.countDown()
                    release.await()
                    return PreparedVisualAssets(selection, "owned", "owned", emptyMap(), emptyMap())
                } finally { phase.set(null) }
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                assertNull(state.preparationStatus)
                val action = state.prepareForLoad()
                val pending = worker.submit<PreparedVisualAssets> { action() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals("Checking pictures...", state.preparationStatus)
                phase.set("Checking sounds...")
                assertEquals("Checking sounds...", state.preparationStatus)
                pending.cancel(true)
                worker.submit {}.get(5, TimeUnit.SECONDS)
                assertNull(state.preparationStatus)
                release.countDown()
                worker.submit<PreparedVisualAssets> { state.prepareForLoad()() }.get(5, TimeUnit.SECONDS)
                assertNull(state.preparationStatus)
                assertTrue(state.canStart)
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }

    @Test fun repeated_content_loads_retain_one_content_binding_and_one_stable_visual_identity() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val fixtures=repo.resolve("SimulationTest/public/inputs")
        val input=fixtures.resolve("three-format-tanks/ini")
        val pack=(com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(fixtures.resolve("content-packs/amber-scouts-json")) as
            com.rustedwarfare.reforge.core.content.ContentResult.Success).value
        val choice=ContentPackChoice(pack,"amber_scout")
        val resources=SandboxRenderResources.from(ControlledScenario.load(input))
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val backend=object:VisualAssetSettings {
            override val initialSelection=VisualAssetSelection()
            override val initialError:String?=null
            override fun prepare(selection:VisualAssetSelection):PreparedVisualAssets {
                calls.incrementAndGet()
                return PreparedVisualAssets(selection,"owned","owned-mapping",
                    resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath,unit.backImagePath,
                        unit.turretImagePath,unit.deadImagePath,unit.totalFrames) },
                    mapOf("controlled-strip-2" to resources.tileSets.single().imagePath))
            }
            override fun applyPrepared(prepared:PreparedVisualAssets)=Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            SandboxLoader().use { loader ->
                var firstIdentity:String?=null
                repeat(4) {
                    loader.requestPack(input,choice,state.prepareForLoad(choice))
                    var result:Result<LoadedSandbox>?=null
                    val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                    while(result==null&&System.nanoTime()<end){result=loader.pollPrepared();if(result==null)Thread.sleep(2)}
                    val loaded=kotlin.test.assertNotNull(result).getOrThrow()
                    val visuals=kotlin.test.assertNotNull(loaded.visuals)
                    if(firstIdentity==null)firstIdentity=visuals.identity
                    assertEquals(firstIdentity,visuals.identity,"Reopening the same content must not grow verification/binding wrappers")
                    assertEquals(pack.units.getValue("amber_scout").imagePath,loaded.renderResources.units.getValue("amber_scout").imagePath)
                    state.rememberPublished(visuals,loaded.contentPack)
                }
                assertEquals(1,calls.get())
            }
        }
    }

    @Test fun a_published_matching_descriptor_is_retained_without_repeating_backend_preparation() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val acknowledged = mutableListOf<PreparedVisualAssets>()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection) = PreparedVisualAssets(selection, "self-owned",
                "prepared-${calls.incrementAndGet()}", emptyMap(), emptyMap())
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
            override fun onPublished(prepared: PreparedVisualAssets) { acknowledged += prepared }
        }
        VisualAssetSettingsSession(backend).use { state ->
            val published = state.prepareForLoad()()
            state.rememberPublished(published)
            assertEquals(listOf(published), acknowledged)
            kotlin.test.assertSame(published, state.prepareForLoad()())
            assertEquals(1, calls.get())
            state.edit(state.selection.copy(originalDirectory = Path.of("different")))
            state.apply()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!state.poll() && System.nanoTime() < deadline) Thread.sleep(2)
            state.rememberPublished(published) // A late result cannot replace the current selection's descriptor.
            assertEquals(listOf(published), acknowledged, "A late result cannot publish a platform audit for another selection")
            assertEquals("prepared-2", state.prepareForLoad()().identity)
            assertEquals(2, calls.get())
        }
    }

    @Test fun the_loader_verifies_the_frozen_descriptor_once_after_binding_before_publication() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val input = repo.resolve("SimulationTest/public/inputs/maintenance-defense/construction")
        val resources = SandboxRenderResources.from(ControlledScenario.load(input))
        val checks = java.util.concurrent.atomic.AtomicInteger()
        val assets = PreparedVisualAssets(VisualAssetSelection(), "self-owned", "shared",
            resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
            mapOf("construction-strip-5" to resources.tileSets.single().imagePath)) { checks.incrementAndGet() }
        SandboxLoader().use { loader ->
            loader.request(input) { assets }
            var loaded: Result<LoadedSandbox>? = null
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (loaded == null && System.nanoTime() < end) { loaded = loader.pollPrepared(); if (loaded == null) Thread.sleep(2) }
            kotlin.test.assertNotNull(loaded).getOrThrow()
            assertEquals(1, checks.get(), "Binding and final publication must share one complete frozen-generation verification")
        }
    }

    @Test fun a_completed_preview_is_reused_but_final_load_still_rechecks_its_inputs() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val input = repo.resolve("SimulationTest/public/inputs/maintenance-defense/construction")
        val resources = SandboxRenderResources.from(ControlledScenario.load(input))
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                calls.incrementAndGet()
                return PreparedVisualAssets(selection, "self-owned", "shared",
                    resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                        unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                    mapOf("construction-strip-5" to resources.tileSets.single().imagePath)) {
                    require(valid.get()) { "Owned prepared inputs changed" }
                }
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            state.ensurePreviews()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!state.poll() && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(1, calls.get())
            assertEquals("shared", state.prepareForLoad()().identity)
            assertEquals(1, calls.get())
            valid.set(false)
            SandboxLoader().use { loader ->
                loader.request(input, state.prepareForLoad())
                var loaded: Result<LoadedSandbox>? = null
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (loaded == null && System.nanoTime() < end) { loaded = loader.pollPrepared(); if (loaded == null) Thread.sleep(2) }
                assertTrue(loaded?.exceptionOrNull()?.message.orEmpty().contains("Owned prepared inputs changed"))
                assertEquals(1, calls.get(), "Even a reused descriptor must fail the loader's final checks")
            }
        }
    }

    @Test fun a_different_content_source_never_reuses_the_prior_content_bound_preview() {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        fun choice(format: String): ContentPackChoice {
            val pack = (com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(
                repo.resolve("SimulationTest/public/inputs/content-pack-sandboxes/$format/package")) as
                com.rustedwarfare.reforge.core.content.ContentResult.Success).value
            return ContentPackChoice(pack, "amber_scout")
        }
        val first = choice("json"); val next = choice("ini")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection) = PreparedVisualAssets(selection, "self-owned",
                "prepared-${calls.incrementAndGet()}", emptyMap(), emptyMap())
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            state.ensurePreviews(first)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!state.poll() && System.nanoTime() < deadline) Thread.sleep(2)
            val prepared = state.prepareForLoad(next)()
            assertEquals(2, calls.get())
            assertEquals(next.content.units.getValue("amber_scout").imagePath, prepared.units.getValue("amber_scout").body)
        }
    }

    @Test fun cancelling_optional_preview_preserves_the_saved_startable_selection() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                entered.countDown(); release.await()
                return PreparedVisualAssets(selection, "self-owned", "preview", emptyMap(), emptyMap())
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            try {
                state.ensurePreviews()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                state.cancel()
                assertEquals(backend.initialSelection, state.selection)
                assertTrue(state.canStart, "Cancelling optional icons must not invalidate the saved choice")
                assertNull(state.error)
            } finally { release.countDown() }
        }
    }

    @Test fun starting_shares_the_current_preview_instead_of_preparing_the_same_selection_twice() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                val ordinal = calls.incrementAndGet()
                entered.countDown(); release.await()
                return PreparedVisualAssets(selection, "self-owned", "prepared-$ordinal", emptyMap(), emptyMap())
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            try {
                assertTrue(state.ensurePreviews())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val prepare = state.prepareForLoad()
                release.countDown()
                assertEquals("prepared-1", prepare().identity)
                assertEquals(1, calls.get(), "The launch must consume the same in-flight non-GL preparation")
            } finally { release.countDown() }
        }
    }

    @Test fun optional_preview_does_not_block_start_for_the_saved_selection() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                entered.countDown(); release.await()
                return PreparedVisualAssets(selection, "self-owned", "preview", emptyMap(), emptyMap())
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) = Unit
        }
        VisualAssetSettingsSession(backend).use { state ->
            try {
                assertTrue(state.ensurePreviews())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue(state.busy)
                assertTrue(state.canStart, "A saved valid selection must be startable while optional icons prepare")
            } finally { release.countDown() }
        }
    }

    @Test fun cancelling_an_apply_keeps_the_old_request_from_saving_even_if_it_returns_late() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saved = mutableListOf<VisualAssetSelection>()
        val old = VisualAssetSelection(VisualAssetSource.ORIGINAL, Path.of("old"))
        val latest = VisualAssetSelection(VisualAssetSource.PLACEHOLDER, Path.of("remembered"))
        val backend = object : VisualAssetSettings {
            override val initialSelection = VisualAssetSelection()
            override val initialError: String? = null
            override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
                if (selection == old) {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* deliberately late platform result */ }
                }
                return PreparedVisualAssets(selection, "self-owned", selection.toString(), emptyMap(), emptyMap())
            }
            override fun applyPrepared(prepared: PreparedVisualAssets) { saved += prepared.selection }
        }
        VisualAssetSettingsSession(backend).use { state ->
            try {
                state.edit(old); state.apply()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                state.cancel()
                state.edit(latest); state.apply()
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!state.canStart && System.nanoTime() < deadline) { state.poll(); Thread.sleep(5) }
                assertTrue(state.canStart)
                assertEquals(latest, state.selection)
                assertEquals(listOf(latest), saved)
            } finally { release.countDown() }
        }
    }
    @Test fun cancelled_visual_preparation_never_publishes_over_the_latest_loading_request() {
        val repo = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val root = repo.resolve("SimulationTest/public/inputs/maintenance-defense/construction")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun visuals(id: String, scene: com.rustedwarfare.reforge.core.sim.Scenario): PreparedVisualAssets {
            val resources = SandboxRenderResources.from(scene)
            return PreparedVisualAssets(VisualAssetSelection(), "self-owned", id,
                resources.units.mapValues { (_, unit) -> VisualUnitImages(unit.imagePath, unit.backImagePath,
                    unit.turretImagePath, unit.deadImagePath, unit.totalFrames) },
                mapOf("construction-strip-5" to resources.tileSets.single().imagePath))
        }
        val scene = ControlledScenario.load(root)
        SandboxLoader().use { loader ->
            try {
                loader.request(root) { entered.countDown(); release.await(); visuals("old", scene) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                loader.request(root) { visuals("new", scene) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var result: LoadedSandbox? = null
                while (result == null && System.nanoTime() < deadline) {
                    result = loader.pollPrepared()?.getOrThrow()
                    if (result == null) Thread.sleep(5)
                }
                assertEquals("new", result?.visuals?.identity)
                assertEquals("p006-construction", result?.scenario?.scenarioId)
                assertNull(loader.pollPrepared())
            } finally { release.countDown() }
        }
    }
}
