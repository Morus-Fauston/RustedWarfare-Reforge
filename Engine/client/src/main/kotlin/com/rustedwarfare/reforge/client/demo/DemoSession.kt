package com.rustedwarfare.reforge.client.demo

import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.sim.*
import kotlin.math.floor

/** Trusted local demo host; the six-tile circle is not an original-game visibility claim. */
class DemoSession(val scenario: Scenario) : AutoCloseable {
    private val context = when (val initialized = SimulationContext.initialize(scenario)) {
        is InitResult.Accepted -> initialized.context
        is InitResult.Rejected -> error("${initialized.error.code}: ${initialized.error.message}")
    }
    private var selection = linkedSetOf<String>()
    private var actionNumber = 0L
    private var nextStep = 0L
    val observation: Observation get() = context.observe()
    internal fun enableMotionDiagnostics() = context.enableMotionDiagnostics(context.world)
    internal fun drainMotionDiagnostics(): List<MotionStepSample> = context.drainMotionDiagnostics(context.world)
    val knowledge: KnowledgeSnapshot get() = context.knowledgeSnapshot(context.world, "p1")
    val selectedIds: Set<String> get() = selection.toSet()
    var lastTarget: Position? = null
        private set
    var searches = 0
        private set
    var replans = 0
        private set
    val exploredCells: Int get() = knowledge.cells.count { it.lastKnownBlocked != null }
    val visibleCells: Int get() = knowledge.cells.count { it.visible }
    var feedback = "Ready"
        private set
    var isOpen = true
        private set

    init {
        try {
            require(scenario.players.any { it.playerId == "p1" && it.control == PlayerControl.LOCAL }) {
                "Demo requires a locally controlled p1"
            }
            context.enableKnowledge(context.world,
                KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.enablePathDiagnostics(context.world)
            projectVisibility()
            selectAll()
        } catch (error: Throwable) {
            context.close()
            isOpen = false
            throw error
        }
    }

    fun selectAll() {
        if (!isOpen) return
        selection = observation.units.filter { it.ownerPlayerId == "p1" }.mapTo(linkedSetOf()) { it.unitId }
        feedback = "Selected ${selection.size}"
    }

    fun selectAt(position: Position) {
        if (!isOpen) return
        val selected = observation.units.lastOrNull {
            val radius = scenario.unitDefinitions.getValue(it.typeId).displayRadius
            val dx = it.position.x - position.x
            val dy = it.position.y - position.y
            it.ownerPlayerId == "p1" && dx * dx + dy * dy <= radius * radius
        }
        selection = linkedSetOf<String>().apply { selected?.let { add(it.unitId) } }
        feedback = selected?.let { "Selected ${it.unitId}" } ?: "No selection"
    }

    fun moveSelected(position: Position) {
        if (!isOpen) return
        var accepted = 0
        var rejection: String? = null
        for (unitId in selection) {
            when (val result = context.submit(MoveAction("demo-${++actionNumber}", "p1", unitId, position, context.world))) {
                is ActionResult.Accepted -> accepted++
                is ActionResult.Rejected -> rejection = result.error.code
            }
        }
        if (accepted > 0) lastTarget = position
        feedback = rejection ?: if (accepted > 0) "Move $accepted" else "No selection"
        drainSearches()
    }

    fun stopSelected() {
        if (!isOpen) return
        for (unitId in selection) context.submit(StopAction("demo-${++actionNumber}", "p1", unitId, context.world))
        lastTarget = null
        feedback = if (selection.isEmpty()) "No selection" else "Stopped ${selection.size}"
        drainSearches()
    }

    fun tick() {
        if (!isOpen) return
        val sample = context.advance(Step(nextStep, 1.0, "sim-tick"))
        nextStep++
        sample.events.lastOrNull()?.let { feedback = it.kind }
        projectVisibility()
        drainSearches()
    }

    private fun drainSearches() {
        val samples = context.drainPathDiagnostics(context.world)
        searches += samples.sumOf { it.actualSearchCalls.toInt() }
        replans += samples.count { it.reason == PathSearchReason.KNOWLEDGE_REPLAN }
    }

    private fun projectVisibility() {
        val map = scenario.map
        val visible = BooleanArray(map.widthTiles * map.heightTiles)
        val radius = 6.0 * map.tileWidth
        for (unit in observation.units) {
            if (unit.ownerPlayerId != "p1") continue
            val firstColumn = floor((unit.position.x - radius) / map.tileWidth).toInt().coerceAtLeast(0)
            val lastColumn = floor((unit.position.x + radius) / map.tileWidth).toInt().coerceAtMost(map.widthTiles - 1)
            val firstRow = floor((unit.position.y - radius) / map.tileHeight).toInt().coerceAtLeast(0)
            val lastRow = floor((unit.position.y + radius) / map.tileHeight).toInt().coerceAtMost(map.heightTiles - 1)
            for (row in firstRow..lastRow) for (column in firstColumn..lastColumn) {
                val dx = (column + 0.5) * map.tileWidth - unit.position.x
                val dy = (row + 0.5) * map.tileHeight - unit.position.y
                if (dx * dx + dy * dy <= radius * radius) visible[row * map.widthTiles + column] = true
            }
        }
        val projection = mutableListOf<VisibleTerrainCell>()
        for (row in 0 until map.heightTiles) for (column in 0 until map.widthTiles) {
            if (visible[row * map.widthTiles + column])
                projection += VisibleTerrainCell(column, row, map.blocking.isBlocked(column, row))
        }
        // Discoveries from this projection are consumed by KR-R on the next advance.
        context.acceptVisibleTerrain(context.world, "p1", projection)
    }

    override fun close() {
        if (!isOpen) return
        context.close()
        isOpen = false
        selection.clear()
        lastTarget = null
        feedback = "Match closed"
    }
}
