package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.path.NavigationTopologyMasks
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dual-adapter global navigation guidance seam (ADR-012 / ENG-P016-008 / ENG-P016-009D).
 *
 * Unifies:
 * - [ChunkedFlowFieldAdapter]: O(1) direct-drive shared Eikonal flow field with 2x2 bilinear smooth
 *   negative gradients, 1-tile narrow-gate centerline axis alignment, mixed-group wide-corridor yield
 *   surcharge, obstructed low-speed gate-queue congestion feedback (counted once per queue segment;
 *   smooth free-flowing exit traffic pays zero surcharge), P1 shadow double-buffer pure monotonic
 *   rebuilds with atomic swap (zero cost-rise backfeed cycles), single nearest-reachable connected
 *   boundary seeding for sealed/fog-blocked targets, and zero-allocation copy-on-write rollback.
 * - [WaypointRouteAdapter]: direct/A* polyline & rectangular `HullRoute` guidance for small squads,
 *   rectangular naval hulls, and fallback when a flow field is unavailable.
 */
internal interface NavigationGuidance {
    data class Sample(
        val aimPosition: Position,
        val dirX: Double,
        val dirY: Double,
        val remainingDistance: Double,
        val corridorClearanceWorld: Double,
        val finalApproach: Boolean,
        val fromFlowField: Boolean,
        val waypointIndex: Int = 0,
    )

    fun sampleGuidance(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        fallbackAim: Position,
        finalPoint: Boolean,
        executionStep: Long = 0L,
    ): Sample
}

/**
 * Continuous line-of-sight waypoint/polyline & rectangular [HullRoute] guidance adapter
 * (ADR-012 / ADR-016 / ENG-P017-004).
 *
 * Drives individual units, small squads (`< 4`), claimed final landing approaches, and rectangular
 * naval hulls without 20px breadcrumbs:
 * - Accumulates exact remaining polyline distance across `unit.pathPoints`;
 * - Aims continuously along the active LOS-clear leg and seamlessly rejoins downstream LOS-clear
 *   waypoints whenever the immediate front waypoint is occluded after a lateral push.
 */
internal object WaypointRouteAdapter : NavigationGuidance {
    override fun sampleGuidance(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        fallbackAim: Position,
        finalPoint: Boolean,
        executionStep: Long,
    ): NavigationGuidance.Sample {
        val points = unit.pathPoints
        val mapW = terrain.widthTiles.toDouble() * terrain.tileWidth
        val mapH = terrain.heightTiles.toDouble() * terrain.tileHeight
        val queryPos = if (unit.nativeTransportPlacement) {
            Position(unit.position.x.coerceIn(0.0, mapW), unit.position.y.coerceIn(0.0, mapH))
        } else {
            unit.position
        }
        val hull = HullRoute.forUnit(
            unit = unit,
            maneuver = unit.followsManeuverRoute,
            tileWidth = terrain.tileWidth,
            tileHeight = terrain.tileHeight,
        )
        val routeR = host.routeRadius(unit)
        val occupancy = host.routeOccupancy(unit)

        var chosenIndex = 0
        var chosenAim = points.firstOrNull() ?: fallbackAim
        if (points.size >= 2) {
            val firstClear = host.continuationFits(terrain, queryPos, points[0], unit.heading, hull, routeR, occupancy)
            if (!firstClear) {
                for (i in points.lastIndex downTo 1) {
                    val cand = points[i]
                    if (host.continuationFits(terrain, queryPos, cand, unit.heading, hull, routeR, occupancy)) {
                        chosenIndex = i
                        chosenAim = cand
                        break
                    }
                }
            }
        } else if (points.size == 1 && fallbackAim != points[0]) {
            chosenAim = fallbackAim
        }

        val remaining = if (points.isEmpty()) {
            hypot(chosenAim.x - unit.position.x, chosenAim.y - unit.position.y)
        } else {
            var sum = hypot(points[chosenIndex].x - unit.position.x, points[chosenIndex].y - unit.position.y)
            for (i in chosenIndex until points.lastIndex) {
                sum += hypot(points[i + 1].x - points[i].x, points[i + 1].y - points[i].y)
            }
            sum
        }
        val dx = chosenAim.x - unit.position.x
        val dy = chosenAim.y - unit.position.y
        val dist = hypot(dx, dy)
        val dirX = if (dist > 1e-6) dx / dist else 0.0
        val dirY = if (dist > 1e-6) dy / dist else 0.0
        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
        val isLastLeg = if (points.isEmpty()) finalPoint else (chosenIndex == points.lastIndex)
        val chosenFinal = finalPoint && isLastLeg && remaining <= minTile * 1.5
        val clearRings = terrain.clearanceRings(col, row)
        val clearWorld = if (clearRings <= 0) 0.0 else (clearRings * 2.0 - 1.0) * minTile
        return NavigationGuidance.Sample(
            aimPosition = chosenAim,
            dirX = dirX,
            dirY = dirY,
            remainingDistance = remaining,
            corridorClearanceWorld = clearWorld,
            finalApproach = chosenFinal,
            fromFlowField = false,
            waypointIndex = chosenIndex,
        )
    }
}

/**
 * Eikonal shared flow-field manager & adapter (ENG-P016-009D recast).
 *
 * Key invariants:
 * 1. Pure positive Eikonal slowness `f(c, r) >= 10.0f` with NO static pinhole penalty (abolishes
 *    `PINHOLE_BASE_SURCHARGE` and `congPen * 3`).
 * 2. Orthogonal 2-neighbor Eikonal quadratic update + wall-safe upwind gradient + `2x2` bilinear
 *    sampling over passable cells only, with 1-tile narrow-gate centerline axis alignment.
 * 3. P1 shadow double-buffer pure monotonic rebuilds using cut-cost invariance (`liveCost < cCut`
 *    copied intact; `>= cCut` rebuilt strictly monotonically in shadow; atomic swap on finish) ->
 *    zero backfeed loops and zero mid-build gradient tearing.
 * 4. Obstructed low-speed gate-queue congestion feedback: counts ONLY vehicles in or approaching a
 *    constricted gate throat whose forward speed along the flow is below free-flow threshold
 *    (`0.55 * moveSpeed`), attributing each queue segment once to its gate throat.
 * 5. Hull-clearance & no-toll-skipping shortcut verification so units cannot bypass congested or
 *    yield-surcharged wide gates via straight line-of-sight chords.
 * 6. Rollback-safe via both 0-arg deep [fork] and steady-state O(1) copy-on-write [fork(PathSearchRollback)].
 */
internal class ChunkedFlowFieldAdapter private constructor(
    private val fields: LinkedHashMap<FieldKey, ChunkedField>,
    private var lastCongestionStep: Long,
    private val undo: PathSearchRollback?,
    private val backedUpKeys: HashSet<FieldKey>?,
    private var congestionStepBackedUp: Boolean = false,
) : NavigationGuidance {

    constructor(
        fields: LinkedHashMap<FieldKey, ChunkedField> = linkedMapOf(),
        lastCongestionStep: Long = -1L,
    ) : this(fields, lastCongestionStep, undo = null, backedUpKeys = null)

    internal data class FieldKey(
        val ownerId: String,
        val movementType: MovementType,
        val clearanceBand: Int,
        val goalCol: Int,
        val goalRow: Int,
        val smallYieldsWideCorridor: Boolean,
        val sourceComponentId: Int = 0,
    )

    internal class WavefrontHeap internal constructor(
        private var keys: FloatArray,
        private var cells: IntArray,
        var size: Int,
    ) {
        constructor(initialCapacity: Int = 256) : this(
            FloatArray(initialCapacity),
            IntArray(initialCapacity),
            0,
        )

        fun push(cell: Int, priority: Float) {
            if (size >= keys.size) {
                val nextCap = max(256, keys.size * 2)
                keys = keys.copyOf(nextCap)
                cells = cells.copyOf(nextCap)
            }
            var idx = size++
            while (idx > 0) {
                val parent = (idx - 1) ushr 1
                if (keys[parent] <= priority) break
                keys[idx] = keys[parent]
                cells[idx] = cells[parent]
                idx = parent
            }
            keys[idx] = priority
            cells[idx] = cell
        }

        fun peekKey(): Float = keys[0]

        fun clear() {
            size = 0
        }

        fun popCell(): Int {
            val top = cells[0]
            size--
            if (size > 0) {
                val lastKey = keys[size]
                val lastCell = cells[size]
                var idx = 0
                val half = size ushr 1
                while (idx < half) {
                    var child = (idx shl 1) + 1
                    val right = child + 1
                    if (right < size && keys[right] < keys[child]) child = right
                    if (lastKey <= keys[child]) break
                    keys[idx] = keys[child]
                    cells[idx] = cells[child]
                    idx = child
                }
                keys[idx] = lastKey
                cells[idx] = lastCell
            }
            return top
        }

        fun copy(): WavefrontHeap = WavefrontHeap(
            keys = if (size == 0) FloatArray(min(64, keys.size.coerceAtLeast(16))) else keys.copyOf(max(16, size)),
            cells = if (size == 0) IntArray(min(64, cells.size.coerceAtLeast(16))) else cells.copyOf(max(16, size)),
            size = size,
        )
    }

    internal class ChunkedField internal constructor(
        val key: FieldKey,
        val width: Int,
        val height: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val chunksX: Int,
        val chunksY: Int,
        val requiredRings: Int,
        val wideCorridorRings: Int,
        var terrain: PlanningTerrain,
        val goalWorld: Position,
        var effectiveGoalWorld: Position,
        var goalWasNearestFallback: Boolean,
        var lastAccessStep: Long,
        var completed: Boolean,
        var totalExpansions: Long,
        var dirtyRebuildCount: Int,
        var reusedTrunkChunks: Int,
        var initialDiagnosticEmitted: Boolean,
        var terrainRebuildCount: Int,
        var reportedTerrainRebuilds: Int,
        var cost: FloatArray,
        var dirX: FloatArray,
        var dirY: FloatArray,
        var liveValid: Boolean,
        val chunkDirty: BooleanArray,
        val chunkBuilt: BooleanArray,
        var gateThroatMask: BooleanArray,
        var wideYieldMask: BooleanArray,
        val congestionPenalty: ShortArray,
        var anyCongestionActive: Boolean,
        var liveHeap: WavefrontHeap,
        var reusedMask: BooleanArray,
        var hasReusedMask: Boolean,
        var shadowActive: Boolean,
        var shadowMinCut: Float,
        var shadowCost: FloatArray,
        var shadowDirX: FloatArray,
        var shadowDirY: FloatArray,
        var shadowHeap: WavefrontHeap?,
        var activeSubscribers: Int,
    ) {
        val cellCount: Int = width * height
        val heapSize: Int get() = liveHeap.size
        val shadowHeapSize: Int get() = shadowHeap?.size ?: 0

        // Reusable per-field congestion refresh scratch arrays (zero allocation per 24-tick refresh)
        var congestionLoadScratch: IntArray = EMPTY_INT_ARRAY
        var gateSliceLoadScratch: IntArray = EMPTY_INT_ARRAY
        var gateSliceWidthScratch: IntArray = EMPTY_INT_ARRAY

        fun ensureShadowBuffers() {
            if (shadowCost.size != cellCount) {
                shadowCost = FloatArray(cellCount) { INF_COST_F }
                shadowDirX = FloatArray(cellCount)
                shadowDirY = FloatArray(cellCount)
            }
            if (shadowHeap == null) {
                shadowHeap = WavefrontHeap()
            }
        }

        fun ensureCongestionScratch() {
            if (congestionLoadScratch.size != cellCount) {
                congestionLoadScratch = IntArray(cellCount)
                gateSliceLoadScratch = IntArray(cellCount)
                gateSliceWidthScratch = IntArray(cellCount)
            } else {
                congestionLoadScratch.fill(0)
                gateSliceLoadScratch.fill(0)
                gateSliceWidthScratch.fill(0)
            }
        }

        fun pushHeap(cell: Int, priority: Float) = liveHeap.push(cell, priority)
        fun peekHeapKey(): Float = liveHeap.peekKey()
        fun clearHeap() = liveHeap.clear()
        fun popHeapCell(): Int = liveHeap.popCell()

        fun pushShadowHeap(cell: Int, priority: Float) {
            (shadowHeap ?: WavefrontHeap().also { shadowHeap = it }).push(cell, priority)
        }
        fun peekShadowHeapKey(): Float = checkNotNull(shadowHeap).peekKey()
        fun clearShadowHeap() { shadowHeap?.clear() }
        fun popShadowHeapCell(): Int = checkNotNull(shadowHeap).popCell()

        fun copyForRollback(): ChunkedField = ChunkedField(
            key = key,
            width = width,
            height = height,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            chunksX = chunksX,
            chunksY = chunksY,
            requiredRings = requiredRings,
            wideCorridorRings = wideCorridorRings,
            terrain = terrain,
            goalWorld = goalWorld,
            effectiveGoalWorld = effectiveGoalWorld,
            goalWasNearestFallback = goalWasNearestFallback,
            lastAccessStep = lastAccessStep,
            completed = completed,
            totalExpansions = totalExpansions,
            dirtyRebuildCount = dirtyRebuildCount,
            reusedTrunkChunks = reusedTrunkChunks,
            initialDiagnosticEmitted = initialDiagnosticEmitted,
            terrainRebuildCount = terrainRebuildCount,
            reportedTerrainRebuilds = reportedTerrainRebuilds,
            cost = cost.copyOf(),
            dirX = dirX.copyOf(),
            dirY = dirY.copyOf(),
            liveValid = liveValid,
            chunkDirty = chunkDirty.copyOf(),
            chunkBuilt = chunkBuilt.copyOf(),
            gateThroatMask = gateThroatMask,
            wideYieldMask = wideYieldMask,
            congestionPenalty = congestionPenalty.copyOf(),
            anyCongestionActive = anyCongestionActive,
            liveHeap = liveHeap.copy(),
            reusedMask = if (hasReusedMask && reusedMask.isNotEmpty()) reusedMask.copyOf() else EMPTY_BOOLEAN_ARRAY,
            hasReusedMask = hasReusedMask,
            shadowActive = shadowActive,
            shadowMinCut = shadowMinCut,
            shadowCost = if (shadowActive && shadowCost.isNotEmpty()) shadowCost.copyOf() else EMPTY_FLOAT_ARRAY,
            shadowDirX = if (shadowActive && shadowDirX.isNotEmpty()) shadowDirX.copyOf() else EMPTY_FLOAT_ARRAY,
            shadowDirY = if (shadowActive && shadowDirY.isNotEmpty()) shadowDirY.copyOf() else EMPTY_FLOAT_ARRAY,
            shadowHeap = if (shadowActive) shadowHeap?.copy() else null,
            activeSubscribers = activeSubscribers,
        )

        companion object {
            fun create(
                key: FieldKey,
                width: Int,
                height: Int,
                tileWidth: Int,
                tileHeight: Int,
                chunksX: Int,
                chunksY: Int,
                requiredRings: Int,
                wideCorridorRings: Int,
                terrain: PlanningTerrain,
                goalWorld: Position,
                effectiveGoalWorld: Position,
                goalWasNearestFallback: Boolean,
                lastAccessStep: Long,
            ): ChunkedField {
                val cellCount = width * height
                val gateMask = terrain.gateThroatMask()
                val wideMask = terrain.wideYieldMask(wideCorridorRings, key.smallYieldsWideCorridor)
                return ChunkedField(
                    key = key,
                    width = width,
                    height = height,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    chunksX = chunksX,
                    chunksY = chunksY,
                    requiredRings = requiredRings,
                    wideCorridorRings = wideCorridorRings,
                    terrain = terrain,
                    goalWorld = goalWorld,
                    effectiveGoalWorld = effectiveGoalWorld,
                    goalWasNearestFallback = goalWasNearestFallback,
                    lastAccessStep = lastAccessStep,
                    completed = false,
                    totalExpansions = 0L,
                    dirtyRebuildCount = 0,
                    reusedTrunkChunks = 0,
                    initialDiagnosticEmitted = false,
                    terrainRebuildCount = 0,
                    reportedTerrainRebuilds = 0,
                    cost = FloatArray(cellCount) { INF_COST_F },
                    dirX = FloatArray(cellCount),
                    dirY = FloatArray(cellCount),
                    liveValid = false,
                    chunkDirty = BooleanArray(chunksX * chunksY) { true },
                    chunkBuilt = BooleanArray(chunksX * chunksY) { false },
                    gateThroatMask = gateMask,
                    wideYieldMask = wideMask,
                    congestionPenalty = ShortArray(cellCount),
                    anyCongestionActive = false,
                    liveHeap = WavefrontHeap(),
                    reusedMask = EMPTY_BOOLEAN_ARRAY,
                    hasReusedMask = false,
                    shadowActive = false,
                    shadowMinCut = INF_COST_F,
                    shadowCost = EMPTY_FLOAT_ARRAY,
                    shadowDirX = EMPTY_FLOAT_ARRAY,
                    shadowDirY = EMPTY_FLOAT_ARRAY,
                    shadowHeap = null,
                    activeSubscribers = 0,
                )
            }
        }
    }

    /** Standalone deep-copy fork (for unit tests and isolated snapshots). */
    fun fork(): ChunkedFlowFieldAdapter {
        val next = LinkedHashMap<FieldKey, ChunkedField>(fields.size)
        for ((k, v) in fields) next[k] = v.copyForRollback()
        return ChunkedFlowFieldAdapter(next, lastCongestionStep, undo = null, backedUpKeys = null)
    }

    /** Zero-allocation copy-on-write rollback fork for 60Hz [StepState.fork]. */
    fun fork(rollback: PathSearchRollback): ChunkedFlowFieldAdapter =
        ChunkedFlowFieldAdapter(fields, lastCongestionStep, undo = rollback, backedUpKeys = HashSet(4))

    private fun recordFieldMutation(key: FieldKey) {
        val rb = undo ?: return
        val seen = backedUpKeys ?: return
        if (!seen.add(key)) return
        val prior = fields[key]?.copyForRollback()
        rb.record {
            if (prior == null) fields.remove(key) else fields[key] = prior
        }
    }

    private fun recordCongestionStepMutation() {
        val rb = undo ?: return
        if (congestionStepBackedUp) return
        congestionStepBackedUp = true
        val prev = lastCongestionStep
        rb.record { lastCongestionStep = prev }
    }

    fun activeFieldCount(): Int = fields.size
    fun activeSubscriberFieldCount(): Int = fields.values.count { it.activeSubscribers > 0 }
    fun statsFor(key: FieldKey): ChunkedField? = fields[key]
    internal fun cachedFieldsForTest(): Collection<ChunkedField> = fields.values

    fun resolveSourceComponent(
        terrain: PlanningTerrain,
        unitRadius: Double,
        goalCol: Int,
        goalRow: Int,
        unitCol: Int,
        unitRow: Int,
    ): Int {
        val band = clearanceBandFor(terrain, unitRadius)
        val cf = terrain.clearanceField()
        val goalComp = if (cf.clearanceRings(goalCol, goalRow) >= band) cf.componentId(goalCol, goalRow, band) else 0
        val unitComp = findUnitComponent(cf, terrain.widthTiles, terrain.heightTiles, unitCol, unitRow, band)
        if (goalComp != 0 && (unitComp == 0 || unitComp == goalComp)) return 0
        return unitComp
    }

    private fun findUnitComponent(
        cf: com.rustedwarfare.reforge.core.path.TerrainClearanceField,
        w: Int,
        h: Int,
        col: Int,
        row: Int,
        band: Int,
    ): Int {
        if (col in 0 until w && row in 0 until h && cf.clearanceRings(col, row) >= band) {
            val c = cf.componentId(col, row, band)
            if (c != 0) return c
        }
        for (ring in 1..2) {
            for (dy in -ring..ring) {
                val nr = row + dy
                if (nr !in 0 until h) continue
                for (dx in -ring..ring) {
                    if (abs(dx) != ring && abs(dy) != ring) continue
                    val nc = col + dx
                    if (nc !in 0 until w) continue
                    if (cf.clearanceRings(nc, nr) >= band) {
                        val c = cf.componentId(nc, nr, band)
                        if (c != 0) return c
                    }
                }
            }
        }
        return 0
    }

    /**
     * Returns true when [uCol]/[uRow] has no orthogonally adjacent tile meeting the unit's clearance
     * band (e.g. trapped in a 1x1 walled enclosure), so the caller delegates to [MovePlanning] for
     * canonical `immobile-unit` rejection instead of building a degenerate 1-cell fallback flow field.
     */
    fun isSingleCellImmobilePocket(
        terrain: PlanningTerrain,
        unitRadius: Double,
        uCol: Int,
        uRow: Int,
    ): Boolean {
        val band = clearanceBandFor(terrain, unitRadius)
        val cf = terrain.clearanceField()
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        for (d in 0 until 4) {
            val nc = uCol + ORTHO_DX[d]
            val nr = uRow + ORTHO_DY[d]
            if (nc in 0 until w && nr in 0 until h && cf.clearanceRings(nc, nr) >= band) {
                return false
            }
        }
        return true
    }

    fun consumeInitialDiagnostic(field: ChunkedField): Boolean {
        if (field.initialDiagnosticEmitted) return false
        recordFieldMutation(field.key)
        field.initialDiagnosticEmitted = true
        return true
    }

    fun drainTerrainRebuildDiagnostics(): List<Pair<String, Position>> {
        var result: MutableList<Pair<String, Position>>? = null
        for (field in fields.values) {
            if (field.terrainRebuildCount > field.reportedTerrainRebuilds) {
                recordFieldMutation(field.key)
                val delta = field.terrainRebuildCount - field.reportedTerrainRebuilds
                field.reportedTerrainRebuilds = field.terrainRebuildCount
                val list = result ?: mutableListOf<Pair<String, Position>>().also { result = it }
                repeat(delta) {
                    list += field.key.ownerId to field.effectiveGoalWorld
                }
            }
        }
        return result ?: emptyList()
    }

    fun ensureFieldForGroup(
        ownerId: String,
        movementType: MovementType,
        unitRadius: Double,
        maxGroupRadius: Double,
        terrain: PlanningTerrain,
        goalWorld: Position,
        stepIndex: Long,
        sourceComponentId: Int = 0,
    ): ChunkedField {
        val band = clearanceBandFor(terrain, unitRadius)
        val smallYieldsWide = maxGroupRadius > unitRadius * 1.45 && band == 1
        val goalCol = floor(goalWorld.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val goalRow = floor(goalWorld.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val key = FieldKey(ownerId, movementType, band, goalCol, goalRow, smallYieldsWide, sourceComponentId)

        val existing = fields[key]
        if (existing != null) {
            if (existing.terrain !== terrain) {
                markChangedTerrainDirty(existing, terrain)
            }
            if (existing.lastAccessStep != stepIndex) {
                val fKey = existing.key
                val prevAccess = existing.lastAccessStep
                undo?.record { fields[fKey]?.lastAccessStep = prevAccess }
                existing.lastAccessStep = stepIndex
            }
            return existing
        }

        // Rapid re-click: cancel incomplete old builds near the same target; reuse far trunk chunks if nearby & clear.
        var reusableOld: ChunkedField? = null
        val staleKeys = mutableListOf<FieldKey>()
        for ((oldKey, oldField) in fields) {
            if (oldKey.ownerId == ownerId &&
                oldKey.movementType == movementType &&
                oldKey.clearanceBand == band &&
                oldKey.smallYieldsWideCorridor == smallYieldsWide &&
                oldKey.sourceComponentId == sourceComponentId
            ) {
                val distTiles = hypot((oldKey.goalCol - goalCol).toDouble(), (oldKey.goalRow - goalRow).toDouble())
                // Only cancel an incomplete old field on rapid re-click near the same target; do NOT cancel
                // an independent split squad's flow field heading to a distant goal on the opposite side of the map!
                if (!oldField.completed && distTiles > 0.0 && distTiles <= TRUNK_REUSE_RADIUS_TILES) staleKeys += oldKey
                if (reusableOld == null && distTiles > 0.0 && distTiles <= TRUNK_REUSE_RADIUS_TILES &&
                    oldField.terrain === terrain && oldField.liveValid && !oldField.goalWasNearestFallback &&
                    !oldField.anyCongestionActive && !smallYieldsWide &&
                    PlanningSegmentClearance.allows(terrain, oldField.goalWorld, goalWorld, 1.0)
                ) {
                    reusableOld = oldField
                }
            }
        }
        for (k in staleKeys) {
            recordFieldMutation(k)
            fields.remove(k)
        }

        while (fields.size >= MAX_CACHED_FIELDS) {
            val lru = fields.entries.filter { it.value.activeSubscribers == 0 && it.value.completed }.minByOrNull { it.value.lastAccessStep }?.key
                ?: fields.entries.filter { it.value.activeSubscribers == 0 }.minByOrNull { it.value.lastAccessStep }?.key
                ?: fields.entries.filter { it.value.completed }.minByOrNull { it.value.lastAccessStep }?.key
                ?: fields.entries.minByOrNull { it.value.lastAccessStep }?.key
                ?: break
            recordFieldMutation(lru)
            fields.remove(lru)
        }

        val chunksX = (terrain.widthTiles + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunksY = (terrain.heightTiles + CHUNK_SIZE - 1) / CHUNK_SIZE
        val bigBand = clearanceBandFor(terrain, maxGroupRadius)
        val field = ChunkedField.create(
            key = key,
            width = terrain.widthTiles,
            height = terrain.heightTiles,
            tileWidth = terrain.tileWidth,
            tileHeight = terrain.tileHeight,
            chunksX = chunksX,
            chunksY = chunksY,
            requiredRings = band,
            wideCorridorRings = max(2, bigBand),
            terrain = terrain,
            goalWorld = goalWorld,
            effectiveGoalWorld = goalWorld,
            goalWasNearestFallback = false,
            lastAccessStep = stepIndex,
        )

        if (reusableOld != null && reusableOld.terrain === terrain && sourceComponentId == 0) {
            val oldGCol = reusableOld.key.goalCol
            val oldGRow = reusableOld.key.goalRow
            val goalShiftTiles = hypot((oldGCol - goalCol).toDouble(), (oldGRow - goalRow).toDouble())
            val shiftRadius = max(
                TRUNK_REUSE_LOCAL_HALO_TILES,
                ceil(goalShiftTiles).toInt() + 4,
            )
            // Shift reused trunk costs upward by `(ceil(dist) + 2) * BASE_STEP_COST` so that by the triangle
            // inequality every freshly expanded halo cell strictly beats every adjacent reused cell, preventing
            // any 2-cycle backfeed loop when re-clicking away from the reused trunk.
            val shiftBias = (ceil(goalShiftTiles).toFloat() + 2f) * BASE_STEP_COST
            var reused = 0
            for (cy in 0 until chunksY) {
                for (cx in 0 until chunksX) {
                    val cIdx = cy * chunksX + cx
                    if (!reusableOld.chunkBuilt[cIdx] || reusableOld.chunkDirty[cIdx]) continue
                    val centerCol = cx * CHUNK_SIZE + CHUNK_SIZE / 2
                    val centerRow = cy * CHUNK_SIZE + CHUNK_SIZE / 2
                    val dNew = hypot((centerCol - goalCol).toDouble(), (centerRow - goalRow).toDouble())
                    if (dNew > shiftRadius * 1.5) {
                        if (field.reusedMask.isEmpty()) field.reusedMask = BooleanArray(field.cellCount)
                        copyChunkFrom(reusableOld, field, cx, cy, shiftBias)
                        field.chunkBuilt[cIdx] = true
                        field.chunkDirty[cIdx] = false
                        reused++
                    }
                }
            }
            field.reusedTrunkChunks = reused
            field.hasReusedMask = reused > 0
        }

        seedGoalCells(field, intoShadow = false)
        recordFieldMutation(key)
        fields[key] = field
        return field
    }

    private fun matchStandaloneUnitField(
        unit: MutableUnit,
        movementType: MovementType,
        terrain: PlanningTerrain,
    ): ChunkedField? {
        val target = unit.moveTarget ?: unit.tasks.firstOrNull()?.takeIf { it.kind == UnitTaskKind.MOVE }?.target ?: return null
        val tCol = floor(target.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val tRow = floor(target.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val unitRadius = max(1.0, flowPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val band = clearanceBandFor(terrain, unitRadius)
        for (field in fields.values) {
            if (field.key.ownerId != unit.ownerPlayerId || field.key.movementType != movementType || field.key.clearanceBand != band) continue
            if (field.key.goalCol == tCol && field.key.goalRow == tRow) return field
            val effCol = floor(field.effectiveGoalWorld.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
            val effRow = floor(field.effectiveGoalWorld.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
            if (effCol == tCol && effRow == tRow) return field
        }
        return null
    }

    fun advanceP1Budget(
        units: Collection<MutableUnit>,
        spatialIndex: WorldSpatialIndex,
        terrainLookup: (MutableUnit, MovementType) -> PlanningTerrain,
        stepIndex: Long,
        maxExpansions: Int,
    ): Int {
        if (fields.isEmpty() || maxExpansions <= 0) return 0

        val latestTerrains = HashMap<Pair<String, MovementType>, PlanningTerrain>(4)
        val subscriberCounts = HashMap<FieldKey, Int>(fields.size)
        for (unit in units) {
            if (!unit.alive || unit.carriedBy != null || unit.attachment != null) continue
            val hasPendingMove = unit.moveTarget != null ||
                (unit.landingMember != null && !unit.landingMember!!.parked) ||
                unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE
            if (!hasPendingMove) continue
            val mType = UnitLocomotion.movement(unit).type
            if (mType == MovementType.AIR || mType == MovementType.NONE) continue
            val pair = unit.ownerPlayerId to mType
            val latestTerrain = latestTerrains.getOrPut(pair) { terrainLookup(unit, mType) }
            val subscribedField = fieldForUnit(unit, latestTerrain) ?: matchStandaloneUnitField(unit, mType, latestTerrain)
            if (subscribedField != null) {
                subscriberCounts[subscribedField.key] = (subscriberCounts[subscribedField.key] ?: 0) + 1
            }
        }

        for (field in fields.values) {
            val nextSubs = subscriberCounts[field.key] ?: 0
            if (field.activeSubscribers != nextSubs) {
                val fKey = field.key
                val prevSubs = field.activeSubscribers
                undo?.record { fields[fKey]?.activeSubscribers = prevSubs }
                field.activeSubscribers = nextSubs
            }
            if (nextSubs > 0) {
                val latestTerrain = latestTerrains[field.key.ownerId to field.key.movementType]
                if (latestTerrain != null && field.terrain !== latestTerrain) {
                    markChangedTerrainDirty(field, latestTerrain)
                }
            }
        }

        if (stepIndex - lastCongestionStep >= CONGESTION_REFRESH_INTERVAL) {
            recordCongestionStepMutation()
            lastCongestionStep = stepIndex
            if (subscriberCounts.isNotEmpty()) {
                updateObstructedQueueCongestion(spatialIndex)
            }
        }

        var used = 0
        val hasUnitContext = units.isNotEmpty()
        val activeFields = fields.values.filter {
            (!hasUnitContext || it.activeSubscribers > 0) &&
                ((!it.completed && it.heapSize > 0) || (it.shadowActive && it.shadowHeapSize > 0))
        }
        if (activeFields.isEmpty()) return 0
        val perFieldSlice = max(128, maxExpansions / activeFields.size)
        for (field in activeFields) {
            if (used >= maxExpansions) break
            recordFieldMutation(field.key)
            val slice = min(perFieldSlice, maxExpansions - used)
            used += if (field.shadowActive) expandShadowWavefront(field, slice) else expandLiveWavefront(field, slice)
        }
        if (used < maxExpansions) {
            for (field in activeFields) {
                if (used >= maxExpansions) break
                val hasWork = if (field.shadowActive) field.shadowHeapSize > 0 else field.heapSize > 0
                if (hasWork) {
                    recordFieldMutation(field.key)
                    used += if (field.shadowActive) expandShadowWavefront(field, maxExpansions - used)
                    else expandLiveWavefront(field, maxExpansions - used)
                }
            }
        }
        return used
    }

    internal fun markChangedTerrainDirty(field: ChunkedField, newTerrain: PlanningTerrain) {
        val oldTerrain = field.terrain
        recordFieldMutation(field.key)
        field.terrain = newTerrain
        val w = field.width
        val h = field.height
        val oldCf = oldTerrain.clearanceField()
        val newCf = newTerrain.clearanceField()
        val oldWideYield = field.wideYieldMask
        val newGateThroat = newTerrain.gateThroatMask()
        val newWideYield = newTerrain.wideYieldMask(field.wideCorridorRings, field.key.smallYieldsWideCorridor)
        field.gateThroatMask = newGateThroat
        field.wideYieldMask = newWideYield
        val dirtyChunks = BooleanArray(field.chunksX * field.chunksY)
        var anyDirty = false
        var anyCongActive = false
        var minAffectedCost = INF_COST_F

        for (row in 0 until h) {
            val base = row * w
            for (col in 0 until w) {
                val idx = base + col
                val oldBlocked = oldTerrain.isBlocked(col, row)
                val newBlocked = newTerrain.isBlocked(col, row)
                val oldRings = oldCf.clearanceRingsAt(idx)
                val newRings = newCf.clearanceRingsAt(idx)
                val wideChanged = field.key.smallYieldsWideCorridor && oldWideYield[idx] != newWideYield[idx]
                val penCleared = field.congestionPenalty[idx] > 0 && !newGateThroat[idx]
                if (penCleared) {
                    field.congestionPenalty[idx] = 0
                } else if (field.congestionPenalty[idx] > 0) {
                    anyCongActive = true
                }
                if (oldBlocked != newBlocked ||
                    (oldRings >= field.requiredRings) != (newRings >= field.requiredRings) ||
                    wideChanged || penCleared
                ) {
                    val ownC = field.cost[idx]
                    if (ownC < minAffectedCost) minAffectedCost = ownC
                    for (d in 0 until 4) {
                        val nc = col + ORTHO_DX[d]
                        val nr = row + ORTHO_DY[d]
                        if (nc in 0 until w && nr in 0 until h) {
                            val nC = field.cost[nr * w + nc]
                            if (nC < minAffectedCost) minAffectedCost = nC
                        }
                    }
                    val cx = col / CHUNK_SIZE
                    val cy = row / CHUNK_SIZE
                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny !in 0 until field.chunksY) continue
                        for (dx in -1..1) {
                            val nx = cx + dx
                            if (nx !in 0 until field.chunksX) continue
                            dirtyChunks[ny * field.chunksX + nx] = true
                            anyDirty = true
                        }
                    }
                }
            }
        }
        field.gateThroatMask = newGateThroat
        field.wideYieldMask = newWideYield
        field.anyCongestionActive = anyCongActive
        if (!anyDirty) return

        field.dirtyRebuildCount++
        field.terrainRebuildCount++
        field.completed = false

        // Mark only chunks touched by direct dirty halo as dirty so clean upstream chunks stay built.
        for (cy in 0 until field.chunksY) {
            for (cx in 0 until field.chunksX) {
                val cIdx = cy * field.chunksX + cx
                if (dirtyChunks[cIdx]) {
                    field.chunkDirty[cIdx] = true
                    field.chunkBuilt[cIdx] = false
                }
            }
        }

        if (!field.liveValid) {
            // Still in initial live build: restart cleanly from seeds on the updated terrain (pure monotonic, zero stale heap entries).
            field.cost.fill(INF_COST_F)
            field.dirX.fill(0f)
            field.dirY.fill(0f)
            field.hasReusedMask = false
            field.reusedMask = EMPTY_BOOLEAN_ARRAY
            field.clearHeap()
            field.chunkDirty.fill(true)
            field.chunkBuilt.fill(false)
            seedGoalCells(field, intoShadow = false)
            return
        }

        // Shadow double-buffer pure monotonic rebuild: live buffers stay tear-free until shadow finishes and swaps atomically!
        startShadowMonotonicRebuild(field, minAffectedCost, terrainChanged = true)
    }

    /**
     * Pure monotonic cut-cost shadow rebuild theorem:
     * Every passable cell with `liveCost < effectiveCut` is mathematically invariant under all
     * cost/passability changes occurring at `>= effectiveCut` (accumulated across any overlapping
     * mid-shadow mutations via `shadowMinCut = min(shadowMinCut, cCut)`). Copying ALL strictly-upstream
     * cells into `shadowCost` (without chunk-level exclusion, so `cost == 0` goal cells are preserved
     * unconditionally even when an edit lies in the goal chunk) and seeding the shadow min-heap from
     * their frontier guarantees the exact global Eikonal fixed point with zero backfeed cycles.
     */
    private fun startShadowMonotonicRebuild(
        field: ChunkedField,
        cCut: Float,
        terrainChanged: Boolean,
    ) {
        field.ensureShadowBuffers()
        val effectiveCut = if (field.shadowActive) min(field.shadowMinCut, cCut) else cCut
        field.shadowMinCut = effectiveCut
        field.shadowActive = true
        field.completed = false
        field.clearShadowHeap()
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val req = field.requiredRings
        val sCost = field.shadowCost
        val lCost = field.cost
        sCost.fill(INF_COST_F)

        val effCol = floor(field.effectiveGoalWorld.x / field.tileWidth).toInt().coerceIn(0, w - 1)
        val effRow = floor(field.effectiveGoalWorld.y / field.tileHeight).toInt().coerceIn(0, h - 1)

        if (!terrainChanged) {
            // Congestion-only refresh: terrain passability and fallback seed `(effCol, effRow)` are unchanged!
            // Symmetric with normal goals: only re-seed `(effCol, effRow)` directly when `effectiveCut <= 1e-3f`;
            // otherwise preserve all strictly-upstream `liveCost < effectiveCut` cells (including the 0-cost fallback seed).
            if (effectiveCut <= 1e-3f) {
                val seedIdx = effRow * w + effCol
                if (cf.clearanceRingsAt(seedIdx) >= req) {
                    sCost[seedIdx] = 0f
                    field.pushShadowHeap(seedIdx, 0f)
                } else {
                    seedGoalCells(field, intoShadow = true)
                }
                return
            }
        } else {
            val goalInvalidated = if (!field.goalWasNearestFallback) {
                effectiveCut <= 1e-3f ||
                    cf.clearanceRings(field.key.goalCol, field.key.goalRow) < req ||
                    (field.key.sourceComponentId != 0 && cf.componentId(field.key.goalCol, field.key.goalRow, req) != field.key.sourceComponentId)
            } else {
                val resolved = resolveSeedCell(field)
                effectiveCut <= 1e-3f || resolved == null || resolved.first != effCol || resolved.second != effRow
            }
            if (goalInvalidated) {
                seedGoalCells(field, intoShadow = true)
                return
            }
        }

        // Step 1: Copy ALL strictly-upstream invariant cells (liveCost < effectiveCut and still passable).
        var copiedAny = false
        for (r in 0 until h) {
            val base = r * w
            for (c in 0 until w) {
                val idx = base + c
                val lc = lCost[idx]
                if (lc < effectiveCut && cf.clearanceRingsAt(idx) >= req) {
                    sCost[idx] = lc
                    field.shadowDirX[idx] = field.dirX[idx]
                    field.shadowDirY[idx] = field.dirY[idx]
                    copiedAny = true
                }
            }
        }

        if (!copiedAny) {
            seedGoalCells(field, intoShadow = true)
            return
        }

        // Step 2: Seed shadow heap with strictly-upstream cells bordering any uncopied passable cell.
        for (r in 0 until h) {
            val base = r * w
            for (c in 0 until w) {
                val idx = base + c
                val sc = sCost[idx]
                if (sc >= INF_COST_F) continue
                var touchesOpen = false
                for (d in 0 until 4) {
                    val nc = c + ORTHO_DX[d]
                    val nr = r + ORTHO_DY[d]
                    if (nc !in 0 until w || nr !in 0 until h) continue
                    val nIdx = nr * w + nc
                    if (sCost[nIdx] >= INF_COST_F && cf.clearanceRingsAt(nIdx) >= req) {
                        touchesOpen = true
                        break
                    }
                }
                if (touchesOpen) {
                    field.pushShadowHeap(idx, sc)
                }
            }
        }

        if (field.shadowHeapSize == 0) {
            // All reachable cells were strictly upstream of the changed cells -> finalize swap immediately.
            commitShadowSwap(field)
        }
    }

    private fun commitShadowSwap(field: ChunkedField) {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val req = field.requiredRings
        for (r in 0 until h) {
            val base = r * w
            for (c in 0 until w) {
                val idx = base + c
                if (field.shadowCost[idx] < INF_COST_F && cf.clearanceRingsAt(idx) >= req) {
                    recomputeCellGradientOn(field, c, r, field.shadowCost, field.shadowDirX, field.shadowDirY)
                } else {
                    field.shadowDirX[idx] = 0f
                    field.shadowDirY[idx] = 0f
                }
            }
        }
        val tmpCost = field.cost; field.cost = field.shadowCost; field.shadowCost = tmpCost
        val tmpDx = field.dirX; field.dirX = field.shadowDirX; field.shadowDirX = tmpDx
        val tmpDy = field.dirY; field.dirY = field.shadowDirY; field.shadowDirY = tmpDy
        field.liveValid = true
        field.shadowActive = false
        field.shadowMinCut = INF_COST_F
        field.completed = true
        field.chunkDirty.fill(false)
        field.chunkBuilt.fill(true)
    }

    /**
     * Looks up the matching [ChunkedField] for [unit] in O(1), resolving connected-component fallback
     * FIRST so enclosed members in a mixed connected+enclosed group hit their own `sourceComponentId`
     * field instead of falsely matching the main `0`-component field.
     */
    internal fun fieldForUnit(unit: MutableUnit, terrain: PlanningTerrain): ChunkedField? {
        val group = unit.landingMember?.group ?: return null
        if (group.members.size < MIN_GROUP_SIZE) return null
        val movement = UnitLocomotion.movement(unit)
        if (movement.type == MovementType.AIR || movement.type == MovementType.NONE) return null
        val unitRadius = max(1.0, flowPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val band = clearanceBandFor(terrain, unitRadius)
        val goalCol = floor(group.click.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val goalRow = floor(group.click.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val maxGroupR = max(unitRadius, group.maxRadiusByMovement[movement.type] ?: unitRadius)
        val wantYieldWide = maxGroupR > unitRadius * 1.45 && band == 1
        val uCol = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val uRow = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val srcComp = resolveSourceComponent(terrain, unitRadius, goalCol, goalRow, uCol, uRow)
        return fields[FieldKey(unit.ownerPlayerId, movement.type, band, goalCol, goalRow, wantYieldWide, srcComp)]
    }

    /**
     * Returns true when a shared flow field already covers [unit]'s live cell (or 1-tile ring)
     * with a finite Eikonal cost, enabling immediate O(1) group direct drive without enqueueing
     * individual per-unit A* searches.
     */
    fun hasValidGuidanceFor(unit: MutableUnit, terrain: PlanningTerrain): Boolean {
        val field = fieldForUnit(unit, terrain) ?: return false
        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        if (field.cost[row * field.width + col] < INF_COST_F) return true
        for (d in 0 until 8) {
            val nc = col + DX[d]
            val nr = row + DY[d]
            if (nc in 0 until field.width && nr in 0 until field.height && field.cost[nr * field.width + nc] < INF_COST_F) {
                return true
            }
        }
        return false
    }

    /** Returns true when [unit]'s flow field finished expanding yet still cannot reach [unit]. */
    fun isUnreachableInCompletedField(unit: MutableUnit, terrain: PlanningTerrain): Boolean {
        val field = fieldForUnit(unit, terrain) ?: return false
        if (!field.completed && !field.liveValid) return false
        return !hasValidGuidanceFor(unit, terrain)
    }

    override fun sampleGuidance(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        fallbackAim: Position,
        finalPoint: Boolean,
        executionStep: Long,
    ): NavigationGuidance.Sample {
        val lm = unit.landingMember
        if (!host.trafficSeparationEnabled || lm == null || lm.claimed != null) {
            return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint, executionStep)
        }
        val field = fieldForUnit(unit, terrain)
            ?: return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint, executionStep)

        val w = field.width
        val h = field.height
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val minTile = min(tw, th)
        val unitRadius = max(1.0, flowPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val col = floor(unit.position.x / tw).toInt().coerceIn(0, w - 1)
        val row = floor(unit.position.y / th).toInt().coerceIn(0, h - 1)
        var idx = row * w + col
        var cellCost = field.cost[idx]
        var snappedFromShoulder = false
        if (cellCost >= INF_COST_F) {
            var bestIdx = -1
            var bestCost = INF_COST_F
            for (ring in 1..3) {
                for (dy in -ring..ring) {
                    val nr = row + dy
                    if (nr !in 0 until h) continue
                    for (dx in -ring..ring) {
                        if (abs(dx) != ring && abs(dy) != ring) continue
                        val nc = col + dx
                        if (nc !in 0 until w) continue
                        val nIdx = nr * w + nc
                        val ncCost = field.cost[nIdx]
                        if (ncCost < bestCost && PlanningSegmentClearance.gridLineUnblocked(terrain, col, row, nc, nr)) {
                            bestCost = ncCost
                            bestIdx = nIdx
                        }
                    }
                }
                if (bestIdx >= 0) break
            }
            if (bestIdx < 0) {
                return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint, executionStep)
            }
            idx = bestIdx
            cellCost = bestCost
            snappedFromShoulder = true
        }

        // 2x2 bilinear smooth negative gradient across passable neighbor cell centers
        val cf = terrain.clearanceField()
        val reqR = field.requiredRings
        var vx = 0.0
        var vy = 0.0
        val bilinear = if (snappedFromShoulder) null else sampleBilinearGradient(field, cf, reqR, unit.position.x, unit.position.y)
        if (bilinear != null) {
            vx = bilinear.x
            vy = bilinear.y
        } else if (snappedFromShoulder) {
            // Unit's wide hull was nudged onto a sub-band wall-shoulder tile (rings < requiredRings):
            // blend an inward pull toward the nearest band-valid interior cell center with its downstream
            // flow gradient so heavy units never scrape flat along a gate wall shoulder.
            val vcX = (idx % w + 0.5) * tw - unit.position.x
            val vcY = (idx / w + 0.5) * th - unit.position.y
            val vcLen = hypot(vcX, vcY)
            val fx = field.dirX[idx].toDouble()
            val fy = field.dirY[idx].toDouble()
            val fLen = hypot(fx, fy)
            val pullX = if (vcLen > 1e-4) vcX / vcLen else 0.0
            val pullY = if (vcLen > 1e-4) vcY / vcLen else 0.0
            val flowX = if (fLen > 1e-4) fx / fLen else 0.0
            val flowY = if (fLen > 1e-4) fy / fLen else 0.0
            vx = pullX * 0.65 + flowX * 0.35
            vy = pullY * 0.65 + flowY * 0.35
            val len = hypot(vx, vy)
            if (len > 1e-4) {
                vx /= len; vy /= len
            } else {
                return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
            }
        } else {
            vx = field.dirX[idx].toDouble()
            vy = field.dirY[idx].toDouble()
            val len = hypot(vx, vy)
            if (len > 1e-4) {
                vx /= len; vy /= len
            } else {
                val gdx = field.effectiveGoalWorld.x - unit.position.x
                val gdy = field.effectiveGoalWorld.y - unit.position.y
                val gd = hypot(gdx, gdy)
                if (cellCost <= 40f && gd > 1e-4) {
                    vx = gdx / gd; vy = gdy / gd
                } else {
                    return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
                }
            }
        }

        val remainingWorld = (cellCost / BASE_STEP_COST) * minTile
        val terrainHull = UnitLocomotion.terrainHull(unit, terrain)
        val routeR = host.routeRadius(unit)

        // Direct-to-goal shortcut: MUST pass BOTH true hull clearance AND zero toll-gate crossing!
        val directGoalClear = segmentClearAndTollFree(
            terrain, field, terrainHull, unit.position, field.effectiveGoalWorld, routeR, unitRadius,
        )
        val safeAim = if (directGoalClear) {
            val gdx = field.effectiveGoalWorld.x - unit.position.x
            val gdy = field.effectiveGoalWorld.y - unit.position.y
            val gd = hypot(gdx, gdy)
            if (gd > 1e-4) {
                vx = gdx / gd; vy = gdy / gd
            }
            field.effectiveGoalWorld
        } else {
            // 1-tile narrow-gate centerline alignment within 1-2 tiles upstream or inside a pinhole
            val pinholeAim = alignSingleTilePinholeAim(field, terrain, unit.position, col, row, unitRadius)
            if (pinholeAim != null) {
                val pdx = pinholeAim.x - unit.position.x
                val pdy = pinholeAim.y - unit.position.y
                val pd = hypot(pdx, pdy)
                if (pd > 1e-4) {
                    vx = pdx / pd; vy = pdy / pd
                }
                pinholeAim
            } else {
                // Smooth forward aim along the 2x2 bilinear Eikonal gradient (keeps wide gates transversely uniform
                // and open fields smooth without 8-neighbor cell quantization), verified for full hull clearance
                // (toll-skipping is already blocked on directGoalClear; flow-aligned traffic inside a tolled wide
                // gate retains full bilinear smoothness); falls back to steepest-descent clear cell center near sharp corners.
                val mapW = w * tw
                val mapH = h * th
                val lookDist = min(remainingWorld.coerceAtLeast(minTile * 0.9), minTile * 2.2)
                val gradAim = Position(
                    (unit.position.x + vx * lookDist).coerceIn(unitRadius, mapW - unitRadius),
                    (unit.position.y + vy * lookDist).coerceIn(unitRadius, mapH - unitRadius),
                )
                if (segmentClearWithHull(terrain, terrainHull, unit.position, gradAim, routeR, unitRadius)) {
                    gradAim
                } else if (snappedFromShoulder) {
                    val validCenter = Position((idx % w + 0.5) * tw, (idx / w + 0.5) * th)
                    val cdx = validCenter.x - unit.position.x
                    val cdy = validCenter.y - unit.position.y
                    val cd = hypot(cdx, cdy)
                    if (cd > 1e-4) {
                        vx = cdx / cd; vy = cdy / cd
                    }
                    validCenter
                } else {
                    val descentAim = walkSteepestDescentAim(field, terrain, terrainHull, unit.position, idx, cellCost, routeR, unitRadius)
                    if (descentAim != null) {
                        val adx = descentAim.x - unit.position.x
                        val ady = descentAim.y - unit.position.y
                        val ad = hypot(adx, ady)
                        if (ad > 1e-4) {
                            vx = adx / ad; vy = ady / ad
                        }
                        descentAim
                    } else {
                        val ownCenter = Position((idx % w + 0.5) * tw, (idx / w + 0.5) * th)
                        val od = hypot(ownCenter.x - unit.position.x, ownCenter.y - unit.position.y)
                        if (od > 1.5 && segmentClearWithHull(terrain, terrainHull, unit.position, ownCenter, routeR, unitRadius)) {
                            vx = (ownCenter.x - unit.position.x) / od
                            vy = (ownCenter.y - unit.position.y) / od
                            ownCenter
                        } else {
                            fallbackAim
                        }
                    }
                }
            }
        }

        val clearRings = cf.clearanceRings(col, row)
        val clearWorld = if (clearRings <= 0) 0.0 else (clearRings * 2.0 - 1.0) * minTile

        val goalDist = hypot(field.effectiveGoalWorld.x - unit.position.x, field.effectiveGoalWorld.y - unit.position.y)

        return NavigationGuidance.Sample(
            aimPosition = safeAim,
            dirX = vx,
            dirY = vy,
            remainingDistance = remainingWorld,
            corridorClearanceWorld = clearWorld,
            finalApproach = directGoalClear && goalDist <= minTile,
            fromFlowField = true,
        )
    }

    /**
     * 2x2 bilinear interpolation of cell-center negative Eikonal gradients over passable cells only.
     * Blocked or out-of-band cells carry weight 0 so gradients never pull units into rock corners.
     */
    private fun sampleBilinearGradient(
        field: ChunkedField,
        cf: com.rustedwarfare.reforge.core.path.TerrainClearanceField,
        reqRings: Int,
        wx: Double,
        wy: Double,
    ): Position? {
        val w = field.width
        val h = field.height
        val gx = wx / field.tileWidth.toDouble() - 0.5
        val gy = wy / field.tileHeight.toDouble() - 0.5
        val c0 = floor(gx).toInt()
        val r0 = floor(gy).toInt()
        val fx = (gx - c0).coerceIn(0.0, 1.0)
        val fy = (gy - r0).coerceIn(0.0, 1.0)

        var sx = 0.0
        var sy = 0.0
        var wSum = 0.0
        for (dr in 0..1) {
            val r = r0 + dr
            if (r !in 0 until h) continue
            val wy0 = if (dr == 0) 1.0 - fy else fy
            val base = r * w
            for (dc in 0..1) {
                val c = c0 + dc
                if (c !in 0 until w) continue
                val nIdx = base + c
                if (cf.clearanceRingsAt(nIdx) < reqRings || field.cost[nIdx] >= INF_COST_F) continue
                val dx = field.dirX[nIdx].toDouble()
                val dy = field.dirY[nIdx].toDouble()
                if (dx == 0.0 && dy == 0.0) continue
                val wt = (if (dc == 0) 1.0 - fx else fx) * wy0
                if (wt > 0.0) {
                    sx += dx * wt
                    sy += dy * wt
                    wSum += wt
                }
            }
        }
        if (wSum <= 1e-6) return null
        val mag = hypot(sx, sy)
        // Guard against watershed ridges where opposing neighbor cells point toward different gates
        // and cancel each other out (`mag / wSum < 0.45`): fall back to nearest cell's non-canceling gradient.
        if (mag <= 0.45 * wSum) return null
        return Position(sx / mag, sy / mag)
    }

    /**
     * 1-tile narrow-gate centerline axis alignment:
     * When the unit's current cell or its 1..2 downstream steepest-descent cells pass through a 1-tile
     * pinhole (`reqRings == 1`), smoothly aligns the unit onto the gate centerline axis before/through
     * the jambs so circular hulls never clip corners or arch across the entrance.
     */
    private fun alignSingleTilePinholeAim(
        field: ChunkedField,
        terrain: PlanningTerrain,
        pos: Position,
        startCol: Int,
        startRow: Int,
        unitRadius: Double,
    ): Position? {
        if (field.requiredRings > 1) return null
        val w = field.width
        val h = field.height
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val cf = terrain.clearanceField()

        // Walk up to 2 orthogonal steepest-descent steps to detect an imminent or current 1-tile pinhole.
        var curC = startCol
        var curR = startRow
        var pinC = -1
        var pinR = -1
        var pinAxis = 0 // 1 = horizontal passage (walls up/down), 2 = vertical passage (walls left/right)
        var pinDir = 0  // +1 or -1 along passage axis

        for (step in 0..2) {
            val axis = singleTilePinholeAxis(terrain, curC, curR)
            if (axis != 0) {
                pinC = curC
                pinR = curR
                pinAxis = axis
                // Determine travel sign along the pinhole axis from potential drop
                pinDir = if (axis == 1) {
                    val leftC = if (curC > 0) field.cost[curR * w + curC - 1] else INF_COST_F
                    val rightC = if (curC + 1 < w) field.cost[curR * w + curC + 1] else INF_COST_F
                    if (rightC <= leftC) +1 else -1
                } else {
                    val upC = if (curR > 0) field.cost[(curR - 1) * w + curC] else INF_COST_F
                    val downC = if (curR + 1 < h) field.cost[(curR + 1) * w + curC] else INF_COST_F
                    if (downC <= upC) +1 else -1
                }
                break
            }
            // Advance 1 step to steepest lower-cost orthogonal neighbor
            val curCost = field.cost[curR * w + curC]
            var nextC = -1
            var nextR = -1
            var bestCost = curCost
            for (d in 0 until 4) {
                val nc = curC + ORTHO_DX[d]
                val nr = curR + ORTHO_DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                if (cf.clearanceRingsAt(nIdx) < 1) continue
                val c = field.cost[nIdx]
                if (c < bestCost) {
                    bestCost = c
                    nextC = nc
                    nextR = nr
                }
            }
            if (nextC < 0) break
            curC = nextC
            curR = nextR
        }
        if (pinAxis == 0) return null

        val latSlack = max(1.5, min(tw, th) * 0.5 - unitRadius - 1.25)
        val funnelBand = min(tw, th) * 0.36
        if (pinAxis == 1) {
            // Horizontal 1-tile gate (walls above/below): centerline is Y = (pinR + 0.5) * th
            val axisY = (pinR + 0.5) * th
            val gateEntryX = if (pinDir > 0) pinC * tw else (pinC + 1) * tw
            val distBeforeEntry = (gateEntryX - pos.x) * pinDir
            val latErr = abs(pos.y - axisY)
            return when {
                // Pressing against the jamb cheek right beside the opening: slide purely tangentially onto centerline!
                distBeforeEntry in -2.0..(unitRadius + 5.0) && latErr > latSlack -> {
                    val safeX = (gateEntryX - pinDir * (unitRadius + 3.5)).coerceIn(unitRadius, w * tw - unitRadius)
                    Position(safeX, axisY)
                }
                // Well off-axis upstream: curve smoothly toward an approach point ahead of the opening without
                // forcing two cars onto the same dead-stop dot right in front of the jamb.
                distBeforeEntry > unitRadius + 5.0 && latErr > funnelBand -> {
                    val leadX = (gateEntryX - pinDir * max(tw * 0.65, min(distBeforeEntry * 0.45, tw * 1.1)))
                        .coerceIn(unitRadius, w * tw - unitRadius)
                    Position(leadX, axisY)
                }
                else -> {
                    // Within funnel band or already inside pinhole: drive straight along centerline through exit!
                    var exitC = pinC
                    while (exitC + pinDir in 0 until w && singleTilePinholeAxis(terrain, exitC + pinDir, pinR) == 1) {
                        exitC += pinDir
                    }
                    val aimX = ((exitC + 0.5 + pinDir * 1.15) * tw).coerceIn(unitRadius, w * tw - unitRadius)
                    Position(aimX, axisY)
                }
            }
        } else {
            // Vertical 1-tile gate (walls left/right): centerline is X = (pinC + 0.5) * tw
            val axisX = (pinC + 0.5) * tw
            val gateEntryY = if (pinDir > 0) pinR * th else (pinR + 1) * th
            val distBeforeEntry = (gateEntryY - pos.y) * pinDir
            val latErr = abs(pos.x - axisX)
            return when {
                distBeforeEntry in -2.0..(unitRadius + 5.0) && latErr > latSlack -> {
                    val safeY = (gateEntryY - pinDir * (unitRadius + 3.5)).coerceIn(unitRadius, h * th - unitRadius)
                    Position(axisX, safeY)
                }
                distBeforeEntry > unitRadius + 5.0 && latErr > funnelBand -> {
                    val leadY = (gateEntryY - pinDir * max(th * 0.65, min(distBeforeEntry * 0.45, th * 1.1)))
                        .coerceIn(unitRadius, h * th - unitRadius)
                    Position(axisX, leadY)
                }
                else -> {
                    var exitR = pinR
                    while (exitR + pinDir in 0 until h && singleTilePinholeAxis(terrain, pinC, exitR + pinDir) == 2) {
                        exitR += pinDir
                    }
                    val aimY = ((exitR + 0.5 + pinDir * 1.15) * th).coerceIn(unitRadius, h * th - unitRadius)
                    Position(axisX, aimY)
                }
            }
        }
    }

    private fun walkSteepestDescentAim(
        field: ChunkedField,
        terrain: PlanningTerrain,
        terrainHull: MovementCollisionShape?,
        pos: Position,
        startIdx: Int,
        startCost: Float,
        routeR: Double,
        unitRadius: Double,
    ): Position? {
        val w = field.width
        val h = field.height
        val cf = terrain.clearanceField()
        val reqR = field.requiredRings
        var curC = startIdx % w
        var curR = startIdx / w
        var curCost = startCost
        var bestClear: Position? = null
        var firstHop: Position? = null
        for (hop in 0 until 4) {
            var nextC = -1
            var nextR = -1
            var nextCost = curCost
            for (d in 0 until 8) {
                val nc = curC + DX[d]
                val nr = curR + DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                if (cf.clearanceRingsAt(nIdx) < reqR) continue
                if (d >= 4 && (cf.clearanceRingsAt(curR * w + nc) < reqR ||
                        cf.clearanceRingsAt(nr * w + curC) < reqR)
                ) continue
                val c = field.cost[nIdx]
                if (c < nextCost) {
                    nextCost = c
                    nextC = nc
                    nextR = nr
                }
            }
            if (nextC < 0) break
            curC = nextC
            curR = nextR
            curCost = nextCost
            val center = Position((curC + 0.5) * terrain.tileWidth, (curR + 0.5) * terrain.tileHeight)
            if (hop == 0) firstHop = center
            val clear = segmentClearWithHull(terrain, terrainHull, pos, center, routeR, unitRadius)
            if (clear) {
                bestClear = center
            } else if (bestClear != null) {
                break
            }
            if (singleTilePinholeAxis(terrain, curC, curR) != 0 && clear) break
        }
        return bestClear ?: firstHop?.takeIf { segmentClearWithHull(terrain, terrainHull, pos, it, routeR, unitRadius) }
    }

    private fun segmentClearWithHull(
        terrain: PlanningTerrain,
        terrainHull: MovementCollisionShape?,
        from: Position,
        to: Position,
        routeR: Double,
        terrainR: Double,
    ): Boolean = PlanningSegmentClearance.allowsHull(
        terrain = terrain,
        hullGrid = null,
        terrainHull = terrainHull,
        from = from,
        to = to,
        heading = 0.0,
        boundaryRadius = routeR,
        terrainRadius = terrainR,
    )

    /**
     * Verifies both true hull-width terrain clearance AND that the chord does not evade toll-gated
     * cells (active congestion penalty or mixed wide-corridor yield cells).
     */
    private fun segmentClearAndTollFree(
        terrain: PlanningTerrain,
        field: ChunkedField,
        terrainHull: MovementCollisionShape?,
        from: Position,
        to: Position,
        routeR: Double,
        terrainR: Double,
        allowOwnCellToll: Boolean = false,
    ): Boolean {
        if (!segmentClearWithHull(terrain, terrainHull, from, to, routeR, terrainR)) return false
        val checkToll = field.anyCongestionActive || field.key.smallYieldsWideCorridor
        if (!checkToll) return true

        val w = field.width
        val h = field.height
        val tw = terrain.tileWidth.toDouble()
        val th = terrain.tileHeight.toDouble()
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = hypot(dx, dy)
        val stepLen = min(tw, th) * 0.45
        val steps = ceil(dist / stepLen).toInt().coerceAtLeast(1)
        val startCol = floor(from.x / tw).toInt().coerceIn(0, w - 1)
        val startRow = floor(from.y / th).toInt().coerceIn(0, h - 1)
        val startIdx = startRow * w + startCol
        val checkYield = field.key.smallYieldsWideCorridor
        for (i in 1..steps) {
            val t = i.toDouble() / steps.toDouble()
            val c = floor((from.x + dx * t) / tw).toInt().coerceIn(0, w - 1)
            val r = floor((from.y + dy * t) / th).toInt().coerceIn(0, h - 1)
            val idx = r * w + c
            if (allowOwnCellToll && idx == startIdx) continue
            if (field.congestionPenalty[idx] > 0) return false
            if (checkYield && field.wideYieldMask[idx]) return false
        }
        return true
    }

    private fun resolveSeedCell(field: ChunkedField): Pair<Int, Int>? {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val goalCol = field.key.goalCol
        val goalRow = field.key.goalRow
        val req = field.requiredRings
        val srcComp = field.key.sourceComponentId
        val goalPassable = cf.clearanceRings(goalCol, goalRow) >= req
        if (goalPassable && (srcComp == 0 || cf.componentId(goalCol, goalRow, req) == srcComp)) {
            return goalCol to goalRow
        }
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestCol = -1
        var bestRow = -1
        for (r in 0 until h) {
            val base = r * w
            val wy = (r + 0.5) * field.tileHeight
            val dy = wy - field.goalWorld.y
            val dy2 = dy * dy
            if (dy2 > bestDistSq) continue
            for (c in 0 until w) {
                val idx = base + c
                if (cf.clearanceRingsAt(idx) < req) continue
                if (srcComp != 0 && cf.componentId(c, r, req) != srcComp) continue
                val wx = (c + 0.5) * field.tileWidth
                val dx = wx - field.goalWorld.x
                val d2 = dx * dx + dy2
                if (d2 < bestDistSq) {
                    bestDistSq = d2
                    bestCol = c
                    bestRow = r
                }
            }
        }
        return if (bestCol >= 0) bestCol to bestRow else null
    }

    private fun seedGoalCells(field: ChunkedField, intoShadow: Boolean) {
        val w = field.width
        val costArr = if (intoShadow) field.shadowCost else field.cost

        fun seed(idx: Int, c: Float) {
            if (c < costArr[idx]) {
                costArr[idx] = c
                if (intoShadow) field.pushShadowHeap(idx, c) else field.pushHeap(idx, c)
            }
        }

        val resolved = resolveSeedCell(field) ?: return
        val (seedCol, seedRow) = resolved
        if (seedCol == field.key.goalCol && seedRow == field.key.goalRow) {
            field.effectiveGoalWorld = field.goalWorld
            field.goalWasNearestFallback = false
            seed(seedRow * w + seedCol, 0f)
            return
        }

        // Seed ONLY the single unique nearest-reachable cell matching effectiveGoalWorld so all
        // units converge cleanly around one consistent boundary target instead of splitting across
        // equidistant rock corners.
        field.effectiveGoalWorld = Position((seedCol + 0.5) * field.tileWidth, (seedRow + 0.5) * field.tileHeight)
        field.goalWasNearestFallback = true
        seed(seedRow * w + seedCol, 0f)
    }

    private fun cellSlowness(field: ChunkedField, idx: Int): Float {
        var f = BASE_STEP_COST
        if (field.key.smallYieldsWideCorridor && field.wideYieldMask[idx]) {
            f += WIDE_CORRIDOR_YIELD_SURCHARGE
        }
        f += field.congestionPenalty[idx].toFloat()
        return f
    }

    /**
     * Exact 2D orthogonal upwind Eikonal quadratic update:
     * `max(u - ux, 0)^2 + max(u - uy, 0)^2 = f^2` with `f >= 10f > 0`.
     * Guaranteed strictly greater than `min(ux, uy)`, preserving Fast Marching monotonicity.
     */
    private fun solveEikonal(ux: Float, uy: Float, f: Float): Float {
        val lo = min(ux, uy)
        if (lo >= INF_COST_F) return INF_COST_F
        val hi = max(ux, uy)
        if (hi >= INF_COST_F || hi - lo >= f) {
            return lo + f
        }
        val diff = hi - lo
        val disc = max(0f, 2f * f * f - diff * diff)
        return 0.5f * (lo + hi + sqrt(disc))
    }

    private fun expandLiveWavefront(field: ChunkedField, maxExpansions: Int): Int {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val reqRings = field.requiredRings
        val costArr = field.cost
        val hasReused = field.hasReusedMask
        val reusedMask = field.reusedMask
        var expanded = 0

        while (expanded < maxExpansions && field.heapSize > 0) {
            val poppedKey = field.peekHeapKey()
            val cell = field.popHeapCell()
            val curCost = costArr[cell]
            if (poppedKey > curCost + 1e-4f || curCost >= INF_COST_F) continue
            val col = cell % w
            val row = cell / w
            expanded++
            field.totalExpansions++

            val chunkIdx = (row / CHUNK_SIZE) * field.chunksX + (col / CHUNK_SIZE)
            field.chunkBuilt[chunkIdx] = true
            field.chunkDirty[chunkIdx] = false

            for (d in 0 until 4) {
                val nc = col + ORTHO_DX[d]
                val nr = row + ORTHO_DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                if (cf.clearanceRingsAt(nIdx) < reqRings) continue
                if (hasReused && reusedMask[nIdx]) continue
                val ux = min(
                    if (nc > 0 && cf.clearanceRingsAt(nIdx - 1) >= reqRings && (!hasReused || !reusedMask[nIdx - 1])) costArr[nIdx - 1] else INF_COST_F,
                    if (nc + 1 < w && cf.clearanceRingsAt(nIdx + 1) >= reqRings && (!hasReused || !reusedMask[nIdx + 1])) costArr[nIdx + 1] else INF_COST_F,
                )
                val uy = min(
                    if (nr > 0 && cf.clearanceRingsAt(nIdx - w) >= reqRings && (!hasReused || !reusedMask[nIdx - w])) costArr[nIdx - w] else INF_COST_F,
                    if (nr + 1 < h && cf.clearanceRingsAt(nIdx + w) >= reqRings && (!hasReused || !reusedMask[nIdx + w])) costArr[nIdx + w] else INF_COST_F,
                )
                val nextCost = solveEikonal(ux, uy, cellSlowness(field, nIdx))
                if (nextCost + 1e-3f < costArr[nIdx]) {
                    costArr[nIdx] = nextCost
                    field.pushHeap(nIdx, nextCost)
                }
            }
            recomputeCellGradientOn(field, col, row, costArr, field.dirX, field.dirY)
        }
        if (field.heapSize == 0) {
            if (hasReused) {
                // Smoothly align reused-chunk boundary cells downhill into the freshly expanded halo.
                for (r in 0 until h) {
                    val base = r * w
                    for (c in 0 until w) {
                        val idx = base + c
                        if (!reusedMask[idx] || costArr[idx] >= INF_COST_F) continue
                        val bordersNew =
                            (c > 0 && !reusedMask[idx - 1]) ||
                            (c + 1 < w && !reusedMask[idx + 1]) ||
                            (r > 0 && !reusedMask[idx - w]) ||
                            (r + 1 < h && !reusedMask[idx + w])
                        if (bordersNew) {
                            recomputeCellGradientOn(field, c, r, costArr, field.dirX, field.dirY)
                        }
                    }
                }
                field.hasReusedMask = false
                field.reusedMask = EMPTY_BOOLEAN_ARRAY
            }
            field.liveValid = true
            field.completed = true
        }
        return expanded
    }

    private fun expandShadowWavefront(field: ChunkedField, maxExpansions: Int): Int {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val reqRings = field.requiredRings
        val sCost = field.shadowCost
        var expanded = 0

        while (expanded < maxExpansions && field.shadowHeapSize > 0) {
            val poppedKey = field.peekShadowHeapKey()
            val cell = field.popShadowHeapCell()
            val curCost = sCost[cell]
            if (poppedKey > curCost + 1e-4f || curCost >= INF_COST_F) continue
            val col = cell % w
            val row = cell / w
            expanded++
            field.totalExpansions++

            for (d in 0 until 4) {
                val nc = col + ORTHO_DX[d]
                val nr = row + ORTHO_DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                if (cf.clearanceRingsAt(nIdx) < reqRings) continue
                val ux = min(
                    if (nc > 0 && cf.clearanceRingsAt(nIdx - 1) >= reqRings) sCost[nIdx - 1] else INF_COST_F,
                    if (nc + 1 < w && cf.clearanceRingsAt(nIdx + 1) >= reqRings) sCost[nIdx + 1] else INF_COST_F,
                )
                val uy = min(
                    if (nr > 0 && cf.clearanceRingsAt(nIdx - w) >= reqRings) sCost[nIdx - w] else INF_COST_F,
                    if (nr + 1 < h && cf.clearanceRingsAt(nIdx + w) >= reqRings) sCost[nIdx + w] else INF_COST_F,
                )
                val nextCost = solveEikonal(ux, uy, cellSlowness(field, nIdx))
                if (nextCost + 1e-3f < sCost[nIdx]) {
                    sCost[nIdx] = nextCost
                    field.pushShadowHeap(nIdx, nextCost)
                }
            }
        }
        if (field.shadowHeapSize == 0) {
            commitShadowSwap(field)
        }
        return expanded
    }

    /**
     * Wall-safe upwind negative gradient:
     * Only takes drops toward lower-cost passable orthogonal neighbors (`drop > 0`); blocked walls
     * contribute 0 drop, so vectors never point into wall faces, remain isotropic in open fields,
     * align strictly along 1-tile narrow gates, and stay transversely uniform inside wide gates.
     */
    private fun recomputeCellGradientOn(
        field: ChunkedField,
        col: Int,
        row: Int,
        costArr: FloatArray,
        outDx: FloatArray,
        outDy: FloatArray,
    ) {
        val w = field.width
        val h = field.height
        val idx = row * w + col
        val ownCost = costArr[idx]
        if (ownCost <= 0f || ownCost >= INF_COST_F) {
            outDx[idx] = 0f
            outDy[idx] = 0f
            return
        }
        val cf = field.terrain.clearanceField()
        val req = field.requiredRings

        val dropL = if (col > 0 && cf.clearanceRingsAt(idx - 1) >= req) max(0f, ownCost - costArr[idx - 1]) else 0f
        val dropR = if (col + 1 < w && cf.clearanceRingsAt(idx + 1) >= req) max(0f, ownCost - costArr[idx + 1]) else 0f
        val dropU = if (row > 0 && cf.clearanceRingsAt(idx - w) >= req) max(0f, ownCost - costArr[idx - w]) else 0f
        val dropD = if (row + 1 < h && cf.clearanceRingsAt(idx + w) >= req) max(0f, ownCost - costArr[idx + w]) else 0f

        // On a watershed ridge where BOTH opposite directions descend toward different gates, pick the
        // steeper downhill branch instead of subtracting them into a zero vector (prevents wall-face stalls!).
        var gx = when {
            dropL > 0f && dropR > 0f -> if (dropR >= dropL) dropR.toDouble() else -dropL.toDouble()
            else -> (dropR - dropL).toDouble()
        }
        var gy = when {
            dropU > 0f && dropD > 0f -> if (dropU >= dropD) -dropU.toDouble() else dropD.toDouble()
            else -> (dropD - dropU).toDouble()
        }
        var mag = hypot(gx, gy)
        if (mag <= 1e-4) {
            // Tie-break on rare saddle cell via best single passable orthogonal neighbor
            var bestDrop = 0f
            for (d in 0 until 4) {
                val nc = col + ORTHO_DX[d]
                val nr = row + ORTHO_DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                if (cf.clearanceRingsAt(nIdx) < req) continue
                val drop = ownCost - costArr[nIdx]
                if (drop > bestDrop) {
                    bestDrop = drop
                    gx = ORTHO_DX[d].toDouble()
                    gy = ORTHO_DY[d].toDouble()
                }
            }
            mag = hypot(gx, gy)
        }
        if (mag > 1e-4) {
            outDx[idx] = (gx / mag).toFloat()
            outDy[idx] = (gy / mag).toFloat()
        } else {
            outDx[idx] = 0f
            outDy[idx] = 0f
        }
    }

    /**
     * Obstructed low-speed gate-queue congestion feedback:
     * - Counts ONLY vehicles in or upstream-approaching a constricted gate throat whose forward progress
     *   along the flow field is below `FREE_FLOW_SPEED_FRACTION * moveSpeed` (smooth free-flowing
     *   traffic exiting a gate pays ZERO penalty).
     * - Credits each obstructed vehicle to at most ONE gate throat cell (walking at most 4 tiles
     *   downstream along `(dirX, dirY)`), stopping immediately at any blocked wall tile so each
     *   queue segment is counted ONCE and never double-counted or attributed across walls.
     * - Triggers a pure monotonic shadow double-buffer rebuild with cut-cost invariance so live
     *   gradients stay tear-free and loop-free.
     */
    private fun updateObstructedQueueCongestion(spatialIndex: WorldSpatialIndex) {
        val frozenUnits = spatialIndex.frozenLayerUnits(SpatialLayer.SURFACE)
        for (field in fields.values) {
            if (field.activeSubscribers <= 0 || !field.liveValid || field.shadowActive) continue
            val w = field.width
            val h = field.height
            val tw = field.tileWidth.toDouble()
            val th = field.tileHeight.toDouble()
            val throatMask = field.gateThroatMask
            field.ensureCongestionScratch()
            val obstructedLoad = field.congestionLoadScratch

            for (i in 0 until frozenUnits.size) {
                val u = frozenUnits[i]
                if (u.ownerPlayerId != field.key.ownerId) continue
                if (!u.hasMoveOrder) continue
                val col = floor(u.position.x / tw).toInt()
                val row = floor(u.position.y / th).toInt()
                if (col !in 0 until w || row !in 0 until h) continue
                val idx = row * w + col
                if (field.cost[idx] >= INF_COST_F) continue

                val fx = field.dirX[idx].toDouble()
                val fy = field.dirY[idx].toDouble()
                val fLen = hypot(fx, fy)
                if (fLen < 1e-4) continue
                val nfx = fx / fLen
                val nfy = fy / fLen

                val vx: Double
                val vy: Double
                if (u.slidingMode) {
                    vx = u.flightVelocity.x
                    vy = u.flightVelocity.y
                } else {
                    val rad = Math.toRadians(u.heading)
                    vx = sin(rad) * u.speed
                    vy = -cos(rad) * u.speed
                }
                val fwdSpeed = vx * nfx + vy * nfy
                val speedMag = hypot(vx, vy)
                val freeFlowThreshold = FREE_FLOW_SPEED_FRACTION * max(0.2, u.moveSpeed)

                // Smooth free-flowing vehicles making >= 55% forward progress along flow pay ZERO congestion toll.
                if (fwdSpeed >= freeFlowThreshold) continue
                // Fast-moving vehicles already moving away from the flow direction do not represent a forward gate queue.
                if (speedMag >= freeFlowThreshold && (vx * nfx + vy * nfy) / speedMag < -0.25) continue

                // Attribute this obstructed unit to AT MOST ONE gate throat cell (either current cell or
                // the first constricted throat cell within 4 tiles downstream along flow, stopping at walls).
                var targetGateIdx = -1
                if (throatMask[idx]) {
                    targetGateIdx = idx
                } else {
                    for (ahead in 1..4) {
                        val gc = (col + kotlin.math.round(nfx * ahead).toInt()).coerceIn(0, w - 1)
                        val gr = (row + kotlin.math.round(nfy * ahead).toInt()).coerceIn(0, h - 1)
                        if (field.terrain.isBlocked(gc, gr)) break
                        val gIdx = gr * w + gc
                        if (throatMask[gIdx] && field.cost[gIdx] < INF_COST_F) {
                            targetGateIdx = gIdx
                            break
                        }
                    }
                }
                if (targetGateIdx >= 0) {
                    val weight = if (u.hardRadius >= 10.0) 2 else 1
                    obstructedLoad[targetGateIdx] += weight
                }
            }

            // Aggregate transversely across contiguous gate-throat cells in the same constriction slice so
            // a wide gate blocked by slow heavies charges a uniform balanced toll across the whole gate opening
            // (preventing small cars from cramming along the 1-tile shoulder of a congested wide gate).
            val gateSliceLoad = field.gateSliceLoadScratch
            val gateSliceWidth = field.gateSliceWidthScratch
            for (r in 0 until h) {
                val base = r * w
                for (c in 0 until w) {
                    val idx = base + c
                    if (!throatMask[idx]) continue
                    // Determine whether this throat is pinched vertically (walls up/down -> gate opens along Y)
                    // or horizontally (walls left/right -> gate opens along X).
                    val vertWall = NavigationTopologyMasks.isVerticallyBoundedThroat(field.terrain, c, r)
                    var sum = obstructedLoad[idx]
                    var widthCnt = 1
                    if (vertWall) {
                        var y = r - 1
                        while (y >= 0 && throatMask[y * w + c] && widthCnt < 8) {
                            sum += obstructedLoad[y * w + c]; widthCnt++; y--
                        }
                        y = r + 1
                        while (y < h && throatMask[y * w + c] && widthCnt < 8) {
                            sum += obstructedLoad[y * w + c]; widthCnt++; y++
                        }
                    } else {
                        var x = c - 1
                        while (x >= 0 && throatMask[base + x] && widthCnt < 8) {
                            sum += obstructedLoad[base + x]; widthCnt++; x--
                        }
                        x = c + 1
                        while (x < w && throatMask[base + x] && widthCnt < 8) {
                            sum += obstructedLoad[base + x]; widthCnt++; x++
                        }
                    }
                    gateSliceLoad[idx] = sum
                    gateSliceWidth[idx] = widthCnt
                }
            }

            var changedAny = false
            var anyActive = false
            var minChangedCost = INF_COST_F
            for (i in 0 until field.cellCount) {
                val oldPen = field.congestionPenalty[i].toInt()
                val targetPen = if (!throatMask[i]) {
                    0
                } else {
                    val sliceLoad = gateSliceLoad[i]
                    val sliceW = max(1, gateSliceWidth[i])
                    val densityPerLane = sliceLoad.toDouble() / sliceW.toDouble()
                    val ownLoad = obstructedLoad[i]
                    when {
                        // Single-tile pinhole: gently penalize only when genuine multi-unit queue forms
                        sliceW == 1 && sliceLoad >= 3 -> min(MAX_CONGESTION_PENALTY, (sliceLoad - 2) * 14)
                        // Multi-tile wide gate or 2-tile narrow gate: scale by obstructed density per lane
                        sliceW >= 2 && (densityPerLane >= 0.75 || ownLoad >= 2) ->
                            min(MAX_CONGESTION_PENALTY, ((densityPerLane - 0.5) * 42.0).toInt().coerceAtLeast(if (ownLoad >= 2) (ownLoad - 1) * 12 else 0))
                        else -> 0
                    }
                }
                val nextPen = when {
                    targetPen >= oldPen + HYSTERESIS_UP_STEP -> min(MAX_CONGESTION_PENALTY, oldPen + HYSTERESIS_UP_STEP)
                    targetPen > oldPen + HYSTERESIS_DEADBAND -> targetPen
                    targetPen == 0 && oldPen > 0 -> max(0, oldPen - HYSTERESIS_DOWN_STEP)
                    targetPen + HYSTERESIS_DEADBAND < oldPen -> max(targetPen, oldPen - HYSTERESIS_DOWN_STEP)
                    else -> oldPen
                }
                if (nextPen > 0) anyActive = true
                if (nextPen != oldPen) {
                    if (!changedAny) recordFieldMutation(field.key)
                    field.congestionPenalty[i] = nextPen.toShort()
                    changedAny = true
                    val c = field.cost[i]
                    if (c < minChangedCost) minChangedCost = c
                    val col = i % w
                    val row = i / w
                    for (d in 0 until 4) {
                        val nc = col + ORTHO_DX[d]
                        val nr = row + ORTHO_DY[d]
                        if (nc in 0 until w && nr in 0 until h) {
                            val ncCost = field.cost[nr * w + nc]
                            if (ncCost < minChangedCost) minChangedCost = ncCost
                        }
                    }
                }
            }
            if (field.anyCongestionActive != anyActive) {
                if (!changedAny) recordFieldMutation(field.key)
                field.anyCongestionActive = anyActive
            }
            if (changedAny) {
                startShadowMonotonicRebuild(field, minChangedCost, terrainChanged = false)
            }
        }
    }

    private fun copyChunkFrom(src: ChunkedField, dst: ChunkedField, cx: Int, cy: Int, shiftBias: Float) {
        val r0 = cy * CHUNK_SIZE
        val r1 = min(dst.height, r0 + CHUNK_SIZE)
        val c0 = cx * CHUNK_SIZE
        val c1 = min(dst.width, c0 + CHUNK_SIZE)
        val w = dst.width
        val dstReused = dst.reusedMask
        for (r in r0 until r1) {
            val start = r * w + c0
            val len = c1 - c0
            for (k in 0 until len) {
                val idx = start + k
                val sc = src.cost[idx]
                dst.cost[idx] = if (sc < INF_COST_F) sc + shiftBias else INF_COST_F
                dstReused[idx] = true
            }
            System.arraycopy(src.dirX, start, dst.dirX, start, len)
            System.arraycopy(src.dirY, start, dst.dirY, start, len)
        }
    }

    companion object {
        internal const val CHUNK_SIZE: Int = 16
        internal const val MIN_GROUP_SIZE: Int = 4
        internal const val MAX_CACHED_FIELDS: Int = 12
        internal const val INF_COST_F: Float = 1_000_000_000f
        internal const val INF_COST: Int = 1_000_000_000
        internal const val BASE_STEP_COST: Float = 10.0f
        internal const val WIDE_CORRIDOR_YIELD_SURCHARGE: Float = 10.0f
        internal const val MAX_CONGESTION_PENALTY: Int = 110
        internal const val HYSTERESIS_UP_STEP: Int = 20
        internal const val HYSTERESIS_DOWN_STEP: Int = 6
        internal const val HYSTERESIS_DEADBAND: Int = 5
        internal const val FREE_FLOW_SPEED_FRACTION: Double = 0.55
        internal const val CONGESTION_REFRESH_INTERVAL: Long = 24L
        internal const val TRUNK_REUSE_RADIUS_TILES: Double = 14.0
        internal const val TRUNK_REUSE_LOCAL_HALO_TILES: Int = 8

        private val EMPTY_BOOLEAN_ARRAY = BooleanArray(0)
        private val EMPTY_FLOAT_ARRAY = FloatArray(0)
        private val EMPTY_INT_ARRAY = IntArray(0)
        internal var gateMaskComputeCount: Long
            get() = PlanningTerrain.gateMaskComputeCount
            set(value) { PlanningTerrain.gateMaskComputeCount = value }

        private val ORTHO_DX = intArrayOf(1, -1, 0, 0)
        private val ORTHO_DY = intArrayOf(0, 0, 1, -1)
        private val DX = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)

        internal fun flowPassabilityRadius(unit: MutableUnit, tileWidth: Int = 20, tileHeight: Int = 20): Double =
            when (val shape = unit.definition.movementCollision) {
                is MovementCollisionShape.Rectangle -> shape.boundingRadius
                else -> UnitLocomotion.terrainPassabilityRadius(unit, tileWidth, tileHeight)
            }

        internal fun clearanceBandFor(terrain: PlanningTerrain, radius: Double): Int =
            terrain.clearanceBandFor(radius)

        internal fun singleTilePinholeAxis(terrain: PlanningTerrain, col: Int, row: Int): Int =
            terrain.singleTilePinholeAxis(col, row)
    }
}
