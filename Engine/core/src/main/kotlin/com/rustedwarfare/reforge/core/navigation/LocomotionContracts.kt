package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.CustomVerticalPoseProfile
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.SubmersibleMotionProfile
import com.rustedwarfare.reforge.core.content.WaterLandSpeedProfile
import com.rustedwarfare.reforge.core.sim.MutableUnit

/**
 * Narrow Interface #1 (SPEC §2.1 / ADR-016 / ENG-P017-006):
 * Pure immutable data view of a unit's effective locomotion, terrain passability hull,
 * contact shape, kinematics, and vertical/medium capability profiles — zero unit-ID branching.
 */
data class LocomotionDescriptor(
    val movementType: MovementType,
    val terrainHull: MovementCollisionShape?,
    val contactShape: MovementCollisionShape,
    val mass: Double,
    val moveSpeed: Double,
    val moveAccelerationSpeed: Double,
    val moveDecelerationSpeed: Double,
    val maxTurnSpeed: Double,
    val turnAcceleration: Double,
    val reverseSpeedPercentage: Double,
    val shortReverseDistance: Double?,
    val slidingMode: Boolean,
    val ignoringBody: Boolean,
    val waterLandSpeed: WaterLandSpeedProfile?,
    val submersible: SubmersibleMotionProfile?,
    val verticalPose: CustomVerticalPoseProfile?,
    val sinksInWaterOnDeath: Boolean,
)

/**
 * Narrow Interface #1b (SPEC §2.1 / ADR-016):
 * Unified data-driven vertical pose & corpse lifecycle contract implemented by
 * hover, flight, submersible, amphibious, custom-height, and water-corpse controllers.
 */
internal interface VerticalPoseController {
    /**
     * Advances live vertical/medium pose for [unit].
     * Returns an optional gameplay event type emitted on medium threshold crossing (or null).
     */
    fun advancePose(
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
        acceptsHeight: (Double) -> Boolean,
    ): String?

    /** Advances post-death vertical fall / sinking pose for [unit] when applicable. */
    fun advanceCorpse(
        unit: MutableUnit,
        map: MapDefinition,
        delta: Double,
    ) {}
}
