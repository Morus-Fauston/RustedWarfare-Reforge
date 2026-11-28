package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.MapDefinition

/** Immutable cell history, refreshed at the accepted world boundary rather than at query time. */
internal class MissileTargetIndex private constructor(private val inverseX: Float, private val inverseY: Float,
    private val cells: Map<Int, List<String>>, private val membership: Map<String, Int>) {
    companion object {
        fun initial(map: MapDefinition, units: Collection<MutableUnit>): MissileTargetIndex {
            val width = (map.widthTiles.toLong() * map.tileWidth / 32).toFloat()
            val height = (map.heightTiles.toLong() * map.tileHeight / 32).toFloat()
            return MissileTargetIndex(1f / width, 1f / height, emptyMap(), emptyMap()).refresh(units)
        }
    }
    private fun column(x: Float) = (x * inverseX).toInt().coerceIn(0, 31)
    private fun row(y: Float) = (y * inverseY).toInt().coerceIn(0, 31)
    private fun cell(unit: MutableUnit) = column(unit.position.x.toFloat()) * 32 + row(unit.position.y.toFloat())

    fun remove(id: String): MissileTargetIndex {
        val old = membership[id] ?: return this
        return MissileTargetIndex(inverseX, inverseY, cells + (old to cells.getValue(old).filter { it != id }), membership - id)
    }

    fun refresh(units: Collection<MutableUnit>): MissileTargetIndex {
        var nextCells: MutableMap<Int, List<String>>? = null
        var nextMembership: MutableMap<String, Int>? = null
        for (unit in units) {
            val uid = unit.unitId
            val oldCell = (nextMembership ?: membership)[uid]
            if (!unit.alive) {
                if (oldCell != null) {
                    val mCells = nextCells ?: HashMap(cells).also { nextCells = it }
                    val mMem = nextMembership ?: HashMap(membership).also { nextMembership = it }
                    mCells[oldCell] = mCells.getValue(oldCell).filter { it != uid }
                    mMem.remove(uid)
                }
                continue
            }
            val target = cell(unit)
            if (oldCell == target) continue
            val mCells = nextCells ?: HashMap(cells).also { nextCells = it }
            val mMem = nextMembership ?: HashMap(membership).also { nextMembership = it }
            if (oldCell != null) {
                mCells[oldCell] = mCells.getValue(oldCell).filter { it != uid }
            }
            mCells[target] = mCells[target].orEmpty() + uid
            mMem[uid] = target
        }
        return if (nextCells == null) this else MissileTargetIndex(inverseX, inverseY, nextCells, checkNotNull(nextMembership))
    }

    fun candidates(x: Float, y: Float, radius: Float, units: Map<String, MutableUnit>): List<MutableUnit> {
        val left = x - radius; val right = x + radius; val top = y - radius; val bottom = y + radius
        require(listOf(left, right, top, bottom).all { it.isFinite() }) { "Missile search geometry must remain finite" }
        val result = mutableListOf<MutableUnit>()
        for (column in column(right) downTo column(left)) for (row in row(bottom) downTo row(top)) {
            for (id in cells[column * 32 + row].orEmpty().asReversed()) {
                val unit = units.getValue(id)
                val ux = unit.position.x.toFloat(); val uy = unit.position.y.toFloat()
                if (ux >= left && ux <= right && uy >= top && uy <= bottom) result += unit
            }
        }
        return result
    }

    /** Ordinary repair callbacks traverse columns, rows and cell membership forwards. */
    fun repairCandidates(x: Float, y: Float, radius: Float, units: Map<String, MutableUnit>): List<MutableUnit> {
        val left = x - radius; val right = x + radius; val top = y - radius; val bottom = y + radius
        require(listOf(left, right, top, bottom).all { it.isFinite() }) { "Repair search geometry must remain finite" }
        val result = mutableListOf<MutableUnit>()
        for (column in column(left)..column(right)) for (row in row(top)..row(bottom)) {
            for (id in cells[column * 32 + row].orEmpty()) {
                val unit = units.getValue(id)
                val ux = unit.position.x.toFloat(); val uy = unit.position.y.toFloat()
                if (ux >= left && ux <= right && uy >= top && uy <= bottom) result += unit
            }
        }
        return result
    }

    fun validate(units: Map<String, MutableUnit>) {
        var totalInCells = 0
        for ((cellIdx, ids) in cells) {
            for (i in 0 until ids.size) {
                val id = ids[i]
                require(membership[id] == cellIdx)
                totalInCells++
            }
        }
        require(totalInCells == membership.size)
        var aliveCount = 0
        for (u in units.values) {
            if (!u.alive) continue
            aliveCount++
            val idx = membership[u.unitId]
            require(idx != null && cell(u) == idx)
        }
        require(aliveCount == membership.size)
    }
}
