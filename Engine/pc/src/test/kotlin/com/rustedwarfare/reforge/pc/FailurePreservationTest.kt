package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.*

class FailurePreservationTest {
    @Test fun a_secondary_diagnostic_failure_cannot_replace_the_original_process_failure() {
        val directory = Files.createTempDirectory("p004-pc-failure-")
        val path = directory.resolve("existing.jsonl")
        Files.writeString(path, "partial\n")
        val first = IllegalStateException("original process failure")
        preserveFailure(first) { Files.writeString(path, "overwrite", CREATE_NEW) }
        assertEquals("original process failure", first.message)
        assertIs<java.nio.file.FileAlreadyExistsException>(first.suppressed.single())
        assertEquals("partial\n", Files.readString(path))
    }
}
