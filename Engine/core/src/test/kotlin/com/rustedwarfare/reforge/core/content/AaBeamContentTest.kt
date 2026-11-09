package com.rustedwarfare.reforge.core.content

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

internal object AaBeamContentFixture {
    fun write(root: Path, format: String): Path {
        Files.createDirectories(root)
        for ((name, size) in linkedMapOf("body" to (33 to 52), "dead" to (33 to 52),
                "shadow" to (33 to 52), "turret" to (16 to 26), "beam" to (13 to 20),
                "beam_start" to (20 to 20), "beam_end" to (20 to 20))) {
            val image = BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(size.first / 2, size.second / 2, 0xff497f74.toInt())
            check(ImageIO.write(image, "png", root.resolve("$name.png").toFile()))
        }
        val fields = linkedMapOf("format" to "rw-reforge.aa-beam-gunship.v1", "name" to "aaBeamGunship",
            "maxHp" to "2500", "radius" to "22", "price" to "6000", "buildSpeed" to "0.0004761904761904762",
            "image" to "body.png", "image_dead" to "dead.png", "image_shadow" to "shadow.png",
            "image_turret" to "turret.png", "beam_image" to "beam.png", "beam_image_start" to "beam_start.png",
            "beam_image_end" to "beam_end.png")
        val text = if (format == "ini") fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
            else fields.entries.joinToString(",\n", "{\n", "\n}") { (key, value) ->
                "\"$key\": " + if (key in setOf("maxHp", "radius", "price", "buildSpeed")) value else "\"$value\""
            }
        Files.writeString(root.resolve("aa.$format"), text)
        Files.writeString(root.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1", "id":"owned-aa", "name":"Owned AA gunship",
            "units":[{"type":"aa-beam-gunship", "file":"aa.$format"}]
        }""")
        return root
    }
}

class AaBeamContentTest {
    @TempDir lateinit var temporary: Path

    @Test fun three_formats_load_the_qualified_aa_gunship_with_its_actual_production_and_instant_damage() {
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(AaBeamContentFixture.write(temporary.resolve(format), format))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            val unit = pack.units.getValue("aaBeamGunship")
            assertEquals(2500.0, unit.maximumHealth)
            assertEquals(22.0, unit.radius)
            assertEquals(BuildProfile(6000L, 1f / 2100f), unit.build)
            assertEquals(MovementType.AIR, unit.movement.type)
            assertNull(unit.rotor)
            val weapon = assertNotNull(unit.combat)
            assertEquals(280.0, weapon.attackRange)
            assertEquals(5.0, weapon.shootDelay)
            assertEquals(22.0, weapon.projectile.directDamage)
            assertEquals(.75, weapon.projectile.damageModifiers.shieldDamage)
            assertTrue(weapon.projectile.instant)
            assertFalse(weapon.projectile.lightning)
            assertEquals(9, pack.sources.size)
        }
    }

    @Test fun all_formats_reject_unknown_fields_bad_references_wrong_sizes_and_unqualified_values() {
        for (format in listOf("ini", "json", "hjson")) for (fault in listOf("unknown", "reference", "size", "value", "type")) {
            val root = AaBeamContentFixture.write(temporary.resolve("$format-$fault"), format)
            val file = root.resolve("aa.$format")
            val before = Files.readString(file)
            when (fault) {
                "unknown" -> Files.writeString(file, if (format == "ini") before + "\nunsupported=true\n"
                    else before.replace("\n}", ",\n\"unsupported\": true\n}"))
                "reference" -> Files.writeString(file, before.replace("beam.png", "../outside.png"))
                "size" -> check(ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", root.resolve("beam.png").toFile()))
                "value" -> Files.writeString(file, before.replace("2500", "2499"))
                "type" -> Files.writeString(file, before.replace("6000", "true"))
            }
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), "$format/$fault")
            assertTrue(failure.error.reason.isNotBlank(), "$format/$fault must explain its refusal")
        }
    }
}
