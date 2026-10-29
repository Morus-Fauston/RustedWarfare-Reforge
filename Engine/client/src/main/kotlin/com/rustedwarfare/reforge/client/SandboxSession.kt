package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.util.UUID
import java.nio.file.Path
import java.util.Locale

data class ProductionActionView(val typeId: String, val title: String, val imagePath: Path, val totalFrames: Int,
    val price: Long, val rate: Float, val maximumHealth: Double?, val moveSpeed: Double,
    val count: Int, val progress: Float?, val affordable: Boolean)

data class ProductionNotice(val text: String, val step: Long)
data class ConstructionActionView(val typeId: String, val title: String, val imagePath: Path, val price: Long,
    val maximumHealth: Double, val rate: Float)
enum class PlacementState { CLEAR, BLOCKED, UNEXPLORED }
data class ConstructionPreview(val typeId: String, val position: Position, val state: PlacementState,
    val cells: List<Pair<Int, Int>>)

class SandboxSession(scenario: Scenario, private val evidence: ClientEvidence = ClientEvidence.NONE,
                     val options: SandboxOptions = SandboxOptions(),
                     clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }) : AutoCloseable {
    private val scenario: Scenario = scenario.copy(automaticVision = options.knowledgeRules())
    val renderResources: SandboxRenderResources = SandboxRenderResources.from(this.scenario)
    private val context = when (val initialized = SimulationContext.initialize(this.scenario)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    private var terrainKnowledge: List<com.rustedwarfare.reforge.core.knowledge.CellKnowledge>? = null
    private var latestFrame: SandboxFrame? = null
    val frame: SandboxFrame get() = checkNotNull(latestFrame) { "Match is closed" }
    val observation: PlayerObservation get() = frame.observation
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
            "selectedIds" to selectedIds.toList(), "center" to center?.fields()) }
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

    private fun unitTitle(type: String) = when (type) {
        "commandCenter" -> "Command center"
        "builder" -> "Builder"
        "c_tank" -> "Tank"
        "landFactory" -> "Land factory"
        "extractorT1" -> "Extractor"
        "fabricatorT1" -> "Fabricator"
        "c_turret_t1" -> "Turret"
        "laserDefence" -> "Laser Shield"
        else -> "Unit"
    }

    val hasEconomicConstruction: Boolean get() = scenario.unitDefinitions.containsKey("extractorT1")
    val selectedUnitInfo: String?
        get() {
            if (!isOpen || selectedIds.isEmpty()) return null
            if (selectedIds.size > 1) return selectedWorkerQueueText
            val unit = observation.units.singleOrNull { it.unitId == selectedId } ?: return null
            return "${unit.health?.toInt() ?: 0} / ${unit.maxHealth?.toInt() ?: 0}" +
                (nominalIncome(unit.typeId)?.let { "\n+\$${it.toInt()}" } ?: "") +
                (if (unit.constructionProgress < 1f) "\nBuilding ${(unit.constructionProgress * 100f).toInt()}%"
                    else if (unit.typeId.startsWith("extractorT") || unit.typeId.startsWith("fabricatorT")) "\nActive" else "") +
                (unit.pointDefense?.let { String.format(Locale.ROOT, "\nEnergy %.3f%s", it.energy,
                    if (it.exhausted) " LOCKED" else "") } ?: "") +
                (selectedWorkerQueueText?.let { "\n$it" } ?: "")
        }
    fun nominalIncome(typeId: String): Float? = scenario.unitDefinitions[typeId]?.income?.let { it.credits * 40f / it.period }
    val totalNominalIncome: Float get() = observation.units.filter {
        it.alive && it.ownerPlayerId == observation.playerId && it.constructionProgress >= 1f
    }.sumOf { nominalIncome(it.typeId)?.toDouble() ?: 0.0 }.toFloat()

    init {
        try { latestFrame = makeFrame(); capture("initial") }
        catch (failure: Exception) { context.close(); isOpen = false; throw failure }
    }

    val selectedFactoryQueue: FactoryQueueObservation?
        get() {
            if (!isOpen || selectedIds.size != 1) return null
            val unit = observation.units.singleOrNull { it.unitId == selectedId } ?: return null
            if (!controllable(unit)) return null
            return observation.factoryQueues.firstOrNull { it.factoryId == unit.unitId }
        }

    private val productionTypeIds: List<String>
        get() = selectedFactoryQueue?.let { queue ->
            val factory = observation.units.single { it.unitId == queue.factoryId }
            scenario.unitDefinitions.getValue(factory.typeId).production?.unitTypeIds
        }.orEmpty()

    val productionPrice: Long?
        get() = productionAction?.price

    val productionAction: ProductionActionView?
        get() = productionActions.firstOrNull()

    val productionActions: List<ProductionActionView>
        get() = productionTypeIds.map { type ->
            val definition = scenario.unitDefinitions.getValue(type)
            val build = checkNotNull(definition.build)
            val queue = checkNotNull(selectedFactoryQueue)
            val affordable = observation.credits?.let { it >= build.price.toDouble() }
                ?: (observation.balance?.let { it >= build.price } == true)
            ProductionActionView(type, unitTitle(type), definition.imagePath,
                definition.totalFrames, build.price, build.rate, definition.maximumHealth, definition.movement.moveSpeed,
                queue.items.count { it.typeId == type }, queue.items.firstOrNull()?.takeIf { it.typeId == type }?.progress, affordable)
        }

    fun produce(typeId: String? = productionTypeIds.firstOrNull()): ActionResult? = typeId?.takeIf { it in productionTypeIds }?.let { type ->
        apply(ProduceAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type,
            UUID.randomUUID().toString().replace("-", ""), context.world))
    }

    fun cancelProduction(typeId: String? = productionTypeIds.firstOrNull()): ActionResult? = typeId?.takeIf { it in productionTypeIds }?.let { type ->
        apply(CancelProductionAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type, context.world))
    }

    val constructionActions: List<ConstructionActionView>
        get() {
            if (!isOpen || selectedIds.isEmpty()) return emptyList()
            val workers = observation.units.filter { it.unitId in selectedIds }
            if (workers.size != selectedIds.size || workers.any { !controllable(it) }) return emptyList()
            val worker = workers.first()
            // Mixed-capability aggregation remains outside the observed builder menu.
            if (workers.any { it.typeId != worker.typeId }) return emptyList()
            return scenario.unitDefinitions.getValue(worker.typeId).builder?.buildingTypeIds.orEmpty().map { type ->
                val definition = scenario.unitDefinitions.getValue(type)
                ConstructionActionView(type, unitTitle(type), definition.imagePath, checkNotNull(definition.construction).price,
                    checkNotNull(definition.maximumHealth), checkNotNull(definition.construction).rate)
            }
        }

    val canMaintain: Boolean
        get() = isOpen && scenario.maintenanceContract != null && selectedIds.isNotEmpty() &&
            observation.units.filter { it.unitId in selectedIds }.let { workers ->
                workers.size == selectedIds.size && workers.all { controllable(it) && it.typeId == "builder" &&
                    scenario.unitDefinitions.getValue(it.typeId).builder != null }
            }

    /** Owner-only queue copy for the ordinary construction panel. */
    val selectedWorkerQueueText: String?
        get() {
            if (constructionActions.isEmpty()) return null
            val workers = observation.units.filter { it.unitId in selectedIds }.sortedBy { it.unitId }
            if (workers.size == 1) {
                val tasks = workers.single().tasks
                val head = tasks.firstOrNull()
                val title = head?.buildingTypeId ?: head?.targetUnitId?.let { target ->
                    observation.units.firstOrNull { it.unitId == target }?.typeId
                }
                return "Tasks ${tasks.size}\n" + (head?.kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Idle") +
                    (title?.let { " ${unitTitle(it)}" } ?: "")
            }
            val counts = workers.map { it.tasks.size }
            val low = counts.min(); val high = counts.max()
            val range = if (low == high) "$low" else "$low-$high"
            val current = workers.groupingBy { it.tasks.firstOrNull()?.kind }.eachCount()
                .entries.sortedBy { it.key?.ordinal ?: -1 }.joinToString("\n") { (kind, count) ->
                    "${kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Idle"} x$count"
                }
            return "Tasks $range each\n$current"
        }

    fun reclaimTargetAt(position: Position): String? {
        if (!hasSelectedMaintainer()) return null
        val id = unitAt(position)
        return observation.units.singleOrNull { it.unitId == id && it.alive && it.ownerPlayerId == "p1" &&
            it.typeId in checkNotNull(scenario.maintenanceContract).basePrices }?.unitId
    }

    fun reclaim(position: Position, append: Boolean = false): ActionResult? = reclaim(reclaimTargetAt(position) ?: "", append)

    fun reclaim(targetUnitId: String, append: Boolean = false): ActionResult? {
        lastCommandBatch = null
        if (!hasSelectedMaintainer()) return null
        val target = observation.units.singleOrNull { it.unitId == targetUnitId && it.alive && it.ownerPlayerId == "p1" &&
            it.typeId in checkNotNull(scenario.maintenanceContract).basePrices }
        if (target == null) {
            feedback = "Select own building"
            rejectedFeedback = false
            return null
        }
        return dispatch { worker -> ReclaimBuildingAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
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
        if (!append && checkNotNull(lastCommandBatch).accepted > 0) selection.clear()
        return result
    }

    /** Dispatch eligibility is independent of the deliberately limited mixed-selection menu. */
    private fun hasSelectedConstructor(typeId: String): Boolean = isOpen && observation.units.any { unit ->
        unit.unitId in selectedIds && controllable(unit) &&
            typeId in scenario.unitDefinitions.getValue(unit.typeId).builder?.buildingTypeIds.orEmpty()
    }

    private fun hasSelectedMaintainer(): Boolean = isOpen && scenario.maintenanceContract != null &&
        observation.units.any { unit -> unit.unitId in selectedIds && controllable(unit) && unit.typeId == "builder" &&
            scenario.unitDefinitions.getValue(unit.typeId).builder != null }

    /** Uses only the player's published terrain and visible buildings, never placement truth. */
    fun previewConstruction(typeId: String, order: Position): ConstructionPreview? {
        if (constructionActions.none { it.typeId == typeId } || !order.x.isFinite() || !order.y.isFinite()) return null
        val map = scenario.map
        val definition = scenario.unitDefinitions.getValue(typeId)
        val position = ConstructionPlacement.snap(order, definition.building?.anchor)
        fun cells(point: Position, building: com.rustedwarfare.reforge.core.content.BuildingProfile): List<Pair<Int, Int>> {
            fun coordinate(value: Double, offset: Float?) = if (offset == null) kotlin.math.floor(value / 20.0).toInt()
                else (((value.toFloat() - offset) + 1f) * .05f).toInt()
            val col = coordinate(point.x, building.anchor?.offsetX)
            val row = coordinate(point.y, building.anchor?.offsetY)
            val shape = building.placementFootprint
            return (shape.top..shape.bottom).flatMap { y -> (shape.left..shape.right).map { x -> col + x to row + y } }
        }
        val building = checkNotNull(definition.building)
        val candidate = cells(position, building)
        var blocked = position.x - definition.radius < 0.0 || position.y - definition.radius < 0.0 ||
            position.x + definition.radius > map.widthTiles * 20.0 || position.y + definition.radius > map.heightTiles * 20.0
        var unknown = false
        for ((col, row) in candidate) {
            if (col !in 0 until map.widthTiles || row !in 0 until map.heightTiles) { blocked = true; continue }
            val gid = frame.terrain[row * map.widthTiles + col].gid
            if (gid == null) { unknown = true; continue }
            val tileset = map.tileSets.lastOrNull { it.firstGid <= gid } ?: continue
            val tile = gid - tileset.firstGid
            if (building.requiresResourcePool) {
                if (tile !in tileset.resourcePoolTileIds) blocked = true
            } else if (tile in tileset.blockedTileIds || tile in tileset.resourcePoolTileIds) blocked = true
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
        get() {
            if (!isOpen || selectedIds.size != 1) return emptyList()
            val unit = observation.units.singleOrNull { it.unitId == selectedId } ?: return emptyList()
            if (!controllable(unit)) return emptyList()
            return scenario.unitDefinitions.getValue(unit.typeId).upgrades?.actions.orEmpty()
        }

    val selectedUpgradeQueue: UpgradeQueueObservation?
        get() = if (selectedUpgrades.isEmpty()) null else observation.upgradeQueues.firstOrNull { it.unitId == selectedId }

    val economicUpgradeTooltip: String?
        get() {
            val action = selectedUpgrades.singleOrNull()?.takeIf { it.actionKey in setOf("upgrade_t2", "upgrade_t3") } ?: return null
            val unit = observation.units.single { it.unitId == selectedId }
            // Original menu labels are nominal times; they do not change simulation rates.
            val seconds = when (unit.typeId) {
                "extractorT1" -> "27.7"
                "extractorT2" -> "55.5"
                "fabricatorT1" -> "65.6"
                "fabricatorT2" -> "93.3"
                else -> return null
            }
            return "Upgrade - T${action.targetTypeId.last()} (\$${action.price})\n" +
                "${seconds}s\n" +
                "Income +\$${nominalIncome(unit.typeId)?.toInt() ?: 0} -> +\$${nominalIncome(action.targetTypeId)?.toInt() ?: 0}\n" +
                (if ((observation.credits ?: 0.0) >= action.price) "Left: upgrade" else "Insufficient funds") + "\nRight: cancel one"
        }

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
        if (hasSelectedMaintainer() && target != null && target.ownerPlayerId == "p1" &&
            target.typeId in checkNotNull(scenario.maintenanceContract).basePrices &&
            (target.constructionProgress < 1f || target.health!! < target.maxHealth!!))
            return dispatch { worker -> RepairBuildingAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
        if (target != null && target.ownerPlayerId == "p1" && target.constructionProgress < 1f &&
            hasSelectedConstructor(target.typeId))
            return dispatch { worker -> ResumeConstructionAction("click-${++actionNumber}", "p1", worker, target.unitId, context.world, append = append) }
        return if (target != null && target.ownerPlayerId != "p1")
            dispatch { actor -> AttackAction("click-${++actionNumber}", "p1", actor, target.unitId, context.world) }
        else move(position)
    }
    fun tick() {
        context.advance(Step(nextStep, 1.0, "sim-tick"))
        nextStep++
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
        evidence.recordWhenEnabled("selection") { mapOf("selectedId" to selectedId, "world" to position.fields()) }
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
            it.position.x in left..right && it.position.y in top..bottom }
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
            val dx = unit.position.x - position.x
            val dy = unit.position.y - position.y
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
                is ResumeConstructionAction -> action.append; is RepairBuildingAction -> action.append;
                is ReclaimBuildingAction -> action.append; else -> null },
            "targetUnitId" to when (action) { is AttackAction -> action.targetUnitId; is ResumeConstructionAction -> action.targetUnitId;
                is RepairBuildingAction -> action.targetUnitId; is ReclaimBuildingAction -> action.targetUnitId; else -> null },
            "upgradeId" to when (action) { is UpgradeAction -> action.upgradeId; is CancelUpgradeAction -> action.upgradeId; else -> null },
            "result" to result.fields()) + if (action is BuildAction) mapOf("buildingTypeId" to action.buildingTypeId) else emptyMap() }
        capture("after-action")
        return result
    }

    private fun capture(phase: String) {
        evidence.recordWhenEnabled("observation") { mapOf("phase" to phase, "sample" to observation.fields()) }
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
            context.close()
            isOpen = false
            selection.clear()
            latestFrame = null
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
