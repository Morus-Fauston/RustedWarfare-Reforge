package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.sim.CoordinateModel
import com.rustedwarfare.reforge.core.sim.MovementContract
import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.PlayerControl
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario
import com.rustedwarfare.reforge.core.sim.TimeModel
import com.rustedwarfare.reforge.core.sim.UnitSpawn
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Self-authored demonstration content; these values are not original-game contracts. */
object DemoScenario {
    private data class Parsed(val map: MapDefinition, val tank: UnitDefinition)

    private val resources = listOf(
        "maps/valley.tmx", "tilesets/terrain.tsx", "units/scout_tank.ini",
        "images/terrain.png", "images/tank.png", "images/goal.png", "images/overview.png",
    )

    private val parsed: Parsed by lazy { extractAndParse() }

    fun load(): Scenario {
        val content = parsed
        val spawns = (0 until 50).map { index ->
            UnitSpawn(
                "demo-${index + 1}", "p1", "demo_tank",
                Position(90.0 + index % 5 * 20.0, 210.0 + index / 5 * 20.0), 0.0,
            )
        }
        return Scenario(
            "reforge-demo-valley", content.map, mapOf("demo_tank" to content.tank),
            listOf(Player("p1", PlayerControl.LOCAL)), Collections.unmodifiableList(spawns),
            CoordinateModel("demo.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("demo.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(4.0, 4.0),
        )
    }

    private fun extractAndParse(): Parsed {
        val root = Files.createTempDirectory("reforge-demo-").toAbsolutePath().normalize()
        val owned = arrayListOf(root)
        try {
            for (relative in resources) {
                val target = root.resolve(relative).normalize()
                check(target.startsWith(root)) { "Demo resource escapes its temporary directory: $relative" }
                if (Files.notExists(target.parent)) {
                    Files.createDirectory(target.parent)
                    owned.add(target.parent)
                }
                owned.add(target)
                val input = checkNotNull(DemoScenario::class.java.classLoader.getResourceAsStream("demo/$relative")) {
                    "Missing packaged demo resource: demo/$relative"
                }
                input.use { Files.copy(it, target) }
            }
            val map = loaded(ContentLoader.loadMap(root.resolve("maps/valley.tmx")))
            val tank = loaded(ContentLoader.loadUnitVariant(root.resolve("units/scout_tank.ini")))
            // Texture loading still needs these files after parsing and across scenario resets.
            owned.forEach { it.toFile().deleteOnExit() }
            return Parsed(map, tank)
        } catch (failure: Exception) {
            for (path in owned.asReversed()) {
                try { Files.deleteIfExists(path) }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    private fun <T> loaded(result: ContentResult<T>): T = when (result) {
        is ContentResult.Success -> result.value
        is ContentResult.Failure -> error(result.error.describe())
    }
}
