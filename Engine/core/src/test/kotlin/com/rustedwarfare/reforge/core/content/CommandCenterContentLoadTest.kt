package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class CommandCenterContentLoadTest {
    @Test fun malformed_native_center_fields_and_non_native_png_dimensions_are_rejected_with_field_identity() {
        val source = file()
        val text = Files.readString(source).replace(Regex("(?m)^(image|image_back|image_dead)=(.*)$")) {
            "${it.groupValues[1]}=${source.parent.resolve(it.groupValues[2].trim()).toAbsolutePath()}"
        }
        val path = Files.createTempFile("p006-center-", ".properties")
        val png = Files.createTempFile("p006-center-", ".png")
        try {
            // A structurally valid PNG with a real CRC, but not a four-frame native center sprite.
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", png.toFile())
            val cases = listOf(
                Triple(text.replace("maxHp=4000", ""), "maxHp", ContentErrorCode.MISSING_REQUIRED_FIELD),
                Triple(text + "\nunknown=1", "unknown", ContentErrorCode.UNSUPPORTED_FIELD),
                Triple(text + "\nincomePeriod=10", "incomePeriod", ContentErrorCode.MALFORMED_SYNTAX),
                Triple(text.replace("incomeCredits=4.5", "incomeCredits=NaN"), "incomeCredits", ContentErrorCode.MALFORMED_SYNTAX),
                Triple(text.replace("productionUnit=builder", "productionUnit=c_tank"), "productionUnit", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(text.replace(Regex("(?m)^image=.*$")) { "image=$png" }, "image", ContentErrorCode.BAD_REFERENCE),
                Triple(text.replace(Regex("(?m)^image_back=.*$")) { "image_back=$png" }, "image_back", ContentErrorCode.BAD_REFERENCE),
                Triple(text.replace(Regex("(?m)^image_dead=.*$")) { "image_dead=$png" }, "image_dead", ContentErrorCode.BAD_REFERENCE),
                Triple(text.replace(Regex("(?m)^image_dead=.*$"), "image_dead=missing-center.png"), "image_dead", ContentErrorCode.BAD_REFERENCE))
            for ((input, field, code) in cases) {
                Files.writeString(path, input)
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCommandCenter(path)).error
                assertEquals(code, error.code)
                assertEquals(field, error.field)
                assertEquals(path.toAbsolutePath().normalize().toString(), error.file)
            }
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(png) }
    }

    private fun file(): Path = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
        ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-001/20261027-intake-01/center-input-01/units/commandCenter.properties")

    @Test fun native_center_loads_its_real_income_production_missile_and_four_frame_visual_identity() {
        val center = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCommandCenter(file())).value
        assertEquals("commandCenter", center.id)
        assertEquals(4000.0, center.maximumHealth)
        assertEquals(30.0, center.radius)
        assertEquals(4, center.totalFrames)
        assertEquals("base.png", center.imagePath.fileName.toString())
        assertEquals("base_back.png", center.building!!.backImagePath!!.fileName.toString())
        assertEquals(BuildingFootprint(-1, -1, 1, 1), center.building.footprint)
        assertEquals(DirectHitTargetKind.NATIVE_BUILDING, center.directHitTargetKind)
        assertEquals(2500.0, center.durability!!.maxDamagePerHit)
        assertEquals(IncomeProfile(10f, 4.5f), center.income)
        assertEquals(ProductionProfile("builder"), center.production)
        val weapon = assertNotNull(center.combat)
        assertNull(weapon.turretImagePath)
        assertEquals(280.0, weapon.attackRange)
        assertEquals(70.0, weapon.shootDelay)
        assertEquals(70.0, weapon.projectile.directDamage)
        assertEquals(180.0, weapon.projectile.life)
        assertEquals(2.0, weapon.projectile.speed)
        assertEquals(NativeMissileProfile(), weapon.projectile.missile)
    }
}
