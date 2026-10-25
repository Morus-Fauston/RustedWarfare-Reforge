package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TileSetDefinition
import com.rustedwarfare.reforge.core.sim.Scenario
import com.badlogic.gdx.graphics.Color
import com.rustedwarfare.reforge.core.content.TurretMount
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.UnitObservation
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import java.nio.file.Path
import java.util.Collections
import kotlin.math.cos
import kotlin.math.sin

data class UnitAppearance(val displayRadius: Double, val imagePath: Path, val imageScale: Double, val totalFrames: Int,
    val turretImagePath: Path? = null, val deadImagePath: Path? = null, val turretImageScale: Double = 1.0,
    val backImagePath: Path? = null, val turretMount: TurretMount = TurretMount(),
    val defenseMuzzleOffset: Position? = null) {
    fun turretPosition(unit: UnitObservation): Position {
        val forward = Math.toRadians(unit.heading)
        val side = Math.toRadians(unit.heading + 90.0)
        return Position(unit.position.x + sin(forward) * turretMount.localY + sin(side) * turretMount.localX,
            unit.position.y - cos(forward) * turretMount.localY - cos(side) * turretMount.localX)
    }
}

internal data class DefenseBeam(val start: Position, val end: Position)

class SandboxRenderResources private constructor(
    val widthTiles: Int, val heightTiles: Int, val tileWidth: Int, val tileHeight: Int,
    val tileSets: List<TileSetDefinition>, val units: Map<String, UnitAppearance>,
    private val playerTeams: Map<String, String?>,
) {
    /** Both endpoints come from the authorized player view and immutable appearance metadata. */
    internal fun defenseBeams(observation: PlayerObservation): List<DefenseBeam> = observation.projectileEvents.mapNotNull { event ->
        if (event.kind != "deflected") return@mapNotNull null
        val tower = observation.units.find { it.unitId == event.interceptorUnitId } ?: return@mapNotNull null
        val offset = units[tower.typeId]?.defenseMuzzleOffset ?: return@mapNotNull null
        DefenseBeam(Position(tower.position.x + offset.x, tower.position.y + offset.y), event.position)
    }

    fun unitCircleColor(viewerPlayerId: String, ownerPlayerId: String): Color {
        require(viewerPlayerId in playerTeams && ownerPlayerId in playerTeams)
        val viewerTeam = playerTeams[viewerPlayerId]
        return when {
            ownerPlayerId == viewerPlayerId -> Color(0.45f, 0.91f, 0.65f, 1f)
            viewerTeam != null && viewerTeam == playerTeams[ownerPlayerId] -> Color(1f, 0.72f, 0.3f, 1f)
            else -> Color(1f, 0f, 0f, 1f)
        }
    }
    companion object {
        fun from(scenario: Scenario): SandboxRenderResources = SandboxRenderResources(
            scenario.map.widthTiles, scenario.map.heightTiles, scenario.map.tileWidth, scenario.map.tileHeight,
            Collections.unmodifiableList(ArrayList(scenario.map.tileSets)),
            Collections.unmodifiableMap(scenario.unitDefinitions.mapValues { (_, unit) ->
                UnitAppearance(unit.displayRadius, unit.imagePath, unit.imageScale, unit.totalFrames,
                    unit.combat?.turretImagePath, unit.durability?.deadImagePath ?: unit.combat?.deadImagePath,
                    unit.combat?.turretImageScale ?: 1.0, unit.building?.backImagePath,
                    unit.combat?.mount ?: TurretMount(),
                    unit.pointDefense?.let { Position(it.muzzleOffsetX, it.muzzleOffsetY) })
            }), Collections.unmodifiableMap(scenario.players.associate { it.playerId to it.teamId }))
    }
}
