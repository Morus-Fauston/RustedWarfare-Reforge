package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.contactBufferOutline
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure Kotlin diagnostic geometry projector (ADR-012 / ENG-P016-001).
 *
 * Consumes committed authoritative [Observation] or player-authorized [PlayerObservation]
 * frames without re-simulation, interpolation, or graphics toolkit dependencies.
 * Feeds both the LibGDX F3 live overlay and the headless BufferedImage offline contact sheet.
 */
data class DiagnosticUnitPrimitive(
    val unitId: String,
    val ownerPlayerId: String,
    val typeId: String,
    val center: Position,
    val heading: Double,
    val shape: MovementCollisionShape,
    val hullCorners: List<Position>,
    val boundingRadius: Double,
    val bufferShape: MovementCollisionShape?,
    val bufferCorners: List<Position>,
    val bufferRadius: Double?,
    val headingTip: Position,
    val velocityVector: Position,
    val guidanceDirection: Position?,
    val route: List<Position>,
    val target: Position?,
    val arrived: Boolean,
    val stalledHighlight: Boolean,
    val windowDisplacement: Double?,
)

data class DiagnosticFrameScene(
    val sampleId: String,
    val stepIndex: Long,
    val simulationTime: Double,
    val elapsedSeconds: Double,
    val units: List<DiagnosticUnitPrimitive>,
    val activeMoveCount: Int,
    val arrivedCount: Int,
    val stalledCount: Int,
    val hardContactCount: Int,
)

enum class DiagnosticKeyframeKind(val label: String) {
    INTERVAL("2s-interval"),
    FIRST_MOVE("first-move"),
    FIRST_CONTACT("first-contact"),
    PEAK_CONGESTION("peak-congestion"),
    LAST_ARRIVAL("last-arrival"),
    FINAL("final"),
}

data class DiagnosticKeyframe(
    val frame: DiagnosticFrameScene,
    val reasons: Set<DiagnosticKeyframeKind>,
)

data class DiagnosticFocusRegion(
    val sampleId: String,
    val stepIndex: Long,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val stalledCount: Int,
    val hardContactCount: Int,
)

data class DiagnosticSequenceProjection(
    val frames: List<DiagnosticFrameScene>,
    val keyframes: List<DiagnosticKeyframe>,
    val peakCongestionFocus: DiagnosticFocusRegion?,
    val firstAutonomousMoveStep: Long?,
    val firstHardContactStep: Long?,
    val peakCongestionStep: Long?,
    val lastArrivalStep: Long?,
)

object DiagnosticSceneProjector {
    const val DEFAULT_STALL_WINDOW_STEPS: Int = 120
    const val DEFAULT_STALL_MIN_RADIUS_DISP: Double = 8.0
    const val DEFAULT_STALL_SPEED_FRACTION: Double = 0.12
    const val STEPS_PER_SECOND: Double = 60.0

    fun isInitialSample(sampleId: String): Boolean = sampleId.contains(":initial:")

    fun elapsedSecondsFor(sampleId: String, stepIndex: Long): Double =
        if (isInitialSample(sampleId)) 0.0 else (stepIndex + 1L) / STEPS_PER_SECOND

    fun mobileShapeForView(unit: UnitObservation, definitions: Map<String, UnitDefinition>): MovementCollisionShape? {
        if (!unit.alive || unit.carriedBy != null || unit.attachment != null) return null
        val definition = definitions[unit.typeId] ?: return null
        // The projected attachment relation can be hidden when the parent is outside vision.
        return definition.movementCollision.takeIf {
            definition.building == null && definition.attachmentModule == null && it.boundingRadius > 0.0
        }
    }

    fun hasContactLayerForView(unit: UnitObservation, definitions: Map<String, UnitDefinition>): Boolean =
        definitions[unit.typeId]?.hasContactLayerAt(unit.height) == true

    fun projectFrame(
        observation: Observation,
        definitions: Map<String, UnitDefinition>,
        arrivedUnitIds: Set<String> = emptySet(),
        stalledUnitIds: Set<String> = emptySet(),
        windowDisplacements: Map<String, Double> = emptyMap(),
    ): DiagnosticFrameScene = projectUnits(
        sampleId = observation.sampleId,
        stepIndex = observation.stepIndex,
        simulationTime = observation.simulationTime,
        units = observation.units,
        definitions = definitions,
        arrivedUnitIds = arrivedUnitIds,
        stalledUnitIds = stalledUnitIds,
        windowDisplacements = windowDisplacements,
    )

    fun projectPlayerFrame(
        observation: PlayerObservation,
        definitions: Map<String, UnitDefinition>,
        arrivedUnitIds: Set<String> = emptySet(),
        stalledUnitIds: Set<String> = emptySet(),
        windowDisplacements: Map<String, Double> = emptyMap(),
    ): DiagnosticFrameScene = projectUnits(
        sampleId = observation.sampleId,
        stepIndex = observation.stepIndex,
        simulationTime = observation.simulationTime,
        units = observation.units,
        definitions = definitions,
        arrivedUnitIds = arrivedUnitIds,
        stalledUnitIds = stalledUnitIds,
        windowDisplacements = windowDisplacements,
    )

    fun projectUnits(
        sampleId: String,
        stepIndex: Long,
        simulationTime: Double,
        units: List<UnitObservation>,
        definitions: Map<String, UnitDefinition>,
        arrivedUnitIds: Set<String> = emptySet(),
        stalledUnitIds: Set<String> = emptySet(),
        windowDisplacements: Map<String, Double> = emptyMap(),
    ): DiagnosticFrameScene {
        var activeMoveCount = 0
        val primitives = units.mapNotNull { unit ->
            val shape = mobileShapeForView(unit, definitions) ?: return@mapNotNull null
            if (hasActiveMovementIntent(unit)) activeMoveCount++
            val radians = Math.toRadians(unit.heading)
            val forwardX = sin(radians)
            val forwardY = -cos(radians)

            fun worldOffset(forward: Double, right: Double = 0.0): Position = Position(
                unit.position.x + forwardX * forward - forwardY * right,
                unit.position.y + forwardY * forward + forwardX * right,
            )

            fun corners(outline: MovementCollisionShape): List<Position> = when (outline) {
                is MovementCollisionShape.Circle -> emptyList()
                is MovementCollisionShape.Rectangle -> {
                    val l = outline.length / 2.0
                    val w = outline.width / 2.0
                    listOf(worldOffset(l, w), worldOffset(l, -w), worldOffset(-l, -w), worldOffset(-l, w))
                }
            }

            val nose = when (shape) {
                is MovementCollisionShape.Circle -> shape.radius
                is MovementCollisionShape.Rectangle -> shape.length / 2.0
            }
            val hasContact = hasContactLayerForView(unit, definitions)
            val buffer = if (hasContact) shape.contactBufferOutline() else null
            val velocity = computeVelocityVector(unit, forwardX, forwardY)
            val guidance = computeGuidanceDirection(unit)
            val arrived = unit.unitId in arrivedUnitIds || (unit.moveArrival != null && unit.moveTarget == null)
            val stalled = unit.unitId in stalledUnitIds

            DiagnosticUnitPrimitive(
                unitId = unit.unitId,
                ownerPlayerId = unit.ownerPlayerId,
                typeId = unit.typeId,
                center = unit.position,
                heading = unit.heading,
                shape = shape,
                hullCorners = corners(shape),
                boundingRadius = shape.boundingRadius,
                bufferShape = buffer,
                bufferCorners = buffer?.let(::corners).orEmpty(),
                bufferRadius = buffer?.boundingRadius,
                headingTip = worldOffset(nose),
                velocityVector = velocity,
                guidanceDirection = guidance,
                route = unit.pathPoints,
                target = unit.moveTarget,
                arrived = arrived,
                stalledHighlight = stalled,
                windowDisplacement = windowDisplacements[unit.unitId],
            )
        }

        val contactCount = countHardContactPairs(units, definitions)
        val arrivedCount = primitives.count { it.arrived }
        val stalledCount = primitives.count { it.stalledHighlight }

        return DiagnosticFrameScene(
            sampleId = sampleId,
            stepIndex = stepIndex,
            simulationTime = simulationTime,
            elapsedSeconds = elapsedSecondsFor(sampleId, stepIndex),
            units = primitives,
            activeMoveCount = activeMoveCount,
            arrivedCount = arrivedCount,
            stalledCount = stalledCount,
            hardContactCount = contactCount,
        )
    }

    /**
     * Stateful tracker for live F3 overlay or streaming step consumption.
     * Separating [ingest] (O(U) history maintenance per committed step) from [project] (on-demand geometry)
     * guarantees zero O(N^2) work when F3 is hidden or when synchronous selection/command actions occur.
     */
    class LiveTracker(
        private val definitions: Map<String, UnitDefinition>,
        private val windowSteps: Int = DEFAULT_STALL_WINDOW_STEPS,
    ) {
        private data class HistorySample(val tickPoint: Long, val position: Position, val orderIdentity: String)

        private val historyByUnit = HashMap<String, ArrayDeque<HistorySample>>()
        private val arrivedUnits = HashSet<String>()
        private var lastIngestedSampleKey: String? = null
        private var lastTickPoint: Long? = null
        private var cachedProjectedSource: Any? = null
        private var cachedScene: DiagnosticFrameScene? = null

        fun reset() {
            historyByUnit.clear()
            arrivedUnits.clear()
            lastIngestedSampleKey = null
            lastTickPoint = null
            cachedProjectedSource = null
            cachedScene = null
        }

        fun ingest(observation: Observation) {
            ingestFrame(
                sampleId = observation.sampleId,
                stepIndex = observation.stepIndex,
                units = observation.units,
                events = observation.events,
                viewerPlayerId = null,
            )
        }

        fun ingestPlayer(observation: PlayerObservation) {
            ingestFrame(
                sampleId = observation.sampleId,
                stepIndex = observation.stepIndex,
                units = observation.units,
                events = observation.events,
                viewerPlayerId = observation.playerId,
            )
        }

        fun updateAndProject(observation: Observation): DiagnosticFrameScene {
            if (cachedProjectedSource === observation && cachedScene != null) return cachedScene!!
            ingest(observation)
            val tickPoint = tickPointOf(observation.sampleId, observation.stepIndex)
            val (stalledSet, displacements) = currentStalledSet(tickPoint, observation.units)
            return projectUnits(
                sampleId = observation.sampleId,
                stepIndex = observation.stepIndex,
                simulationTime = observation.simulationTime,
                units = observation.units,
                definitions = definitions,
                arrivedUnitIds = arrivedUnits.toSet(),
                stalledUnitIds = stalledSet,
                windowDisplacements = displacements,
            ).also {
                cachedProjectedSource = observation
                cachedScene = it
            }
        }

        fun updateAndProjectPlayer(observation: PlayerObservation): DiagnosticFrameScene {
            if (cachedProjectedSource === observation && cachedScene != null) return cachedScene!!
            ingestPlayer(observation)
            val tickPoint = tickPointOf(observation.sampleId, observation.stepIndex)
            val (stalledSet, displacements) = currentStalledSet(tickPoint, observation.units)
            return projectUnits(
                sampleId = observation.sampleId,
                stepIndex = observation.stepIndex,
                simulationTime = observation.simulationTime,
                units = observation.units,
                definitions = definitions,
                arrivedUnitIds = arrivedUnits.toSet(),
                stalledUnitIds = stalledSet,
                windowDisplacements = displacements,
            ).also {
                cachedProjectedSource = observation
                cachedScene = it
            }
        }

        private fun tickPointOf(sampleId: String, stepIndex: Long): Long =
            if (isInitialSample(sampleId)) -1L else stepIndex

        private fun ingestFrame(
            sampleId: String,
            stepIndex: Long,
            units: List<UnitObservation>,
            events: List<Event>,
            viewerPlayerId: String?,
        ) {
            val tickPoint = tickPointOf(sampleId, stepIndex)
            val sampleKey = (if (isInitialSample(sampleId)) "init:" else "step:") + stepIndex
            if (lastTickPoint != null && tickPoint < lastTickPoint!!) {
                reset()
            }
            val sameStepReingest = (lastIngestedSampleKey == sampleKey)
            lastIngestedSampleKey = sampleKey
            lastTickPoint = tickPoint
            cachedProjectedSource = null
            cachedScene = null

            val unitMap = HashMap<String, UnitObservation>(units.size * 2)
            for (u in units) unitMap[u.unitId] = u

            for (ev in events) {
                val id = ev.unitId ?: continue
                val u = unitMap[id]
                // In PlayerObservation mode, only track persistent arrival state for the viewer's own units
                // because enemy tasks/moveTarget are stripped by fog-of-war projection.
                val trackArrival = (viewerPlayerId == null) || (u?.ownerPlayerId == viewerPlayerId)
                when (ev.kind) {
                    "movement-arrived" -> if (trackArrival && u?.alive == true) arrivedUnits.add(id)
                    "movement-started" -> arrivedUnits.remove(id)
                }
            }

            val aliveVisibleIds = HashSet<String>(units.size)
            for (unit in units) {
                if (!unit.alive) {
                    arrivedUnits.remove(unit.unitId)
                    historyByUnit.remove(unit.unitId)
                    continue
                }
                aliveVisibleIds.add(unit.unitId)
                if (viewerPlayerId != null && unit.ownerPlayerId != viewerPlayerId) {
                    arrivedUnits.remove(unit.unitId)
                }
                val orderKey = activeOrderIdentity(unit)
                if (orderKey != null) {
                    arrivedUnits.remove(unit.unitId)
                    val deque = historyByUnit.getOrPut(unit.unitId) { ArrayDeque(windowSteps + 2) }
                    if (deque.isNotEmpty() && deque.last().orderIdentity != orderKey) {
                        deque.clear()
                    }
                    if (sameStepReingest && deque.isNotEmpty() && deque.last().tickPoint == tickPoint) {
                        deque.removeLast()
                    }
                    deque.addLast(HistorySample(tickPoint, unit.position, orderKey))
                    while (deque.size > 1 && tickPoint - deque.first().tickPoint > windowSteps) {
                        deque.removeFirst()
                    }
                } else {
                    historyByUnit.remove(unit.unitId)
                    if (unit.speed > 1e-4 || unit.movementState == MovementState.MOVING) {
                        arrivedUnits.remove(unit.unitId)
                    }
                }
            }
            historyByUnit.keys.retainAll(aliveVisibleIds)
            arrivedUnits.retainAll(aliveVisibleIds)
        }

        private fun currentStalledSet(
            tickPoint: Long,
            units: List<UnitObservation>,
        ): Pair<Set<String>, Map<String, Double>> {
            val stalled = HashSet<String>()
            val displacements = HashMap<String, Double>()
            for (unit in units) {
                if (mobileShapeForView(unit, definitions) == null) continue
                if (activeOrderIdentity(unit) == null) continue
                val deque = historyByUnit[unit.unitId] ?: continue
                val oldest = deque.firstOrNull() ?: continue
                val span = tickPoint - oldest.tickPoint
                val disp = hypot(unit.position.x - oldest.position.x, unit.position.y - oldest.position.y)
                displacements[unit.unitId] = disp
                if (span >= windowSteps) {
                    val threshold = stallDisplacementThreshold(unit, definitions[unit.typeId], windowSteps)
                    if (disp < threshold) {
                        stalled.add(unit.unitId)
                    }
                }
            }
            return stalled to displacements
        }
    }

    fun stallDisplacementThreshold(
        unit: UnitObservation,
        definition: UnitDefinition?,
        windowSteps: Int = DEFAULT_STALL_WINDOW_STEPS,
    ): Double {
        val radius = definition?.movementCollision?.boundingRadius ?: 6.0
        val maxSpeed = definition?.movement?.moveSpeed ?: 0.0
        val byRadius = max(DEFAULT_STALL_MIN_RADIUS_DISP, 1.5 * radius)
        val bySpeed = maxSpeed * windowSteps * DEFAULT_STALL_SPEED_FRACTION
        return max(byRadius, bySpeed)
    }

    fun projectSequence(
        observations: List<Observation>,
        definitions: Map<String, UnitDefinition>,
        intervalSteps: Int = DEFAULT_STALL_WINDOW_STEPS,
        stallWindowSteps: Int = DEFAULT_STALL_WINDOW_STEPS,
    ): DiagnosticSequenceProjection {
        require(intervalSteps > 0 && stallWindowSteps > 0) { "Interval and stall window steps must be positive" }
        if (observations.isEmpty()) {
            return DiagnosticSequenceProjection(emptyList(), emptyList(), null, null, null, null, null)
        }

        val tracker = LiveTracker(definitions, stallWindowSteps)
        val initialPos = HashMap<String, Position>()
        observations.first().units.forEach { initialPos[it.unitId] = it.position }

        var firstMoveStep: Long? = null
        var firstPublishedStep: Long? = null
        var firstContactStep: Long? = null
        var lastArrivalStep: Long? = null

        val frames = observations.map { obs ->
            val frame = tracker.updateAndProject(obs)
            if (!isInitialSample(obs.sampleId)) {
                if (firstPublishedStep == null && obs.events.any { it.kind == "movement-started" }) {
                    firstPublishedStep = obs.stepIndex
                }
                if (firstMoveStep == null) {
                    val physicallyMoved = obs.units.any { u ->
                        mobileShapeForView(u, definitions) != null &&
                            (computeSpeedMagnitude(u) > 1e-4 ||
                                initialPos[u.unitId]?.let { p -> hypot(u.position.x - p.x, u.position.y - p.y) > 1e-3 } == true)
                    }
                    if (physicallyMoved) firstMoveStep = obs.stepIndex
                }
                if (firstContactStep == null && frame.hardContactCount > 0) {
                    firstContactStep = obs.stepIndex
                }
                if (obs.events.any { it.kind == "movement-arrived" }) {
                    lastArrivalStep = obs.stepIndex
                }
            }
            frame
        }

        val effectiveFirstMoveStep = firstMoveStep ?: firstPublishedStep

        val peakFrame = frames
            .filter { !isInitialSample(it.sampleId) && (it.stalledCount > 0 || it.hardContactCount > 0) }
            .maxWithOrNull(compareBy<DiagnosticFrameScene>(
                { it.stalledCount + it.hardContactCount * 2 },
                { it.stalledCount },
                { it.hardContactCount },
                { -it.stepIndex },
            ))
        val peakStep = peakFrame?.stepIndex

        val hasInitialAndStepZero = frames.size >= 2 && isInitialSample(frames[0].sampleId) &&
            !isInitialSample(frames[1].sampleId) && frames[1].stepIndex == 0L
        val baseStep = frames.first { !isInitialSample(it.sampleId) || !hasInitialAndStepZero }.stepIndex

        val keyframes = frames.mapIndexedNotNull { idx, frame ->
            if (hasInitialAndStepZero && idx == 0) return@mapIndexedNotNull null
            val reasons = LinkedHashSet<DiagnosticKeyframeKind>()
            if ((frame.stepIndex - baseStep) % intervalSteps == 0L) reasons.add(DiagnosticKeyframeKind.INTERVAL)
            if (!isInitialSample(frame.sampleId)) {
                if (frame.stepIndex == effectiveFirstMoveStep) reasons.add(DiagnosticKeyframeKind.FIRST_MOVE)
                if (frame.stepIndex == firstContactStep) reasons.add(DiagnosticKeyframeKind.FIRST_CONTACT)
                if (peakFrame != null && frame.sampleId == peakFrame.sampleId && frame.stepIndex == peakStep) {
                    reasons.add(DiagnosticKeyframeKind.PEAK_CONGESTION)
                }
                if (frame.stepIndex == lastArrivalStep) reasons.add(DiagnosticKeyframeKind.LAST_ARRIVAL)
            }
            if (idx == frames.lastIndex && reasons.isEmpty()) reasons.add(DiagnosticKeyframeKind.FINAL)
            if (reasons.isEmpty()) null else DiagnosticKeyframe(frame, reasons)
        }

        val focus = peakFrame?.let { pf ->
            val obs = observations.first { o -> o.sampleId == pf.sampleId && o.stepIndex == pf.stepIndex }
            computeFocusRegion(pf, obs, definitions)
        }

        return DiagnosticSequenceProjection(
            frames = frames,
            keyframes = keyframes,
            peakCongestionFocus = focus,
            firstAutonomousMoveStep = effectiveFirstMoveStep,
            firstHardContactStep = firstContactStep,
            peakCongestionStep = peakStep,
            lastArrivalStep = lastArrivalStep,
        )
    }

    private fun computeFocusRegion(
        frame: DiagnosticFrameScene,
        observation: Observation,
        definitions: Map<String, UnitDefinition>,
    ): DiagnosticFocusRegion? {
        val stalledUnits = frame.units.filter { it.stalledHighlight }
        val focusUnits = stalledUnits.ifEmpty {
            val involvedIds = findHardContactUnitIds(observation.units, definitions)
            frame.units.filter { it.unitId in involvedIds }
        }
        if (focusUnits.isEmpty()) return null
        val margin = max(80.0, focusUnits.maxOf { it.boundingRadius } * 6.0)
        val minX = focusUnits.minOf { it.center.x - it.boundingRadius } - margin
        val maxX = focusUnits.maxOf { it.center.x + it.boundingRadius } + margin
        val minY = focusUnits.minOf { it.center.y - it.boundingRadius } - margin
        val maxY = focusUnits.maxOf { it.center.y + it.boundingRadius } + margin
        return DiagnosticFocusRegion(
            sampleId = frame.sampleId,
            stepIndex = frame.stepIndex,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            stalledCount = frame.stalledCount,
            hardContactCount = frame.hardContactCount,
        )
    }

    /**
     * Stable player/command identity for 2-second stall window tracking.
     *
     * Anchors on `head.kind + head.actionId` whenever a task exists so internal slot claims,
     * entrance swaps, nearest-reachable fallbacks, or pursuit target coordinate updates under
     * the SAME player command never reset the 120-step stall window. Only an actual command
     * change (new `actionId`) or completion clears the window.
     */
    internal fun activeOrderIdentity(unit: UnitObservation): String? {
        val head = unit.tasks.firstOrNull()
        if (head != null) {
            return when (head.kind) {
                UnitTaskKind.MOVE, UnitTaskKind.PATROL -> "${head.kind}:${head.actionId}"
                else -> if (unit.moveTarget != null) "${head.kind}:${head.actionId}" else null
            }
        }
        val moveTarget = unit.moveTarget
        if (moveTarget != null) {
            return "DIRECT:${moveTarget.x}:${moveTarget.y}"
        }
        return null
    }

    private fun hasActiveMovementIntent(unit: UnitObservation): Boolean =
        activeOrderIdentity(unit) != null

    private fun computeSpeedMagnitude(unit: UnitObservation): Double {
        unit.flightPose?.let { return hypot(it.velocity.x, it.velocity.y) }
        unit.hoverPose?.let { return hypot(it.velocity.x, it.velocity.y) }
        return abs(unit.speed)
    }

    private fun computeVelocityVector(unit: UnitObservation, forwardX: Double, forwardY: Double): Position {
        unit.flightPose?.let { return it.velocity }
        unit.hoverPose?.let { return it.velocity }
        val sign = if (unit.waterPose?.reversing == true || unit.mechPose?.reversing == true) -1.0 else 1.0
        return Position(forwardX * unit.speed * sign, forwardY * unit.speed * sign)
    }

    private fun computeGuidanceDirection(unit: UnitObservation): Position? {
        val aim = unit.pathPoints.firstOrNull { hypot(it.x - unit.position.x, it.y - unit.position.y) > 1e-4 }
            ?: unit.moveTarget?.takeIf { hypot(it.x - unit.position.x, it.y - unit.position.y) > 1e-4 }
            ?: return null
        val dx = aim.x - unit.position.x
        val dy = aim.y - unit.position.y
        val len = hypot(dx, dy)
        return if (len <= 1e-6) null else Position(dx / len, dy / len)
    }

    private fun countHardContactPairs(
        units: List<UnitObservation>,
        definitions: Map<String, UnitDefinition>,
    ): Int = findHardContactPairs(units, definitions).size

    private fun findHardContactUnitIds(
        units: List<UnitObservation>,
        definitions: Map<String, UnitDefinition>,
    ): Set<String> {
        val ids = HashSet<String>()
        for ((a, b) in findHardContactPairs(units, definitions)) {
            ids.add(a)
            ids.add(b)
        }
        return ids
    }

    private fun findHardContactPairs(
        units: List<UnitObservation>,
        definitions: Map<String, UnitDefinition>,
    ): List<Pair<String, String>> {
        data class Entry(
            val unit: UnitObservation,
            val layer: ContactLayer,
            val shape: MovementCollisionShape,
        )
        val eligible = units.mapNotNull { u ->
            val shape = mobileShapeForView(u, definitions) ?: return@mapNotNull null
            val def = definitions[u.typeId] ?: return@mapNotNull null
            val layer = contactLayerForObservation(def, u.height) ?: return@mapNotNull null
            Entry(u, layer, shape)
        }
        val pairs = ArrayList<Pair<String, String>>()
        for (i in 0 until eligible.size) {
            val a = eligible[i]
            for (j in i + 1 until eligible.size) {
                val b = eligible[j]
                if (a.layer != b.layer) continue
                val reach = a.shape.boundingRadius + b.shape.boundingRadius + 0.05
                val dx = a.unit.position.x - b.unit.position.x
                val dy = a.unit.position.y - b.unit.position.y
                if (abs(dx) > reach || abs(dy) > reach) continue
                if (shapesTouchOrOverlap(a.shape, a.unit.position, a.unit.heading, b.shape, b.unit.position, b.unit.heading)) {
                    pairs.add(a.unit.unitId to b.unit.unitId)
                }
            }
        }
        return pairs
    }

    private fun contactLayerForObservation(def: UnitDefinition, height: Double): ContactLayer? = when {
        CombatTargetQuery.isUnderwater(def, height) -> ContactLayer.SUBMERGED
        !def.hasContactLayerAt(height) -> null
        else -> ContactLayer.SURFACE
    }

    private fun shapesTouchOrOverlap(
        first: MovementCollisionShape,
        at: Position,
        heading: Double,
        second: MovementCollisionShape,
        other: Position,
        otherHeading: Double,
    ): Boolean {
        val tolerance = 0.05
        fun inflate(s: MovementCollisionShape): MovementCollisionShape = when (s) {
            is MovementCollisionShape.Circle -> s.copy(radius = s.radius + tolerance * 0.5)
            is MovementCollisionShape.Rectangle -> s.copy(length = s.length + tolerance, width = s.width + tolerance)
        }
        return UnitContact.posesOverlap(inflate(first), at, heading, inflate(second), other, otherHeading)
    }
}
