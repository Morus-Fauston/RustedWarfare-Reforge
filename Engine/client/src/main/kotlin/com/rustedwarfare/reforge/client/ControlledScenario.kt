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

    private fun loadTurret(root: Path): Scenario {
        val file = root.resolve("turret.properties")
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file) &&
            file.toRealPath() == root.toRealPath().resolve("turret.properties")) { "$file: expected ordinary in-root file" }
        require(Files.readAllLines(file) == listOf("format=rw-reforge.turret-sandbox.v1", "variant=basic-turret")) {
            "$file: unsupported fixed turret fixture"
        }
        checkCancellation()
        val base = loadProduction(root)
        checkCancellation()
        val tower = when (val result = ContentLoader.loadTurretUnit(root.resolve("units/turret_t1.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        checkCancellation()
        val combat = checkNotNull(base.combatContract)
        return base.copy(scenarioId = "p005-turret", unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = base.units + listOf(UnitSpawn("t", "p1", tower.id, Position(280.0, 100.0), 90.0),
                UnitSpawn("u", "p2", tower.id, Position(500.0, 100.0), 270.0),
                UnitSpawn("g", "p2", "landFactory", Position(430.0, 100.0), 0.0)),
            combatContract = combat.copy(
                unitObjectIds = combat.unitObjectIds + mapOf("t" to 11L, "u" to 12L, "g" to 13L), nextObjectId = 14L))
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
