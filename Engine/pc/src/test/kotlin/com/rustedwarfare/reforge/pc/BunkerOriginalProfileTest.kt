package com.rustedwarfare.reforge.pc

import kotlin.test.*

class BunkerOriginalProfileTest {
    @Test fun both_forms_bind_the_original_body_legs_shadow_and_second_weapon_with_a_deployed_shield() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        for (type in listOf("mechBunker", "mechBunkerDeployed")) {
            val bindings = assertNotNull(mapped.json.get("unitBindings").get(type), type)
            assertEquals(setOf("body", "leg", "foot", "shadow", "weaponTurrets") +
                if (type.endsWith("Deployed")) setOf("shield") else emptySet(), bindings.map { it.name }.toSet())
            assertEquals(listOf("2"), bindings.get("weaponTurrets").map { it.name })
            val slots = listOf(
                Triple("body", "topBunker.png", 39 to 49),
                Triple("shadow", "topBunker.png", 39 to 49),
                Triple("leg", "leg.png", 16 to 68),
                Triple("foot", "foot_small.png", 17 to 36),
                Triple("2", "bunkerTurret.png", 14 to 57))
            for ((slot, file, size) in slots) {
                val recipeId = if (slot == "2") bindings.get("weaponTurrets").getString(slot) else bindings.getString(slot)
                val recipe = mapped.recipes.getValue(recipeId)
                val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
                assertEquals("assets/units/mechs_large/$file", source.getString("path"))
                assertEquals(listOf(size.first, size.second), recipe.get("target").asIntArray().toList())
                assertEquals(if (slot == "shadow") "alpha-shadow" else "crop", recipe.getString("operation"))
                assertEquals(0, recipe.getInt("rotate", 0))
            }
            if (type.endsWith("Deployed")) assertEquals("heavy-shield", bindings.getString("shield"))
        }
    }
}
