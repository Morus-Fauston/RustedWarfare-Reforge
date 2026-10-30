package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DefenseConstructionContentTest {
    @Test fun builder_v4_exposes_exactly_five_buildings_without_changing_older_formats() {
        val original = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("build-economy/queue/units/builder.properties") }
        ?: root().resolve(".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/input/units/builder.properties"))
        val text = Files.readString(original).replace(Regex("(?m)^(image|image_dead)=(.*)$")) {
            "${it.groupValues[1]}=${original.parent.resolve(it.groupValues[2].trim()).toAbsolutePath()}"
        }
        val file = Files.createTempFile("p006-defense-builder-", ".properties")
        val five = listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence")
        fun input(version: Int, types: List<String>?): String = text
            .replace("rw-reforge.native-builder.v3", "rw-reforge.native-builder.v$version")
            .replace(Regex("(?m)^constructionTypes=.*$"), types?.let { "constructionTypes=${it.joinToString(",")}" } ?: "")
        fun load(value: String): ContentResult<UnitDefinition> {
            Files.writeString(file, value)
            return ContentLoader.loadBuilder(file)
        }
        try {
            for ((version, targets) in listOf(1 to null, 2 to listOf("landFactory"), 3 to five.take(3))) {
                val old = assertIs<ContentResult.Success<UnitDefinition>>(load(input(version, targets))).value
                assertEquals(targets, old.builder?.buildingTypeIds)
                assertEquals(BuildProfile(500L, .002f), old.build)
            }
            val enabled = assertIs<ContentResult.Success<UnitDefinition>>(load(input(4, five))).value
            assertEquals(five, enabled.builder!!.buildingTypeIds)
            assertEquals(BuildProfile(500L, .002f), enabled.build)
            for (types in listOf(five.dropLast(1), five + "commandCenter", five.reversed(),
                    five.dropLast(1) + "c_turret_t2_gun")) {
                val error = assertIs<ContentResult.Failure>(load(input(4, types))).error
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
                assertEquals("constructionTypes", error.field)
            }
            val missing = assertIs<ContentResult.Failure>(load(input(4, null))).error
            assertEquals(ContentErrorCode.MISSING_REQUIRED_FIELD, missing.code)
            assertEquals("constructionTypes", missing.field)
            val unknown = assertIs<ContentResult.Failure>(load(input(5, five))).error
            assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, unknown.code)
            assertEquals("format", unknown.field)
        } finally { Files.deleteIfExists(file) }
    }

    @Test fun point_defense_construction_is_opt_in_and_keeps_the_legacy_profile() {
        val path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/point-defense/units/laserDefence.properties") }
        ?: root().resolve(".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-005/20261026-e-defense-input-01/input/units/laserDefence.properties"))
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadPointDefense(path)).value
        val disabled = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadPointDefense(path, false)).value
        assertEquals(metadata(legacy), metadata(disabled))
        assertEquals(legacy.building, disabled.building)
        assertEquals(legacy.construction, disabled.construction)
        assertNull(legacy.construction)
        assertNull(legacy.building!!.anchor)
        val enabled = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadPointDefense(path, true)).value
        assertEquals(ConstructionProfile(1200L, .001f), enabled.construction)
        assertEquals(FootprintAnchor(20f, 20f), enabled.building!!.anchor)
        assertEquals(metadata(legacy), metadata(enabled))
        assertEquals(legacy.building!!.copy(anchor = FootprintAnchor(20f, 20f)), enabled.building)
        assertNull(enabled.combat)
        assertNull(enabled.production)
        assertNull(enabled.upgrades)
    }

    @Test fun construction_is_an_independent_explicit_capability_of_the_basic_turret() {
        val path = source().resolve("turret_t1.ini")
        val legacy = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path)).value
        for (upgrades in listOf(false, true)) {
            val old = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path, upgrades)).value
            val disabled = assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadTurretUnit(path, upgrades, false)).value
            assertEquals(old.id, disabled.id)
            assertEquals(old.construction, disabled.construction)
            assertEquals(old.upgrades?.actions, disabled.upgrades?.actions)
            assertEquals(old.combat, disabled.combat)
            assertEquals(old.building, disabled.building)
            assertEquals(old.retainedUnconsumed, disabled.retainedUnconsumed)
            val enabled = assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadTurretUnit(path, upgrades, true)).value
            assertEquals(ConstructionProfile(500L, .0008f), enabled.construction)
            assertEquals(old.upgrades?.actions, enabled.upgrades?.actions)
            assertEquals(old.combat, enabled.combat)
            assertEquals(old.building, enabled.building)
            assertEquals(old.retainedUnconsumed - setOf("core.price", "core.buildSpeed"), enabled.retainedUnconsumed)
        }
        assertNull(legacy.construction)
        assertNull(legacy.upgrades)
    }

    @Test fun turret_construction_fields_fail_at_the_reader_only_when_opted_in() = withTurretCopy { folder ->
        val path = folder.resolve("turret_t1.ini")
        val original = Files.readString(path)
        val cases = listOf(
            Triple("price", null, ContentErrorCode.MISSING_REQUIRED_FIELD),
            Triple("price", "", ContentErrorCode.MISSING_REQUIRED_FIELD),
            Triple("price", "1.5", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("price", "NaN", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("price", "0", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("price", "-1", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("price", "501", ContentErrorCode.UNSUPPORTED_FEATURE),
            Triple("buildSpeed", null, ContentErrorCode.MISSING_REQUIRED_FIELD),
            Triple("buildSpeed", "", ContentErrorCode.MISSING_REQUIRED_FIELD),
            Triple("buildSpeed", "NaN", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("buildSpeed", "Infinity", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("buildSpeed", "0", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("buildSpeed", "-0.001", ContentErrorCode.MALFORMED_SYNTAX),
            Triple("buildSpeed", "0.0009", ContentErrorCode.UNSUPPORTED_FEATURE))
        for ((field, value, code) in cases) {
            val core = original.substringBefore("[action_upgrade_gun]")
                .replace(Regex("(?m)^$field:.*$"), value?.let { "$field: $it" } ?: "")
            Files.writeString(path, core + "[action_upgrade_gun]" + original.substringAfter("[action_upgrade_gun]"))
            val old = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path, false)).value
            assertNull(old.construction)
            for (upgrades in listOf(false, true)) {
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path, upgrades, true)).error
                assertEquals(code, error.code, "$field=$value")
                assertEquals("core", error.section)
                assertEquals(field, error.field)
                assertEquals(path.toAbsolutePath().normalize().toString(), error.file)
            }
        }
    }

    @Test fun construction_never_enables_upgrades_or_high_tier_building_entries() = withTurretCopy { folder ->
        for (name in listOf("turret_t2_gun.ini", "turret_t1_artillery.ini")) {
            val path = folder.resolve(name)
            assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadTurretUnit(path, false, false))
            for (upgrades in listOf(false, true)) {
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path, upgrades, true)).error
                assertEquals(ContentErrorCode.UNSUPPORTED_FEATURE, error.code)
                assertEquals("core", error.section)
                assertEquals("name", error.field)
            }
        }
        val path = folder.resolve("turret_t1.ini")
        val text = Files.readString(path)
        for ((change, field, code) in listOf(
            Triple(text.replaceFirst("price:  1000", ""), "price", ContentErrorCode.MISSING_REQUIRED_FIELD),
            Triple(text.replaceFirst("price:  1000", "price: NaN"), "price", ContentErrorCode.MALFORMED_SYNTAX),
            Triple(text.replaceFirst("convertTo: c_turret_t2_gun", "convertTo: c_turret_t1_artillery"),
                "convertTo", ContentErrorCode.UNSUPPORTED_FEATURE))) {
            Files.writeString(path, change)
            val constructionOnly = assertIs<ContentResult.Success<UnitDefinition>>(
                ContentLoader.loadTurretUnit(path, false, true)).value
            assertNull(constructionOnly.upgrades)
            assertEquals(ConstructionProfile(500L, .0008f), constructionOnly.construction)
            val error = assertIs<ContentResult.Failure>(ContentLoader.loadTurretUnit(path, true, true)).error
            assertEquals(code, error.code)
            assertEquals("action_upgrade_gun", error.section)
            assertEquals(field, error.field)
        }
    }

    private fun withTurretCopy(body: (Path) -> Unit) {
        val folder = Files.createTempDirectory("p006-defense-reader-")
        try {
            Files.list(source()).use { files -> files.forEach { Files.copy(it, folder.resolve(it.fileName)) } }
            body(folder)
        } finally {
            Files.list(folder).use { files -> files.forEach { Files.delete(it) } }
            Files.delete(folder)
        }
    }

    private fun metadata(unit: UnitDefinition): List<Any?> = listOf(unit.id, unit.className, unit.radius,
        unit.displayRadius, unit.totalFrames, unit.imagePath, unit.imageScale, unit.movement,
        unit.retainedUnconsumed, unit.fogOfWarSightRange, unit.combat, unit.durability, unit.production,
        unit.build, unit.directHitTargetKind, unit.pointDefense, unit.upgrades?.actions, unit.income,
        unit.builder?.buildingTypeIds, unit.deathExplosion)

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("AGENTS.md")) }

    private fun source(): Path = (System.getProperty("public.fixtures.dir")?.let { Path.of(it).resolve("maintenance-defense/construction/units") }
        ?: root().resolve(
            ".scratch/开发票据/引擎重写/P006-建造经济与原版操作/evidence/ENG-P006-007/20261029-implementation-01/turret-input/units"))
}
