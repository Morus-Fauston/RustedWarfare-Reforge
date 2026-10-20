package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.TileSetDefinition
import com.rustedwarfare.reforge.core.sim.Scenario
import java.nio.file.Path
import java.util.Collections

data class UnitAppearance(val displayRadius: Double, val imagePath: Path, val imageScale: Double, val totalFrames: Int)

class SandboxRenderResources private constructor(
    val widthTiles: Int, val heightTiles: Int, val tileWidth: Int, val tileHeight: Int,
    val tileSets: List<TileSetDefinition>, val units: Map<String, UnitAppearance>,
) {
    companion object {
        fun from(scenario: Scenario): SandboxRenderResources = SandboxRenderResources(
            scenario.map.widthTiles, scenario.map.heightTiles, scenario.map.tileWidth, scenario.map.tileHeight,
            Collections.unmodifiableList(ArrayList(scenario.map.tileSets)),
            Collections.unmodifiableMap(scenario.unitDefinitions.mapValues { (_, unit) ->
                UnitAppearance(unit.displayRadius, unit.imagePath, unit.imageScale, unit.totalFrames)
            }))
    }
}
