package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.*

class SandboxSession(val scenario: Scenario, private val evidence: ClientEvidence = ClientEvidence.NONE) : AutoCloseable {
    private val context = when (val initialized = SimulationContext.initialize(scenario)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    val observation: Observation get() = context.observe()
    var selectedId: String? = null
        private set

    private var actionNumber = 0L
    private var nextStep = 0L
    var isOpen = true
        private set
    var feedback = "Ready"
        private set

    init {
        try { capture("initial") }
        catch (failure: Exception) { context.close(); isOpen = false; throw failure }
    }

    fun move(position: Position): ActionResult? = selectedId?.let {
        apply(MoveAction("click-${++actionNumber}", "p1", it, position, context.world))
    }
    fun stop(): ActionResult? = selectedId?.let {
        apply(StopAction("click-${++actionNumber}", "p1", it, context.world))
    }
    fun tick() {
        val sample = context.advance(Step(nextStep, 1.0, "sim-tick"))
        nextStep++
        sample.events.lastOrNull()?.let { feedback = it.kind }
        capture("step")
    }

    fun select(position: Position) {
        selectedId = unitAt(position)
        selectedId?.let { apply(SelectAction("click-${++actionNumber}", "p1", it, context.world)) }
        feedback = selectedId?.let { "Selected $it" } ?: "No selection"
        evidence.record("selection", mapOf("selectedId" to selectedId, "world" to position.fields()))
    }

    fun tap(position: Position) {
        if (unitAt(position) != null) select(position) else move(position)
    }

    private fun unitAt(position: Position): String? = observation.units.lastOrNull {
            val radius = scenario.unitDefinitions.getValue(it.typeId).displayRadius
            val dx = it.position.x - position.x
            val dy = it.position.y - position.y
            dx * dx + dy * dy <= radius * radius
        }?.unitId

    private fun apply(action: Action): ActionResult {
        capture("before-action")
        val result = context.submit(action)
        feedback = when (result) {
            is ActionResult.Rejected -> result.error.code
            is ActionResult.Accepted -> result.events.lastOrNull()?.kind ?: "Accepted"
        }
        evidence.record("action", mapOf("type" to action.javaClass.simpleName,
            "actorPlayerId" to action.actorPlayerId, "unitId" to action.unitId,
            "target" to (action as? MoveAction)?.target?.fields(), "result" to result.fields()))
        capture("after-action")
        return result
    }

    private fun capture(phase: String) { evidence.record("observation", mapOf("phase" to phase, "sample" to observation.fields())) }

    override fun close() {
        if (!isOpen) return
        try { capture("before-close") }
        finally {
            context.close()
            isOpen = false
            selectedId = null
            feedback = "Match closed"
        }
        evidence.record("closed", emptyMap())
    }
}
