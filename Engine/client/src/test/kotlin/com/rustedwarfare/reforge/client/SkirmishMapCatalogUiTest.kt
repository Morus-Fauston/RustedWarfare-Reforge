package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.ScenarioCatalog
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkirmishMapCatalogUiTest {
    @Test
    fun selection_state_switches_cleanly_between_tmx_maps_and_catalog_scenarios() {
        val state = SkirmishMapSelectionState()
        assertFalse(state.hasSelection)
        assertNull(state.activeMap)

        assertTrue(state.selectCatalog("rocks-mixed-100"))
        assertTrue(state.hasSelection)
        assertNull(state.selected)
        assertNotNull(state.selectedCatalog)
        assertEquals(74, state.activeMap?.widthTiles)
        assertEquals(40, state.activeMap?.heightTiles)
        assertTrue(state.activePreviewSummary!!.contains("100 units"))

        val demo = TilesetAtlasResolver.builtInSkirmishMaps().first()
        assertTrue(state.select(demo.path, null, demo.displayName))
        assertNotNull(state.selected)
        assertNull(state.selectedCatalog, "Selecting a .tmx map must clear catalog scenario selection")
        assertEquals(demo.displayName, state.activeDisplayName)

        assertFalse(state.selectCatalog("nonexistent-scene-id"))
        assertNotNull(state.error)

        state.clear()
        assertFalse(state.hasSelection)
        assertNull(state.error)
    }

    @Test
    fun sandbox_loader_loads_catalog_scenario_directly_into_playable_sandbox() {
        val entry = checkNotNull(ScenarioCatalog.byId("narrow-bridge-yield"))
        val packRoot = Path.of("..", "..", "SimulationTest", "public", "inputs", "phase-six-a-sandbox", "json", "package")
            .toAbsolutePath().normalize()
        val loader = SandboxLoader()
        try {
            loader.requestCatalogScenario(entry, packRoot, contentPack = null, prepareVisuals = null)
            var result: Result<LoadedSandbox>? = null
            val deadline = System.nanoTime() + 10_000_000_000L
            while (result == null && System.nanoTime() < deadline) {
                result = loader.pollPrepared()
                if (result == null) Thread.sleep(15)
            }
            val loaded = checkNotNull(result) { "Timed out waiting for catalog scenario load" }.getOrThrow()
            assertEquals("narrow-bridge-yield", loaded.scenario.scenarioId)
            assertEquals(2, loaded.scenario.units.size)
            assertEquals(36, loaded.scenario.map.widthTiles)
        } finally {
            loader.close()
        }
    }

    @Test
    fun sandbox_loader_binds_air_flock_and_mixed_catalog_scenarios_with_placeholder_visuals_from_non_pack_root() {
        val nonPackRoot = Path.of("..", "..", "SimulationTest", "public", "inputs", "phase-six-a-sandbox", "json")
            .toAbsolutePath().normalize()
        val fakePlaceholderVisuals = {
            PreparedVisualAssets(
                selection = VisualAssetSelection(),
                profileId = "test-placeholder",
                identity = "test-placeholder-v1",
                units = mapOf(
                    "c_tank" to VisualUnitImages(
                        body = Path.of("visuals/c_tank_body.png").toAbsolutePath(),
                        turret = Path.of("visuals/c_tank_turret.png").toAbsolutePath(),
                    ),
                    // Intentionally omit turret on heavyTank to verify slot-complete alias fallback to c_tank
                    "heavyTank" to VisualUnitImages(
                        body = Path.of("visuals/heavy_body_only.png").toAbsolutePath(),
                    ),
                ),
                terrain = emptyMap(),
            )
        }
        val loader = SandboxLoader()
        try {
            fun awaitLoad(sceneId: String): LoadedSandbox {
                val entry = checkNotNull(ScenarioCatalog.byId(sceneId))
                loader.requestCatalogScenario(entry, nonPackRoot, contentPack = null, prepareVisuals = fakePlaceholderVisuals)
                var result: Result<LoadedSandbox>? = null
                val deadline = System.nanoTime() + 10_000_000_000L
                while (result == null && System.nanoTime() < deadline) {
                    result = loader.pollPrepared()
                    if (result == null) Thread.sleep(15)
                }
                return checkNotNull(result) { "Timed out loading $sceneId" }.getOrThrow()
            }

            val airLoaded = awaitLoad("air-flock-rally")
            assertEquals("air-flock-rally", airLoaded.scenario.scenarioId)
            assertEquals(16, airLoaded.scenario.units.size)
            assertNotNull(airLoaded.contentPack, "fallbackCatalogPack must preserve ContentPackChoice for contentPackVisuals")
            val heliVisual = checkNotNull(airLoaded.renderResources.units["helicopter"])
            assertNotNull(heliVisual.rotorImagePath, "helicopter visual binding must retain rotor slot")

            val mixedLoaded = awaitLoad("rocks-mixed-100")
            assertEquals("rocks-mixed-100", mixedLoaded.scenario.scenarioId)
            assertEquals(100, mixedLoaded.scenario.units.size)
            val cTankVisual = checkNotNull(mixedLoaded.renderResources.units["c_tank"])
            val heavyVisual = checkNotNull(mixedLoaded.renderResources.units["slow_heavy"])
            assertEquals(
                cTankVisual.imagePath,
                heavyVisual.imagePath,
                "slow_heavy must skip incomplete heavyTank alias lacking turret and fall back to slot-complete c_tank",
            )
            assertNotNull(heavyVisual.turretImagePath)
        } finally {
            loader.close()
        }
    }
}
