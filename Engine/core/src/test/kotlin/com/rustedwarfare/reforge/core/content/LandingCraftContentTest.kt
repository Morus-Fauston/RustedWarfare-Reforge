package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class LandingCraftContentTest {
    @TempDir lateinit var temporary: Path

    @Test fun native_transport_content_rejects_unknown_capabilities_bad_values_and_bad_images_with_source_locations() {
        val fixtures = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val source = fixtures.resolve("transport-production/$format/production-package/units")
            for (case in listOf("unknown", "wrong-hp", "bad-image", "missing-image")) {
                val root = Files.createDirectory(temporary.resolve("$format-$case"))
                for (name in listOf("hovercraft.$format", "hovercraft.png", "hovercraft_dead.png")) Files.copy(source.resolve(name), root.resolve(name))
                val file = root.resolve("hovercraft.$format")
                val original = Files.readString(file)
                val altered = when (case) {
                    "unknown" -> if (format == "ini") "$original\nweapons=laser\n" else original.replaceFirst("{", "{\n\"weapons\": \"laser\",")
                    "wrong-hp" -> original.replace("450", "451")
                    else -> original
                }
                Files.writeString(file, altered)
                if (case == "bad-image") Files.write(root.resolve("hovercraft.png"), byteArrayOf(1, 2, 3))
                if (case == "missing-image") Files.writeString(file, altered.replace("hovercraft.png", "absent.png"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadHovercraftWithSources(file), "$format/$case").error
                assertNotNull(error.field, "$format/$case: $error")
                assertTrue(error.toString().contains(file.fileName.toString()), "$format/$case: $error")
            }
        }
    }
}
