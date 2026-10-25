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
        get() = if (!isOpen || selectedIds.size != 1) null else observation.factoryQueues.firstOrNull { it.factoryId == selectedId }

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

    fun move(position: Position): ActionResult? = selectedId?.let {
        apply(MoveAction("click-${++actionNumber}", "p1", it, position, context.world))
    }
    fun stop(): ActionResult? = selectedId?.let {
        apply(StopAction("click-${++actionNumber}", "p1", it, context.world))
    }
    fun moveSelected(position: Position): SelectionCommandResult {
        if (!isOpen || !insideMap(position)) return SelectionCommandResult(emptyList(), selectedIds.sorted())
        return commandSelection { actionId, id -> MoveAction(actionId, "p1", id, position, context.world) }
    }
    fun stopSelected(): SelectionCommandResult = commandSelection { actionId, id ->
        StopAction(actionId, "p1", id, context.world)
    }
    fun commandSelected(position: Position): SelectionCommandResult {
        if (!isOpen || !insideMap(position)) return SelectionCommandResult(emptyList(), selectedIds.sorted())
        val targetId = unitAt(position)
        val target = observation.units.firstOrNull { it.unitId == targetId && it.alive && it.ownerPlayerId != "p1" }
        return if (target == null) moveSelected(position) else commandSelection { actionId, id ->
            AttackAction(actionId, "p1", id, target.unitId, context.world)
        }
    }

    private fun commandSelection(action: (String, String) -> Action): SelectionCommandResult {
        val ids = selectedIds.sorted()
        val members = mutableListOf<SelectionMemberResult>()
        val skipped = mutableListOf<String>()
        for (id in ids) {
            val unit = if (isOpen) observation.units.firstOrNull { it.unitId == id && it.alive } else null
            if (unit == null || unit.ownerPlayerId != "p1" ||
                scenario.players.none { it.playerId == unit.ownerPlayerId && it.control == PlayerControl.LOCAL }) {
                skipped += id
                continue
            }
            val actionId = "click-${++actionNumber}"
            members += SelectionMemberResult(id, actionId, apply(action(actionId, id)))
        }
        val result = SelectionCommandResult(members, skipped)
        val errors = members.mapNotNull { (it.result as? ActionResult.Rejected)?.error?.code }.distinct()
        feedback = "${result.accepted} accepted, ${result.rejected} rejected, ${skipped.size} skipped" +
            if (errors.isEmpty()) "" else ": ${errors.joinToString()}"
        rejectedFeedback = true
        evidence.recordWhenEnabled("selection-command") { mapOf("selectedIds" to ids,
            "accepted" to result.accepted, "rejected" to result.rejected, "skippedIds" to skipped,
            "members" to members.map { mapOf("unitId" to it.unitId, "actionId" to it.actionId, "result" to it.result.fields()) }) }
        return result
    }
    fun command(position: Position): ActionResult? {
        val actor = selectedId ?: return null
        val targetId = unitAt(position)
        val target = observation.units.firstOrNull { it.unitId == targetId }
        return if (target != null && target.ownerPlayerId != "p1")
            apply(AttackAction("click-${++actionNumber}", "p1", actor, target.unitId, context.world))
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
        if (!isOpen || !insideMap(position)) return
        rejectedFeedback = false
        val id = unitAt(position)
        if (id == null) selection.select(emptyList(), mode)
        else if (apply(SelectAction("click-${++actionNumber}", "p1", id, context.world)) is ActionResult.Accepted) {
            selection.select(listOf(id), mode)
        }
        feedback = selectedId?.let { "Selected $it" } ?: "No selection"
        evidence.recordWhenEnabled("selection") { mapOf("selectedId" to selectedId,
            "selectedIds" to selectedIds.toList(), "world" to position.fields()) }
    }

    fun selectBox(start: Position, end: Position, mode: SelectionMode = SelectionMode.REPLACE,
                  baseSelection: Set<String> = selectedIds) {
        if (!isOpen || listOf(start.x, start.y, end.x, end.y).any { !it.isFinite() }) return
        val left = minOf(start.x, end.x).coerceAtLeast(0.0)
        val top = minOf(start.y, end.y).coerceAtLeast(0.0)
        val right = maxOf(start.x, end.x).coerceAtMost(scenario.map.widthTiles.toDouble() * scenario.map.tileWidth)
        val bottom = maxOf(start.y, end.y).coerceAtMost(scenario.map.heightTiles.toDouble() * scenario.map.tileHeight)
        val local = scenario.players.any { it.playerId == "p1" && it.control == PlayerControl.LOCAL }
        val candidates = observation.units.filter { local && it.alive && it.ownerPlayerId == "p1" &&
            it.position.x in left..right && it.position.y in top..bottom }.sortedBy { it.unitId }
        val mobile = candidates.filter { scenario.unitDefinitions.getValue(it.typeId).movement.type !=
            com.rustedwarfare.reforge.core.content.MovementType.NONE }
        val accepted = (mobile.ifEmpty { candidates }).filter {
            apply(SelectAction("click-${++actionNumber}", "p1", it.unitId, context.world)) is ActionResult.Accepted
        }.map { it.unitId }
        val visible = observation.units.filter { it.alive }.mapTo(hashSetOf()) { it.unitId }
        selection.select(baseSelection.filter { it in visible })
        selection.select(accepted, mode)
        feedback = "${selectedIds.size} selected"
        rejectedFeedback = true
        evidence.recordWhenEnabled("box-selection") { mapOf("selectedIds" to selectedIds.toList(),
            "start" to start.fields(), "end" to end.fields(), "mode" to mode.name) }
    }

    fun tap(position: Position) {
        if (unitAt(position) != null) select(position) else move(position)
    }

    fun storeGroup(index: Int) {
        if (!isOpen) return
        selection.saveGroup(index)
        feedback = "Stored group ${if (index == 9) 0 else index + 1} (${selectedIds.size})"
        rejectedFeedback = true
    }

    fun recallGroup(index: Int, additive: Boolean = false): Position? {
        if (!isOpen) return null
        val eligible = selection.groupMembers(index).filterTo(linkedSetOf()) { id ->
            observation.units.any { it.unitId == id && it.alive } &&
                apply(SelectAction("click-${++actionNumber}", "p1", id, context.world)) is ActionResult.Accepted
        }
        val focusId = selection.recallGroup(index, eligible, additive)
        val focus = observation.units.firstOrNull { it.unitId == focusId && it.alive }?.position
        feedback = "Group ${if (index == 9) 0 else index + 1}: ${selectedIds.size} selected"
        rejectedFeedback = true
        evidence.recordWhenEnabled("group-recall") { mapOf("group" to index, "additive" to additive,
            "selectedIds" to selectedIds.toList(), "focus" to focus?.fields()) }
        return focus
    }

    fun visibleGroupSize(index: Int): Int = if (!isOpen) 0 else selection.groupMembers(index).count { id ->
        observation.units.any { it.unitId == id && it.alive }
    }

    private fun insideMap(position: Position): Boolean = position.x.isFinite() && position.y.isFinite() &&
        position.x >= 0.0 && position.y >= 0.0 &&
        position.x < scenario.map.widthTiles.toDouble() * scenario.map.tileWidth &&
        position.y < scenario.map.heightTiles.toDouble() * scenario.map.tileHeight

    private fun unitAt(position: Position): String? = observation.units.filter {
            if (!it.alive) return@filter false
            val radius = renderResources.units.getValue(it.typeId).displayRadius
            val dx = it.position.x - position.x
            val dy = it.position.y - position.y
            dx * dx + dy * dy <= radius * radius
        }.minWithOrNull(compareBy<UnitObservation> {
            val dx = it.position.x - position.x
            val dy = it.position.y - position.y
            dx * dx + dy * dy
        }.thenBy { it.unitId })?.unitId

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
        val hadSelection = selectedId != null
        selection.reconcile(observation.units.filter { it.alive }.mapTo(hashSetOf()) { it.unitId },
            observation.units.filter { !it.alive }.mapTo(hashSetOf()) { it.unitId })
        if (hadSelection && selectedId == null) feedback = "No selection"
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
