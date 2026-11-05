package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.*

class HelicopterContentFailureTest {
    @Test fun an_escaping_rotor_reference_is_rejected_at_the_declaration_field_and_can_reload_after_repair() {
        withCopy("package") { root ->
            val input = root.resolve("units/helicopter.json")
            val original = Files.readString(input)
            val document = JsonValue.readJSON(original).asObject()
            document.get("arm_1").asObject().set("image_end", "../../outside-rotor.png")
            Files.writeString(input, document.toString())
            val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
            assertEquals(ContentErrorCode.BAD_REFERENCE, result.error.code)
            assertEquals(input.toString(), result.error.file)
            assertEquals("arm_1", result.error.section)
            assertEquals("image_end", result.error.field)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    @Test fun the_air_factory_rejects_a_ground_product_with_the_same_name_and_can_recompose_after_repair() {
        withCopy("production-package") { root ->
            val input = root.resolve("units/helicopter.json")
            val manifest = root.resolve("pack.json")
            val original = Files.readString(input)
            val manifestOriginal = Files.readString(manifest)
            val document = JsonValue.readJSON(original).asObject()
            document.remove("arm_1")
            val movement = document.get("movement").asObject()
            for (field in listOf("targetHeight", "targetHeightDrift", "heightChangeRate", "startingHeightOffset", "moveSlidingDir", "reverseSpeedPercentage"))
                movement.remove(field)
            movement.set("movementType", "LAND").set("moveSlidingMode", false).set("moveIgnoringBody", false)
            document.get("attack").asObject().set("canAttackFlyingUnits", false)
            document.get("graphics").asObject().set("image_turret", "body.png")
            document.get("weapons").asArray().get(0).asObject().set("recoilOutTime", 4).set("recoilReturnTime", 7)
            Files.writeString(input, document.toString())
            val entries = JsonValue.readJSON(manifestOriginal).asObject()
            entries.get("units").asArray().get(0).asObject().set("type", "tank")
            Files.writeString(manifest, entries.toString())
            val packageResult = ContentLoader.loadContentPack(root)
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(packageResult, packageResult.toString()).value
            assertEquals(MovementType.LAND, pack.units.getValue("helicopter").movement.type)
            val result = assertIs<ContentResult.Failure>(ContentLoader.composeContentPacks(listOf(pack)))
            assertEquals(ContentErrorCode.BAD_REFERENCE, result.error.code)
            assertEquals("productionUnits", result.error.field)
            assertEquals("helicopter", result.error.objectRef)
            Files.writeString(input, original)
            Files.writeString(manifest, manifestOriginal)
            assertIs<ContentResult.Success<LoadedContentSet>>(ContentLoader.loadContentPacks(listOf(root)))
        }
    }

    @Test fun invalid_height_rate_reports_the_actual_field_and_can_reload_after_repair() {
        withCopy("package") { root ->
            val input = root.resolve("units/helicopter.json")
            val original = Files.readString(input)
            val document = JsonValue.readJSON(original).asObject()
            document.get("movement").asObject().set("heightChangeRate", -2)
            Files.writeString(input, document.toString())
            val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, result.error.code)
            assertEquals(input.toString(), result.error.file)
            assertEquals("movement", result.error.section)
            assertEquals("heightChangeRate", result.error.field)
            Files.writeString(input, original)
            assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root))
        }
    }

    private fun withCopy(packageName: String, action: (Path) -> Unit) {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("helicopter-production/json/$packageName")
        val temporary = Files.createTempDirectory("p008-helicopter-content-").toRealPath()
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
