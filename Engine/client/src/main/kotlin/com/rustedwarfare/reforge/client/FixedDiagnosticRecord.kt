package com.rustedwarfare.reforge.client

import java.util.Collections
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import com.rustedwarfare.reforge.core.knowledge.KnowledgeSnapshot

/** Call-time diagnostic data, detached from caller containers and mutable scalar objects. */
class FixedDiagnosticRecord private constructor(val kind: String, private val baseValues: Map<String, Any?>,
    val metadata: Map<String, Any?>, val byteUpperBound: Long,
    internal val knowledgeSnapshot: KnowledgeSnapshot? = null) {
    val values: Map<String, Any?> get() {
        val knowledge = knowledgeSnapshot ?: return baseValues
        @Suppress("UNCHECKED_CAST")
        val sample = baseValues.getValue("sample") as Map<String, Any?>
        return Collections.unmodifiableMap(baseValues + ("sample" to Collections.unmodifiableMap(
            sample + ("knowledge" to diagnosticKnowledgeValues(knowledge)))))
    }
    companion object {
        fun capture(kind: String, values: Map<String, Any?>,
                    metadata: Map<String, Any?> = emptyMap()): FixedDiagnosticRecord =
            fixed(kind, values, metadata, null)

        fun observation(phase: String, observation: PlayerObservation, generation: Long? = null,
                        metadata: Map<String, Any?> = emptyMap()): FixedDiagnosticRecord =
            fixed("observation", mapOf("phase" to phase, "sample" to observation.fields(false)) +
                (generation?.let { mapOf("generation" to it) } ?: emptyMap()), metadata, observation.knowledge)

        private fun fixed(kind: String, values: Map<String, Any?>, metadata: Map<String, Any?>,
                          knowledge: KnowledgeSnapshot?): FixedDiagnosticRecord {
            val data = Freezer(); val fixedValues = data.freeze(values)
            val meta = Freezer(); val fixedMetadata = meta.freeze(metadata)
            // Each knowledge cell has three fixed ASCII keys, booleans/null and an Int gid.
            // 64 bytes cover its worst JSON shape, plus one comma per cell and array delimiters.
            val knowledgeBound = knowledge?.let { it.cells.size.toLong() * 65 + 2 } ?: 0
            @Suppress("UNCHECKED_CAST")
            return FixedDiagnosticRecord(kind, fixedValues as Map<String, Any?>, fixedMetadata as Map<String, Any?>,
                plus(1024L, plus(stringBound(kind), plus(data.bound, plus(meta.bound, knowledgeBound)))), knowledge)
        }

        private class Key(private val text: String) { override fun toString() = text }
        private class NumberValue(source: Number) : Number() {
            private val text = source.toString()
            private val long = source.toLong()
            private val double = source.toDouble()
            override fun toByte() = long.toByte()
            override fun toShort() = long.toShort()
            override fun toInt() = long.toInt()
            override fun toLong() = long
            override fun toFloat() = double.toFloat()
            override fun toDouble() = double
            override fun toString() = text
        }
        private fun plus(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
        private fun stringBound(value: String) = value.length.toLong() * 6 + 2 // worst JSON UTF-16 escaping
        private val EMPTY_MAP: Map<Any?, Any?> = Collections.unmodifiableMap(emptyMap())
        private val EMPTY_LIST: List<Any?> = Collections.unmodifiableList(emptyList())
        private val SINGLETON_MAP_CLASS = Collections.singletonMap<Any?, Any?>(null, null).javaClass

        private class CapturedMap(private val fixed: Array<MutableMap.MutableEntry<Any?, Any?>?>) : java.util.AbstractMap<Any?, Any?>() {
            override val size: Int get() = fixed.size
            override fun get(key: Any?): Any? {
                for (entry in fixed) if (checkNotNull(entry).key == key) return entry.value
                return null
            }
            override fun containsKey(key: Any?): Boolean {
                for (entry in fixed) if (checkNotNull(entry).key == key) return true
                return false
            }
            override val entries: MutableSet<MutableMap.MutableEntry<Any?, Any?>>
                get() = object : java.util.AbstractSet<MutableMap.MutableEntry<Any?, Any?>>() {
                    override val size: Int get() = fixed.size
                    override fun iterator(): MutableIterator<MutableMap.MutableEntry<Any?, Any?>> =
                        object : MutableIterator<MutableMap.MutableEntry<Any?, Any?>> {
                            private var index = 0
                            override fun hasNext(): Boolean = index < fixed.size
                            override fun next(): MutableMap.MutableEntry<Any?, Any?> {
                                if (!hasNext()) throw NoSuchElementException()
                                return checkNotNull(fixed[index++])
                            }
                            override fun remove(): Unit = throw UnsupportedOperationException()
                        }
                }
        }

        /** One call-owned budget accumulator; no temporary value/bound pair per field. */
        private class Freezer {
            var bound = 0L
                private set
            private fun add(bytes: Long) { bound = plus(bound, bytes) }
            private fun fixedKey(key: Any?): Any? = when (key) {
                null, is String, is Byte, is Short, is Int, is Long, is Float, is Double, is Boolean -> key
                else -> Key(key.toString())
            }
            fun freeze(value: Any?): Any? = when (value) {
                null -> { add(4); null }
                is String -> { add(stringBound(value)); value }
                is Boolean -> { add(5); value }
                is Byte, is Short, is Int, is Long, is Float, is Double -> { add(64); value }
                is Number -> NumberValue(value).also { add(maxOf(64L, stringBound(it.toString()))) }
                is Map<*, *> -> {
                    add(2)
                    if (value.isEmpty()) EMPTY_MAP else if (value.size == 1) {
                        val entry = value.entries.first()
                        val key = fixedKey(entry.key)
                        add(stringBound(key.toString()) + 2)
                        val fixedValue = freeze(entry.value)
                        if (value.javaClass == SINGLETON_MAP_CLASS && key === entry.key && fixedValue === entry.value) value
                        else Collections.singletonMap(key, fixedValue)
                    } else {
                        val entries = arrayOfNulls<MutableMap.MutableEntry<Any?, Any?>>(value.size)
                        var index = 0
                        for ((key, entry) in value) {
                            val capturedKey = fixedKey(key)
                            add(stringBound(capturedKey.toString()) + 2)
                            entries[index++] = java.util.AbstractMap.SimpleImmutableEntry(capturedKey, freeze(entry))
                        }
                        check(index == entries.size) { "Diagnostic map changed during capture" }
                        CapturedMap(entries)
                    }
                }
                is Iterable<*> -> {
                    add(2)
                    if (value is Collection<*> && value.isEmpty()) EMPTY_LIST else {
                        val owned = ArrayList<Any?>((value as? Collection<*>)?.size ?: 10)
                        for (entry in value) { add(1); owned.add(freeze(entry)) }
                        if (owned.isEmpty()) EMPTY_LIST else Collections.unmodifiableList(owned)
                    }
                }
                else -> value.toString().also { add(stringBound(it)) }
            }
        }
    }
}
