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
        val map = when (val result = ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        val tank = when (val result = ContentLoader.loadUnitVariant(root.resolve("units/weaponless_tank.ini"))) {
            is ContentResult.Success -> result.value
            is ContentResult.Failure -> error(result.error.describe())
        }
        val manifest = readManifest(root)
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
