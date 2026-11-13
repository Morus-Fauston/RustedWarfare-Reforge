package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class ShieldContentTest {
    @Test fun invalid_shield_values_and_unknown_shield_capabilities_keep_their_field_and_source() {
        for ((field, value, code) in listOf(
            Triple("maxShield", "-1", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("maxShield", "0", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("maxShield", "1e100", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("shieldRegen", "-0.08", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("shieldRegen", "1e100", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("shieldRegenDelay", "60", ContentErrorCode.UNSUPPORTED_FIELD))) withHeavyShieldPackage { root ->
            val author = root.resolve("units/heavy.json")
            val document = JsonValue.readJSON(Files.readString(author)).asObject()
            document.get("core").asObject().set(field, JsonValue.readJSON(value))
            Files.writeString(author, document.toString())
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
            assertEquals(code, error.code, field)
            assertEquals(field, error.field)
            assertEquals(author.toRealPath().toString(), error.file)
        }
    }

    @Test fun a_bad_shield_image_is_rejected_and_a_later_change_invalidates_the_loaded_pack() = withHeavyShieldPackage { root ->
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
        Files.write(root.resolve("units/shield.png"), byteArrayOf(1, 2, 3))
        assertIs<ContentResult.Failure>(pack.verifyStable())
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
        assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
        assertEquals("image_shield", error.field)
        assertEquals(root.resolve("units/shield.png").toRealPath().toString(), error.file)
    }
    @Test fun a_positive_capacity_that_underflows_the_runtime_float_is_rejected_at_its_source() = withHeavyShieldPackage { root ->
        val author = root.resolve("units/heavy.json")
        val document = JsonValue.readJSON(Files.readString(author)).asObject()
        document.get("core").asObject().set("maxShield", 1e-100)
        Files.writeString(author, document.toString())
        val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
        assertEquals(ContentErrorCode.MALFORMED_SYNTAX, result.error.code)
        assertEquals("maxShield", result.error.field)
        assertEquals(author.toRealPath().toString(), result.error.file)
    }
    @Test fun current_three_format_heavy_packs_have_the_same_full_shield_definition() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(fixtures.resolve("heavy-mech-production/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            assertEquals(ShieldProfile(500f, .08f), pack.units.getValue("mechHeavyMissile").shield)
        }
    }

    @Test fun the_normal_heavy_definition_consumes_its_original_shield_capacity_and_regeneration() = withHeavyShieldPackage { root ->
        val result = ContentLoader.loadContentPack(root)
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        assertEquals(ShieldProfile(500f, .08f), pack.units.getValue("mechHeavyMissile").shield)
        assertEquals("shield.png", assertNotNull(pack.units.getValue("mechHeavyMissile").shieldImagePath).fileName.toString())
        assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
    }
}

internal fun withHeavyShieldPackage(check: (Path) -> Unit) {
    val root = Files.createTempDirectory("reforge-heavy-shield-").toRealPath()
    require(root.parent == Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
    try {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("heavy-mech-production/json/package")
        Files.walk(source).use { paths -> paths.forEach { file ->
            val destination = root.resolve(source.relativize(file)).normalize()
            require(destination.startsWith(root))
            if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination)
        } }
        val author = root.resolve("units/heavy.json")
        val value = JsonValue.readJSON(Files.readString(author)).asObject()
        value.get("core").asObject().set("maxShield", 500).set("shieldRegen", .08)
        Files.writeString(author, value.toString())
        check(root)
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
        } }
    }
}
