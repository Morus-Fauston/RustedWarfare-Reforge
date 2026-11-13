package com.rustedwarfare.reforge.pc

import javax.imageio.ImageIO
import kotlin.test.*

class AaBeamOriginalProfileTest {
    @Test fun the_pc_profile_binds_body_dead_auto_shadow_turret_and_all_three_beam_images() {
        val mapped = assertNotNull(PcOriginalResourceProfile.bundled().images.mappedImages)
        val bindings = assertNotNull(mapped.json.get("unitBindings").get("aaBeamGunship"))
        assertEquals(setOf("body","dead","shadow","turret","beam","beamStart","beamEnd"), bindings.map { it.name }.toSet())
        val expected = listOf(
            Triple("body","assets/units/aa_beam_gunship/base3.png",33 to 52),
            Triple("dead","assets/units/aa_beam_gunship/base3_dead.png",33 to 52),
            Triple("shadow","assets/units/aa_beam_gunship/base3.png",33 to 52),
            Triple("turret","assets/units/aa_beam_gunship/beam_turret.png",16 to 26),
            Triple("beam","assets/units/shared/beam3.png",13 to 20),
            Triple("beamStart","assets/units/shared/beam1_start.png",20 to 20),
            Triple("beamEnd","assets/units/shared/beam1_end.png",20 to 20))
        for ((slot,path,size) in expected) {
            val recipe = mapped.recipes.getValue(bindings.getString(slot))
            val source = mapped.json.get("sources").single { it.getString("id") == recipe.getString("source") }
            assertEquals(path,source.getString("path"))
            assertEquals(listOf(size.first,size.second),recipe.get("target").asIntArray().toList())
            assertEquals(if (slot == "shadow") "alpha-shadow" else "crop",recipe.getString("operation"))
            assertEquals(0,recipe.getInt("rotate",0))
        }
        val accelerated = assertNotNull(mapped.json.get("unitBindings").get("aaBeamGunship_afterburn"))
        assertEquals(bindings.map { it.name }.toSet() + "afterburnTrail", accelerated.map { it.name }.toSet())
        for ((slot, _, _) in expected) assertEquals(bindings.getString(slot), accelerated.getString(slot))
        val trail = mapped.recipes.getValue(accelerated.getString("afterburnTrail"))
        val source = mapped.json.get("sources").single { it.getString("id") == trail.getString("source") }
        assertEquals("assets/units/shared/glow_sharp.png", source.getString("path"))
        assertEquals(listOf(32,32), trail.get("target").asIntArray().toList())
        assertEquals("crop", trail.getString("operation"))
    }

    @Test fun auto_shadow_preserves_every_alpha_value_and_replaces_rgb_with_black() {
        val recipe = """"unitOutputs":{"body":{"file":"units/body.png","operation":"alpha-shadow","source":"own","rect":[0,0,4,1],"target":[4,1]}},"terrainOutputs":{}"""
        VisualAssetTestFixture(recipe).use { fixture ->
            val image = ImageIO.read(fixture.service().prepare(fixture.root).outputs.getValue("body").toFile())
            assertEquals(listOf(0xff000000.toInt(),0xff000000.toInt(),0,0x80000000.toInt()),(0..3).map { image.getRGB(it,0) })
        }
    }
}
