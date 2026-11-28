package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.floor

/** Tile geometry shared by hull checks over authoritative or player-authorized cells. */
internal class HullTerrainGrid private constructor(
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    private val blocked: BlockedRangeQuery?,
    private val cellBlocked: CellBlockedQuery?,
) {
    private fun interface BlockedRangeQuery {
        fun anyBlocked(left: Int, right: Int, top: Int, bottom: Int, predicate: IntCellPredicate): Boolean
    }

    private fun interface CellBlockedQuery {
        fun isBlocked(col: Int, row: Int): Boolean
    }

    val width = columns.toDouble() * tileWidth
    val height = rows.toDouble() * tileHeight

    fun isCellBlocked(col: Int, row: Int): Boolean = cellBlocked?.isBlocked(col, row) == true

    fun anyBlocked(left: Double, right: Double, top: Double, bottom: Double,
                   predicate: (Int, Int) -> Boolean): Boolean =
        anyBlockedPrim(left, right, top, bottom) { x, y -> predicate(x, y) }

    fun anyBlockedPrim(left: Double, right: Double, top: Double, bottom: Double,
                       predicate: IntCellPredicate): Boolean =
        blocked?.anyBlocked(cell(left, tileWidth, columns), cell(right, tileWidth, columns),
            cell(top, tileHeight, rows), cell(bottom, tileHeight, rows), predicate) == true

    private fun cell(coordinate: Double, size: Int, count: Int): Int =
        floor(coordinate / size).toInt().coerceIn(0, count - 1)

    companion object {
        private class ThreadCache {
            var mapA: java.lang.ref.WeakReference<MapDefinition>? = null
            var gridsA: Array<HullTerrainGrid?>? = null
            var mapB: java.lang.ref.WeakReference<MapDefinition>? = null
            var gridsB: Array<HullTerrainGrid?>? = null
        }

        private val authCache = ThreadLocal.withInitial { ThreadCache() }

        fun authoritative(map: MapDefinition, movement: MovementType): HullTerrainGrid {
            val cache = authCache.get()
            val ord = movement.ordinal
            val grids = when {
                cache.mapA?.get() === map -> checkNotNull(cache.gridsA)
                cache.mapB?.get() === map -> checkNotNull(cache.gridsB)
                else -> {
                    val fresh = arrayOfNulls<HullTerrainGrid>(MovementType.entries.size)
                    cache.mapB = cache.mapA
                    cache.gridsB = cache.gridsA
                    cache.mapA = java.lang.ref.WeakReference(map)
                    cache.gridsA = fresh
                    fresh
                }
            }
            return grids[ord] ?: run {
                val grid = if (movement == MovementType.AIR) null else map.blockingFor(movement)
                HullTerrainGrid(
                    map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                    grid?.let { g -> BlockedRangeQuery { l, r, t, b, p -> g.anyBlockedPrim(l, r, t, b, p) } },
                    grid?.let { g -> CellBlockedQuery { c, r -> g.isBlocked(c, r) } },
                ).also { grids[ord] = it }
            }
        }

        fun leavingProducer(collision: MapDefinition, ground: MapDefinition, movement: MovementType,
                            producerCells: Set<Pair<Int, Int>>): HullTerrainGrid {
            val all = collision.blockingFor(movement)
            val hardGround = ground.blockingFor(movement)
            return HullTerrainGrid(
                ground.widthTiles, ground.heightTiles, ground.tileWidth, ground.tileHeight,
                BlockedRangeQuery { left, right, top, bottom, hits ->
                    all.anyBlockedPrim(left, right, top, bottom) { x, y ->
                        ((x to y) !in producerCells || hardGround.isBlocked(x, y)) && hits.test(x, y)
                    }
                },
                CellBlockedQuery { x, y ->
                    all.isBlocked(x, y) && ((x to y) !in producerCells || hardGround.isBlocked(x, y))
                },
            )
        }

        fun authorized(terrain: PlanningTerrain) =
            HullTerrainGrid(
                terrain.widthTiles, terrain.heightTiles, terrain.tileWidth, terrain.tileHeight,
                BlockedRangeQuery { l, r, t, b, p -> terrain.anyBlockedPrim(l, r, t, b, p) },
                CellBlockedQuery { c, r -> terrain.isBlocked(c, r) },
            )
    }
}
