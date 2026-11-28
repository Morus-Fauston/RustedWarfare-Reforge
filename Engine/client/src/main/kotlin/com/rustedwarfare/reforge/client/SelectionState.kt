package com.rustedwarfare.reforge.client

import java.util.Collections

/** Session-local presentation IDs; callers admit members through the authorized selection boundary. */
class SelectionState(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val selected = linkedSetOf<String>()
    private var selectedSnapshot: Set<String> = Collections.emptySet()
    private var explicitPrimary: String? = null
    private val groups = Array(10) { linkedSetOf<String>() }
    private val lastRecallMillis = arrayOfNulls<Long>(10)
    val selectedIds: Set<String> get() = selectedSnapshot
    val primaryId: String? get() = explicitPrimary?.takeIf { it in selected } ?: selected.lastOrNull()
    var revision: Long = 0L
        private set

    private fun refreshSnapshot() {
        selectedSnapshot = if (selected.isEmpty()) Collections.emptySet()
        else Collections.unmodifiableSet(LinkedHashSet(selected))
    }

    fun select(ids: Collection<String>, mode: SelectionMode = SelectionMode.REPLACE) {
        val before = selected.toList()
        val beforePrimary = primaryId
        val unique = ids.toSet()
        when (mode) {
            SelectionMode.REPLACE -> {
                selected.clear()
                selected.addAll(unique)
                explicitPrimary = null
            }
            SelectionMode.ADD -> {
                selected.addAll(unique)
                if (unique.isNotEmpty()) explicitPrimary = null
            }
            SelectionMode.TOGGLE -> {
                unique.forEach { if (!selected.remove(it)) selected.add(it) }
                if (explicitPrimary !in selected) explicitPrimary = null
            }
        }
        if (before != selected.toList() || beforePrimary != primaryId) {
            refreshSnapshot()
            revision++
        }
    }

    /** Explicitly switches the primary unit inside the existing [selectedIds] set without mutating membership. */
    fun setPrimary(unitId: String): Boolean {
        if (unitId !in selected) return false
        if (primaryId == unitId) return true
        explicitPrimary = unitId
        revision++
        return true
    }

    fun saveGroup(index: Int) {
        require(index in groups.indices)
        groups[index].clear()
        groups[index].addAll(selected)
    }

    /** Appends current [selectedIds] to group [index] without clearing existing members. */
    fun appendGroup(index: Int) {
        require(index in groups.indices)
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
        val changed = selected.retainAll(visibleAliveIds)
        if (explicitPrimary != null && explicitPrimary !in selected) {
            explicitPrimary = null
        }
        if (changed) {
            refreshSnapshot()
            revision++
        }
        groups.forEach { it.removeAll(observedDeadIds) }
    }

    fun clear() {
        if (selected.isNotEmpty() || explicitPrimary != null) revision++
        selected.clear()
        selectedSnapshot = Collections.emptySet()
        explicitPrimary = null
        groups.forEach { it.clear() }
        lastRecallMillis.fill(null)
    }
}

enum class SelectionMode { REPLACE, ADD, TOGGLE }
