package com.rustedwarfare.reforge.pc

import kotlin.test.*

class HeavyMechOriginalProfileTest {
    @Test fun four_leg_and_named_turret_recipes_have_their_own_finite_slots_in_current_profile() {
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
        assertEquals("rw.android.1.15.176.visual.v5", profile.id)
        assertEquals("rw.pc.steam.9902063.images.v8", PcOriginalImageProfile.bundled().id)
        assertEquals("rw.pc.steam.9902063.resources.v8", PcOriginalResourceProfile.bundled().id)
    }
}
