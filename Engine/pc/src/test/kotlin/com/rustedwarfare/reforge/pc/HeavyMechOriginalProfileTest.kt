package com.rustedwarfare.reforge.pc

import kotlin.test.*

class HeavyMechOriginalProfileTest {
    @Test fun four_leg_and_named_turret_recipes_have_their_own_finite_slots_in_v4() {
        val profile = VisualAssetProfile.bundled()
        val heavy = assertNotNull(profile.json.get("unitBindings").get("mechHeavyMissile"))
        assertEquals(setOf("body", "turret", "leg", "foot", "weaponTurrets"), heavy.map { it.name }.toSet())
        val named = heavy.get("weaponTurrets")
        assertEquals(listOf("smallLaser"), named.map { it.name })
        fun size(id: String) = profile.recipes.getValue(id).get("target").asIntArray().toList()
        assertEquals(listOf(25, 39), size(heavy.getString("body")))
        assertEquals(listOf(35, 28), size(heavy.getString("turret")))
        assertEquals(listOf(11, 24), size(named.getString("smallLaser")))
        assertEquals(listOf(16, 68), size(heavy.getString("leg")))
        assertEquals(listOf(11, 18), size(heavy.getString("foot")))
        val factory = profile.json.get("unitBindings").get("mechFactoryT2")
        assertEquals(setOf("body", "back", "dead"), factory.map { it.name }.toSet())
        assertEquals(listOf(74, 106), size(factory.getString("body")))
        val t1 = profile.json.get("unitBindings").get("mechFactory")
        assertEquals(t1.getString("back"), factory.getString("back"))
        assertEquals(t1.getString("dead"), factory.getString("dead"))
        assertEquals("rw.android.1.15.176.visual.v4", profile.id)
        assertEquals("rw.pc.steam.9902063.images.v4", PcOriginalImageProfile.bundled().id)
        assertEquals("rw.pc.steam.9902063.resources.v4", PcOriginalResourceProfile.bundled().id)
    }

    @Test fun v3_certificate_metadata_keeps_its_exact_frozen_bytes() {
        val frozen = mapOf(
            "original-profile-v3.json" to "0aae02084f82106699971d17054573bc28f370d51730b01cce8155f253a81d7f",
            "pc-original-images-profile-v3.json" to "881f31dc239cbbc98ba1c54d6ff6d91406de611907a6ef74c307076349ba632f",
            "pc-original-resources-profile-v3.json" to "763d1b3f0d6a1ac7dfc9350673d9420c564fb39df152867e57a3b87214ac0dc0")
        for ((name, hash) in frozen) {
            val bytes = assertNotNull(javaClass.getResourceAsStream("/visuals/$name")).use { it.readBytes() }
            assertEquals(hash, visualHash(bytes))
        }
    }
}
