package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position

data class MinimapUnit(val unitId: String, val ownerPlayerId: String, val position: Position)
data class MinimapSnapshot(val terrain: List<TerrainFrameCell>, val units: List<MinimapUnit>) {
    companion object {
        fun from(frame: SandboxFrame): MinimapSnapshot = MinimapSnapshot(frame.terrain,
            java.util.Collections.unmodifiableList(frame.observation.units.filter { it.alive }.map {
                MinimapUnit(it.unitId, it.ownerPlayerId, it.position)
            }))
    }
}
