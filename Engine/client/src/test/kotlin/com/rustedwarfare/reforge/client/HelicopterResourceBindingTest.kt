package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class HelicopterResourceBindingTest {
    @Test
    fun checked_content_retains_the_rotor_and_partial_hd_keeps_its_world_dimensions_and_other_slots() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val root = fixtures.resolve("helicopter-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("production-package"))).value
            val choice = ContentPackChoice(pack, "builder")
            val before = SandboxRenderResources.from(contentPackScenario(root, choice))
            val empty = PreparedVisualAssets(VisualAssetSelection(), "owned-helicopter", "fixed", emptyMap(),
                before.tileSets.associate { it.supportedLayout() to it.imagePath })
            val prepared = contentPackVisuals(empty, choice)
            val rotorId = ResourceId.parse("reforge:units/helicopter/rotor")
            assertTrue(rotorId in prepared.resourcePack.resources, "Real preparation must retain the rotor binding")
            val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
            val oldRotor = base.images.getValue(rotorId)
            assertEquals(ImageLogicalSize(46, 20), ImageLogicalSize(oldRotor.declaration.frameWidth, oldRotor.declaration.frameHeight))
            val body = base.images.getValue(ResourceId.parse("reforge:units/helicopter/body"))
            assertEquals(ImageLogicalSize(26, 46), ImageLogicalSize(body.declaration.frameWidth, body.declaration.frameHeight))
            val factory = base.images.getValue(ResourceId.parse("reforge:units/airfactory/body"))
            assertEquals(ImageLogicalSize(40, 61), ImageLogicalSize(factory.declaration.frameWidth, factory.declaration.frameHeight))
            assertFalse(ResourceId.parse("reforge:units/airfactory/back") in base.images)
            val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:rotor-hd","name":"Rotor HD","description":"Self-owned rotor binding check","icon":"reforge:units/helicopter/body"}""".toByteArray(), "pack.json")
            val hd = oldRotor.copy(path = Path.of("no-io/rotor-hd.png").toAbsolutePath(),
                pixelWidth = oldRotor.pixelWidth * 2, pixelHeight = oldRotor.pixelHeight * 2)
            val high = ResourceImageLayer(metadata, mapOf(rotorId to hd))
            val resolved = ResourceImageComposer.compose(base, listOf(high))
            val replaced = prepared.withResourceImages(resolved)
            assertEquals(hd.path, replaced.resourcePack.resolve(rotorId))
            assertEquals(metadata.id.value, resolved.providers.getValue(rotorId))
            for ((id, image) in base.images) if (id != rotorId) {
                assertEquals(image.path, replaced.resourcePack.resolve(id))
                assertEquals(base.metadata.id.value, resolved.providers.getValue(id))
            }
            val slot = UnitImageSlot.valueOf("ROTOR")
            val after = before.withVisuals(replaced)
            assertEquals(before.units.getValue("helicopter").drawSize(slot, oldRotor.pixelWidth, oldRotor.pixelHeight),
                after.units.getValue("helicopter").drawSize(slot, hd.pixelWidth, hd.pixelHeight))
            val bad = hd.copy(declaration = hd.declaration.copy(frameWidth = hd.declaration.frameWidth + 1))
            assertTrue(assertFailsWith<IllegalArgumentException> {
                ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(rotorId to bad))))
            }.message.orEmpty().contains(rotorId.value))
            assertEquals(oldRotor.path, prepared.resourcePack.resolve(rotorId))
        }
    }
}
