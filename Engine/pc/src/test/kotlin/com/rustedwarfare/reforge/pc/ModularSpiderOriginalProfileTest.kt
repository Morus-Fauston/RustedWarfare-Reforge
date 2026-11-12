package com.rustedwarfare.reforge.pc

import kotlin.test.*

class ModularSpiderOriginalProfileTest {
    @Test fun the_spider_chassis_gun_and_slot_overlay_resolve_to_qualified_pc_resources() {
        val resources = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(resources.images.mappedImages)
        val parent = mapped.json.get("unitBindings").get("modularSpider")
        val expected = mapOf(
            "body" to ("assets/units/modular_spider/body.png" to listOf(81, 113)),
            "shadow" to ("assets/units/modular_spider/body.png" to listOf(81, 113)),
            "dead" to ("assets/units/modular_spider/body_dead.png" to listOf(70, 90)),
            "slotOverlay" to ("assets/units/modular_spider/body_ui.png" to listOf(82, 114)),
            "leg" to ("assets/units/modular_spider/leg.png" to listOf(10, 84)),
            "foot" to ("assets/units/experimental_spider/foot.png" to listOf(17, 22)),
            "turret" to ("assets/units/shared/nano_turret.png" to listOf(11, 19)),
        )
        assertEquals(expected.keys, parent.map { it.name }.toSet())
        for (slot in parent) {
            val recipe = mapped.recipes.getValue(slot.asString())
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            val (path, size) = expected.getValue(slot.name)
            assertEquals(path, source.getString("path"))
            assertEquals(size, recipe.get("target").asIntArray().toList())
            assertEquals(if (slot.name == "shadow") "alpha-shadow" else "crop", recipe.getString("operation"))
        }
        val gun = mapped.json.get("unitBindings").get("modularSpider_gunturret").getString("turret")
        val turretSource = mapped.recipes.getValue(gun).getString("source")
        assertEquals("assets/units/turrets/turret_top_l2.png",
            mapped.json.get("sources").single { it.getString("id") == turretSource }.getString("path"))
        assertEquals("res/raw/firing3.ogg", resources.events["reforge:events/spider-gun-fire"])
    }
}
