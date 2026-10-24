package com.rustedwarfare.reforge.client

import kotlin.test.*

class ClientFailureHandlingTest {
    @Test fun failed_diagnostic_and_cleanup_preserve_the_first_failure_and_attempt_the_entire_sequence() {
        val primary = IllegalStateException("original load failure")
        val diagnostic = IllegalStateException("diagnostic disk failure")
        val cleanup = IllegalStateException("cleanup failure")
        val attempts = mutableListOf<String>()
        val sink = ClientEvidence { kind, values ->
            assertEquals("load-error", kind)
            assertEquals(primary.toString(), values["message"])
            attempts += "diagnostic"
            throw diagnostic
        }
        val actual = assertFailsWith<IllegalStateException> {
            sink.recordFailure("load-error", primary)
            preserveFailure(primary) { attempts += "cleanup"; throw cleanup }
            preserveFailure(primary) { attempts += "last cleanup" }
            throw primary
        }
        assertSame(primary, actual)
        assertEquals(listOf("diagnostic", "cleanup", "last cleanup"), attempts)
        assertEquals(listOf(diagnostic, cleanup), primary.suppressed.toList())
    }

    @Test fun recoverable_load_reporting_returns_to_its_caller_after_attaching_a_secondary_failure() {
        val primary = IllegalArgumentException("recoverable input failure")
        val diagnostic = IllegalStateException("diagnostic failure")
        val sink = ClientEvidence { _, _ -> throw diagnostic }
        sink.recordFailure("load-error", primary)
        assertEquals(listOf(diagnostic), primary.suppressed.toList())
    }
}
