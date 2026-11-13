package com.rustedwarfare.reforge.pc

import kotlin.test.*

class MechEngineerOriginalProfileTest {
    @Test fun engineer_maps_the_blank_body_named_nano_dead_and_four_foot_assets_at_native_sizes() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("mechEngineer"))
        assertEquals(setOf("body", "turret", "dead", "leg", "foot", "weaponTurrets"), bindings.map { it.name }.toSet())
        assertEquals(setOf("nanoTurret"), bindings.get("weaponTurrets").map { it.name }.toSet())
        val slots = listOf(
            Triple("body", "assets/units/shared/blank.png", listOf(1, 1)),
            Triple("turret", "assets/units/mech_engineer/body.png", listOf(40, 44)),
            Triple("dead", "assets/units/mech_engineer/body_dead.png", listOf(38, 44)),
            Triple("leg", "assets/units/mech_engineer/leg.png", listOf(19, 82)),
            Triple("foot", "assets/units/mech_engineer/foot.png", listOf(26, 33)),
            Triple("nanoTurret", "assets/units/mech_engineer/cannon.png", listOf(28, 35)))
        for ((slot, path, dimensions) in slots) {
            val output = if (slot == "nanoTurret") bindings.get("weaponTurrets").getString(slot) else bindings.getString(slot)
            val recipe = mapped.recipes.getValue(output)
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals(path, source.getString("path"))
            assertEquals(dimensions, recipe.get("target").asIntArray().toList())
            assertEquals(listOf(0, 0) + dimensions, recipe.get("rect").asIntArray().toList())
            assertEquals("crop", recipe.getString("operation"))
        }
    }
}
