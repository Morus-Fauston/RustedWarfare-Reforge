package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.navigation.UnitLocomotion
import com.rustedwarfare.reforge.core.navigation.VerticalPoseController

/**
 * Pure parameter-driven water corpse sinking controller (ENG-P017-006):
 * removes the `"heavyBattleship"` unit-ID whitelist in favor of [UnitDefinition.sinksInWaterOnDeath],
 * respects explicit opt-out (`sinksInWaterOnDeath = false`), prevents cross-medium submersibles
 * from sinking underground on land, and supports explicit water sinking for any non-WATER unit
 * without a dedicated corpse controller.
 */
internal object WaterBody : VerticalPoseController {
    override fun advancePose(
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        acceptsHeight: (Double) -> Boolean,
    ): String? = null

    override fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        if (!unit.definition.sinksInWaterOnDeath || unit.waterSettled) return
        val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
        val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
        val inWater = map.isWater(column, row)
        if (SubmarineMotion.isSubmersible(unit.definition)) {
            if (!inWater && unit.definition.movement.type != MovementType.WATER && unit.height >= UnitLocomotion.SUBMERGED_DEPTH) {
                unit.height = 0.0
                unit.waterSettled = true
                return
            }
            if (unit.height <= -10.0) {
                unit.height = -10.0
                unit.waterSettled = true
            } else {
                val elapsed = delta.toFloat()
                unit.sinkSpeed += .002f * elapsed
                unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
                require(unit.sinkSpeed.isFinite() && unit.height.isFinite()) { "Submarine corpse must remain finite" }
            }
            return
        }
        // Skip units owned by dedicated air/hover/amphibious/slow-fall corpse controllers.
        if (unit.definition.movement.hover != null ||
            unit.amphibiousState != null ||
            NautilusMotion.slowDeath(unit) ||
            unit.definition.transport == TransportProfile.NATIVE_DROPSHIP
        ) return
        if (!inWater || unit.height <= -10.0) {
            unit.waterSettled = true
            return
        }
        val elapsed = delta.toFloat()
        unit.sinkSpeed += .0008f * elapsed
        unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        require(unit.sinkSpeed.isFinite() && unit.height.isFinite()) { "Water corpse must remain finite" }
    }
}
