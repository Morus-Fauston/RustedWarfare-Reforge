package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import org.hjson.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeclaredTankContentTest {
    @Test fun declared_tank_selects_a_reusable_land_template_without_repeating_movement_fields() {
        withTank { root, _ ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve("three-format-tanks/json/units/tank.json")
            val model = JsonValue.readJSON(Files.readString(source)).asObject()
            model.get("core").asObject().set("name", "field_tank").remove("overrideAndReplace")
            model.remove("movement")
            model.set("unitType", "tank")
            val baseline = assertIs<ContentResult.Success<LoadedUnitContent>>(
                ContentLoader.loadCombatUnitWithSources(source)).value.definition
            for (format in listOf("json", "hjson")) {
                val input = root.resolve("field_tank.$format")
                Files.writeString(input, model.toString())
                val result = ContentLoader.loadDeclaredTankWithSources(input)
                val variant = assertIs<ContentResult.Success<LoadedUnitContent>>(result, result.toString()).value.definition
                assertEquals("field_tank", variant.id)
                assertEquals(baseline.movement, variant.movement)
            }
            for ((value, code) in listOf(
                JsonValue.valueOf("hoverTank") to ContentErrorCode.UNSUPPORTED_FEATURE,
                JsonValue.valueOf(3) to ContentErrorCode.MALFORMED_SYNTAX,
            )) {
                val invalidParent = root.resolve("invalid-template.json")
                Files.writeString(invalidParent, model.set("unitType", value).toString())
                val child = root.resolve("child.hjson")
                Files.writeString(child, """{"format":"rw-reforge.unit.v2","copyFrom":["invalid-template.json"]}""")
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadDeclaredTankWithSources(child)).error
                assertEquals(code, error.code)
                assertEquals("unitType", error.field)
                assertEquals(invalidParent.toString(), error.file)
                assertTrue(error.reason.contains(child.toString()))
            }
            model.set("unitType", "tank")
            model.add("movement", JsonValue.readJSON("""{"moveSpeed":1.25}"""))
            val customized = root.resolve("customized.json")
            Files.writeString(customized, model.toString())
            val result = ContentLoader.loadDeclaredTankWithSources(customized)
            val variant = assertIs<ContentResult.Success<LoadedUnitContent>>(result, result.toString()).value.definition
            assertEquals(baseline.movement.copy(moveSpeed = 1.25), variant.movement)
        }
    }

    @Test
    fun new_name_uses_existing_tank_semantics_without_relaxing_the_legacy_entry() {
        withTank { root, input ->
            val source = Files.readString(input).replace("name: c_tank", "name: amber_scout")
                .replace("overrideAndReplace: tank", "")
            Files.writeString(input, source)
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(
                ContentLoader.loadDeclaredTankWithSources(input)).value
            assertEquals("amber_scout", loaded.definition.id)
            assertEquals(210.0, assertNotNull(loaded.definition.combat).maxHp)
            assertEquals(25.0, loaded.definition.combat!!.projectile.directDamage)
            assertEquals(350L, assertNotNull(loaded.definition.build).price)
            assertEquals(listOf(input), loaded.sources.map { it.path })
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            val legacy = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(input)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, legacy.code)
            assertEquals("name", legacy.field)
            assertTrue(loaded.definition.imagePath.startsWith(root))
        }
    }

    private fun withTank(check: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("p008-declared-tank-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve("three-format-tanks/ini/units")
            for (name in listOf("tank.ini", "tank.png", "tank_dead.png", "tank_turret.png"))
                Files.copy(source.resolve(name), root.resolve(name))
            check(root, root.resolve("tank.ini"))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(owned)
            } }
        }
    }
}
