package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

/** Immutable value: 30 physical slots, at most 29 active. Slot 29 is still written at capacity. */
internal class UnitTaskQueue private constructor(private val slots: List<UnitTask?>, val size: Int) {
    constructor() : this(List(30) { null }, 0)

    val head: UnitTask? get() = if (size > 0) slots[0] else null
    val second: UnitTask? get() = if (size > 1) slots[1] else null
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
        require(slots.size == 30 && size in 0..29) {
            "Invalid task queue capacity"
        }
        for (i in 0 until size) {
            require(slots[i] != null) { "Invalid task queue capacity" }
        }
        // Include the inactive capacity slot: it is written by a full append and later shifted.
        for (i in 0 until 30) {
            val task = slots[i] ?: continue
            require(task.extraWorkRange == 0f && (!task.automaticRepair || task.kind == UnitTaskKind.CONSTRUCT)) {
                "Invalid automatic repair task"
            }
            require(task.actionId.isNotEmpty() && (task.atStep == null || task.atStep >= 0)) {
                "Invalid task identity"
            }
            when (task.kind) {
                UnitTaskKind.MOVE, UnitTaskKind.PATROL -> require(task.target?.let {
                    it.x.isFinite() && it.y.isFinite()
                } == true && task.targetUnitId == null) { "Invalid point task" }
                UnitTaskKind.ATTACK, UnitTaskKind.LOAD_INTO, UnitTaskKind.LOAD_UP -> require(task.target == null && !task.targetUnitId.isNullOrEmpty()) {
                    "Invalid attack task"
                }
                UnitTaskKind.BUILD -> require(task.target?.let { it.x.isFinite() && it.y.isFinite() } == true &&
                    !task.buildingTypeId.isNullOrEmpty() && isLowerHex32(task.buildingNonce) &&
                    task.targetUnitId == null) { "Invalid construction intent" }
                UnitTaskKind.CONSTRUCT, UnitTaskKind.RECLAIM -> require(!task.targetUnitId.isNullOrEmpty() && task.target == null) {
                    "Invalid construction target"
                }
            }
        }
    }

    companion object {
        private fun isLowerHex32(value: String?): Boolean {
            if (value == null || value.length != 32) return false
            for (i in 0 until 32) {
                val c = value[i]
                if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false
            }
            return true
        }
    }
}
