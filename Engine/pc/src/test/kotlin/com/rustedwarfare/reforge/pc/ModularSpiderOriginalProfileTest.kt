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

    @Test fun every_new_weapon_module_resolves_the_qualified_pc_turret_and_sound_sources() {
        val resources = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(resources.images.mappedImages)
        val expected = mapOf(
            "smallgunturret" to ("shared/turret_small.png" to listOf(8, 34)),
            "smallgunturretT2" to ("shared/small_laser_turretT2.png" to listOf(14, 24)),
            "gunturretT2" to ("turrets/turret_top_l3.png" to listOf(16, 46)),
            "antiair" to ("turrets/anti_air_top.png" to listOf(16, 26)),
            "antiairT2" to ("turrets/anti_air_top_l3.png" to listOf(25, 24)),
            "antiairFlak" to ("turrets/anti_air_top_flakgun.png" to listOf(29, 32)),
        )
        for ((suffix, expectedSource) in expected) {
            val (path, dimensions) = expectedSource
            val binding = assertNotNull(mapped.json.get("unitBindings").get("modularSpider_$suffix"), suffix)
            val recipe = mapped.recipes.getValue(binding.getString("turret"))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("assets/units/$path", source.getString("path"))
            assertEquals(dimensions, recipe.get("target").asIntArray().toList())
        }
        assertEquals("res/raw/plasma_fire.ogg", resources.events["reforge:events/scout-plasma-fire"])
        assertEquals("res/raw/missile_fire.ogg", resources.events["reforge:events/naval-missile-fire"])
        assertEquals("res/raw/large_gun_fire2.ogg", resources.events["reforge:events/naval-main-fire"])
    }

    @Test fun lightning_and_artillery_use_their_distinct_pc_gun_base_and_sound_sources() {
        val resources = PcOriginalResourceProfile.bundled()
        val mapped = assertNotNull(resources.images.mappedImages)
        val bindings = mapped.json.get("unitBindings")
        val lightning = assertNotNull(bindings.get("modularSpider_lightning"))
        val artillery = assertNotNull(bindings.get("modularSpider_artillery"))
        val pictures = mapOf(
            lightning.getString("turret") to ("turrets/turret_top_lightning.png" to listOf(22, 31)),
            artillery.getString("turret") to ("experimental_spider/experimental_tank_turret2.png" to listOf(7, 35)),
            artillery.get("weaponTurrets").getString("mainCannonBase") to
                ("experimental_spider/spider_turret_base.png" to listOf(20, 19)),
        )
        for ((asset, expected) in pictures) {
            val recipe = mapped.recipes.getValue(asset)
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals("assets/units/${expected.first}", source.getString("path"))
            assertEquals(expected.second, recipe.get("target").asIntArray().toList())
        }
        assertEquals("res/raw/lighting_burst.ogg", resources.events["reforge:events/spider-lightning-fire"])
    }
}
