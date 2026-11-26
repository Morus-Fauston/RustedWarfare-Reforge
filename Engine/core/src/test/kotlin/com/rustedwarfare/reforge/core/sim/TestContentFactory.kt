package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementProfile
import java.nio.file.Path

internal object TestContentFactory {
    fun unitWith(
        source: UnitDefinition,
        radius: Double = source.radius,
        movement: MovementProfile = source.movement,
        movementCollision: MovementCollisionShape? = null,
    ): UnitDefinition = UnitDefinition(
        source.id, source.className, radius, source.displayRadius, source.totalFrames, source.imagePath,
        source.imageScale, movement, source.retainedUnconsumed,
        movementCollision = movementCollision,
        explicitMovementCollision = movementCollision != null || radius != source.radius || source.explicitMovementCollision,
    )

    fun openScenario(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir")))
        val map = (ContentLoader.loadMap(root.resolve("maps/p001-controlled.tmx")) as ContentResult.Success).value
        val unit = (ContentLoader.loadUnitVariant(root.resolve("units/weaponless_tank.ini")) as ContentResult.Success).value
        return Scenario(
            scenarioId = "p001-test",
            map = map,
            unitDefinitions = mapOf("tank" to unit),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 100.0), 0.0)),
            coordinateModel = CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            timeModel = TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"),
            movementContract = MovementContract(16.0, 4.0),
        )
    }
}

internal fun SimulationContext.advanceToMoveOutcome(firstStep: Long, unitId: String): Observation {
    for (step in firstStep until firstStep + 6000) {
        val frame = advance(Step(step, 1.0, "sim-tick"))
        if (frame.events.any { it.unitId == unitId && (it.kind == "movement-started" || it.kind == "path-unreachable") })
            return frame
    }
    error("No movement planning outcome for $unitId")
}
