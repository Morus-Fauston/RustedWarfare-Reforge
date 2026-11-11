package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CustomActionDefinition
import com.rustedwarfare.reforge.core.content.AutomaticActionCondition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.NautilusRules
import com.rustedwarfare.reforge.core.content.MapDefinition

/** Immutable queue values participate in the same candidate transaction as the unit. */
internal data class PendingUnitAction(val action: CustomActionDefinition, override val progress: Float = 0f) : PendingFactoryWork {
    override val cost: Long get() = 0L
    override val rate: Float get() = action.rate
}
internal data class CustomActionState(val queue: List<PendingUnitAction> = emptyList(),
    val cooldownEnds: Map<String, Int> = emptyMap(), val automaticCooldown: Float = 0f)

/** Finite custom conversion actions; movement orders and same-name weapon state stay on the object. */
internal object CustomUnitActions {
    fun supports(definition: UnitDefinition): Boolean = AaBeamGunship.supports(definition) || Bunker.supports(definition) ||
        NautilusRules.supports(definition)

    fun movementLocked(unit: MutableUnit, head: PendingUnitAction? = unit.customActionState.queue.firstOrNull()): Boolean = head?.let {
        it.progress > 0f && it.action.cannotMove
    } == true

    /** Runs after the base movement and the queue: submission itself never changes motion. */
    fun constrain(unit: MutableUnit, delta: Double, steering: SteeringPolicy, coordinates: CoordinatePolicy,
                  head: PendingUnitAction? = unit.customActionState.queue.firstOrNull()) {
        val action = head?.action ?: return
        if (action.cannotMove) {
            unit.speed = 0.0
            unit.flightVelocity = Position(0.0, 0.0)
        }
        action.rotateTo?.let { sourceAngle ->
            var heading = coordinates.normalize(sourceAngle + 90.0)
            if (action.rotateToOrBackwards && kotlin.math.abs(coordinates.difference(heading, unit.heading)) > 90.0)
                heading = coordinates.normalize(heading + 180.0)
            val offset = coordinates.displacement(heading, 100.0)
            val target = Position(unit.position.x + offset.x, unit.position.y + offset.y)
            if (unit.mechState != null) MechMotion.turn(unit, target, delta, steering, coordinates)
            else BodyMotion.turnOrdinary(unit, target, delta, steering, coordinates)
        }
    }

    fun observe(unit: MutableUnit, now: Int, map: MapDefinition,
                queue: List<PendingUnitAction> = unit.customActionState.queue): UnitActionsObservation? {
        val profile = unit.definition.customActions ?: return null
        val actions = profile.actions.filter { it.automatic == null && it.visible }.map { action ->
            val index = queue.indexOfFirst { it.action.actionId == action.actionId }
            val remaining = maxOf(0, (unit.customActionState.cooldownEnds[action.actionId] ?: now) - now)
            UnitActionObservation(action.name, index.takeIf { it >= 0 },
                queue.getOrNull(index)?.progress, remaining,
                unit.alive && unit.carriedBy == null && BuildingWork.isReady(unit) && remaining == 0 && index < 0 &&
                    (!action.requiresOverWater || overWater(unit, map)))
        }
        return UnitActionsObservation(immutable(actions), unit.customActionState.automaticCooldown)
    }

    fun request(unit: MutableUnit, name: String, cancel: Boolean, now: Int, map: MapDefinition): SimulationError? {
        val action = unit.definition.customActions?.actions?.singleOrNull { it.name == name && it.automatic == null && it.visible }
            ?: return SimulationError("unsupported-unit-action", "Unit does not expose this action")
        if (!BuildingWork.isReady(unit)) return SimulationError("unit-incomplete", "Unit is not complete")
        if (cancel) {
            if (!action.canPlayerCancel)
                return SimulationError("unit-action-not-cancellable", "This action cannot be cancelled")
            val index = unit.customActionState.queue.indexOfLast { it.action.actionId == action.actionId }
            if (index < 0) return SimulationError("unit-action-not-queued", "This action is not queued")
            unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue.filterIndexed { i, _ -> i != index })
            return null
        }
        if (action.requiresOverWater && !overWater(unit, map))
            return SimulationError("unit-action-requires-water", "This action requires water")
        if ((unit.customActionState.cooldownEnds[action.actionId] ?: now) > now)
            return SimulationError("unit-action-cooldown", "This action is cooling down")
        if (!action.allowMultipleInQueue && unit.customActionState.queue.any { it.action.actionId == action.actionId })
            return SimulationError("duplicate-unit-action", "This action is already queued")
        val queue = unit.customActionState.queue.toMutableList()
        val index = if (action.highPriority) queue.indexOfFirst { !it.action.highPriority }.takeIf { it >= 0 } ?: queue.size else queue.size
        queue.add(index, PendingUnitAction(action))
        unit.customActionState = unit.customActionState.copy(queue = queue.toList())
        return null
    }

    fun advanceQueue(unit: MutableUnit, definitions: Map<String, UnitDefinition>, delta: Double, now: Int): Boolean {
        val state = unit.customActionState
        val head = state.queue.firstOrNull() ?: return false
        val action = checkNotNull(unit.definition.customActions?.actions?.singleOrNull {
            it.actionId == head.action.actionId && it.automatic == null })
        val progress = head.progress + head.action.rate * (unit.definition.production?.speedMultiplier ?: 1f) * delta.toFloat()
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
        check(supports(unit.definition) && supports(target))
        val old = unit.definition
        val health = (checkNotNull(unit.health).toFloat() / checkNotNull(unit.definition.maximumHealth).toFloat() *
            checkNotNull(target.maximumHealth).toFloat()).toDouble()
        val energy = target.energy?.let { unit.energy / checkNotNull(old.energy).maximum * it.maximum } ?: 0f
        val shield = target.shield?.let { next ->
            if (next.startAtZero) unit.shield.coerceIn(0f, next.maximum)
            else old.shield?.let { unit.shield / it.maximum * next.maximum } ?: next.maximum
        } ?: 0f
        require(health.isFinite() && energy.isFinite() && shield.isFinite()) { "Converted resources must remain finite" }
        unit.typeId = target.id
        unit.definition = target
        unit.health = health
        unit.energy = energy
        unit.shield = shield
        unit.mechState = when {
            target.mech == null -> null
            unit.mechState?.feet?.size == target.mech.legs.size -> unit.mechState
            else -> MechMotion.initial(target, unit.position, unit.heading)
        }
        if (old.movement.type != target.movement.type) unit.cancelRoute()
        val retainedIds = checkNotNull(target.customActions).actions.filter { it.automatic == null }.map { it.actionId }.toSet()
        unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue.filter {
            it.action.actionId in retainedIds })
    }

    private fun overWater(unit: MutableUnit, map: MapDefinition): Boolean = map.isOverWater(
        (unit.position.x.toFloat() / map.tileWidth).toInt(), (unit.position.y.toFloat() / map.tileHeight).toInt())

    fun advanceAutomatic(unit: MutableUnit, definitions: Map<String, UnitDefinition>, map: MapDefinition, delta: Double): Boolean {
        val profile = unit.definition.customActions ?: return false
        val automatic = profile.actions.filter { it.automatic != null }
        if (automatic.isEmpty()) return false
        val remaining = maxOf(0f, unit.customActionState.automaticCooldown - delta.toFloat())
        unit.customActionState = unit.customActionState.copy(automaticCooldown = remaining)
        if (remaining > 0f) return false
        for (action in automatic) {
            val ready = when (action.automatic) {
                AutomaticActionCondition.ENERGY_EMPTY -> unit.energy <= 0f
                AutomaticActionCondition.OVER_WATER -> overWater(unit, map)
                AutomaticActionCondition.NOT_OVER_WATER -> !overWater(unit, map)
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
