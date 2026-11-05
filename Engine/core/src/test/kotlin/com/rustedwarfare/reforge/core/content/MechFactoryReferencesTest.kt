package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class MechFactoryReferencesTest {
    @Test
    fun a_final_set_rejects_a_missing_mech_or_builder_instead_of_publishing_an_unusable_menu() {
        withCopy("production-package") { root ->
            val manifest = root.resolve("pack.json")
            val original = Files.readString(manifest)
            for ((index, missing) in listOf(0 to "mechGun", 2 to "builder")) {
                val document = JsonValue.readJSON(original).asObject()
                document.get("units").asArray().remove(index)
                Files.writeString(manifest, document.toString())
                val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)))
                assertEquals(ContentErrorCode.BAD_REFERENCE, result.error.code)
                assertEquals(missing, result.error.objectRef)
                Files.writeString(manifest, original)
                assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
            }
        }
    }

    @Test
    fun this_shared_leg_image_profile_rejects_a_different_second_leg_image_and_can_reload_after_repair() {
        withCopy("package") { root ->
            val input = root.resolve("units/mech.ini")
            val original = Files.readString(input)
            Files.copy(root.resolve("units/leg.png"), root.resolve("units/other-leg.png"))
            Files.writeString(input, original.replace("[leg_2]", "[leg_2]\nimage_leg: other-leg.png"))
            val rejected = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, rejected.error.code)
            assertEquals("leg_2", rejected.error.section)
            assertEquals("image_leg", rejected.error.field)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    private fun withCopy(packageName: String, action: (Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("mech-production/ini/$packageName")
        val temporary = Files.createTempDirectory("p008-mech-reference-").toRealPath()
        require(temporary.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
        try {
            Files.walk(source).use { paths -> paths.forEach { file ->
                val target = temporary.resolve(source.relativize(file)).normalize()
                require(target.startsWith(temporary))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
            action(temporary)
        } finally {
            for (path in Files.walk(temporary).use { it.sorted(Comparator.reverseOrder()).toList() }) {
                require(path.normalize().startsWith(temporary)); Files.delete(path)
            }
        }
    }
}
