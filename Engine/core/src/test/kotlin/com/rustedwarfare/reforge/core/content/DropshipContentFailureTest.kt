package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DropshipContentFailureTest {
    @TempDir lateinit var temporary: Path

    @Test fun invalid_native_values_capabilities_and_images_report_the_original_field_and_recover() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("air-transport-production/$format/production-package")
            val root = temporary.resolve(format)
            Files.walk(source).use { paths -> paths.forEach { path ->
                val target = root.resolve(source.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val input = root.resolve("units/dropship.$format")
            val original = Files.readString(input)
            fun replace(field: String, old: String, new: String): String =
                if (format == "ini") original.replace("$field=$old", "$field=$new")
                else original.replace("\"$field\": $old", "\"$field\": $new")
            val mutations = listOf(
                Triple("maxHp", ContentErrorCode.UNSUPPORTED_FEATURE, replace("maxHp", "500", "501")),
                Triple("buildSpeed", ContentErrorCode.MALFORMED_SYNTAX, replace("buildSpeed", "0.001", if (format == "ini") "NaN" else "\"NaN\"")),
                Triple("weapon", ContentErrorCode.UNSUPPORTED_FIELD,
                    if (format == "ini") original + "\nweapon=true\n" else original.replaceFirst("{", "{\"weapon\": true,")),
                Triple("image", ContentErrorCode.BAD_REFERENCE, original.replace("dropship.png", "missing.png")),
                Triple("image_dead", ContentErrorCode.BAD_REFERENCE, original.replace("dropship_dead.png", "air_factory_dead.png")),
                Triple("image_shadow", ContentErrorCode.BAD_REFERENCE, original.replace("dropship_shadow.png", "../../outside.png")),
            )
            for ((field, code, text) in mutations) {
                assertNotEquals(original, text)
                Files.writeString(input, text)
                val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), "$format/$field")
                assertEquals(code, result.error.code, "$format/$field: ${result.error}")
                assertEquals(input.toAbsolutePath().normalize().toString(), result.error.file)
                assertEquals(field, result.error.field)
                Files.writeString(input, original)
                assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
            }
        }
    }
}
