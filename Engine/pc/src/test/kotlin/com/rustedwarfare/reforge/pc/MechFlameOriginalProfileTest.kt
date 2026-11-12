package com.rustedwarfare.reforge.pc

import kotlin.test.*

class MechFlameOriginalProfileTest {
    @Test fun the_fixed_pc_package_maps_the_flame_chassis_and_the_two_leg_assets() {
        val profile = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(profile.images.mappedImages)
        val bindings = mapped.json.get("unitBindings").get("mechFlame")
        assertEquals(setOf("body", "shadow", "leg", "foot"), bindings.map { it.name }.toSet())
        val expected = mapOf(
            "body" to ("topFlame.png" to listOf(45, 87)),
            "shadow" to ("topFlame.png" to listOf(45, 87)),
            "leg" to ("leg.png" to listOf(16, 68)),
            "foot" to ("foot.png" to listOf(17, 36)),
        )
        for (slot in bindings) {
            val recipe = mapped.recipes.getValue(slot.asString())
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            val (file, size) = expected.getValue(slot.name)
            assertEquals("assets/units/mechs_large/$file", source.getString("path"))
            assertEquals(size, recipe.get("target").asIntArray().toList())
            assertEquals(if (slot.name == "shadow") "alpha-shadow" else "crop", recipe.getString("operation"))
        }
    }
}
