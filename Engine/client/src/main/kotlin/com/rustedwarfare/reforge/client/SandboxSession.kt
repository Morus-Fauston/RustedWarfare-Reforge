package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.foundationMovementType
import com.rustedwarfare.reforge.core.content.waterBlocked
import java.util.UUID
import java.nio.file.Path

data class ProductionActionView(val typeId: String, val title: String, val imagePath: Path, val totalFrames: Int,
    val price: Long, val rate: Float, val maximumHealth: Double?, val moveSpeed: Double,
    val count: Int, val progress: Float?, val affordable: Boolean, val tooltip: String = "")

data class ProductionNotice(val text: String, val step: Long)
data class ConstructionActionView(val typeId: String, val title: String, val imagePath: Path, val price: Long,
    val maximumHealth: Double, val rate: Float, val panelTitle: String = title, val tooltip: String = "")
enum class PlacementState { CLEAR, BLOCKED, UNEXPLORED }
data class ConstructionPreview(val typeId: String, val position: Position, val state: PlacementState,
    val cells: List<Pair<Int, Int>>)

class SandboxSession(scenario: Scenario, private val evidence: ClientEvidence = ClientEvidence.NONE,
                     val options: SandboxOptions = SandboxOptions(),
                     clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
                     val generation: Long = 1L) : AutoCloseable {
    constructor(scenario: Scenario, evidence: ClientEvidence, options: SandboxOptions, clockMillis: () -> Long) :
        this(scenario, evidence, options, clockMillis, 1L)

    private val scenario: Scenario = scenario.copy(automaticVision = options.knowledgeRules())
    val hasConstructorDefinitions: Boolean = this.scenario.unitDefinitions.values.any { it.builder != null }
    val hasProductionDefinitions: Boolean = this.scenario.unitDefinitions.values.any { it.production != null }
    val renderResources: SandboxRenderResources = SandboxRenderResources.from(this.scenario)
    private val commandHost = OfflineSandboxHost(this.scenario, options.deploymentEnabled, generation)
    private val context = commandHost.context
    private val commandWorld = context.world
    private var commandSequence = 0L
    private val pendingSandboxRequests = linkedMapOf<Long, SandboxCommandRequest>()
    var lastSandboxReceipt: SandboxCommandReceipt? = null
        private set
    var sandboxCommandFeedback: String = SandboxCommands.HELP
        private set
    val canDeploy: Boolean get() = isOpen && options.deploymentEnabled

    fun submitSandboxCommand(text: String): SandboxCommandReceipt {
        commandSequence = Math.incrementExact(commandSequence)
        val request = SandboxCommandRequest(commandSequence, commandWorld, generation, text)
        val result = commandHost.submit(request)
        if (result.status == SandboxCommandStatus.QUEUED) pendingSandboxRequests[result.sequence] = request
        recordSandboxReceipt(request, result)
        return result
    }

    private fun recordSandboxReceipt(request: SandboxCommandRequest, result: SandboxCommandReceipt) {
        lastSandboxReceipt = result
        sandboxCommandFeedback = when (result.status) {
            SandboxCommandStatus.HELP -> SandboxCommands.HELP
            SandboxCommandStatus.QUEUED -> "Queued for next simulation step"
            SandboxCommandStatus.APPLIED -> "Spawned ${checkNotNull(result.unitId)}"
            SandboxCommandStatus.REJECTED -> "Rejected: ${result.code}" +
                (result.errorPosition?.let { " at character $it" } ?: "")
        }
        evidence.recordWhenEnabled("sandbox-command") {
            val command = result.command as? SandboxCommand.Spawn
            mapOf("requestSequence" to result.sequence, "generation" to result.generation,
                "worldId" to result.worldId, "stepIndex" to (result.stepIndex ?: nextStep),
                "status" to result.status.name, "input" to request.text.take(512),
                "parameters" to command?.let { mapOf("type" to it.typeId, "x" to it.position.x,
                    "y" to it.position.y, "owner" to it.ownerPlayerId, "heading" to it.heading,
                    "state" to it.state.name.lowercase(java.util.Locale.ROOT)) },
                "unitId" to result.unitId, "code" to result.code, "errorPosition" to result.errorPosition)
        }
    }

    private var terrainKnowledge: List<com.rustedwarfare.reforge.core.knowledge.CellKnowledge>? = null
    private var latestFrame: SandboxFrame? = null
    val frame: SandboxFrame get() = checkNotNull(latestFrame) { "Match is closed" }
    val observation: PlayerObservation get() = frame.observation
    private val actionCatalogue = ActionCatalogue(this.scenario.unitDefinitions)
    private var menuObservation: PlayerObservation? = null
    private var menuSelectionRevision = -1L
    private var menu: ActionMenuView? = null
    val actionMenu: ActionMenuView
        get() {
            if (!isOpen) return ActionMenuView()
            val view = observation
            if (menuObservation !== view || menuSelectionRevision != selectionRevision) {
                menu = actionCatalogue.menu(view, selectedIds, selectedId,
                    scenario.players.any { it.playerId == "p1" && it.control == PlayerControl.LOCAL },
                    scenario.maintenanceContract != null)
                menuObservation = view
                menuSelectionRevision = selectionRevision
            }
            return checkNotNull(menu)
        }
    private val selection = SelectionState(clockMillis)
    val selectedId: String? get() = selection.primaryId
    val selectedIds: Set<String> get() = selection.selectedIds
    val selectionRevision: Long get() = selection.revision
    fun saveGroup(index: Int) {
        if (!isOpen) return
        selection.saveGroup(index)
        evidence.recordWhenEnabled("group-save") { mapOf("group" to index, "count" to groupCount(index)) }
    }
    fun groupCount(index: Int): Int {
        if (!isOpen) return 0
        val visible = observation.units.filter { it.alive }.map { it.unitId }.toSet()
        return selection.groupMembers(index).count { it in visible }
    }
    fun recallGroup(index: Int, additive: Boolean = false): Position? {
        if (!isOpen) return null
        val alive = observation.units.filter { it.alive }.map { it.unitId }.toSet()
        val eligible = selection.groupMembers(index).filter { it in alive }.filter { id ->
            apply(SelectAction("click-${++actionNumber}", "p1", id, context.world)) is ActionResult.Accepted
        }.toSet()
        val centerId = selection.recallGroup(index, eligible, additive)
        val center = observation.units.firstOrNull { it.unitId == centerId }?.position
        rejectedFeedback = false
        feedback = "Group $index: ${selectedIds.size} selected"
        evidence.recordWhenEnabled("group-recall") { mapOf("group" to index, "additive" to additive,
            "selectedIds" to selectedIds.toList(), "selectionRevision" to selectionRevision, "center" to center?.fields()) }
        return center
    }
    var lastCommandBatch: SelectionCommandBatch? = null
        private set

    private var actionNumber = 0L
    private var nextStep = 0L
    var isOpen = true
        private set
    var feedback = "Ready"
        private set
    private var rejectedFeedback = false
    var productionNotice: ProductionNotice? = null
        private set

    /** Player copy is separate from the original diagnostic feedback and identifiers. */
    val playerFeedback: String
        get() = when {
            feedback.startsWith("Selected ") -> if (selectedIds.size > 1) "${selectedIds.size} units selected"
                else observation.units.firstOrNull { it.unitId == selectedId }?.let { "${unitTitle(it.typeId)} selected" } ?: "No selection"
            feedback == "insufficient-funds" || feedback == "construction-insufficient-funds" -> "Insufficient funds"
            feedback == "construction-unavailable" -> "Cannot build here"
            feedback.substringBefore(';') == "construction-queue-full" -> "Construction queue is full"
            feedback == "production-queued" -> "Added to production queue"
            feedback == "production-cancelled" -> "Production cancelled; funds returned"
            feedback == "empty-production-queue" -> "Production queue is empty"
            feedback == "path-unreachable" -> "Destination is unreachable"
            feedback == "unit-produced" -> "Unit created"
            feedback == "unit-damaged" -> "Unit under attack"
            feedback == "unit-died" -> "Unit destroyed"
            feedback in setOf("Ready", "Accepted", "No selection", "Match closed") -> feedback
            feedback.startsWith("Group ") || feedback.startsWith("Accepted ") || feedback.startsWith("Added ") -> feedback.substringBefore(';')
            rejectedFeedback -> "Order unavailable"
            else -> "Order received"
        }

    private fun unitTitle(type: String) = actionCatalogue.title(type)
    val hasEconomicConstruction: Boolean get() = scenario.unitDefinitions.containsKey("extractorT1")
    val selectedUnitInfo: String? get() = actionMenu.selectedUnitInfo
    fun nominalIncome(typeId: String): Float? = actionCatalogue.nominalIncome(typeId)
    val totalNominalIncome: Float get() = actionMenu.totalNominalIncome

    init {
        try { latestFrame = makeFrame(); capture("initial") }
        catch (failure: Exception) { context.close(); isOpen = false; throw failure }
    }

    val selectedFactoryQueue: FactoryQueueObservation? get() = actionMenu.factoryQueue
    private val productionTypeIds: List<String> get() = actionMenu.production.map { it.typeId }
    val productionPrice: Long? get() = productionAction?.price
    val productionAction: ProductionActionView? get() = actionMenu.production.firstOrNull()
    val productionActions: List<ProductionActionView> get() = actionMenu.production

    fun produce(typeId: String? = productionTypeIds.firstOrNull()): ActionResult? = typeId?.takeIf { it in productionTypeIds }?.let { type ->
        apply(ProduceAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type,
            UUID.randomUUID().toString().replace("-", ""), context.world))
    }

    fun cancelProduction(typeId: String? = productionTypeIds.firstOrNull()): ActionResult? = typeId?.takeIf { it in productionTypeIds }?.let { type ->
        apply(CancelProductionAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type, context.world))
    }

    val constructionActions: List<ConstructionActionView> get() = actionMenu.construction
    val canMaintain: Boolean get() = actionMenu.canMaintain
    val canRepair: Boolean get() = actionMenu.canRepair
    /** Owner-only queue copy, derived with the rest of the selected menu. */
    val selectedWorkerQueueText: String? get() = actionMenu.workerQueueText

    fun reclaimTargetAt(position: Position): String? {
        if (!hasSelectedMaintainer()) return null
        val id = unitAt(position)
        return observation.units.singleOrNull { it.unitId == id && maintenanceTarget(it, reclaim = true) }?.unitId
    }

    fun reclaim(position: Position, append: Boolean = false): ActionResult? = reclaim(reclaimTargetAt(position) ?: "", append)

    fun reclaim(targetUnitId: String, append: Boolean = false): ActionResult? {
        lastCommandBatch = null
        if (!hasSelectedMaintainer()) return null
        val target = observation.units.singleOrNull { it.unitId == targetUnitId && maintenanceTarget(it, reclaim = true) }
        if (target == null) {
            feedback = if (hasSelectedNativeMaintainer()) "Select own unit" else "Select own building"
            rejectedFeedback = false
            return null
        }
        return dispatch { worker -> ReclaimUnitAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
    }

    fun repairTargetAt(position: Position): String? {
        if (!canRepair) return null
        val id = unitAt(position)
        return observation.units.singleOrNull { it.unitId == id && it.unitId !in selectedIds &&
            maintenanceTarget(it, reclaim = false, explicit = true) }?.unitId
    }

    fun repair(targetUnitId: String, append: Boolean = false): ActionResult? {
        lastCommandBatch = null
        if (!canRepair) return null
        val target = observation.units.singleOrNull { it.unitId == targetUnitId && it.unitId !in selectedIds &&
            maintenanceTarget(it, reclaim = false, explicit = true) }
        if (target == null) {
            feedback = "Select repairable unit"; rejectedFeedback = false; return null
        }
        return dispatch { worker -> RepairUnitAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
    }

    fun build(typeId: String, position: Position, append: Boolean = false): ActionResult? {
        lastCommandBatch = null
        if (!hasSelectedConstructor(typeId)) return null
        if (previewConstruction(typeId, position)?.state == PlacementState.BLOCKED) {
            feedback = "construction-unavailable"
            rejectedFeedback = false
            return null
        }
        val result = dispatch { worker -> BuildAction("click-${++actionNumber}", "p1", worker, typeId, position,
            UUID.randomUUID().toString().replace("-", ""), context.world, append = append) }
        if (!append && checkNotNull(lastCommandBatch).accepted > 0) selection.select(emptyList())
        return result
    }

    /** Dispatch eligibility is independent of the deliberately limited mixed-selection menu. */
    private fun hasSelectedConstructor(typeId: String): Boolean = isOpen && observation.units.any { unit ->
        unit.unitId in selectedIds && controllable(unit) &&
            typeId in scenario.unitDefinitions.getValue(unit.typeId).builder?.buildingTypeIds.orEmpty()
    }

    private fun hasSelectedNativeMaintainer(): Boolean = isOpen && observation.units.any { unit ->
        unit.unitId in selectedIds && controllable(unit) && scenario.unitDefinitions.getValue(unit.typeId).maintenance != null }

    private fun hasSelectedMaintainer(): Boolean = hasSelectedNativeMaintainer() ||
        isOpen && scenario.maintenanceContract != null && observation.units.any { unit ->
            unit.unitId in selectedIds && controllable(unit) && unit.typeId == "builder" &&
                scenario.unitDefinitions.getValue(unit.typeId).builder != null }

    private fun maintenanceTarget(target: UnitObservation, reclaim: Boolean, explicit: Boolean = false): Boolean {
        if (!target.alive || target.carriedBy != null || target.health == null) return false
        val definition = scenario.unitDefinitions.getValue(target.typeId)
        if (hasSelectedNativeMaintainer()) {
            if (reclaim) return target.ownerPlayerId == "p1" && (definition.build != null ||
                definition.construction != null || definition.reclaimPrice != null ||
                scenario.maintenanceContract?.basePrices?.containsKey(target.typeId) == true)
            if (explicit) return true
            val ownTeam = scenario.players.single { it.playerId == "p1" }.teamId
            return target.ownerPlayerId == "p1" || ownTeam != null &&
                scenario.players.single { it.playerId == target.ownerPlayerId }.teamId == ownTeam
        }
        return target.ownerPlayerId == "p1" && definition.building != null &&
            scenario.maintenanceContract?.basePrices?.containsKey(target.typeId) == true
    }

    /** Uses only the player's published terrain and visible buildings, never placement truth. */
    fun previewConstruction(typeId: String, order: Position): ConstructionPreview? {
        if (constructionActions.none { it.typeId == typeId } || !order.x.isFinite() || !order.y.isFinite()) return null
        val map = scenario.map
        val definition = scenario.unitDefinitions.getValue(typeId)
        val position = ConstructionPlacement.snap(order, definition.building?.anchor)
        fun cells(point: Position, building: com.rustedwarfare.reforge.core.content.BuildingProfile): List<Pair<Int, Int>> {
            return try {
                ConstructionPlacement.footprint(map.widthTiles, map.heightTiles, map.tileWidth, map.tileHeight,
                    point, building.placementFootprint, building.anchor).cells
            } catch (_: IllegalArgumentException) { emptyList() }
        }
        val building = checkNotNull(definition.building)
        val candidate = cells(position, building)
        var blocked = candidate.isEmpty() || !ConstructionPlacement.contains(map.widthTiles, map.heightTiles,
            map.tileWidth, map.tileHeight, position, definition.radius)
        var unknown = false
        for ((col, row) in candidate) {
            if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) { blocked = true; continue }
            val gid = frame.terrain[row * map.widthTiles + col].gid
            if (gid == null) { unknown = true; continue }
            val tileset = map.tileSets.lastOrNull { it.firstGid <= gid } ?: continue
            val tile = gid - tileset.firstGid
            if (building.requiresResourcePool) {
                if (tile !in tileset.resourcePoolTileIds) blocked = true
            } else if (tile in tileset.resourcePoolTileIds ||
                if (definition.foundationMovementType == MovementType.WATER) map.tileSets.waterBlocked(gid)
                else tile in tileset.blockedTileIds || tile in tileset.waterTileIds) blocked = true
        }
        if (observation.units.any { unit -> unit.alive && scenario.unitDefinitions.getValue(unit.typeId).building?.let {
                cells(unit.position, it).any { cell -> cell in candidate }
            } == true }) blocked = true
        return ConstructionPreview(typeId, position, when {
            blocked -> PlacementState.BLOCKED
            unknown -> PlacementState.UNEXPLORED
            else -> PlacementState.CLEAR
        }, java.util.Collections.unmodifiableList(candidate))
    }

    val selectedUpgrades: List<com.rustedwarfare.reforge.core.content.UpgradeActionProfile>
        get() = java.util.Collections.unmodifiableList(actionMenu.upgrades.map { it.profile })
    val selectedUpgradeQueue: UpgradeQueueObservation? get() = actionMenu.upgradeQueue
    val economicUpgradeTooltip: String?
        get() = actionMenu.upgrades.singleOrNull()?.takeIf { it.kind == UpgradePresentationKind.ECONOMIC }?.tooltip

    fun upgrade(actionKey: String): ActionResult? = selectedUpgrades.firstOrNull { it.actionKey == actionKey }?.let {
        apply(UpgradeAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), it.actionKey, context.world))
    }

    fun cancelUpgrade(actionKey: String): ActionResult? = selectedUpgrades.firstOrNull { it.actionKey == actionKey }?.let {
        apply(CancelUpgradeAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), it.actionKey, context.world))
    }

    fun move(position: Position): ActionResult? = dispatch { id ->
        MoveAction("click-${++actionNumber}", "p1", id, position, context.world)
    }
    val canPatrol: Boolean get() = isOpen && observation.units.any {
        it.unitId in selectedIds && controllable(it) && scenario.unitDefinitions.getValue(it.typeId).movement.type !=
            com.rustedwarfare.reforge.core.content.MovementType.NONE
    }
    fun patrol(position: Position, append: Boolean, members: Set<String> = selectedIds): ActionResult? = dispatch(members) { id ->
        PatrolAction("click-${++actionNumber}", "p1", id, position, append, context.world)
    }
    fun stop(): ActionResult? = dispatch { id ->
        StopAction("click-${++actionNumber}", "p1", id, context.world)
    }

    fun unitAction(name: String): ActionResult? = actionMenu.unitActions.singleOrNull { it.name == name }?.let {
        apply(UnitAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), name, context.world))
    }

    fun submarineDepth(surface: Boolean): ActionResult? = actionMenu.depthAction?.takeIf { it.surface == surface }?.let {
        apply(SubmarineDepthAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), surface, context.world))
    }

    fun amphibiousFlight(flying: Boolean): ActionResult? = actionMenu.flightAction
        ?.takeIf { it.flying == flying && it.available }?.let {
            apply(AmphibiousFlightAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), flying, context.world))
        }

    fun unload(unloading: Boolean): ActionResult? = actionMenu.transportAction
        ?.takeIf { it.available && it.unloading == unloading }?.let {
            apply(UnloadAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), unloading, context.world))
        }

    private fun dispatch(selectionAtPress: Set<String> = selectedIds, action: (String) -> Action): ActionResult? {
        val members = selectionAtPress.sorted()
        val revision = selectionRevision
        val results = mutableListOf<SelectionCommandMember>()
        var skipped = 0
        lastCommandBatch = null
        for (id in members) {
            if (members.size > 1 && observation.units.none { it.unitId == id && controllable(it) }) { skipped++; continue }
            results += SelectionCommandMember(id, apply(action(id)))
        }
        val batch = SelectionCommandBatch(results, skipped)
        lastCommandBatch = batch
        if (members.isEmpty()) {
            feedback = "No selection"
            rejectedFeedback = false
        } else if (members.size > 1) {
            feedback = (if (batch.queueFull == members.size) "construction-queue-full"
                else if (batch.queueFull > 0) "Added ${batch.added}, queue full ${batch.queueFull}, rejected ${batch.rejected}, skipped ${batch.skipped}"
                else "Accepted ${batch.accepted}, rejected ${batch.rejected}, skipped ${batch.skipped}") +
                results.mapNotNull { (it.result as? ActionResult.Rejected)?.error?.code }.distinct().joinToString(", ", prefix = "; ")
            rejectedFeedback = batch.rejected > 0
        }
        evidence.recordWhenEnabled("selection-command") { mapOf("accepted" to batch.accepted,
            "added" to batch.added, "queueFull" to batch.queueFull, "rejected" to batch.rejected, "skipped" to batch.skipped,
            "selectionRevision" to revision, "selectionAtPress" to members,
            "members" to results.map { mapOf("unitId" to it.unitId, "result" to it.result.fields()) }) }
        return results.lastOrNull()?.result
    }

    private fun controllable(unit: UnitObservation): Boolean = unit.alive && unit.ownerPlayerId == "p1" &&
        scenario.players.any { it.playerId == "p1" && it.control == PlayerControl.LOCAL }
    /** Read-only hit identity from this player's published observation. */
    fun commandTargetAt(position: Position): String? = unitAt(position)

    fun command(position: Position, append: Boolean = false): ActionResult? {
        val targetId = unitAt(position)
        val target = observation.units.firstOrNull { it.unitId == targetId }
        if (target != null && target.ownerPlayerId == "p1") {
            if (target.transport != null && selectedIds.any { it != target.unitId })
                return dispatch { actor -> LoadAction("click-${++actionNumber}", "p1", actor, target.unitId, LoadDirection.INTO, context.world) }
            if (observation.units.any { it.unitId in selectedIds && controllable(it) && it.transport != null })
                return dispatch { actor -> LoadAction("click-${++actionNumber}", "p1", actor, target.unitId, LoadDirection.UP, context.world) }
        }
        if (hasSelectedMaintainer() && target != null && maintenanceTarget(target, reclaim = false) &&
            (target.constructionProgress < 1f || target.health!! < target.maxHealth!!))
            return dispatch { worker -> RepairUnitAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
        if (target != null && target.ownerPlayerId == "p1" && target.constructionProgress < 1f &&
            hasSelectedConstructor(target.typeId))
            return dispatch { worker -> ResumeConstructionAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
        return if (target != null && target.ownerPlayerId != "p1")
            dispatch { actor -> AttackAction("click-${++actionNumber}", "p1", actor, target.unitId, context.world) }
        else move(position)
    }
    /** Host-authorized sound parameters only; player truth is never read by a playback backend. */
    fun consumeSounds(listener: SoundListener): AuthorizedSoundBatch = context.consumeSounds(context.world, "p1", listener)

    fun tick() {
        commandHost.advance(Step(nextStep, 1.0, "sim-tick"))
        nextStep++
        for ((sequence, request) in pendingSandboxRequests.toMap()) {
            val result = checkNotNull(commandHost.receipt(sequence))
            if (result.status != SandboxCommandStatus.QUEUED) {
                recordSandboxReceipt(request, result)
                pendingSandboxRequests.remove(sequence)
            }
        }
        publishFrame()
        observation.events.lastOrNull { it.kind == "unit-produced" }?.let { event ->
            observation.units.firstOrNull { it.unitId == event.unitId && it.ownerPlayerId == observation.playerId }?.let { unit ->
                productionNotice = ProductionNotice("${unitTitle(unit.typeId)} created", observation.stepIndex)
            }
        }
        if (!rejectedFeedback) observation.events.lastOrNull()?.let { feedback = it.kind }
        capture("step")
    }

    fun select(position: Position, mode: SelectionMode = SelectionMode.REPLACE) {
        rejectedFeedback = false
        val id = unitAt(position)
        if (id != null) {
            if (apply(SelectAction("click-${++actionNumber}", "p1", id, context.world)) is ActionResult.Accepted)
                selection.select(listOf(id), mode)
        } else if (mode == SelectionMode.REPLACE) selection.select(emptyList())
        feedback = selectedId?.let { "Selected $it" } ?: "No selection"
        evidence.recordWhenEnabled("selection") { mapOf("selectedId" to selectedId, "selectedIds" to selectedIds.sorted(),
            "selectionRevision" to selectionRevision, "world" to position.fields()) }
    }

    fun tap(position: Position) {
        if (unitAt(position) != null) select(position) else move(position)
    }

    fun selectBox(start: Position, end: Position, mode: SelectionMode = SelectionMode.REPLACE,
                  selectionAtStart: Set<String> = selectedIds) {
        if (!isOpen || listOf(start.x, start.y, end.x, end.y).any { !it.isFinite() }) return
        val left = minOf(start.x, end.x); val right = maxOf(start.x, end.x)
        val top = minOf(start.y, end.y); val bottom = maxOf(start.y, end.y)
        val candidates = observation.units.filter { controllable(it) &&
            it.displayedBodyPosition().let { center -> center.x in left..right && center.y in top..bottom } }
        val mobile = candidates.filter { scenario.unitDefinitions.getValue(it.typeId).movement.type !=
            com.rustedwarfare.reforge.core.content.MovementType.NONE }
        val accepted = (mobile.ifEmpty { candidates }).sortedBy { it.unitId }.mapNotNull { unit ->
            unit.unitId.takeIf { apply(SelectAction("click-${++actionNumber}", "p1", it, context.world)) is ActionResult.Accepted }
        }
        val visible = observation.units.filter { it.alive }.map { it.unitId }.toSet()
        selection.select(selectionAtStart.filter { it in visible })
        selection.select(accepted, mode)
        rejectedFeedback = false
        feedback = selectedId?.let { "Selected ${selectedIds.size}: $it" } ?: "No selection"
        evidence.recordWhenEnabled("selection-box") { mapOf("selectedIds" to selectedIds.toList(),
            "start" to start.fields(), "end" to end.fields(), "mode" to mode.name) }
    }

    private fun unitAt(position: Position): String? = observation.units.asSequence()
        .filter { it.alive }
        .map { unit ->
            val center = unit.displayedBodyPosition()
            val dx = center.x - position.x
            val dy = center.y - position.y
            unit to (dx * dx + dy * dy)
        }
        .filter { (unit, distance) ->
            val radius = renderResources.units.getValue(unit.typeId).displayRadius
            distance <= radius * radius
        }
        .sortedWith(compareBy<Pair<UnitObservation, Double>> { it.second }.thenBy { it.first.unitId })
        .firstOrNull()?.first?.unitId

    private fun apply(action: Action): ActionResult {
        capture("before-action")
        val result = context.submit(action)
        publishFrame()
        feedback = when (result) {
            is ActionResult.Rejected -> result.error.code
            is ActionResult.Accepted -> result.events.lastOrNull()?.kind ?: "Accepted"
        }
        rejectedFeedback = result is ActionResult.Rejected
        evidence.recordWhenEnabled("action") { mapOf("type" to action.javaClass.simpleName,
            "actorPlayerId" to action.actorPlayerId, "unitId" to action.unitId,
            "target" to when (action) { is MoveAction -> action.target.fields(); is PatrolAction -> action.target.fields(); is BuildAction -> action.target.fields(); else -> null },
            "append" to when (action) { is PatrolAction -> action.append; is BuildAction -> action.append;
                is ResumeConstructionAction -> action.append; is RepairUnitAction -> action.append;
                is ReclaimUnitAction -> action.append; else -> null },
            "targetUnitId" to when (action) { is AttackAction -> action.targetUnitId; is ResumeConstructionAction -> action.targetUnitId;
                is RepairUnitAction -> action.targetUnitId; is ReclaimUnitAction -> action.targetUnitId;
                is LoadAction -> action.targetUnitId; else -> null },
            "upgradeId" to when (action) { is UpgradeAction -> action.upgradeId; is CancelUpgradeAction -> action.upgradeId; else -> null },
            "result" to result.fields()) + (if (action is BuildAction) mapOf("buildingTypeId" to action.buildingTypeId) else emptyMap()) +
            (if (action is SubmarineDepthAction) mapOf("surface" to action.surface) else emptyMap()) +
            (if (action is AmphibiousFlightAction) mapOf("flying" to action.flying) else emptyMap()) +
            (if (action is UnitAction) mapOf("actionName" to action.actionName, "cancelQueued" to action.cancelQueued) else emptyMap()) +
            (if (action is LoadAction) mapOf("direction" to action.direction.name) else emptyMap()) +
            (if (action is UnloadAction) mapOf("unloading" to action.unloading) else emptyMap()) }
        capture("after-action")
        return result
    }

    private fun capture(phase: String) {
        if (evidence !== ClientEvidence.NONE) evidence.recordObservation(phase, observation)
    }

    private fun makeFrame(): SandboxFrame {
        val view = context.observePlayer(context.world, "p1")
        val map = scenario.map
        val knowledge = view.knowledge
        val previous = latestFrame
        if (previous != null && knowledge?.cells == terrainKnowledge) return previous.withObservation(view)
        // NoFog alone explicitly authorizes the local host's entire ground layout.
        val ground = if (knowledge == null) map.groundGids else null
        val terrain = List(map.widthTiles * map.heightTiles) { index ->
            val column = index % map.widthTiles
            val row = index / map.widthTiles
            val cell = knowledge?.cell(column, row)
            TerrainFrameCell(column, row, if (cell == null) checkNotNull(ground)[index] else cell.lastKnownGroundGid,
                cell?.visible ?: true)
        }
        terrainKnowledge = knowledge?.cells
        return SandboxFrame(view, terrain)
    }

    private fun publishFrame() {
        latestFrame = makeFrame()
        val previous = selectedIds
        selection.reconcile(observation.units.filter { it.alive }.map { it.unitId }.toSet(),
            observation.units.filter { !it.alive }.map { it.unitId }.toSet() +
                observation.events.filter { it.kind == "building-reclaimed" }.mapNotNull { it.unitId })
        if (previous.isNotEmpty() && selectedIds.isEmpty()) {
            feedback = "No selection"
        }
    }

    override fun close() {
        if (!isOpen) return
        try { capture("before-close") }
        finally {
            commandHost.close()
            pendingSandboxRequests.clear()
            isOpen = false
            selection.clear()
            latestFrame = null
            menuObservation = null
            menu = null
            terrainKnowledge = null
            productionNotice = null
            feedback = "Match closed"
        }
        evidence.record("closed", emptyMap())
    }
}

data class SelectionCommandMember(val unitId: String, val result: ActionResult)

class SelectionCommandBatch(members: List<SelectionCommandMember>, val skipped: Int) {
    val members: List<SelectionCommandMember> = java.util.Collections.unmodifiableList(ArrayList(members))
    val accepted: Int get() = members.count { it.result is ActionResult.Accepted }
    val rejected: Int get() = members.count { it.result is ActionResult.Rejected }
    val queueFull: Int get() = members.count { member ->
        (member.result as? ActionResult.Accepted)?.events?.any { it.kind == "construction-queue-full" } == true
    }
    val added: Int get() = accepted - queueFull
}
