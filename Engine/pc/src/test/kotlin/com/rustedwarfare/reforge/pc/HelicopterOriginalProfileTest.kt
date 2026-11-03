package com.rustedwarfare.reforge.pc

import kotlin.test.*

class HelicopterOriginalProfileTest {
    @Test fun the_previous_v2_certificate_metadata_keeps_its_exact_frozen_bytes() {
        val frozen = mapOf(
            "original-profile-v2.json" to "a21b3f3ce3e080a5c66ede5115fd8b6f3b80ede798e9206d6d718e29c1ab2fae",
            "pc-original-images-profile-v2.json" to "7f51746072fb90bc470f9ccda95a839a419d95d9cc478a4a896ca9dac987f9f3",
            "pc-original-resources-profile-v2.json" to "2146d4e522bc599a209ff6b77ffed5c70a12d98e1b7da2a11fe666ab88a480b3")
        for ((name, hash) in frozen) {
            val bytes = assertNotNull(javaClass.getResourceAsStream("/visuals/$name"), "Keep the qualified v2 metadata")
                .use { it.readBytes() }
            assertEquals(hash, visualHash(bytes))
        }
    }

    @Test fun the_new_qualification_maps_only_the_five_finite_helicopter_and_air_factory_slots() {
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
        assertEquals("rw.android.1.15.176.visual.v3", profile.id)
        val pc = PcOriginalImageProfile.bundled()
        assertEquals("rw.pc.steam.9902063.images.v3", pc.id)
        assertEquals(profile.json.get("unitBindings").toString(), pc.mappedImages!!.json.get("unitBindings").toString())
        val current = PcOriginalResourceProfile.bundled()
        assertEquals("rw.pc.steam.9902063.resources.v3", current.id)
        val old = PcOriginalResourceProfile.versionOne()
        assertEquals(old.images.jarSha256, current.images.jarSha256)
        assertEquals(old.images.images, current.images.images)
        assertEquals(old.sounds, current.sounds)
        assertEquals(old.events, current.events)
    }
}
