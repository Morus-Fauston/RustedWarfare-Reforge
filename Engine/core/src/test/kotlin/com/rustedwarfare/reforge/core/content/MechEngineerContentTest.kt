package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import org.hjson.JsonValue
import kotlin.test.*

class MechEngineerContentTest {
    @TempDir lateinit var temporary: Path

    private fun copy(format: String, name: String): Path {
        val source = Path.of(checkNotNull(System.getProperty("public.fixtures.dir"))).resolve("mech-engineer-production/$format/package")
        val target = temporary.resolve(name)
        Files.walk(source).use { paths -> paths.forEach { path ->
            val out = target.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(out) else Files.copy(path, out)
        } }
        return target
    }

    @Test fun inherited_qualification_reports_its_actual_source_and_unknown_fields_and_missing_targets_fail_closed() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copy(format, "inherited-$format")
            val entry = root.resolve("engineer.$format")
            val parent = root.resolve("base.$format")
            Files.move(entry, parent)
            Files.writeString(entry, if (format == "ini") "[core]\ncopyFrom: base.$format\n" else
                """{"format":"rw-reforge.unit.v2","copyFrom":["base.$format"]}""")
            val loaded = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            assertTrue(loaded.sources.any { it.path == parent.toAbsolutePath() })
            assertTrue(loaded.sources.any { it.path == root.resolve("cannon.png").toAbsolutePath() })
            val old = Files.readString(parent)
            Files.writeString(parent, old.replace("0.08", "0.09"))
            val changed = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, changed.code)
            assertEquals(parent.toString(), changed.file)
            assertEquals("selfRegenRate", changed.field)
            assertIs<ContentResult.Failure>(loaded.verifyStable())
            val unknown = if (format == "ini") old.replace("[core]", "[core]\nexecuteArbitraryScript: true") else {
                val json = JsonValue.readHjson(old).asObject()
                json.get("core").asObject().add("executeArbitraryScript", true)
                json.toString()
            }
            Files.writeString(parent, unknown)
            assertEquals(ContentErrorCode.UNSUPPORTED_FIELD,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.writeString(parent, old)
            val manifest = root.resolve("pack.json")
            val contents = JsonValue.readJSON(Files.readString(manifest)).asObject()
            contents.get("units").asArray().remove(1)
            Files.writeString(manifest, contents.toString())
            val missing = assertIs<ContentResult.Failure>(ContentLoader.loadContentPacks(listOf(root))).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, missing.code)
            assertEquals("repairbay", missing.objectRef)
        }
    }

    @Test fun image_dimensions_and_paths_are_checked_before_content_can_be_used() {
        for (format in listOf("ini", "json", "hjson")) {
            val root = copy(format, "images-$format")
            val entry = root.resolve("engineer.$format")
            val old = Files.readString(entry)
            Files.writeString(entry, old.replace("cannon.png", "missing.png"))
            assertEquals(ContentErrorCode.BAD_REFERENCE,
                assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error.code)
            Files.writeString(entry, old)
            Files.write(root.resolve("body.png"), Files.readAllBytes(root.resolve("foot.png")))
            val dimensions = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(root)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, dimensions.code)
            assertEquals("image_body", dimensions.field)
        }
    }

    @Test fun three_syntaxes_load_the_engineer_with_distinct_work_regeneration_and_aa_weapon_parameters() {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        for (format in listOf("ini", "json", "hjson")) {
            val result = ContentLoader.loadContentPack(root.resolve("mech-engineer-production/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
            val unit = pack.units.getValue("mechEngineer")
            assertEquals(2000.0, unit.maximumHealth)
            assertEquals(2, unit.techLevel)
            assertEquals(BuildProfile(5000L, .0008f), unit.build)
            assertEquals(.08f, unit.selfRegenRate)
            assertEquals(IncomeProfile(40f, 1f), unit.income)
            assertEquals(3, unit.transportSlots)
            assertEquals(MovementType.OVER_CLIFF, unit.movement.type)
            assertEquals(.2, unit.movement.reverseSpeedPercentage)
            assertEquals(140f, unit.builder!!.range)
            assertEquals(0f, unit.builder.preparation)
            assertEquals(.3f, unit.builder.repairRate)
            assertEquals(4, unit.mech!!.legs.size)
            assertEquals(65.0, unit.mech.legs[0].hardLimit)
            val weapons = unit.combat!!.namedWeapons!!.ordered
            assertEquals(listOf("body", "nanoTurret"), weapons.map { it.name })
            assertEquals(1, weapons.count { it.canShoot })
            val body = weapons[0]
            assertFalse(body.canAttackLandUnits)
            assertTrue(body.canAttackFlyingUnits)
            assertEquals(2.0, body.warmup)
            assertEquals(100.0, body.projectile.directDamage)
            assertEquals(OrdinarySplashProfile(40f, 35f), body.projectile.splash)
            assertEquals(5.0, body.projectile.acceleration!!.targetSpeed)
            assertTrue(body.projectile.retargetOnDeath)
            assertEquals(0.0, weapons[1].warmup)
            assertEquals("body", weapons[1].attachedTo)
            assertIs<ContentResult.Success<Unit>>(pack.verifyStable())
        }
    }
}
