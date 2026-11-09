package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CustomActionDefinition
import com.rustedwarfare.reforge.core.content.AutomaticActionCondition
import com.rustedwarfare.reforge.core.content.UnitDefinition

/** Immutable queue values participate in the same candidate transaction as the unit. */
internal data class PendingUnitAction(val action: CustomActionDefinition, val progress: Float = 0f)
internal data class CustomActionState(val queue: List<PendingUnitAction> = emptyList(),
    val cooldownEnds: Map<String, Int> = emptyMap(), val automaticCooldown: Float = 0f)

/** Finite custom conversion actions; movement orders and same-name weapon state stay on the object. */
internal object CustomUnitActions {
    fun observe(unit: MutableUnit, now: Int): UnitActionsObservation? {
        val profile = unit.definition.customActions ?: return null
        val actions = profile.actions.filter { it.automatic == null }.map { action ->
            val index = unit.customActionState.queue.indexOfFirst { it.action.actionId == action.actionId }
            val remaining = maxOf(0, (unit.customActionState.cooldownEnds[action.actionId] ?: now) - now)
            UnitActionObservation(action.name, index.takeIf { it >= 0 },
                unit.customActionState.queue.getOrNull(index)?.progress, remaining,
                unit.alive && unit.carriedBy == null && BuildingWork.isReady(unit) && remaining == 0 && index < 0)
        }
        return UnitActionsObservation(immutable(actions), unit.customActionState.automaticCooldown)
    }

    fun request(unit: MutableUnit, name: String, cancel: Boolean, now: Int): SimulationError? {
        val action = unit.definition.customActions?.actions?.singleOrNull { it.name == name && it.automatic == null }
            ?: return SimulationError("unsupported-unit-action", "Unit does not expose this action")
        if (!BuildingWork.isReady(unit)) return SimulationError("unit-incomplete", "Unit is not complete")
        if (cancel) return SimulationError("unit-action-not-cancellable", "This action cannot be cancelled")
        if ((unit.customActionState.cooldownEnds[action.actionId] ?: now) > now)
            return SimulationError("unit-action-cooldown", "This action is cooling down")
        if (!action.allowMultipleInQueue && unit.customActionState.queue.any { it.action.actionId == action.actionId })
            return SimulationError("duplicate-unit-action", "This action is already queued")
        unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue + PendingUnitAction(action))
        return null
    }

    fun advanceQueue(unit: MutableUnit, definitions: Map<String, UnitDefinition>, delta: Double, now: Int): Boolean {
        val state = unit.customActionState
        val head = state.queue.firstOrNull() ?: return false
        val action = checkNotNull(unit.definition.customActions?.actions?.singleOrNull {
            it.actionId == head.action.actionId && it.automatic == null })
        val progress = head.progress + head.action.rate * delta.toFloat()
        require(progress.isFinite()) { "Unit action progress must remain finite" }
        if (progress < 1f) {
            unit.customActionState = state.copy(queue = listOf(head.copy(progress = progress)) + state.queue.drop(1))
            return false
        }
        unit.customActionState = state.copy(queue = state.queue.drop(1))
        action.energySet?.let { unit.energy = it }
        if (action.cooldown > 0) unit.customActionState = unit.customActionState.copy(
            cooldownEnds = state.cooldownEnds + (action.actionId to (now + action.cooldown)))
        convert(unit, definitions.getValue(action.targetTypeId))
        return true
    }

    private fun convert(unit: MutableUnit, target: UnitDefinition) {
        check(AaBeamGunship.supports(unit.definition) && AaBeamGunship.supports(target))
        val health = (checkNotNull(unit.health).toFloat() / checkNotNull(unit.definition.maximumHealth).toFloat() *
            checkNotNull(target.maximumHealth).toFloat()).toDouble()
        val energy = unit.energy / checkNotNull(unit.definition.energy).maximum * checkNotNull(target.energy).maximum
        require(health.isFinite() && energy.isFinite()) { "Converted resources must remain finite" }
        unit.typeId = target.id
        unit.definition = target
        unit.health = health
        unit.energy = energy
        val retainedIds = checkNotNull(target.customActions).actions.filter { it.automatic == null }.map { it.actionId }.toSet()
        unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue.filter {
            it.action.actionId in retainedIds })
    }

    fun advanceAutomatic(unit: MutableUnit, definitions: Map<String, UnitDefinition>, delta: Double): Boolean {
        val profile = unit.definition.customActions ?: return false
        val automatic = profile.actions.filter { it.automatic != null }
        if (automatic.isEmpty()) return false
        val remaining = maxOf(0f, unit.customActionState.automaticCooldown - delta.toFloat())
        unit.customActionState = unit.customActionState.copy(automaticCooldown = remaining)
        if (remaining > 0f) return false
        for (action in automatic) {
            val ready = when (action.automatic) {
                AutomaticActionCondition.ENERGY_EMPTY -> unit.energy <= 0f
                null -> false
            }
            if (!ready) continue
            action.energySet?.let { unit.energy = it }
            convert(unit, definitions.getValue(action.targetTypeId))
            // Original setter reads the definition AFTER the conversion.
            unit.customActionState = unit.customActionState.copy(
                automaticCooldown = checkNotNull(unit.definition.customActions).automaticCooldown)
            return true
        }
        return false
    }

    fun validateCandidate(unit: MutableUnit) {
        val state = unit.customActionState
        val actions = unit.definition.customActions?.actions.orEmpty().filter { it.automatic == null }
        val ids = actions.map { it.actionId }.toSet()
        require(state.automaticCooldown.isFinite() && state.automaticCooldown in 0f..60f &&
            state.queue.size <= actions.size && state.queue.map { it.action.actionId }.distinct().size == state.queue.size &&
            state.queue.all { it.action.actionId in ids && it.progress.isFinite() && it.progress in 0f..<1f } &&
            state.cooldownEnds.keys.all { it == "aaBeamGunship_afterburn_0" } &&
            (unit.definition.customActions != null || state == CustomActionState())) {
            "Invalid custom unit action candidate"
        }
    }
}
