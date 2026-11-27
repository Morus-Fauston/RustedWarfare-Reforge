package com.rustedwarfare.reforge.pc

import kotlin.test.*

class HelicopterOriginalProfileTest {
    @Test fun current_qualification_maps_the_five_finite_helicopter_and_air_factory_slots() {
        val profile = VisualAssetProfile.bundled()
        val helicopter = assertNotNull(profile.json.get("unitBindings").get("helicopter"))
        assertEquals(setOf("body", "rotor", "dead"), helicopter.map { it.name }.toSet())
        val factory = assertNotNull(profile.json.get("unitBindings").get("airFactory"))
        assertEquals(setOf("body", "dead"), factory.map { it.name }.toSet())
        fun size(id: String) = profile.recipes.getValue(id).get("target").asIntArray().toList()
        assertEquals(listOf(26, 46), size(helicopter.getString("body")))
        assertEquals(listOf(46, 20), size(helicopter.getString("rotor")))
        assertEquals(listOf(26, 46), size(helicopter.getString("dead")))
        assertEquals(listOf(40, 61), size(factory.getString("body")))
        assertEquals(listOf(40, 61), size(factory.getString("dead")))
        assertEquals(listOf(0, 0, 40, 61), profile.recipes.getValue(factory.getString("body")).get("rect").asIntArray().toList())
        assertEquals("rw.android.1.15.176.visual.v5", profile.id)
        val pc = PcOriginalImageProfile.bundled()
        assertEquals("rw.pc.steam.9902063.images.v8", pc.id)
        val current = PcOriginalResourceProfile.bundled()
        assertEquals("rw.pc.steam.9902063.resources.v8", current.id)
    }
}
