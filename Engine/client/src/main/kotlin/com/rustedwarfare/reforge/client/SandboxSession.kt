package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*
import java.util.UUID

class SandboxSession(scenario: Scenario, private val evidence: ClientEvidence = ClientEvidence.NONE,
                     val options: SandboxOptions = SandboxOptions()) : AutoCloseable {
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
    var selectedId: String? = null
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
        get() = if (!isOpen) null else observation.factoryQueues.firstOrNull { it.factoryId == selectedId }

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

    fun select(position: Position) {
        rejectedFeedback = false
        selectedId = unitAt(position)
        selectedId?.let { apply(SelectAction("click-${++actionNumber}", "p1", it, context.world)) }
        feedback = selectedId?.let { "Selected $it" } ?: "No selection"
        evidence.recordWhenEnabled("selection") { mapOf("selectedId" to selectedId, "world" to position.fields()) }
    }

    fun tap(position: Position) {
        if (unitAt(position) != null) select(position) else move(position)
    }

    private fun unitAt(position: Position): String? = observation.units.lastOrNull {
            if (!it.alive) return@lastOrNull false
            val radius = renderResources.units.getValue(it.typeId).displayRadius
            val dx = it.position.x - position.x
            val dy = it.position.y - position.y
            dx * dx + dy * dy <= radius * radius
        }?.unitId

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
        if (selectedId != null && observation.units.none { it.unitId == selectedId && it.alive }) {
            selectedId = null
            feedback = "No selection"
        }
    }

    override fun close() {
        if (!isOpen) return
        try { capture("before-close") }
        finally {
            context.close()
            isOpen = false
            selectedId = null
            latestFrame = null
            terrainKnowledge = null
            feedback = "Match closed"
        }
        evidence.record("closed", emptyMap())
    }
}
