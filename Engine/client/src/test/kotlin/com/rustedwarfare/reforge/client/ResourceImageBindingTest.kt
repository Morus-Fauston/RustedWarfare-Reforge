package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.client.demo.DemoScenario
import com.rustedwarfare.reforge.core.content.PngImages
import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourceImageBindingTest {
    @Test
    fun high_resolution_bindings_keep_each_real_unit_slot_world_size_and_all_five_atlas_layouts() {
        val repo = repo()
        val scenes = listOf(
            ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/maintenance-defense/turret")),
            ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/maintenance-defense/construction")),
            ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/content-movement-knowledge/P002-知识沙盒输入")),
            ControlledScenario.load(repo.resolve("SimulationTest/public/inputs/three-format-tanks/ini")),
            DemoScenario.load())
        val coveredSlots = mutableSetOf<UnitImageSlot>()
        val layouts = mutableSetOf<String>()
        for (scene in scenes) {
            val before = SandboxRenderResources.from(scene)
            val prepared = baseline(scene)
            val base = imageLayer(prepared)
            val hd = ResourceImageLayer(metadata("owned:hd"), base.images.mapValues { (id, image) ->
                // Deliberately nonexistent paths prove this binding operation is
                // pure. Actual PNG import and actual GL pixels are separate checks.
                image.copy(path = Path.of("does-not-exist/hd/${id.value.replace(':', '_')}.png").toAbsolutePath(),
                    pixelWidth = image.pixelWidth * 2, pixelHeight = image.pixelHeight * 2)
            })
            val resolved = ResourceImageComposer.compose(base, listOf(hd))
            val replacement = prepared.withResourceImages(resolved)
            assertSame(resolved, replacement.resolvedImages)
            val after = before.withVisuals(replacement)
            assertEquals(before.widthTiles, after.widthTiles)
            assertEquals(before.heightTiles, after.heightTiles)
            for ((id, old) in before.units) {
                val current = after.units.getValue(id)
                val binding = prepared.resourcePack.unitBindings.getValue(id)
                val slots = mapOf(UnitImageSlot.BODY to binding.body, UnitImageSlot.BACK to binding.back,
                    UnitImageSlot.TURRET to binding.turret, UnitImageSlot.DEAD to binding.dead)
                for ((slot, address) in slots) {
                    if (address == null) continue
                    coveredSlots += slot
                    val normal = base.images.getValue(address)
                    val high = resolved.images.getValue(address)
                    assertEquals(old.drawSize(slot, normal.pixelWidth / normal.declaration.totalFrames, normal.pixelHeight),
                        current.drawSize(slot, high.pixelWidth / high.declaration.totalFrames, high.pixelHeight), "$id/$slot")
                }
                assertEquals(old.displayRadius, current.displayRadius)
                assertEquals(old.imageScale, current.imageScale)
                assertEquals(old.turretImageScale, current.turretImageScale)
                assertEquals(old.turretMount, current.turretMount)
                assertEquals(old.defenseMuzzleOffset, current.defenseMuzzleOffset)
                assertEquals(old.recoil, current.recoil)
                assertEquals(old.shootDelay, current.shootDelay)
                assertEquals(old.totalFrames, current.totalFrames)
                assertEquals(hd.images.getValue(binding.body).path, current.imagePath)
                assertEquals(replacement.units.getValue(id).logicalImages, replacement.resourcePack.unitBindings.getValue(id).logicalImages)
            }
            for ((old, current) in before.tileSets.zip(after.tileSets)) {
                layouts += old.supportedLayout()
                assertEquals(old.tileWidth, current.tileWidth)
                assertEquals(old.tileHeight, current.tileHeight)
                assertEquals(old.tileWidth * 2, current.pixelTileWidth)
                assertEquals(old.tileHeight * 2, current.pixelTileHeight)
                assertEquals(old.firstGid, current.firstGid)
                assertEquals(old.blockedTileIds, current.blockedTileIds)
                assertEquals(old.resourcePoolTileIds, current.resourcePoolTileIds)
                assertEquals(old.tileCount, current.tileCount)
                assertEquals(old.columns, current.columns)
            }
        }
        assertEquals(setOf(UnitImageSlot.BODY, UnitImageSlot.BACK, UnitImageSlot.TURRET, UnitImageSlot.DEAD), coveredSlots)
        assertEquals(setOf("controlled-ground-40", "controlled-rock-35", "construction-strip-5", "controlled-strip-2", "demo-strip-4"), layouts)
    }

    @Test
    fun missing_required_logical_binding_cannot_publish_a_half_replaced_visual_set() {
        val scene = ControlledScenario.load(repo().resolve("SimulationTest/public/inputs/three-format-tanks/ini"))
        val prepared = baseline(scene)
        val base = imageLayer(prepared)
        val missing = prepared.resourcePack.unitBindings.values.first().body
        val result = ResourceImageComposer.compose(ResourceImageLayer(base.metadata, base.images - missing), emptyList())
        assertTrue(assertFailsWith<IllegalArgumentException> { prepared.withResourceImages(result) }
            .message.orEmpty().contains(missing.value))
    }

    private fun baseline(scene: Scenario): PreparedVisualAssets {
        val resources = SandboxRenderResources.from(scene)
        return PreparedVisualAssets(VisualAssetSelection(), "own-binding-fixture", "fixed-base",
            resources.units.mapValues { (_, value) -> VisualUnitImages(value.imagePath, value.backImagePath, value.turretImagePath,
                value.deadImagePath, value.totalFrames) }, resources.tileSets.associate { it.supportedLayout() to it.imagePath })
    }

    private fun imageLayer(prepared: PreparedVisualAssets): ResourceImageLayer {
        val frames = prepared.resourcePack.unitBindings.values.associate { it.body to it.totalFrames }
        return ResourceImageLayer(metadata("reforge:base"), prepared.resourcePack.resources.mapValues { (id, path) ->
            val dimensions = PngImages.inspect(Files.readAllBytes(path), allowRgb = true)
            val count = frames[id] ?: 1
            PreparedResourceImage(path, ResourceImageDeclaration(path.fileName.toString(), dimensions.width / count, dimensions.height, count),
                dimensions.width, dimensions.height)
        })
    }

    private fun metadata(id: String) = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"$id","name":"Binding fixture","description":"Self-owned mechanism data","icon":"reforge:units/c_tank/body"}""".toByteArray(), "pack.json")
    private fun repo() = System.getProperty("repo.root")?.let(Path::of) ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
}
