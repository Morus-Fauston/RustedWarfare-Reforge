package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SubmarineContentTest {
    @Test fun a_structured_locomotion_error_points_to_its_inherited_source() = withSubmarinePackage { root ->
        val child = root.resolve("sub.json")
        val parent = root.resolve("parent.hjson")
        Files.writeString(parent, Files.readString(child).replace("water", "air-water"))
        Files.writeString(child, """{"format":"rw-reforge.native-submarine.v1","copyFrom":["parent.hjson"]}""")
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
        assertEquals("locomotion", error.field)
        assertEquals(parent.toString(), error.file)
        assertTrue(error.reason.contains(child.toString()))
    }

    @Test fun three_formats_reject_missing_unknown_changed_native_and_bad_image_fields_at_their_sources() {
        for (format in listOf("ini", "json", "hjson")) withSubmarinePackage(format) { root ->
            val file = root.resolve("sub.$format")
            val original = Files.readString(file)
            val mutations = if (format == "ini") listOf(
                Triple(original.replace("maxHp=260\n", ""), "maxHp", ContentErrorCode.MISSING_REQUIRED_FIELD),
                Triple(original + "\nteleport=true", "teleport", ContentErrorCode.UNSUPPORTED_FIELD),
                Triple(original.replace("price=800", "price=801"), "price", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(original.replace("body.png", "missing.png"), "image", ContentErrorCode.BAD_REFERENCE))
            else listOf(
                Triple(original.replace(",\"locomotion\":\"water\"", ""), "locomotion", ContentErrorCode.MISSING_REQUIRED_FIELD),
                Triple(original.replace("\"water\"", "\"air-water\""), "locomotion", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(original.replace("\"water\"", "1"), "locomotion", ContentErrorCode.MALFORMED_SYNTAX),
                Triple(original.replace("\"maxHp\":260,", ""), "maxHp", ContentErrorCode.MISSING_REQUIRED_FIELD),
                Triple(original.replaceFirst("{", "{\"teleport\":true,"), "teleport", ContentErrorCode.UNSUPPORTED_FIELD),
                Triple(original.replace("\"price\":800", "\"price\":801"), "price", ContentErrorCode.UNSUPPORTED_FEATURE),
                Triple(original.replace("body.png", "missing.png"), "image", ContentErrorCode.BAD_REFERENCE))
            for ((text, field, code) in mutations) {
                assertNotEquals(original, text)
                Files.writeString(file, text)
                val result = ContentLoader.loadContentPack(root)
                val error = assertIs<ContentResult.Failure>(result, "$format/$field: $result").error
                assertEquals(code, error.code, "$format/$field: $error")
                assertEquals(field, error.field)
                assertEquals(file.toAbsolutePath().toString(), error.file)
            }
            Files.writeString(file, original)
            val manifest = root.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"submarine\"", "\"unbounded-submarine\""))
            assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root), "$format unknown capability")
        }
    }

    @Test fun three_formats_load_the_same_playable_native_submarine_and_capture_its_sources() {
        for (format in listOf("ini", "json", "hjson")) withSubmarinePackage(format) { root ->
            val result = ContentLoader.loadContentPack(root)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            val unit = pack.units.getValue("attackSubmarine")
            assertEquals("NativeSubmarine", unit.className)
            assertEquals(260.0, unit.maximumHealth)
            assertEquals(15.0, unit.radius)
            assertEquals(13.0, unit.displayRadius)
            assertEquals(MovementType.WATER, unit.movement.type)
            assertEquals(.8, unit.movement.moveSpeed)
            assertEquals(.6, unit.movement.reverseSpeedPercentage)
            assertEquals(BuildProfile(800L, .001f), unit.build)
            assertEquals(250.0, unit.combat!!.attackRange)
            assertEquals(42.0, unit.combat.projectile.directDamage)
            assertNull(unit.fixedUnderwaterTarget)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
            Files.writeString(root.resolve("sub.$format"), "broken")
            assertIs<ContentResult.Failure>(pack.verifyStable())
        }
    }
}

internal fun withSubmarinePackage(format: String = "json", check: (Path) -> Unit) {
    val root = Files.createTempDirectory("reforge-submarine-").toRealPath()
    val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
    try {
        val image = fixtures.resolve("naval-production/ini/production-package/units/naval/underwater-target.png")
        // Reuse an owned PNG as a test image; this is not an original visual qualification.
        val ownedImage = if (Files.exists(image)) image else Files.walk(fixtures.resolve("naval-production/ini/production-package"))
            .use { paths -> paths.filter { it.fileName.toString().endsWith(".png") }.findFirst().orElseThrow() }
        Files.copy(ownedImage, root.resolve("body.png"))
        Files.copy(ownedImage, root.resolve("dead.png"))
        Files.writeString(root.resolve("pack.json"), """{
            "format":"rw-reforge.content-pack.v1", "id":"owned-submarine-$format", "name":"Owned submarine test",
            "units":[{"type":"submarine", "file":"sub.$format"}]
        }""")
        val values = linkedMapOf("format" to "rw-reforge.native-submarine.v1", "name" to "attackSubmarine",
            "maxHp" to "260", "radius" to "15", "price" to "800", "buildSpeed" to "0.001",
            "image" to "body.png", "image_dead" to "dead.png")
        val text = if (format == "ini") values.entries.joinToString("\n") { "${it.key}=${it.value}" }
            else "{" + (values + ("locomotion" to "water")).entries.joinToString(",") {
                "\"${it.key}\":" + if (it.key in setOf("maxHp", "radius", "price", "buildSpeed")) it.value else "\"${it.value}\""
            } + "}"
        Files.writeString(root.resolve("sub.$format"), text)
        check(root)
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
        } }
    }
}
