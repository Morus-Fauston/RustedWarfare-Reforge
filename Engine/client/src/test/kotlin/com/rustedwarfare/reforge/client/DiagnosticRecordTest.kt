package com.rustedwarfare.reforge.client

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DiagnosticRecordTest {
    private class Output : DiagnosticOutput {
        val rows = mutableListOf<Pair<Long, FixedDiagnosticRecord>>()
        var flushes = 0; var closes = 0
        var writeError: Throwable? = null; var flushError: Throwable? = null; var closeError: Throwable? = null
        override fun write(sequence: Long, record: FixedDiagnosticRecord) {
            writeError?.let { throw it }; rows.add(sequence to record)
        }
        override fun flush() { flushError?.let { throw it }; flushes++ }
        override fun close() { closes++; closeError?.let { throw it } }
    }
    private fun record() = FixedDiagnosticRecord.capture("sample", mapOf("n" to 1))

    @Test fun capture_detaches_nested_maps_lists_metadata_and_mutable_numbers() {
        val number = AtomicInteger(17)
        val nested = linkedMapOf<String, Any?>("text" to "before", "number" to number)
        val list = mutableListOf<Any?>(nested)
        val values = linkedMapOf<String, Any?>("items" to list)
        val metadata = linkedMapOf<String, Any?>("at" to "now")
        val fixed = FixedDiagnosticRecord.capture("sample", values, metadata)
        number.set(19); nested["text"] = "after"; list.clear(); values.clear(); metadata.clear()
        val saved = ((fixed.values.getValue("items") as List<*>).single() as Map<*, *>)
        assertEquals("before", saved["text"])
        assertEquals("17", saved["number"].toString())
        assertEquals("now", fixed.metadata["at"])
        assertFailsWith<UnsupportedOperationException> { (fixed.values as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (saved as MutableMap).clear() }
        assertTrue(fixed.byteUpperBound >= "before17now".toByteArray().size)
    }

    @Test fun synchronous_sequence_and_flush_are_completed_before_return() {
        val out = Output(); val sink = SynchronousDiagnosticWriter(out)
        assertEquals(0L, sink.record(record(), false))
        assertEquals(0, out.flushes)
        assertEquals(1L, sink.record(record(), true))
        assertEquals(1, out.flushes)
        sink.flush(); assertEquals(2, out.flushes)
        assertEquals(listOf(0L, 1L), out.rows.map { it.first })
        sink.close(); sink.close()
        assertEquals(1, out.closes)
        assertFailsWith<IllegalStateException> { sink.record(record(), false) }
    }

    @Test fun write_failure_stays_first_across_record_flush_close_and_secondary_cleanup() {
        val out = Output(); val sink = SynchronousDiagnosticWriter(out)
        val first = IOException("write failed"); val cleanup = IOException("close failed")
        out.writeError = first; out.closeError = cleanup
        assertSame(first, assertFailsWith<IOException> { sink.record(record(), false) })
        out.writeError = null
        assertSame(first, assertFailsWith<IOException> { sink.record(record(), true) })
        assertSame(first, assertFailsWith<IOException> { sink.flush() })
        assertSame(first, assertFailsWith<IOException> { sink.close() })
        assertSame(cleanup, first.suppressed.single())
        assertSame(first, assertFailsWith<IOException> { sink.close() })
        assertEquals(1, out.closes); assertTrue(out.rows.isEmpty())
    }

    @Test fun flush_failure_is_latched_and_cannot_be_replaced_by_a_later_close_failure() {
        val out = Output(); val sink = SynchronousDiagnosticWriter(out)
        val first = IOException("flush failed"); out.flushError = first; out.closeError = IOException("cleanup")
        assertSame(first, assertFailsWith<IOException> { sink.record(record(), true) })
        assertSame(first, assertFailsWith<IOException> { sink.close() })
        assertEquals(listOf(0L), out.rows.map { it.first })
    }

    @Test fun close_failure_is_reported_again_without_reclosing_the_output() {
        val out = Output(); val sink = SynchronousDiagnosticWriter(out)
        val first = IOException("close failed"); out.closeError = first
        assertSame(first, assertFailsWith<IOException> { sink.close() })
        assertSame(first, assertFailsWith<IOException> { sink.close() })
        assertSame(first, assertFailsWith<IOException> { sink.record(record(), false) })
        assertEquals(1, out.closes)
    }

    @Test fun disabled_sink_does_not_evaluate_payload_preparation() {
        ClientEvidence.NONE.recordWhenEnabled("sample") { error("disabled payload evaluated") }
    }

    @Test fun captured_maps_keep_key_identity_order_and_immutable_entries_and_iterators() {
        val a = object { override fun toString() = "same" }
        val b = object { override fun toString() = "same" }
        val source = linkedMapOf<Any?, Any?>(null to "null-key", 1 to "integer", "1" to "string", a to "a", b to "b")
        val fixed = FixedDiagnosticRecord.capture("sample", mapOf("nested" to source))
        source.clear()
        val nested = fixed.values["nested"] as Map<*, *>
        assertEquals(listOf("null", "1", "1", "same", "same"), nested.keys.map { it.toString() })
        assertEquals(listOf("null-key", "integer", "string", "a", "b"), nested.values.toList())
        assertEquals("integer", nested[1]); assertEquals("string", nested["1"])
        val iterator = nested.entries.iterator(); val entry = iterator.next()
        assertFailsWith<UnsupportedOperationException> { (entry as MutableMap.MutableEntry<Any?, Any?>).setValue("changed") }
        assertFailsWith<UnsupportedOperationException> { (iterator as MutableIterator).remove() }
        assertEquals("null-key", nested[null])
    }

    @Test fun resource_upper_bound_stays_the_same_for_fixed_scalar_and_nested_list_shape() {
        val fixed = FixedDiagnosticRecord.capture("sample", mapOf("number" to 1, "flags" to listOf(true, null)))
        assertEquals(1217L, fixed.byteUpperBound)
    }

    @Test fun known_immutable_singleton_with_immutable_value_can_be_reused() {
        val input = java.util.Collections.singletonMap<String, Any?>("text", "fixed")
        val fixed = FixedDiagnosticRecord.capture("sample", input, input)
        assertSame(input, fixed.values); assertSame(input, fixed.metadata)
        assertFailsWith<UnsupportedOperationException> { (fixed.values as MutableMap).clear() }
    }

    @Test fun singleton_container_with_a_mutable_child_is_still_detached() {
        val child = mutableListOf<Any?>("before")
        val input = java.util.Collections.singletonMap<String, Any?>("items", child)
        val fixed = FixedDiagnosticRecord.capture("sample", input)
        child.clear(); child.add("after")
        assertNotSame(input, fixed.values)
        assertEquals(listOf("before"), fixed.values["items"])
    }
}
