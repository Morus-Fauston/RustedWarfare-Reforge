package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor

data class VisionCell(val column: Int, val row: Int)

class VisionSnapshot internal constructor(
    val worldId: String,
    val playerId: String,
    val epoch: Long,
    basicCells: List<VisionCell>,
    effectiveCells: List<VisionCell>,
) {
    val basicCells: List<VisionCell> = Collections.unmodifiableList(ArrayList(basicCells))
    val effectiveCells: List<VisionCell> = Collections.unmodifiableList(ArrayList(effectiveCells))
    private val effectiveIndex = this.effectiveCells.mapTo(HashSet()) { cellKey(it.column, it.row) }

    internal fun contains(column: Int, row: Int): Boolean = cellKey(column, row) in effectiveIndex

    private fun cellKey(column: Int, row: Int): Long = (row.toLong() shl 32) or (column.toLong() and 0xffffffffL)
}

internal data class VisionSource(val owner: String, val position: Position, val radius: Int)

internal object AutomaticVision {
    fun compute(worldId: String, epoch: Long, width: Int, height: Int, tileWidth: Int, tileHeight: Int,
                players: List<Player>, sources: List<VisionSource>, sharing: Boolean): Map<String, VisionSnapshot> {
        val basic = players.associate { it.playerId to BooleanArray(width * height) }
        for (source in sources) {
            if (source.radius <= 0) continue
            val cells = basic.getValue(source.owner)
            val x = source.position.x / tileWidth
            val y = source.position.y / tileHeight
            val radius = source.radius.toDouble()
            val left = floor(x - radius - 0.5).coerceIn(0.0, (width - 1).toDouble()).toInt()
            val right = ceil(x + radius - 0.5).coerceIn(0.0, (width - 1).toDouble()).toInt()
            val top = floor(y - radius - 0.5).coerceIn(0.0, (height - 1).toDouble()).toInt()
            val bottom = ceil(y + radius - 0.5).coerceIn(0.0, (height - 1).toDouble()).toInt()
            for (row in top..bottom) for (column in left..right) {
                val dx = column + 0.5 - x
                val dy = row + 0.5 - y
                if (dx * dx + dy * dy <= radius * radius) cells[row * width + column] = true
            }
        }
        fun values(cells: BooleanArray): List<VisionCell> = cells.indices.filter { cells[it] }
            .map { VisionCell(it % width, it / width) }
        return players.associate { player ->
            val effective = basic.getValue(player.playerId).copyOf()
            if (sharing && player.teamId != null) for (ally in players) {
                if (ally.teamId == player.teamId) {
                    val contribution = basic.getValue(ally.playerId)
                    for (index in effective.indices) effective[index] = effective[index] || contribution[index]
                }
            }
            player.playerId to VisionSnapshot(worldId, player.playerId, epoch,
                values(basic.getValue(player.playerId)), values(effective))
        }
    }
}
