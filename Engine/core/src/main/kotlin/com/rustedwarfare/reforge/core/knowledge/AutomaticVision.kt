package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.sim.Player
import com.rustedwarfare.reforge.core.sim.Position
import java.util.Collections
import kotlin.math.ceil
import kotlin.math.floor

data class VisionCell(val column: Int, val row: Int)

class VisionSnapshot private constructor(
    val worldId: String, val playerId: String, val epoch: Long,
    val basicCells: List<VisionCell>, val effectiveCells: List<VisionCell>,
    private val area: VisionArea?,
    private val legacy: Set<Long>?,
) {
    internal constructor(worldId: String, playerId: String, epoch: Long,
                         basicCells: List<VisionCell>, effectiveCells: List<VisionCell>) : this(
        worldId, playerId, epoch, Collections.unmodifiableList(ArrayList(basicCells)),
        Collections.unmodifiableList(ArrayList(effectiveCells)), null, legacyIndex(effectiveCells))

    internal fun contains(column: Int, row: Int): Boolean = area?.contains(column, row)
        ?: (cellKey(column, row) in checkNotNull(legacy))

    internal companion object {
        private fun cellKey(column: Int, row: Int): Long = (row.toLong() shl 32) or (column.toLong() and 0xffffffffL)
        private fun legacyIndex(cells: List<VisionCell>): Set<Long> = cells.mapTo(HashSet()) { cellKey(it.column, it.row) }
        fun owned(worldId: String, playerId: String, epoch: Long, basic: VisionArea, effective: VisionArea) =
            VisionSnapshot(worldId, playerId, epoch, basic.cells, effective.cells, effective, null)
    }
}

internal data class VisionSource(val owner: String, val position: Position, val radius: Int)
internal data class VisionGeometry(val worldId: String, val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int)
internal data class VisionOwner(val coverage: VisionCoverage, val area: VisionArea)
internal class AutomaticVisionState(val geometry: VisionGeometry, players: List<Player>, sources: List<VisionSource>,
    val sharing: Boolean, val empty: VisionOwner, basic: Map<String, VisionOwner>, effective: Map<List<String>, VisionOwner>) {
    val players: List<Player> = Collections.unmodifiableList(ArrayList(players))
    val sources: List<VisionSource> = Collections.unmodifiableList(ArrayList(sources))
    val basic: Map<String, VisionOwner> = Collections.unmodifiableMap(LinkedHashMap(basic))
    val effective: Map<List<String>, VisionOwner> = Collections.unmodifiableMap(effective.entries.associateTo(linkedMapOf()) {
        Collections.unmodifiableList(ArrayList(it.key)) to it.value })
    val audiences: Map<String, VisionOwner> = Collections.unmodifiableMap(linkedMapOf<String, VisionOwner>().also { result ->
        for ((members, value) in this.effective) for (member in members) result[member] = value
    })
}
internal data class VisionProjection(val state: AutomaticVisionState, val views: Map<String, VisionSnapshot>)

internal object AutomaticVision {
    fun compute(worldId: String, epoch: Long, width: Int, height: Int, tileWidth: Int, tileHeight: Int,
                players: List<Player>, sources: List<VisionSource>, sharing: Boolean): Map<String, VisionSnapshot> =
        project(null, worldId, epoch, width, height, tileWidth, tileHeight, players, sources, sharing).views

    fun project(previous: AutomaticVisionState?, worldId: String, epoch: Long, width: Int, height: Int,
                tileWidth: Int, tileHeight: Int, players: List<Player>, sources: List<VisionSource>, sharing: Boolean): VisionProjection {
        val geometry = VisionGeometry(worldId, width, height, tileWidth, tileHeight)
        val positive = sources.filter { it.radius > 0 }
        if (previous != null && previous.geometry == geometry && previous.players == players &&
            previous.sources == positive && previous.sharing == sharing) return expose(previous, epoch)
        val prior = previous?.takeIf { it.geometry == geometry }
        val empty = prior?.empty ?: VisionCoverage.empty(width * height).let {
            VisionOwner(it, VisionArea.empty(width, height, it)) }
        val emptyCoverage = empty.coverage
        val before = prior?.sources?.groupingBy { it }?.eachCount() ?: emptyMap()
        val after = positive.groupingBy { it }.eachCount()
        val deltas = linkedMapOf<VisionSource, Int>()
        for ((source, count) in before) if (count > (after[source] ?: 0)) deltas[source] = (after[source] ?: 0) - count
        for ((source, count) in after) if (count > (before[source] ?: 0)) deltas[source] = count - (before[source] ?: 0)
        val ids = players.associateBy { it.playerId }
        for (source in positive) ids.getValue(source.owner)
        val basic = linkedMapOf<String, VisionOwner>()
        fun changed(old: VisionOwner, contributions: Map<VisionSource, Int>): VisionOwner {
            if (contributions.isEmpty()) return old
            val update = old.coverage.edit { editor ->
                for ((source, count) in contributions) visit(source, geometry) { editor.add(it, count) }
            }
            return VisionOwner(update.coverage, old.area.updated(update))
        }
        for (player in players) basic[player.playerId] = changed(prior?.basic?.get(player.playerId) ?: empty,
            deltas.filterKeys { it.owner == player.playerId })
        val effective = linkedMapOf<List<String>, VisionOwner>()
        for (members in groups(players, sharing)) {
            effective[members] = if (members.size == 1) basic.getValue(members.single()) else {
                val old = prior?.effective?.get(members)
                if (old != null) changed(old, deltas.filterKeys { it.owner in members }) else {
                    val update = emptyCoverage.edit { editor ->
                        for (member in members) {
                            val own = basic.getValue(member)
                            for (cell in own.area.cells) {
                                val index = cell.row * width + cell.column
                                editor.add(index, own.coverage[index])
                            }
                        }
                    }
                    VisionOwner(update.coverage, empty.area.updated(update))
                }
            }
        }
        return expose(AutomaticVisionState(geometry, players, positive, sharing, empty, basic, effective), epoch)
    }

    private fun groups(players: List<Player>, sharing: Boolean): List<List<String>> = players.groupBy {
        if (sharing && it.teamId != null) "team:${it.teamId}" else "player:${it.playerId}"
    }.values.map { group -> group.map { it.playerId }.sorted() }

    private fun expose(state: AutomaticVisionState, epoch: Long): VisionProjection {
        val views = state.players.associate { player -> player.playerId to VisionSnapshot.owned(state.geometry.worldId,
            player.playerId, epoch, state.basic.getValue(player.playerId).area, state.audiences.getValue(player.playerId).area) }
        return VisionProjection(state, Collections.unmodifiableMap(views))
    }

    // Keep the existing tile-normalized Double geometry and clipping order exactly.
    private inline fun visit(source: VisionSource, geometry: VisionGeometry, consume: (Int) -> Unit) {
        val width = geometry.width; val height = geometry.height
        val x = source.position.x / geometry.tileWidth
        val y = source.position.y / geometry.tileHeight
        val radius = source.radius.toDouble()
        val left = floor(x - radius - 0.5).coerceIn(0.0, (width - 1).toDouble()).toInt()
        val right = ceil(x + radius - 0.5).coerceIn(0.0, (width - 1).toDouble()).toInt()
        val top = floor(y - radius - 0.5).coerceIn(0.0, (height - 1).toDouble()).toInt()
        val bottom = ceil(y + radius - 0.5).coerceIn(0.0, (height - 1).toDouble()).toInt()
        for (row in top..bottom) for (column in left..right) {
            val dx = column + 0.5 - x
            val dy = row + 0.5 - y
            if (dx * dx + dy * dy <= radius * radius) consume(row * width + column)
        }
    }
}
