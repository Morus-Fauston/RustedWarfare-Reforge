package com.rustedwarfare.reforge.core.sim

/** The stage executed in the most recent step, not a promise about the next step. */
enum class MechLegPhase { PLANTED, WARMING_UP, LIFTING, SWINGING, LOWERING }

data class MechLegPose(val index: Int, val anchor: Position, val foot: Position,
                       val height: Double, val heading: Double, val phase: MechLegPhase)

/** Physical presentation of one already-authorized unit; no future foot targets or path data. */
class MechPose internal constructor(val forwardHeading: Double, val angularVelocity: Double,
                                    val reversing: Boolean, legs: List<MechLegPose>) {
    val legs: List<MechLegPose> = immutable(legs)

    override fun equals(other: Any?): Boolean = other is MechPose && forwardHeading == other.forwardHeading &&
        angularVelocity == other.angularVelocity && reversing == other.reversing && legs == other.legs
    override fun hashCode(): Int = listOf(forwardHeading, angularVelocity, reversing, legs).hashCode()
    override fun toString(): String = "MechPose(forwardHeading=$forwardHeading, angularVelocity=$angularVelocity, reversing=$reversing, legs=$legs)"
}
