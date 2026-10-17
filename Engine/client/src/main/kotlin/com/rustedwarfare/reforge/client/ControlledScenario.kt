package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.sim.*
import java.nio.file.Path

/** Local P001 fixture only; this is not a general scenario-file interpreter. */
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
        return Scenario(
            "p001-windows", map, mapOf("tank" to tank),
            listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0),
                UnitSpawn("u2", "p2", "tank", Position(510.0, 310.0), 0.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            MovementContract(16.0, 4.0),
        )
    }
}
