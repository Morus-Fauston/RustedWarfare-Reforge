package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.util.UUID

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

    private val productionTypeId: String?
        get() = selectedFactoryQueue?.let { queue ->
            val factory = observation.units.single { it.unitId == queue.factoryId }
            scenario.unitDefinitions.getValue(factory.typeId).production?.unitTypeId
        }

    val productionPrice: Long?
        get() = productionTypeId?.let { scenario.unitDefinitions.getValue(it).build?.price }

    fun produce(): ActionResult? = productionTypeId?.let { type ->
        apply(ProduceAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type,
            UUID.randomUUID().toString().replace("-", ""), context.world))
    }

    fun cancelProduction(): ActionResult? = productionTypeId?.let { type ->
        apply(CancelProductionAction("click-${++actionNumber}", "p1", checkNotNull(selectedId), type, context.world))
    }

    fun move(position: Position): ActionResult? = dispatch { id ->
        MoveAction("click-${++actionNumber}", "p1", id, position, context.world)
    }
    fun stop(): ActionResult? = dispatch { id ->
        StopAction("click-${++actionNumber}", "p1", id, context.world)
    }

    private fun dispatch(action: (String) -> Action): ActionResult? {
        val members = selectedIds.sorted()
        val results = mutableListOf<SelectionCommandMember>()
        var skipped = 0
        lastCommandBatch = null
        for (id in members) {
            if (members.size > 1 && observation.units.none { it.unitId == id && controllable(it) }) { skipped++; continue }
            results += SelectionCommandMember(id, apply(action(id)))
        }
        val batch = SelectionCommandBatch(results, skipped)
        lastCommandBatch = batch
        if (members.size > 1) {
            feedback = "Accepted ${batch.accepted}, rejected ${batch.rejected}, skipped ${batch.skipped}" +
                results.mapNotNull { (it.result as? ActionResult.Rejected)?.error?.code }.distinct().joinToString(", ", prefix = "; ")
            rejectedFeedback = batch.rejected > 0
            evidence.recordWhenEnabled("selection-command") { mapOf("accepted" to batch.accepted,
                "rejected" to batch.rejected, "skipped" to batch.skipped,
                "members" to results.map { mapOf("unitId" to it.unitId, "result" to it.result.fields()) }) }
        }
        return results.lastOrNull()?.result
    }

    private fun controllable(unit: UnitObservation): Boolean = unit.alive && unit.ownerPlayerId == "p1" &&
        scenario.players.any { it.playerId == "p1" && it.control == PlayerControl.LOCAL }
    fun command(position: Position): ActionResult? {
        val targetId = unitAt(position)
        val target = observation.units.firstOrNull { it.unitId == targetId }
        return if (target != null && target.ownerPlayerId != "p1")
            dispatch { actor -> AttackAction("click-${++actionNumber}", "p1", actor, target.unitId, context.world) }
        else move(position)
    }
    fun tick() {
        context.advance(Step(nextStep, 1.0, "sim-tick"))
        nextStep++
        publishFrame()
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
            "target" to (action as? MoveAction)?.target?.fields(),
            "targetUnitId" to (action as? AttackAction)?.targetUnitId, "result" to result.fields()) }
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
            observation.units.filter { !it.alive }.map { it.unitId }.toSet())
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
}
