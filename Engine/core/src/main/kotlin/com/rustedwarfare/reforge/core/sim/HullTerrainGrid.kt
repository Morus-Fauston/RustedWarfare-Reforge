package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.floor

/** Tile geometry shared by hull checks over authoritative or player-authorized cells. */
internal class HullTerrainGrid private constructor(
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    private val blocked: ((Int, Int, Int, Int, (Int, Int) -> Boolean) -> Boolean)?,
) {
    val width = columns.toDouble() * tileWidth
    val height = rows.toDouble() * tileHeight

    fun anyBlocked(left: Double, right: Double, top: Double, bottom: Double,
                   predicate: (Int, Int) -> Boolean): Boolean =
        blocked?.invoke(cell(left, tileWidth, columns), cell(right, tileWidth, columns),
            cell(top, tileHeight, rows), cell(bottom, tileHeight, rows), predicate) == true

    private fun cell(coordinate: Double, size: Int, count: Int): Int =
        floor(coordinate / size).toInt().coerceIn(0, count - 1)

    companion object {
        fun authoritative(map: MapDefinition, movement: MovementType) =
            HullTerrainGrid(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                if (movement == MovementType.AIR) null else map.blockingFor(movement)::anyBlocked)

        fun leavingProducer(collision: MapDefinition, ground: MapDefinition, movement: MovementType,
                            producerCells: Set<Pair<Int, Int>>): HullTerrainGrid {
            val all = collision.blockingFor(movement)
            val hardGround = ground.blockingFor(movement)
            return HullTerrainGrid(ground.widthTiles, ground.heightTiles, ground.tileWidth, ground.tileHeight) { left, right, top, bottom, hits ->
                all.anyBlocked(left, right, top, bottom) { x, y ->
                    ((x to y) !in producerCells || hardGround.isBlocked(x, y)) && hits(x, y)
                }
            }
        }

        fun authorized(terrain: PlanningTerrain) =
            HullTerrainGrid(terrain.widthTiles, terrain.heightTiles, terrain.tileWidth, terrain.tileHeight, terrain::anyBlocked)
    }
}
