package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.Position

/**
 * Narrow read-only dynamic building occupancy view consumed by `core.navigation`
 * (ADR-016 / ENG-P017-006), eliminating reverse dependencies on `BuildingTerrain`
 * and `ConstructionPlacement`.
 */
internal interface DynamicBuildingGrid {
    val cells: Set<Pair<Int, Int>>
    val collisionMap: MapDefinition
    val maxHalfExtentTiles: Int

    fun isBuildingCell(column: Int, row: Int): Boolean

    fun footprintCells(
        map: MapDefinition,
        position: Position,
        shape: BuildingFootprint,
        anchor: FootprintAnchor? = null,
    ): Set<Pair<Int, Int>>
}
