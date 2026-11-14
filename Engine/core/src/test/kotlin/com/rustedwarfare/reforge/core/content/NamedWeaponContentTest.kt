package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class NamedWeaponContentTest {
    @Test
    fun three_formats_feed_each_barrel_and_recoil_into_the_real_profile_and_reject_conflicting_length_aliases() {
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"barrelY\":18,\"barrelX\":-3," +
            "\"barrelOffsetX_onOddShots\":-2,\"recoilOffset\":-4,\"recoilOutTime\":2,\"recoilReturnTime\":4,")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nbarrelY: 18\nbarrelX: -3\nbarrelOffsetX_onOddShots: -2\n" +
                "recoilOffset: -4\nrecoilOutTime: 2\nrecoilReturnTime: 4")
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Own barrel fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                fun combat(): CombatProfile {
                    val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                    return assertNotNull(unit.combat)
                }
                val profile = combat()
                val rear = assertNotNull(profile.namedWeapons).byName.getValue("rear")
                assertEquals(18.0, rear.turretSize)
                assertEquals(-3.0, rear.barrelX)
                assertEquals(-2.0, rear.oddShotOffset)
                assertEquals(WeaponRecoil(-4.0, 2.0, 4.0), rear.recoil)
                assertEquals(rear.recoil, profile.recoil)
                assertEquals(18.0, profile.turretSize)
                assertEquals(-3.0, profile.barrelX)
                assertEquals(-2.0, profile.oddShotOffset)
                Files.writeString(path, if (name == "tank.ini") text.replace("barrelY: 18", "size: 18")
                    else text.replace("\"barrelY\":18", "\"size\":18"))
                assertEquals(18.0, combat().turretSize)
                Files.writeString(path, if (name == "tank.ini") text.replace("barrelY: 18", "barrelY: 18\nsize: 18")
                    else text.replace("\"barrelY\":18", "\"barrelY\":18,\"size\":18"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals("barrelY", error.field)
                assertEquals("turret_rear", error.section)
            }
        }
    }

    @Test
    fun three_formats_consume_local_arc_and_signed_idle_direction_with_explicit_disabled_arc() {
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"limitingAngle\":30,\"idleDir\":-90,")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nlimitingAngle: 30\nidleDir: -90")
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Own local arc fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                fun rear(): NamedWeaponDefinition {
                    val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                    return assertNotNull(assertNotNull(unit.combat).namedWeapons).byName.getValue("rear")
                }
                assertEquals(30.0, rear().limitingAngle)
                assertEquals(-90.0, rear().idleDirection)
                Files.writeString(path, if (name == "tank.ini") text.replace("limitingAngle: 30", "limitingAngle: -1")
                    else text.replace("\"limitingAngle\":30", "\"limitingAngle\":-1"))
                assertEquals(null, rear().limitingAngle)
                Files.writeString(path, if (name == "tank.ini") text.replace("limitingAngle: 30", "limitingAngle: -2")
                    else text.replace("\"limitingAngle\":30", "\"limitingAngle\":-2"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals("limitingAngle", error.field)
            }
        }
    }

    @Test
    fun three_formats_resolve_link_names_and_apply_the_9000_default_before_explicit_delay() {
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"linkDelayWithTurret\":\"front\",")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nlinkDelayWithTurret: front") +
            "\n[turret_front]\nx: 0\ny: 0\ncanShoot: true\nprojectile: 1\nturnSpeedAcceleration: 1\n"
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Own linked clock fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                fun weapons(): WeaponCatalog {
                    val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                    return assertNotNull(assertNotNull(unit.combat).namedWeapons)
                }
                assertEquals("front", weapons().byName.getValue("rear").linkDelayWithTurret)
                assertEquals(9000.0, weapons().byName.getValue("rear").shootDelay)
                assertEquals(75.0, weapons().byName.getValue("front").shootDelay)
                Files.writeString(path, if (name == "tank.ini") text.replace("linkDelayWithTurret: front", "linkDelayWithTurret: front\ndelay: 20")
                    else text.replace("\"linkDelayWithTurret\":\"front\"", "\"linkDelayWithTurret\":\"front\",\"delay\":20"))
                assertEquals(20.0, weapons().byName.getValue("rear").shootDelay)
            }
        }
    }

    @Test
    fun finite_link_contract_rejects_missing_self_and_cyclic_names_with_the_consuming_field() {
        for (target in listOf("absent", "rear", "front")) {
            val text = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"linkDelayWithTurret\":\"$target\",")
                .let { if (target == "front") it.replace("\"name\":\"front\",", "\"name\":\"front\",\"linkDelayWithTurret\":\"rear\",") else it }
            withInput(text) { path ->
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(if (target == "front") ContentErrorCode.UNSUPPORTED_FEATURE else ContentErrorCode.BAD_REFERENCE, error.code)
                assertEquals("turret_rear", error.section)
                assertEquals("linkDelayWithTurret", error.field)
            }
        }
    }

    @Test
    fun all_formats_resolve_forward_attachment_names_and_keep_slave_an_independent_flag() {
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"attachedTo\":\"front\",\"slave\":true,")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nattachedTo: front\nslave: true") +
            "\n[turret_front]\nx: 0\ny: 10\ncanShoot: false\nprojectile: 1\nturnSpeedAcceleration: 1\n"
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Own forward mount fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                val catalog = assertNotNull(assertNotNull(unit.combat).namedWeapons)
                assertEquals(listOf("rear", "front"), catalog.ordered.map { it.name })
                assertEquals("front", catalog.byName.getValue("rear").attachedTo)
                assertEquals(true, catalog.byName.getValue("rear").slave)
                assertEquals(null, catalog.byName.getValue("front").attachedTo)
                assertEquals(false, catalog.byName.getValue("front").slave)
            }
        }
    }

    @Test
    fun invalid_named_mounts_report_the_consuming_section_and_field_instead_of_falling_back() {
        val nested = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"attachedTo\":\"front\",")
            .replace("\"name\":\"front\",", "\"name\":\"front\",\"attachedTo\":\"root\",")
            .replace("\"weapons\":[", "\"weapons\":[{\"name\":\"root\",\"x\":0,\"y\":0,\"projectile\":\"round\",\"turnSpeedAcceleration\":1},")
        val cases = listOf(
            Triple("\"attachedTo\":\"absent\",", ContentErrorCode.BAD_REFERENCE, "attachedTo"),
            Triple("\"attachedTo\":\"rear\",", ContentErrorCode.BAD_REFERENCE, "attachedTo"),
            Triple("\"slave\":true,", ContentErrorCode.UNSUPPORTED_FEATURE, "slave"))
        for ((extra, code, field) in cases) {
            withInput(tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",$extra")) { path ->
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(code, error.code)
                assertEquals("turret_rear", error.section)
                assertEquals(field, error.field)
            }
        }
        withInput(nested) { path ->
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
            assertEquals("turret_rear", error.section)
            assertEquals("attachedTo", error.field)
        }
    }

    @Test
    fun formats_read_non_shooting_bases_and_independent_turn_speed_and_aim_gate() {
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",\"turnSpeed\":1,\"canAttackMaxAngle\":4,")
            .replace("\"canShoot\":true", "\"canShoot\":false")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nturnSpeed: 1\ncanAttackMaxAngle: 4")
            .replace("canShoot: true", "canShoot: false")
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Own gate fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                fun rear(): NamedWeaponDefinition {
                    val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                    return assertNotNull(assertNotNull(unit.combat).namedWeapons).byName.getValue("rear")
                }
                assertEquals(false, rear().canShoot)
                assertEquals(1.0, rear().turnSpeed)
                assertEquals(4.0, rear().aimMaxAngle)
                val alias = if (name == "tank.ini") text.replace("canShoot: false", "canAttack: false")
                    else text.replace("\"canShoot\":false", "\"canAttack\":false")
                Files.writeString(path, alias)
                assertEquals(false, rear().canShoot)
                Files.writeString(path, if (name == "tank.ini") alias.replace("canAttack: false", "canAttack: false\ncanShoot: true")
                    else alias.replace("\"canAttack\":false", "\"canAttack\":false,\"canShoot\":true"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals("canShoot", error.field)
                assertEquals("turret_rear", error.section)
            }
        }
    }

    @Test
    fun all_formats_consume_the_distinct_warmup_clocks_and_preserve_strict_numeric_types() {
        val fields = "\"warmup\":2,\"warmupCallDownRate\":0.5,\"warmupNoReset\":true,\"warmupShootDelayTransfer\":4,"
        val json = tankJson.replace("\"name\":\"rear\",", "\"name\":\"rear\",$fields")
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\nwarmup: 2\nwarmupCallDownRate: 0.5\nwarmupNoReset: true\nwarmupShootDelayTransfer: 4")
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to json,
            "tank.hjson" to ("# Owned warmup fixture\n" + json.replace("\"format\":", "format:")))) {
            withInput(text, name) { path ->
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path), name).value
                val weapon = assertNotNull(assertNotNull(unit.combat).namedWeapons).byName.getValue("rear")
                assertEquals(2.0, weapon.warmup)
                assertEquals(0.5, weapon.warmupCallDownRate)
                assertEquals(true, weapon.warmupNoReset)
                assertEquals(4.0, weapon.warmupShootDelayTransfer)
                if (name != "tank.ini") {
                    Files.writeString(path, text.replace("\"warmup\":2", "\"warmup\":\"2\""))
                    val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                    assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                    assertEquals("warmup", error.field)
                }
            }
        }
    }

    @Test
    fun existing_formats_consume_each_weapons_target_flags_and_reject_stringified_booleans() {
        val text = tankJson.replace("\"name\":\"rear\"", "\"name\":\"rear\",\"canAttackLandUnits\":false," +
            "\"canAttackFlyingUnits\":true,\"canAttackUnderwaterUnits\":true")
        for (format in listOf("json", "hjson")) {
            withInput(if (format == "hjson") "# Own target flag fixture\n" + text.replace("\"format\":", "format:") else text,
                "tank.$format") { path ->
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
                val weapons = assertNotNull(assertNotNull(unit.combat).namedWeapons)
                val rear = weapons.byName.getValue("rear")
                assertEquals(false, rear.canAttackLandUnits)
                assertEquals(true, rear.canAttackFlyingUnits)
                assertEquals(true, rear.canAttackUnderwaterUnits)
                assertEquals(true, weapons.byName.getValue("front").canAttackLandUnits)
                Files.writeString(path, text.replace("\"canAttackLandUnits\":false", "\"canAttackLandUnits\":\"false\""))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals("canAttackLandUnits", error.field)
            }
        }
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]\ncanAttackLandUnits: false\ncanAttackFlyingUnits: true\ncanAttackUnderwaterUnits: true")
        withInput(ini, "tank.ini") { path ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            val rear = assertNotNull(assertNotNull(unit.combat).namedWeapons).byName.getValue("rear")
            assertEquals(false, rear.canAttackLandUnits)
            assertEquals(true, rear.canAttackFlyingUnits)
            assertEquals(true, rear.canAttackUnderwaterUnits)
        }
    }

    @Test
    fun author_enables_multi_targeting_and_declares_each_weapons_range_in_the_existing_format() {
        val text = tankJson.replace("\"attack\":{", "\"attack\":{\"turretMultiTargeting\":true,")
            .replace("\"name\":\"rear\"", "\"name\":\"rear\",\"limitingRange\":90")
            .replace("\"name\":\"front\"", "\"name\":\"front\",\"limitingMinRange\":100")
        withInput(text) { path ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(path)).value
            val combat = assertNotNull(unit.combat)
            assertEquals(true, combat.multiTargeting)
            val weapons = assertNotNull(combat.namedWeapons)
            assertEquals(90.0, weapons.byName.getValue("rear").maximumRange)
            assertEquals(100.0, weapons.byName.getValue("front").minimumRange)
            assertEquals(0.0, weapons.byName.getValue("rear").minimumRange)
            assertEquals(null, weapons.byName.getValue("front").maximumRange)
        }
    }

    @Test
    fun ini_json_and_hjson_preserve_the_same_named_order_and_projectile_identity() {
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val ini = Files.readString(fixtures.resolve("production-combat/combat/units/tank.ini"))
            .replace("[turret_1]", "[turret_rear]")
            .replace("[projectile_1]", "[projectile_round]")
            .replace("projectile: 1", "projectile: round") + """

                [turret_front]
                x: 0
                y: 0
                canShoot: true
                projectile: round
                turnSpeedAcceleration: 1
            """.trimIndent()
        val hjson = "# Owned named weapon fixture\n" + tankJson.replace("\"format\":", "format:")
        for ((name, text) in listOf("tank.ini" to ini, "tank.json" to tankJson, "tank.hjson" to hjson)) {
            withInput(text, name) { input ->
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(input), name).value
                val weapons = assertNotNull(assertNotNull(unit.combat).namedWeapons)
                assertEquals(listOf("rear", "front"), weapons.ordered.map { it.name }, name)
                assertEquals(listOf("round", "round"), weapons.ordered.map { it.projectileName }, name)
                assertEquals(listOf(25.0, 25.0), weapons.ordered.map { it.projectile.directDamage }, name)
            }
        }
    }

    @Test
    fun inserting_a_named_weapon_preserves_old_references_and_each_weapons_own_mount_and_delay() {
        val changed = tankJson.replace(
            "\"weapons\":[",
            "\"weapons\":[{\"name\":\"inserted\",\"x\":0,\"y\":0,\"canShoot\":true,\"projectile\":\"other\",\"turnSpeedAcceleration\":1},"
        ).replace("\"name\":\"rear\",\"x\":0,\"y\":0", "\"name\":\"rear\",\"x\":-9,\"y\":-4,\"delay\":60")
            .replace("\"name\":\"front\",\"x\":0,\"y\":0", "\"name\":\"front\",\"x\":9,\"y\":8,\"delay\":30")
            .replace("\"projectiles\":[", "\"projectiles\":[{\"name\":\"other\",\"directDamage\":11,\"life\":60,\"speed\":5},")
        withInput(changed) { input ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(input)).value
            val weapons = assertNotNull(assertNotNull(unit.combat).namedWeapons)
            assertEquals(listOf("inserted", "rear", "front"), weapons.ordered.map { it.name })
            assertEquals(listOf("round", "round"), listOf("rear", "front").map { weapons.byName.getValue(it).projectileName })
            assertEquals(listOf(25.0, 25.0), listOf("rear", "front").map { weapons.byName.getValue(it).projectile.directDamage })
            assertEquals(11.0, weapons.byName.getValue("inserted").projectile.directDamage)
            assertEquals(TurretMount(-9.0, -4.0), weapons.byName.getValue("rear").mount)
            assertEquals(TurretMount(9.0, 8.0), weapons.byName.getValue("front").mount)
            assertEquals(60.0, weapons.byName.getValue("rear").shootDelay)
            assertEquals(30.0, weapons.byName.getValue("front").shootDelay)
        }
    }

    @Test
    fun author_loads_two_named_weapons_in_declaration_order_with_forward_and_shared_projectile_references() {
        withInput(tankJson) { input ->
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(input)).value
            val weapons = assertNotNull(assertNotNull(unit.combat).namedWeapons)
            assertEquals(listOf("rear", "front"), weapons.ordered.map { it.name })
            assertEquals(setOf("rear", "front"), weapons.byName.keys)
            assertEquals(listOf("round", "round"), weapons.ordered.map { it.projectileName })
            assertEquals(listOf(25.0, 25.0), weapons.ordered.map { it.projectile.directDamage })
            assertEquals(75.0, weapons.byName.getValue("front").shootDelay)
        }
    }

    private fun withInput(text: String, name: String = "tank.json", check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p008-012-owned-weapons-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val images = fixtures.resolve("production-combat/combat/units")
            for (name in listOf("tank.png", "tank_dead.png", "tank_turret.png"))
                Files.copy(images.resolve(name), root.resolve(name))
            val input = root.resolve(name)
            Files.writeString(input, text)
            check(input)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(owned)
            } }
        }
    }

    private val tankJson = """
        {
          "format":"rw-reforge.unit.v2",
          "core":{"name":"c_tank","overrideAndReplace":"tank","class":"CustomUnitMetadata",
            "price":350,"buildSpeed":0.002,"maxHp":210,"radius":11,"displayRadius":11,
            "isBio":false,"softCollisionOnAll":0},
          "graphics":{"total_frames":3,"image":"tank.png","image_wreak":"tank_dead.png","image_turret":"tank_turret.png"},
          "attack":{"canAttack":true,"canAttackFlyingUnits":false,"canAttackLandUnits":true,
            "canAttackUnderwaterUnits":false,"turretSize":20,"turretTurnSpeed":4,"maxAttackRange":130,"shootDelay":75},
          "weapons":[
            {"name":"rear","x":0,"y":0,"canShoot":true,"projectile":"round","turnSpeedAcceleration":1},
            {"name":"front","x":0,"y":0,"canShoot":true,"projectile":"round","turnSpeedAcceleration":1}],
          "projectiles":[{"name":"round","directDamage":25,"life":60,"speed":5}],
          "movement":{"movementType":"LAND","moveSpeed":1.1,"moveAccelerationSpeed":0.07,
            "moveDecelerationSpeed":0.17,"maxTurnSpeed":4.1,"turnAcceleration":0.25,
            "moveSlidingMode":false,"moveIgnoringBody":false}
        }
    """.trimIndent()
}
