package com.rustedwarfare.reforge.core.sim

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
        var next = this
        for (unit in units) {
            if (!unit.alive) { next = next.remove(unit.unitId); continue }
            val target = cell(unit)
            if (next.membership[unit.unitId] == target) continue
            next = next.remove(unit.unitId)
            next = MissileTargetIndex(inverseX, inverseY,
                next.cells + (target to (next.cells[target].orEmpty() + unit.unitId)), next.membership + (unit.unitId to target))
        }
        return next
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

    fun validate(units: Map<String, MutableUnit>) {
        val all = cells.values.flatten()
        require(all.size == all.toSet().size && all.toSet() == membership.keys)
        for ((id, index) in membership) require(units.getValue(id).alive && cell(units.getValue(id)) == index && id in cells.getValue(index))
        require(units.values.filter { it.alive }.map { it.unitId }.toSet() == membership.keys)
    }
}
