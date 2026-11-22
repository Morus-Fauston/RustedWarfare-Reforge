package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Bucket layer for the step-authoritative spatial index. */
internal enum class SpatialLayer {
    SURFACE,
    SUBMERGED,
    AIR;

    companion object {
        fun fromContact(layer: ContactLayer): SpatialLayer = when (layer) {
            ContactLayer.SURFACE -> SURFACE
            ContactLayer.SUBMERGED -> SUBMERGED
        }
    }
}

/** Frozen step-start unit pose snapshot for order-independent local-avoidance neighbor sampling. */
internal data class FrozenSpatialUnit(
    val unitId: String,
    val updateKey: String,
    val ownerPlayerId: String,
    val layer: SpatialLayer,
    val position: Position,
    val heading: Double,
    val speed: Double,
    val flightVelocity: Position,
    val hardRadius: Double,
    val bufferRadius: Double,
    val nativeTransportPlacement: Boolean,
    val moving: Boolean,
)

/**
 * Step-authoritative spatial index attached to [StepState].
 *
 * Maintains incremental live spatial buckets for [SpatialLayer.SURFACE], [SpatialLayer.SUBMERGED],
 * and [SpatialLayer.AIR], plus a read-only step-start frozen snapshot captured via [beginStep]
 * for order-independent deterministic Top-K neighbor queries without any 64-neighbor truncation.
 *
 * Live queries return units in deterministic [MutableUnit.updateKey] ascending order and support
 * candidate-step isolation via [fork] and consistency verification via [validate].
 */
internal class WorldSpatialIndex private constructor(
    private val cellSize: Double,
    private val buckets: Array<HashMap<Long, ArrayList<MutableUnit>>>,
    private val entries: HashMap<String, Entry>,
    private val maxHardRadius: DoubleArray,
    private val maxBufferRadius: DoubleArray,
    private var frozenBuckets: Array<HashMap<Long, ArrayList<FrozenSpatialUnit>>>?,
    private var frozenById: HashMap<String, FrozenSpatialUnit>?,
    private val frozenMaxHardRadius: DoubleArray,
    private val frozenMaxBufferRadius: DoubleArray,
) {
    private data class Entry(
        val unit: MutableUnit,
        var layer: SpatialLayer,
        var cellKey: Long,
        var hardRadius: Double,
        var bufferRadius: Double,
    )

    constructor(cellSize: Double = DEFAULT_CELL_SIZE) : this(
        cellSize = cellSize,
        buckets = Array(LAYER_COUNT) { HashMap() },
        entries = HashMap(),
        maxHardRadius = DoubleArray(LAYER_COUNT),
        maxBufferRadius = DoubleArray(LAYER_COUNT),
        frozenBuckets = null,
        frozenById = null,
        frozenMaxHardRadius = DoubleArray(LAYER_COUNT),
        frozenMaxBufferRadius = DoubleArray(LAYER_COUNT),
    )

    fun fork(forkedUnits: Map<String, MutableUnit>): WorldSpatialIndex {
        val nextBuckets = Array(LAYER_COUNT) { layerIdx ->
            val src = buckets[layerIdx]
            val dst = HashMap<Long, ArrayList<MutableUnit>>((src.size * 4) / 3 + 2)
            for ((key, list) in src) {
                val cloned = ArrayList<MutableUnit>(list.size)
                for (i in 0 until list.size) {
                    val mapped = forkedUnits[list[i].unitId]
                    if (mapped != null) cloned.add(mapped)
                }
                if (cloned.isNotEmpty()) dst[key] = cloned
            }
            dst
        }
        val nextEntries = HashMap<String, Entry>((entries.size * 4) / 3 + 2)
        for ((id, entry) in entries) {
            val mapped = forkedUnits[id] ?: continue
            nextEntries[id] = Entry(mapped, entry.layer, entry.cellKey, entry.hardRadius, entry.bufferRadius)
        }
        return WorldSpatialIndex(
            cellSize = cellSize,
            buckets = nextBuckets,
            entries = nextEntries,
            maxHardRadius = maxHardRadius.copyOf(),
            maxBufferRadius = maxBufferRadius.copyOf(),
            frozenBuckets = null,
            frozenById = null,
            frozenMaxHardRadius = DoubleArray(LAYER_COUNT),
            frozenMaxBufferRadius = DoubleArray(LAYER_COUNT),
        )
    }

    /** Rebuilds the live buckets from an authoritative unit collection. */
    fun syncAll(units: Collection<MutableUnit>) {
        for (layerIdx in 0 until LAYER_COUNT) {
            buckets[layerIdx].clear()
            maxHardRadius[layerIdx] = 0.0
            maxBufferRadius[layerIdx] = 0.0
        }
        entries.clear()
        frozenBuckets = null
        frozenById = null
        for (unit in units) {
            insert(unit)
        }
    }

    /** Inserts or updates a unit in the live spatial index according to its current authoritative state. */
    fun insert(unit: MutableUnit) {
        val layer = layerOf(unit)
        if (layer == null) {
            remove(unit.unitId)
            return
        }
        val key = cellKey(unit.position.x, unit.position.y)
        val hard = unit.definition.movementCollision.boundingRadius
        val buf = unit.definition.contactBufferCollision.boundingRadius
        val existing = entries[unit.unitId]
        if (existing != null) {
            if (existing.unit === unit && existing.layer == layer && existing.cellKey == key &&
                existing.hardRadius == hard && existing.bufferRadius == buf
            ) {
                return
            }
            removeFromBucket(existing.layer.ordinal, existing.cellKey, existing.unit)
            existing.layer = layer
            existing.cellKey = key
            existing.hardRadius = hard
            existing.bufferRadius = buf
            if (existing.unit !== unit) {
                entries[unit.unitId] = Entry(unit, layer, key, hard, buf)
            }
            addToBucket(layer.ordinal, key, unit)
        } else {
            entries[unit.unitId] = Entry(unit, layer, key, hard, buf)
            addToBucket(layer.ordinal, key, unit)
        }
        val li = layer.ordinal
        if (hard > maxHardRadius[li]) maxHardRadius[li] = hard
        if (buf > maxBufferRadius[li]) maxBufferRadius[li] = buf
    }

    /** Updates a unit's cell or layer membership after a coordinate, height, alive, or cargo mutation. */
    fun relocate(unit: MutableUnit) = insert(unit)

    /** Removes a unit from the live spatial index. */
    fun remove(unitId: String) {
        val existing = entries.remove(unitId) ?: return
        val li = existing.layer.ordinal
        removeFromBucket(li, existing.cellKey, existing.unit)
        if (existing.hardRadius >= maxHardRadius[li] || existing.bufferRadius >= maxBufferRadius[li]) {
            recomputeMaxRadii(li)
        }
    }

    private fun recomputeMaxRadii(layerIndex: Int) {
        var mh = 0.0
        var mb = 0.0
        for (list in buckets[layerIndex].values) {
            for (i in 0 until list.size) {
                val u = list[i]
                val h = u.definition.movementCollision.boundingRadius
                val b = u.definition.contactBufferCollision.boundingRadius
                if (h > mh) mh = h
                if (b > mb) mb = b
            }
        }
        maxHardRadius[layerIndex] = mh
        maxBufferRadius[layerIndex] = mb
    }

    /**
     * Marks step start and captures the immutable step-start snapshot eagerly if requested,
     * or invalidates any previous snapshot so callers that need frozen neighbor views
     * capture the exact step-start poses before any unit mutates.
     */
    fun beginStep(captureFrozen: Boolean = true) {
        if (!captureFrozen) {
            frozenBuckets = null
            frozenById = null
            return
        }
        val nextFrozenBuckets = Array(LAYER_COUNT) { HashMap<Long, ArrayList<FrozenSpatialUnit>>() }
        val nextFrozenById = HashMap<String, FrozenSpatialUnit>((entries.size * 4) / 3 + 2)
        for (li in 0 until LAYER_COUNT) {
            maxHardRadius[li] = 0.0
            maxBufferRadius[li] = 0.0
            frozenMaxHardRadius[li] = 0.0
            frozenMaxBufferRadius[li] = 0.0
        }
        for (li in 0 until LAYER_COUNT) {
            val dstLayer = nextFrozenBuckets[li]
            for ((key, liveList) in buckets[li]) {
                val frozenList = ArrayList<FrozenSpatialUnit>(liveList.size)
                for (i in 0 until liveList.size) {
                    val u = liveList[i]
                    val hard = u.definition.movementCollision.boundingRadius
                    val buf = u.definition.contactBufferCollision.boundingRadius
                    if (hard > maxHardRadius[li]) maxHardRadius[li] = hard
                    if (buf > maxBufferRadius[li]) maxBufferRadius[li] = buf
                    if (u.attachment != null || u.definition.building != null) continue
                    val snap = FrozenSpatialUnit(
                        unitId = u.unitId,
                        updateKey = u.updateKey,
                        ownerPlayerId = u.ownerPlayerId,
                        layer = SpatialLayer.entries[li],
                        position = u.position,
                        heading = u.heading,
                        speed = u.speed,
                        flightVelocity = u.flightVelocity,
                        hardRadius = hard,
                        bufferRadius = buf,
                        nativeTransportPlacement = u.nativeTransportPlacement,
                        moving = u.moveTarget != null || u.speed != 0.0,
                    )
                    frozenList.add(snap)
                    nextFrozenById[u.unitId] = snap
                    if (hard > frozenMaxHardRadius[li]) frozenMaxHardRadius[li] = hard
                    if (buf > frozenMaxBufferRadius[li]) frozenMaxBufferRadius[li] = buf
                }
                if (frozenList.isNotEmpty()) dstLayer[key] = frozenList
            }
        }
        frozenBuckets = nextFrozenBuckets
        frozenById = nextFrozenById
    }

    fun frozenUnit(unitId: String): FrozenSpatialUnit? = frozenById?.get(unitId)

    /** Returns all step-start frozen snapshots in [layer] in deterministic [updateKey] order. */
    fun frozenLayerUnits(layer: SpatialLayer): List<FrozenSpatialUnit> {
        val fBuckets = frozenBuckets ?: return emptyList()
        val bucketMap = fBuckets[layer.ordinal]
        if (bucketMap.isEmpty()) return emptyList()
        val out = ArrayList<FrozenSpatialUnit>()
        for (list in bucketMap.values) out.addAll(list)
        out.sortWith(FROZEN_KEY_COMPARATOR)
        return out
    }

    /**
     * Queries the step-start frozen snapshot for the deterministic Top-K nearest visible neighbors
     * within [radius] (center-to-center distance <= [radius], or expanded by [includeBufferPadding]
     * for contact-buffer reach). Never truncates to empty or returns null under dense crowds.
     * Ties in squared distance are broken deterministically by [FrozenSpatialUnit.updateKey].
     */
    fun queryFrozenTopK(
        observer: MutableUnit,
        layer: SpatialLayer,
        radius: Double,
        limit: Int,
        includeBufferPadding: Boolean = false,
        visible: (FrozenSpatialUnit) -> Boolean,
    ): List<FrozenSpatialUnit> {
        if (limit <= 0 || radius < 0.0 || !radius.isFinite()) return emptyList()
        val fBuckets = frozenBuckets ?: run {
            beginStep(captureFrozen = true)
            checkNotNull(frozenBuckets)
        }
        val obsSnap = frozenById?.get(observer.unitId)
        val ox = obsSnap?.position?.x ?: observer.position.x
        val oy = obsSnap?.position?.y ?: observer.position.y
        val li = layer.ordinal
        val layerMap = fBuckets[li]
        if (layerMap.isEmpty()) return emptyList()

        val obsBuf = obsSnap?.bufferRadius ?: observer.definition.contactBufferCollision.boundingRadius
        val pad = if (includeBufferPadding) obsBuf + frozenMaxBufferRadius[li] else 0.0
        val searchR = radius + pad
        val x0 = cellCoord(ox - searchR)
        val x1 = cellCoord(ox + searchR)
        val y0 = cellCoord(oy - searchR)
        val y1 = cellCoord(oy + searchR)

        val candidates = ArrayList<ScoredFrozen>()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = layerMap[packKey(cx, cy)] ?: continue
                for (i in 0 until list.size) {
                    val candidate = list[i]
                    if (candidate.unitId == observer.unitId) continue
                    val dx = candidate.position.x - ox
                    val dy = candidate.position.y - oy
                    val maxDist = if (includeBufferPadding) radius + obsBuf + candidate.bufferRadius else radius
                    val distSq = dx * dx + dy * dy
                    if (distSq > maxDist * maxDist) continue
                    if (!visible(candidate)) continue
                    candidates.add(ScoredFrozen(candidate, distSq))
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        candidates.sortWith(SCORED_FROZEN_COMPARATOR)
        val count = min(limit, candidates.size)
        val result = ArrayList<FrozenSpatialUnit>(count)
        for (i in 0 until count) {
            result.add(candidates[i].unit)
        }
        return result
    }

    /**
     * Queries the step-start frozen snapshot for all visible units in [layer] overlapping
     * the bounding box [minX..maxX, minY..maxY] inflated by each candidate's radius.
     */
    fun queryFrozenBounds(
        layer: SpatialLayer,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        useBufferRadius: Boolean = false,
        visible: (FrozenSpatialUnit) -> Boolean = { true },
    ): List<FrozenSpatialUnit> {
        val fBuckets = frozenBuckets ?: run {
            beginStep(captureFrozen = true)
            checkNotNull(frozenBuckets)
        }
        val li = layer.ordinal
        val layerMap = fBuckets[li]
        if (layerMap.isEmpty()) return emptyList()
        val pad = if (useBufferRadius) frozenMaxBufferRadius[li] else frozenMaxHardRadius[li]
        val x0 = cellCoord(minX - pad)
        val x1 = cellCoord(maxX + pad)
        val y0 = cellCoord(minY - pad)
        val y1 = cellCoord(maxY + pad)
        val out = ArrayList<FrozenSpatialUnit>()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = layerMap[packKey(cx, cy)] ?: continue
                for (i in 0 until list.size) {
                    val u = list[i]
                    val r = if (useBufferRadius) u.bufferRadius else u.hardRadius
                    if (u.position.x + r >= minX && u.position.x - r <= maxX &&
                        u.position.y + r >= minY && u.position.y - r <= maxY &&
                        visible(u)
                    ) {
                        out.add(u)
                    }
                }
            }
        }
        if (out.size > 1) out.sortWith(FROZEN_KEY_COMPARATOR)
        return out
    }

    /**
     * Queries the live mutable index for all units in [layer] whose collision shape (hard or buffer)
     * can intersect [minX..maxX, minY..maxY]. Returned in deterministic [MutableUnit.updateKey] ascending order.
     */
    fun queryBounds(
        layer: SpatialLayer,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        useBufferRadius: Boolean = false,
    ): List<MutableUnit> {
        val li = layer.ordinal
        val layerMap = buckets[li]
        if (layerMap.isEmpty()) return emptyList()
        val pad = if (useBufferRadius) maxBufferRadius[li] else maxHardRadius[li]
        val x0 = cellCoord(minX - pad)
        val x1 = cellCoord(maxX + pad)
        val y0 = cellCoord(minY - pad)
        val y1 = cellCoord(maxY + pad)
        val out = ArrayList<MutableUnit>()
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = layerMap[packKey(cx, cy)] ?: continue
                for (i in 0 until list.size) {
                    val u = list[i]
                    val r = if (useBufferRadius) u.definition.contactBufferCollision.boundingRadius
                    else u.definition.movementCollision.boundingRadius
                    if (u.position.x + r >= minX && u.position.x - r <= maxX &&
                        u.position.y + r >= minY && u.position.y - r <= maxY
                    ) {
                        out.add(u)
                    }
                }
            }
        }
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

    /**
     * Allocation-light callback visitor for live bounds queries when the caller does not depend
     * on [MutableUnit.updateKey] iteration order (e.g., symmetric min-fraction / boolean overlap checks).
     * Returning `true` stops iteration early.
     */
    inline fun forEachInBoundsUnordered(
        layer: SpatialLayer,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        useBufferRadius: Boolean = false,
        visitor: (MutableUnit) -> Boolean,
    ) {
        val li = layer.ordinal
        val layerMap = bucketsFor(li)
        if (layerMap.isEmpty()) return
        val pad = maxRadiusFor(li, useBufferRadius)
        val x0 = cellCoordOf(minX - pad)
        val x1 = cellCoordOf(maxX + pad)
        val y0 = cellCoordOf(minY - pad)
        val y1 = cellCoordOf(maxY + pad)
        for (cx in x0..x1) {
            for (cy in y0..y1) {
                val list = layerMap[packKeyOf(cx, cy)] ?: continue
                for (i in 0 until list.size) {
                    val u = list[i]
                    val r = if (useBufferRadius) u.definition.contactBufferCollision.boundingRadius
                    else u.definition.movementCollision.boundingRadius
                    if (u.position.x + r >= minX && u.position.x - r <= maxX &&
                        u.position.y + r >= minY && u.position.y - r <= maxY
                    ) {
                        if (visitor(u)) return
                    }
                }
            }
        }
    }

    @PublishedApi internal fun bucketsFor(li: Int): HashMap<Long, ArrayList<MutableUnit>> = buckets[li]
    @PublishedApi internal fun maxRadiusFor(li: Int, useBufferRadius: Boolean): Double =
        if (useBufferRadius) maxBufferRadius[li] else maxHardRadius[li]
    @PublishedApi internal fun cellCoordOf(v: Double): Int = floor(v / cellSize).toInt()
    @PublishedApi internal fun packKeyOf(cx: Int, cy: Int): Long = packKey(cx, cy)

    /**
     * Queries the live mutable index for all units in [layer] that can intersect a swept segment
     * from [start] to [end] inflated by [moverRadius] (plus each candidate's own hard or buffer radius).
     * Results are returned in deterministic [MutableUnit.updateKey] ascending order.
     */
    fun querySwept(
        layer: SpatialLayer,
        start: Position,
        end: Position,
        moverRadius: Double,
        useBufferRadius: Boolean = false,
    ): List<MutableUnit> {
        val minX = min(start.x, end.x) - moverRadius
        val maxX = max(start.x, end.x) + moverRadius
        val minY = min(start.y, end.y) - moverRadius
        val maxY = max(start.y, end.y) + moverRadius
        return queryBounds(layer, minX, maxX, minY, maxY, useBufferRadius = useBufferRadius)
    }

    /**
     * Queries live units across all layers whose centers lie within axis-aligned box
     * [center.x - radius, center.x + radius] x [center.y - radius, center.y + radius].
     * Returned in deterministic [MutableUnit.updateKey] ascending order (matching orderedUnits()).
     */
    fun queryAllLayersCenterBox(center: Position, radius: Double): List<MutableUnit> {
        if (!radius.isFinite() || radius < 0.0) return emptyList()
        val minX = center.x - radius
        val maxX = center.x + radius
        val minY = center.y - radius
        val maxY = center.y + radius
        val x0 = cellCoord(minX)
        val x1 = cellCoord(maxX)
        val y0 = cellCoord(minY)
        val y1 = cellCoord(maxY)
        val out = ArrayList<MutableUnit>()
        for (li in 0 until LAYER_COUNT) {
            val layerMap = buckets[li]
            if (layerMap.isEmpty()) continue
            for (cx in x0..x1) {
                for (cy in y0..y1) {
                    val list = layerMap[packKey(cx, cy)] ?: continue
                    for (i in 0 until list.size) {
                        val u = list[i]
                        if (u.position.x >= minX && u.position.x <= maxX &&
                            u.position.y >= minY && u.position.y <= maxY
                        ) {
                            out.add(u)
                        }
                    }
                }
            }
        }
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

    /** Queries live units across all layers whose centers lie in [minX..maxX, minY..maxY] inflated by [extraMargin]. */
    fun queryAllLayersBounds(minX: Double, maxX: Double, minY: Double, maxY: Double, extraMargin: Double = 0.0): List<MutableUnit> {
        val out = ArrayList<MutableUnit>()
        for (li in 0 until LAYER_COUNT) {
            val layerMap = buckets[li]
            if (layerMap.isEmpty()) continue
            val pad = extraMargin + maxHardRadius[li]
            val x0 = cellCoord(minX - pad)
            val x1 = cellCoord(maxX + pad)
            val y0 = cellCoord(minY - pad)
            val y1 = cellCoord(maxY + pad)
            for (cx in x0..x1) {
                for (cy in y0..y1) {
                    val list = layerMap[packKey(cx, cy)] ?: continue
                    for (i in 0 until list.size) {
                        val u = list[i]
                        val r = u.definition.movementCollision.boundingRadius + extraMargin
                        if (u.position.x + r >= minX && u.position.x - r <= maxX &&
                            u.position.y + r >= minY && u.position.y - r <= maxY
                        ) {
                            out.add(u)
                        }
                    }
                }
            }
        }
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

    /** Returns all indexed live units in [layer], sorted by [MutableUnit.updateKey]. */
    fun allInLayer(layer: SpatialLayer): List<MutableUnit> {
        val layerMap = buckets[layer.ordinal]
        if (layerMap.isEmpty()) return emptyList()
        val out = ArrayList<MutableUnit>()
        for (list in layerMap.values) out.addAll(list)
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

    /** Validates that live spatial buckets exactly match the authoritative unit map. */
    fun validate(units: Map<String, MutableUnit>) {
        var expectedCount = 0
        for (unit in units.values) {
            val attachment = unit.attachment
            val orphanedAttachment = attachment != null && units[attachment.parentId]?.let { !it.alive || it.reclaimed } == true
            val expectedLayer = if (orphanedAttachment) null else layerOf(unit)
            val entry = entries[unit.unitId]
            if (expectedLayer == null) {
                require(entry == null) { "Inactive/carried/attached unit ${unit.unitId} remained in WorldSpatialIndex" }
            } else {
                expectedCount++
                require(entry != null) { "Active unit ${unit.unitId} missing from WorldSpatialIndex" }
                require(entry.unit === unit) { "WorldSpatialIndex holds stale instance for ${unit.unitId}" }
                require(entry.layer == expectedLayer) {
                    "WorldSpatialIndex layer mismatch for ${unit.unitId}: ${entry.layer} != $expectedLayer"
                }
                val expectedKey = cellKey(unit.position.x, unit.position.y)
                require(entry.cellKey == expectedKey) {
                    "WorldSpatialIndex cell mismatch for ${unit.unitId}: ${entry.cellKey} != $expectedKey"
                }
                val bucket = buckets[expectedLayer.ordinal][expectedKey]
                require(bucket != null && bucket.any { it === unit }) {
                    "WorldSpatialIndex bucket missing ${unit.unitId}"
                }
            }
        }
        require(entries.size == expectedCount) {
            "WorldSpatialIndex entry count ${entries.size} != expected $expectedCount"
        }
        var bucketTotal = 0
        for (li in 0 until LAYER_COUNT) {
            for (list in buckets[li].values) bucketTotal += list.size
        }
        require(bucketTotal == expectedCount) {
            "WorldSpatialIndex bucket total $bucketTotal != expected $expectedCount"
        }
    }

    private fun addToBucket(layerIndex: Int, key: Long, unit: MutableUnit) {
        val bucket = buckets[layerIndex].getOrPut(key) { ArrayList(4) }
        // Maintain updateKey ascending order inside each small bucket so single-cell queries need no sort.
        var idx = bucket.size
        while (idx > 0 && bucket[idx - 1].updateKey > unit.updateKey) {
            idx--
        }
        bucket.add(idx, unit)
    }

    private fun removeFromBucket(layerIndex: Int, key: Long, unit: MutableUnit) {
        val layerMap = buckets[layerIndex]
        val bucket = layerMap[key] ?: return
        for (i in 0 until bucket.size) {
            if (bucket[i] === unit || bucket[i].unitId == unit.unitId) {
                bucket.removeAt(i)
                break
            }
        }
        if (bucket.isEmpty()) layerMap.remove(key)
    }

    private fun cellCoord(v: Double): Int = floor(v / cellSize).toInt()

    private fun cellKey(x: Double, y: Double): Long = packKey(cellCoord(x), cellCoord(y))

    private data class ScoredFrozen(val unit: FrozenSpatialUnit, val distSq: Double)

    companion object {
        const val DEFAULT_CELL_SIZE = 48.0
        private val LAYER_COUNT = SpatialLayer.entries.size

        private val UNIT_KEY_COMPARATOR = Comparator<MutableUnit> { a, b -> a.updateKey.compareTo(b.updateKey) }
        private val FROZEN_KEY_COMPARATOR = Comparator<FrozenSpatialUnit> { a, b -> a.updateKey.compareTo(b.updateKey) }
        private val SCORED_FROZEN_COMPARATOR = Comparator<ScoredFrozen> { a, b ->
            val cmp = a.distSq.compareTo(b.distSq)
            if (cmp != 0) cmp else a.unit.updateKey.compareTo(b.unit.updateKey)
        }

        private fun packKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

        /**
         * Classifies a unit into its spatial index bucket, or returns null when excluded
         * (dead, carried inside a transport, or reclaimed).
         *
         * Buildings and attackable attachments live in their combat layer so target queries can
         * locate them via the index; contact collision checks still filter by [UnitContact.layer].
         */
        fun layerOf(unit: MutableUnit): SpatialLayer? {
            if (!unit.alive || unit.reclaimed || unit.carriedBy != null) return null
            if (unit.attachment != null) {
                return when {
                    CombatTargetQuery.isUnderwater(unit) -> SpatialLayer.SUBMERGED
                    CombatTargetQuery.isFlying(unit) -> SpatialLayer.AIR
                    else -> SpatialLayer.SURFACE
                }
            }
            if (unit.definition.building != null) {
                return if (CombatTargetQuery.isUnderwater(unit)) SpatialLayer.SUBMERGED else SpatialLayer.SURFACE
            }
            val contactLayer = UnitContact.layer(unit)
            if (contactLayer != null) return SpatialLayer.fromContact(contactLayer)
            return if (unit.definition.movement.type == MovementType.AIR) SpatialLayer.AIR else null
        }
    }
}
