package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class DropshipOriginalProfileTest {
    @Test fun landing_craft_uses_complete_fixed_pc_body_dead_and_shadow_recipes() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("dropship"))
        assertEquals(setOf("body", "dead", "shadow"), bindings.map { it.name }.toSet())
        for ((slot, filename) in listOf("body" to "dropship.png", "dead" to "dropship_dead.png", "shadow" to "dropship_shadow.png")) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("res/drawable/$filename", source.getString("path"))
            assertEquals(listOf(0, 0, 45, 47), recipe.get("rect").asIntArray().toList())
            assertEquals(listOf(45, 47), recipe.get("target").asIntArray().toList())
            assertEquals(0, recipe.getInt("rotate", 0))
        }
    }

    @Test fun all_three_owned_content_baselines_accept_the_native_transport_layout() {
        for (format in listOf("ini", "json", "hjson")) ResourceImagePackApplicationFixture().use { fixture ->
            val root = Path.of(System.getProperty("public.fixtures.dir")).parent.resolve("inputs/air-transport-production/$format/production-package")
            val choice = ContentPackChoice(assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value, "commandCenter")
            val layouts = listOf(OwnPackImage("reforge:units/dropship/body", 1, 45, 47),
                OwnPackImage("reforge:units/dropship/dead", 1, 45, 47), OwnPackImage("reforge:units/dropship/shadow", 1, 45, 47))
            val pack = fixture.create("owned:native-air-transport-layout", layouts)
            val backend = fixture.backend()
            val selection = backend.initialSelection.copy(packs = ResourcePackSelection(listOf(pack.catalogEntry), listOf(pack.catalogEntry.id)))
            val prepared = backend.prepareForContent(selection, choice)
            val images = assertNotNull(prepared.resolvedImages)
            for (layout in layouts) assertEquals(pack.metadata.id.value, images.providers[ResourceId.parse(layout.id)])
            prepared.verifyForPublication()
        }
    }
}
