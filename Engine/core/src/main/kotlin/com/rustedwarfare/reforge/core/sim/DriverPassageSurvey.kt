package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import java.util.WeakHashMap
import kotlin.math.floor

/** The supplemental driver-width rule handles straight single-file throats. Each tile read
 * costs one unit. A warm answer and an unfinished query belong to one authorized terrain. */
internal class DriverPassageSurvey private constructor(
    private val maps: MutableMap<PlanningTerrain, MutableMap<Query, Progress>>,
    private val undo: PathSearchRollback?,
) {
    constructor() : this(WeakHashMap(), null)
    var shortOfBudget = false
        private set
    private fun unavailable(): NarrowPassage? { shortOfBudget = true; return null }
    private data class Query(val x: Int, val y: Int, val width: Double)
    private class Progress(val scan: Scan, var paid: Int = 0)
    fun fork(rollback: PathSearchRollback) = DriverPassageSurvey(maps, rollback)

    fun at(terrain: PlanningTerrain, point: Position, width: Double, budget: PathWorkBudget): NarrowPassage? {
        val x = floor(point.x / terrain.tileWidth).toInt()
        val y = floor(point.y / terrain.tileHeight).toInt()
        return at(terrain, x, y, width, budget)
    }

    fun at(terrain: PlanningTerrain, x: Int, y: Int, width: Double, budget: PathWorkBudget): NarrowPassage? {
        if (x !in 0 until terrain.widthTiles || y !in 0 until terrain.heightTiles) return null
        val key = Query(x, y, width)
        val map = maps[terrain] ?: run {
            if (budget.exhausted) return unavailable()
            hashMapOf<Query, Progress>().also { maps[terrain] = it; undo?.record { maps.remove(terrain) } }
        }
        val cursor = map[key] ?: run {
            if (budget.exhausted) return unavailable()
            Progress(Scan(key)).also { map[key] = it; undo?.record { map.remove(key) } }
        }
        val scan = cursor.scan
        val old = cursor.paid
        while ((!scan.finished || cursor.paid < scan.produced) && budget.take(1)) {
            if (cursor.paid == scan.produced) scan.next(terrain)
            cursor.paid++
        }
        // Publish each cell under the same terrain and width, with one unit of
        // work per index entry. The cursor and all index changes roll back together.
        if (scan.finished) while (cursor.paid >= scan.produced && cursor.paid - scan.produced < scan.throat.size && budget.take(1)) {
            val cell = scan.throat[cursor.paid - scan.produced]
            val alias = Query(cell.column, cell.row, width)
            val previous = map.put(alias, cursor)
            if (previous !== cursor) undo?.record { if (previous == null) map.remove(alias) else map[alias] = previous }
            cursor.paid++
        }
        if (old != cursor.paid) undo?.record { cursor.paid = old }
        return if (scan.finished && cursor.paid == scan.produced + scan.throat.size) scan.result else unavailable()
    }

    /** Pure work can survive a discarded step. The journaled cursor pays again before the
     * answer can affect gameplay, including on a different retry of the same failed step. */
    private class Scan(val query: Query) {
        // Borrow the map key only while advancing. A suspended iterator may outlive its terrain.
        private var activeTerrain: PlanningTerrain? = null
        private val terrain: PlanningTerrain get() = checkNotNull(activeTerrain)
        var throat: List<PassageCell> = emptyList()
        var produced = 0
        var finished = false
        var result: NarrowPassage? = null
        private var iterator: Iterator<Unit>? = sequence {
            for (horizontal in listOf(true, false)) {
                val along = if (horizontal) query.x else query.y
                val cross = if (horizontal) query.y else query.x
                val pitch = if (horizontal) terrain.tileHeight else terrain.tileWidth
                suspend fun SequenceScope<Unit>.open(a: Int, b: Int): Boolean {
                    yield(Unit)
                    val x = if (horizontal) a else b
                    val y = if (horizontal) b else a
                    return x in 0 until terrain.widthTiles && y in 0 until terrain.heightTiles && !terrain.isBlocked(x, y)
                }
                if (!open(along, cross)) break
                var lo = cross
                var hi = cross
                while ((hi - lo + 1.0) * pitch < query.width && open(along, lo - 1)) lo--
                while ((hi - lo + 1.0) * pitch < query.width && open(along, hi + 1)) hi++
                val clearance = (hi - lo + 1.0) * pitch
                if (clearance >= query.width) continue
                suspend fun SequenceScope<Unit>.mouth(direction: Int): Int? {
                    var station = along + direction
                    while (true) {
                        for (b in lo..hi) if (!open(station, b)) return null
                        if (open(station, lo - 1) || open(station, hi + 1)) return station
                        station += direction
                    }
                }
                val first = (mouth(-1) ?: continue) + 1
                val end = mouth(1) ?: continue
                val cells = linkedSetOf<PassageCell>()
                val index = mutableListOf<PassageCell>()
                var cellHash = 0
                for (a in first until end) for (b in lo..hi) {
                    yield(Unit)
                    val cell = if (horizontal) PassageCell(a, b) else PassageCell(b, a)
                    cells.add(cell)
                    index.add(cell)
                    cellHash += cell.hashCode()
                }
                val size = if (horizontal) terrain.tileWidth else terrain.tileHeight
                fun opening(outward: Int, station: Int) = PassageOpening(horizontal, outward, station.toDouble() * size,
                    lo.toDouble() * pitch, (hi + 1.0) * pitch)
                throat = index
                result = NarrowPassage(cells, listOf(opening(-1, first), opening(1, end)), clearance,
                    terrain.tileWidth, terrain.tileHeight, cellHash)
                break
            }
        }.iterator()

        fun next(snapshot: PlanningTerrain) {
            val work = checkNotNull(iterator)
            activeTerrain = snapshot
            try {
                if (work.hasNext()) work.next() else { finished = true; iterator = null }
                produced++
            } finally {
                activeTerrain = null
            }
        }
    }
}
