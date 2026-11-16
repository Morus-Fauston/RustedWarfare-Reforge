package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UnitMassContentTest {
    @field:TempDir
    lateinit var temporary: Path

    private val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
        ?: Path.of("../../SimulationTest/public/inputs")

    @Test fun inherited_original_ini_mass_is_required_after_merging() {
        val base = Files.readString(unitPath()).replace(unitImageReference(), unitImagePath().toString())
        val parent = temporary.resolve("parent.ini")
        val child = temporary.resolve("child.ini")
        Files.writeString(parent, "[core]\nmass: 1250\n")
        Files.writeString(child, base.replace("mass: 1", "copyFrom: parent.ini"))
        fun loadedMass(): Double? =
            assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(child)).value.mass

        assertEquals(1250.0, loadedMass())
        Files.writeString(parent, "[core]\nmass: 1250.5\n")
        val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(child)).error
        assertEquals(ContentErrorCode.MALFORMED_SYNTAX, invalid.code)
        assertEquals(parent.toString(), invalid.file)
        assertEquals("mass", invalid.field)
        Files.writeString(parent, "[core]\nmass: 1250\n")
        Files.writeString(child, base.replace("mass: 1", "mass: 42\ncopyFrom: parent.ini"))
        assertEquals(42.0, loadedMass())
        Files.writeString(child, base.replace("mass: 1", "mass: IGNORE\ncopyFrom: parent.ini"))
        fun missingMass() {
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(child)).error
            assertEquals(ContentErrorCode.MISSING_REQUIRED_FIELD, error.code)
            assertEquals(child.toString(), error.file)
            assertEquals("core", error.section)
            assertEquals("mass", error.field)
        }
        missingMass()
        Files.writeString(child, base.replace("mass: 1", "copyFrom: parent.ini"))
        Files.writeString(parent, "[core]\n")
        missingMass()
    }

    @Test fun original_ini_uses_the_nearest_package_template_after_explicit_inheritance() {
        val source = fixtures.resolve("content-pack-combinations/sandbox/units/tank.ini")
        val pack = temporary.resolve("pack")
        val units = pack.resolve("units")
        Files.createDirectories(units)
        val tank = units.resolve("tank.ini")
        val base = Files.readString(source).lineSequence()
            .filterNot { it.startsWith("mass:") || it.startsWith("overrideAndReplace:") }
            .joinToString("\n").replace("name: c_tank", "name: c_tank\ncopyFrom: parent.ini")
        Files.writeString(tank, base)
        for (image in Files.list(source.parent).use { it.filter { file -> file.toString().endsWith(".png") }.toList() }) {
            Files.copy(image, units.resolve(image.fileName))
        }
        Files.writeString(pack.resolve("pack.json"), """
            {"format":"rw-reforge.content-pack.v1","id":"mass-template","name":"Mass template",
             "units":[{"type":"tank","file":"units/tank.ini"}]}
        """.trimIndent())
        val parent = units.resolve("parent.ini")
        Files.writeString(parent, "[core]\n")
        Files.writeString(temporary.resolve("all-units.template"), "[core]\nmass: 7000\n")
        val packTemplate = pack.resolve("all-units.template")
        Files.writeString(packTemplate, "[core]\nmass: 1300\n")
        val nearest = units.resolve("all-units.template")

        fun loaded(): LoadedContentPack {
            val result = ContentLoader.loadContentPack(pack)
            return assertIs<ContentResult.Success<LoadedContentPack>>(result, result.toString()).value
        }
        assertEquals(1300.0, loaded().units.getValue("c_tank").mass)
        Files.writeString(tank, base.replace("[core]", "[core]\n@copyFrom_skipThisSection: true"))
        assertEquals(ContentErrorCode.MISSING_REQUIRED_FIELD,
            assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error.code)
        Files.writeString(tank, base)
        Files.writeString(nearest, "[core]\nmass: 2500\n")
        val nearestPack = loaded()
        assertEquals(2500.0, nearestPack.units.getValue("c_tank").mass)
        assertEquals(true, nearestPack.sources.any { it.path == nearest })
        Files.writeString(parent, "[core]\nmass: 42\n")
        assertEquals(42.0, loaded().units.getValue("c_tank").mass)
        Files.writeString(parent, "[core]\nmass: IGNORE\n")
        val ignored = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error
        assertEquals(ContentErrorCode.MISSING_REQUIRED_FIELD, ignored.code)
        Files.writeString(parent, "[core]\n")
        Files.delete(nearest)
        Files.delete(packTemplate)
        val missing = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack)).error
        assertEquals(ContentErrorCode.MISSING_REQUIRED_FIELD, missing.code)
    }

    @Test fun original_ini_inherits_sections_across_the_unit_and_directory_template() {
        val unit = temporary.resolve("unit.ini")
        val template = temporary.resolve("all-units.template")
        val base = Files.readString(unitPath()).lineSequence().filterNot { it.startsWith("mass:") }
            .joinToString("\n").replace(unitImageReference(), unitImagePath().toString())

        fun mass(): Double? {
            val result = ContentLoader.loadUnitVariant(unit)
            return assertIs<ContentResult.Success<UnitDefinition>>(result, result.toString()).value.mass
        }

        Files.writeString(unit, "$base\n[template_mass]\nmass: 1200\n")
        Files.writeString(template, "[core]\n@copyFromSection: template_mass\n")
        assertEquals(1200.0, mass())

        val inherits = base.replace("[core]", "[core]\n@copyFromSection: template_mass")
        Files.writeString(unit, inherits)
        Files.writeString(template, "[template_mass]\nmass: 1500\n")
        assertEquals(1500.0, mass())

        Files.writeString(unit, "$inherits\n[template_mass]\nmass: 1600\n")
        assertEquals(1600.0, mass())
        Files.writeString(template, "[core]\n@copyFromSection: template_default\n[template_default]\nmass: 1700\n")
        assertEquals(1600.0, mass())
        Files.writeString(template, "[core]\nmass: 1400\n")
        assertEquals(1400.0, mass())

        Files.writeString(unit, inherits)
        Files.writeString(template, "[template_mass]\nmass: 1400.5\n")
        val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(unit)).error
        assertEquals(ContentErrorCode.MALFORMED_SYNTAX, invalid.code)
        assertEquals(template.toString(), invalid.file)
        assertEquals("template_mass", invalid.section)
        assertEquals("mass", invalid.field)
    }

    @Test fun structured_movable_mass_must_be_finite_and_positive() {
        for (format in listOf("json", "hjson")) {
            val fixture = fixtures.resolve("phase-seven-single-ship/$format/package")
            val pack = temporary.resolve(format)
            Files.createDirectory(pack)
            for (image in Files.list(fixture).use { it.filter { file -> file.toString().endsWith(".png") }.toList() }) {
                Files.copy(image, pack.resolve(image.fileName))
            }
            val unit = pack.resolve("heavyBattleship.$format")
            val base = Files.readString(fixture.resolve(unit.fileName))
            Files.writeString(pack.resolve("pack.json"), """
                {"format":"rw-reforge.content-pack.v1","id":"mass-validation","name":"Mass validation",
                 "units":[{"type":"battleship","file":"heavyBattleship.$format"}]}
            """.trimIndent())
            for ((mass, code) in listOf(
                null to ContentErrorCode.MISSING_REQUIRED_FIELD,
                "0" to ContentErrorCode.MALFORMED_SYNTAX,
                "-1" to ContentErrorCode.MALFORMED_SYNTAX,
                "1e999" to ContentErrorCode.MALFORMED_SYNTAX,
            )) {
                Files.writeString(unit, base.replace("    \"mass\": 40000,", mass?.let { "    \"mass\": $it," } ?: ""))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadContentPack(pack), "$format mass=$mass").error
                assertEquals(code, error.code, "$format mass=$mass")
                assertEquals(unit.toString(), error.file)
                assertEquals("core", error.section)
                assertEquals("mass", error.field)
            }
        }
    }

    @Test fun json_and_hjson_keep_the_declared_heavy_battleship_mass() {
        for (format in listOf("json", "hjson")) {
            val result = ContentLoader.loadContentPack(fixtures.resolve("phase-seven-single-ship/$format/package"))
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(result).value
            assertEquals(40000.0, pack.units.getValue("heavyBattleship").mass, format)
        }
    }
}
