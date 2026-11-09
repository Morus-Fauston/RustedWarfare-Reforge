package com.rustedwarfare.reforge.client

import java.nio.file.Path
import kotlin.test.*

class AmphibiousResourceBindingTest {
    @Test fun both_wings_survive_real_preparation_and_partial_hd_replacement_preserves_world_size() {
        for (format in listOf("ini", "json", "hjson")) {
            val loaded = AmphibiousSandboxScene.load(format)
            val before = SandboxRenderResources.from(loaded.scenario)
            val empty = PreparedVisualAssets(VisualAssetSelection(), "owned-amphibious", "fixed", emptyMap(),
                before.tileSets.associate { it.supportedLayout() to it.imagePath })
            val prepared = contentPackVisuals(empty, assertNotNull(loaded.contentPack))
            val left = ResourceId.parse("reforge:units/amphibiousjet/left_wing")
            val right = ResourceId.parse("reforge:units/amphibiousjet/right_wing")
            assertTrue(left in prepared.resourcePack.resources, "Left wing must reach the real resource pipeline")
            assertTrue(right in prepared.resourcePack.resources, "Right wing must reach the real resource pipeline")
            val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
            val old = base.images.getValue(left)
            assertEquals(30, old.declaration.frameWidth)
            assertEquals(14, old.declaration.frameHeight)
            assertEquals(1, old.declaration.totalFrames)
            assertEquals(1, base.images.getValue(ResourceId.parse("reforge:units/amphibiousjet/body")).declaration.totalFrames)
            val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:wing-hd","name":"Wing HD","description":"Owned wing binding","icon":"reforge:units/amphibiousjet/left_wing"}""".toByteArray(), "pack.json")
            val hd = old.copy(path = Path.of("no-io/left-hd.png").toAbsolutePath(), pixelWidth = 60, pixelHeight = 28)
            val resolved = ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(left to hd))))
            val replaced = prepared.withResourceImages(resolved)
            val after = before.withVisuals(replaced)
            val slot = UnitImageSlot.valueOf("LEFT_WING")
            assertEquals(hd.path, after.units.getValue("amphibiousJet").imageBindings()[slot]?.resource)
            assertEquals(before.units.getValue("amphibiousJet").drawSize(slot, 30, 14),
                after.units.getValue("amphibiousJet").drawSize(slot, 60, 28))
            assertEquals(base.images.getValue(right).path, replaced.resourcePack.resolve(right))
            assertFailsWith<IllegalArgumentException> {
                ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata,
                    mapOf(left to hd.copy(declaration = hd.declaration.copy(frameWidth = 15))))))
            }
        }
    }
}
