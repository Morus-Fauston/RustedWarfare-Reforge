package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import org.hjson.JsonObject
import org.hjson.JsonValue
import kotlin.test.*

class UnderwaterTargetContentTest {
    @Test
    fun wrong_native_scalars_missing_fields_and_bad_images_reject_at_the_real_field_and_reload_after_repair() = withCopy { root ->
        val input = root.resolve("units/naval/underwaterTarget.json")
        val original = Files.readString(input)
        data class Bad(val field: String, val code: ContentErrorCode, val change: (JsonObject) -> Unit)
        val cases = listOf(
            Bad("height", ContentErrorCode.UNSUPPORTED_FEATURE) { it.set("height", -7) },
            Bad("height", ContentErrorCode.MALFORMED_SYNTAX) { it.set("height", "-8") },
            Bad("height", ContentErrorCode.MISSING_REQUIRED_FIELD) { it.remove("height") },
            Bad("maxHp", ContentErrorCode.UNSUPPORTED_FEATURE) { it.set("maxHp", 1001) },
            Bad("radius", ContentErrorCode.UNSUPPORTED_FEATURE) { it.set("radius", 7) },
            Bad("name", ContentErrorCode.UNSUPPORTED_FEATURE) { it.set("name", "submarine") },
            Bad("productionUnits", ContentErrorCode.UNSUPPORTED_FIELD) { it.set("productionUnits", "heavyBattleship") },
            Bad("image", ContentErrorCode.BAD_REFERENCE) { it.set("image", "missing-target.png") })
        for (case in cases) {
            val document = JsonValue.readJSON(original).asObject()
            case.change(document)
            Files.writeString(input, document.toString())
            val failed = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), case.field)
            assertEquals(case.code, failed.error.code, case.field)
            assertEquals(case.field, failed.error.field)
            assertEquals(input.toString(), failed.error.file)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    @Test
    fun all_three_packages_load_the_explicit_fixed_depth_target_without_submarine_capabilities() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
                fixtures.resolve("naval-production/$format/production-package"))).value
            val target = pack.units.getValue("navalUnderwaterTarget")
            assertEquals(FixedUnderwaterTargetProfile(-8.0), target.fixedUnderwaterTarget)
            assertEquals(1000.0, target.maximumHealth)
            assertEquals(6.0, target.radius)
            assertEquals(MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), target.movement)
            assertNull(target.combat)
            assertNull(target.builder)
            assertNull(target.build)
            assertNull(target.production)
            assertNull(target.mech)
            assertNull(target.rotor)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }

    private fun withCopy(action: (Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("naval-production/json/production-package")
        val temporary = Files.createTempDirectory("p008-underwater-content-").toRealPath()
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
