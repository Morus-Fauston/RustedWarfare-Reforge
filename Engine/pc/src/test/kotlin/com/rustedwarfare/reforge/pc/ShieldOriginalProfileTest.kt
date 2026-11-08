package com.rustedwarfare.reforge.pc

import kotlin.test.*

class ShieldOriginalProfileTest {
    @Test fun the_current_pc_mapping_binds_the_qualified_default_shield_without_relabeling_android_sources() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapping = assertNotNull(profile.images.mappedImages)
        val heavy = assertNotNull(mapping.json.get("unitBindings").get("mechHeavyMissile"))
        val recipe = mapping.recipes.getValue(assertNotNull(heavy.getString("shield", null)))
        assertEquals(listOf(92, 92), recipe.get("target").asIntArray().toList())
        val source = mapping.json.get("sources").single { it.getString("id") == recipe.getString("source") }
        assertEquals("res/drawable/shield_mid.png", source.getString("path"))
        assertEquals("9d5fa70ddd3475b261f588db2e38625bbe6804952b57a887530edb902f9d3bab", source.getString("sha256"))
        assertNull(VisualAssetProfile.bundled().json.get("unitBindings").get("mechHeavyMissile").get("shield"),
            "The new qualification is PC-only; frozen Android source claims remain unchanged")
    }
}
