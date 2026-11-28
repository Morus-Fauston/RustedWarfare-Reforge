package com.rustedwarfare.reforge.pc

import kotlin.test.*

class MechOriginalProfileTest {
    @Test fun current_qualified_mapping_contains_the_two_mech_objects_and_the_exact_six_static_slots() {
        val profile = VisualAssetProfile.bundled()
        val mech = assertNotNull(profile.json.get("unitBindings").get("mechGun"))
        assertEquals(setOf("body", "leg", "foot"), mech.map { it.name }.toSet())
        val factory = assertNotNull(profile.json.get("unitBindings").get("mechFactory"))
        assertEquals(setOf("body", "back", "dead"), factory.map { it.name }.toSet())
        fun size(id: String) = profile.recipes.getValue(id).get("target").asIntArray().toList()
        assertEquals(listOf(18, 31), size(mech.getString("body")))
        assertEquals(listOf(47, 7), size(mech.getString("leg")))
        assertEquals(90, profile.recipes.getValue(mech.getString("leg")).getInt("rotate"))
        assertEquals(listOf(6, 18), size(mech.getString("foot")))
        assertEquals(listOf(74, 106), size(factory.getString("body")))
        assertEquals(listOf(74, 106), size(factory.getString("back")))
        assertEquals(listOf(72, 88), size(factory.getString("dead")))
        val pc = PcOriginalImageProfile.bundled()
        assertEquals("rw.pc.steam.9902063.images.v8", pc.id)
        assertEquals("rw.pc.steam.9902063.resources.v8", PcOriginalResourceProfile.bundled().id)
    }
}
