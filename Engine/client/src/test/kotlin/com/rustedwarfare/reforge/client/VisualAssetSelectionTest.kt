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
