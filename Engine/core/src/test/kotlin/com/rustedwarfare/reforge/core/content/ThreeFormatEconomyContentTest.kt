package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class ThreeFormatEconomyContentTest {
    @Test
    fun economic_template_roots_preserve_the_old_true_false_and_absent_diagnostic_categories() = withEconomy { root ->
        val stems = listOf("extractor", "fabricatorT1")
        for (format in listOf("ini", "json", "hjson")) {
            if (format != "ini") Files.writeString(root.resolve("extractor_common.$format"),
                economicJson(root.resolve("extractor_common.ini"), format))
            for (stem in stems) {
                val path = root.resolve("$stem.$format")
                val original = if (format == "ini") Files.readString(path) else economicJson(root.resolve("$stem.ini"), format)
                for (enabled in listOf(false, true)) for (flag in listOf<Boolean?>(null, true, false)) {
                    val author = if (flag == null) original else if (format == "ini")
                        original.replaceFirst("[core]", "[core]\ndont_load: $flag")
                    else original.replace("\"core\":{", "\"core\":{\"dont_load\":$flag,")
                    Files.writeString(path, author)
                    val legacy = ContentLoader.loadEconomyUnit(path, enabled)
                    val tracked = ContentLoader.loadEconomyUnitWithSources(path, enabled)
                    if (flag == null) {
                        assertIs<ContentResult.Success<UnitDefinition>>(legacy)
                        assertIs<ContentResult.Success<LoadedUnitContent>>(tracked)
                    } else {
                        val code = if (flag) ContentErrorCode.UNSUPPORTED_FEATURE else ContentErrorCode.UNSUPPORTED_FIELD
                        for (error in listOf(assertIs<ContentResult.Failure>(legacy).error, assertIs<ContentResult.Failure>(tracked).error)) {
                            assertEquals(code, error.code, "$stem/$format/upgrade=$enabled/dont_load=$flag")
                            assertEquals("core", error.section)
                            assertEquals("dont_load", error.field)
                            assertEquals(path.toString(), error.file)
                        }
                    }
                }
                Files.writeString(path, original)
            }
        }
    }

    @Test
    fun economic_source_api_tracks_a_mixed_parent_and_reports_its_real_declared_failure() = withEconomy { root ->
        val parent = root.resolve("extractor_common.hjson")
        Files.writeString(parent, economicJson(root.resolve("extractor_common.ini"), "hjson"))
        val child = root.resolve("extractor.ini")
        Files.writeString(child, Files.readString(child).replace("extractor_common.ini", "extractor_common.hjson"))
        val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadEconomyUnitWithSources(child)).value
        assertEquals(setOf(child, parent), loaded.sources.map { it.path }.toSet())
        assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
        assertNull(loaded.definition.upgrades)
        assertEquals(1400L, assertIs<ContentResult.Success<LoadedUnitContent>>(
            ContentLoader.loadEconomyUnitWithSources(child, true)).value.definition.upgrades!!.actions.single().price)
        for (row in loaded.sources) assertEquals(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(row.path))
            .joinToString("") { "%02x".format(it) }, row.sha256)
        Files.writeString(parent, Files.readString(parent).replace("\"mass\":9000", "\"mass\":\"bad\""))
        assertIs<ContentResult.Failure>(loaded.verifyStable())
        val failure = assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnitWithSources(child)).error
        assertEquals(parent.toString(), failure.file)
        assertEquals("core", failure.section)
        assertEquals("mass", failure.field)
        assertTrue(failure.reason.contains(child.toString()))
    }

    @Test
    fun economic_six_forms_keep_all_semantics_and_existing_upgrade_switches_in_three_formats() = withEconomy { root ->
        val stems = listOf("extractor", "extractorT2", "extractorT3", "fabricatorT1", "fabricatorT2", "fabricatorT3")
        val legacy = stems.associateWith { assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("$it.ini"), true)).value }
        for (format in listOf("ini", "json", "hjson")) {
            if (format != "ini") for (stem in stems + "extractor_common")
                Files.writeString(root.resolve("$stem.$format"), economicJson(root.resolve("$stem.ini"), format))
            for ((index, stem) in stems.withIndex()) {
                val path = root.resolve("$stem.$format")
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(path, true)).value
                assertEquals(semanticValues(legacy.getValue(stem)), semanticValues(unit), "$stem/$format")
                assertEquals(listOf(800.0, 1000.0, 2000.0, 500.0, 1000.0, 2200.0)[index], unit.maximumHealth)
                assertEquals(listOf(8f, 12f, 20f, 2f, 7f, 14f)[index], unit.income!!.credits)
                assertEquals(listOf(700L, 2100L, 6100L, 2200L, 6600L, 14100L)[index], unit.construction!!.price)
                if (index == 0 || index == 3) {
                    val disabled = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(path)).value
                    assertNull(disabled.upgrades)
                } else assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE,
                    assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(path)).error.code)
            }
            val extractor = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("extractor.$format"), true)).value
            assertEquals(UpgradeActionProfile("upgrade_t2", "extractorT2", 1400L, .0006f), extractor.upgrades!!.actions.single())
            val fabricator = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("fabricatorT1.$format"), true)).value
            assertEquals(UpgradeActionProfile("upgrade_t2", "fabricatorT2", 4400L, 1f / (65.6f * 60f)), fabricator.upgrades!!.actions.single())
            assertNull(assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("extractorT3.$format"), true)).value.upgrades)
        }
    }

    @Test
    fun economic_templates_and_scalar_types_do_not_gain_permissions_from_the_common_reader() = withEconomy { root ->
        for (stem in listOf("extractor", "extractor_common", "fabricatorT1", "fabricatorT2"))
            Files.writeString(root.resolve("$stem.json"), economicJson(root.resolve("$stem.ini"), "json"))
        val path = root.resolve("extractor.json")
        val original = Files.readString(path)
        for ((field, bad) in listOf(
            "buildSpeed" to original.replace("\"buildSpeed\":0.001", "\"buildSpeed\":\"0.001\""),
            "canAttack" to original.replace("\"canAttack\":false", "\"canAttack\":\"false\""),
            "dont_load" to original.replace("\"core\":{", "\"core\":{\"dont_load\":false,"))) {
            assertNotEquals(original, bad)
            Files.writeString(path, bad)
            assertEquals(field, assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(path)).error.field)
        }
        Files.writeString(path, original)
        val parent = root.resolve("extractor_common.json")
        Files.writeString(parent, Files.readString(parent).replace("\"dont_load\":true", "\"dont_load\":false"))
        assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(path))
        val fabricator = root.resolve("fabricatorT1.json")
        Files.writeString(fabricator, Files.readString(fabricator).replaceFirst("{", "{\"copyFrom\":[],"))
        assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(fabricator))
        assertIs<ContentResult.Failure>(ContentLoader.loadEconomyUnit(root.resolve("fabricatorT2.json"), true))
    }

    @Test
    fun json_extractor_reaches_the_existing_income_and_construction_definition() = withEconomy { root ->
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("extractor.ini"))).value
        for (stem in listOf("extractor", "extractor_common"))
            Files.writeString(root.resolve("$stem.json"), economicJson(root.resolve("$stem.ini"), "json"))
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadEconomyUnit(root.resolve("extractor.json"))).value
        assertEquals(legacy.durability, unit.durability)
        assertEquals(legacy.income, unit.income)
        assertEquals(legacy.construction, unit.construction)
        assertEquals(legacy.retainedUnconsumed, unit.retainedUnconsumed)
        assertEquals(800.0, unit.maximumHealth)
        assertEquals(IncomeProfile(40f, 8f), unit.income)
        assertEquals(ConstructionProfile(700L, .001f), unit.construction)
        assertTrue(assertNotNull(unit.building).requiresResourcePool)
        assertNull(unit.upgrades)
    }

    @Test
    fun three_formats_preserve_all_four_existing_builder_profiles_and_reject_numeric_strings() {
        val profiles = listOf(
            "build-economy/start/units" to null,
            "build-economy/factory/units" to listOf("landFactory"),
            "build-economy/economy/units" to listOf("extractorT1", "landFactory", "fabricatorT1"),
            "maintenance-defense/construction/units" to listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence"))
        for ((fixture, targets) in profiles) withBuilder(fixture) { root ->
            val old = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(root.resolve("builder.properties"))).value
            val raw = Files.readString(root.resolve("builder.properties"))
            val stringFields = setOf("format", "name", "movementType", "image", "image_dead", "constructionTypes")
            val json = raw.lineSequence().filter { '=' in it && !it.trim().startsWith('#') }.joinToString(",", "{", "}") {
                val (key, value) = it.trim().split('=', limit = 2)
                "\"$key\":" + if (key in stringFields) "\"$value\"" else value
            }
            for (format in listOf("ini", "json", "hjson")) {
                val path = root.resolve("builder.$format")
                Files.writeString(path, if (format == "ini") raw else if (format == "hjson") json.replaceFirst("{", "{\n# Actual Hjson syntax\n") else json)
                val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(path)).value
                assertEquals(listOf(old.id, old.className, old.radius, old.displayRadius, old.totalFrames, old.imagePath,
                    old.imageScale, old.movement, old.retainedUnconsumed, old.fogOfWarSightRange, old.durability, old.build),
                    listOf(unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames, unit.imagePath,
                    unit.imageScale, unit.movement, unit.retainedUnconsumed, unit.fogOfWarSightRange, unit.durability, unit.build))
                assertEquals(targets, unit.builder?.buildingTypeIds)
                assertEquals(.8, unit.movement.moveSpeed)
                assertNull(unit.combat); assertNull(unit.income); assertNull(unit.upgrades)
            }
            val path = root.resolve("builder.json")
            Files.writeString(path, json.replace("\"moveSpeed\":0.8", "\"moveSpeed\":\"0.8\""))
            val failure = assertIs<ContentResult.Failure>(ContentLoader.loadBuilder(path)).error
            assertEquals(ContentErrorCode.MALFORMED_SYNTAX, failure.code)
            assertEquals("moveSpeed", failure.field)
            assertNull(failure.section)
        }
    }

    @Test
    fun builder_source_api_retains_actual_cross_format_bytes_and_rejects_changed_parent() = withBuilder { root ->
        val parent = root.resolve("base.hjson")
        Files.writeString(parent, builderJson.replaceFirst("{", "{\n# Shared partial native definition\n\"dont_load\":true,"))
        val child = root.resolve("builder.ini")
        Files.writeString(child, "format=rw-reforge.native-builder.v1\nname=builder\ncopyFrom=base.hjson\n")
        val loaded = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadBuilderWithSources(child)).value
        assertEquals(setOf(child, parent), loaded.sources.map { it.path }.toSet())
        for (row in loaded.sources) {
            val bytes = Files.readAllBytes(row.path)
            assertEquals(bytes.size.toLong(), row.bytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, row.sha256)
        }
        assertIs<ContentResult.Success<Unit>>(loaded.verifyStable())
        Files.writeString(parent, Files.readString(parent).replace("\"maxHp\":170", "\"maxHp\":\"170\""))
        assertIs<ContentResult.Failure>(loaded.verifyStable())
        val failure = assertIs<ContentResult.Failure>(ContentLoader.loadBuilderWithSources(child)).error
        assertEquals(parent.toString(), failure.file)
        assertNull(failure.section)
        assertEquals("maxHp", failure.field)
        assertTrue(failure.reason.contains(child.toString()))
    }

    @Test
    fun json_builder_uses_the_existing_public_native_definition() = withBuilder { root ->
        val old = assertIs<ContentResult.Success<UnitDefinition>>(
            ContentLoader.loadBuilder(root.resolve("builder.properties"))).value
        val path = root.resolve("builder.json")
        Files.writeString(path, builderJson)
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadBuilder(path)).value
        assertEquals("builder", unit.id)
        assertEquals(old.movement, unit.movement)
        assertEquals(old.durability, unit.durability)
        assertEquals(old.imagePath, unit.imagePath)
        assertEquals(170.0, unit.maximumHealth)
        assertEquals(10.0, unit.radius)
        assertEquals(12.0, unit.displayRadius)
        assertEquals(15, unit.fogOfWarSightRange)
        assertEquals(500L, assertNotNull(unit.build).price)
        assertEquals(.002f, unit.build!!.rate)
        assertNull(unit.builder)
        assertNull(unit.combat)
    }

    private fun withBuilder(fixture: String = "build-economy/start/units", block: (Path) -> Unit) {
        val source = (System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")).resolve(fixture)
        val root = Files.createTempDirectory("three-format-builder-")
        try {
            for (name in listOf("builder.properties", "builder.png", "builder_dead.png"))
                Files.copy(source.resolve(name), root.resolve(name))
            block(root)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun withEconomy(block: (Path) -> Unit) {
        val source = (System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("../../SimulationTest/public/inputs")).resolve("build-economy/upgrades/units")
        val root = Files.createTempDirectory("three-format-economy-")
        try {
            Files.list(source).use { files -> files.filter(Files::isRegularFile).forEach { Files.copy(it, root.resolve(it.fileName)) } }
            block(root)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun semanticValues(unit: UnitDefinition): List<Any?> = listOf(
        unit.id, unit.className, unit.radius, unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale,
        unit.movement, unit.retainedUnconsumed, unit.fogOfWarSightRange, unit.combat, unit.durability, unit.building,
        unit.production?.unitTypeIds, unit.build, unit.directHitTargetKind, unit.pointDefense, unit.upgrades?.actions,
        unit.income, unit.builder?.buildingTypeIds, unit.construction, unit.deathExplosion)

    // Test-owned authoring translation of fixed public data; no runtime parser/schema or trace is consulted.
    private fun economicJson(path: Path, format: String): String {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: String? = null
        for (raw in Files.readAllLines(path)) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith('[')) {
                require(line.endsWith(']'))
                current = line.substring(1, line.length - 1)
                require(sections.putIfAbsent(current, linkedMapOf()) == null)
            } else {
                val separator = line.indexOf(':'); require(separator > 0)
                require(sections.getValue(checkNotNull(current)).putIfAbsent(line.substring(0, separator).trim(), line.substring(separator + 1).trim()) == null)
            }
        }
        val numeric = setOf("price", "maxHp", "mass", "techLevel", "buildSpeed", "radius", "displayRadius",
            "buildingSelectionOffset", "numBitsOnDeath", "total_frames", "maxAttackRange", "moveSpeed",
            "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration", "KeyframeTimeScale",
            "buildPriority", "recommendedInEachBasePriorityIfUnmet", "recommendedInEachBaseNum", "noneInBaseExtraPriority",
            "noneGlobalExtraPriority", "maxEachBase", "maxGlobal", "x", "y", "life", "speed", "frame", "shadowFrame",
            "drawType", "areaDamage", "areaRadius", "areaExpandTime", "deflectionPower", "lightSize")
        val booleans = setOf("dont_load", "isBio", "isBuilding", "placeOnlyOnResPool", "hideScorchMark",
            "image_back_always_use_full_image", "canAttack", "canAttackFlyingUnits", "canAttackLandUnits",
            "canAttackUnderwaterUnits", "pingPong", "canShoot", "instant", "largeHitEffect", "targetGround", "friendlyFire", "skipInheritance")
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun objectText(values: Map<String, String>) = values.entries.joinToString(",", "{", "}") { (key, value) ->
            val outputKey = if (key == "@copyFrom_skipThisSection") "skipInheritance" else key
            quote(outputKey) + ":" + when {
                outputKey in booleans -> { require(value in setOf("true", "false")); value }
                outputKey in numeric && !value.endsWith('s') -> value
                else -> quote(value)
            }
        }
        val members = mutableListOf("\"format\":\"rw-reforge.unit.v2\"")
        sections["core"]?.remove("copyFrom")?.let {
            members += "\"copyFrom\":[" + quote(it.removeSuffix(".ini") + ".$format") + "]"
        }
        val arrays = linkedMapOf<String, MutableList<String>>()
        for ((section, values) in sections) {
            val kind = when { section.startsWith("action_") -> "actions"; section.startsWith("turret_") -> "weapons"; section.startsWith("projectile_") -> "projectiles"; else -> null }
            if (kind == null) members += quote(section) + ":" + objectText(values)
            else arrays.getOrPut(kind) { mutableListOf() }.add("{\"name\":" + quote(section.substringAfter('_')) + "," + objectText(values).drop(1))
        }
        for ((kind, entries) in arrays) members += quote(kind) + ":[" + entries.joinToString(",") + "]"
        return "{\n" + (if (format == "hjson") "# Actual formal Hjson\n" else "") + members.joinToString(",\n") + "\n}"
    }

    private val builderJson = """{
      "format":"rw-reforge.native-builder.v1", "name":"builder", "maxHp":170,
      "radius":10, "displayRadius":12, "movementType":"LAND", "moveSpeed":0.8,
      "moveAccelerationSpeed":0.04, "moveDecelerationSpeed":0.1, "maxTurnSpeed":3.8,
      "turnAcceleration":0.35, "price":500, "buildSpeed":0.002, "fogOfWarSightRange":15,
      "image":"builder.png", "image_dead":"builder_dead.png"
    }"""
}
