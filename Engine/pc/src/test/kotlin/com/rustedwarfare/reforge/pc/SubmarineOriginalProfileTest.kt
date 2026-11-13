package com.rustedwarfare.reforge.pc

import kotlin.test.*

class SubmarineOriginalProfileTest {
    @Test fun qualified_pc_submarine_body_and_dead_are_bound_at_their_native_dimensions_with_the_surface_missile_sound() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(profile.images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("attackSubmarine"))
        assertEquals(setOf("body", "dead"), bindings.map { it.name }.toSet())
        for ((slot, path, bytes, hash) in listOf(
            listOf("body", "res/drawable/attack_submarine.png", "776", "781ec1b35171d07ccb4d8c217ea2ca653e78237b0296486c99eea54251f9d85d"),
            listOf("dead", "res/drawable/attack_submarine_dead.png", "495", "71b370c9186510e3cfd04a97fb0e1abb4d94a704af9d7b3b61e3067682f4bfd2"))) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            assertEquals(listOf(12,40), recipe.get("target").asIntArray().toList())
            assertEquals(listOf(0,0,12,40), recipe.get("rect").asIntArray().toList())
            assertEquals(1, recipe.getInt("frames"))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals(path, source.getString("path")); assertEquals(bytes.toInt(), source.getInt("bytes"))
            assertEquals(hash, source.getString("sha256"))
        }
        assertEquals("res/raw/missile_fire.ogg", profile.events["reforge:events/naval-missile-fire"])
    }
}
