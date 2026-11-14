package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
internal data class SpiderBlink(val target: Position, val queuedAt: Long, val duration: Int, val remaining: Int = duration)

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
        val profile = checkNotNull(child.definition.attachmentModule?.parentBlink)
        Blink.validateTarget(child.position, point, profile, collision)?.let { return it }
        child.blinkRequest = Position(point.x.toFloat().toDouble(), point.y.toFloat().toDouble())
        return null
    }

    fun cooldown(time: Double, child: MutableUnit): Int =
        Blink.uiCooldown(child.blinkCooldownUntil - time)

    fun finishSlot(state: StepState, step: Step, parent: MutableUnit, child: MutableUnit) {
        val target = child.blinkRequest ?: return
        if (!BuildingWork.isReady(child)) return
        child.blinkRequest = null
        val profile = checkNotNull(child.definition.attachmentModule?.parentBlink)
        child.blinkCooldownUntil = state.simulationTime + step.delta + profile.cooldownTicks
        state.pendingEvents += Event("unit-action-completed", child.unitId)
        if (parent.spiderBlink == null) {
            parent.spiderBlink = SpiderBlink(target, step.stepIndex, profile.chargeTicks)
            state.pendingEvents += Event("unit-action-blink-charging", parent.unitId)
        }
    }

    fun advanceParent(state: StepState, parent: MutableUnit, pending: SpiderBlink) {
        if (pending.remaining > 1) {
            parent.spiderBlink = pending.copy(remaining = pending.remaining - 1)
            return
        }
        parent.spiderBlink = null
        Blink.teleport(state, parent, pending.target)
    }

    fun progress(pending: SpiderBlink): Float = 1f - pending.remaining.toFloat() / pending.duration
}
