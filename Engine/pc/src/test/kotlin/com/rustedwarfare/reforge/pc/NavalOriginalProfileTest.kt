package com.rustedwarfare.reforge.pc

import kotlin.test.*

class NavalOriginalProfileTest {
    @Test fun current_resource_version_loads_from_bundled_qualification_chain() {
        assertEquals(listOf(8), PcOriginalProfileVersions.supportedVersions)
        assertEquals("rw.pc.steam.9902063.resources.v8", PcOriginalResourceProfile.bundled().id)
        for (legacy in 1..5) {
            assertNull(javaClass.getResourceAsStream("/visuals/pc-original-images-profile-v$legacy.json"))
            assertNull(javaClass.getResourceAsStream("/visuals/pc-original-resources-profile-v$legacy.json"))
        }
        for (legacy in 1..4) {
            assertNull(javaClass.getResourceAsStream("/visuals/original-profile-v$legacy.json"))
        }
    }

    @Test fun current_profile_has_finite_naval_body_dead_shared_turret_and_distinct_original_sound_events() {
        val profile = VisualAssetProfile.bundled()
        assertEquals("rw.android.1.15.176.visual.v5", profile.id)
        assertEquals("rw.pc.steam.9902063.images.v8", PcOriginalImageProfile.bundled().id)
        val bindings = profile.json.get("unitBindings")
        val ship = assertNotNull(bindings.get("heavyBattleship"))
        assertEquals(setOf("body", "dead", "turret"), ship.map { it.name }.toSet())
        fun size(id: String) = profile.recipes.getValue(id).get("target").asIntArray().toList()
        assertEquals(listOf(20, 102), size(ship.getString("body")))
        assertEquals(listOf(20, 102), size(ship.getString("dead")))
        assertEquals(listOf(12, 34), size(ship.getString("turret")))
        val t1 = assertNotNull(bindings.get("seaFactory"))
        val t2 = assertNotNull(bindings.get("seaFactoryT2"))
        for (factory in listOf(t1, t2)) {
            assertEquals(setOf("body", "dead"), factory.map { it.name }.toSet())
            assertEquals(listOf(90, 99), size(factory.getString("body")))
        }
        assertEquals(t1.getString("dead"), t2.getString("dead"))
        val sounds = PcOriginalResourceProfile.bundled()
        assertEquals("rw.pc.steam.9902063.resources.v8", sounds.id)
        assertEquals("res/raw/large_gun_fire2.ogg", sounds.events["reforge:events/naval-main-fire"])
        assertEquals("res/raw/missile_fire.ogg", sounds.events["reforge:events/naval-missile-fire"])
    }
}
