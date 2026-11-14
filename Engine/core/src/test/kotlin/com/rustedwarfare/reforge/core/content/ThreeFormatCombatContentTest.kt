package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreeFormatCombatContentTest {
    @Test
    fun v2_reports_invalid_or_incomplete_inline_projectiles_at_the_weapon_declaration() {
        for (format in listOf("json", "hjson")) for ((projectile, code) in listOf(
            """{"directDamage":41,"life":1e309,"speed":5}""" to ContentErrorCode.MALFORMED_SYNTAX,
            """{"directDamage":41,"speed":5}""" to ContentErrorCode.MISSING_REQUIRED_FIELD,
        )) {
            withInput("tank.$format", """{"format":"rw-reforge.unit.v2","copyFrom":["parent.$format"]}""") { path ->
                val parent = path.resolveSibling("parent.$format")
                Files.writeString(parent, tankJson.replace("\"projectile\":\"1\"", "\"projectile\":$projectile"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(code, error.code)
                assertEquals(parent.toString(), error.file)
                assertEquals("turret_1", error.section)
                assertEquals("projectile.life", error.field)
            }
        }
    }

    @Test
    fun v2_tech_level_requires_the_number_type_in_both_structured_formats() {
        for (format in listOf("json", "hjson")) {
            val invalid = tankJson.replace("\"price\":350", "\"price\":350,\"techLevel\":\"2\"")
            withInput("tank.$format", invalid) { path ->
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals("core", error.section)
                assertEquals("techLevel", error.field)
            }
        }
    }

    @Test
    fun v2_whole_inline_projectile_replaces_the_inherited_reference_and_keeps_the_mount() {
        withInput("tank.json", """
            {"format":"rw-reforge.unit.v2", "copyFrom":["parent.json"],
             "weapons":[{"name":"1","delay":12,"projectile":{"directDamage":41,"life":50,"speed":8}}]}
        """.trimIndent()) { path ->
            val parent = tankJson
                .replace("\"x\":0, \"y\":0", "\"x\":4, \"y\":0")
            Files.writeString(path.parent.resolve("parent.json"), parent)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            val weapon = assertNotNull(assertNotNull(unit.combat).namedWeapons).byName.getValue("1")
            assertEquals(4.0, weapon.mount.localX)
            assertEquals(12.0, weapon.shootDelay)
            assertEquals(41.0, weapon.projectile.directDamage)
            assertEquals(50.0, weapon.projectile.life)
            assertEquals(8.0, weapon.projectile.speed)
        }
    }

    @Test
    fun v2_empty_weapon_array_keeps_parent_and_unknown_deletion_is_rejected() {
        withInput("tank.json", """
            {"format":"rw-reforge.unit.v2", "copyFrom":["parent.json"], "weapons":[]}
        """.trimIndent()) { path ->
            Files.writeString(path.parent.resolve("parent.json"), tankJson)
            val first = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(25.0, assertNotNull(first.combat).projectile.directDamage)
            Files.writeString(path, """{"format":"rw-reforge.unit.v2", "copyFrom":["parent.json"],
              "removeWeapons":["absent"]}""")
            val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, invalid.code)
            assertEquals("removeWeapons", invalid.field)
        }
    }

    @Test
    fun v2_deletion_is_scoped_to_its_inheritance_branch_and_validates_surviving_links() {
        withInput("tank.json", """
            {"format":"rw-reforge.unit.v2", "copyFrom":["with-side.json","without-side.json"]}
        """.trimIndent()) { path ->
            val parent = tankJson.replace(
                "\"turnSpeedAcceleration\":1}]",
                "\"turnSpeedAcceleration\":1},{\"name\":\"side\",\"x\":2,\"y\":0,\"projectile\":\"1\",\"turnSpeedAcceleration\":1}]",
            )
            Files.writeString(path.parent.resolve("with-side.json"), parent)
            Files.writeString(path.parent.resolve("without-side.json"), """
                {"format":"rw-reforge.unit.v2", "copyFrom":["with-side.json"], "removeWeapons":["side"]}
            """.trimIndent())
            fun weapons(): Set<String> {
                val loaded = ContentLoader.loadCombatUnit(path)
                val combat = assertNotNull(assertIs<ContentResult.Success<UnitDefinition>>(loaded, loaded.toString()).value.combat)
                return assertNotNull(combat.namedWeapons).byName.keys
            }
            assertEquals(setOf("1", "side"), weapons())

            Files.writeString(path, """{"format":"rw-reforge.unit.v2", "copyFrom":["without-side.json"]}""")
            assertEquals(setOf("1"), weapons())
            Files.writeString(path, """
                {"format":"rw-reforge.unit.v2", "copyFrom":["with-side.json"], "removeWeapons":["side"],
                 "weapons":[{"name":"1","attachedTo":"side"}]}
            """.trimIndent())
            val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, invalid.code)
            assertEquals("attachedTo", invalid.field)
        }
    }

    @Test
    fun v2_rejects_obsolete_unit_format_at_the_normal_loader() {
        withInput("tank.json", tankJson.replace("rw-reforge.unit.v2", "rw-reforge.unit." + "v1")) { path ->
            val invalid = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, invalid.code)
            assertEquals("format", invalid.field)
        }
    }

    @Test
    fun json_tank_loads_existing_combat_values_and_defaults() {
        withInput("tank.json", tankJson) { path ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals("c_tank", unit.id)
            assertEquals(350L, assertNotNull(unit.build).price)
            assertEquals(15, unit.fogOfWarSightRange)
            val combat = assertNotNull(unit.combat)
            assertEquals(210.0, combat.maxHp)
            assertEquals(130.0, combat.attackRange)
            assertEquals(1.1, unit.movement.moveSpeed)
        }
    }

    @Test
    fun hjson_comments_and_quoteless_image_names_keep_strict_json_separate() {
        val hjson = tankJson.replaceFirst("{", "{\n# A real Hjson comment\n")
            .replace("\"image\":\"tank.png\"", "image: tank # main.png\n")
        withInput("tank.hjson", hjson) { path ->
            Files.copy(path.parent.resolve("tank.png"), path.parent.resolve("tank # main.png"))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals("tank # main.png", unit.imagePath.fileName.toString())
            assertEquals(210.0, assertNotNull(unit.combat).maxHp)
        }
        withInput("tank.json", hjson) { path ->
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
        }
    }

    @Test
    fun structured_numeric_and_boolean_fields_reject_strings_with_original_field_diagnostics() {
        for ((from, to, section, field) in listOf(
            listOf("\"price\":350", "\"price\":\"350\"", "core", "price"),
            listOf("\"canAttack\":true", "\"canAttack\":\"true\"", "attack", "canAttack"),
        )) {
            withInput("tank.json", tankJson.replace(from, to)) { path ->
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals(path.toAbsolutePath().normalize().toString(), error.file)
                assertEquals(section, error.section)
                assertEquals(field, error.field)
            }
        }
    }

    @Test
    fun json_unit_inherits_ini_content_and_keeps_local_values() {
        withInput("tank.json", """
            {"format":"rw-reforge.unit.v2", "copyFrom":["base.ini"], "core":{"maxHp":300}}
        """.trimIndent()) { path ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            Files.copy(fixtures.resolve("production-combat/combat/units/tank.ini"), path.parent.resolve("base.ini"))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals("c_tank", unit.id)
            assertEquals(350L, assertNotNull(unit.build).price)
            assertEquals(300.0, assertNotNull(unit.combat).maxHp)
            assertEquals(130.0, assertNotNull(unit.combat).attackRange)
            assertEquals("tank.png", unit.imagePath.fileName.toString())
        }
    }

    @Test
    fun ini_unit_consumes_hjson_and_json_templates_without_inheriting_template_only_status() {
        withInput("tank.ini", """
            [core]
            copyFrom: middle.hjson
            maxHp: 320
        """.trimIndent()) { path ->
            val base = path.parent.resolve("base.json")
            Files.writeString(base, tankJson.replace("\"price\":350", "\"dont_load\":true, \"price\":350"))
            Files.writeString(path.parent.resolve("middle.hjson"), """
                format: rw-reforge.unit.v2
                copyFrom: ["base.json"]
                core: {
                  dont_load: true
                  price: 420
                }
            """.trimIndent())
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(320.0, assertNotNull(unit.combat).maxHp)
            assertEquals(420L, assertNotNull(unit.build).price)
            assertEquals(130.0, assertNotNull(unit.combat).attackRange)
            val template = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(base)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, template.code)
            assertEquals("dont_load", template.field)
        }
    }

    @Test
    fun invalid_inherited_value_reports_the_declaring_file_and_field() {
        withInput("tank.hjson", "format: rw-reforge.unit.v2\ncopyFrom: [\"base.ini\"]") { path ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val parent = path.parent.resolve("base.ini")
            val original = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            Files.writeString(parent, original.replace("price: 350", "price: 9223372036854775808"))
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
            assertEquals(parent.toAbsolutePath().normalize().toString(), error.file)
            assertEquals("core", error.section)
            assertEquals("price", error.field)
        }
    }

    @Test
    fun single_weapon_and_projectile_use_stable_names_instead_of_array_positions() {
        val named = tankJson.replace("\"weapons\": [{\"name\":\"1\"", "\"weapons\": [{\"name\":\"主炮\"")
            .replace("\"projectile\":\"1\"", "\"projectile\":\"实心弹\"")
            .replace("\"projectiles\": [{\"name\":\"1\"", "\"projectiles\": [{\"name\":\"实心弹\"")
        withInput("tank.json", named) { path ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(25.0, assertNotNull(unit.combat).projectile.directDamage)
            assertEquals(75.0, assertNotNull(unit.combat).shootDelay)
        }
    }

    @Test
    fun ini_weapon_copies_json_template_fields_and_preserves_local_values() {
        withInput("tank.ini", """
            [core]
            copyFrom: base.json
            [turret_1]
            @copyFromSection: template_gun
            x: 0
            projectile: 1
        """.trimIndent()) { path ->
            val base = tankJson.replace(
                "\"weapons\": [{\"name\":\"1\", \"x\":0, \"y\":0, \"canShoot\":true, \"projectile\":\"1\", \"turnSpeedAcceleration\":1}]",
                "\"weapons\": []",
            ).replace("\"format\": \"rw-reforge.unit.v2\",", """
                "format": "rw-reforge.unit.v2",
                "templates": {"template_gun": {"x":3, "y":0, "canShoot":true, "turnSpeedAcceleration":2}},
            """.trimIndent())
            Files.writeString(path.parent.resolve("base.json"), base)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(2.0, assertNotNull(unit.combat).turretTurnAcceleration)
            assertEquals(25.0, assertNotNull(unit.combat).projectile.directDamage)
        }
    }

    @Test
    fun structured_section_controls_skip_file_inheritance_and_follow_reverse_depth_first_templates() {
        withInput("tank.hjson", """
            {
              format: rw-reforge.unit.v2
              copyFrom: ["base.ini"]
              weapons: [{name:"1", skipInheritance:true, copyFromSections:["template_a","template_b"], x:0}]
              templates: {
                template_a: {x:9, y:0, canShoot:true, projectile:"1", turnSpeedAcceleration:7}
                template_b: {copyFromSections:["template_common"], x:8}
                template_common: {y:0, canShoot:true, projectile:"1", turnSpeedAcceleration:2}
              }
            }
        """.trimIndent()) { path ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            Files.copy(fixtures.resolve("production-combat/combat/units/tank.ini"), path.parent.resolve("base.ini"))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(2.0, assertNotNull(unit.combat).turretTurnAcceleration)
            assertEquals(210.0, assertNotNull(unit.combat).maxHp)
        }
    }

    @Test
    fun inherited_images_use_the_consumer_directory_and_errors_keep_the_declaration_and_chain() {
        withInput("tank.json", """{"format":"rw-reforge.unit.v2","copyFrom":["parents/middle.ini"]}""") { path ->
            val parents = Files.createDirectory(path.parent.resolve("parents"))
            val middle = parents.resolve("middle.ini")
            Files.writeString(middle, "[core]\ncopyFrom: base.ini\ndont_load: true")
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val base = parents.resolve("base.ini")
            val original = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            Files.writeString(base, original)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(path.parent.resolve("tank.png"), unit.imagePath)
            Files.writeString(base, original.replace("image_wreak: tank_dead.png", "image_wreak: missing.png"))
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals(base.toString(), error.file)
            assertEquals("graphics", error.section)
            assertEquals("image_wreak", error.field)
            for (file in listOf(path, middle, base)) assertTrue(error.reason.contains(file.toString()), error.describe())
        }
    }

    @Test
    fun bad_inheritance_references_report_the_actual_author_field_and_consumption_chain() {
        withInput("tank.json", """{"format":"rw-reforge.unit.v2","copyFrom":["missing.ini"]}""") { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals(null, error.section)
            assertEquals("copyFrom", error.field)
            assertEquals("missing.ini", error.objectRef)
            assertTrue(error.reason.contains(path.toString()))
        }
        withInput("tank.hjson", """
            {format:"rw-reforge.unit.v2", weapons:[{name:"1", copyFromSections:["template_missing"]}]}
        """.trimIndent()) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, error.code)
            assertEquals("turret_1", error.section)
            assertEquals("copyFromSections", error.field)
            assertEquals("template_missing", error.objectRef)
            assertTrue(error.reason.contains(path.toString()))
        }
    }

    @Test
    fun file_inheritance_uses_the_entire_b_branch_first_and_allows_a_shared_ancestor() {
        withInput("tank.json", """{"format":"rw-reforge.unit.v2","copyFrom":["a.ini","b.hjson"],"core":{"maxHp":300}}""") { path ->
            Files.writeString(path.parent.resolve("a.ini"), "[core]\ncopyFrom: shared.json\nprice: 700\ndisplayRadius: 21")
            Files.writeString(path.parent.resolve("b.hjson"), "format: rw-reforge.unit.v2\ncopyFrom: [\"shared.json\"]\ncore: {maxHp:550}")
            Files.writeString(path.parent.resolve("shared.json"), tankJson)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(350L, assertNotNull(unit.build).price)
            assertEquals(11.0, unit.displayRadius)
            assertEquals(300.0, assertNotNull(unit.combat).maxHp)
        }
    }

    @Test
    fun nested_file_cycles_and_depth_eleven_fail_but_depth_ten_loads() {
        withInput("tank.ini", "[core]\ncopyFrom: node1.ini") { path ->
            val root = path.parent
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val full = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            for (depth in 1..9) Files.writeString(root.resolve("node$depth.ini"), "[core]\ncopyFrom: node${depth + 1}.ini")
            Files.writeString(root.resolve("node10.ini"), full)
            assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path))
            Files.writeString(root.resolve("node10.ini"), "[core]\ncopyFrom: node11.ini")
            Files.writeString(root.resolve("node11.ini"), full)
            val deep = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, deep.code)
            assertTrue(deep.reason.contains("depth exceeds 10"), deep.describe())
            Files.writeString(root.resolve("node2.ini"), "[core]\ncopyFrom: node1.ini")
            val cycle = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, cycle.code)
            assertEquals("node1.ini", cycle.objectRef)
            assertTrue(cycle.reason.contains("Cyclic inheritance"), cycle.describe())
        }
    }

    @Test
    fun section_cycles_and_depth_eleven_fail_but_depth_ten_loads() {
        fun text(last: Int): String {
            val templates = (1..last).joinToString(",") { depth ->
                if (depth == last) "\"template_$depth\":{\"x\":0,\"y\":0,\"canShoot\":true,\"projectile\":\"1\",\"turnSpeedAcceleration\":2}"
                else "\"template_$depth\":{\"copyFromSections\":[\"template_${depth + 1}\"]}"
            }
            return """{"format":"rw-reforge.unit.v2","copyFrom":["base.json"],"weapons":[{"name":"1","skipInheritance":true,"copyFromSections":["template_1"]}],"templates":{$templates}}"""
        }
        withInput("tank.json", text(10)) { path ->
            Files.writeString(path.parent.resolve("base.json"), tankJson)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(2.0, assertNotNull(unit.combat).turretTurnAcceleration)
            Files.writeString(path, text(11))
            val deep = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, deep.code)
            assertTrue(deep.reason.contains("depth exceeds 10"), deep.describe())
            Files.writeString(path, text(2).replace(
                "\"template_2\":{\"x\":0,\"y\":0,\"canShoot\":true,\"projectile\":\"1\",\"turnSpeedAcceleration\":2}",
                "\"template_2\":{\"copyFromSections\":[\"template_1\"]}",
            ))
            val cycle = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, cycle.code)
            assertEquals("copyFromSections", cycle.field)
            assertTrue(cycle.reason.contains("Cyclic section inheritance"), cycle.describe())
        }
    }

    @Test
    fun ini_skip_flag_blocks_parent_weapon_fields_but_allows_explicit_section_copy() {
        withInput("tank.ini", """
            [core]
            copyFrom: base.json
            [turret_1]
            @copyFrom_skipThisSection: true
            @copyFromSection: template_gun
            [template_gun]
            x: 0
            y: 0
            canShoot: true
            projectile: 1
            turnSpeedAcceleration: 2
        """.trimIndent()) { path ->
            Files.writeString(path.parent.resolve("base.json"), tankJson)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(2.0, assertNotNull(unit.combat).turretTurnAcceleration)
        }
    }

    @Test
    fun structured_rejections_cover_identity_types_ranges_missing_values_and_unknown_capabilities() {
        val weapon = "{\"name\":\"1\", \"x\":0, \"y\":0, \"canShoot\":true, \"projectile\":\"1\", \"turnSpeedAcceleration\":1}"
        for ((text, expected) in listOf(
            tankJson.replace("\"price\":350", "\"price\":350,\"price\":351") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace(weapon, "$weapon,$weapon") to ContentErrorCode.IDENTITY_CONFLICT,
            tankJson.replace(weapon, "$weapon,${weapon.replace("\"x\":0", "\"x\":0,\"unknownAbility\":true")}") to ContentErrorCode.IDENTITY_CONFLICT,
            tankJson.replace("\"format\":", "\"templates\":{\"template_dup\":{\"x\":0},\"template_dup\":{\"unknownAbility\":true}},\"format\":") to ContentErrorCode.IDENTITY_CONFLICT,
            tankJson.replace("\"projectile\":\"1\"", "\"projectile\":\"missing\"") to ContentErrorCode.BAD_REFERENCE,
            tankJson.replace(weapon, "$weapon,${weapon.replace("\"name\":\"1\"", "\"name\":\"second\"").replace("\"projectile\":\"1\"", "\"projectile\":\"missing\"")}") to ContentErrorCode.BAD_REFERENCE,
            tankJson.replace("\"price\":350", "\"price\":null") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"maxHp\":210", "\"maxHp\":{}") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"canAttack\":true", "\"canAttack\":2") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"price\":350", "\"price\":9223372036854775808") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"price\":350", "\"price\":350.0") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"directDamage\":25", "\"directDamage\":25.0") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"maxHp\":210", "\"maxHp\":2147483648") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"life\":60", "\"life\":1e309") to ContentErrorCode.MALFORMED_SYNTAX,
            tankJson.replace("\"maxHp\":210,", "") to ContentErrorCode.MISSING_REQUIRED_FIELD,
            tankJson.replace("tank_dead.png", "missing.png") to ContentErrorCode.BAD_REFERENCE,
            tankJson.replace("rw-reforge.unit.v2", "rw-reforge.unit.v99") to ContentErrorCode.UNSUPPORTED_FEATURE,
            tankJson.replace("\"directDamage\":25", "\"targetGround\":true,\"directDamage\":25") to ContentErrorCode.UNSUPPORTED_FIELD,
            tankJson.replace("\"format\":", "\"abilities\":{},\"format\":") to ContentErrorCode.UNSUPPORTED_FIELD,
            tankJson.replace("\"canShoot\":true", "\"skipInheritance\":\"true\",\"canShoot\":true") to ContentErrorCode.MALFORMED_SYNTAX,
        )) for (extension in listOf("json", "hjson")) withInput("tank.$extension", text) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path), text).error
            assertEquals(expected, error.code, error.describe())
        }
    }

    @Test
    fun structured_costs_keep_long_precision_and_build_rates_keep_binary32_precision() {
        for (extension in listOf("json", "hjson")) {
            withInput("tank.$extension", tankJson.replace("\"price\":350", "\"price\":9007199254740993")) { path ->
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
                assertEquals(9007199254740993L, assertNotNull(unit.build).price)
                assertEquals(0.002f, assertNotNull(unit.build).rate)
            }
        }
    }

    @Test
    fun exact_number_tokens_do_not_consume_comments_escaped_keys_or_multiline_and_quoteless_strings() {
        val hjson = tankJson.replace("\"price\":350", "\"pri\\u0063e\":350")
            .replace("\"canAttack\":true", "\"canAttack\":/* 9223372036854775808 */true")
            .replace("\"image\":\"tank.png\"", "image: 09 # 1e309.png\n")
            .replace("\"core\": {", """
                "core": {displayLocaleKey: '''
                  Numbers in a string:
                  9007199254740993 # text
                  /* text */ [1,2]
                ''',
            """.trimIndent())
        withInput("tank.hjson", hjson) { path ->
            Files.copy(path.parent.resolve("tank.png"), path.parent.resolve("09 # 1e309.png"))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(350L, assertNotNull(unit.build).price)
            assertEquals("09 # 1e309.png", unit.imagePath.fileName.toString())
            assertEquals(210.0, assertNotNull(unit.combat).maxHp)
        }
    }

    @Test
    fun loaded_content_retains_actual_source_bytes_and_detects_changes_without_reparsing() {
        val text = """{"format":"rw-reforge.unit.v2","copyFrom":["base.ini"]}"""
        withInput("tank.json", text) { path ->
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val base = path.parent.resolve("base.ini")
            val original = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            Files.writeString(base, original)
            Files.writeString(path.parent.resolve("unused.ini"), "This file is never consumed")
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadCombatUnitWithSources(path)).value
            assertEquals(350L, assertNotNull(loaded.definition.build).price)
            assertEquals(listOf(path, base), loaded.sources.map { it.path })
            assertEquals(55L, loaded.sources[0].bytes)
            assertEquals("fb5aac95b156141918ea415032306818d198c009703cb1ffbd8afe9c9087397a", loaded.sources[0].sha256)
            assertEquals("99aa63a6f6e87662e257af3ca7c4557290153e60e62fcdf74360e15caef612e8", loaded.sources[1].sha256)
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            Files.writeString(base, original.replace("price: 350", "price: 420"))
            val changed = assertIs<ContentResult.Failure>(loaded.verifyStable()).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, changed.code)
            assertEquals(base.toString(), changed.file)
            assertEquals(350L, assertNotNull(loaded.definition.build).price)
            assertEquals("99aa63a6f6e87662e257af3ca7c4557290153e60e62fcdf74360e15caef612e8", loaded.sources[1].sha256)
            val reopened = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadCombatUnitWithSources(path)).value
            assertEquals(420L, assertNotNull(reopened.definition.build).price)
            assertIs<ContentResult.Success<Unit>>(reopened.verifyStable())
        }
    }

    @Test
    fun equivalent_numeric_spellings_keep_zero_mounts_and_signed_offsets_must_remain_finite() {
        val zero = tankJson.replace("\"x\":0, \"y\":0", "\"x\":0.0, \"y\":0e0")
        for (format in listOf("json", "hjson")) withInput("tank.$format", zero) { path ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(0.0, assertNotNull(unit.combat).mount.localX)
            assertEquals(0.0, assertNotNull(unit.combat).mount.localY)
            Files.writeString(path, zero.replace("\"x\":0.0", "\"x\":1.0"))
            val offset = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            assertEquals(1.0, assertNotNull(offset.combat).mount.localX)
            Files.writeString(path, zero.replace("\"x\":0.0", "\"x\":1e309"))
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
            assertEquals("x", error.field)
        }
    }

    private fun withInput(name: String, text: String, check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p008-three-format-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val images = fixtures.resolve("production-combat/combat/units")
            for (image in listOf("tank.png", "tank_dead.png", "tank_turret.png")) {
                Files.copy(images.resolve(image), root.resolve(image))
            }
            val input = root.resolve(name)
            Files.writeString(input, text)
            check(input)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                    require(owned.toAbsolutePath().normalize().startsWith(root))
                    Files.deleteIfExists(owned)
                }
            }
        }
    }

    private val tankJson = """
        {
          "format": "rw-reforge.unit.v2",
          "core": {"name":"c_tank", "overrideAndReplace":"tank", "class":"CustomUnitMetadata",
            "price":350, "buildSpeed":0.002, "maxHp":210, "radius":11, "displayRadius":11,
            "isBio":false, "softCollisionOnAll":0},
          "graphics": {"total_frames":3, "image":"tank.png", "image_wreak":"tank_dead.png", "image_turret":"tank_turret.png"},
          "attack": {"canAttack":true, "canAttackFlyingUnits":false, "canAttackLandUnits":true,
            "canAttackUnderwaterUnits":false, "turretSize":20, "turretTurnSpeed":4, "maxAttackRange":130, "shootDelay":75},
          "weapons": [{"name":"1", "x":0, "y":0, "canShoot":true, "projectile":"1", "turnSpeedAcceleration":1}],
          "projectiles": [{"name":"1", "directDamage":25, "life":60, "speed":5}],
          "movement": {"movementType":"LAND", "moveSpeed":1.1, "moveAccelerationSpeed":0.07,
            "moveDecelerationSpeed":0.17, "maxTurnSpeed":4.1, "turnAcceleration":0.25,
            "moveSlidingMode":false, "moveIgnoringBody":false}
        }
    """.trimIndent()
}
