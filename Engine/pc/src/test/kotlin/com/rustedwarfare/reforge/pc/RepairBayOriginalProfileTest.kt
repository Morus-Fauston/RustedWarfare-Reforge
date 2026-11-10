package com.rustedwarfare.reforge.pc

import kotlin.test.*
import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import javax.imageio.ImageIO

class RepairBayOriginalProfileTest {
    @Test fun all_normal_repair_bay_packs_use_the_qualified_native_builder_body_and_dead_layouts() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            for (name in listOf("production-package", "normal-package")) {
                val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                    root.resolve("repairbay-production/$format/$name"))).value
                val builder = pack.units.getValue("builder")
                for (path in listOf(builder.imagePath, checkNotNull(builder.durability!!.deadImagePath))) {
                    val image = ImageIO.read(path.toFile())
                    assertEquals(20, image.width, "$format/$name/${path.fileName}")
                    assertEquals(20, image.height, "$format/$name/${path.fileName}")
                }
            }
        }
    }

    @Test fun repair_bay_maps_both_original_loose_images_at_native_dimensions() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("repairbay"))
        assertEquals(setOf("body", "dead"), bindings.map { it.name }.toSet())
        for ((slot, file, width) in listOf(Triple("body", "repair_bay.png", 62), Triple("dead", "repair_bay_dead.png", 60))) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("res/drawable/$file", source.getString("path"))
            assertEquals(listOf(width, 76), recipe.get("target").asIntArray().toList())
            assertEquals("crop", recipe.getString("operation"))
        }
    }
}
