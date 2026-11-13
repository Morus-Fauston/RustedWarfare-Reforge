package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.math.sin

/** Height remains part of the candidate unit; failure cannot publish a partial pose. */
internal object HoverMotion {
    /** Native land-base death branch; hover bobbing stops at death. */
    fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        if (unit.definition.movement.hover == null) return
        val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
        val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
        val target = if (map.isWater(column, row)) -10f else 0f
        var height = unit.height.toFloat()
        if (height <= target) return
        val elapsed = delta.toFloat()
        if (height > 0f && unit.sinkSpeed < .4f) unit.sinkSpeed = .4f
        unit.sinkSpeed += .002f * elapsed
        height = maxOf(target, height - unit.sinkSpeed * elapsed)
        check(height.isFinite() && unit.sinkSpeed.isFinite()) { "Non-finite hover corpse for ${unit.unitId}" }
        unit.height = height.toDouble()
    }

    fun advancePose(unit: MutableUnit, delta: Double) {
        val profile = unit.definition.movement.hover ?: return
        unit.heightPhase += profile.phaseSpeed * delta
        if (unit.heightPhase > 360.0) unit.heightPhase -= 360.0
        val desired = profile.targetHeight + sin(Math.toRadians(unit.heightPhase)) * profile.heightDrift
        val limit = profile.heightChangeRate * delta
        check(listOf(unit.heightPhase, desired, limit, unit.height - desired).all { it.isFinite() }) {
            "Non-finite hover pose for ${unit.unitId}"
        }
        unit.height = approach(unit.height, desired, limit)
        check(unit.height.isFinite()) { "Non-finite hover height for ${unit.unitId}" }
    }
}
