package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.path.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Dual-adapter global navigation guidance seam (ADR-012 / ENG-P016-008).
 *
 * Unifies:
 * - [ChunkedFlowFieldAdapter]: on-demand chunked shared flow fields for large same-layer group moves
 *   with clearance-band wide-corridor yield cost for small units, hysteresis-damped soft congestion
 *   splitting across multi-gap stone/chokepoint layouts, localized dirty-chunk rebuild on new buildings
 *   or fog discoveries, rapid re-click trunk reuse, and cul-de-sac capacity saturation overflow.
 * - [WaypointRouteAdapter]: direct/A* polyline & rectangular `HullRoute` guidance for small squads,
 *   rectangular naval hulls, and fallback when a flow field has not yet reached the unit's chunk.
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
        val overflowSettled: Boolean = false,
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
 * Standard waypoint/polyline adapter for small squads, rectangular naval hulls, and individual moves.
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
        val dx = fallbackAim.x - unit.position.x
        val dy = fallbackAim.y - unit.position.y
        val dist = hypot(dx, dy)
        val dirX = if (dist > 1e-6) dx / dist else 0.0
        val dirY = if (dist > 1e-6) dy / dist else 0.0
        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
        val clearRings = terrain.clearanceField().clearanceRings(col, row)
        val clearWorld = if (clearRings <= 0) 0.0 else (clearRings * 2.0 - 1.0) * minTile
        return NavigationGuidance.Sample(
            aimPosition = fallbackAim,
            dirX = dirX,
            dirY = dirY,
            remainingDistance = dist,
            corridorClearanceWorld = clearWorld,
            finalApproach = finalPoint,
            fromFlowField = false,
            overflowSettled = false,
        )
    }
}

/**
 * On-demand chunked shared flow-field manager & adapter (ENG-P016-008).
 *
 * - Chunked 16x16 tiles expanded under P1 step budget; local building/fog edits mark only affected
 *   chunks (+ 1-chunk halo) dirty and rebuild locally from clean boundary cells.
 * - Clearance bands & wide-corridor yield: in mixed groups with large units, small-unit fields add
 *   a mild surcharge on scarce wide corridors so small units prefer narrower side gaps when available
 *   while still traversing wide corridors when they are the only route.
 * - Hysteresis-smoothed soft congestion splitting across parallel stone/chokepoint gaps.
 * - Rapid re-click cancellation + trunk reuse outside the shifted goal halo.
 * - Cul-de-sac capacity saturation overflow for trailing units behind settled teammates.
 * - Rollback-safe via [fork] for candidate-step transactions.
 */
internal class ChunkedFlowFieldAdapter(
    private val fields: LinkedHashMap<FieldKey, ChunkedField> = linkedMapOf(),
    private var lastCongestionStep: Long = -1L,
) : NavigationGuidance {

    internal data class FieldKey(
        val ownerId: String,
        val movementType: MovementType,
        val clearanceBand: Int,
        val goalCol: Int,
        val goalRow: Int,
        val smallYieldsWideCorridor: Boolean,
    )

    internal class ChunkedField(
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
        var lastAccessStep: Long,
        var completed: Boolean = false,
        var totalExpansions: Long = 0L,
        var dirtyRebuildCount: Int = 0,
        var reusedTrunkChunks: Int = 0,
    ) {
        val cellCount: Int = width * height
        val cost: IntArray = IntArray(cellCount) { INF_COST }
        val dirX: FloatArray = FloatArray(cellCount)
        val dirY: FloatArray = FloatArray(cellCount)
        val chunkDirty: BooleanArray = BooleanArray(chunksX * chunksY) { true }
        val chunkBuilt: BooleanArray = BooleanArray(chunksX * chunksY) { false }
        val congestionPenalty: ShortArray = ShortArray(cellCount)
        var heapKeys: IntArray = IntArray(256)
        var heapCells: IntArray = IntArray(256)
        var heapSize: Int = 0
        val inFrontier: BooleanArray = BooleanArray(cellCount)

        fun pushHeap(cell: Int, priority: Int) {
            if (heapSize >= heapKeys.size) {
                val nextCap = heapKeys.size * 2
                heapKeys = heapKeys.copyOf(nextCap)
                heapCells = heapCells.copyOf(nextCap)
            }
            var idx = heapSize++
            while (idx > 0) {
                val parent = (idx - 1) ushr 1
                if (heapKeys[parent] <= priority) break
                heapKeys[idx] = heapKeys[parent]
                heapCells[idx] = heapCells[parent]
                idx = parent
            }
            heapKeys[idx] = priority
            heapCells[idx] = cell
            inFrontier[cell] = true
        }

        fun peekHeapKey(): Int = heapKeys[0]

        fun clearHeap() {
            heapSize = 0
            inFrontier.fill(false)
        }

        fun popHeapCell(): Int {
            val top = heapCells[0]
            heapSize--
            if (heapSize > 0) {
                val lastKey = heapKeys[heapSize]
                val lastCell = heapCells[heapSize]
                var idx = 0
                val half = heapSize ushr 1
                while (idx < half) {
                    var child = (idx shl 1) + 1
                    val right = child + 1
                    if (right < heapSize && heapKeys[right] < heapKeys[child]) child = right
                    if (lastKey <= heapKeys[child]) break
                    heapKeys[idx] = heapKeys[child]
                    heapCells[idx] = heapCells[child]
                    idx = child
                }
                heapKeys[idx] = lastKey
                heapCells[idx] = lastCell
            }
            inFrontier[top] = false
            return top
        }

        fun copyForRollback(): ChunkedField {
            val copy = ChunkedField(
                key, width, height, tileWidth, tileHeight, chunksX, chunksY,
                requiredRings, wideCorridorRings, terrain, goalWorld, lastAccessStep,
                completed, totalExpansions, dirtyRebuildCount, reusedTrunkChunks,
            )
            System.arraycopy(cost, 0, copy.cost, 0, cellCount)
            System.arraycopy(dirX, 0, copy.dirX, 0, cellCount)
            System.arraycopy(dirY, 0, copy.dirY, 0, cellCount)
            System.arraycopy(chunkDirty, 0, copy.chunkDirty, 0, chunkDirty.size)
            System.arraycopy(chunkBuilt, 0, copy.chunkBuilt, 0, chunkBuilt.size)
            System.arraycopy(congestionPenalty, 0, copy.congestionPenalty, 0, cellCount)
            copy.heapKeys = heapKeys.copyOf()
            copy.heapCells = heapCells.copyOf()
            copy.heapSize = heapSize
            System.arraycopy(inFrontier, 0, copy.inFrontier, 0, cellCount)
            return copy
        }
    }

    fun fork(): ChunkedFlowFieldAdapter {
        val next = LinkedHashMap<FieldKey, ChunkedField>(fields.size)
        for ((k, v) in fields) next[k] = v.copyForRollback()
        return ChunkedFlowFieldAdapter(next, lastCongestionStep)
    }

    fun activeFieldCount(): Int = fields.size
    fun statsFor(key: FieldKey): ChunkedField? = fields[key]

    fun ensureFieldForGroup(
        ownerId: String,
        movementType: MovementType,
        unitRadius: Double,
        maxGroupRadius: Double,
        terrain: PlanningTerrain,
        goalWorld: Position,
        stepIndex: Long,
    ): ChunkedField {
        val band = clearanceBandFor(terrain, unitRadius)
        val smallYieldsWide = maxGroupRadius > unitRadius * 1.45 && band == 1
        val goalCol = floor(goalWorld.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val goalRow = floor(goalWorld.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val key = FieldKey(ownerId, movementType, band, goalCol, goalRow, smallYieldsWide)

        val existing = fields[key]
        if (existing != null) {
            existing.lastAccessStep = stepIndex
            if (existing.terrain !== terrain) {
                markChangedTerrainDirty(existing, terrain)
            }
            return existing
        }

        // Rapid re-click: cancel incomplete old builds for same owner/layer/band; reuse far trunk chunks if nearby.
        var reusableOld: ChunkedField? = null
        val staleKeys = mutableListOf<FieldKey>()
        for ((oldKey, oldField) in fields) {
            if (oldKey.ownerId == ownerId &&
                oldKey.movementType == movementType &&
                oldKey.clearanceBand == band &&
                oldKey.smallYieldsWideCorridor == smallYieldsWide
            ) {
                val distTiles = hypot((oldKey.goalCol - goalCol).toDouble(), (oldKey.goalRow - goalRow).toDouble())
                if (!oldField.completed) staleKeys += oldKey
                if (reusableOld == null && distTiles <= TRUNK_REUSE_RADIUS_TILES && oldField.terrain === terrain) {
                    reusableOld = oldField
                }
            }
        }
        for (k in staleKeys) fields.remove(k)

        while (fields.size >= MAX_CACHED_FIELDS) {
            val lru = fields.entries.minByOrNull { it.value.lastAccessStep }?.key ?: break
            fields.remove(lru)
        }

        val chunksX = (terrain.widthTiles + CHUNK_SIZE - 1) / CHUNK_SIZE
        val chunksY = (terrain.heightTiles + CHUNK_SIZE - 1) / CHUNK_SIZE
        val bigBand = clearanceBandFor(terrain, maxGroupRadius)
        val field = ChunkedField(
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
            lastAccessStep = stepIndex,
        )

        if (reusableOld != null && reusableOld.terrain === terrain) {
            val oldGCol = reusableOld.key.goalCol
            val oldGRow = reusableOld.key.goalRow
            val shiftRadius = max(
                TRUNK_REUSE_LOCAL_HALO_TILES,
                ceil(hypot((oldGCol - goalCol).toDouble(), (oldGRow - goalRow).toDouble())).toInt() + 4,
            )
            var reused = 0
            for (cy in 0 until chunksY) {
                for (cx in 0 until chunksX) {
                    val cIdx = cy * chunksX + cx
                    if (!reusableOld.chunkBuilt[cIdx] || reusableOld.chunkDirty[cIdx]) continue
                    val centerCol = cx * CHUNK_SIZE + CHUNK_SIZE / 2
                    val centerRow = cy * CHUNK_SIZE + CHUNK_SIZE / 2
                    val dNew = hypot((centerCol - goalCol).toDouble(), (centerRow - goalRow).toDouble())
                    if (dNew > shiftRadius * 1.5) {
                        copyChunkFrom(reusableOld, field, cx, cy)
                        field.chunkBuilt[cIdx] = true
                        field.chunkDirty[cIdx] = false
                        reused++
                    }
                }
            }
            field.reusedTrunkChunks = reused
        }

        seedGoalCells(field, goalCol, goalRow)
        fields[key] = field
        return field
    }

    fun advanceP1Budget(
        units: Collection<MutableUnit>,
        spatialIndex: WorldSpatialIndex,
        terrainLookup: (MutableUnit, MovementType) -> PlanningTerrain,
        stepIndex: Long,
        maxExpansions: Int,
    ): Int {
        if (fields.isEmpty() || maxExpansions <= 0) return 0

        val checkedOwners = HashSet<Pair<String, MovementType>>()
        for (unit in units) {
            if (!unit.alive || unit.carriedBy != null || unit.attachment != null) continue
            val hasPendingMove = unit.moveTarget != null ||
                (unit.landingMember != null && !unit.landingMember!!.parked) ||
                unit.tasks.firstOrNull()?.kind == UnitTaskKind.MOVE
            if (!hasPendingMove) continue
            val mType = UnitLocomotion.movement(unit).type
            if (mType == MovementType.AIR || mType == MovementType.NONE) continue
            val pair = unit.ownerPlayerId to mType
            if (!checkedOwners.add(pair)) continue
            val latestTerrain = terrainLookup(unit, mType)
            for (field in fields.values) {
                if (field.key.ownerId == unit.ownerPlayerId && field.key.movementType == mType && field.terrain !== latestTerrain) {
                    markChangedTerrainDirty(field, latestTerrain)
                }
            }
        }

        if (stepIndex - lastCongestionStep >= CONGESTION_REFRESH_INTERVAL) {
            lastCongestionStep = stepIndex
            updateSoftCongestionHysteresis(spatialIndex)
        }

        var used = 0
        val activeFields = fields.values.filter { !it.completed && it.heapSize > 0 }
        if (activeFields.isEmpty()) return 0
        val perFieldSlice = max(64, maxExpansions / activeFields.size)
        for (field in activeFields) {
            if (used >= maxExpansions) break
            used += expandWavefront(field, min(perFieldSlice, maxExpansions - used))
        }
        if (used < maxExpansions) {
            for (field in activeFields) {
                if (used >= maxExpansions) break
                if (field.heapSize > 0) used += expandWavefront(field, maxExpansions - used)
            }
        }
        return used
    }

    internal fun markChangedTerrainDirty(field: ChunkedField, newTerrain: PlanningTerrain) {
        val oldTerrain = field.terrain
        field.terrain = newTerrain
        val w = field.width
        val h = field.height
        val dirtyChunks = BooleanArray(field.chunksX * field.chunksY)
        var anyDirty = false
        var minBlockedOldCost = INF_COST
        for (row in 0 until h) {
            for (col in 0 until w) {
                val oldBlocked = oldTerrain.isBlocked(col, row)
                val newBlocked = newTerrain.isBlocked(col, row)
                val oldRings = oldTerrain.clearanceField().clearanceRings(col, row)
                val newRings = newTerrain.clearanceField().clearanceRings(col, row)
                val passabilityLost = (!oldBlocked && newBlocked) ||
                    (oldRings >= field.requiredRings && newRings < field.requiredRings)
                if (oldBlocked != newBlocked ||
                    (oldRings >= field.requiredRings) != (newRings >= field.requiredRings) ||
                    (field.key.smallYieldsWideCorridor && (oldRings >= field.wideCorridorRings) != (newRings >= field.wideCorridorRings))
                ) {
                    if (passabilityLost) {
                        val oldC = field.cost[row * w + col]
                        if (oldC < minBlockedOldCost) minBlockedOldCost = oldC
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
        if (!anyDirty) return
        // If new obstruction narrowed/blocked a passable tile, propagate dirtiness to any adjacent clean
        // downstream chunk whose cells depended on paths through the obstructed cost band (all border cells
        // on the far side having cost > minBlockedOldCost) so stale lower costs cannot back-feed loops into
        // the newly blocked region while preserving unaffected goal-side chunks intact.
        if (minBlockedOldCost < INF_COST) {
            var expandedDirty = true
            while (expandedDirty) {
                expandedDirty = false
                for (cy in 0 until field.chunksY) {
                    for (cx in 0 until field.chunksX) {
                        val cIdx = cy * field.chunksX + cx
                        if (dirtyChunks[cIdx] || !field.chunkBuilt[cIdx]) continue
                        val adjDirty =
                            (cx > 0 && dirtyChunks[cIdx - 1]) ||
                            (cx + 1 < field.chunksX && dirtyChunks[cIdx + 1]) ||
                            (cy > 0 && dirtyChunks[cIdx - field.chunksX]) ||
                            (cy + 1 < field.chunksY && dirtyChunks[cIdx + field.chunksX])
                        if (!adjDirty) continue
                        val r0 = cy * CHUNK_SIZE
                        val r1 = min(h, r0 + CHUNK_SIZE)
                        val c0 = cx * CHUNK_SIZE
                        val c1 = min(w, c0 + CHUNK_SIZE)
                        var hasDownstream = false
                        var hasStrictlyUpstream = false
                        for (r in r0 until r1) {
                            val base = r * w
                            for (c in c0 until c1) {
                                val cost = field.cost[base + c]
                                if (cost < INF_COST) {
                                    if (cost > minBlockedOldCost) hasDownstream = true
                                    else if (cost < minBlockedOldCost) hasStrictlyUpstream = true
                                }
                            }
                        }
                        if (hasDownstream && !hasStrictlyUpstream) {
                            dirtyChunks[cIdx] = true
                            expandedDirty = true
                        }
                    }
                }
            }
        }
        field.dirtyRebuildCount++
        applyDirtyChunkReset(field, dirtyChunks, preserveGradients = false)
    }

    private fun applyDirtyChunkReset(field: ChunkedField, dirtyChunks: BooleanArray, preserveGradients: Boolean) {
        val w = field.width
        val h = field.height
        field.completed = false
        // Preserve only clean-chunk frontier entries currently in the heap and discard stale entries from
        // dirty chunks so mid-build terrain/congestion updates never pop INF_COST cells or jam inFrontier.
        val keptCells = IntArray(field.heapSize)
        val keptKeys = IntArray(field.heapSize)
        var keptCount = 0
        for (i in 0 until field.heapSize) {
            val cell = field.heapCells[i]
            val key = field.heapKeys[i]
            val cIdx = ((cell / w) / CHUNK_SIZE) * field.chunksX + ((cell % w) / CHUNK_SIZE)
            if (!dirtyChunks[cIdx] && key == field.cost[cell] && key < INF_COST) {
                keptCells[keptCount] = cell
                keptKeys[keptCount] = key
                keptCount++
            }
        }
        field.clearHeap()
        for (i in 0 until keptCount) {
            if (!field.inFrontier[keptCells[i]]) {
                field.pushHeap(keptCells[i], keptKeys[i])
            }
        }
        for (cy in 0 until field.chunksY) {
            for (cx in 0 until field.chunksX) {
                val cIdx = cy * field.chunksX + cx
                if (!dirtyChunks[cIdx]) continue
                field.chunkDirty[cIdx] = true
                field.chunkBuilt[cIdx] = false
                val r0 = cy * CHUNK_SIZE
                val r1 = min(h, r0 + CHUNK_SIZE)
                val c0 = cx * CHUNK_SIZE
                val c1 = min(w, c0 + CHUNK_SIZE)
                for (r in r0 until r1) {
                    val base = r * w
                    for (c in c0 until c1) {
                        val idx = base + c
                        field.cost[idx] = INF_COST
                        field.inFrontier[idx] = false
                        if (!preserveGradients) {
                            field.dirX[idx] = 0f
                            field.dirY[idx] = 0f
                        }
                    }
                }
            }
        }
        val gcx = field.key.goalCol / CHUNK_SIZE
        val gcy = field.key.goalRow / CHUNK_SIZE
        if (dirtyChunks[gcy * field.chunksX + gcx]) {
            seedGoalCells(field, field.key.goalCol, field.key.goalRow)
        }
        for (cy in 0 until field.chunksY) {
            for (cx in 0 until field.chunksX) {
                val cIdx = cy * field.chunksX + cx
                if (dirtyChunks[cIdx]) continue
                val adjDirty =
                    (cx > 0 && dirtyChunks[cIdx - 1]) ||
                    (cx + 1 < field.chunksX && dirtyChunks[cIdx + 1]) ||
                    (cy > 0 && dirtyChunks[cIdx - field.chunksX]) ||
                    (cy + 1 < field.chunksY && dirtyChunks[cIdx + field.chunksX])
                if (!adjDirty) continue
                val r0 = cy * CHUNK_SIZE
                val r1 = min(h, r0 + CHUNK_SIZE)
                val c0 = cx * CHUNK_SIZE
                val c1 = min(w, c0 + CHUNK_SIZE)
                for (r in r0 until r1) {
                    for (c in c0 until c1) {
                        if (r != r0 && r != r1 - 1 && c != c0 && c != c1 - 1) continue
                        val idx = r * w + c
                        val cur = field.cost[idx]
                        if (cur < INF_COST && !field.inFrontier[idx]) {
                            field.pushHeap(idx, cur)
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns true when a shared chunked flow field already covers [unit]'s live cell (or 1-tile ring)
     * with a finite integration cost, enabling immediate O(1) group startup without waiting for 100
     * individual A* searches in P0/P1.
     */
    fun hasValidGuidanceFor(unit: MutableUnit, terrain: PlanningTerrain): Boolean {
        val group = unit.landingMember?.group ?: return false
        if (group.members.size < MIN_GROUP_SIZE ||
            unit.definition.movementCollision is MovementCollisionShape.Rectangle
        ) return false
        val movement = UnitLocomotion.movement(unit)
        if (movement.type == MovementType.AIR || movement.type == MovementType.NONE) return false
        val unitRadius = max(1.0, UnitLocomotion.terrainPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val band = clearanceBandFor(terrain, unitRadius)
        val goalCol = floor(group.click.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val goalRow = floor(group.click.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val maxGroupR = max(unitRadius, group.maxRadiusByMovement[movement.type] ?: unitRadius)
        val wantYieldWide = maxGroupR > unitRadius * 1.45 && band == 1
        val field = fields[FieldKey(unit.ownerPlayerId, movement.type, band, goalCol, goalRow, wantYieldWide)]
            ?: return false
        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        if (field.cost[row * field.width + col] < INF_COST) return true
        for (d in 0 until 8) {
            val nc = col + DX[d]
            val nr = row + DY[d]
            if (nc in 0 until field.width && nr in 0 until field.height && field.cost[nr * field.width + nc] < INF_COST) {
                return true
            }
        }
        return false
    }

    override fun sampleGuidance(
        host: UnitMovementHost,
        unit: MutableUnit,
        terrain: PlanningTerrain,
        fallbackAim: Position,
        finalPoint: Boolean,
        executionStep: Long,
    ): NavigationGuidance.Sample {
        val group = unit.landingMember?.group
        if (group == null || group.members.size < MIN_GROUP_SIZE ||
            unit.definition.movementCollision is MovementCollisionShape.Rectangle
        ) {
            return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
        }
        val movement = UnitLocomotion.movement(unit)
        if (movement.type == MovementType.AIR || movement.type == MovementType.NONE) {
            return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
        }
        val unitRadius = max(1.0, UnitLocomotion.terrainPassabilityRadius(unit, terrain.tileWidth, terrain.tileHeight))
        val band = clearanceBandFor(terrain, unitRadius)
        val goalCol = floor(group.click.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val goalRow = floor(group.click.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        val maxGroupR = max(unitRadius, group.maxRadiusByMovement[movement.type] ?: unitRadius)
        val wantYieldWide = maxGroupR > unitRadius * 1.45 && band == 1
        val field = fields[FieldKey(unit.ownerPlayerId, movement.type, band, goalCol, goalRow, wantYieldWide)]
            ?: return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)

        val col = floor(unit.position.x / terrain.tileWidth).toInt().coerceIn(0, terrain.widthTiles - 1)
        val row = floor(unit.position.y / terrain.tileHeight).toInt().coerceIn(0, terrain.heightTiles - 1)
        var idx = row * field.width + col
        var cellCost = field.cost[idx]
        if (cellCost >= INF_COST) {
            // A unit hugging a shoreline or wall edge (e.g. radius 11 -> band 2 on a rings==1 edge tile) may
            // stand 1-3 tiles outside band-valid interior cells; snap sample lookup to the nearest valid cell
            // so flow-field steering and saturation overflow remain active.
            var bestIdx = -1
            var bestCost = INF_COST
            for (ring in 1..3) {
                for (dy in -ring..ring) {
                    val nr = row + dy
                    if (nr !in 0 until field.height) continue
                    for (dx in -ring..ring) {
                        if (abs(dx) != ring && abs(dy) != ring) continue
                        val nc = col + dx
                        if (nc !in 0 until field.width) continue
                        val nIdx = nr * field.width + nc
                        val ncCost = field.cost[nIdx]
                        if (ncCost < bestCost) {
                            bestCost = ncCost
                            bestIdx = nIdx
                        }
                    }
                }
                if (bestIdx >= 0) break
            }
            if (bestIdx < 0) {
                return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
            }
            idx = bestIdx
            cellCost = bestCost
        }

        var vx = field.dirX[idx].toDouble()
        var vy = field.dirY[idx].toDouble()
        var vecLen = hypot(vx, vy)
        if (vecLen < 1e-4) {
            // Goal cell (cost == 0) has zero gradient by definition: aim toward true goalWorld so
            // shoreline / pocket-end units still participate in saturation-overflow settling instead of freezing.
            val gdx = field.goalWorld.x - unit.position.x
            val gdy = field.goalWorld.y - unit.position.y
            val gd = hypot(gdx, gdy)
            if (cellCost <= 40 && gd > 1e-4) {
                vx = gdx / gd
                vy = gdy / gd
                vecLen = 1.0
            } else {
                return WaypointRouteAdapter.sampleGuidance(host, unit, terrain, fallbackAim, finalPoint)
            }
        } else {
            vx /= vecLen
            vy /= vecLen
        }

        val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
        val remainingWorld = (cellCost / 10.0) * minTile
        val directGoalClear = PlanningSegmentClearance.allows(terrain, unit.position, field.goalWorld, host.routeRadius(unit))
        val safeAim = if (directGoalClear) {
            // In open line-of-sight stretches (no obstacles between unit and goal), aim straight at the true
            // continuous goal point rather than quantizing through 8-neighbor tile gradients (avoids octahedral
            // funneling pinch in open fields while preserving full flow-field obstacle routing & overflow checks).
            val gdx = field.goalWorld.x - unit.position.x
            val gdy = field.goalWorld.y - unit.position.y
            val gd = hypot(gdx, gdy)
            if (gd > 1e-4) {
                vx = gdx / gd
                vy = gdy / gd
            }
            field.goalWorld
        } else {
            // Walk up to 5 steepest-descent cells down the flow field and pick the furthest cell center with
            // verified PlanningSegmentClearance from the unit's live position. This keeps units cleanly curving
            // around staggered rock wall corners and gate jambs instead of aiming into wall faces.
            val routeR = host.routeRadius(unit)
            val cf = terrain.clearanceField()
            val reqR = field.requiredRings
            var curC = idx % field.width
            var curR = idx / field.width
            var curCost = cellCost
            var bestClearAim: Position? = null
            var firstHopCenter: Position? = null
            for (hop in 0 until 5) {
                var nextC = -1
                var nextR = -1
                var nextCost = curCost
                for (d in 0 until 8) {
                    val nc = curC + DX[d]
                    val nr = curR + DY[d]
                    if (nc !in 0 until field.width || nr !in 0 until field.height) continue
                    val nIdx = nr * field.width + nc
                    if (cf.clearanceRingsAt(nIdx) < reqR) continue
                    if (d >= 4 && (cf.clearanceRingsAt(curR * field.width + nc) < reqR ||
                            cf.clearanceRingsAt(nr * field.width + curC) < reqR)) continue
                    val ncCost = field.cost[nIdx]
                    if (ncCost < nextCost) {
                        nextCost = ncCost
                        nextC = nc
                        nextR = nr
                    }
                }
                if (nextC < 0) break
                curC = nextC
                curR = nextR
                curCost = nextCost
                val center = Position((curC + 0.5) * terrain.tileWidth, (curR + 0.5) * terrain.tileHeight)
                if (hop == 0) firstHopCenter = center
                val clearToCenter = PlanningSegmentClearance.allows(terrain, unit.position, center, routeR)
                if (clearToCenter) {
                    bestClearAim = center
                } else if (bestClearAim != null) {
                    break
                }
                // Stop lookahead inside a 1-tile pinhole so units align squarely onto the pinhole centerline
                // rather than cutting diagonally across the jamb toward post-gate cells.
                if (isSingleTilePinhole(terrain, curC, curR) && clearToCenter) {
                    break
                }
            }
            if (bestClearAim != null) {
                val adx = bestClearAim.x - unit.position.x
                val ady = bestClearAim.y - unit.position.y
                val ad = hypot(adx, ady)
                if (ad > 1e-4) {
                    vx = adx / ad
                    vy = ady / ad
                }
                bestClearAim
            } else {
                // Unit is off-center near a tight 1-tile gate jamb where the straight chord to the next cell center
                // grazes the corner. Aim toward the current valid flow-field cell center (centering onto the gate
                // axis) before falling back to short gradient or fallbackAim — NEVER fall back to an obstructed
                // behind-wall click when the current cell center is reachable!
                val startC = idx % field.width
                val startR = idx / field.width
                val ownCenter = Position((startC + 0.5) * terrain.tileWidth, (startR + 0.5) * terrain.tileHeight)
                val distOwn = hypot(ownCenter.x - unit.position.x, ownCenter.y - unit.position.y)
                if (distOwn > 1.5 && PlanningSegmentClearance.allows(terrain, unit.position, ownCenter, routeR)) {
                    vx = (ownCenter.x - unit.position.x) / distOwn
                    vy = (ownCenter.y - unit.position.y) / distOwn
                    ownCenter
                } else {
                    val hopTarget = firstHopCenter
                    if (hopTarget != null) {
                        val hdx = hopTarget.x - unit.position.x
                        val hdy = hopTarget.y - unit.position.y
                        val hd = hypot(hdx, hdy)
                        if (hd > 1e-4) {
                            vx = hdx / hd
                            vy = hdy / hd
                        }
                        hopTarget
                    } else {
                        val shortAim = Position(
                            (unit.position.x + vx * minTile).coerceIn(unitRadius, terrain.widthTiles * terrain.tileWidth - unitRadius),
                            (unit.position.y + vy * minTile).coerceIn(unitRadius, terrain.heightTiles * terrain.tileHeight - unitRadius),
                        )
                        if (PlanningSegmentClearance.allows(terrain, unit.position, shortAim, routeR)) shortAim
                        else fallbackAim
                    }
                }
            }
        }

        val clearRings = terrain.clearanceField().clearanceRings(col, row)
        val clearWorld = if (clearRings <= 0) 0.0 else (clearRings * 2.0 - 1.0) * minTile
        val overflowSettled = checkCulDeSacSaturationOverflow(host, unit, field, cellCost, vx, vy, unitRadius, minTile, executionStep)

        return NavigationGuidance.Sample(
            aimPosition = safeAim,
            dirX = vx,
            dirY = vy,
            remainingDistance = remainingWorld,
            corridorClearanceWorld = clearWorld,
            finalApproach = finalPoint || remainingWorld <= minTile * 2.0,
            fromFlowField = true,
            overflowSettled = overflowSettled,
        )
    }

    private fun checkCulDeSacSaturationOverflow(
        host: UnitMovementHost,
        unit: MutableUnit,
        field: ChunkedField,
        ownCellCost: Int,
        dirX: Double,
        dirY: Double,
        unitRadius: Double,
        minTile: Double,
        executionStep: Long,
    ): Boolean {
        val member = unit.landingMember ?: return false
        if (member.parked) return false
        if (ownCellCost <= 40 && unit.nearestMoveTarget != null && member.claimed == null) {
            val maxSpd0 = max(0.2, UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit)))
            val closeToShoreClick = hypot(unit.position.x - (unit.moveTarget?.x ?: field.goalWorld.x),
                unit.position.y - (unit.moveTarget?.y ?: field.goalWorld.y)) <= max(unitRadius * 4.5, minTile * 2.5)
            if (abs(unit.speed) < 0.35 * maxSpd0 && closeToShoreClick &&
                (unit.stalledRouteTicks >= 8L || unit.nearTargetTime > 20.0 || unit.terrainCorrectionTime >= 10.0)
            ) {
                return true
            }
        }
        val group = member.group
        val goalClearRings = field.terrain.clearanceField().clearanceRings(field.key.goalCol, field.key.goalRow)
        val isConfinedPocketOrShore = goalClearRings <= 3 || unit.nearestMoveTarget != null
        if (!isConfinedPocketOrShore) return false
        val stallTicksThreshold = 55L
        val deepStallThreshold = 85L
        val anchorStalled = unit.nav.progressAnchorStep >= 0L && (executionStep - unit.nav.progressAnchorStep) >= stallTicksThreshold
        val deeplyAnchorStalled = unit.nav.progressAnchorStep >= 0L && (executionStep - unit.nav.progressAnchorStep) >= deepStallThreshold
        val claimedOrIngressStalled = (member.claimed != null || member.ingress != null) &&
            (unit.stalledRouteTicks >= 12L || unit.localProgressTime > 36.0 || unit.terrainCorrectionTime >= 14.0 ||
                unit.nearTargetTime >= 120.0 || anchorStalled)
        if (member.claimed != null && !claimedOrIngressStalled) return false
        if (group.members.size < MIN_GROUP_SIZE) return false
        val searchRadius = max(unitRadius * 5.0, minTile * 2.5)
        val nearby = host.spatialIndex.queryFrozenTopK(
            observer = unit,
            layer = SpatialLayer.SURFACE,
            radius = searchRadius,
            limit = 16,
            includeBufferPadding = false,
        ) { cand ->
            cand.ownerPlayerId == unit.ownerPlayerId
        }
        if (nearby.isEmpty()) return false

        // When a claimed/ingress unit has a concrete slot target, check blockage along the direction to its
        // target as well as along the flow-field direction so units fanning around a pocket mouth still detect
        // the settled wall in front of them.
        val aimTarget = member.claimed ?: member.ingress
        var checkDx = dirX
        var checkDy = dirY
        if (aimTarget != null) {
            val adx = aimTarget.x - unit.position.x
            val ady = aimTarget.y - unit.position.y
            val adist = hypot(adx, ady)
            if (adist > 1e-4) {
                checkDx = adx / adist
                checkDy = ady / adist
            }
        }

        var parkedAhead = 0
        var parkedCloseAny = 0
        var closestParkedDist = Double.POSITIVE_INFINITY
        for (i in 0 until nearby.size) {
            val peer = nearby[i]
            val peerUnit = host.units[peer.unitId] ?: continue
            val peerMember = peerUnit.landingMember ?: continue
            if (peerMember.group !== group) continue
            if (!peerMember.parked && peerUnit.moveTarget != null) continue
            val dx = peer.position.x - unit.position.x
            val dy = peer.position.y - unit.position.y
            val dist = hypot(dx, dy)
            if (dist < 1e-4) continue
            val dotFlow = (dx * dirX + dy * dirY) / dist
            val dotAim = (dx * checkDx + dy * checkDy) / dist
            val dot = max(dotFlow, dotAim)
            val peerCol = floor(peer.position.x / field.tileWidth).toInt().coerceIn(0, field.width - 1)
            val peerRow = floor(peer.position.y / field.tileHeight).toInt().coerceIn(0, field.height - 1)
            val peerCost = field.cost[peerRow * field.width + peerCol]
            val closeRange = (unitRadius + peer.hardRadius) * 3.0
            if (dist <= closeRange) {
                parkedCloseAny++
                if (dist < closestParkedDist) closestParkedDist = dist
                if (dot > 0.10 && (peerCost <= ownCellCost + 8 || aimTarget != null)) {
                    parkedAhead++
                }
            }
        }
        if (parkedAhead == 0 && parkedCloseAny < 2) return false
        val ownCol = floor(unit.position.x / field.tileWidth).toInt().coerceIn(0, field.width - 1)
        val ownRow = floor(unit.position.y / field.tileHeight).toInt().coerceIn(0, field.height - 1)
        var openForwardTile = false
        for (d in 0 until 8) {
            val nc = ownCol + DX[d]
            val nr = ownRow + DY[d]
            if (nc !in 0 until field.width || nr !in 0 until field.height) continue
            val nCost = field.cost[nr * field.width + nc]
            if (nCost < ownCellCost - 5) {
                val nCenter = Position((nc + 0.5) * field.tileWidth, (nr + 0.5) * field.tileHeight)
                val occupiedByParked = nearby.any { peer ->
                    val pu = host.units[peer.unitId]
                    val isGroupPeer = pu?.landingMember?.group === group
                    val isSettled = isGroupPeer && (pu?.landingMember?.parked == true || pu?.moveTarget == null)
                    isSettled && hypot(peer.position.x - nCenter.x, peer.position.y - nCenter.y) < unitRadius + peer.hardRadius + 4.0
                }
                if (!occupiedByParked) {
                    openForwardTile = true
                    break
                }
            }
        }
        val maxSpd = max(0.2, UnitLocomotion.effectiveMaximumSpeed(unit, UnitLocomotion.movement(unit)))
        val slow = abs(unit.speed) < 0.45 * maxSpd
        // True saturation indicator: unit is either slow/blocked OR hasn't improved road progress in >= 45 ticks
        // while boxed against settled group teammates.
        val stalledAtFrontier = unit.stalledRouteTicks >= 12L || unit.localProgressTime > 55.0 ||
            unit.terrainCorrectionTime >= 18.0 || unit.nearTargetTime >= 110.0 || anchorStalled
        if (deeplyAnchorStalled && (parkedAhead >= 1 || parkedCloseAny >= 1) && closestParkedDist <= unitRadius * 5.0) {
            return true
        }
        if (openForwardTile) {
            return closestParkedDist <= unitRadius * 4.5 &&
                ((parkedAhead >= 2 && (slow || anchorStalled) &&
                    (unit.stalledRouteTicks >= 18L || unit.localProgressTime > 55.0 || unit.terrainCorrectionTime >= 18.0 || anchorStalled)) ||
                    (claimedOrIngressStalled && (parkedAhead >= 1 || parkedCloseAny >= 2) && (slow || anchorStalled)) ||
                    (parkedCloseAny >= 3 && slow && (anchorStalled || unit.nearTargetTime >= 90.0 || unit.terrainCorrectionTime >= 18.0)))
        }
        return (parkedAhead >= 2 && (slow || anchorStalled) && closestParkedDist <= unitRadius * 4.2 &&
            (unit.stalledRouteTicks >= 8L || unit.localProgressTime > 35.0 || anchorStalled)) ||
            (parkedAhead >= 1 && slow && (stalledAtFrontier || claimedOrIngressStalled)) ||
            (parkedCloseAny >= 2 && (slow || anchorStalled) && anchorStalled)
    }

    private fun seedGoalCells(field: ChunkedField, goalCol: Int, goalRow: Int) {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        if (cf.clearanceRings(goalCol, goalRow) >= field.requiredRings) {
            val idx = goalRow * w + goalCol
            field.cost[idx] = 0
            field.pushHeap(idx, 0)
            return
        }
        val maxRing = max(w, h)
        for (ring in 1..maxRing) {
            var seeded = false
            for (dy in -ring..ring) {
                val r = goalRow + dy
                if (r !in 0 until h) continue
                for (dx in -ring..ring) {
                    if (abs(dx) != ring && abs(dy) != ring) continue
                    val c = goalCol + dx
                    if (c !in 0 until w) continue
                    if (cf.clearanceRings(c, r) >= field.requiredRings) {
                        val idx = r * w + c
                        val distCost = ((hypot(dx.toDouble(), dy.toDouble()) - ring.toDouble()) * 10.0).toInt().coerceAtLeast(0)
                        if (distCost < field.cost[idx]) {
                            field.cost[idx] = distCost
                            field.pushHeap(idx, distCost)
                            seeded = true
                        }
                    }
                }
            }
            if (seeded) return
        }
    }

    private fun isSingleTilePinhole(terrain: PlanningTerrain, col: Int, row: Int): Boolean {
        val w = terrain.widthTiles
        val h = terrain.heightTiles
        if (terrain.isBlocked(col, row)) return false
        val leftB = col == 0 || terrain.isBlocked(col - 1, row)
        val rightB = col == w - 1 || terrain.isBlocked(col + 1, row)
        val upB = row == 0 || terrain.isBlocked(col, row - 1)
        val downB = row == h - 1 || terrain.isBlocked(col, row + 1)
        return (leftB && rightB) || (upB && downB)
    }

    private fun expandWavefront(field: ChunkedField, maxExpansions: Int): Int {
        val w = field.width
        val h = field.height
        val cf = field.terrain.clearanceField()
        val reqRings = field.requiredRings
        val wideRings = field.wideCorridorRings
        val yieldWide = field.key.smallYieldsWideCorridor
        var expanded = 0

        while (expanded < maxExpansions && field.heapSize > 0) {
            val poppedKey = field.peekHeapKey()
            val cell = field.popHeapCell()
            val curCost = field.cost[cell]
            if (poppedKey != curCost || curCost >= INF_COST) continue
            val col = cell % w
            val row = cell / w
            expanded++
            field.totalExpansions++

            val chunkIdx = (row / CHUNK_SIZE) * field.chunksX + (col / CHUNK_SIZE)
            field.chunkBuilt[chunkIdx] = true
            field.chunkDirty[chunkIdx] = false

            for (d in 0 until 8) {
                val nc = col + DX[d]
                val nr = row + DY[d]
                if (nc !in 0 until w || nr !in 0 until h) continue
                val nIdx = nr * w + nc
                val nRings = cf.clearanceRingsAt(nIdx)
                if (nRings < reqRings) continue
                if (d >= 4) {
                    if (cf.clearanceRingsAt(row * w + nc) < reqRings ||
                        cf.clearanceRingsAt(nr * w + col) < reqRings
                    ) continue
                }
                var stepCost = if (d < 4) 10 else 14
                if (yieldWide && nRings >= wideRings && nRings <= wideRings + 2) {
                    stepCost += WIDE_CORRIDOR_YIELD_SURCHARGE
                }
                val pinhole = nRings == 1 && isSingleTilePinhole(field.terrain, nc, nr)
                if (pinhole) {
                    stepCost += PINHOLE_BASE_SURCHARGE
                }
                val congPen = field.congestionPenalty[nIdx].toInt()
                stepCost += if (pinhole) congPen * 3 else congPen

                val nextCost = curCost + stepCost
                if (nextCost < field.cost[nIdx]) {
                    field.cost[nIdx] = nextCost
                    field.pushHeap(nIdx, nextCost)
                }
            }
            recomputeCellGradient(field, col, row)
        }
        if (field.heapSize == 0) {
            field.completed = true
        }
        return expanded
    }

    private fun recomputeCellGradient(field: ChunkedField, col: Int, row: Int) {
        val w = field.width
        val h = field.height
        val idx = row * w + col
        val ownCost = field.cost[idx]
        if (ownCost == 0) {
            field.dirX[idx] = 0f
            field.dirY[idx] = 0f
            return
        }
        val cf = field.terrain.clearanceField()
        val reqRings = field.requiredRings
        var bestDx = 0.0
        var bestDy = 0.0
        var bestDrop = 0
        var sumX = 0.0
        var sumY = 0.0
        for (d in 0 until 8) {
            val nc = col + DX[d]
            val nr = row + DY[d]
            if (nc !in 0 until w || nr !in 0 until h) continue
            val nIdx = nr * w + nc
            if (cf.clearanceRingsAt(nIdx) < reqRings) continue
            if (d >= 4) {
                if (cf.clearanceRingsAt(row * w + nc) < reqRings ||
                    cf.clearanceRingsAt(nr * w + col) < reqRings
                ) continue
            }
            val nCost = field.cost[nIdx]
            val drop = ownCost - nCost
            if (drop > 0) {
                val len = if (d < 4) 1.0 else 1.41421356237
                val weight = drop.toDouble() / len
                sumX += (DX[d] / len) * weight
                sumY += (DY[d] / len) * weight
                if (drop > bestDrop) {
                    bestDrop = drop
                    bestDx = DX[d] / len
                    bestDy = DY[d] / len
                }
            }
        }
        val nearWall = cf.clearanceRingsAt(idx) <= reqRings + 1
        if (nearWall && bestDrop > 0) {
            // Near walls and gate jambs, follow the steepest legal descent edge directly instead of
            // averaging across perpendicular branches (which creates diagonal vectors pointing into wall faces).
            field.dirX[idx] = bestDx.toFloat()
            field.dirY[idx] = bestDy.toFloat()
        } else {
            val mag = hypot(sumX, sumY)
            if (mag > 1e-4) {
                field.dirX[idx] = (sumX / mag).toFloat()
                field.dirY[idx] = (sumY / mag).toFloat()
            } else if (bestDrop > 0) {
                field.dirX[idx] = bestDx.toFloat()
                field.dirY[idx] = bestDy.toFloat()
            }
        }
    }

    private fun updateSoftCongestionHysteresis(spatialIndex: WorldSpatialIndex) {
        val frozenUnits = spatialIndex.frozenLayerUnits(SpatialLayer.SURFACE)
        for (field in fields.values) {
            val w = field.width
            val h = field.height
            val counts = ShortArray(field.cellCount)
            for (u in frozenUnits) {
                if (u.ownerPlayerId != field.key.ownerId) continue
                val col = floor(u.position.x / field.tileWidth).toInt()
                val row = floor(u.position.y / field.tileHeight).toInt()
                if (col in 0 until w && row in 0 until h) {
                    val idx = row * w + col
                    val next = counts[idx].toInt() + 1
                    if (next <= 255) counts[idx] = next.toShort()
                }
            }
            val cf = field.terrain.clearanceField()
            var changedAny = false
            for (i in 0 until field.cellCount) {
                val oldPen = field.congestionPenalty[i].toInt()
                val rings = cf.clearanceRingsAt(i)
                val ownCnt = counts[i].toInt()
                val c = i % w
                val r = i / w
                val pinhole = rings == 1 && isSingleTilePinhole(field.terrain, c, r)
                var localDensity = ownCnt
                if (pinhole) {
                    for (dr in -2..2) {
                        val nr = r + dr
                        if (nr !in 0 until h) continue
                        for (dc in -2..2) {
                            if (dc == 0 && dr == 0) continue
                            val nc = c + dc
                            if (nc in 0 until w) localDensity += counts[nr * w + nc].toInt()
                        }
                    }
                } else if (rings in 1..3) {
                    for (d in 0 until 8) {
                        val nc = c + DX[d]
                        val nr = r + DY[d]
                        if (nc in 0 until w && nr in 0 until h) {
                            localDensity += counts[nr * w + nc].toInt()
                        }
                    }
                }
                val targetPen = when {
                    pinhole && localDensity >= 2 -> min(MAX_CONGESTION_PENALTY, (localDensity - 1) * 10)
                    rings in 1..4 && ownCnt >= 2 -> min(MAX_CONGESTION_PENALTY, (ownCnt - 1) * 8)
                    rings in 1..2 && localDensity >= 4 -> min(MAX_CONGESTION_PENALTY, (localDensity - 3) * 6)
                    else -> 0
                }
                val nextPen = when {
                    targetPen >= oldPen + 6 -> min(MAX_CONGESTION_PENALTY, oldPen + 6)
                    targetPen == 0 && oldPen > 0 -> max(0, oldPen - 4)
                    else -> oldPen
                }
                if (nextPen != oldPen) {
                    field.congestionPenalty[i] = nextPen.toShort()
                    changedAny = true
                }
            }
            if (changedAny && field.completed) {
                // Only rebuild the affected congestion chunks (+ 1-chunk halo) incrementally without wiping
                // the entire map or zeroing existing dirX/dirY vectors while P1 budget slices run.
                val dirtyChunks = BooleanArray(field.chunksX * field.chunksY)
                var anyDirty = false
                for (i in 0 until field.cellCount) {
                    if (field.congestionPenalty[i] != 0.toShort() || counts[i] >= 2) {
                        val c = i % w
                        val r = i / w
                        val cx = c / CHUNK_SIZE
                        val cy = r / CHUNK_SIZE
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
                if (anyDirty) {
                    applyDirtyChunkReset(field, dirtyChunks, preserveGradients = true)
                }
            }
        }
    }

    private fun copyChunkFrom(src: ChunkedField, dst: ChunkedField, cx: Int, cy: Int) {
        val r0 = cy * CHUNK_SIZE
        val r1 = min(dst.height, r0 + CHUNK_SIZE)
        val c0 = cx * CHUNK_SIZE
        val c1 = min(dst.width, c0 + CHUNK_SIZE)
        val w = dst.width
        for (r in r0 until r1) {
            val start = r * w + c0
            val len = c1 - c0
            System.arraycopy(src.cost, start, dst.cost, start, len)
            System.arraycopy(src.dirX, start, dst.dirX, start, len)
            System.arraycopy(src.dirY, start, dst.dirY, start, len)
        }
    }

    companion object {
        internal const val CHUNK_SIZE: Int = 16
        internal const val MIN_GROUP_SIZE: Int = 4
        internal const val MAX_CACHED_FIELDS: Int = 12
        internal const val INF_COST: Int = 1_000_000_000
        internal const val WIDE_CORRIDOR_YIELD_SURCHARGE: Int = 4
        internal const val PINHOLE_BASE_SURCHARGE: Int = 22
        internal const val MAX_CONGESTION_PENALTY: Int = 48
        internal const val CONGESTION_REFRESH_INTERVAL: Long = 30L
        internal const val TRUNK_REUSE_RADIUS_TILES: Double = 14.0
        internal const val TRUNK_REUSE_LOCAL_HALO_TILES: Int = 8

        private val DX = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, 1, -1, -1)

        internal fun clearanceBandFor(terrain: PlanningTerrain, radius: Double): Int {
            val minTile = min(terrain.tileWidth, terrain.tileHeight).toDouble()
            if (radius <= minTile * 0.5) return 1
            return 1 + ceil((radius - minTile * 0.5) / minTile).toInt()
        }
    }
}
