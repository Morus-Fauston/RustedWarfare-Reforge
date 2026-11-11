package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MechBodyDefinition
import com.rustedwarfare.reforge.core.content.MechKind
import java.util.Random
import kotlin.math.abs
import kotlin.math.sign

/** Pure presentation steering, before this step's path/propulsion work. */
internal object MechBody {
    fun advance(unit: MutableUnit, delta: Double) {
        val old = unit.mechState ?: return
        if (unit.definition.mech?.kind != MechKind.TWO_LEGGED) return
        val definition = checkNotNull(unit.definition.mech).body
        if (old.hold != 0.0) {
            unit.mechState = old.copy(hold = approach(old.hold, 0.0, delta))
            return
        }
        val base = old.motionAngle + if (old.reversing && unit.definition.movement.reverseSpeedPercentage > 0.95)
            definition.reverseIdleDirection else definition.idleDirection
        val next = if (old.hadMotionAim || definition.sweepAngle == 0.0) {
            val difference = MechMotion.angleDifference(base, old.bodyAngle)
            when {
                old.bodyAngle == base -> old
                abs(difference) < 0.5 -> old.copy(hold = 20.0, angularVelocity = 0.0)
                else -> turn(old, base, delta, definition).first
            }
        } else {
            var sweep = old.copy(sweepTime = old.sweepTime + delta)
            if (!sweep.sweepReached) {
                val (turned, remaining) = turn(sweep, base + sweep.sweepOffset, delta * definition.sweepTimeScale, definition)
                sweep = turned.copy(sweepReached = remaining == 0.0)
            }
            if (sweep.sweepTime > definition.sweepDelay) {
                val cycle = sweep.sweepCycle + 1L
                // Owned deterministic identity, deliberately distinct from the original global engine/id RNG.
                val extra = if (definition.sweepRandomDelay == 0) 0 else
                    Random(31L * unit.unitId.hashCode() + cycle).nextInt(definition.sweepRandomDelay)
                sweep = sweep.copy(sweepTime = -extra.toDouble(), sweepCycle = cycle, sweepReached = false,
                    sweepOffset = if (sweep.sweepOffset > 0.0) -definition.sweepAngle else definition.sweepAngle)
            }
            sweep
        }
        if (listOf(next.bodyAngle, next.angularVelocity, next.hold, next.sweepTime, next.sweepOffset).any { !it.isFinite() })
            throw ArithmeticException("Non-finite mech body candidate")
        unit.mechState = next
    }

    private fun turn(state: MechState, target: Double, delta: Double, definition: MechBodyDefinition): Pair<MechState, Double> {
        val difference = MechMotion.angleDifference(target, state.bodyAngle)
        if (difference == 0.0) return state to 0.0
        // The qualified representative's turnSpeedDeceleration is the reference default -1.
        var rate = approach(state.angularVelocity, sign(difference) * definition.turnSpeed, definition.turnAcceleration * delta)
        var increment = rate * delta
        if (abs(increment) > abs(difference)) { increment = difference; rate = 0.0 }
        return state.copy(bodyAngle = state.bodyAngle + increment, angularVelocity = rate) to
            (difference - increment)
    }
}
