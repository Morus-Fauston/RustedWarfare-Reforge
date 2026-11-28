package com.rustedwarfare.reforge.core.navigation

import java.util.Arrays
import kotlin.math.max

/**
 * Zero-allocation primitive-array A* frontier using a generation-stamped flat cell table and a
 * parallel primitive binary min-heap (`IntArray`).
 *
 * Replaces boxed `PriorityQueue<Node>`, `HashMap<PathGridKey, Int>`, and `HashMap<PathGridKey, PathGridKey>`
 * for standard 2D cell-indexed A* searches (circular / non-heading-dependent units), while keeping
 * exact tie-breaking parity `(f, row, col, g)` and full candidate-step rollback via compact primitive
 * undo logs.
 *
 * State index encoding:
 * - `0 until cellCount`: regular grid cell `row * width + col`
 * - `cellCount`: optional distinct initial-pose state (when start position differs from start tile center)
 */
internal class PrimitiveGridFrontier(
    val width: Int,
    val height: Int,
    private val initialPoseIndex: Int = width * height,
    initialHeapCapacity: Int = 64,
) {
    val stateCount: Int = initialPoseIndex + 1

    private var generation: Int = 1
    private val seenGen = IntArray(stateCount)
    private val gCost = IntArray(stateCount)
    private val parentState = IntArray(stateCount)

    // Binary min-heap stored in parallel primitive arrays: no Node boxing.
    private var heapState = IntArray(max(16, initialHeapCapacity))
    private var heapG = IntArray(max(16, initialHeapCapacity))
    private var heapF = IntArray(max(16, initialHeapCapacity))
    var heapSize: Int = 0
        private set

    // Reusable buffers for rollback records so normal runs allocate zero lambdas when rollback == null.
    fun resetForNextSearch() {
        heapSize = 0
        if (generation == Int.MAX_VALUE) {
            Arrays.fill(seenGen, 0)
            generation = 1
        } else {
            generation++
        }
    }

    fun isEmpty(): Boolean = heapSize == 0

    fun stateIndex(col: Int, row: Int): Int = row * width + col

    fun colOf(state: Int, startCol: Int): Int =
        if (state == initialPoseIndex) startCol else state % width

    fun rowOf(state: Int, startRow: Int): Int =
        if (state == initialPoseIndex) startRow else state / width

    fun hasCost(state: Int): Boolean = seenGen[state] == generation

    fun costOf(state: Int): Int =
        if (seenGen[state] == generation) gCost[state] else Int.MAX_VALUE

    fun parentOf(state: Int): Int = parentState[state]

    /** Pops the minimum `(f, row, col, g)` entry into the caller's scratch callback (no allocation). */
    inline fun poll(
        startCol: Int,
        startRow: Int,
        rollback: PathSearchRollback?,
        onPop: (state: Int, g: Int, f: Int) -> Unit,
    ): Boolean {
        if (heapSize == 0) return false
        val st = heapState[0]
        val g = heapG[0]
        val f = heapF[0]
        popRoot(startCol, startRow)
        if (rollback != null) {
            rollback.record { pushRaw(st, g, f, startCol, startRow) }
        }
        onPop(st, g, f)
        return true
    }

    fun pushWithStart(
        state: Int,
        g: Int,
        f: Int,
        startCol: Int,
        startRow: Int,
        rollback: PathSearchRollback?,
    ) {
        pushRaw(state, g, f, startCol, startRow)
        if (rollback != null) {
            rollback.record { removeOne(state, g, f, startCol, startRow) }
        }
    }

    fun tryRelaxWithStart(
        nextState: Int,
        parent: Int,
        nextG: Int,
        nextF: Int,
        startCol: Int,
        startRow: Int,
        rollback: PathSearchRollback?,
    ): Boolean {
        val prevGen = seenGen[nextState]
        val prevG = if (prevGen == generation) gCost[nextState] else Int.MAX_VALUE
        if (nextG >= prevG) return false
        val savedG = gCost[nextState]
        val savedParent = parentState[nextState]
        seenGen[nextState] = generation
        gCost[nextState] = nextG
        parentState[nextState] = parent
        if (rollback != null) {
            rollback.record {
                seenGen[nextState] = prevGen
                gCost[nextState] = savedG
                parentState[nextState] = savedParent
            }
        }
        pushWithStart(nextState, nextG, nextF, startCol, startRow, rollback)
        return true
    }

    fun setRootWithStart(
        state: Int,
        f: Int,
        startCol: Int,
        startRow: Int,
        rollback: PathSearchRollback?,
    ) {
        val prevGen = seenGen[state]
        val prevG = gCost[state]
        val prevParent = parentState[state]
        seenGen[state] = generation
        gCost[state] = 0
        parentState[state] = -1
        if (rollback != null) {
            rollback.record {
                seenGen[state] = prevGen
                gCost[state] = prevG
                parentState[state] = prevParent
            }
        }
        pushWithStart(state, 0, f, startCol, startRow, rollback)
    }

    @PublishedApi
    internal fun pushRaw(state: Int, g: Int, f: Int, startCol: Int, startRow: Int) {
        if (heapSize == heapState.size) {
            val nextCap = heapState.size shl 1
            heapState = heapState.copyOf(nextCap)
            heapG = heapG.copyOf(nextCap)
            heapF = heapF.copyOf(nextCap)
        }
        val idx = heapSize++
        heapState[idx] = state
        heapG[idx] = g
        heapF[idx] = f
        siftUp(idx, startCol, startRow)
    }

    @PublishedApi
    internal fun popRoot(startCol: Int, startRow: Int) {
        val last = --heapSize
        if (last > 0) {
            heapState[0] = heapState[last]
            heapG[0] = heapG[last]
            heapF[0] = heapF[last]
            siftDown(0, startCol, startRow)
        }
    }

    private fun removeOne(state: Int, g: Int, f: Int, startCol: Int, startRow: Int) {
        for (i in 0 until heapSize) {
            if (heapState[i] == state && heapG[i] == g && heapF[i] == f) {
                val last = --heapSize
                if (i != last) {
                    val movedState = heapState[last]
                    val movedG = heapG[last]
                    val movedF = heapF[last]
                    heapState[i] = movedState
                    heapG[i] = movedG
                    heapF[i] = movedF
                    siftDown(i, startCol, startRow)
                    if (heapState[i] == movedState && heapG[i] == movedG && heapF[i] == movedF) {
                        siftUp(i, startCol, startRow)
                    }
                }
                return
            }
        }
        error("Rollback could not locate heap entry state=$state g=$g f=$f")
    }

    private fun siftUp(startIdx: Int, startCol: Int, startRow: Int) {
        var i = startIdx
        val st = heapState[i]
        val g = heapG[i]
        val f = heapF[i]
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (compareEntries(st, g, f, heapState[p], heapG[p], heapF[p], startCol, startRow) >= 0) break
            heapState[i] = heapState[p]
            heapG[i] = heapG[p]
            heapF[i] = heapF[p]
            i = p
        }
        heapState[i] = st
        heapG[i] = g
        heapF[i] = f
    }

    private fun siftDown(startIdx: Int, startCol: Int, startRow: Int) {
        var i = startIdx
        val st = heapState[i]
        val g = heapG[i]
        val f = heapF[i]
        val half = heapSize ushr 1
        while (i < half) {
            var child = (i shl 1) + 1
            val right = child + 1
            if (right < heapSize &&
                compareEntries(
                    heapState[right], heapG[right], heapF[right],
                    heapState[child], heapG[child], heapF[child],
                    startCol, startRow,
                ) < 0
            ) {
                child = right
            }
            if (compareEntries(st, g, f, heapState[child], heapG[child], heapF[child], startCol, startRow) <= 0) break
            heapState[i] = heapState[child]
            heapG[i] = heapG[child]
            heapF[i] = heapF[child]
            i = child
        }
        heapState[i] = st
        heapG[i] = g
        heapF[i] = f
    }

    /** Deterministic ordering matching `compareBy(f, row, col, g, initialPose)`. */
    private fun compareEntries(
        stA: Int, gA: Int, fA: Int,
        stB: Int, gB: Int, fB: Int,
        startCol: Int, startRow: Int,
    ): Int {
        if (fA != fB) return if (fA < fB) -1 else 1
        val rowA = if (stA == initialPoseIndex) startRow else stA / width
        val rowB = if (stB == initialPoseIndex) startRow else stB / width
        if (rowA != rowB) return if (rowA < rowB) -1 else 1
        val colA = if (stA == initialPoseIndex) startCol else stA % width
        val colB = if (stB == initialPoseIndex) startCol else stB % width
        if (colA != colB) return if (colA < colB) -1 else 1
        if (gA != gB) return if (gA < gB) -1 else 1
        return stA.compareTo(stB)
    }
}
