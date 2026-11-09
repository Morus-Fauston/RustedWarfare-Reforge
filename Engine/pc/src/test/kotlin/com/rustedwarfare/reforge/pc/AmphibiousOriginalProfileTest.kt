package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.ResourcePackSelection
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AmphibiousOriginalProfileTest {
    @Test fun all_three_owned_content_baselines_accept_the_qualified_native_part_layouts() {
        for (format in listOf("ini", "json", "hjson")) ResourceImagePackApplicationFixture().use { fixture ->
            val root=Path.of(System.getProperty("public.fixtures.dir")).parent
                .resolve("inputs/amphibious-production/$format/production-package")
            val choice=ContentPackChoice(assertIs<ContentResult.Success<*>>(ContentLoader.loadContentPack(root)).value
                as com.rustedwarfare.reforge.core.content.LoadedContentPack,"commandCenter")
            val layouts=listOf(
                OwnPackImage("reforge:units/airfactoryt2/body",1,40,63),
                OwnPackImage("reforge:units/airfactoryt2/dead",1,40,61),
                OwnPackImage("reforge:units/amphibiousjet/body",1,15,26),
                OwnPackImage("reforge:units/amphibiousjet/dead",1,15,26),
                OwnPackImage("reforge:units/amphibiousjet/shadow",1,15,26),
                OwnPackImage("reforge:units/amphibiousjet/left_wing",1,30,14),
                OwnPackImage("reforge:units/amphibiousjet/right_wing",1,30,14))
            val pack=fixture.create("owned:native-amphibious-layout",layouts)
            val backend=fixture.backend()
            val selection=backend.initialSelection.copy(packs=ResourcePackSelection(listOf(pack.catalogEntry),listOf(pack.catalogEntry.id)))
            val prepared=backend.prepareForContent(selection,choice)
            val images=assertNotNull(prepared.resolvedImages)
            for (layout in layouts) {
                val id=ResourceId.parse(layout.id)
                assertEquals(pack.metadata.id.value,images.providers[id])
                val actual=images.images.getValue(id).declaration
                assertEquals(listOf(layout.width,layout.height,1),listOf(actual.frameWidth,actual.frameHeight,actual.totalFrames))
            }
            prepared.verifyForPublication()
        }
    }
    @Test fun native_jet_parts_and_t2_factory_use_fixed_pc_sources_and_lightning_uses_its_actual_sound() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(profile.images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("amphibiousJet"))
        assertEquals(setOf("body", "dead", "shadow", "leftWing", "rightWing"), bindings.map { it.name }.toSet())
        for ((slot, file, width, height) in listOf(
            listOf("body", "amphibious_jet.png", "26", "15"),
            listOf("dead", "amphibious_jet_dead.png", "26", "15"),
            listOf("shadow", "amphibious_jet_shadow.png", "26", "15"),
            listOf("leftWing", "amphibious_jet_p2.png", "14", "30"),
            listOf("rightWing", "amphibious_jet_p1.png", "14", "30"))) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("res/drawable/$file", source.getString("path"))
            assertEquals(listOf(0, 0, width.toInt(), height.toInt()), recipe.get("rect").asIntArray().toList())
            assertEquals(listOf(height.toInt(), width.toInt()), recipe.get("target").asIntArray().toList())
            assertEquals(90, recipe.getInt("rotate"))
            assertEquals(1, recipe.getInt("frames"))
        }
        val factory = mapped.json.get("unitBindings").get("airFactoryT2")
        assertNotNull(factory)
        val recipe = mapped.recipes.getValue(factory.getString("body"))
        val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
        assertEquals("res/drawable/air_factory_t2.png", source.getString("path"))
        assertEquals(listOf(0, 0, 40, 63), recipe.get("rect").asIntArray().toList())
        assertEquals("res/raw/lighting_burst.ogg", profile.events["reforge:events/amphibious-lightning-fire"])
    }

    @Test fun public_owned_lightning_has_playable_fallback_audio() {
        val ready = PcBaseSoundResources(Files.createTempDirectory("amphibious-base-sound-")).prepare()
        val event = assertNotNull(ready.events[ResourceId.parse("reforge:events/amphibious-lightning-fire")])
        assertTrue(ready.sounds.getValue(event.sound).frames > 0)
        ready.verify()
    }
}
