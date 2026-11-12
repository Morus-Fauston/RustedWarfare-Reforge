package com.rustedwarfare.reforge.pc

import kotlin.test.*

class NuclearOriginalProfileTest {
    @Test fun ground_interceptor_maps_all_five_current_pc_images_in_the_native_size() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapping = assertNotNull(profile.images.mappedImages)
        assertEquals("res/raw/buiding_explode.ogg", profile.events["reforge:events/nuke-intercept"])
        val silo = assertNotNull(mapping.json.get("unitBindings").get("antiNukeLauncherC"))
        assertEquals(setOf("body", "back", "dead", "arm1", "arm2"), silo.map { it.name }.toSet())
        for ((slot, size) in mapOf("body" to listOf(40, 48), "back" to listOf(40, 48),
            "dead" to listOf(40, 48), "arm1" to listOf(13, 11), "arm2" to listOf(12, 11))) {
            val recipe = mapping.recipes.getValue(silo.getString(slot))
            assertEquals(size, recipe.get("target").asIntArray().toList())
            assertEquals(listOf(0, 0) + size, recipe.get("rect").asIntArray().toList())
        }
    }

    @Test fun qualified_pc_silo_uses_native_body_back_dead_two_doors_and_both_nuclear_sounds() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapping = assertNotNull(profile.images.mappedImages)
        val silo = assertNotNull(mapping.json.get("unitBindings").get("nukeLauncherC"))
        assertEquals(setOf("body", "back", "dead", "arm1", "arm2"), silo.map { it.name }.toSet())
        for ((slot, size) in mapOf("body" to listOf(84,70), "back" to listOf(84,70),
            "dead" to listOf(84,70), "arm1" to listOf(38,29), "arm2" to listOf(42,29))) {
            val recipe = mapping.recipes.getValue(silo.getString(slot))
            assertEquals(size, recipe.get("target").asIntArray().toList())
            assertEquals(listOf(0,0) + size, recipe.get("rect").asIntArray().toList())
        }
        assertEquals("res/raw/nuke_launch.ogg", profile.events["reforge:events/nuke-launch"])
        assertEquals("res/raw/nuke_explode.ogg", profile.events["reforge:events/nuke-explode"])
    }
}
