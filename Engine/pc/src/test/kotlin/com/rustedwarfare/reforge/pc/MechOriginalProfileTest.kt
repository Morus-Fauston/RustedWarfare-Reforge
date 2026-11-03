package com.rustedwarfare.reforge.pc

import kotlin.test.*

class MechOriginalProfileTest {
    @Test fun the_previous_certificate_keeps_its_exact_frozen_metadata_and_the_original_source_collections() {
        val frozen = mapOf(
            "original-profile-v1.json" to "5040596d6d04c882459bc92c25d90d9708567014fb720ffb3d26ba0c20463086",
            "pc-original-images-profile-v1.json" to "8d01adee4760a437f7a21ea34d614351ff49a0ff9b0ff38124975ee6dffa285e",
            "pc-original-resources-profile-v1.json" to "ec5ada10b75df89654e8563ace562f59666a8dff076f3167c6a1b9179f631076")
        for ((name, hash) in frozen) {
            val bytes = checkNotNull(javaClass.getResourceAsStream("/visuals/$name")).use { it.readBytes() }
            assertEquals(hash, visualHash(bytes), "The previously qualified profile cannot be rewritten")
        }
        val legacy = PcOriginalResourceProfile.versionOne()
        val current = PcOriginalResourceProfile.bundled()
        assertEquals("rw.pc.steam.9902063.resources.v1", legacy.id)
        assertEquals("rw.pc.steam.9902063.images.v1", legacy.images.id)
        assertEquals(32, legacy.images.mappedImages!!.recipes.size)
        assertEquals(38, current.images.mappedImages!!.recipes.size)
        assertEquals(legacy.images.jarSha256, current.images.jarSha256)
        assertEquals(legacy.images.images, current.images.images)
        assertEquals(legacy.sounds, current.sounds)
        assertEquals(legacy.events, current.events)
    }
    @Test fun current_qualified_mapping_contains_the_two_new_objects_and_the_exact_six_static_slots() {
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
        assertEquals("rw.pc.steam.9902063.images.v2", pc.id)
        assertEquals(profile.json.get("unitBindings").toString(), pc.mappedImages!!.json.get("unitBindings").toString())
        assertEquals("rw.pc.steam.9902063.resources.v2", PcOriginalResourceProfile.bundled().id)
    }
}
