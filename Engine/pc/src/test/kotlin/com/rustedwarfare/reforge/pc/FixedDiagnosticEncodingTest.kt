package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.FixedDiagnosticRecord
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class FixedDiagnosticEncodingTest {
    @Test fun frozen_values_keep_existing_json_spelling_and_conservative_utf8_bounds() {
        for (value in listOf<Any?>(null, true, false, Long.MIN_VALUE, Long.MAX_VALUE,
            Double.MIN_VALUE, Double.MAX_VALUE, -0.0, 1.25f, BigDecimal("1.2300"),
            "中文😀\u0000\n\r\t\"\\".repeat(200), listOf(null, 1, "nested"))) {
            val input = linkedMapOf<String, Any?>("payload" to value, "other" to mapOf("value" to value))
            val expected = evidenceJson(input)
            val record = FixedDiagnosticRecord.capture("sample", input, mapOf("run" to "r"))
            val actual = evidenceJson(record.values)
            assertEquals(expected, actual)
            assertTrue(actual.toByteArray(Charsets.UTF_8).size.toLong() <= record.byteUpperBound)
        }
    }

    @Test fun mutable_numeric_input_is_encoded_from_capture_time() {
        val input = AtomicLong(17)
        val record = FixedDiagnosticRecord.capture("sample", mapOf("value" to input))
        input.set(99)
        assertEquals("{\"value\":17}", evidenceJson(record.values))
    }
}
