package com.rustedwarfare.reforge.pc

import kotlin.test.*

class NautilusOriginalProfileTest {
    @Test fun surface_land_and_experimental_factory_map_all_current_native_layers_and_leg_assets() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        for (type in listOf("nautilusSubmarineSurface", "nautilusSubmarineLand", "experimentalLandFactory")) {
            val bindings = assertNotNull(mapped.json.get("unitBindings").get(type), type)
            val factory = type == "experimentalLandFactory"
            assertEquals(if (factory) setOf("body", "back", "dead") else setOf("body", "dead", "leg", "foot"), bindings.map { it.name }.toSet())
            val slots = if (factory) listOf(Triple("body", "res/drawable/experimental_unit_factory_front.png", listOf(100,115)),
                Triple("back", "res/drawable/experimental_unit_factory_base.png", listOf(100,115)),
                Triple("dead", "res/drawable/experimental_unit_factory_dead.png", listOf(100,115)))
                else listOf(Triple("body", "assets/units/nautilus/body.png", listOf(42,90)),
                    Triple("dead", "assets/units/nautilus/body_dead.png", listOf(42,90)),
                    Triple("leg", "assets/units/nautilus/leg.png", listOf(16,68)),
                    Triple("foot", "assets/units/nautilus/foot.png", listOf(17,36)))
            for ((slot,path,dimensions) in slots) {
                val recipe = mapped.recipes.getValue(bindings.getString(slot))
                val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
                assertEquals(path, source.getString("path"))
                assertEquals(dimensions, recipe.get("target").asIntArray().toList())
                assertEquals(listOf(0,0)+dimensions, recipe.get("rect").asIntArray().toList())
            }
        }
    }

    @Test fun the_submerged_producer_maps_native_body_and_dead_images_without_public_pixel_copies() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("nautilusSubmarine"))
        assertEquals(setOf("body", "dead"), bindings.map { it.name }.toSet())
        for ((slot, filename) in listOf("body" to "body.png", "dead" to "body_dead.png")) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("assets/units/nautilus/$filename", source.getString("path"))
            assertEquals(listOf(42, 90), recipe.get("target").asIntArray().toList())
            assertEquals(listOf(0, 0, 42, 90), recipe.get("rect").asIntArray().toList())
            assertEquals("crop", recipe.getString("operation"))
        }
    }
}
