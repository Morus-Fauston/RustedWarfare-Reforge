package com.rustedwarfare.reforge.testing.perf

import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.*

class FailurePreservationTest {
    @Test fun original_failure_and_each_failed_preservation_remain_visible_without_overwriting_partial_output() {
        val root = Files.createTempDirectory("p004-preserve-")
        val existing = root.resolve("failure.txt")
        Files.writeString(existing, "old evidence")
        val primary = IllegalArgumentException("first input failure")
        preserveFailure(primary) { Files.writeString(existing, "overwrite", CREATE_NEW) }
        preserveFailure(primary) { Files.writeString(root.resolve("missing/receipt"), "partial", CREATE_NEW) }
        preserveFailure(primary) { Files.writeString(root.resolve("partial.txt"), "partial", CREATE_NEW) }
        assertEquals("first input failure", primary.message)
        assertEquals(2, primary.suppressed.size)
        assertIs<java.nio.file.FileAlreadyExistsException>(primary.suppressed[0])
        assertIs<java.nio.file.NoSuchFileException>(primary.suppressed[1])
        assertEquals("old evidence", Files.readString(existing))
        assertEquals("partial", Files.readString(root.resolve("partial.txt")))
        assertTrue(primary.stackTraceToString().contains("Suppressed"))
    }
}
