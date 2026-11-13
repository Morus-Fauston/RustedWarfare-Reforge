package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.BLINK_COOLDOWN
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.BLINK_DURATION
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.BLINK_RANGE

internal data class SpiderBlink(val target: Position, val queuedAt: Long, val remaining: Int = BLINK_DURATION)

/** The selected slot owns its input and cooldown. The chassis owns the accepted destination. */
internal object AttachmentBlink {
    const val REQUEST = "blink"
    const val QUEUED = "blinkQueued"

    fun request(time: Double, child: MutableUnit, action: UnitAction, collision: MapDefinition): SimulationError? {
        if (action.cancelQueued) {
            if (action.target != null) return SimulationError("unexpected-action-target", "Cancellation takes no ground point")
            if (child.blinkRequest == null) return SimulationError("unit-action-not-queued", "There is no queued Blink")
            child.blinkRequest = null
            return null
        }
        if (child.blinkRequest != null) return SimulationError("duplicate-unit-action", "Blink is already queued")
        if (cooldown(time, child) > 0 || child.attachmentCooldown > 0f)
            return SimulationError("unit-action-cooldown", "Blink is cooling down")
        val point = action.target ?: return SimulationError("missing-action-target", "Select a ground point")
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        if (!x.isFinite() || !y.isFinite()) return SimulationError("invalid-action-target", "Ground target must be finite")
        if (GroundMath.square(x, y, child.position.x.toFloat(), child.position.y.toFloat()) > BLINK_RANGE * BLINK_RANGE)
            return SimulationError("action-target-out-of-range", "Location too far")
        val column = kotlin.math.floor(x / collision.tileWidth).toInt()
        val row = kotlin.math.floor(y / collision.tileHeight).toInt()
        if (column !in 0 until collision.widthTiles || row !in 0 until collision.heightTiles ||
            collision.hoverBlocking.isBlocked(column, row))
            return SimulationError("action-target-impassable", "Invalid map location (must be passable by HOVER)")
        child.blinkRequest = Position(x.toDouble(), y.toDouble())
        return null
    }

    fun cooldown(time: Double, child: MutableUnit): Int =
        kotlin.math.ceil(maxOf(0.0, child.blinkCooldownUntil - time) * 1000.0 / 60.0).toInt()

    fun finishSlot(state: StepState, step: Step, parent: MutableUnit, child: MutableUnit) {
        val target = child.blinkRequest ?: return
        if (!BuildingWork.isReady(child)) return
        child.blinkRequest = null
        child.blinkCooldownUntil = state.simulationTime + step.delta + BLINK_COOLDOWN
        state.pendingEvents += Event("unit-action-completed", child.unitId)
        if (parent.spiderBlink == null) {
            parent.spiderBlink = SpiderBlink(target, step.stepIndex)
            state.pendingEvents += Event("unit-action-blink-charging", parent.unitId)
        }
    }

    fun advanceParent(state: StepState, parent: MutableUnit, pending: SpiderBlink) {
        if (pending.remaining > 1) {
            parent.spiderBlink = pending.copy(remaining = pending.remaining - 1)
            return
        }
        val source = parent.position
        val dx = pending.target.x - source.x
        val dy = pending.target.y - source.y
        parent.position = pending.target
        parent.mechState = parent.mechState?.let { gait ->
            gait.copy(feet = immutable(gait.feet.map { foot ->
                foot.copy(position = Position(foot.position.x + dx, foot.position.y + dy))
            }), gaitPosition = parent.position)
        }
        for (id in parent.attachmentIds.values) {
            val child = state.units.getValue(id)
            UnitAttachments.synchronize(parent, child, checkNotNull(child.attachment).slot)
        }
        parent.spiderBlink = null
        state.temporaryVision = state.temporaryVision + TemporaryVision(parent.ownerPlayerId, source)
        state.pendingEvents += Event("teleported", parent.unitId)
    }

    fun progress(pending: SpiderBlink): Float = 1f - pending.remaining.toFloat() / BLINK_DURATION
}
