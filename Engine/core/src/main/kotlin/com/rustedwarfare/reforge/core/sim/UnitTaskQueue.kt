package com.rustedwarfare.reforge.core.sim

/** Immutable value: 30 physical slots, at most 29 active. Slot 29 is still written at capacity. */
internal class UnitTaskQueue private constructor(private val slots: List<UnitTask?>, val size: Int) {
    constructor() : this(List(30) { null }, 0)

    val tasks: List<UnitTask> = immutable(slots.take(size).map { checkNotNull(it) })

    fun append(task: UnitTask): UnitTaskQueue {
        val next = slots.toMutableList()
        next[size] = task
        return UnitTaskQueue(next, if (size < 29) size + 1 else size)
    }

    fun pop(): UnitTaskQueue {
        check(size > 0)
        return UnitTaskQueue(slots.drop(1) + null, size - 1)
    }
    fun replaceHead(task: UnitTask): UnitTaskQueue {
        check(size > 0)
        val next = slots.toMutableList()
        next[0] = task
        return UnitTaskQueue(next, size)
    }

    fun validateCandidate() {
        require(slots.size == 30 && size in 0..29 && slots.take(size).all { it != null }) {
            "Invalid task queue capacity"
        }
        // Include the inactive capacity slot: it is written by a full append and later shifted.
        for (task in slots.filterNotNull()) {
            require(task.actionId.isNotEmpty() && (task.atStep == null || task.atStep >= 0)) {
                "Invalid task identity"
            }
            when (task.kind) {
                UnitTaskKind.MOVE, UnitTaskKind.PATROL -> require(task.target?.let {
                    it.x.isFinite() && it.y.isFinite()
                } == true && task.targetUnitId == null) { "Invalid point task" }
                UnitTaskKind.ATTACK -> require(task.target == null && !task.targetUnitId.isNullOrEmpty()) {
                    "Invalid attack task"
                }
                UnitTaskKind.BUILD -> require(task.target?.let { it.x.isFinite() && it.y.isFinite() } == true &&
                    !task.buildingTypeId.isNullOrEmpty() && task.buildingNonce?.matches(Regex("[0-9a-f]{32}")) == true &&
                    task.targetUnitId == null) { "Invalid construction intent" }
                UnitTaskKind.CONSTRUCT, UnitTaskKind.RECLAIM -> require(!task.targetUnitId.isNullOrEmpty() && task.target == null) {
                    "Invalid construction target"
                }
            }
        }
    }
}
