package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.knowledge.VisionSnapshot
import com.rustedwarfare.reforge.core.content.foundationMovementType
import com.rustedwarfare.reforge.core.content.MaintenanceProfile
import java.util.Collections

internal data class BuildingRoute(val points: List<Position>, val knowledgeRevision: Long, val buildingRevision: Long)
internal interface BuildingWorkAccess {
    fun path(worker: MutableUnit, target: Position, range: Double, task: UnitTask, step: Long): BuildingRoute?
    fun construct(ownerPlayerId: String, typeId: String, position: Position, nonce: String,
                  paidLedger: ProductionLedger): ConstructionRegistration
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
        private fun nativeBay(worker: MutableUnit) = worker.definition.maintenance ==
            com.rustedwarfare.reforge.core.content.MaintenanceProfile.NATIVE_REPAIR_BAY

        private fun unitMaintenance(worker: MutableUnit) = worker.definition.maintenance != null

        fun automaticRange(worker: MutableUnit): Float = if (nativeBay(worker)) 230f else worker.definition.builder?.range ?: 0f

        private fun repairRate(worker: MutableUnit) = if (nativeBay(worker)) .2f else checkNotNull(worker.definition.builder).repairRate
        fun initial() = BuildingWork(0, emptyMap())

        fun isReady(unit: MutableUnit): Boolean = unit.constructionProgress >= 1f

        fun finishActivity(worker: MutableUnit, units: Map<String, MutableUnit>, delta: Double) {
            val target = worker.tasks.firstOrNull()?.takeIf {
                it.kind == UnitTaskKind.CONSTRUCT || it.kind == UnitTaskKind.RECLAIM }?.targetUnitId?.let { units[it] }
            val active = worker.constructionWorking && target?.alive == true
            if (!worker.previousWorkTargetActive || !active) worker.constructionPreparation =
                maxOf(0f, worker.constructionPreparation - (if (nativeBay(worker)) 4f else
                    checkNotNull(worker.definition.builder).decay) * delta.toFloat())
            worker.previousWorkTargetActive = active
        }

        private fun canContinue(worker: MutableUnit, target: MutableUnit, input: Scenario): Boolean {
            if (unitMaintenance(worker)) return target !== worker && target.alive && target.carriedBy == null && target.health != null
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

    /** Native post-parent update: selecting a task does not execute repair in this call. */
    fun advanceAutomatic(worker: MutableUnit, input: Scenario, delta: Double, candidates: () -> List<MutableUnit>) {
        if (!unitMaintenance(worker) || worker.definition.maintenance == MaintenanceProfile.SCOUT_BOT ||
            !worker.alive || worker.carriedBy != null || !isReady(worker)) return
        worker.repairScanElapsed += delta.toFloat()
        require(worker.repairScanElapsed.isFinite()) { "Repair scan timer must remain finite" }
        if (worker.repairScanElapsed <= 40f || worker.tasks.isNotEmpty()) return
        worker.repairScanElapsed = 0f
        val team = input.players.single { it.playerId == worker.ownerPlayerId }.teamId
        var chosen: MutableUnit? = null
        var bestHealth = -1f
        for (target in candidates()) {
            if (!canContinue(worker, target, input) || target.health == null ||
                isReady(target) && target.health!! >= checkNotNull(target.definition.maximumHealth)) continue
            if (target.ownerPlayerId != worker.ownerPlayerId &&
                (team == null || input.players.single { it.playerId == target.ownerPlayerId }.teamId != team)) continue
            val dx = worker.position.x.toFloat() - target.position.x.toFloat()
            val dy = worker.position.y.toFloat() - target.position.y.toFloat()
            val range = automaticRange(worker)
            if (dx * dx + dy * dy >= range * range) continue
            val health = checkNotNull(target.health).toFloat()
            if (bestHealth == -1f || health < bestHealth) { chosen = target; bestHealth = health }
        }
        chosen?.let { worker.taskQueue = worker.taskQueue.append(UnitTask(UnitTaskKind.CONSTRUCT,
            "automatic-repair:${worker.unitId}", targetUnitId = it.unitId, automaticRepair = true)) }
    }

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
                                    view: VisionSnapshot?, reclaim: Boolean = false): SimulationError? {
        val contract = input.maintenanceContract
        if (!unitMaintenance(worker) && (contract == null || worker.typeId != "builder" || worker.definition.builder == null))
            return SimulationError("cannot-maintain", "Unit cannot maintain buildings")
        if (target == null || target === worker || !target.alive || target.carriedBy != null || target.health == null ||
            (reclaim || !unitMaintenance(worker)) && target.ownerPlayerId != worker.ownerPlayerId ||
            !unitMaintenance(worker) && (target.definition.building == null || target.typeId !in checkNotNull(contract).basePrices) ||
            reclaim && target.definition.reclaimPrice == null && target.definition.build == null &&
                target.definition.construction == null && contract?.basePrices?.containsKey(target.typeId) != true ||
            !visible(target, input, view))
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
            is RepairUnitAction -> { targetId = action.targetUnitId; append = action.append; kind = UnitTaskKind.CONSTRUCT }
            is ReclaimUnitAction -> { targetId = action.targetUnitId; append = action.append; kind = UnitTaskKind.RECLAIM }
            else -> error("Expected a building continuation or maintenance action")
        }
        val target = units[targetId]
        if (action is ResumeConstructionAction) {
            val builder = worker.definition.builder ?: return SimulationError("cannot-build", "Unit cannot construct buildings")
            if (target == null || !target.alive || target.ownerPlayerId != worker.ownerPlayerId ||
                target.typeId !in builder.buildingTypeIds || isReady(target) || !visible(target, input, view))
                return SimulationError("invalid-construction-target", "Construction target is not available")
        } else validateMaintenance(worker, target, input, view, action is ReclaimUnitAction)?.let { return it }
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
        // Native sea-factory a(worker) returns 110 for mobile and fixed workers alike.
        val bonus = if (target.definition.production?.exitProfile ==
            com.rustedwarfare.reforge.core.content.FactoryExitProfile.NATIVE_SEA) 110.0 else 0.0
        if (nativeBay(worker)) {
            val range = 230.0 + bonus + if (wasWorking) 5.0 else 0.0
            if (frame.coordinates.distanceSquared(worker.position, target.position) > range * range) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
                worker.cancelRoute()
                return BuildingPreparation(false)
            }
            worker.cancelRoute()
            worker.constructionWorking = true
            worker.constructionPreparation = 0f
            return BuildingPreparation(true)
        }
        val profile = checkNotNull(worker.definition.builder)
        val range = profile.range.toDouble() + bonus + if (wasWorking) 5.0 else 0.0
        if (frame.coordinates.distanceSquared(worker.position, target.position) > range * range) {
            if (worker.tasks.firstOrNull()?.automaticRepair == true) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
                worker.cancelRoute()
                return BuildingPreparation(false)
            }
            return BuildingPreparation(false, route(worker, target.position, profile.range.toDouble() + bonus,
                checkNotNull(worker.tasks.firstOrNull()), frame))
        }
        worker.cancelRoute()
        val difference = turnForWork(worker, target.position, frame)
        if (kotlin.math.abs(difference) >= 30.0) return BuildingPreparation(false)
        worker.constructionWorking = true
        if (worker.constructionPreparation < profile.preparation) {
            worker.constructionPreparation += frame.step.delta.toFloat()
            return BuildingPreparation(false)
        }
        worker.constructionPreparation = profile.preparation
        return BuildingPreparation(true)
    }

    private fun turnForWork(worker: MutableUnit, target: Position, frame: BuildingWorkFrame): Double {
        val name = worker.definition.builder?.mainNanoTurret
            ?: return frame.steering.turn(worker, target, frame.step.delta, frame.coordinates)
        val definition = checkNotNull(worker.definition.combat?.namedWeapons).byName.getValue(name)
        val state = checkNotNull(worker.namedWeapons).getValue(name)
        state.idleHold = 70f
        val origin = NamedWeaponRuntime.pivot(worker, definition, state)
        return OrdinaryWeapon.turnTo(worker, ScreenPlane.desired(origin, target), frame.step.delta,
            state, NamedWeaponRuntime.profile(worker, definition))
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
            val head = worker.tasks.firstOrNull()
            val kind = head?.kind
            val change = if (kind == UnitTaskKind.BUILD || kind == UnitTaskKind.CONSTRUCT || kind == UnitTaskKind.RECLAIM)
                dispatch(worker, frame, wasWorking, ledger, upgrades) else BuildingWorkChange(this, ledger, upgrades)
            if (head?.automaticRepair == true && worker.tasks.firstOrNull() === head && worker.tasks.size > 1 &&
                worker.tasks[1].kind != UnitTaskKind.PATROL) {
                worker.taskQueue = worker.taskQueue.pop()
                worker.constructionElapsed = 0f
                worker.cancelRoute()
            }
            return change
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
            if (target == null || !target.alive || target.carriedBy != null ||
                !unitMaintenance(worker) && target.ownerPlayerId != worker.ownerPlayerId ||
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
                    checkNotNull(target.definition.construction).rate *
                    (when (target.definition.techLevel) { 2 -> .5f; 3 -> .25f; else -> 1f }) *
                    (worker.definition.builder?.buildSpeedMultiplier ?: 1f) * delta.toFloat())
                return BuildingWorkChange(this, ledger, upgrades, events =
                    if (isReady(target)) listOf(Event("construction-completed", target.unitId)) else emptyList())
            }
            val health = checkNotNull(target.health).toFloat() + repairRate(worker) * delta.toFloat()
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
        val difference = turnForWork(worker, order, frame)
        if (kotlin.math.abs(difference) > 30.0 || !ConstructionRetryRules.isDue(worker.constructionRetryTime, clock))
            return BuildingWorkChange(this, ledger, upgrades)
        val nonce = checkNotNull(task.buildingNonce)
        if (nonce in nonces[worker.ownerPlayerId].orEmpty()) return unavailable(worker, ledger, upgrades)
        val definition = input.unitDefinitions.getValue(checkNotNull(task.buildingTypeId))
        if (definition.nuclearSilo != null && !input.nukesEnabled) return unavailable(worker, ledger, upgrades)
        // A force-nano mobile project uses the ordinary one-cell placement anchor, without becoming a building.
        val building = definition.building ?: com.rustedwarfare.reforge.core.content.BuildingProfile(
            com.rustedwarfare.reforge.core.content.BuildingFootprint(0, 0, 0, 0))
        val position = ConstructionPlacement.snap(order, building.anchor)
        val cells = try { BuildingTerrain.footprint(input.map, position, building.placementFootprint, building.anchor) }
            catch (_: BuildingInitializationException) { emptySet() }
        val view = frame.view
        if (view != null && cells.any { !view.contains(it.first, it.second) }) return BuildingWorkChange(this, ledger, upgrades)
        val live = frame.units.values.filter { it.alive && it.definition.building != null }
        val poolAtCenter = input.map.isResourcePool(MapBounds.cell(position.x, input.map.tileWidth, input.map.widthTiles),
            MapBounds.cell(position.y, input.map.tileHeight, input.map.heightTiles))
        val terrainBlocked = if (building.requiresResourcePool) !poolAtCenter else
            cells.any { input.map.isFoundationBlocked(it.first, it.second, definition.foundationMovementType) }
        val mobileContact = definition.building == null && frame.units.values.any { other ->
            other !== worker && other.alive && other.carriedBy == null && ConstructionPlacement.mobileOverlaps(
                position, definition.radius, other.position, other.definition.radius, other.definition.building != null)
        }
        val blocked = cells.isEmpty() || !ConstructionPlacement.contains(input.map.widthTiles, input.map.heightTiles,
            input.map.tileWidth, input.map.tileHeight, position, definition.radius) ||
            terrainBlocked || mobileContact || live.any { other ->
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
            val birth = frame.access.construct(worker.ownerPlayerId, definition.id, position, nonce, charged)
            val unit = birth.unit
            paid = birth.ledger
            registered = birth.upgrades
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
            validateMaintenance(worker, target, frame.input, frame.view, reclaim = true) != null) {
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
            target.health = maxOf(0f, checkNotNull(target.health).toFloat() - repairRate(worker) * 5.1f * frame.step.delta.toFloat()).toDouble()
            checkNotNull(target.health) <= 0.0
        }
        if (!finished) return BuildingWorkChange(this, ledger, upgrades)
        val terminalPrice = target.definition.reclaimPrice?.toDouble() ?: run {
            val price = if (unitMaintenance(worker)) target.definition.build?.price ?: target.definition.construction?.price ?:
                checkNotNull(frame.input.maintenanceContract).basePrices.getValue(target.typeId)
                else checkNotNull(frame.input.maintenanceContract).basePrices.getValue(target.typeId)
            price.toDouble() * .8f.toDouble()
        }
        var paid = ledger.creditReclaim(worker.ownerPlayerId, terminalPrice)
        if (target.definition.nuclearSilo != null) paid = NuclearOrdnance.discard(target, paid)
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
        // Native ci removes the target now; its worker discovers the invalid target at the next entry.
        if (!unitMaintenance(worker)) {
            worker.taskQueue = worker.taskQueue.pop()
            worker.constructionElapsed = 0f
        }
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
        if (!input.nukesEnabled && input.unitDefinitions[action.buildingTypeId]?.nuclearSilo != null)
            return SimulationError("nukes-disabled", "Nuclear construction is disabled for this game")
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
