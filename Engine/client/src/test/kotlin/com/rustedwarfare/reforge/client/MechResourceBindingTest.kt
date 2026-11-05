package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechResourceBindingTest {
    @Test
    fun checked_content_binds_legs_and_feet_and_partial_hd_keeps_their_world_dimensions() {
        for (format in listOf("ini", "json", "hjson")) {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val root = fixtures.resolve("mech-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val choice = ContentPackChoice(pack, "mechGun")
            val scenario = contentPackScenario(root, choice)
            val before = SandboxRenderResources.from(scenario)
            val empty = PreparedVisualAssets(VisualAssetSelection(), "owned-mech", "fixed", emptyMap(),
                before.tileSets.associate { it.supportedLayout() to it.imagePath })
            val prepared = contentPackVisuals(empty, choice)
            val legId = ResourceId.parse("reforge:units/mechgun/leg")
            val footId = ResourceId.parse("reforge:units/mechgun/foot")
            assertTrue(legId in prepared.resourcePack.resources, "The actual content preparation must retain the leg binding")
            assertTrue(footId in prepared.resourcePack.resources, "The actual content preparation must retain the foot binding")
            val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
            val oldLeg = base.images.getValue(legId)
            val oldFoot = base.images.getValue(footId)
            val body = base.images.getValue(ResourceId.parse("reforge:units/mechgun/body"))
            assertEquals(ImageLogicalSize(18, 31), ImageLogicalSize(body.declaration.frameWidth, body.declaration.frameHeight))
            assertEquals(ImageLogicalSize(47, 7), ImageLogicalSize(oldLeg.declaration.frameWidth, oldLeg.declaration.frameHeight))
            assertEquals(ImageLogicalSize(6, 18), ImageLogicalSize(oldFoot.declaration.frameWidth, oldFoot.declaration.frameHeight))
            val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:mech-hd","name":"Mech HD","description":"Self-owned binding check","icon":"reforge:units/mechgun/body"}""".toByteArray(), "pack.json")
            val hdLeg = oldLeg.copy(path = Path.of("no-io/leg-hd.png").toAbsolutePath(),
                pixelWidth = oldLeg.pixelWidth * 2, pixelHeight = oldLeg.pixelHeight * 2)
            val high = ResourceImageLayer(metadata, mapOf(legId to hdLeg))
            val resolved = ResourceImageComposer.compose(base, listOf(high))
            val replaced = prepared.withResourceImages(resolved)
            assertEquals(hdLeg.path, replaced.resourcePack.resolve(legId))
            assertEquals(oldFoot.path, replaced.resourcePack.resolve(footId))
            assertEquals(metadata.id.value, resolved.providers.getValue(legId))
            assertEquals(base.metadata.id.value, resolved.providers.getValue(footId))
            val after = before.withVisuals(replaced)
            for ((address, slotName) in listOf(legId to "LEG", footId to "FOOT")) {
                val slot = UnitImageSlot.valueOf(slotName)
                val old = base.images.getValue(address)
                val current = resolved.images.getValue(address)
                assertEquals(before.units.getValue("mechGun").drawSize(slot, old.pixelWidth, old.pixelHeight),
                    after.units.getValue("mechGun").drawSize(slot, current.pixelWidth, current.pixelHeight))
            }
            val bad = high.images.getValue(legId).let { it.copy(declaration = it.declaration.copy(frameWidth = it.declaration.frameWidth + 1)) }
            assertTrue(assertFailsWith<IllegalArgumentException> {
                ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(legId to bad))))
            }.message.orEmpty().contains(legId.value))
            assertEquals(oldFoot.path, prepared.resourcePack.resolve(footId), "Failed preparation cannot mutate the good generation")
        }
    }
}
