package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import kotlin.math.abs
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

/**
 * Frozen step-start unit pose & kinematic snapshot for strictly update-order-independent
 * local-avoidance neighbor sampling. Instances are owned per [WorldSpatialIndex] (never shared
 * across [WorldSpatialIndex.fork]) and updated in-place during [WorldSpatialIndex.beginStep]
 * for zero steady-state heap allocation.
 */
internal class FrozenSpatialUnit(
    var unitId: String,
    var updateKey: String,
    var ownerPlayerId: String,
    var layer: SpatialLayer,
    var position: Position,
    var heading: Double,
    var speed: Double,
    var flightVelocity: Position,
    var hardRadius: Double,
    var bufferRadius: Double,
    var nativeTransportPlacement: Boolean,
    var moving: Boolean,
    var anchored: Boolean = !moving,
    var displaceableInContact: Boolean = true,
    var hasMoveOrder: Boolean = moving,
    var collisionShape: MovementCollisionShape = MovementCollisionShape.Circle(hardRadius),
    var mass: Double? = null,
    var moveSpeed: Double = 1.0,
    var maxTurnSpeed: Double = 3.0,
    var slidingMode: Boolean = false,
    var hasAttachments: Boolean = false,
    var definitionRadius: Double = hardRadius,
    var height: Double = 0.0,
    var flightTargetHeight: Double = 0.0,
    var parkedClusterKey: Any? = null,
) {
    /** Compares core pose and kinematic snapshot fields (aux traits like [collisionShape], [mass], [parkedClusterKey] are refreshed in [WorldSpatialIndex.beginStep]). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrozenSpatialUnit) return false
        return unitId == other.unitId &&
            updateKey == other.updateKey &&
            ownerPlayerId == other.ownerPlayerId &&
            layer == other.layer &&
            position == other.position &&
            heading == other.heading &&
            speed == other.speed &&
            flightVelocity == other.flightVelocity &&
            hardRadius == other.hardRadius &&
            bufferRadius == other.bufferRadius &&
            nativeTransportPlacement == other.nativeTransportPlacement &&
            moving == other.moving &&
            anchored == other.anchored &&
            displaceableInContact == other.displaceableInContact &&
            hasMoveOrder == other.hasMoveOrder
    }

    override fun hashCode(): Int {
        var result = unitId.hashCode()
        result = 31 * result + layer.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + heading.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + moving.hashCode()
        result = 31 * result + anchored.hashCode()
        return result
    }
}

/**
 * Primitive open-addressing `Long -> V` hash table with zero `java.lang.Long` boxing on lookup,
 * insert, or removal.
 */
internal class LongObjectTable<V : Any>(initialCapacity: Int = 64) {
    private var cap: Int = tableCapacity(initialCapacity)
    private var mask: Int = cap - 1
    private var shift: Int = 64 - Integer.numberOfTrailingZeros(cap)
    private var keys: LongArray = LongArray(cap) { EMPTY_KEY }
    @Suppress("UNCHECKED_CAST")
    private var values: Array<V?> = arrayOfNulls<Any?>(cap) as Array<V?>
    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    operator fun get(key: Long): V? {
        if (size == 0) return null
        if (key == EMPTY_KEY) return getMinKeyFallback()
        var idx = hashIndex(key)
        while (true) {
            val k = keys[idx]
            if (k == key) return values[idx]
            if (k == EMPTY_KEY) return null
            idx = (idx + 1) and mask
        }
    }

    private var minKeyVal: V? = null
    private fun getMinKeyFallback(): V? = minKeyVal

    operator fun set(key: Long, value: V) {
        if (key == EMPTY_KEY) {
            if (minKeyVal == null) size++
            minKeyVal = value
            return
        }
        if ((size + 1) * 4 >= cap * 3) rehash(cap shl 1)
        var idx = hashIndex(key)
        while (true) {
            val k = keys[idx]
            if (k == EMPTY_KEY) {
                keys[idx] = key
                values[idx] = value
                size++
                return
            }
            if (k == key) {
                values[idx] = value
                return
            }
            idx = (idx + 1) and mask
        }
    }

    inline fun getOrPut(key: Long, defaultValue: () -> V): V {
        val existing = get(key)
        if (existing != null) return existing
        val created = defaultValue()
        set(key, created)
        return created
    }

    fun remove(key: Long): V? {
        if (size == 0) return null
        if (key == EMPTY_KEY) {
            val old = minKeyVal ?: return null
            minKeyVal = null
            size--
            return old
        }
        var idx = hashIndex(key)
        while (true) {
            val k = keys[idx]
            if (k == EMPTY_KEY) return null
            if (k == key) {
                val old = values[idx]
                removeAtSlot(idx)
                size--
                return old
            }
            idx = (idx + 1) and mask
        }
    }

    private fun removeAtSlot(deleteIdx: Int) {
        var hole = deleteIdx
        var scan = (hole + 1) and mask
        while (true) {
            val k = keys[scan]
            if (k == EMPTY_KEY) {
                keys[hole] = EMPTY_KEY
                values[hole] = null
                return
            }
            val natural = hashIndex(k)
            // Move scan into hole if natural does not lie cyclically in (hole, scan]
            val shouldMove = if (hole <= scan) {
                natural <= hole || natural > scan
            } else {
                natural <= hole && natural > scan
            }
            if (shouldMove) {
                keys[hole] = k
                values[hole] = values[scan]
                hole = scan
            }
            scan = (scan + 1) and mask
        }
    }

    fun clear() {
        if (size == 0) return
        keys.fill(EMPTY_KEY)
        values.fill(null)
        minKeyVal = null
        size = 0
    }

    inline fun forEachEntry(visitor: (key: Long, value: V) -> Unit) {
        if (size == 0) return
        minKeyVal?.let { visitor(EMPTY_KEY, it) }
        val ks = keys
        val vs = values
        for (i in ks.indices) {
            val k = ks[i]
            if (k != EMPTY_KEY) {
                visitor(k, checkNotNull(vs[i]))
            }
        }
    }

    inline fun forEachValue(visitor: (value: V) -> Unit) {
        if (size == 0) return
        minKeyVal?.let(visitor)
        val ks = keys
        val vs = values
        for (i in ks.indices) {
            if (ks[i] != EMPTY_KEY) {
                visitor(checkNotNull(vs[i]))
            }
        }
    }

    private fun hashIndex(key: Long): Int = ((key * -7046029254386353131L) ushr shift).toInt()

    private fun rehash(nextCapacity: Int) {
        val oldKeys = keys
        val oldVals = values
        cap = tableCapacity(nextCapacity)
        mask = cap - 1
        shift = 64 - Integer.numberOfTrailingZeros(cap)
        keys = LongArray(cap) { EMPTY_KEY }
        @Suppress("UNCHECKED_CAST")
        values = arrayOfNulls<Any?>(cap) as Array<V?>
        val hadMin = if (minKeyVal != null) 1 else 0
        size = hadMin
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != EMPTY_KEY) {
                set(k, checkNotNull(oldVals[i]))
            }
        }
    }

    companion object {
        const val EMPTY_KEY: Long = Long.MIN_VALUE

        private fun tableCapacity(requested: Int): Int {
            var c = 16
            val target = max(16, requested)
            while (c < target) c = c shl 1
            return c
        }
    }
}

/**
 * Primitive flat cell head table for the frozen step-start snapshot:
 * - Zero `Long` boxing on `headOf(key)` or `prepend(key, unitIdx)`
 * - `O(touchedSlots)` `reset()` at `beginStep()` without clearing untouched table capacity
 */
private class FrozenCellTable(initialCapacity: Int = 128) {
    private var cap: Int = pow2(initialCapacity)
    private var mask: Int = cap - 1
    private var shift: Int = 64 - Integer.numberOfTrailingZeros(cap)
    private var keys: LongArray = LongArray(cap) { EMPTY_KEY }
    private var heads: IntArray = IntArray(cap) { -1 }
    private var tails: IntArray = IntArray(cap) { -1 }
    private var touched: IntArray = IntArray(cap)
    var touchedCount: Int = 0
        private set

    fun isEmpty(): Boolean = touchedCount == 0

    fun reset() {
        for (i in 0 until touchedCount) {
            val slot = touched[i]
            keys[slot] = EMPTY_KEY
            heads[slot] = -1
            tails[slot] = -1
        }
        touchedCount = 0
    }

    fun headOf(key: Long): Int {
        if (touchedCount == 0) return -1
        var idx = hashIndex(key)
        while (true) {
            val k = keys[idx]
            if (k == key) return heads[idx]
            if (k == EMPTY_KEY) return -1
            idx = (idx + 1) and mask
        }
    }

    /** Appends [frozenIdx] to the tail of cell [key] in `nextInCell`, preserving bucket `updateKey` order. */
    fun append(key: Long, frozenIdx: Int, nextInCell: IntArray) {
        if ((touchedCount + 1) * 4 >= cap * 3) grow(cap shl 1)
        var idx = hashIndex(key)
        while (true) {
            val k = keys[idx]
            if (k == EMPTY_KEY) {
                keys[idx] = key
                heads[idx] = frozenIdx
                tails[idx] = frozenIdx
                nextInCell[frozenIdx] = -1
                touched[touchedCount++] = idx
                return
            }
            if (k == key) {
                val prevTail = tails[idx]
                nextInCell[prevTail] = frozenIdx
                tails[idx] = frozenIdx
                nextInCell[frozenIdx] = -1
                return
            }
            idx = (idx + 1) and mask
        }
    }

    fun headAtTouched(index: Int): Int = heads[touched[index]]

    inline fun forEachHead(visitor: (headIdx: Int) -> Unit) {
        for (i in 0 until touchedCount) {
            val h = heads[touched[i]]
            if (h >= 0) visitor(h)
        }
    }

    private fun hashIndex(key: Long): Int = ((key * -7046029254386353131L) ushr shift).toInt()

    private fun grow(nextCapacity: Int) {
        val oldKeys = keys
        val oldHeads = heads
        val oldTails = tails
        val oldTouched = touched
        val oldCount = touchedCount
        cap = pow2(nextCapacity)
        mask = cap - 1
        shift = 64 - Integer.numberOfTrailingZeros(cap)
        keys = LongArray(cap) { EMPTY_KEY }
        heads = IntArray(cap) { -1 }
        tails = IntArray(cap) { -1 }
        touched = IntArray(cap)
        touchedCount = 0
        for (i in 0 until oldCount) {
            val oldSlot = oldTouched[i]
            val k = oldKeys[oldSlot]
            var idx = hashIndex(k)
            while (keys[idx] != EMPTY_KEY) idx = (idx + 1) and mask
            keys[idx] = k
            heads[idx] = oldHeads[oldSlot]
            tails[idx] = oldTails[oldSlot]
            touched[touchedCount++] = idx
        }
    }

    companion object {
        private const val EMPTY_KEY: Long = Long.MIN_VALUE
        private fun pow2(requested: Int): Int {
            var c = 32
            while (c < requested) c = c shl 1
            return c
        }
    }
}

/**
 * Step-authoritative spatial index attached to [StepState] (ADR-012 / ENG-P016-002 / ENG-P016-009C).
 *
 * Uses primitive open-addressing spatial tables (`LongArray` keys, `IntArray` intrusive cell lists,
 * reusable `FrozenSpatialUnit` slots, and zero-allocation Top-K scratch insertion) so 60Hz neighbor
 * and swept contact queries allocate zero heap objects in steady state while maintaining exact
 * deterministic `updateKey` ordering and candidate-step `fork()` isolation.
 */
internal class WorldSpatialIndex private constructor(
    private val cellSize: Double,
    private val buckets: Array<LongObjectTable<ArrayList<MutableUnit>>>,
    private val entries: HashMap<String, Entry>,
    private val maxHardRadius: DoubleArray,
    private val maxBufferRadius: DoubleArray,
) {
    private class Entry(
        var unit: MutableUnit,
        var layer: SpatialLayer,
        var cellKey: Long,
        var hardRadius: Double,
        var bufferRadius: Double,
        var frozenEpoch: Int = 0,
        var frozenIndex: Int = -1,
        var cachedFrozen: FrozenSpatialUnit? = null,
    )

    private val bucketListPool: ArrayDeque<ArrayList<MutableUnit>> = ArrayDeque(64)

    // Reusable frozen snapshot buffers (isolated per WorldSpatialIndex instance; never shared across fork)
    private var frozenValid: Boolean = false
    private var frozenEpoch: Int = 1
    private var frozenCount: Int = 0
    private var frozenUnits: Array<FrozenSpatialUnit?> = arrayOfNulls(64)
    private var frozenNextInCell: IntArray = IntArray(64) { -1 }
    private val frozenTables: Array<FrozenCellTable> = Array(LAYER_COUNT) { FrozenCellTable(128) }
    private val frozenMaxHardRadius: DoubleArray = DoubleArray(LAYER_COUNT)
    private val frozenMaxBufferRadius: DoubleArray = DoubleArray(LAYER_COUNT)

    // Scratch buffers for allocation-free Top-K queries when callers use queryFrozenTopK
    private var scratchTopUnits: Array<FrozenSpatialUnit?> = arrayOfNulls(32)
    private var scratchTopDistSq: DoubleArray = DoubleArray(32)

    constructor(cellSize: Double = DEFAULT_CELL_SIZE) : this(
        cellSize = cellSize,
        buckets = Array(LAYER_COUNT) { LongObjectTable(64) },
        entries = HashMap(),
        maxHardRadius = DoubleArray(LAYER_COUNT),
        maxBufferRadius = DoubleArray(LAYER_COUNT),
    )

    fun isEmpty(): Boolean = entries.isEmpty()

    fun fork(forkedUnits: Map<String, MutableUnit>): WorldSpatialIndex {
        val nextBuckets = Array(LAYER_COUNT) { layerIdx ->
            val src = buckets[layerIdx]
            val dst = LongObjectTable<ArrayList<MutableUnit>>(max(16, (src.size * 4) / 3 + 2))
            src.forEachEntry { key, list ->
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
            nextEntries[id] = Entry(
                unit = mapped,
                layer = entry.layer,
                cellKey = entry.cellKey,
                hardRadius = entry.hardRadius,
                bufferRadius = entry.bufferRadius,
                frozenEpoch = 0,
                frozenIndex = -1,
                cachedFrozen = null,
            )
        }
        return WorldSpatialIndex(
            cellSize = cellSize,
            buckets = nextBuckets,
            entries = nextEntries,
            maxHardRadius = maxHardRadius.copyOf(),
            maxBufferRadius = maxBufferRadius.copyOf(),
        )
    }

    /** Rebuilds the live buckets from an authoritative unit collection. */
    fun syncAll(units: Collection<MutableUnit>) {
        for (layerIdx in 0 until LAYER_COUNT) {
            buckets[layerIdx].forEachValue { list ->
                list.clear()
                if (bucketListPool.size < 256) bucketListPool.addLast(list)
            }
            buckets[layerIdx].clear()
            maxHardRadius[layerIdx] = 0.0
            maxBufferRadius[layerIdx] = 0.0
        }
        entries.clear()
        invalidateFrozen()
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
            existing.unit = unit
            existing.layer = layer
            existing.cellKey = key
            existing.hardRadius = hard
            existing.bufferRadius = buf
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
        buckets[layerIndex].forEachValue { list ->
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

    private fun invalidateFrozen() {
        frozenValid = false
        for (li in 0 until LAYER_COUNT) frozenTables[li].reset()
        for (i in 0 until frozenCount) frozenUnits[i] = null
        frozenCount = 0
    }

    private fun ensureFrozenCapacity(required: Int) {
        if (required <= frozenUnits.size) return
        var cap = frozenUnits.size
        while (cap < required) cap = cap shl 1
        frozenUnits = frozenUnits.copyOf(cap)
        frozenNextInCell = frozenNextInCell.copyOf(cap)
    }

    /**
     * Marks step start and captures the immutable step-start snapshot into reusable primitive buffers,
     * avoiding per-step `HashMap`, `ArrayList`, `FrozenSpatialUnit`, and `Long` boxing allocations.
     */
    fun beginStep(
        captureFrozen: Boolean = true,
        canDisplace: ((MutableUnit) -> Boolean)? = null,
    ) {
        if (!captureFrozen) {
            invalidateFrozen()
            return
        }
        for (li in 0 until LAYER_COUNT) {
            frozenTables[li].reset()
            maxHardRadius[li] = 0.0
            maxBufferRadius[li] = 0.0
            frozenMaxHardRadius[li] = 0.0
            frozenMaxBufferRadius[li] = 0.0
        }
        val prevCount = frozenCount
        ensureFrozenCapacity(max(16, entries.size))
        val epoch = if (frozenEpoch == Int.MAX_VALUE) {
            for (e in entries.values) e.frozenEpoch = 0
            1
        } else frozenEpoch + 1
        frozenEpoch = epoch

        var idx = 0
        for (li in 0 until LAYER_COUNT) {
            val layerEnum = SpatialLayer.entries[li]
            val dstTable = frozenTables[li]
            buckets[li].forEachEntry { key, liveList ->
                for (i in 0 until liveList.size) {
                    val u = liveList[i]
                    val shape = u.definition.movementCollision
                    val hard = shape.boundingRadius
                    val buf = u.definition.contactBufferCollision.boundingRadius
                    if (hard > maxHardRadius[li]) maxHardRadius[li] = hard
                    if (buf > maxBufferRadius[li]) maxBufferRadius[li] = buf
                    if (u.attachment != null || u.definition.building != null) continue
                    val hasOrder = u.moveTarget != null
                    val movingNow = hasOrder || u.speed != 0.0
                    val anchoredNow = computeStepStartAnchored(u, movingNow, hard)
                    val displaceableNow = canDisplace?.invoke(u) ?: true
                    val mv = UnitLocomotion.movement(u)
                    val flightTargetH = u.definition.movement.flight?.targetHeight ?: 0.0
                    val hasAtt = u.attachmentOffsets.isNotEmpty()
                    val parkedGroup = if (u.landingMember?.parked == true) u.landingMember?.group else null
                    val entry = entries[u.unitId]
                    val prior = entry?.cachedFrozen
                    val snap = if (prior != null) {
                        prior.unitId = u.unitId
                        prior.updateKey = u.updateKey
                        prior.ownerPlayerId = u.ownerPlayerId
                        prior.layer = layerEnum
                        prior.position = u.position
                        prior.heading = u.heading
                        prior.speed = u.speed
                        prior.flightVelocity = u.flightVelocity
                        prior.hardRadius = hard
                        prior.bufferRadius = buf
                        prior.nativeTransportPlacement = u.nativeTransportPlacement
                        prior.moving = movingNow
                        prior.anchored = anchoredNow
                        prior.displaceableInContact = displaceableNow
                        prior.hasMoveOrder = hasOrder
                        prior.collisionShape = shape
                        prior.mass = u.definition.mass
                        prior.moveSpeed = mv.moveSpeed
                        prior.maxTurnSpeed = mv.maxTurnSpeed
                        prior.slidingMode = mv.slidingMode
                        prior.hasAttachments = hasAtt
                        prior.definitionRadius = u.definition.radius
                        prior.height = u.height
                        prior.flightTargetHeight = flightTargetH
                        prior.parkedClusterKey = parkedGroup
                        prior
                    } else {
                        FrozenSpatialUnit(
                            unitId = u.unitId,
                            updateKey = u.updateKey,
                            ownerPlayerId = u.ownerPlayerId,
                            layer = layerEnum,
                            position = u.position,
                            heading = u.heading,
                            speed = u.speed,
                            flightVelocity = u.flightVelocity,
                            hardRadius = hard,
                            bufferRadius = buf,
                            nativeTransportPlacement = u.nativeTransportPlacement,
                            moving = movingNow,
                            anchored = anchoredNow,
                            displaceableInContact = displaceableNow,
                            hasMoveOrder = hasOrder,
                            collisionShape = shape,
                            mass = u.definition.mass,
                            moveSpeed = mv.moveSpeed,
                            maxTurnSpeed = mv.maxTurnSpeed,
                            slidingMode = mv.slidingMode,
                            hasAttachments = hasAtt,
                            definitionRadius = u.definition.radius,
                            height = u.height,
                            flightTargetHeight = flightTargetH,
                            parkedClusterKey = parkedGroup,
                        ).also { if (entry != null) entry.cachedFrozen = it }
                    }
                    if (idx >= frozenUnits.size) ensureFrozenCapacity(idx + 1)
                    frozenUnits[idx] = snap
                    dstTable.append(key, idx, frozenNextInCell)
                    if (entry != null) {
                        entry.frozenEpoch = epoch
                        entry.frozenIndex = idx
                    }
                    if (hard > frozenMaxHardRadius[li]) frozenMaxHardRadius[li] = hard
                    if (buf > frozenMaxBufferRadius[li]) frozenMaxBufferRadius[li] = buf
                    idx++
                }
            }
        }
        for (stale in idx until prevCount) {
            frozenUnits[stale] = null
        }
        frozenCount = idx
        frozenValid = true
    }

    private fun computeStepStartAnchored(u: MutableUnit, movingNow: Boolean, hardRadius: Double): Boolean {
        if (!movingNow) return true
        if (u.moveTarget == null && abs(u.speed) <= 0.05) return true
        if (u.constructionWorking) return true
        if (u.spiderBlink != null) return true
        val waitingEntry = u.passageEntry
        if (waitingEntry != null && !waitingEntry.admitted && !waitingEntry.entered && abs(u.speed) < 0.05) {
            val mouth = waitingEntry.passage.mouths[waitingEntry.side]
            val across = if (mouth.horizontal) u.position.y else u.position.x
            val clearBand = max(1.0, hardRadius) * 1.25
            if (across < mouth.low - clearBand || across > mouth.high + clearBand) return true
        }
        return false
    }

    fun frozenUnit(unitId: String): FrozenSpatialUnit? {
        if (!frozenValid) return null
        val entry = entries[unitId] ?: return null
        return if (entry.frozenEpoch == frozenEpoch && entry.frozenIndex in 0 until frozenCount) {
            frozenUnits[entry.frozenIndex]
        } else null
    }

    /** Returns all step-start frozen snapshots in [layer] in deterministic [updateKey] order. */
    fun frozenLayerUnits(layer: SpatialLayer): List<FrozenSpatialUnit> {
        if (!frozenValid) return emptyList()
        val table = frozenTables[layer.ordinal]
        if (table.isEmpty()) return emptyList()
        val out = ArrayList<FrozenSpatialUnit>()
        table.forEachHead { headIdx ->
            var cur = headIdx
            while (cur >= 0) {
                out.add(checkNotNull(frozenUnits[cur]))
                cur = frozenNextInCell[cur]
            }
        }
        out.sortWith(FROZEN_KEY_COMPARATOR)
        return out
    }

    /**
     * Zero-allocation Top-K query into caller-provided [outUnits] and [outDistSq] (each length >= [limit]).
     * Returns the number of matched neighbors `count in 0..limit`, sorted strictly by `(distSq, updateKey)`.
     */
    inline fun queryFrozenTopKInto(
        observer: MutableUnit,
        layer: SpatialLayer,
        radius: Double,
        limit: Int,
        includeBufferPadding: Boolean = false,
        outUnits: Array<FrozenSpatialUnit?>,
        outDistSq: DoubleArray,
        visible: (FrozenSpatialUnit) -> Boolean,
    ): Int {
        if (limit <= 0 || radius < 0.0 || !radius.isFinite()) return 0
        ensureFrozenReady()
        val obsSnap = frozenUnit(observer.unitId)
        val ox = obsSnap?.position?.x ?: observer.position.x
        val oy = obsSnap?.position?.y ?: observer.position.y
        val li = layer.ordinal
        if (isFrozenLayerEmpty(li)) return 0

        val obsBuf = obsSnap?.bufferRadius ?: observer.definition.contactBufferCollision.boundingRadius
        val pad = if (includeBufferPadding) obsBuf + frozenMaxBufferFor(li) else 0.0
        val searchR = radius + pad
        val x0 = cellCoordOf(ox - searchR)
        val x1 = cellCoordOf(ox + searchR)
        val y0 = cellCoordOf(oy - searchR)
        val y1 = cellCoordOf(oy + searchR)
        val obsId = observer.unitId

        var count = 0
        val spanX = x1.toLong() - x0.toLong() + 1L
        val spanY = y1.toLong() - y0.toLong() + 1L
        val touchedBuckets = frozenBucketCount(li)
        if (spanX > 256L || spanY > 256L || spanX * spanY > touchedBuckets.toLong() * 4L + 16L) {
            for (bi in 0 until touchedBuckets) {
                var cur = frozenTouchedHeadAt(li, bi)
                while (cur >= 0) {
                    val candidate = frozenUnitAt(cur)
                    cur = frozenNextAt(cur)
                    if (candidate.unitId == obsId) continue
                    val dx = candidate.position.x - ox
                    val dy = candidate.position.y - oy
                    val maxDist = if (includeBufferPadding) radius + obsBuf + candidate.bufferRadius else radius
                    val distSq = dx * dx + dy * dy
                    if (distSq > maxDist * maxDist) continue
                    if (count == limit) {
                        val worstDist = outDistSq[count - 1]
                        if (distSq > worstDist) continue
                        if (distSq == worstDist && candidate.updateKey >= checkNotNull(outUnits[count - 1]).updateKey) continue
                    }
                    if (!visible(candidate)) continue
                    count = insertTopK(candidate, distSq, count, limit, outUnits, outDistSq)
                }
            }
        } else {
            for (cx in x0..x1) {
                for (cy in y0..y1) {
                    var cur = frozenHeadAt(li, packKeyOf(cx, cy))
                    while (cur >= 0) {
                        val candidate = frozenUnitAt(cur)
                        cur = frozenNextAt(cur)
                        if (candidate.unitId == obsId) continue
                        val dx = candidate.position.x - ox
                        val dy = candidate.position.y - oy
                        val maxDist = if (includeBufferPadding) radius + obsBuf + candidate.bufferRadius else radius
                        val distSq = dx * dx + dy * dy
                        if (distSq > maxDist * maxDist) continue
                        if (count == limit) {
                            val worstDist = outDistSq[count - 1]
                            if (distSq > worstDist) continue
                            if (distSq == worstDist && candidate.updateKey >= checkNotNull(outUnits[count - 1]).updateKey) continue
                        }
                        if (!visible(candidate)) continue
                        count = insertTopK(candidate, distSq, count, limit, outUnits, outDistSq)
                    }
                }
            }
        }
        return count
    }

    @PublishedApi internal fun insertTopK(
        candidate: FrozenSpatialUnit,
        distSq: Double,
        currentCount: Int,
        limit: Int,
        outUnits: Array<FrozenSpatialUnit?>,
        outDistSq: DoubleArray,
    ): Int {
        var count = currentCount
        var ins = count
        if (count < limit) {
            count++
        } else {
            ins = limit - 1
        }
        while (ins > 0) {
            val prevDist = outDistSq[ins - 1]
            val prevUnit = checkNotNull(outUnits[ins - 1])
            val c = distSq.compareTo(prevDist)
            if (c > 0 || (c == 0 && candidate.updateKey >= prevUnit.updateKey)) break
            outDistSq[ins] = prevDist
            outUnits[ins] = prevUnit
            ins--
        }
        outDistSq[ins] = distSq
        outUnits[ins] = candidate
        return count
    }

    @PublishedApi internal fun ensureFrozenReady() {
        if (!frozenValid) beginStep(captureFrozen = true)
    }

    @PublishedApi internal fun isFrozenLayerEmpty(li: Int): Boolean = frozenTables[li].isEmpty()
    @PublishedApi internal fun frozenBucketCount(li: Int): Int = frozenTables[li].touchedCount
    @PublishedApi internal fun frozenTouchedHeadAt(li: Int, index: Int): Int = frozenTables[li].headAtTouched(index)
    @PublishedApi internal fun frozenMaxBufferFor(li: Int): Double = frozenMaxBufferRadius[li]
    @PublishedApi internal fun frozenHeadAt(li: Int, key: Long): Int = frozenTables[li].headOf(key)
    @PublishedApi internal fun frozenUnitAt(idx: Int): FrozenSpatialUnit = checkNotNull(frozenUnits[idx])
    @PublishedApi internal fun frozenNextAt(idx: Int): Int = frozenNextInCell[idx]

    /**
     * Queries the step-start frozen snapshot for the deterministic Top-K nearest visible neighbors
     * within [radius]. Never truncates to empty or returns null under dense crowds.
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
        if (limit > scratchTopUnits.size) {
            var cap = scratchTopUnits.size
            while (cap < limit) cap = cap shl 1
            scratchTopUnits = arrayOfNulls(cap)
            scratchTopDistSq = DoubleArray(cap)
        }
        val uBuf = scratchTopUnits
        val dBuf = scratchTopDistSq
        val count = queryFrozenTopKInto(observer, layer, radius, limit, includeBufferPadding, uBuf, dBuf, visible)
        if (count == 0) return emptyList()
        val result = ArrayList<FrozenSpatialUnit>(count)
        for (i in 0 until count) {
            result.add(checkNotNull(uBuf[i]))
            uBuf[i] = null
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
        ensureFrozenReady()
        val li = layer.ordinal
        val table = frozenTables[li]
        if (table.isEmpty()) return emptyList()
        val pad = if (useBufferRadius) frozenMaxBufferRadius[li] else frozenMaxHardRadius[li]
        val x0 = cellCoord(minX - pad)
        val x1 = cellCoord(maxX + pad)
        val y0 = cellCoord(minY - pad)
        val y1 = cellCoord(maxY + pad)
        val out = ArrayList<FrozenSpatialUnit>()
        val spanX = x1.toLong() - x0.toLong() + 1L
        val spanY = y1.toLong() - y0.toLong() + 1L
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite() ||
            spanX > 256L || spanY > 256L || spanX * spanY > table.touchedCount.toLong() * 4L + 16L
        ) {
            table.forEachHead { head ->
                var cur = head
                while (cur >= 0) {
                    val u = checkNotNull(frozenUnits[cur])
                    cur = frozenNextInCell[cur]
                    val r = if (useBufferRadius) u.bufferRadius else u.hardRadius
                    if (u.position.x + r >= minX && u.position.x - r <= maxX &&
                        u.position.y + r >= minY && u.position.y - r <= maxY &&
                        visible(u)
                    ) {
                        out.add(u)
                    }
                }
            }
        } else {
            for (cx in x0..x1) {
                for (cy in y0..y1) {
                    var cur = table.headOf(packKey(cx, cy))
                    while (cur >= 0) {
                        val u = checkNotNull(frozenUnits[cur])
                        cur = frozenNextInCell[cur]
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
        val spanX = x1.toLong() - x0.toLong() + 1L
        val spanY = y1.toLong() - y0.toLong() + 1L
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite() ||
            spanX > 256L || spanY > 256L || spanX * spanY > layerMap.size.toLong() * 4L + 16L
        ) {
            layerMap.forEachValue { list ->
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
        } else {
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
        }
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

    /**
     * Zero-allocation callback visitor for live bounds queries when the caller does not depend
     * on [MutableUnit.updateKey] iteration order. Returning `true` stops iteration early.
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
        val spanX = x1.toLong() - x0.toLong() + 1L
        val spanY = y1.toLong() - y0.toLong() + 1L
        if (!minX.isFinite() || !maxX.isFinite() || !minY.isFinite() || !maxY.isFinite() ||
            spanX > 256L || spanY > 256L || spanX * spanY > layerMap.size.toLong() * 4L + 16L
        ) {
            layerMap.forEachValue { list ->
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
            return
        }
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

    @PublishedApi internal fun bucketsFor(li: Int): LongObjectTable<ArrayList<MutableUnit>> = buckets[li]
    @PublishedApi internal fun maxRadiusFor(li: Int, useBufferRadius: Boolean): Double =
        if (useBufferRadius) maxBufferRadius[li] else maxHardRadius[li]
    @PublishedApi internal fun cellCoordOf(v: Double): Int = floor(v / cellSize).toInt()
    @PublishedApi internal fun packKeyOf(cx: Int, cy: Int): Long = packKey(cx, cy)

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

    fun allInLayer(layer: SpatialLayer): List<MutableUnit> {
        val layerMap = buckets[layer.ordinal]
        if (layerMap.isEmpty()) return emptyList()
        val out = ArrayList<MutableUnit>()
        layerMap.forEachValue { list -> out.addAll(list) }
        if (out.size > 1) out.sortWith(UNIT_KEY_COMPARATOR)
        return out
    }

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
            buckets[li].forEachValue { list -> bucketTotal += list.size }
        }
        require(bucketTotal == expectedCount) {
            "WorldSpatialIndex bucket total $bucketTotal != expected $expectedCount"
        }
    }

    private fun addToBucket(layerIndex: Int, key: Long, unit: MutableUnit) {
        val bucket = buckets[layerIndex].getOrPut(key) {
            bucketListPool.removeLastOrNull() ?: ArrayList(4)
        }
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
        if (bucket.isEmpty()) {
            layerMap.remove(key)
            if (bucketListPool.size < 256) bucketListPool.addLast(bucket)
        }
    }

    private fun cellCoord(v: Double): Int = floor(v / cellSize).toInt()
    private fun cellKey(x: Double, y: Double): Long = packKey(cellCoord(x), cellCoord(y))

    companion object {
        const val DEFAULT_CELL_SIZE = 48.0
        private val LAYER_COUNT = SpatialLayer.entries.size

        private val UNIT_KEY_COMPARATOR = Comparator<MutableUnit> { a, b -> a.updateKey.compareTo(b.updateKey) }
        private val FROZEN_KEY_COMPARATOR = Comparator<FrozenSpatialUnit> { a, b -> a.updateKey.compareTo(b.updateKey) }

        private fun packKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

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
