package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.*

/** Immutable per-carrier value: candidate forks never share a mutable passenger container. */
internal data class TransportState(val passengerIds: List<String> = emptyList(),
    val unloading: Boolean = false, val unloadRemaining: Float = 0f, val motionGate: Boolean = true,
    val liquid: Boolean = false, val airCorpseLanded: Boolean = false)

internal data class TransportChange(val error: SimulationError? = null, val event: Event? = null,
    val approach: Position? = null)

/** Owns admission and both sides of the passenger relation. Callers never edit the manifest. */
internal object Transport {
    fun boundaryRadius(unit: MutableUnit): Double = if (unit.nativeTransportPlacement) 0.0 else unit.definition.radius

    /** Native placement can occupy the small negative strip that JVM f2i maps to cell zero. */
    fun containsPosition(unit: MutableUnit, map: MapDefinition, position: Position): Boolean {
        if (MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, position, boundaryRadius(unit))) return true
        if (!unit.nativeTransportPlacement || !position.x.isFinite() || !position.y.isFinite()) return false
        if (unit.scoutOrigin != null) {
            // A spread impact may be outside the map. Keep the observed birth pose and permit a route back inside.
            val start = MapBounds.clamp(map, unit.position)
            val end = MapBounds.clamp(map, position)
            return kotlin.math.abs(position.x - end.x) <= kotlin.math.abs(unit.position.x - start.x) &&
                kotlin.math.abs(position.y - end.y) <= kotlin.math.abs(unit.position.y - start.y)
        }
        val column = (position.x.toFloat() / map.tileWidth).toInt()
        val row = (position.y.toFloat() / map.tileHeight).toInt()
        return column in 0 until map.widthTiles && row in 0 until map.heightTiles
    }

    /** Adapt only the routing/vision query, never the native stored placement. */
    fun queryPosition(position: Position, nativePlacement: Boolean, map: MapDefinition): Position =
        if (nativePlacement) MapBounds.clamp(map, position) else position

    fun queryPosition(unit: MutableUnit, map: MapDefinition, position: Position = unit.position): Position =
        queryPosition(position, unit.nativeTransportPlacement, map)

    fun supports(definition: UnitDefinition): Boolean {
        if (definition.transport == TransportProfile.AUTHORED)
            return definition.transportSettings?.isValid() == true && definition.maximumHealth != null &&
                (definition.movement.type in setOf(MovementType.HOVER, MovementType.NONE) ||
                    definition.movement.type == MovementType.AIR && RoleCompositionRules.usesRoles(definition) &&
                    definition.movement.flight?.isValid() == true) &&
                definition.attachmentModule == null && definition.attachmentLayout == null
        if (definition.transportSettings != null) return false
        if (definition.transport == TransportProfile.NAUTILUS_SUBMERGED) return NautilusRules.supports(definition)
        return when (definition.transport) {
        TransportProfile.NATIVE_HOVERCRAFT -> definition.id == "hovercraft" && definition.className == "NativeHovercraft" &&
            definition.radius == 15.0 && definition.displayRadius == 15.0 &&
            definition.movement == MovementProfile(MovementType.HOVER, .9, .03, .05, 1.4, .1, false, false,
                hover = HoverProfile(3.0, 1.5, 4.0, .1)) && definition.maximumHealth == 450.0 &&
            definition.build == BuildProfile(600L, .003f)
        TransportProfile.NATIVE_DROPSHIP -> definition.id == "dropship" && definition.className == "NativeDropship" &&
            definition.radius == 20.0 && definition.displayRadius == 20.0 &&
            definition.movement == MovementProfile(MovementType.AIR, 2.3, .03, .05, 1.4, 0.0, false, false, .6) &&
            definition.maximumHealth == 500.0 && definition.build == BuildProfile(800L, .001f) && definition.shadowImagePath != null
        TransportProfile.NAUTILUS_SUBMERGED, TransportProfile.AUTHORED, null -> false
    } && definition.totalFrames == 1 && definition.imageScale == 1.0 && definition.fogOfWarSightRange == 15 &&
        definition.durability?.deadImagePath != null && definition.durability.maxDamagePerHit == null &&
        definition.transportSlots == 1 &&
        definition.combat == null && definition.building == null && definition.builder == null &&
        definition.construction == null && definition.production == null && definition.upgrades == null &&
        definition.income == null && definition.pointDefense == null && definition.deathExplosion == null &&
        definition.mech == null && definition.rotor == null && definition.fixedUnderwaterTarget == null &&
        definition.submarine == null && definition.amphibiousJet == null && definition.shield == null &&
        !definition.authoredWaterCombat && definition.directHitTargetKind == DirectHitTargetKind.ORDINARY
    }

    private fun capacity(unit: MutableUnit): Int =
        unit.definition.transportSettings?.capacity ?: if (unit.definition.transport == TransportProfile.NAUTILUS_SUBMERGED) 3 else 4

    private fun unloadTicks(unit: MutableUnit): Float = unit.definition.transportSettings?.unloadTicks?.toFloat() ?: 30f

    private fun mayUnload(unit: MutableUnit, liquid: Boolean): Boolean = when (unit.definition.transportSettings?.unloadTerrain) {
        TransportUnloadTerrain.LAND -> !liquid
        TransportUnloadTerrain.LIQUID -> liquid
        TransportUnloadTerrain.EITHER -> true
        TransportUnloadTerrain.NEVER -> false
        null -> if (unit.definition.transport == TransportProfile.NAUTILUS_SUBMERGED)
            unit.typeId == "nautilusSubmarineSurface" && liquid else !liquid
    }

    /** Internal creation bypasses ordinary load admission, while sharing the one owned manifest. */
    fun retainProduced(carrier: MutableUnit, passenger: MutableUnit) {
        check(carrier.definition.transport == TransportProfile.NAUTILUS_SUBMERGED && passenger.carriedBy == null)
        val state = checkNotNull(carrier.transportState)
        check(passenger.unitId !in state.passengerIds && passenger.producerId == carrier.unitId)
        carrier.transportState = state.copy(passengerIds = immutable(state.passengerIds + passenger.unitId))
        passenger.carriedBy = carrier.unitId
    }

    /** Custom dA uses strict excess; normal load admission still checks the incoming cost. */
    fun allowsProductionCompletion(carrier: MutableUnit, units: Map<String, MutableUnit>): Boolean =
        carrier.definition.transport != TransportProfile.NAUTILUS_SUBMERGED ||
            checkNotNull(carrier.transportState).passengerIds.sumOf { units.getValue(it).definition.transportSlots.toLong() } <= 3L

    fun validate(units: Map<String, MutableUnit>) {
        val carried = mutableSetOf<String>()
        for (carrier in units.values) carrier.transportState?.let { state ->
            require(carrier.definition.transport != null && state.unloadRemaining.isFinite() &&
                state.unloadRemaining in 0f..unloadTicks(carrier))
            require(state.passengerIds.distinct().size == state.passengerIds.size)
            require(!carrier.reclaimed || state.passengerIds.isEmpty())
            var slots = 0L
            for (id in state.passengerIds) {
                val passenger = units.getValue(id)
                require(carried.add(id) && passenger.carriedBy == carrier.unitId && passenger.transportState == null &&
                    passenger.ownerPlayerId == carrier.ownerPlayerId && passenger.definition.transportSlots >= 0)
                slots += passenger.definition.transportSlots
            }
            require(carrier.definition.transport == TransportProfile.NAUTILUS_SUBMERGED || slots <= capacity(carrier).toLong())
        }
        for (passenger in units.values) {
            require((passenger.carriedBy != null) == (passenger.unitId in carried))
            passenger.transportExit?.let { require(it.previousCarrierId in units && it.remaining.isFinite() && it.remaining in 0f..45f) }
        }
    }

    fun refreshTerrain(unit: MutableUnit, map: MapDefinition) {
        unit.transportState = unit.transportState?.copy(liquid = liquid(unit, map))
    }

    /** Native air-base death update; live transport height never runs on a corpse. */
    fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        if (unit.definition.transport != TransportProfile.NATIVE_DROPSHIP || unit.waterSettled) return
        val state = checkNotNull(unit.transportState)
        val elapsed = delta.toFloat()
        if (unit.height > 0.0) {
            unit.sinkSpeed += .06f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else if (!state.airCorpseLanded) {
            unit.transportState = state.copy(airCorpseLanded = true, liquid = liquid(unit, map))
            unit.sinkSpeed = 0f
        } else if (!state.liquid) {
            unit.height = 0.0
            unit.waterSettled = true
        } else if (unit.height > -10.0) {
            unit.sinkSpeed += .0008f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else unit.waterSettled = true
        require(unit.sinkSpeed.isFinite() && unit.height.isFinite()) { "Air transport corpse must remain finite" }
    }

    /** Native removal invokes f(true) without the carrier's ordinary death callback. */
    fun onRemoved(unit: MutableUnit, units: Map<String, MutableUnit>, map: MapDefinition) =
        clearRelations(unit, units, map, deferCargo = false)

    fun onDeath(unit: MutableUnit, units: Map<String, MutableUnit>, map: MapDefinition, deferCargo: Boolean = false) =
        clearRelations(unit, units, map, deferCargo)

    /** Native f(true): release relations and write cj's negative health, retaining passenger death processing. */
    private fun clearRelations(unit: MutableUnit, units: Map<String, MutableUnit>, map: MapDefinition, deferCargo: Boolean) {
        unit.carriedBy?.let { id ->
            val parent = units[id]
            parent?.transportState?.let { state ->
                parent.transportState = state.copy(passengerIds = immutable(state.passengerIds.filter { it != unit.unitId }))
            }
        }
        unit.carriedBy = null
        unit.transportExit = null
        if (deferCargo) return
        val state = unit.transportState ?: return
        val angle = unit.heading.toFloat() - 270f
        val position = Position((unit.position.x.toFloat() + GroundMath.cos(angle) * 9f).toDouble(),
            (unit.position.y.toFloat() + GroundMath.sin(angle) * 9f).toDouble())
        for (id in state.passengerIds) {
            val passenger = units.getValue(id)
            passenger.carriedBy = null
            passenger.position = position
            passenger.nativeTransportPlacement = !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, position, passenger.definition.radius)
            passenger.health = -1.0
            passenger.deathPending = true
        }
        unit.transportState = state.copy(passengerIds = emptyList(), unloading = false)
    }

    fun requestUnload(carrier: MutableUnit, unloading: Boolean, map: MapDefinition): SimulationError? {
        val state = carrier.transportState ?: return SimulationError("not-carrier", "Unit has no transport capability")
        if (unloading) {
            if (carrier.definition.transport == TransportProfile.NAUTILUS_SUBMERGED && carrier.typeId != "nautilusSubmarineSurface")
                return SimulationError("cannot-unload", "This form retains its produced units")
            if (state.unloading || state.passengerIds.isEmpty() || !mayUnload(carrier, liquid(carrier, map)))
                return SimulationError("cannot-unload", "Carrier cannot start unloading here")
            carrier.transportState = state.copy(unloading = true, unloadRemaining = unloadTicks(carrier))
        } else {
            if (!state.unloading) return SimulationError("not-unloading", "Carrier is not unloading")
            carrier.transportState = state.copy(unloading = false)
        }
        return null
    }

    fun recordMotionGate(unit: MutableUnit, hasNormalAim: Boolean) {
        unit.transportState = unit.transportState?.copy(motionGate = hasNormalAim)
    }

    fun advance(unit: MutableUnit, units: Map<String, MutableUnit>, map: MapDefinition, delta: Double): List<Event> {
        if (unit.nativeTransportPlacement && MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, unit.position, unit.definition.radius))
            unit.nativeTransportPlacement = false
        unit.transportExit?.let {
            val remaining = maxOf(0f, it.remaining - delta.toFloat())
            unit.transportExit = if (remaining == 0f) null else it.copy(remaining = remaining)
        }
        var state = unit.transportState ?: return emptyList()
        val nautilus = unit.definition.transport == TransportProfile.NAUTILUS_SUBMERGED
        val events = mutableListOf<Event>()
        val stationary = unit.definition.transport == TransportProfile.AUTHORED &&
            UnitLocomotion.movement(unit).type == MovementType.NONE
        val canProgress = mayUnload(unit, liquid(unit, map)) && (nautilus || stationary || !state.motionGate)
        if (state.unloading && canProgress &&
            (unit.definition.transport != TransportProfile.NATIVE_DROPSHIP || unit.height.toFloat() < 4f)) {
            state = state.copy(unloadRemaining = maxOf(0f, state.unloadRemaining - delta.toFloat()))
            if (state.unloadRemaining == 0f) {
                state = state.copy(unloadRemaining = unloadTicks(unit))
                val passenger = state.passengerIds.lastOrNull()?.let(units::getValue)
                if (passenger == null) state = state.copy(unloading = false)
                else {
                    val negativeSide = state.passengerIds.size % 2 == 0
                    val point = landingPoint(unit, passenger, map, negativeSide)
                    if (point == null) events += Event("unload-blocked", unit.unitId)
                    else {
                        // Commit relation and manifest together only after a candidate is legal.
                        state = state.copy(passengerIds = immutable(state.passengerIds.dropLast(1)))
                        passenger.carriedBy = null
                        passenger.position = point
                        passenger.nativeTransportPlacement = !MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                            map.heightTiles.toDouble() * map.tileHeight, point, passenger.definition.radius)
                        passenger.heading = ScreenPlane.normalize(if (nautilus) unit.heading else unit.heading - 180.0)
                        if (nautilus) passenger.height = unit.height
                        passenger.transportExit = TransportExitObservation(unit.unitId, 45f)
                        passenger.attackCommand = null
                        passenger.clearWeaponTargets()
                        passenger.cancelRoute()
                        val target = if (nautilus) {
                            val angle = unit.heading.toFloat() - 90f + if (negativeSide) -7f else 7f
                            Position((point.x.toFloat() + GroundMath.cos(angle) * 110f).toDouble(),
                                (point.y.toFloat() + GroundMath.sin(angle) * 110f).toDouble())
                        } else if (unit.definition.transport == TransportProfile.NATIVE_DROPSHIP) {
                            val angle = unit.heading.toFloat() - 90f
                            Position((unit.position.x.toFloat() + GroundMath.cos(angle) * -66f).toDouble(),
                                (unit.position.y.toFloat() + GroundMath.sin(angle) * -66f).toDouble())
                        } else {
                            val angle = passenger.heading.toFloat() - 90f + if (negativeSide) -9f else 9f
                            Position((point.x.toFloat() + GroundMath.cos(angle) * 70f).toDouble(),
                                (point.y.toFloat() + GroundMath.sin(angle) * 70f).toDouble())
                        }
                        passenger.taskQueue = UnitTaskQueue().append(UnitTask(UnitTaskKind.MOVE,
                            "transport-exit:${unit.unitId}:${passenger.unitId}", target))
                        passenger.moveTarget = target
                        passenger.originMoveId = passenger.tasks.first().actionId
                        passenger.adoptRoute(listOf(target), target, -1L)
                        if (state.passengerIds.isEmpty()) state = state.copy(unloading = false)
                        events += Event("unit-unloaded", passenger.unitId)
                    }
                }
            }
        }
        unit.transportState = state
        if (nautilus || unit.definition.transport == TransportProfile.AUTHORED) return events
        if (unit.definition.transport == TransportProfile.NATIVE_DROPSHIP) {
            var phase = unit.heightPhase.toFloat() + 2f * delta.toFloat()
            if (phase > 360f) phase -= 360f
            unit.heightPhase = phase.toDouble()
            val landing = unit.tasks.isEmpty() && !liquid(unit, map)
            val target = if (landing) 2f else 35f + GroundMath.sin(phase) * 1.5f
            val limit = (if (landing) .4f else .35f) * delta.toFloat()
            val height = unit.height.toFloat()
            unit.height = (if (height < target) minOf(target, height + limit) else maxOf(target, height - limit)).toDouble()
            return events
        }
        var phase = unit.heightPhase.toFloat() + 4f * delta.toFloat()
        if (phase > 360f) phase -= 360f
        unit.heightPhase = phase.toDouble()
        val targetHeight = if (state.unloading) 0f else 3f + GroundMath.sin(phase) * 1.5f
        val height = unit.height.toFloat()
        val limit = .1f * delta.toFloat()
        unit.height = (if (height < targetHeight) minOf(targetHeight, height + limit) else maxOf(targetHeight, height - limit)).toDouble()
        return events
    }

    private fun liquid(unit: MutableUnit, map: MapDefinition) = map.isLiquid(
        (unit.position.x.toFloat() / map.tileWidth).toInt(), (unit.position.y.toFloat() / map.tileHeight).toInt())

    private fun landingPoint(carrier: MutableUnit, passenger: MutableUnit, map: MapDefinition, negativeSide: Boolean): Position? {
        val rear = carrier.heading.toFloat() - 270f
        val side = if (negativeSide) -9f else 9f
        val x: Float
        val y: Float
        if (carrier.definition.transport == TransportProfile.NAUTILUS_SUBMERGED) {
            val angle = carrier.heading.toFloat() - 90f
            val customSide = if (negativeSide) -7f else 7f
            x = carrier.position.x.toFloat() + GroundMath.cos(angle) * 30f + GroundMath.cos(angle + 90f) * customSide
            y = carrier.position.y.toFloat() + GroundMath.sin(angle) * 30f + GroundMath.sin(angle + 90f) * customSide
        } else if (carrier.definition.transport == TransportProfile.NATIVE_DROPSHIP) {
            val angle = carrier.heading.toFloat() - 90f
            val airSide = if (negativeSide) -7f else 7f
            x = carrier.position.x.toFloat() + GroundMath.cos(angle) * -9f +
                GroundMath.cos(angle + 90f) * airSide + GroundMath.sin(angle + 90f) * airSide
            y = carrier.position.y.toFloat() + GroundMath.sin(angle) * -9f
        } else {
            x = carrier.position.x.toFloat() + GroundMath.cos(rear) * 7f + GroundMath.cos(carrier.heading.toFloat()) * side
            y = carrier.position.y.toFloat() + GroundMath.sin(rear) * 7f + GroundMath.sin(carrier.heading.toFloat()) * side
        }
        val candidates = listOf(x to y, x + 10f to y, x - 10f to y, x - 20f to y + 10f, x - 20f to y - 10f)
        val blocking = map.blockingFor(UnitLocomotion.movement(passenger).type)
        return candidates.firstOrNull { (px, py) ->
            val column = (px / map.tileWidth).toInt(); val row = (py / map.tileHeight).toInt()
            column in 0 until map.widthTiles && row in 0 until map.heightTiles && !blocking.isBlocked(column, row)
        }?.let { Position(it.first.toDouble(), it.second.toDouble()) }
    }

    fun request(action: LoadAction, actor: MutableUnit, target: MutableUnit,
                units: Map<String, MutableUnit>): TransportChange {
        val carrier = if (action.direction == LoadDirection.INTO) target else actor
        val passenger = if (action.direction == LoadDirection.INTO) actor else target
        admission(carrier, passenger, units)?.let { return TransportChange(error = it) }
        actor.taskQueue = UnitTaskQueue().append(UnitTask(
            if (action.direction == LoadDirection.INTO) UnitTaskKind.LOAD_INTO else UnitTaskKind.LOAD_UP,
            action.actionId, targetUnitId = target.unitId, atStep = action.atStep))
        actor.attackCommand = null
        actor.cancelRoute()
        return advanceLoading(actor, units) ?: error("A new load order must be active")
    }

    fun advanceLoading(actor: MutableUnit, units: Map<String, MutableUnit>): TransportChange? {
        val task = actor.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.LOAD_INTO || it.kind == UnitTaskKind.LOAD_UP }
            ?: return null
        val target = units[task.targetUnitId]
        val into = task.kind == UnitTaskKind.LOAD_INTO
        val carrier = if (into) target else actor
        val passenger = if (into) actor else target
        val failure = if (carrier == null || passenger == null) SimulationError("target-unavailable", "Loading target is not available")
            else admission(carrier, passenger, units)
        if (failure != null) {
            finishOrder(actor)
            return TransportChange(error = failure, event = Event("loading-cancelled", actor.unitId))
        }
        checkNotNull(carrier); checkNotNull(passenger); checkNotNull(target)
        val x = carrier.position.x.toFloat() - passenger.position.x.toFloat()
        val y = carrier.position.y.toFloat() - passenger.position.y.toFloat()
        val squared = x * x + y * y
        if (if (into) squared >= 441f else squared > 441f) return TransportChange(approach = target.position)
        val state = checkNotNull(carrier.transportState)
        carrier.transportState = state.copy(passengerIds = immutable(state.passengerIds + passenger.unitId))
        passenger.carriedBy = carrier.unitId
        finishOrder(actor)
        return TransportChange(event = Event("unit-loaded", carrier.unitId))
    }

    private fun finishOrder(actor: MutableUnit) {
        actor.taskQueue = actor.taskQueue.pop()
        actor.cancelRoute()
        actor.movementRequested = false
    }

    private fun admission(carrier: MutableUnit, passenger: MutableUnit, units: Map<String, MutableUnit>): SimulationError? {
        val state = carrier.transportState
            ?: return SimulationError("not-carrier", "Target has no transport capability")
        val nautilus = carrier.definition.transport == TransportProfile.NAUTILUS_SUBMERGED
        val requiredMovement = carrier.definition.transportSettings?.passengerMovement
        if (nautilus && carrier.typeId != "nautilusSubmarineSurface")
            return SimulationError("invalid-passenger", "Passenger does not meet this form's ordinary load requirements")
        if (!carrier.alive || !passenger.alive || carrier.constructionProgress < 1f || passenger.constructionProgress < 1f ||
            carrier.carriedBy != null || passenger.carriedBy != null || carrier === passenger ||
            carrier.ownerPlayerId != passenger.ownerPlayerId || passenger.transportState != null ||
            passenger.definition.building != null ||
            (if (requiredMovement != null) UnitLocomotion.movement(passenger).type != requiredMovement
                else if (nautilus) passenger.definition.movement.type !in setOf(MovementType.WATER, MovementType.HOVER, MovementType.OVER_CLIFF_WATER)
                else passenger.definition.movement.type in setOf(MovementType.AIR, MovementType.WATER, MovementType.NONE)))
            return SimulationError("invalid-passenger", "Unit cannot board this carrier")
        if (state.unloading) return SimulationError("transport-unloading", "Carrier is unloading")
        if (state.passengerIds.sumOf { units.getValue(it).definition.transportSlots.toLong() } +
                passenger.definition.transportSlots.toLong() > capacity(carrier).toLong())
            return SimulationError("transport-full", "Carrier has no capacity for this passenger")
        return null
    }

    fun observe(carrier: MutableUnit, units: Map<String, MutableUnit>): TransportObservation? = carrier.transportState?.let {
        TransportObservation(immutable(it.passengerIds), it.passengerIds.size,
            it.passengerIds.sumOf { id -> units.getValue(id).definition.transportSlots.toLong() }.toInt(),
            capacity(carrier), it.unloading, it.unloadRemaining,
            mayUnload(carrier, it.liquid) &&
                carrier.alive && carrier.constructionProgress >= 1f && !it.unloading && it.passengerIds.isNotEmpty())
    }
}
