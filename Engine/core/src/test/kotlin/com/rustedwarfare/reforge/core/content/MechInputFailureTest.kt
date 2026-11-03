package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class MechInputFailureTest {
    @Test
    fun every_format_rejects_missing_or_extra_legs_unknown_fields_nonfinite_values_and_can_reload_after_repair() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copyPack(format)
            val input = root.resolve("units/mech.$format")
            val valid = Files.readString(input)
            fun structured(edit: (org.hjson.JsonObject) -> Unit): String =
                JsonValue.readHjson(valid).asObject().also(edit).toString()
            val missing = if (format == "ini") valid.substringBefore("[leg_2]") else structured { it.remove("leg_2") }
            val extra = valid.replace("leg_2", "leg_3")
            val unknown = valid.replace("resetAngle", "unapprovedResetAngle")
            val nonfinite = if (format == "ini") valid.replace("heightSpeed: 0.65", "heightSpeed: NaN")
                else structured { it.get("leg_1").asObject().set("heightSpeed", "NaN") }
            for ((bad, code) in listOf(missing to ContentErrorCode.MISSING_REQUIRED_FIELD,
                extra to ContentErrorCode.UNSUPPORTED_FIELD, unknown to ContentErrorCode.UNSUPPORTED_FIELD,
                nonfinite to ContentErrorCode.MALFORMED_SYNTAX)) {
                assertNotEquals(valid, bad)
                Files.writeString(input, bad)
                val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), format)
                assertEquals(code, failure.error.code, "$format: ${failure.error}")
                Files.writeString(input, valid)
                assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
            }
        }
    }

    @Test
    fun leg_png_bytes_participate_in_source_stability_and_corrupt_or_escaping_images_do_not_publish_a_pack() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copyPack(format)
            val leg = root.resolve("units/leg.png")
            val original = Files.readAllBytes(leg)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            assertTrue(pack.sources.any { it.path == leg })
            Files.write(leg, byteArrayOf(1, 2, 3))
            val changed = assertIs<ContentResult.Failure>(pack.verifyStable())
            assertEquals(ContentErrorCode.BAD_REFERENCE, changed.error.code)
            assertEquals(leg.toString(), changed.error.file)
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.write(leg, original)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
            val input = root.resolve("units/mech.$format")
            val valid = Files.readString(input)
            Files.write(root.parent.resolve("outside.png"), original)
            Files.writeString(input, valid.replace("leg.png", "../../outside.png"))
            assertEquals(ContentErrorCode.BAD_REFERENCE,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.writeString(input, valid)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    private fun copyPack(format: String): Path {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("mech-production/$format/package")
        val temporary = Files.createTempDirectory("mech-bad-input-").toRealPath()
        val destination = temporary.resolve("package")
        Files.walk(source).use { files -> files.forEach { file ->
            val target = destination.resolve(source.relativize(file)).normalize()
            require(target.startsWith(destination))
            if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
        } }
        return destination
    }
}
