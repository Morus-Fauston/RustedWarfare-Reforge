package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.ContentError
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.LoadedUnitContent
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Properties
import java.util.Collections

/** Fixed private sandbox fixtures, not a general scenario-file interpreter. */
object ControlledScenario {
    fun load(root: Path): Scenario = prepare(root).scenario

    internal fun prepare(root: Path): PreparedControlledScenario {
        val content = mutableListOf<LoadedUnitContent>()
        val scenario = load(root, content)
        return PreparedControlledScenario(scenario, content).also { it.verifyStable() }
    }

    private fun load(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        checkCancellation()
        if (Files.exists(root.resolve("construction.properties"), NOFOLLOW_LINKS)) return loadConstruction(root, content)
        if (Files.exists(root.resolve("patrol.properties"), NOFOLLOW_LINKS)) return loadPatrol(root, content)
        if (Files.exists(root.resolve("upgrade.properties"), NOFOLLOW_LINKS)) return loadUpgrade(root, content)
        if (Files.exists(root.resolve("point-defense.properties"), NOFOLLOW_LINKS)) return loadPointDefense(root, content)
        if (Files.exists(root.resolve("turret.properties"), NOFOLLOW_LINKS)) return loadTurret(root, content)
        if (Files.exists(root.resolve("production.properties"), NOFOLLOW_LINKS)) return loadProduction(root, content)
        if (Files.exists(root.resolve("combat.properties"), NOFOLLOW_LINKS)) return loadCombat(root, content)
        val map = when (val result = ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val tank = when (val result = ContentLoader.loadUnitVariant(root.resolve("units/weaponless_tank.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val manifest = readManifest(root)
        checkCancellation()
        val players = if (manifest == null) listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE))
            else (1..3).map { index -> Player("p$index", if (index == 1) PlayerControl.LOCAL else PlayerControl.NONE,
                manifest.getProperty("p$index.team")) }
        val units = if (manifest == null) listOf(
            UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0),
            UnitSpawn("u2", "p2", "tank", Position(510.0, 310.0), 0.0))
            else (1..3).map { index -> UnitSpawn("u$index", "p$index", "tank",
                Position(manifest.getProperty("p$index.x").toDouble(), manifest.getProperty("p$index.y").toDouble()),
                manifest.getProperty("p$index.heading").toDouble()) }
        return Scenario(
            if (manifest == null) "p001-windows" else "p002-knowledge", map, mapOf("tank" to tank), players, units,
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(16.0, 4.0),
        )
    }

    private fun loadConstruction(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val marker = root.resolve("construction.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("construction.properties")) { "$marker: expected ordinary in-root file" }
        val variant = when (Files.readString(marker).trim().replace("\r\n", "\n")) {
            "format=rw-reforge.construction-sandbox.v1\nvariant=command-center-start" -> "start"
            "format=rw-reforge.construction-sandbox.v1\nvariant=command-center-defense" -> "defense"
            "format=rw-reforge.construction-sandbox.v1\nvariant=factory-construction" -> "factory"
            "format=rw-reforge.construction-sandbox.v1\nvariant=economy-construction" -> "economy"
            "format=rw-reforge.construction-sandbox.v1\nvariant=economy-death" -> "economy-death"
            "format=rw-reforge.construction-sandbox.v1\nvariant=economy-upgrades" -> "economy-upgrades"
            "format=rw-reforge.construction-sandbox.v1\nvariant=building-maintenance" -> "building-maintenance"
            "format=rw-reforge.construction-sandbox.v1\nvariant=construction-queue" -> "construction-queue"
            "format=rw-reforge.construction-sandbox.v1\nvariant=platform-maintenance" -> "platform-maintenance"
            "format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction" -> "defense-construction"
            "format=rw-reforge.construction-sandbox.v1\nvariant=defense-construction-siege" -> "defense-construction-siege"
            else -> error("$marker: unsupported fixed construction fixture")
        }
        fun <T> read(result: ContentResult<T>): T {
            checkCancellation()
            return when (result) {
                is ContentResult.Success -> result.value
                is ContentResult.Failure -> error(result.error.describe())
            }
        }
        val map = read(ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx")))
        val tank = loadTank(root, content)
        val builder = tracked(ContentLoader.loadBuilderWithSources(unitPath(root, "builder", "properties")), content)
        val center = readCenter(root, content)
        val defenses = variant in setOf("defense-construction", "defense-construction-siege")
        val maintenance = defenses || variant in setOf("building-maintenance", "construction-queue", "platform-maintenance")
        val economic = defenses || variant in setOf("economy", "economy-death", "economy-upgrades", "building-maintenance", "construction-queue", "platform-maintenance")
        val economyUpgrades = variant == "economy-upgrades" || maintenance
        val factory = if (variant == "factory" || economic) readFactory(root, content) else null
        val economicUnits = if (economic) (listOf("extractor", "fabricatorT1") +
            if (economyUpgrades) listOf("extractorT2", "extractorT3", "fabricatorT2", "fabricatorT3") else emptyList()).map {
            tracked(ContentLoader.loadEconomyUnitWithSources(unitPath(root, it, "ini"), economyUpgrades), content)
        } else emptyList()
        val defenseUnits = if (defenses) listOf(
            readTurret(root, "turret_t1", content, true, true),
            readTurret(root, "turret_t2_gun", content, false, false),
            readTurret(root, "turret_t1_artillery", content, false, false),
            readPointDefense(root, content, true)) else emptyList()
        require(if (variant == "factory" || economic) builder.builder?.buildingTypeIds ==
            (if (defenses) listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence")
             else if (economic) listOf("extractorT1", "landFactory", "fabricatorT1") else listOf("landFactory")) &&
            factory?.construction != null && factory.production?.unitTypeIds == listOf("builder", "c_tank")
            else builder.builder == null) { "$marker: construction capabilities must match the fixed variant" }
        val enemies = if (variant in setOf("defense", "defense-construction-siege")) (1..12).map { index ->
            UnitSpawn("z" + index.toString().padStart(2, '0'), "p2", tank.id,
                Position(190.0 + ((index - 1) / 6) * 22.0, 150.0 + ((index - 1) % 6) * 22.0), 270.0)
        } else listOf(UnitSpawn("z1", "p2", tank.id, Position(510.0, 110.0), 270.0),
            UnitSpawn("z2", "p2", tank.id, Position(510.0, 330.0), 270.0))
        val spawns = if (variant == "platform-maintenance") listOf(
            UnitSpawn("extractor", "p1", "extractorT1", Position(190.0, 290.0), 0.0),
            UnitSpawn("worker", "p1", builder.id, Position(270.0, 270.0), 0.0))
        else if (variant == "economy-death") listOf(
            UnitSpawn("fab", "p1", "fabricatorT1", Position(330.0, 270.0), 0.0),
            UnitSpawn("worker", "p1", builder.id, Position(270.0, 270.0), 0.0),
            UnitSpawn("z1", "p2", tank.id, Position(410.0, 250.0), 270.0),
            UnitSpawn("z2", "p2", tank.id, Position(410.0, 290.0), 270.0))
        else listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0)) + enemies
        return Scenario("p006-construction", map, (listOfNotNull(tank, builder, center, factory) + economicUnits + defenseUnits).associateBy { it.id },
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            spawns,
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1,
                spawns.mapIndexed { index, spawn -> spawn.unitId to 10L + index }.toMap(), 10L + spawns.size),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L)),
            maintenanceContract = if (maintenance) MaintenanceContract(mapOf("commandCenter" to 3000L, "landFactory" to 700L,
                "extractorT1" to 700L, "extractorT2" to 2100L, "extractorT3" to 6100L,
                "fabricatorT1" to 2200L, "fabricatorT2" to 6600L, "fabricatorT3" to 14100L) +
                if (defenses) mapOf("c_turret_t1" to 500L, "c_turret_t2_gun" to 1500L,
                    "c_turret_t1_artillery" to 2100L, "laserDefence" to 1200L) else emptyMap()) else null)
    }

    private fun loadPatrol(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val marker = root.resolve("patrol.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("patrol.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.patrol-sandbox.v1\nvariant=patrol-return") { "$marker: unsupported fixed patrol fixture" }
        val base = loadProduction(root, content)
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = com.rustedwarfare.reforge.core.content.UnitDefinition("patrol_target", tank.className,
            tank.radius, tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement,
            tank.retainedUnconsumed, tank.fogOfWarSightRange,
            durability = com.rustedwarfare.reforge.core.content.DurabilityProfile(25.0, checkNotNull(tank.combat).deadImagePath))
        return base.copy(scenarioId = "p005-patrol", unitDefinitions = base.unitDefinitions + (target.id to target),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("c", "p1", tank.id, Position(110.0, 330.0), 90.0),
                UnitSpawn("f", "p1", "landFactory", Position(310.0, 330.0), 0.0),
                UnitSpawn("b", "p2", target.id, Position(310.0, 210.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 11L, "f" to 10L), 12L))
    }

    private fun loadUpgrade(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val marker = root.resolve("upgrade.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("upgrade.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.upgrade-sandbox.v1\nvariant=two-branches") { "$marker: unsupported fixed upgrade fixture" }
        val base = loadProduction(root, content)
        fun tower(name: String, enabled: Boolean = false) = readTurret(root, name, content, enabled)
        checkCancellation()
        val forms = listOf(tower("turret_t1", true), tower("turret_t2_gun"), tower("turret_t1_artillery"))
        checkCancellation()
        val enemies = listOf(126, 150, 174).mapIndexed { index, y ->
            UnitSpawn("b0${index + 1}", "p2", "c_tank", Position(405.0, y.toDouble()), 270.0) }
        return base.copy(scenarioId = "p005-upgrade", unitDefinitions = base.unitDefinitions + forms.associateBy { it.id },
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 310.0), 90.0),
                UnitSpawn("f", "p1", "landFactory", Position(310.0, 310.0), 0.0),
                UnitSpawn("t", "p1", "c_turret_t1", Position(230.0, 150.0), 90.0)) + enemies,
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L, "t" to 11L,
                "b01" to 12L, "b02" to 13L, "b03" to 14L), 15L))
    }

    private fun loadPointDefense(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val marker = root.resolve("point-defense.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("point-defense.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.point-defense-sandbox.v1\nvariant=level-one") { "$marker: unsupported fixed defense fixture" }
        val base = loadProduction(root, content)
        checkCancellation()
        val defense = readPointDefense(root, content)
        val shooters = listOf(138, 162, 186, 210, 234, 258, 282).map { 410 to it } +
            listOf(162, 186, 210, 234, 258).map { 434 to it }
        val enemy = shooters.mapIndexed { index, (x, y) -> UnitSpawn("b" + (index + 1).toString().padStart(2, '0'), "p2", "c_tank",
            Position(x.toDouble(), y.toDouble()), 270.0) }
        return base.copy(scenarioId = "p005-point-defense", unitDefinitions = base.unitDefinitions + (defense.id to defense),
            units = base.units.filter { it.unitId != "b" } +
                UnitSpawn("d", "p1", defense.id, Position(230.0, 150.0), 0.0) + enemy,
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L, "d" to 11L) +
                enemy.mapIndexed { index, unit -> unit.unitId to (12L + index) }, 24L))
    }

    private fun loadTurret(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val marker = root.resolve("turret.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("turret.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.turret-sandbox.v1\nvariant=basic-turret") { "$marker: unsupported fixed turret fixture" }
        val base = loadProduction(root, content)
        checkCancellation()
        val turret = readTurret(root, "turret_t1", content)
        return base.copy(scenarioId = "p005-turret", unitDefinitions = base.unitDefinitions + (turret.id to turret),
            units = base.units + listOf(UnitSpawn("t", "p1", turret.id, Position(280.0, 150.0), 90.0),
                UnitSpawn("u", "p2", turret.id, Position(500.0, 100.0), 270.0),
                UnitSpawn("g", "p2", "landFactory", Position(430.0, 260.0), 0.0)),
            combatContract = CombatContract(12345, -1,
                mapOf("a" to 8L, "f" to 10L, "b" to 9L, "t" to 11L, "u" to 12L, "g" to 13L), 14L))
    }

    private fun loadProduction(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val file = root.resolve("production.properties")
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) &&
            file.toRealPath() == root.toRealPath().resolve("production.properties")) { "$file: expected ordinary in-root file" }
        require(Files.readString(file).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.production-sandbox.v1\nvariant=production") { "$file: unsupported fixed production fixture" }
        val map = when (val result = ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val tank = loadTank(root, content)
        checkCancellation()
        val factory = readFactory(root, content)
        return Scenario("p005-production", map, mapOf(tank.id to tank, factory.id to factory),
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", tank.id, Position(110.0, 210.0), 90.0),
                UnitSpawn("f", "p1", factory.id, Position(310.0, 210.0), 0.0),
                UnitSpawn("b", "p2", tank.id, Position(430.0, 210.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "f" to 10L, "b" to 9L), 11L),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L)))
    }

    /** Explicit fixed P005 fixture; arbitrary original unit/mod files are not a scene format. */
    private fun loadCombat(root: Path, content: MutableList<LoadedUnitContent>): Scenario {
        val file = root.resolve("combat.properties")
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) &&
            file.toRealPath() == root.toRealPath().resolve("combat.properties")) { "$file: expected ordinary in-root file" }
        val text = Files.readString(file).trim()
        require(text == "format=rw-reforge.combat-sandbox.v1") { "$file: unsupported fixed combat fixture" }
        val map = when (val result = ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val tank = loadTank(root, content)
        return Scenario("p005-combat", map, mapOf("c_tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(350.0, 210.0), 270.0),
                UnitSpawn("c", "p2", "c_tank", Position(510.0, 330.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
    }

    private fun loadTank(root: Path, content: MutableList<LoadedUnitContent>): UnitDefinition {
        checkCancellation()
        return when (val result = ContentLoader.loadCombatUnitWithSources(tankPath(root))) {
            is ContentResult.Success -> result.value.also { content += it }.definition
            is ContentResult.Failure -> error(result.error.describe())
        }
    }

    private fun tracked(result: ContentResult<LoadedUnitContent>, content: MutableList<LoadedUnitContent>): com.rustedwarfare.reforge.core.content.UnitDefinition {
        checkCancellation()
        return when (result) {
            is ContentResult.Success -> { content += result.value; result.value.definition }
            is ContentResult.Failure -> error(result.error.describe())
        }
    }

    private fun readFactory(root: Path, content: MutableList<LoadedUnitContent>) =
        tracked(ContentLoader.loadFactoryWithSources(unitPath(root, "landFactory", "properties")), content)

    private fun readCenter(root: Path, content: MutableList<LoadedUnitContent>) =
        tracked(ContentLoader.loadCommandCenterWithSources(unitPath(root, "commandCenter", "properties")), content)

    private fun readPointDefense(root: Path, content: MutableList<LoadedUnitContent>, construction: Boolean = false) =
        tracked(ContentLoader.loadPointDefenseWithSources(unitPath(root, "laserDefence", "properties"), construction), content)

    private fun readTurret(root: Path, stem: String, content: MutableList<LoadedUnitContent>,
                           upgrades: Boolean = false, construction: Boolean = false) =
        tracked(ContentLoader.loadTurretUnitWithSources(unitPath(root, stem, "ini"), upgrades, construction), content)

    private fun tankPath(root: Path): Path = unitPath(root, "tank", "ini")

    private fun unitPath(root: Path, stem: String, legacyExtension: String): Path {
        val directory = root.resolve("units").toAbsolutePath().normalize()
        val names = listOf(legacyExtension, "ini", "json", "hjson").distinct().map { "$stem.$it" }
        val candidates = names.map(directory::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
        if (candidates.size != 1) error(ContentError(
            if (candidates.isEmpty()) ContentErrorCode.BAD_REFERENCE else ContentErrorCode.IDENTITY_CONFLICT,
            directory.toString(), field = stem, objectRef = candidates.joinToString { it.fileName.toString() },
            reason = "Expected exactly one author file: ${names.joinToString()}; found ${candidates.size}",
        ).describe())
        return candidates.single()
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Sandbox content load cancelled")
    }

    private fun readManifest(root: Path): Properties? {
        val file = root.resolve("sandbox.properties")
        if (Files.notExists(file, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) &&
            file.toRealPath() == root.toRealPath().resolve("sandbox.properties")) { "$file: expected ordinary file inside input root" }
        val properties = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                require(!containsKey(key)) { "$file:$key: duplicate field" }
                return super.put(key, value)
            }
        }
        Files.newBufferedReader(file).use { properties.load(it) }
        val fields = setOf("format") + (1..3).flatMap { index -> listOf("team", "x", "y", "heading").map { "p$index.$it" } }
        require(properties.stringPropertyNames() == fields) { "$file: expected exactly $fields" }
        require(properties.getProperty("format") == "rw-reforge.sandbox.v1") { "$file:format: unsupported format" }
        for (index in 1..3) {
            val team = properties.getProperty("p$index.team")
            require(team.isNotBlank() && team == team.trim()) { "$file:p$index.team: expected nonblank team without outer whitespace" }
            for (field in listOf("x", "y", "heading")) {
                require(properties.getProperty("p$index.$field").toDoubleOrNull()?.isFinite() == true) {
                    "$file:p$index.$field: expected finite number"
                }
            }
        }
        return properties
    }
}

internal class PreparedControlledScenario(val scenario: Scenario, content: List<LoadedUnitContent>) {
    private val loads = content.toList()
    val sources: List<ContentSourceIdentity> = Collections.unmodifiableList(loads.flatMap { it.sources }.distinct())

    fun verifyStable() {
        for (loaded in loads) when (val result = loaded.verifyStable()) {
            is ContentResult.Success -> Unit
            is ContentResult.Failure -> error(result.error.describe())
        }
    }
}
