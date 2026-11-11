package com.rustedwarfare.reforge.pc

import kotlin.test.*

class NavalOriginalProfileTest {
    @Test fun frozen_resource_versions_load_from_their_bundled_qualification_chain() {
        val profiles = listOf(PcOriginalResourceProfile.versionOne(), PcOriginalResourceProfile.versionTwo(),
            PcOriginalResourceProfile.versionThree(), PcOriginalResourceProfile.versionFour(),
            PcOriginalResourceProfile.versionFive())
        assertEquals((1..5).map { "rw.pc.steam.9902063.resources.v$it" }, profiles.map { it.id })
    }

    @Test fun previous_v4_qualification_keeps_its_exact_published_metadata_bytes() {
        for ((file, hash) in mapOf(
            "original-profile-v4.json" to "c21f3e42c418a0645853ca24a269b7a90a4379a8c3dcb90fbe6ffd37bcae0758",
            "pc-original-images-profile-v4.json" to "3325d4bfc17cc1bbe9df49f99a6543ba2c7776a39826a3610e4fc04e7853c1d5",
            "pc-original-resources-profile-v4.json" to "0527c0a87918c71f8ef852900e2dfa023a8b9e3557850b709da478b8721d7275")) {
            val bytes = assertNotNull(javaClass.getResourceAsStream("/visuals/$file")).use { it.readBytes() }
            assertEquals(hash, visualHash(bytes))
        }
    }

    @Test fun current_profile_has_finite_naval_body_dead_shared_turret_and_distinct_original_sound_events() {
        val profile = VisualAssetProfile.bundled()
        assertEquals("rw.android.1.15.176.visual.v5", profile.id)
        assertEquals("rw.pc.steam.9902063.images.v7", PcOriginalImageProfile.bundled().id)
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
        assertEquals("rw.pc.steam.9902063.resources.v7", sounds.id)
        assertEquals("res/raw/large_gun_fire2.ogg", sounds.events["reforge:events/naval-main-fire"])
        assertEquals("res/raw/missile_fire.ogg", sounds.events["reforge:events/naval-missile-fire"])
    }
}
