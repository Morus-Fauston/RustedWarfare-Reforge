package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import java.nio.file.Files
import kotlin.test.*

class HoverOriginalProfileTest {
    @Test fun current_pc_qualification_adds_native_hover_images_and_the_exact_plasma_sound() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapping = assertNotNull(profile.images.mappedImages)
        val hover = assertNotNull(mapping.json.get("unitBindings").get("hoverTank"))
        assertEquals(setOf("body", "dead", "shadow"), hover.map { it.name }.toSet())
        for (slot in listOf("body", "dead", "shadow")) {
            val recipe = mapping.recipes.getValue(hover.getString(slot))
            assertEquals(listOf(17, 24), recipe.get("target").asIntArray().toList())
        }
        assertEquals("res/raw/plasma_fire2.ogg", profile.events["reforge:events/hover-fire"])
        assertEquals(768, profile.images.images.size)
        assertEquals(30, profile.sounds.size)
    }

    @Test fun public_owned_audio_has_a_playable_hover_fallback_without_original_resources() {
        val ready = PcBaseSoundResources(Files.createTempDirectory("hover-base-sound-")).prepare()
        val event = assertNotNull(ready.events[ResourceId.parse("reforge:events/hover-fire")])
        assertTrue(ready.sounds.getValue(event.sound).frames > 0)
        ready.verify()
    }
}
