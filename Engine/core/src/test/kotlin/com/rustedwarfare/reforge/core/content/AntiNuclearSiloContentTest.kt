package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AntiNuclearSiloContentTest {
    @Test
    fun normal_package_loads_the_ground_interceptor_and_its_image_closure_in_each_format() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val launcher = fixtures.resolve("nuclear-silo/$format/package")
            val directory = Files.createTempDirectory("anti-nuclear-silo-$format-")
            val images = setOf("body.png", "back.png", "dead.png", "arm1.png", "arm2.png")
            for (image in images) Files.copy(launcher.resolve(image), directory.resolve(image))
            val file = "antiNukeLauncherC.$format"
            Files.writeString(directory.resolve("pack.json"), """
                {"format":"rw-reforge.content-pack.v1","id":"owned-anti-nuclear-silo","name":"Ground interceptor",
                 "units":[{"type":"nuclear-silo","file":"$file"}]}
            """.trimIndent())
            val fields = mapOf(
                "format" to "rw-reforge.nuclear-silo.v1", "name" to "antiNukeLauncherC",
                "maxHp" to 2800, "radius" to 35, "footprint" to "-1,-1,1,1",
                "constructionFootprint" to "-1,-1,1,1", "movementType" to "NONE",
                "price" to 15000, "buildSpeed" to 0.001, "image" to "body.png",
                "image_back" to "back.png", "image_dead" to "dead.png",
                "image_arm1" to "arm1.png", "image_arm2" to "arm2.png",
            )
            val declaration = if (format == "ini") fields.entries.joinToString("\n") { (key, value) -> "$key=$value" }
                else fields.entries.joinToString(",\n", "{\n", "\n}") { (key, value) ->
                    "\"$key\":" + if (value is String) "\"$value\"" else value.toString()
                }
            Files.writeString(directory.resolve(file), declaration)
            val result = ContentLoader.loadContentPack(directory)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format $result").value
            val interceptor = pack.units.getValue("antiNukeLauncherC")
            assertEquals(2800.0, interceptor.maximumHealth)
            assertEquals(ConstructionProfile(15000L, .001f), interceptor.construction)
            assertEquals(BuildingFootprint(-1, -1, 1, 1), interceptor.building?.placementFootprint)
            assertNull(interceptor.combat)
            assertNull(interceptor.pointDefense)
            assertEquals(images + setOf("pack.json", file), pack.sources.map { it.path.fileName.toString() }.toSet())
        }
    }
}
