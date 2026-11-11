package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MechKind
import kotlin.math.cos
import kotlin.math.sin

/** Internal angles retain the reference frame and unwrapped increments; DTOs convert to core headings. */
internal data class MechFootState(val position: Position, val heading: Double, val height: Double = 0.1,
                                  val moving: Boolean = false, val warmUp: Double = 0.0,
                                  val phase: MechLegPhase = MechLegPhase.LOWERING)

/** Immutable values are safe to share with the candidate copy until replaced on a successful step. */
internal data class MechState(val bodyAngle: Double, val motionAngle: Double, val angularVelocity: Double = 0.0,
                              val reversing: Boolean = false, val hadMotionAim: Boolean = false,
                              val hold: Double = 0.0, val sweepTime: Double = 0.0,
                              val sweepOffset: Double = 0.0, val sweepReached: Boolean = false,
                              val sweepCycle: Long = 0, val feet: List<MechFootState>,
                              val gaitPosition: Position? = null, val gaitHeading: Double = 0.0)

internal object MechMotion {
    fun sourceHeading(heading: Double): Double = (heading - 90.0).let { if (it > 180.0) it - 360.0 else it }

    fun turn(unit: MutableUnit, target: Position, delta: Double, steering: SteeringPolicy,
             coordinates: CoordinatePolicy): Double {
        val previous = checkNotNull(unit.mechState)
        val turn = BodyMotion.turn(unit, target, delta, steering, coordinates, previous.reversing)
        val reverse = turn.reversing
        val hold = if (unit.combat == null && reverse != previous.reversing && previous.hold >= 0.0)
            maxOf(25.0, previous.hold) else previous.hold
        // Integrate the applied turn: canonical public headings cannot retain both signs of 180.
        var motionAngle = previous.motionAngle + turn.applied
        if (motionAngle > 180.0) motionAngle -= 360.0
        if (motionAngle < -180.0) motionAngle += 360.0
        unit.mechState = previous.copy(reversing = reverse, hold = hold, motionAngle = motionAngle)
        return turn.difference
    }

    /** Preserve both signs of the exact half-turn, as in the reference angular helper. */
    fun angleDifference(desired: Double, current: Double): Double {
        var difference = desired % 360.0 - current % 360.0
        if (difference > 180.0) difference -= 360.0
        if (difference < -180.0) difference += 360.0
        return difference
    }

    fun initial(definition: UnitDefinition, position: Position, heading: Double): MechState? = definition.mech?.let { mech ->
        MechState(sourceHeading(heading) + mech.body.idleDirection, sourceHeading(heading), feet = immutable(mech.legs.map {
            MechFootState(offset(position, heading, it.x, it.y), sourceHeading(heading) + it.endDirectionOffset)
        }), gaitPosition = position, gaitHeading = heading).also { requireFinitePose(definition, position, heading, it) }
    }

    fun requireFinitePose(unit: MutableUnit) {
        unit.mechState?.let { requireFinitePose(unit.definition, unit.position, unit.heading, it) }
    }

    private fun requireFinitePose(definition: UnitDefinition, position: Position, heading: Double, state: MechState) {
        val values = mutableListOf(state.bodyAngle, state.motionAngle, state.angularVelocity, state.hold,
            state.sweepTime, state.sweepOffset)
        checkNotNull(definition.mech).legs.forEachIndexed { index, leg ->
            val anchor = offset(position, heading, leg.attachX, leg.attachY)
            val foot = state.feet[index]
            values += listOf(anchor.x, anchor.y, foot.position.x, foot.position.y, foot.height, foot.heading, foot.warmUp)
        }
        if (values.any { !it.isFinite() }) throw ArithmeticException("Non-finite derived mech pose")
    }

    fun pose(unit: MutableUnit): MechPose? = unit.mechState?.let { state ->
        val bodyAligned = unit.definition.mech?.kind != MechKind.TWO_LEGGED
        MechPose(if (bodyAligned) unit.heading else unit.combat?.turretHeading ?: ScreenPlane.normalize(state.bodyAngle + 90.0),
            if (bodyAligned) unit.angularVelocity else unit.combat?.angularVelocity ?: state.angularVelocity, state.reversing,
            checkNotNull(unit.definition.mech).legs.mapIndexed { index, definition ->
                val foot = state.feet[index]
                MechLegPose(index, offset(unit.position, unit.heading, definition.attachX, definition.attachY),
                    foot.position, foot.height, ScreenPlane.normalize(foot.heading + 90.0), foot.phase)
            })
    }

    /** Configuration x is sideways and y forwards; heading zero points up in the core. */
    fun offset(origin: Position, heading: Double, x: Double, y: Double): Position {
        val radians = Math.toRadians(heading)
        return Position(origin.x + cos(radians) * x + sin(radians) * y,
            origin.y + sin(radians) * x - cos(radians) * y)
    }
}
