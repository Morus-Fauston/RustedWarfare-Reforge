package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ThreeFormatProductionContentTest {
    @Test
    fun json_factory_reaches_the_existing_native_definition() = withFactory { root ->
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(root.resolve("landFactory.properties"))).value
        val path = root.resolve("landFactory.json")
        Files.writeString(path, factoryJson)
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(path)).value
        assertEquals(semanticValues(legacy), semanticValues(unit))
        assertEquals(1200.0, unit.maximumHealth)
        assertEquals(30.0, unit.radius)
        assertEquals(listOf("c_tank"), assertNotNull(unit.production).unitTypeIds)
        assertNull(unit.combat)
        assertNull(unit.build)
    }

    @Test
    fun formal_hjson_native_fields_keep_json_strict() = withFactory { root ->
        val hjson = factoryJson.replaceFirst("{", "{\n# Native metadata, same semantics\n")
        val path = root.resolve("landFactory.hjson")
        Files.writeString(path, hjson)
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(path)).value
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(root.resolve("landFactory.properties"))).value
        assertEquals(semanticValues(legacy), semanticValues(unit))
        val strict = root.resolve("landFactory.json")
        Files.writeString(strict, hjson)
        assertEquals(ContentErrorCode.MALFORMED_SYNTAX,
            assertIs<ContentResult.Failure>(ContentLoader.loadFactory(strict)).error.code)
    }

    @Test
    fun native_numeric_strings_booleans_and_null_are_not_silently_converted() = withFactory { root ->
        val path = root.resolve("landFactory.json")
        for ((field, original, invalid) in listOf(
            Triple("maxHp", "1200", "\"1200\""), Triple("radius", "30", "true"),
            Triple("fogOfWarSightRange", "15", "null"), Triple("image", "\"factory.png\"", "[]"),
        )) {
            val needle = "\"$field\":$original"
            kotlin.test.assertTrue(factoryJson.contains(needle))
            Files.writeString(path, factoryJson.replace(needle, "\"$field\":$invalid"))
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadFactory(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code, field)
            assertEquals(field, failure.field)
            assertNull(failure.section)
        }
    }

    @Test
    fun native_partial_parent_uses_shared_cross_format_inheritance_and_original_error_location() = withFactory { root ->
        val parent = root.resolve("base.hjson")
        Files.writeString(parent, factoryJson.replaceFirst("{", "{\n\"dont_load\":true,"))
        val child = root.resolve("landFactory.ini")
        Files.writeString(child, "format=rw-reforge.native-land-factory.v1\nname=landFactory\ncopyFrom=base.hjson\n")
        assertEquals(1200.0, assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(child)).value.maximumHealth)
        Files.writeString(parent, Files.readString(parent).replace("\"maxHp\":1200", "\"maxHp\":\"bad\""))
        val failure = assertIs<ContentResult.Failure>(ContentLoader.loadFactory(child)).error
        assertEquals(parent.toString(), failure.file)
        assertEquals("maxHp", failure.field)
        assertNull(failure.section)
        kotlin.test.assertTrue(failure.reason.contains(child.toString()), "Inherited syntax failure retains its consumer chain")
    }

    @Test
    fun unsupported_empty_actions_cannot_leak_from_turret_schema_into_the_ordinary_tank() = withFactory { root ->
        val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")
        val source = fixtures.resolve("three-format-tanks/json/units")
        for (name in listOf("tank.png", "tank_turret.png", "tank_dead.png"))
            if (!Files.exists(root.resolve(name))) Files.copy(source.resolve(name), root.resolve(name))
        val path = root.resolve("tank.json")
        Files.writeString(path, Files.readString(source.resolve("tank.json")).replaceFirst("{", "{\"actions\":[],"))
        val failure = assertIs<ContentResult.Failure>(ContentLoader.loadCombatUnit(path)).error
        assertEquals(ContentErrorCode.UNSUPPORTED_FIELD, failure.code)
        assertEquals("actions", failure.field)
    }

    @Test
    fun center_formats_keep_income_missiles_and_builder_production() = withUnits("production-combat/center/units") { root ->
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadCommandCenter(root.resolve("commandCenter.properties"))).value
        for (format in listOf("json", "hjson")) {
            val path = root.resolve("commandCenter.$format")
            Files.writeString(path, nativeJson(root.resolve("commandCenter.properties"), format))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCommandCenter(path)).value
            assertEquals(semanticValues(legacy), semanticValues(unit))
            assertEquals(4000.0, unit.maximumHealth)
            assertEquals(IncomeProfile(10f, 4.5f), unit.income)
            assertEquals(listOf("builder"), assertNotNull(unit.production).unitTypeIds)
            assertNotNull(assertNotNull(unit.combat).projectile.missile)
        }
    }

    @Test
    fun laser_formats_keep_interception_and_the_existing_construction_switch() = withUnits("production-combat/defense/units") { root ->
        for (construction in listOf(false, true)) {
            val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadPointDefense(root.resolve("laserDefence.properties"), construction)).value
            for (format in listOf("json", "hjson")) {
                val path = root.resolve("laserDefence.$format")
                Files.writeString(path, nativeJson(root.resolve("laserDefence.properties"), format))
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(
                    ContentLoader.loadPointDefense(path, construction)).value
                assertEquals(semanticValues(legacy), semanticValues(unit))
                assertEquals(500.0, unit.maximumHealth)
                assertEquals(160.0, assertNotNull(unit.pointDefense).range)
                assertEquals(if (construction) ConstructionProfile(1200L, .001f) else null, unit.construction)
                assertNull(unit.combat)
            }
        }
    }

    @Test
    fun factory_v2_keeps_long_price_float_rate_and_ordered_production() = withUnits("build-economy/factory/units") { root ->
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadFactory(root.resolve("landFactory.properties"))).value
        for (format in listOf("json", "hjson")) {
            val path = root.resolve("landFactory.$format")
            Files.writeString(path, nativeJson(root.resolve("landFactory.properties"), format))
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(path)).value
            assertEquals(semanticValues(legacy), semanticValues(unit))
            assertEquals(BuildProfile(700L, .001f), unit.build)
            assertEquals(ConstructionProfile(700L, .001f), unit.construction)
            assertEquals(listOf("builder", "c_tank"), assertNotNull(unit.production).unitTypeIds)
        }
    }

    @Test
    fun three_turret_forms_and_existing_switches_accept_named_structured_sources() = withUnits("maintenance-defense/construction/units") { root ->
        for (format in listOf("json", "hjson")) {
            Files.writeString(root.resolve("turret_common_land.$format"), unitJson(root.resolve("turret_common_land.ini"), format))
            for ((stem, upgrade, construction) in listOf(
                Triple("turret_t1", false, false), Triple("turret_t1", true, false),
                Triple("turret_t1", false, true), Triple("turret_t2_gun", false, false),
                Triple("turret_t1_artillery", false, false),
            )) {
                val legacy = assertIs<ContentResult.Success<UnitDefinition>>(
                    ContentLoader.loadTurretUnit(root.resolve("$stem.ini"), upgrade, construction)).value
                val path = root.resolve("$stem.$format")
                Files.writeString(path, unitJson(root.resolve("$stem.ini"), format))
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(
                    ContentLoader.loadTurretUnit(path, upgrade, construction)).value
                assertEquals(semanticValues(legacy), semanticValues(unit), "$stem / $format / $upgrade / $construction")
                assertEquals(MovementType.NONE, unit.movement.type)
                if (stem == "turret_t1") assertEquals(700.0, unit.maximumHealth)
                if (upgrade) assertEquals(2, assertNotNull(unit.upgrades).actions.size)
                if (construction) assertEquals(ConstructionProfile(500L, .0008f), unit.construction)
                if (stem == "turret_t1_artillery") assertEquals(80f, assertNotNull(assertNotNull(unit.combat).projectile.ground).areaDamage)
            }
        }
    }

    @Test
    fun public_source_receipts_cover_each_migrated_family_and_reject_late_byte_changes() {
        data class Case(val directory: String, val file: String, val load: (Path) -> ContentResult<LoadedUnitContent>)
        val cases = listOf(
            Case("production-combat/production/units", "landFactory.properties", ContentLoader::loadFactoryWithSources),
            Case("production-combat/center/units", "commandCenter.properties", ContentLoader::loadCommandCenterWithSources),
            Case("production-combat/defense/units", "laserDefence.properties", { ContentLoader.loadPointDefenseWithSources(it, true) }),
            Case("maintenance-defense/construction/units", "turret_t1.ini", { ContentLoader.loadTurretUnitWithSources(it, true, true) }),
        )
        for (case in cases) withUnits(case.directory) { root ->
            val path = root.resolve(case.file)
            val bytes = Files.readAllBytes(path)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(case.load(path)).value
            val source = loaded.sources.single { it.path == path }
            assertEquals(expected, source.sha256)
            assertEquals(bytes.size.toLong(), source.bytes)
            assertEquals(if (case.file.endsWith(".ini")) 2 else 1, loaded.sources.size)
            assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
            val changed = loaded.sources.last().path
            Files.writeString(changed, Files.readString(changed) + "\n# self-owned byte mutation\n")
            val failure = assertIs<ContentResult.Failure>(loaded.verifyStable()).error
            assertEquals(ContentErrorCode.BAD_REFERENCE, failure.code)
            assertEquals(changed.toString(), failure.file)
            assertEquals(expected, source.sha256)
        }
    }

    private fun unitJson(path: Path, format: String): String {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        for (raw in Files.readAllLines(path)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith('[')) {
                require(line.endsWith(']'))
                section = line.substring(1, line.length - 1).trim()
                require(sections.putIfAbsent(section, linkedMapOf()) == null)
            } else {
                val index = line.indexOf(':'); require(index > 0)
                require(sections.getValue(checkNotNull(section)).putIfAbsent(
                    line.substring(0, index).trim(), line.substring(index + 1).trim()) == null)
            }
        }
        val numbers = setOf("price", "buildSpeed", "maxHp", "radius", "displayRadius", "fogOfWarSightRange",
            "total_frames", "imageScale", "turretImageScale", "turretTurnSpeed", "turretSize", "maxAttackRange",
            "shootDelay", "x", "y", "turnSpeedAcceleration", "turnSpeedDeceleration", "recoilOffset",
            "recoilOutTime", "recoilReturnTime", "idleSweepAngle", "idleSweepDelay", "idleSweepSpeed", "turnSpeed",
            "barrelX", "barrelOffsetX_onOddShots", "directDamage", "life", "speed", "areaDamage", "areaRadius",
            "targetGroundSpread", "initialUnguidedSpeedHeight", "gravity", "moveSpeed", "moveAccelerationSpeed",
            "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration", "buildingToFootprintOffsetX", "buildingToFootprintOffsetY")
        val booleans = setOf("isBio", "isBuilding", "canAttack", "canAttackFlyingUnits", "canAttackLandUnits",
            "canAttackUnderwaterUnits", "canShoot", "shouldResetTurret", "targetGround", "dont_load")
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun value(key: String, text: String): String = when {
            key in booleans -> { require(text in setOf("true", "false")); text }
            key in numbers && !text.endsWith('s') -> text
            else -> quote(text)
        }
        fun objectText(values: Map<String, String>) = "{" + values.entries.joinToString(",") { (key, text) ->
            quote(key) + ":" + value(key, text)
        } + "}"
        val members = mutableListOf("\"format\":\"rw-reforge.unit.v2\"")
        sections["core"]?.remove("copyFrom")?.let { names ->
            members += "\"copyFrom\":[" + names.split(',').joinToString(",") {
                quote(it.trim().removeSuffix(".ini") + ".$format")
            } + "]"
        }
        val arrays = linkedMapOf<String, MutableList<String>>()
        for ((name, values) in sections) {
            val kind = when {
                name.startsWith("turret_") -> "weapons"
                name.startsWith("projectile_") -> "projectiles"
                name.startsWith("action_") -> "actions"
                else -> null
            }
            if (kind == null) members += quote(name) + ":" + objectText(values)
            else arrays.getOrPut(kind) { mutableListOf() }.add(
                "{\"name\":" + quote(name.substringAfter('_')) + (if (values.isEmpty()) "" else ",") + objectText(values).drop(1))
        }
        for ((name, entries) in arrays) members += quote(name) + ":[" + entries.joinToString(",") + "]"
        return "{\n" + (if (format == "hjson") "# named sections, formal Hjson\n" else "") + members.joinToString(",\n") + "\n}"
    }

    // Test-owned syntax conversion of fixed public data; no implementation schema is consulted.
    private fun nativeJson(path: Path, format: String): String {
        val numeric = setOf("maxHp", "radius", "fogOfWarSightRange", "price", "buildSpeed", "incomePeriod", "incomeCredits")
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val members = Files.readAllLines(path).filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.map { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            quote(key) + ":" + if (key in numeric) value else quote(value)
        }
        return "{\n" + (if (format == "hjson") "# actual Hjson input\n" else "") + members.joinToString(",\n") + "\n}"
    }

    private fun semanticValues(unit: UnitDefinition): List<Any?> = listOf(
        unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames, unit.imagePath,
        unit.imageScale, unit.movement, unit.retainedUnconsumed, unit.fogOfWarSightRange,
        unit.combat, unit.durability, unit.building, unit.production?.unitTypeIds, unit.build,
        unit.directHitTargetKind, unit.pointDefense, unit.upgrades?.actions, unit.income,
        unit.builder?.buildingTypeIds, unit.construction, unit.deathExplosion,
    )

    private fun withFactory(check: (Path) -> Unit) = withUnits("production-combat/production/units", check)

    private fun withUnits(relative: String, check: (Path) -> Unit) {
        val root = Files.createTempDirectory("p008-native-three-format-").toAbsolutePath().normalize()
        try {
            val fixtures = System.getProperty("public.fixtures.dir")?.let(Path::of)
                ?: Path.of("../../SimulationTest/public/inputs")
            val source = fixtures.resolve(relative)
            Files.list(source).use { paths -> paths.filter(Files::isRegularFile).forEach { original ->
                Files.copy(original, root.resolve(original.fileName))
            } }
            check(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(owned)
            } }
        }
    }

    private val factoryJson = """
        {
          "format":"rw-reforge.native-land-factory.v1", "name":"landFactory",
          "maxHp":1200, "radius":30, "footprint":"-1,-1,1,1", "movementType":"NONE",
          "productionUnit":"c_tank", "fogOfWarSightRange":15,
          "image":"factory.png", "image_back":"factory_back.png", "image_dead":"factory_dead.png"
        }
    """.trimIndent()
}
