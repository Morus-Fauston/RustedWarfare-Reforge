package com.rustedwarfare.reforge.pc

import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import kotlin.test.*

class EvidenceStreamingTest {
    private class Tracking : StringWriter() {
        var flushes = 0; var closes = 0
        override fun flush() { flushes++ }
        override fun close() { closes++ }
    }

    @Test fun streaming_keeps_literal_json_bytes_and_does_not_own_flush_or_close() {
        val output = Tracking()
        val value = linkedMapOf("text" to "中文😀\n\"\\", "items" to listOf(null, true, Long.MIN_VALUE, 1.25f),
            "nested" to mapOf("flag" to false))
        writeEvidenceJson(value, output)
        assertEquals("{\"text\":\"中文😀\\n\\\"\\\\\",\"items\":[null,true,-9223372036854775808,1.25],\"nested\":{\"flag\":false}}", output.toString())
        assertEquals(0, output.flushes); assertEquals(0, output.closes)
        output.write("\n"); writeEvidenceJson(mapOf("next" to 2), output)
        assertTrue(output.toString().endsWith("\n{\"next\":2}"))
    }

    @Test fun output_failure_propagates_original_error_without_closing_the_caller_resource() {
        val first = IOException("stream write"); var closed = false
        val output = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) { throw first }
            override fun flush() = error("unexpected flush")
            override fun close() { closed = true }
        }
        assertSame(first, assertFailsWith<IOException> { writeEvidenceJson(mapOf("x" to 1), output) })
        assertFalse(closed)
    }
}
