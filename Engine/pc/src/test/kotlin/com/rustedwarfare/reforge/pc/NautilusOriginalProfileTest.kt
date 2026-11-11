package com.rustedwarfare.reforge.pc

import kotlin.test.*

class NautilusOriginalProfileTest {
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
