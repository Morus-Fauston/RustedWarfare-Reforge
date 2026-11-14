package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.ProjectileAppearance
import java.util.Collections

internal data class BeamDrawing(val start: Position, val end: Position, val images: UnitAppearance, val textureOffset: Float)

/** Geometry only from the authorized player view. */
internal object BeamPresentation {
    fun project(view: PlayerObservation, resources: SandboxRenderResources): List<BeamDrawing> =
        Collections.unmodifiableList(view.projectiles.mapNotNull { shot ->
            if (shot.appearance != ProjectileAppearance.AA_BEAM) return@mapNotNull null
            val beam = requireNotNull(shot.beam) { "A beam requires authorized endpoint geometry" }
            val images = if (beam.resourceUnitType != null) resources.units.getValue(checkNotNull(beam.resourceUnitType))
            else resources.units["aaBeamGunship"] ?: resources.units.values.first {
                it.beamImagePath != null && it.beamStartImagePath != null && it.beamEndImagePath != null
            }
            require(images.beamImagePath != null && images.beamStartImagePath != null && images.beamEndImagePath != null)
            BeamDrawing(Position(shot.position.x, shot.position.y - shot.height),
                Position(beam.end.x, beam.end.y - beam.endHeight), images, beam.textureOffset)
        })
}
