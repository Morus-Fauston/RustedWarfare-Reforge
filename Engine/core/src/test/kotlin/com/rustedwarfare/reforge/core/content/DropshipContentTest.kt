package com.rustedwarfare.reforge.core.content

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DropshipContentTest {
    @TempDir lateinit var temporary: Path

    @Test fun a_content_pack_loads_the_native_unarmed_500_hp_air_transport() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = Files.createDirectory(temporary.resolve(format))
            for (name in listOf("body", "dead", "shadow")) {
                val image = BufferedImage(45, 47, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(22, 23, 0xff3b7e63.toInt())
                assertTrue(ImageIO.write(image, "png", root.resolve("$name.png").toFile()))
            }
            val fields = linkedMapOf("format" to "rw-reforge.native-dropship.v1", "name" to "dropship",
                "maxHp" to "500", "radius" to "20", "price" to "800", "buildSpeed" to "0.001",
                "image" to "body.png", "image_dead" to "dead.png", "image_shadow" to "shadow.png")
            val text = if (format == "ini") fields.entries.joinToString("\n") { "${it.key}=${it.value}" }
                else fields.entries.joinToString(",\n", "{\n", "\n}") {
                    "\"${it.key}\": " + (if (it.key in setOf("maxHp", "radius", "price", "buildSpeed")) it.value else "\"${it.value}\"")
                }
            Files.writeString(root.resolve("dropship.$format"), text)
            Files.writeString(root.resolve("pack.json"), """{
                "format":"rw-reforge.content-pack.v1", "id":"owned-dropship", "name":"Owned transport",
                "units":[{"type":"dropship", "file":"dropship.$format"}]
            }""")
            val result = ContentLoader.loadContentPack(root)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            val unit = pack.units.getValue("dropship")
            assertEquals(500.0, unit.maximumHealth)
            assertEquals(20.0, unit.radius)
            assertEquals(MovementType.AIR, unit.movement.type)
            assertEquals(BuildProfile(800L, .001f), unit.build)
            assertNotNull(unit.transport)
            assertNull(unit.combat)
            assertNull(unit.rotor)
        }
    }
}
