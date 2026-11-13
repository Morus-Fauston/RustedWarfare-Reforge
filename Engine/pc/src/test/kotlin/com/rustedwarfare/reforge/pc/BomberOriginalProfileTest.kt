package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourceId
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class BomberOriginalProfileTest {
    @field:TempDir
    lateinit var cache: Path

    @Test fun the_base_sound_publisher_accepts_the_bomber_impact_event() {
        val resources = PcBaseSoundResources(cache).prepare()
        val event = resources.events.getValue(ResourceId.parse("reforge:events/bomber-impact"))
        assertEquals(ResourceId.parse("reforge:sounds/naval-missile-fire"), event.sound)
        assertTrue(event.sound in resources.sounds)
        resources.verify()
    }

    @Test fun the_fixed_pc_package_binds_the_bomber_frames_auto_shadow_and_hit_sound() {
        val resource = PcOriginalResourceProfile.bundled()
        assertEquals("res/raw/missile_hit.ogg", resource.events["reforge:events/bomber-impact"])
        val mapped = assertNotNull(resource.images.mappedImages)
        val bindings = mapped.json.get("unitBindings").get("bomber")
        assertEquals(setOf("body", "dead", "shadow"), bindings.map { it.name }.toSet())
        for (slot in bindings) {
            val recipe = mapped.recipes.getValue(slot.asString())
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            val file = if (slot.name == "dead") "base_dead.png" else "base.png"
            assertEquals("assets/units/bomber/$file", source.getString("path"))
            assertEquals(listOf(45, 47), recipe.get("target").asIntArray().toList())
            assertEquals(if (slot.name == "shadow") "alpha-shadow" else "crop", recipe.getString("operation"))
        }
    }
}
