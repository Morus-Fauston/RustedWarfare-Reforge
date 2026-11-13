package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.CustomActionDefinition
import com.rustedwarfare.reforge.core.content.AutomaticActionCondition
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.NautilusRules
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.GroundLaunchProfile
import com.rustedwarfare.reforge.core.content.ScoutBotRules

/** Immutable queue values participate in the same candidate transaction as the unit. */
internal data class PendingUnitAction(val action: CustomActionDefinition, val orderId: String, override val progress: Float = 0f,
    val target: Position? = null) : PendingFactoryWork {
    override val cost: Long get() = action.groundLaunch?.price ?: action.ammoProduction?.price ?: 0L
    override val rate: Float get() = action.rate

    fun matches(current: CustomActionDefinition): Boolean = action == current &&
        (target != null) == (action.groundLaunch != null) &&
        target?.let { it.x.isFinite() && it.y.isFinite() } != false
}
internal data class CustomActionChange(val ledger: ProductionLedger?, val error: SimulationError? = null)
internal data class CustomActionState(val queue: List<PendingUnitAction> = emptyList(),
    val cooldownEnds: Map<String, Int> = emptyMap(), val automaticCooldown: Float = 0f)

/** Finite custom conversion actions; movement orders and same-name weapon state stay on the object. */
internal object CustomUnitActions {
    fun supports(definition: UnitDefinition): Boolean = AaBeamGunship.supports(definition) || Bunker.supports(definition) ||
        NautilusRules.supports(definition) || ScoutBotRules.supports(definition)

    fun movementLocked(unit: MutableUnit, head: PendingUnitAction? = unit.customActionState.queue.firstOrNull()): Boolean = head?.let {
        it.progress > 0f && it.action.cannotMove
    } == true

    /** Runs after the base movement and the queue: submission itself never changes motion. */
    fun constrain(unit: MutableUnit, delta: Double, steering: SteeringPolicy, coordinates: CoordinatePolicy,
                  head: PendingUnitAction? = unit.customActionState.queue.firstOrNull()) {
        val action = head?.action ?: return
        if (action.cannotMove && (action.groundLaunch == null || head.progress > 0f)) {
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

    fun observe(unit: MutableUnit, now: Int, input: Scenario,
                queue: List<PendingUnitAction> = unit.customActionState.queue): UnitActionsObservation? {
        val profile = unit.definition.customActions ?: return null
        val actions = profile.actions.filter { it.automatic == null && it.visible }.map { action ->
            val index = queue.indexOfFirst { it.action.actionId == action.actionId }
            val remaining = maxOf(0, (unit.customActionState.cooldownEnds[action.actionId] ?: now) - now)
            UnitActionObservation(action.name, index.takeIf { it >= 0 },
                queue.getOrNull(index)?.progress, remaining,
                unit.alive && unit.carriedBy == null && BuildingWork.isReady(unit) && remaining == 0 &&
                    (index < 0 || action.allowMultipleInQueue) &&
                    (!action.requiresOverWater || overWater(unit, input.map)) &&
                    (action.ammoProduction == null || input.nukesEnabled && hasAmmoQueueRoom(unit, action, queue)))
        }
        val stockpile = unit.ordnance.ammo.takeIf { profile.actions.any { it.ammoProduction != null } }
        return UnitActionsObservation(immutable(actions), unit.customActionState.automaticCooldown, stockpile,
            immutable(queue.filter { it.target != null || it.action.ammoProduction != null }
                .map { QueuedUnitActionObservation(it.action.name, it.progress, it.target) }))
    }

    private fun hasAmmoQueueRoom(unit: MutableUnit, action: CustomActionDefinition, queue: List<PendingUnitAction>): Boolean =
        unit.ordnance.ammo.toLong() + queue.count { it.action.actionId == action.actionId } <
            checkNotNull(action.ammoProduction).queueLimit

    fun request(unit: MutableUnit, command: UnitAction, now: Int, input: Scenario, collisionMap: MapDefinition,
                ledger: ProductionLedger?): CustomActionChange {
        fun reject(code: String, message: String) = CustomActionChange(ledger, SimulationError(code, message))
        val action = unit.definition.customActions?.actions?.singleOrNull {
            it.name == command.actionName && it.automatic == null && it.visible
        } ?: return reject("unsupported-unit-action", "Unit does not expose this action")
        if (!BuildingWork.isReady(unit)) return reject("unit-incomplete", "Unit is not complete")
        val launch = action.groundLaunch
        val target = command.target
        if (target != null && (command.cancelQueued || launch == null))
            return reject("unexpected-action-target", "This action does not accept a ground target")
        if (command.cancelQueued) {
            if (!action.canPlayerCancel)
                return reject("unit-action-not-cancellable", "This action cannot be cancelled")
            val index = unit.customActionState.queue.indexOfLast { it.action.actionId == action.actionId }
            if (index < 0) return reject("unit-action-not-queued", "This action is not queued")
            val item = unit.customActionState.queue[index]
            val refunded = if (item.cost == 0L) ledger else checkNotNull(ledger).refund(unit.ownerPlayerId, item.cost)
            unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue.filterIndexed { i, _ -> i != index })
            return CustomActionChange(refunded)
        }
        if (action.requiresOverWater && !overWater(unit, input.map))
            return reject("unit-action-requires-water", "This action requires water")
        if ((unit.customActionState.cooldownEnds[action.actionId] ?: now) > now)
            return reject("unit-action-cooldown", "This action is cooling down")
        if (!action.allowMultipleInQueue && unit.customActionState.queue.any { it.action.actionId == action.actionId })
            return reject("duplicate-unit-action", "This action is already queued")
        if (action.ammoProduction != null) {
            if (!input.nukesEnabled) return reject("nukes-disabled", "Nuclear ammunition production is locked")
            if (!hasAmmoQueueRoom(unit, action, unit.customActionState.queue))
                return reject("ammo-queue-limit", "The ammunition queue is full")
        }
        if (launch != null) {
            if (target == null) return reject("missing-action-target", "Select a ground point")
            if (!target.x.toFloat().isFinite() || !target.y.toFloat().isFinite())
                return reject("invalid-action-target", "Ground target must be finite")
            val distance = GroundMath.square(unit.position.x.toFloat(), unit.position.y.toFloat(), target.x.toFloat(), target.y.toFloat())
            if (distance > launch.range * launch.range)
                return reject("action-target-out-of-range", "Ground target is beyond launch range")
            val x = kotlin.math.floor(target.x / input.map.tileWidth).toInt()
            val y = kotlin.math.floor(target.y / input.map.tileHeight).toInt()
            if (x !in 0 until input.map.widthTiles || y !in 0 until input.map.heightTiles ||
                collisionMap.blockingFor(launch.movementType).isBlocked(x, y))
                return reject("action-target-impassable", "Ground target is blocked for this launch")
        }
        val item = PendingUnitAction(action, command.actionId, target = target)
        val paid = if (item.cost == 0L) ledger else {
            checkNotNull(ledger).charge(unit.ownerPlayerId, item.cost)
                ?: return reject("insufficient-funds", "Not enough credits to queue this action")
        }
        val queue = unit.customActionState.queue.toMutableList()
        val index = if (action.highPriority) queue.indexOfFirst { !it.action.highPriority }.takeIf { it >= 0 } ?: queue.size else queue.size
        queue.add(index, item)
        unit.customActionState = unit.customActionState.copy(queue = queue.toList())
        return CustomActionChange(paid)
    }

    fun advanceQueue(unit: MutableUnit, definitions: Map<String, UnitDefinition>, delta: Double, now: Int,
                     fireAtGround: (GroundLaunchProfile, Position) -> Unit): Boolean {
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
        val launch = action.groundLaunch
        when {
            launch != null -> fireAtGround(launch, checkNotNull(head.target))
            action.ammoProduction != null -> unit.ordnance = unit.ordnance.copy(ammo = Math.addExact(unit.ordnance.ammo, 1))
            else -> convert(unit, definitions.getValue(checkNotNull(action.targetTypeId)))
        }
        if (action.cooldown > 0) unit.customActionState = unit.customActionState.copy(
            cooldownEnds = state.cooldownEnds + (action.actionId to (now + action.cooldown)))
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
        val retained = checkNotNull(target.customActions).actions.filter { it.automatic == null }.associateBy { it.actionId }
        unit.customActionState = unit.customActionState.copy(queue = unit.customActionState.queue.mapNotNull { item ->
            retained[item.action.actionId]?.let { item.copy(action = it) }
        })
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
            convert(unit, definitions.getValue(checkNotNull(action.targetTypeId)))
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
        val byId = actions.associateBy { it.actionId }
        require(state.automaticCooldown.isFinite() && state.automaticCooldown in 0f..60f &&
            state.queue.map { it.orderId }.distinct().size == state.queue.size &&
            state.queue.all { item -> byId[item.action.actionId]?.let(item::matches) == true && item.progress in 0f..<1f } &&
            state.cooldownEnds.keys.all { (byId[it]?.cooldown ?: 0) > 0 } &&
            (unit.definition.customActions != null || state == CustomActionState())) {
            "Invalid custom unit action candidate"
        }
    }
}
