package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Properties

/** Fixed private sandbox fixtures, not a general scenario-file interpreter. */
object ControlledScenario {
    fun load(root: Path): Scenario {
        checkCancellation()
        if (Files.exists(root.resolve("construction.properties"), NOFOLLOW_LINKS)) return loadConstruction(root)
        if (Files.exists(root.resolve("patrol.properties"), NOFOLLOW_LINKS)) return loadPatrol(root)
        if (Files.exists(root.resolve("upgrade.properties"), NOFOLLOW_LINKS)) return loadUpgrade(root)
        if (Files.exists(root.resolve("point-defense.properties"), NOFOLLOW_LINKS)) return loadPointDefense(root)
        if (Files.exists(root.resolve("turret.properties"), NOFOLLOW_LINKS)) return loadTurret(root)
        if (Files.exists(root.resolve("production.properties"), NOFOLLOW_LINKS)) return loadProduction(root)
        if (Files.exists(root.resolve("combat.properties"), NOFOLLOW_LINKS)) return loadCombat(root)
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

    private fun loadConstruction(root: Path): Scenario {
        val marker = root.resolve("construction.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("construction.properties")) { "$marker: expected ordinary in-root file" }
        val defense = when (Files.readString(marker).trim().replace("\r\n", "\n")) {
            "format=rw-reforge.construction-sandbox.v1\nvariant=command-center-start" -> false
            "format=rw-reforge.construction-sandbox.v1\nvariant=command-center-defense" -> true
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
        val tank = read(ContentLoader.loadCombatUnit(root.resolve("units/tank.ini")))
        val builder = read(ContentLoader.loadBuilder(root.resolve("units/builder.properties")))
        val center = read(ContentLoader.loadCommandCenter(root.resolve("units/commandCenter.properties")))
        val enemies = if (defense) (1..12).map { index ->
            UnitSpawn("z" + index.toString().padStart(2, '0'), "p2", tank.id,
                Position(190.0 + ((index - 1) / 6) * 22.0, 150.0 + ((index - 1) % 6) * 22.0), 270.0)
        } else listOf(UnitSpawn("z1", "p2", tank.id, Position(510.0, 110.0), 270.0),
            UnitSpawn("z2", "p2", tank.id, Position(510.0, 330.0), 270.0))
        val spawns = listOf(UnitSpawn("h", "p1", center.id, Position(110.0, 210.0), 0.0)) + enemies
        return Scenario("p006-construction", map, listOf(tank, builder, center).associateBy { it.id },
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            spawns,
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1,
                spawns.mapIndexed { index, spawn -> spawn.unitId to 10L + index }.toMap(), 10L + spawns.size),
            economyContract = EconomyContract(mapOf("p1" to 1750L, "p2" to 0L)))
    }

    private fun loadPatrol(root: Path): Scenario {
        val marker = root.resolve("patrol.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("patrol.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.patrol-sandbox.v1\nvariant=patrol-return") { "$marker: unsupported fixed patrol fixture" }
        val base = loadProduction(root)
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

    private fun loadUpgrade(root: Path): Scenario {
        val marker = root.resolve("upgrade.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("upgrade.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.upgrade-sandbox.v1\nvariant=two-branches") { "$marker: unsupported fixed upgrade fixture" }
        val base = loadProduction(root)
        fun tower(name: String, enabled: Boolean = false) = when (val result =
            ContentLoader.loadTurretUnit(root.resolve("units/$name.ini"), enabled)) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
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

    private fun loadPointDefense(root: Path): Scenario {
        val marker = root.resolve("point-defense.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("point-defense.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.point-defense-sandbox.v1\nvariant=level-one") { "$marker: unsupported fixed defense fixture" }
        val base = loadProduction(root)
        checkCancellation()
        val defense = when (val result = ContentLoader.loadPointDefense(root.resolve("units/laserDefence.properties"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
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

    private fun loadTurret(root: Path): Scenario {
        val marker = root.resolve("turret.properties")
        require(Files.isRegularFile(marker, NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker) &&
            marker.toRealPath() == root.toRealPath().resolve("turret.properties")) { "$marker: expected ordinary in-root file" }
        require(Files.readString(marker).trim().replace("\r\n", "\n") ==
            "format=rw-reforge.turret-sandbox.v1\nvariant=basic-turret") { "$marker: unsupported fixed turret fixture" }
        val base = loadProduction(root)
        checkCancellation()
        val turret = when (val result = ContentLoader.loadTurretUnit(root.resolve("units/turret_t1.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        return base.copy(scenarioId = "p005-turret", unitDefinitions = base.unitDefinitions + (turret.id to turret),
            units = base.units + listOf(UnitSpawn("t", "p1", turret.id, Position(280.0, 150.0), 90.0),
                UnitSpawn("u", "p2", turret.id, Position(500.0, 100.0), 270.0),
                UnitSpawn("g", "p2", "landFactory", Position(430.0, 260.0), 0.0)),
            combatContract = CombatContract(12345, -1,
                mapOf("a" to 8L, "f" to 10L, "b" to 9L, "t" to 11L, "u" to 12L, "g" to 13L), 14L))
    }

    private fun loadProduction(root: Path): Scenario {
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
        val tank = when (val result = ContentLoader.loadCombatUnit(root.resolve("units/tank.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val factory = when (val result = ContentLoader.loadFactory(root.resolve("units/landFactory.properties"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
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
    private fun loadCombat(root: Path): Scenario {
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
        val tank = when (val result = ContentLoader.loadCombatUnit(root.resolve("units/tank.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        return Scenario("p005-combat", map, mapOf("c_tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.NONE, "two")),
            listOf(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0),
                UnitSpawn("b", "p2", "c_tank", Position(350.0, 210.0), 270.0),
                UnitSpawn("c", "p2", "c_tank", Position(510.0, 330.0), 270.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "b" to 9L, "c" to 10L), 11L))
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
