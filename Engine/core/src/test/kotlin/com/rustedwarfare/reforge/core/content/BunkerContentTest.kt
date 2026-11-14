package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import org.hjson.JsonValue
import kotlin.test.*

class BunkerContentTest {
    @TempDir lateinit var temporary: Path

    private fun copy(format: String, name: String): Path {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val source = inputs.resolve("bunker-production/$format/package")
        val target = temporary.resolve(name)
        Files.walk(source).use { paths -> paths.forEach { path ->
            val out = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(out) else Files.copy(path, out)
        } }
        return target
    }

    @Test fun an_ini_child_can_inherit_the_structured_deployed_bunker_shield() {
        val root = copy("json", "mixed")
        val child = root.resolve("deployed.ini")
        Files.writeString(child, "[core]\ncopyFrom: deployed.json\n")
        val result = ContentLoader.loadBunkerWithSources(child)
        val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(result, result.toString()).value
        assertEquals(ShieldProfile(500f, .15f, true), loaded.definition.shield)
        assertTrue(loaded.sources.any { it.path == root.resolve("deployed.json") })
    }

    @Test fun an_invalid_inherited_qualified_shield_points_to_the_declaring_parent_field() {
        val root = copy("json", "invalid-shield")
        val parent = root.resolve("invalid.json")
        Files.writeString(parent, """{"format":"rw-reforge.unit.v2","copyFrom":["deployed.json"],
            "abilities":[{"name":"shield","maximum":499}]}""")
        val child = root.resolve("child.hjson")
        Files.writeString(child, """{"format":"rw-reforge.unit.v2","copyFrom":["invalid.json"]}""")
        val error = assertIs<ContentResult.Failure>(ContentLoader.loadBunkerWithSources(child)).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
        assertEquals("ability_shield", error.section)
        assertEquals("maximum", error.field)
        assertEquals(parent.toString(), error.file)
    }

    @Test fun final_composition_rejects_a_missing_conversion_target_in_every_format() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copy(format, "missing-$format")
            val path = root.resolve("pack.json")
            val manifest = JsonValue.readJSON(Files.readString(path)).asObject()
            manifest.get("units").asArray().remove(1)
            Files.writeString(path, manifest.toString())
            val result = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root)))
            assertEquals(ContentErrorCode.BAD_REFERENCE, result.error.code)
            assertEquals("convertTo", result.error.field)
            assertEquals("mechBunkerDeployed", result.error.objectRef)
        }
    }

    @Test fun inherited_unsupported_values_report_the_real_source_and_bad_images_cannot_load() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copy(format, "invalid-$format")
            val path = root.resolve("bunker.$format")
            val old = Files.readString(path)
            Files.writeString(path, old.replace("0.0006", "0.0007"))
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, failure.error.code)
            assertEquals("buildSpeed", failure.error.field)
            assertEquals(path.toString(), failure.error.file)
            Files.writeString(path, old)
            Files.write(root.resolve("body.png"), byteArrayOf(1, 2, 3))
            assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root))
        }
    }

    @Test fun unknown_input_is_rejected_and_the_final_pack_identity_includes_the_inherited_leg_images() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copy(format, "unknown-$format")
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            assertTrue(loaded.sources.any { it.path == root.resolve("leg.png").toAbsolutePath() })
            Files.write(root.resolve("leg.png"), Files.readAllBytes(root.resolve("foot.png")))
            assertIs<ContentResult.Failure>(loaded.verifyStable())
            val path = root.resolve("bunker.$format")
            val old = Files.readString(path)
            val modified = if (format == "ini") old.replace("[core]", "[core]\nexecuteArbitraryScript: true") else {
                val json = JsonValue.readHjson(old).asObject()
                json.get("core").asObject().add("executeArbitraryScript", true)
                json.toString()
            }
            Files.writeString(path, modified)
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
        }
    }

    @Test fun normal_bunker_and_deployed_forms_share_the_three_format_content_and_inheritance_seam() {
        val inputs = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(inputs.resolve("bunker-production/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, "$format: $result").value
            assertEquals(setOf("mechBunker", "mechBunkerDeployed"), pack.units.keys)
            val mobile = pack.units.getValue("mechBunker")
            val fixed = pack.units.getValue("mechBunkerDeployed")
            assertEquals(800.0, mobile.maximumHealth)
            assertEquals(BuildProfile(4500, .0006f), mobile.build)
            assertEquals(MovementType.OVER_CLIFF, mobile.movement.type)
            assertEquals(.4, mobile.movement.moveSpeed)
            assertEquals(29.0, mobile.radius)
            assertEquals(200.0, mobile.combat!!.attackRange)
            assertEquals(70.0, mobile.combat!!.namedWeapons!!.byName.getValue("2").shootDelay)
            assertEquals(.006f, mobile.customActions!!.actions.single().rate)
            assertNull(mobile.building)
            assertNull(mobile.shield)
            assertEquals(MovementType.NONE, fixed.movement.type)
            assertEquals(0.0, fixed.movement.moveSpeed)
            assertEquals(23.0, fixed.radius)
            assertEquals(BuildingFootprint(-1,-1,1,1), fixed.building!!.footprint)
            assertEquals(500f, fixed.shield!!.maximum)
            assertEquals(.15f, fixed.shield!!.regeneration)
            assertEquals(240.0, fixed.combat!!.attackRange)
            assertEquals(50.0, fixed.combat!!.namedWeapons!!.byName.getValue("2").shootDelay)
            assertEquals(listOf(-3.0 to 3.0, 3.0 to 3.0, -3.0 to -3.0, 3.0 to -3.0), fixed.mech!!.legs.map { it.x to it.y })
            assertEquals(listOf("deploy", "undeploy"), fixed.customActions!!.actions.map { it.name })
            assertEquals(.010f, fixed.customActions!!.actions.last().rate)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
