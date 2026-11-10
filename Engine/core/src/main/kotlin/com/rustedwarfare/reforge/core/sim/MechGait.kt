package com.rustedwarfare.reforge.core.sim

import kotlin.math.*

/** Two-pass foot placement, evaluated after the real movement/terrain writeback. */
internal object MechGait {
    fun advance(unit: MutableUnit, delta: Double) {
        val previous = unit.mechState ?: return
        val mech = checkNotNull(unit.definition.mech)
        val definitions = mech.legs
        val prediction = if (unit.definition.movement.slidingMode) {
            val raw = Position(unit.flightVelocity.x * 15.0, unit.flightVelocity.y * 15.0)
            finite(raw.x, raw.y)
            val lengthSquared = raw.x * raw.x + raw.y * raw.y
            finite(lengthSquared)
            val length = min(15.0, 240.0 * lengthSquared)
            if (lengthSquared == 0.0) Position(0.0, 0.0) else {
                val direction = atan2(raw.y, raw.x)
                Position(cos(direction) * length, sin(direction) * length)
            }
        } else {
            // Preserve the qualified two-leg scalar arithmetic, including its rounding order.
            val raw = unit.speed * 15.0
            finite(raw)
            val length = min(15.0, 240.0 * raw * raw)
            ScreenPlane.displacement(unit.heading, sign(raw) * length)
        }
        val targets = definitions.map { definition ->
            val base = MechMotion.offset(unit.position, unit.heading, definition.x, definition.y)
            Position(base.x + prediction.x * definition.predictionMultiplier,
                base.y + prediction.y * definition.predictionMultiplier).also { finite(it.x, it.y) }
        }
        val distances = targets.mapIndexed { index, target ->
            ScreenPlane.distanceSquared(previous.feet[index].position, target).also { finite(it) }
        }
        var furthest = 0
        for (index in 1 until distances.size) if (distances[index] > distances[furthest]) furthest = index
        var movingCount = previous.feet.count { it.moving }
        val feet = previous.feet.toMutableList()
        for (index in feet.indices) {
            val definition = definitions[index]
            val target = targets[index]
            val oldDistance = distances[index]
            var foot = feet[index]
            // These recoveries deliberately retain the first pass's distance and moving count.
            if (oldDistance > 90000.0) {
                foot = foot.copy(position = Position(unit.position.x + definition.x, unit.position.y + definition.y))
            } else if (oldDistance > definition.hardLimit * definition.hardLimit) {
                val distance = sqrt(oldDistance)
                foot = foot.copy(position = Position(
                    target.x + (foot.position.x - target.x) / distance * definition.hardLimit,
                    target.y + (foot.position.y - target.y) / distance * definition.hardLimit), moving = true)
            }
            if (!foot.moving && foot.height <= definition.targetHeight + 0.1 &&
                movingCount < definition.maximumMovingLegs && index == furthest) {
                val neighbourMoving = mech.neighbours[index].any { feet[it].moving }
                val threshold = if (neighbourMoving) definition.holdWithMovingNeighbour else definition.holdMinimum
                if (oldDistance > threshold * threshold) {
                    foot = foot.copy(moving = true)
                    movingCount++
                }
            }
            foot = when {
                !foot.moving -> {
                    val height = max(definition.targetHeight, foot.height - definition.heightSpeed * delta)
                    foot.copy(height = height, warmUp = 0.0,
                        phase = if (height > definition.targetHeight) MechLegPhase.LOWERING else MechLegPhase.PLANTED)
                }
                foot.height > definition.targetHeight + definition.liftOffset -> {
                    val step = definition.moveSpeed * delta
                    val reached = oldDistance <= step * step
                    val angle = atan2(target.y - foot.position.y, target.x - foot.position.x)
                    val position = if (reached) target else Position(
                        foot.position.x + cos(angle) * step, foot.position.y + sin(angle) * step)
                    val turn = MechMotion.angleDifference(previous.motionAngle + definition.endDirectionOffset, foot.heading)
                        .coerceIn(-definition.rotateSpeed * delta, definition.rotateSpeed * delta)
                    foot.copy(position = position, heading = foot.heading + turn,
                        moving = !reached, phase = MechLegPhase.SWINGING)
                }
                foot.warmUp < definition.warmUp -> foot.copy(warmUp = foot.warmUp + delta, phase = MechLegPhase.WARMING_UP)
                else -> foot.copy(height = foot.height + definition.heightSpeed * delta, phase = MechLegPhase.LIFTING)
            }
            finite(foot.position.x, foot.position.y, foot.heading, foot.height, foot.warmUp)
            feet[index] = foot
        }
        unit.mechState = previous.copy(feet = immutable(feet))
    }

    private fun finite(vararg values: Double) {
        if (values.any { !it.isFinite() }) throw ArithmeticException("Non-finite mech gait candidate")
    }
}
