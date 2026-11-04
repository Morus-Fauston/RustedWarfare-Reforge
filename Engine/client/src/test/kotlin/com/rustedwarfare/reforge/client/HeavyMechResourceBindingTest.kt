package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class HeavyMechResourceBindingTest {
    @Test fun a_partial_hd_pack_replaces_the_small_turret_independently_and_keeps_its_original_draw_size() {
        val root = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("heavy-mech-production/json/package"))).value
        val scene = HeavyMechClientScene.scenario()
        val resources = SandboxRenderResources.from(scene)
        val empty = PreparedVisualAssets(VisualAssetSelection(), "heavy-owned", "heavy-owned", emptyMap(),
            resources.tileSets.associate { it.supportedLayout() to it.imagePath }) {}
        val prepared = contentPackVisuals(empty, ContentPackChoice(pack, "mechHeavyMissile"))
        val id = ResourceId.parse("reforge:units/mechheavymissile/weapons/smalllaser/turret")
        val parentId = ResourceId.parse("reforge:units/mechheavymissile/turret")
        assertTrue(id in prepared.resourcePack.resources, "The named small turret needs its own stable binding")
        val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
        val small = base.images.getValue(id)
        assertEquals(11, small.declaration.frameWidth)
        assertEquals(24, small.declaration.frameHeight)
        val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:heavy-hd","name":"Heavy HD","description":"Owned independent turret check","icon":"reforge:units/mechheavymissile/body"}""".toByteArray(), "pack.json")
        val hd = small.copy(path = Path.of("no-io/heavy-small-hd.png").toAbsolutePath(), pixelWidth = 22, pixelHeight = 48)
        val resolved = ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(id to hd))))
        val mapped = prepared.withResourceImages(resolved)
        val appearance = resources.withVisuals(mapped).units.getValue("mechHeavyMissile")
        SandboxSession(scene).use { session ->
            val view = session.observation
            val drawings = appearance.turretDrawings(view.units.single(), view.weapons)
            val smallDrawing = drawings.single { it.weaponName == "smallLaser" }
            val parent = drawings.single { it.weaponName == "1" }
            assertEquals(hd.path, smallDrawing.imagePath)
            assertEquals(ImageDrawSize(11.0, 24.0), smallDrawing.drawSize(22, 48))
            assertEquals(base.images.getValue(parentId).path, parent.imagePath)
            assertEquals(ImageDrawSize(35.0, 28.0), parent.drawSize(35, 28))
            assertEquals(listOf("smallLaser", "1"), drawings.map { it.weaponName })
        }
        val bad = hd.copy(declaration = hd.declaration.copy(frameWidth = 12))
        assertFailsWith<IllegalArgumentException> { ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(id to bad)))) }
        assertEquals(small.path, prepared.resourcePack.resolve(id), "A failed layer leaves the original generation intact")
    }
}
