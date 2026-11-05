package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContentPackLoadTest {
    @Test
    fun a_local_pack_discovers_three_format_tanks_and_freezes_actual_metadata_and_images() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root, manifest ->
            val attempt = ContentLoader.loadContentPack(root)
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(attempt, attempt.toString()).value
            assertEquals("amber.scouts", loaded.id)
            assertEquals("Amber scouts", loaded.name)
            assertEquals(listOf("amber_scout"), loaded.units.keys.toList())
            val definition = loaded.units.getValue("amber_scout")
            assertEquals(210.0, definition.maximumHealth)
            assertEquals(25.0, definition.combat!!.projectile.directDamage)
            assertEquals(setOf(manifest, root.resolve("units/tank.$format"), root.resolve("units/tank.png"),
                root.resolve("units/tank_turret.png"), root.resolve("units/tank_dead.png")), loaded.sources.map { it.path }.toSet())
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            Files.write(root.resolve("units/tank.png"), byteArrayOf(1, 2, 3))
            assertIs<ContentResult.Failure>(loaded.verifyStable())
            assertEquals(210.0, loaded.units.getValue("amber_scout").maximumHealth)
        }
    }

    @Test
    fun malformed_duplicate_unknown_and_missing_metadata_never_enable_a_partial_pack() {
        withPack { root, manifest ->
            val original = Files.readString(manifest)
            for ((text, code) in listOf(
                "{" to ContentErrorCode.MALFORMED_SYNTAX,
                original.replace("\"id\":\"amber.scouts\"", "\"id\":\"amber.scouts\",\"id\":\"other\"") to ContentErrorCode.IDENTITY_CONFLICT,
                original.replace("\"units\":", "\"autoDownload\":true,\"units\":") to ContentErrorCode.UNSUPPORTED_FIELD,
                original.replace("\"id\":\"amber.scouts\",", "") to ContentErrorCode.MISSING_REQUIRED_FIELD,
                original.replace("\"amber.scouts\"", "4") to ContentErrorCode.MALFORMED_SYNTAX,
                original.replace("rw-reforge.content-pack.v1", "rw-reforge.content-pack.v99") to ContentErrorCode.UNSUPPORTED_FEATURE,
                original.replace("[{\"type\":\"tank\",\"file\":\"units/tank.ini\"}]", "[]") to ContentErrorCode.MALFORMED_SYNTAX,
            )) {
                Files.writeString(manifest, text)
                assertEquals(code, failure(root).code, text)
            }
            Files.writeString(manifest, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
            Files.copy(manifest, root.resolve("pack.hjson"))
            assertEquals(ContentErrorCode.IDENTITY_CONFLICT, failure(root).code)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(manifest))
        }
    }

    @Test
    fun escaped_repeated_and_unknown_entry_capabilities_are_rejected() {
        withPack { root, manifest ->
            val original = Files.readString(manifest)
            for (bad in listOf("../outside.ini", "/outside.ini", "units/../tank.ini", "C:/outside.ini", "units//tank.ini")) {
                Files.writeString(manifest, original.replace("units/tank.ini", bad))
                assertEquals(ContentErrorCode.BAD_REFERENCE, failure(root).code, bad)
            }
            Files.writeString(manifest, original.replace("\"type\":\"tank\"", "\"type\":\"java\""))
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure(root).code)
            Files.writeString(manifest, original.replace("\"units\":[", "\"units\":[{\"type\":\"tank\",\"file\":\"units/tank.ini\"},"))
            assertEquals(ContentErrorCode.IDENTITY_CONFLICT, failure(root).code)
            Files.copy(root.resolve("units/tank.ini"), root.resolve("units/other.ini"))
            Files.writeString(manifest, original.replace("\"units\":[", "\"units\":[{\"type\":\"tank\",\"file\":\"units/other.ini\"},"))
            val duplicate = failure(root)
            assertEquals(ContentErrorCode.IDENTITY_CONFLICT, duplicate.code)
            assertEquals("name", duplicate.field)
        }
    }

    @Test
    fun missing_corrupt_and_invalid_frame_images_fail_before_publication_and_can_be_repaired() {
        withPack { root, _ ->
            val image = root.resolve("units/tank.png")
            val bytes = Files.readAllBytes(image)
            Files.delete(image)
            val missing = failure(root)
            assertEquals(ContentErrorCode.BAD_REFERENCE, missing.code)
            assertEquals(root.resolve("units/tank.ini").toString(), missing.file)
            assertEquals("graphics", missing.section)
            assertEquals("image", missing.field)
            Files.write(image, byteArrayOf(1, 2, 3))
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure(root).code)
            Files.write(image, bytes)
            val input = root.resolve("units/tank.ini")
            val original = Files.readString(input)
            Files.writeString(input, original.replace("total_frames: 3", "total_frames: 7"))
            val invalid = failure(root)
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, invalid.code)
            assertEquals("total_frames", invalid.field)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    @Test
    fun mixed_templates_have_actual_identity_and_each_source_change_is_detected() {
        withPack("json") { root, manifest ->
            val input = root.resolve("units/tank.json")
            val base = root.resolve("units/base.hjson")
            val unit = JsonValue.readJSON(Files.readString(input)).asObject()
            unit.get("core").asObject().set("dont_load", true)
            Files.writeString(base, "# Reused cross-format parent\n" + unit.toString())
            Files.writeString(input, """{"format":"rw-reforge.unit.v1","copyFrom":["base.hjson"],"core":{"maxHp":300}}""")
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(manifest)).value
            assertEquals(300.0, loaded.units.getValue("amber_scout").maximumHealth)
            assertTrue(base in loaded.sources.map { it.path })
            for (source in loaded.sources) {
                val bytes = Files.readAllBytes(source.path)
                Files.write(source.path, bytes + byteArrayOf(32))
                assertIs<ContentResult.Failure>(loaded.verifyStable(), source.path.toString())
                Files.write(source.path, bytes)
                assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            }
            assertFailsWith<UnsupportedOperationException> { (loaded.units as MutableMap).clear() }
            assertFailsWith<UnsupportedOperationException> { (loaded.sources as MutableList).clear() }
        }
    }

    @Test
    fun external_templates_and_images_are_rejected_at_the_package_boundary() {
        withPack { root, _ ->
            val input = root.resolve("units/tank.ini")
            val original = Files.readString(input)
            val outside = Files.createTempFile("p008-owned-outside-", ".ini").toAbsolutePath().normalize()
            try {
                Files.writeString(outside, original)
                Files.writeString(input, original.replace("[core]", "[core]\ncopyFrom: ${outside.toString().replace('\\', '/')}"))
                assertEquals(ContentErrorCode.BAD_REFERENCE, failure(root).code)
                Files.writeString(input, original.replace("image: tank.png", "image: ${outside.toString().replace('\\', '/')}"))
                assertEquals(ContentErrorCode.BAD_REFERENCE, failure(root).code)
            } finally {
                require(outside.parent == root.parent && outside.fileName.toString().startsWith("p008-owned-outside-"))
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun unimplemented_unit_fields_and_implicit_replacements_remain_errors_in_every_format() {
        for (format in listOf("ini", "json", "hjson")) withPack(format) { root, _ ->
            val input = root.resolve("units/tank.$format")
            val text = Files.readString(input)
            if (format == "ini") {
                Files.writeString(input, text.replace("[core]", "[core]\nteleport: true"))
                assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, failure(root).code)
                Files.writeString(input, text.replace("[core]", "[core]\noverrideAndReplace: tank"))
            } else {
                val unit = JsonValue.readJSON(text).asObject()
                unit.get("core").asObject().set("teleport", true)
                Files.writeString(input, unit.toString())
                assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, failure(root).code)
                unit.get("core").asObject().remove("teleport")
                unit.get("core").asObject().set("overrideAndReplace", "tank")
                Files.writeString(input, unit.toString())
            }
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure(root).code)
        }
    }

    private fun failure(path: Path): ContentError = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(path)).error

    @Test
    fun an_invalid_image_path_is_a_located_content_error_instead_of_an_uncaught_path_exception() {
        withPack { root, _ ->
            val input = root.resolve("units/tank.ini")
            Files.writeString(input, Files.readString(input).replace("image: tank.png", "image: bad\u0000.png"))
            val error = failure(root)
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals(input.toString(), error.file)
            assertEquals("graphics", error.section)
            assertEquals("image", error.field)
        }
    }

    private fun withPack(format: String = "ini", check: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("p008-content-pack-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val units = root.resolve("units"); Files.createDirectory(units)
            val source = fixtures.resolve("three-format-tanks/$format/units")
            for (image in listOf("tank.png", "tank_dead.png", "tank_turret.png"))
                Files.copy(source.resolve(image), units.resolve(image))
            val text = Files.readString(source.resolve("tank.$format"))
            val custom = if (format == "ini") text.replace("name: c_tank", "name: amber_scout")
                .replace("overrideAndReplace: tank", "") else {
                val unit = (if (format == "hjson") JsonValue.readHjson(text) else JsonValue.readJSON(text)).asObject()
                unit.get("core").asObject().set("name", "amber_scout").remove("overrideAndReplace")
                unit.toString()
            }
            Files.writeString(units.resolve("tank.$format"), custom)
            val manifest = root.resolve(if (format == "hjson") "pack.hjson" else "pack.json")
            Files.writeString(manifest, (if (format == "hjson") "# Local author metadata\n" else "") + """
                {"format":"rw-reforge.content-pack.v1","id":"amber.scouts","name":"Amber scouts",
                 "units":[{"type":"tank","file":"units/tank.$format"}]}
            """.trimIndent())
            check(root, manifest)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(owned)
            } }
        }
    }
}
