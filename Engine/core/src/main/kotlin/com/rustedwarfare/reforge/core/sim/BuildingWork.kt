package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.VisionSnapshot
import com.rustedwarfare.reforge.core.content.foundationMovementType
import java.util.Collections

internal data class BuildingRoute(val points: List<Position>, val knowledgeRevision: Long, val buildingRevision: Long)
internal data class BuildingBirth(val objectId: Long, val updateKey: String)
internal interface BuildingWorkAccess {
    fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute?
    fun birth(): BuildingBirth
}
internal data class BuildingWorkFrame(val input: Scenario, val units: Map<String, MutableUnit>, val step: Step,
    val view: VisionSnapshot?, val coordinates: CoordinatePolicy, val steering: SteeringPolicy, val access: BuildingWorkAccess)
internal data class BuildingWorkChange(val work: BuildingWork, val ledger: ProductionLedger,
    val upgrades: OrdinaryUpgrades?, val created: MutableUnit? = null, val events: List<Event> = emptyList(),
    val reclaimedUnitId: String? = null)
internal data class BuildingPreparation(val ready: Boolean, val events: List<Event> = emptyList())

/** Immutable building-work state; complete worker activity lives on the candidate unit. */
internal class BuildingWork private constructor(val clock: Int, val nonces: Map<String, Set<String>>) {
    companion object {
        fun initial() = BuildingWork(0, emptyMap())

        fun isReady(unit: MutableUnit): Boolean = unit.constructionProgress >= 1f

        fun finishActivity(worker: MutableUnit, units: Map<String, MutableUnit>, delta: Double) {
            val target = worker.tasks.firstOrNull()?.takeIf {
                it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM }?.targetUnitId?.let { units[it] }
            val active = worker.constructionWorking && target?.alive == true
            if (!worker.previousWorkTargetActive || !active) worker.constructionPreparation =
                maxOf(0f, worker.constructionPreparation - checkNotNull(worker.definition.builder).decay * delta.toFloat())
            worker.previousWorkTargetActive = active
        }

        private fun canContinue(worker: MutableUnit, target: MutableUnit, input: Scenario): Boolean {
            if (input.maintenanceContract?.basePrices?.containsKey(target.typeId) == true &&
                worker.typeId == "builder" && target.definition.building != null) return true
            val types = checkNotNull(worker.definition.builder).buildingTypeIds
            if (target.typeId in types) return true
            val base = OrdinaryUpgrades.constructionBaseTypeId(target.typeId) ?: return false
            return base in types && target.definition.construction != null
        }

        fun validateEnvelope(action: Action): SimulationError? {
            if (action !is BuildAction) return null
            if (!action.target.x.isFinite() || !action.target.y.isFinite())
                return SimulationError("invalid-target", "Target coordinates must be finite")
            if (!Regex("[0-9a-f]{32}").matches(action.buildingNonce))
                return SimulationError("invalid-building-nonce", "Building nonce must be 32 lowercase hexadecimal characters")
            return null
        }
    }

    fun advanceClock(delta: Double) = BuildingWork((clock.toFloat() + delta.toFloat() * 16.666666f).toInt(), nonces)

    private fun recordBirth(owner: String, nonce: String) = BuildingWork(clock, Collections.unmodifiableMap(
        nonces + (owner to Collections.unmodifiableSet(LinkedHashSet(nonces[owner].orEmpty() + nonce)))))

    private fun visible(target: MutableUnit, input: Scenario, view: VisionSnapshot?): Boolean {
        if (view == null) return true
        val map = input.map
        if (!MapBounds.contains(map.widthTiles.toDouble() * map.tileWidth,
                map.heightTiles.toDouble() * map.tileHeight, target.position, 0.0)) return false
        return view.contains(MapBounds.cell(target.position.x, map.tileWidth, map.widthTiles),
            MapBounds.cell(target.position.y, map.tileHeight, map.heightTiles))
    }

    private fun validateMaintenance(worker: MutableUnit, target: MutableUnit?, input: Scenario,
                                    view: VisionSnapshot?): SimulationError? {
        val contract = input.maintenanceContract
        if (contract == null || worker.typeId != "builder" || worker.definition.builder == null)
            return SimulationError("cannot-maintain", "Unit cannot maintain buildings")
        if (target == null || target === worker || !target.alive || target.ownerPlayerId != worker.ownerPlayerId ||
            target.definition.building == null || target.typeId !in contract.basePrices || !visible(target, input, view))
            return SimulationError("invalid-maintenance-target", "Maintenance target is not available")
        return null
    }

    fun requestTarget(action: Action, worker: MutableUnit, input: Scenario, units: Map<String, MutableUnit>,
                      view: VisionSnapshot? = null): SimulationError? {
        val targetId: String
        val append: Boolean
        val kind: UnitTaskKind
        when (action) {
            is ResumeConstructionAction -> { targetId = action.targetUnitId; append = action.append; kind = UnitTaskKind.CONSTRUCT }
            is RepairBuildingAction -> { targetId = action.targetUnitId; append = action.append; kind = UnitTaskKind.CONSTRUCT }
            is ReclaimBuildingAction -> { targetId = action.targetUnitId; append = action.append; kind = UnitTaskKind.RECLAIM }
            else -> error("Expected a building continuation or maintenance action")
        }
        val target = units[targetId]
        if (action is ResumeConstructionAction) {
            val builder = worker.definition.builder ?: return SimulationError("cannot-build", "Unit cannot construct buildings")
            if (target == null || !target.alive || target.ownerPlayerId != worker.ownerPlayerId ||
                target.typeId !in builder.buildingTypeIds || isReady(target) || !visible(target, input, view))
                return SimulationError("invalid-construction-target", "Construction target is not available")
        } else validateMaintenance(worker, target, input, view)?.let { return it }
        if (!append || worker.tasks.isEmpty()) {
            worker.attackCommand = null
            worker.cancelRoute()
            worker.constructionElapsed = 0f
            worker.constructionWorking = false
        }
        worker.taskQueue = (if (append) worker.taskQueue else UnitTaskQueue()).append(UnitTask(kind, action.actionId,
            targetUnitId = targetId, atStep = action.atStep))
        return null
    }

    /** Shared approach and preparation for construction, repair and reclaim. */
    fun prepareTarget(worker: MutableUnit, target: MutableUnit, wasWorking: Boolean,
                      frame: BuildingWorkFrame): BuildingPreparation {
        val profile = checkNotNull(worker.definition.builder)
        val range = if (wasWorking) 90.0 else profile.range.toDouble()
        if (frame.coordinates.distanceSquared(worker.position, target.position) > range * range)
            return BuildingPreparation(false, route(worker, target.position, profile.range.toDouble(),
                checkNotNull(worker.tasks.firstOrNull()), frame))
        worker.cancelRoute()
        val difference = frame.steering.turn(worker, target.position, frame.step.delta, frame.coordinates)
        if (kotlin.math.abs(difference) >= 30.0) return BuildingPreparation(false)
        worker.constructionWorking = true
        if (worker.constructionPreparation < profile.preparation) {
            worker.constructionPreparation += frame.step.delta.toFloat()
            return BuildingPreparation(false)
        }
        worker.constructionPreparation = profile.preparation
        return BuildingPreparation(true)
    }

    private fun route(worker: MutableUnit, target: Position, range: Double, task: UnitTask,
                      frame: BuildingWorkFrame): List<Event> {
        worker.requestMovement(target)
        if (worker.moveTarget == target && worker.pathPoints.isNotEmpty()) return emptyList()
        val path = frame.access.path(worker, target, range, task, frame.step.stepIndex)
        if (path == null) {
            if (task.kind == UnitTaskKind.BUILD) {
                worker.pauseRoute()
                return emptyList()
            }
            worker.taskQueue = worker.taskQueue.pop()
            worker.constructionElapsed = 0f
            worker.cancelRoute()
            return listOf(Event("path-unreachable", worker.unitId))
        }
        worker.moveTarget = target
        worker.originMoveId = task.actionId
        worker.originAtStep = task.atStep
        worker.adoptRoute(path.points, target, path.knowledgeRevision, path.buildingRevision)
        return emptyList()
    }

    fun advanceActivity(worker: MutableUnit, frame: BuildingWorkFrame, ledger: ProductionLedger,
                        upgrades: OrdinaryUpgrades?): BuildingWorkChange {
        val wasWorking = worker.constructionWorking
        worker.constructionWorking = false
        try {
            val kind = worker.tasks.firstOrNull()?.kind
            return if (kind == UnitTaskKind.BUILD || kind == UnitTaskKind.CONSTRUCT || kind == UnitTaskKind.RECLAIM)
                dispatch(worker, frame, wasWorking, ledger, upgrades) else BuildingWorkChange(this, ledger, upgrades)
        } finally { finishActivity(worker, frame.units, frame.step.delta) }
    }

    fun dispatch(worker: MutableUnit, frame: BuildingWorkFrame, wasWorking: Boolean,
                 ledger: ProductionLedger, upgrades: OrdinaryUpgrades?): BuildingWorkChange {
        val task = worker.tasks.firstOrNull() ?: return BuildingWorkChange(this, ledger, upgrades)
        require(task.kind == UnitTaskKind.BUILD || task.kind == UnitTaskKind.CONSTRUCT || task.kind == UnitTaskKind.RECLAIM)
        val input = frame.input
        val delta = frame.step.delta
        worker.constructionElapsed += delta.toFloat()
        if (task.kind == UnitTaskKind.RECLAIM) return reclaim(worker, frame, wasWorking, ledger, upgrades)
        if (task.kind == UnitTaskKind.CONSTRUCT) {
            val target = frame.units[task.targetUnitId]
            if (target == null || !target.alive || target.ownerPlayerId != worker.ownerPlayerId ||
                !canContinue(worker, target, input) ||
                isReady(target) && checkNotNull(target.health) >= checkNotNull(target.definition.maximumHealth)) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
                worker.cancelRoute()
                return BuildingWorkChange(this, ledger, upgrades)
            }
            val preparation = prepareTarget(worker, target, wasWorking, frame)
            if (!preparation.ready) return BuildingWorkChange(this, ledger, upgrades, events = preparation.events)
            if (!isReady(target)) {
                target.constructionProgress = minOf(1f, target.constructionProgress +
                    checkNotNull(target.definition.construction).rate * delta.toFloat())
                return BuildingWorkChange(this, ledger, upgrades, events =
                    if (isReady(target)) listOf(Event("construction-completed", target.unitId)) else emptyList())
            }
            val health = checkNotNull(target.health).toFloat() + checkNotNull(worker.definition.builder).repairRate * delta.toFloat()
            val maximum = checkNotNull(target.definition.maximumHealth).toFloat()
            target.health = minOf(maximum, health).toDouble()
            if (health > maximum) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
            }
            return BuildingWorkChange(this, ledger, upgrades)
        }
        val order = checkNotNull(task.target)
        val range = checkNotNull(worker.definition.builder).range.toDouble()
        if (frame.coordinates.distanceSquared(worker.position, order) > range * range) {
            val events = route(worker, order, range, task, frame)
            if (worker.moveTarget != null && worker.singleNodePathCount > 3) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
                worker.cancelRoute()
            }
            return BuildingWorkChange(this, ledger, upgrades, events = events)
        }
        worker.cancelRoute()
        val difference = frame.steering.turn(worker, order, delta, frame.coordinates)
        if (kotlin.math.abs(difference) > 30.0 || !ConstructionRetryRules.isDue(worker.constructionRetryTime, clock))
            return BuildingWorkChange(this, ledger, upgrades)
        val nonce = checkNotNull(task.buildingNonce)
        if (nonce in nonces[worker.ownerPlayerId].orEmpty()) return unavailable(worker, ledger, upgrades)
        val definition = input.unitDefinitions.getValue(checkNotNull(task.buildingTypeId))
        val building = checkNotNull(definition.building)
        val position = ConstructionPlacement.snap(order, building.anchor)
        val cells = try { BuildingTerrain.footprint(input.map, position, building.placementFootprint, building.anchor) }
            catch (_: BuildingInitializationException) { emptySet() }
        val view = frame.view
        if (view != null && cells.any { !view.contains(it.first, it.second) }) return BuildingWorkChange(this, ledger, upgrades)
        val live = frame.units.values.filter { it.alive && it.definition.building != null }
        val poolAtCenter = input.map.isResourcePool(MapBounds.cell(position.x, input.map.tileWidth, input.map.widthTiles),
            MapBounds.cell(position.y, input.map.tileHeight, input.map.heightTiles))
        val terrainBlocked = if (building.requiresResourcePool) !poolAtCenter else
            cells.any { input.map.blockingFor(definition.foundationMovementType).isBlocked(it.first, it.second) ||
                input.map.isResourcePool(it.first, it.second) }
        val blocked = cells.isEmpty() || !ConstructionPlacement.contains(input.map.widthTiles, input.map.heightTiles,
            input.map.tileWidth, input.map.tileHeight, position, definition.radius) ||
            terrainBlocked || live.any { other ->
                val profile = checkNotNull(other.definition.building)
                BuildingTerrain.footprint(input.map, other.position, profile.placementFootprint, profile.anchor, clipToMap = true).any { it in cells }
            }
        val reused = if (blocked) live.firstOrNull { other ->
            other.ownerPlayerId == worker.ownerPlayerId && other.typeId == definition.id &&
                frame.coordinates.distanceSquared(position, other.position) <
                    (definition.radius + other.definition.radius) * (definition.radius + other.definition.radius)
        } else null
        if (blocked && reused == null) return unavailable(worker, ledger, upgrades)
        var paid = ledger
        var next = this
        var registered = upgrades
        var created: MutableUnit? = null
        val target = reused ?: run {
            val charged = ledger.charge(worker.ownerPlayerId, checkNotNull(definition.construction).price)
            if (charged == null) {
                worker.constructionRetryTime = clock
                if (worker.constructionElapsed >= 1000f) {
                    worker.taskQueue = worker.taskQueue.pop()
                    worker.constructionElapsed = 0f
                }
                return BuildingWorkChange(this, ledger, upgrades,
                    events = listOf(Event("construction-insufficient-funds", worker.unitId)))
            }
            val publicId = "constructed:${worker.ownerPlayerId}:$nonce"
            check(publicId !in frame.units)
            val identity = frame.access.birth()
            val unit = MutableUnit(publicId, worker.ownerPlayerId, definition.id, definition, position, 0.0,
                objectId = identity.objectId, updateKey = identity.updateKey, constructionProgress = 0f)
            paid = definition.production?.let { production ->
                charged.register(publicId, FactoryProductionSpec(worker.ownerPlayerId, production.unitTypeIds.associateWith {
                    checkNotNull(input.unitDefinitions.getValue(it).build) }, definition.upgrades?.actions.orEmpty()))
            } ?: charged
            registered = upgrades?.register(publicId, definition)
            next = recordBirth(worker.ownerPlayerId, nonce)
            created = unit
            unit
        }
        worker.constructionRetryTime = -9999
        worker.taskQueue = worker.taskQueue.replaceHead(UnitTask(UnitTaskKind.CONSTRUCT, task.actionId,
            targetUnitId = target.unitId, atStep = task.atStep))
        return BuildingWorkChange(next, paid, registered, created,
            if (created == null) emptyList() else listOf(Event("construction-created", target.unitId)))
    }

    private fun reclaim(worker: MutableUnit, frame: BuildingWorkFrame, wasWorking: Boolean,
                        ledger: ProductionLedger, upgrades: OrdinaryUpgrades?): BuildingWorkChange {
        val target = frame.units[checkNotNull(worker.tasks.firstOrNull()).targetUnitId]
        if (target == null || !target.alive || target.ownerPlayerId != worker.ownerPlayerId ||
            validateMaintenance(worker, target, frame.input, frame.view) != null) {
            worker.taskQueue = worker.taskQueue.pop()
            worker.constructionElapsed = 0f
            worker.cancelRoute()
            return BuildingWorkChange(this, ledger, upgrades)
        }
        val preparation = prepareTarget(worker, target, wasWorking, frame)
        if (!preparation.ready) return BuildingWorkChange(this, ledger, upgrades, events = preparation.events)
        if (worker.constructionElapsed < 100f &&
            (target.constructionProgress < .5f || target.constructionProgress >= .5f &&
                checkNotNull(target.health).toFloat() / checkNotNull(target.definition.maximumHealth).toFloat() < .5f))
            return BuildingWorkChange(this, ledger, upgrades)
        val finished = if (!isReady(target)) {
            target.constructionProgress = maxOf(0f, target.constructionProgress - .001f * 5.1f * frame.step.delta.toFloat())
            target.constructionProgress <= 0f
        } else {
            target.health = maxOf(0f, checkNotNull(target.health).toFloat() - .2f * 5.1f * frame.step.delta.toFloat()).toDouble()
            checkNotNull(target.health) <= 0.0
        }
        if (!finished) return BuildingWorkChange(this, ledger, upgrades)
        val price = checkNotNull(frame.input.maintenanceContract).basePrices.getValue(target.typeId)
        var paid = ledger.creditReclaim(worker.ownerPlayerId, price.toDouble() * .8f.toDouble())
        if (target.definition.production != null) paid = paid.destroyFactory(target.unitId)
        val discarded = upgrades?.discard(target, paid)
        paid = discarded?.ledger ?: paid
        target.health = 0.0
        target.reclaimed = true
        target.attackCommand = null
        target.taskQueue = UnitTaskQueue()
        target.cancelRoute()
        target.movementRequested = false
        target.constructionWorking = false
        target.clearWeaponTargets()
        worker.taskQueue = worker.taskQueue.pop()
        worker.constructionElapsed = 0f
        worker.constructionWorking = false
        worker.cancelRoute()
        return BuildingWorkChange(this, paid, discarded?.upgrades ?: upgrades,
            events = discarded?.events.orEmpty() + listOf(Event("building-reclaimed", target.unitId),
                Event("maintenance-completed", worker.unitId)), reclaimedUnitId = target.unitId)
    }

    private fun unavailable(worker: MutableUnit, ledger: ProductionLedger, upgrades: OrdinaryUpgrades?): BuildingWorkChange {
        worker.taskQueue = worker.taskQueue.pop()
        worker.constructionElapsed = 0f
        return BuildingWorkChange(this, ledger, upgrades, events = listOf(Event("construction-unavailable", worker.unitId)))
    }

    fun request(action: BuildAction, worker: MutableUnit, input: Scenario): SimulationError? {
        val builder = worker.definition.builder ?: return SimulationError("cannot-build", "Unit cannot construct buildings")
        if (action.buildingTypeId !in builder.buildingTypeIds || input.unitDefinitions[action.buildingTypeId]?.construction == null)
            return SimulationError("unsupported-construction-type", "Unit cannot construct this type")
        if (!MapBounds.contains(input.map.widthTiles.toDouble() * input.map.tileWidth,
                input.map.heightTiles.toDouble() * input.map.tileHeight, action.target, 0.0))
            return SimulationError("invalid-target", "Target must be within the map")
        if (action.buildingNonce in nonces[worker.ownerPlayerId].orEmpty())
            return SimulationError("duplicate-building-nonce", "Building nonce has already been used")
        if (!action.append || worker.tasks.isEmpty()) {
            worker.attackCommand = null
            worker.cancelRoute()
            worker.constructionElapsed = 0f
            worker.constructionWorking = false
        }
        worker.taskQueue = (if (action.append) worker.taskQueue else UnitTaskQueue()).append(UnitTask(UnitTaskKind.BUILD,
            action.actionId, action.target, atStep = action.atStep, buildingTypeId = action.buildingTypeId,
            buildingNonce = action.buildingNonce))
        return null
    }
}
