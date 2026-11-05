package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType

/** Finite custom battleship corpse branch, separate from the native water-unit base. */
internal object WaterBody {
    fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        if (unit.definition.id != "heavyBattleship" || unit.definition.movement.type != MovementType.WATER || unit.waterSettled) return
        val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
        val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
        if (!map.isWater(column, row) || unit.height <= -10.0) {
            unit.waterSettled = true
            return
        }
        val elapsed = delta.toFloat()
        unit.sinkSpeed += .0008f * elapsed
        unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
    }
}
