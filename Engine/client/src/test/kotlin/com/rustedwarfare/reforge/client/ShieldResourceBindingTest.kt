package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class ShieldResourceBindingTest {
    @Test fun a_valid_shield_without_an_image_keeps_durability_but_has_no_shield_sprite() {
        val scene = HeavyMechClientScene.scenario()
        val scenario = scene.copy(unitDefinitions = scene.unitDefinitions.mapValues { (_, definition) ->
            UnitDefinition(definition.id, definition.className, definition.radius, definition.displayRadius,
                definition.totalFrames, definition.imagePath, definition.imageScale, definition.movement,
                definition.retainedUnconsumed, definition.fogOfWarSightRange, combat = definition.combat,
                durability = definition.durability, mech = definition.mech, shield = definition.shield)
        })
        val resources = SandboxRenderResources.from(scenario)
        val images = resources.units.mapNotNull { (id, appearance) ->
            appearance.shieldImagePath?.let { id to it }
        }.toMap()
        SandboxSession(scenario).use { session ->
            val unit = session.observation.units.single()
            assertEquals(500f, assertNotNull(unit.shield).current)
            assertNull(ShieldPresentation.image(unit, images))
            session.select(unit.position)
            assertTrue(assertNotNull(session.selectedUnitInfo).contains("Shield 500 / 500"))
        }
        SandboxSession(scene).use { session ->
            val normal = SandboxRenderResources.from(scene).units.mapNotNull { (id, appearance) ->
                appearance.shieldImagePath?.let { id to it }
            }.toMap()
            val drawing = assertNotNull(ShieldPresentation.image(session.observation.units.single(), normal))
            assertEquals(normal.getValue("mechHeavyMissile"), drawing.resource)
            assertEquals(.49f, drawing.opacity)
        }
    }

    @Test fun the_heavy_shield_is_an_independent_resource_slot_and_hd_keeps_its_world_size() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val root = fixtures.resolve("heavy-mech-production/$format")
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root.resolve("package"))).value
            val choice = ContentPackChoice(pack, "mechHeavyMissile")
            val scenario = HeavyMechClientScene.scenario().copy(unitDefinitions = pack.units)
            val before = SandboxRenderResources.from(scenario)
            val empty = PreparedVisualAssets(VisualAssetSelection(), "owned-shield", "fixed", emptyMap(),
                before.tileSets.associate { it.supportedLayout() to it.imagePath })
            val prepared = contentPackVisuals(empty, choice)
            val id = ResourceId.parse("reforge:units/mechheavymissile/shield")
            assertTrue(id in prepared.resourcePack.resources, "Shield images must reach the real preparation path")
            val base = ResourceImagePreparation.baseLayer(prepared, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
            val old = base.images.getValue(id)
            assertEquals(92, old.declaration.frameWidth)
            assertEquals(92, old.declaration.frameHeight)
            val metadata = ResourcePackMetadata.read("""{"formatVersion":1,"requiresResourceFormat":1,"id":"owned:shield-hd","name":"Shield HD","description":"Owned binding check","icon":"reforge:units/mechheavymissile/shield"}""".toByteArray(), "pack.json")
            val hd = old.copy(path = Path.of("no-io/shield-hd.png").toAbsolutePath(), pixelWidth = 184, pixelHeight = 184)
            val resolved = ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(id to hd))))
            val after = before.withVisuals(prepared.withResourceImages(resolved))
            val slot = UnitImageSlot.valueOf("SHIELD")
            val normalSize = before.units.getValue("mechHeavyMissile").drawSize(slot, 92, 92)
            assertEquals(58.160919540229884, normalSize.width, 1e-10)
            assertEquals(normalSize, after.units.getValue("mechHeavyMissile").drawSize(slot, 184, 184))
            assertEquals(hd.path, after.units.getValue("mechHeavyMissile").imageBindings()[slot]?.resource)
            val bad = hd.copy(declaration = hd.declaration.copy(frameWidth = 93))
            assertFailsWith<IllegalArgumentException> {
                ResourceImageComposer.compose(base, listOf(ResourceImageLayer(metadata, mapOf(id to bad))))
            }
            assertEquals(old.path, prepared.resourcePack.resolve(id), "A rejected replacement leaves the good generation intact")
        }
    }
}
