package com.rustedwarfare.reforge.client

import java.util.Collections

/** Session-local presentation IDs; callers admit members through the authorized selection boundary. */
class SelectionState(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val selected = linkedSetOf<String>()
    private val groups = Array(10) { linkedSetOf<String>() }
    private val lastRecallMillis = arrayOfNulls<Long>(10)
    val selectedIds: Set<String> get() = Collections.unmodifiableSet(LinkedHashSet(selected))
    val primaryId: String? get() = selected.lastOrNull()

    fun select(ids: Collection<String>, mode: SelectionMode = SelectionMode.REPLACE) {
        val unique = ids.toSet()
        when (mode) {
            SelectionMode.REPLACE -> { selected.clear(); selected.addAll(unique) }
            SelectionMode.ADD -> selected.addAll(unique)
            SelectionMode.TOGGLE -> unique.forEach { if (!selected.remove(it)) selected.add(it) }
        }
    }

    fun saveGroup(index: Int) {
        require(index in groups.indices)
        groups[index].clear()
        groups[index].addAll(selected)
    }

    fun groupMembers(index: Int): Set<String> {
        require(index in groups.indices)
        return Collections.unmodifiableSet(LinkedHashSet(groups[index]))
    }

    fun recallGroup(index: Int, eligibleIds: Set<String>, additive: Boolean = false): String? {
        val members = groupMembers(index).filter { it in eligibleIds }
        select(members, if (additive) SelectionMode.ADD else SelectionMode.REPLACE)
        val now = clockMillis()
        val previous = lastRecallMillis[index]
        lastRecallMillis[index] = now
        return if (previous != null && now - previous in 0L until 700L) members.lastOrNull() else null
    }

    fun reconcile(visibleAliveIds: Set<String>, observedDeadIds: Set<String>) {
        selected.retainAll(visibleAliveIds)
        groups.forEach { it.removeAll(observedDeadIds) }
    }

    fun clear() {
        selected.clear()
        groups.forEach { it.clear() }
        lastRecallMillis.fill(null)
    }
}

enum class SelectionMode { REPLACE, ADD, TOGGLE }
