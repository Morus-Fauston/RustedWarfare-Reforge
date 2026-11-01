package com.rustedwarfare.reforge.client

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import com.rustedwarfare.reforge.client.demo.DemoScenario

class VisualAssetBindingTest {
    @Test fun known_atlas_geometry_and_blocking_select_one_recipe_and_unknown_semantics_are_rejected() {
        val repo = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val knowledge = ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/content-movement-knowledge/P002-知识沙盒输入"))
        assertEquals(listOf("controlled-ground-40", "controlled-rock-35"),
            SandboxRenderResources.from(knowledge).tileSets.map { it.supportedLayout() })
        val demo = SandboxRenderResources.from(DemoScenario.load()).tileSets.single()
        assertEquals("demo-strip-4", demo.supportedLayout())
        assertFailsWith<IllegalArgumentException> { demo.copy(blockedTileIds = emptySet()).supportedLayout() }
        assertFailsWith<IllegalArgumentException> { demo.copy(tileWidth = 21).supportedLayout() }
    }
    @Test fun binds_only_presentation_slots_and_preserves_gameplay_geometry_and_existing_layers() {
        val repo = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val scene = ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/maintenance-defense/construction"))
        val original = SandboxRenderResources.from(scene)
        val images = original.units.mapValues { (id, _) -> VisualUnitImages(
            Path.of("visuals/$id-body.png").toAbsolutePath(), Path.of("visuals/$id-back.png").toAbsolutePath(),
            Path.of("visuals/$id-turret.png").toAbsolutePath(), Path.of("visuals/$id-dead.png").toAbsolutePath()) }
        val prepared = PreparedVisualAssets(VisualAssetSelection(VisualAssetSource.ORIGINAL), "self-owned binding test", "fixed-generation",
            images, mapOf("construction-strip-5" to Path.of("visuals/terrain.png").toAbsolutePath()))
        val bound = original.withVisuals(prepared)
        assertEquals(original.widthTiles, bound.widthTiles)
        assertEquals(original.heightTiles, bound.heightTiles)
        for ((id, before) in original.units) {
            val after = bound.units.getValue(id)
            assertEquals(images.getValue(id).body, after.imagePath)
            assertEquals(1, after.totalFrames)
            assertEquals(before.displayRadius, after.displayRadius)
            assertEquals(before.imageScale, after.imageScale)
            assertEquals(before.turretMount, after.turretMount)
            assertEquals(before.defenseMuzzleOffset, after.defenseMuzzleOffset)
            if (before.backImagePath == null) assertNull(after.backImagePath)
            else assertEquals(images.getValue(id).back, after.backImagePath)
            if (before.turretImagePath == null) assertNull(after.turretImagePath)
            else assertEquals(images.getValue(id).turret, after.turretImagePath)
            if (before.deadImagePath == null) assertNull(after.deadImagePath)
            else assertEquals(images.getValue(id).dead, after.deadImagePath)
            assertNotEquals(before.imagePath, after.imagePath)
        }
        assertEquals(original.tileSets.single().firstGid, bound.tileSets.single().firstGid)
        assertEquals(original.tileSets.single().blockedTileIds, bound.tileSets.single().blockedTileIds)
        assertEquals(original.tileSets.single().resourcePoolTileIds, bound.tileSets.single().resourcePoolTileIds)
        assertEquals(Path.of("visuals/terrain.png").toAbsolutePath(), bound.tileSets.single().imagePath)
        assertNull(bound.tileSets.single().transparentColor)
        assertEquals(scene.unitDefinitions.getValue("builder").imagePath, original.units.getValue("builder").imagePath)
    }
}
