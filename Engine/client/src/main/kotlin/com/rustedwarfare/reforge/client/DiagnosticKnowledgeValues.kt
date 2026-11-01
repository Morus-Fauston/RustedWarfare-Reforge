package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.knowledge.CellKnowledge
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Collections
import java.util.RandomAccess

/** A fixed snapshot, projected only as the codec consumes it; no host or live callback. */
internal fun diagnosticKnowledgeValues(snapshot: KnowledgeSnapshot): List<Map<String, Any?>> =
    Collections.unmodifiableList(object : AbstractList<Map<String, Any?>>(), RandomAccess {
        override val size: Int get() = snapshot.cells.size
        override fun get(index: Int): Map<String, Any?> = KnowledgeCellValues(snapshot.cells[index])
    })

private class KnowledgeCellValues(private val cell: CellKnowledge) : AbstractMap<String, Any?>() {
    override val size: Int get() = 3
    override fun containsKey(key: String): Boolean = key == "visible" || key == "blocked" || key == "gid"
    override fun get(key: String): Any? = when (key) {
        "visible" -> cell.visible
        "blocked" -> cell.lastKnownBlocked
        "gid" -> cell.lastKnownGroundGid
        else -> null
    }
    override val entries: Set<Map.Entry<String, Any?>> get() = object : AbstractSet<Map.Entry<String, Any?>>() {
        override val size: Int get() = 3
        override fun iterator(): Iterator<Map.Entry<String, Any?>> = object : Iterator<Map.Entry<String, Any?>> {
            private var index = 0
            override fun hasNext(): Boolean = index < 3
            override fun next(): Map.Entry<String, Any?> {
                val key = when (index++) {
                    0 -> "visible"
                    1 -> "blocked"
                    2 -> "gid"
                    else -> throw NoSuchElementException()
                }
                return SimpleImmutableEntry(key, this@KnowledgeCellValues[key])
            }
        }
    }
}
