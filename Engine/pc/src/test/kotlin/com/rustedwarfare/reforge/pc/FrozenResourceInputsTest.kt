package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class FrozenResourceInputsTest {
    @Test fun a_full_memory_budget_still_accepts_inputs_and_rechecks_uncached_members() {
        val root = Files.createTempDirectory("sp017-bounded-input-").toAbsolutePath().normalize()
        try {
            val a = Files.write(root.resolve("a.bin"), byteArrayOf(1, 2, 3, 4))
            val b = Files.write(root.resolve("b.bin"), byteArrayOf(4, 3, 2, 1))
            val modified = Files.getLastModifiedTime(b)
            val inputs = PcFrozenResourceInputs.capture(listOf(root), retainedBytesLimit = 4)
            inputs.use {
                assertContentEquals(byteArrayOf(1, 2, 3, 4), it.read(a, 16))
                assertContentEquals(byteArrayOf(4, 3, 2, 1), it.read(b, 16))
                Files.write(b, byteArrayOf(1, 2, 3, 4)); Files.setLastModifiedTime(b, modified)
                assertFailsWith<IllegalArgumentException> { it.read(b, 16) }
                Files.write(b, byteArrayOf(4, 3, 2, 1)); Files.setLastModifiedTime(b, modified)
                Thread.currentThread().interrupt()
                try { assertFailsWith<java.util.concurrent.CancellationException> { it.read(a, 16) } }
                finally { Thread.interrupted() }
                it.verifyCurrent()
            }
            assertFailsWith<IllegalStateException> { inputs.read(a, 16) }
            Files.createDirectory(root.resolve("new-empty-directory"))
            assertFailsWith<IllegalArgumentException> { inputs.verifyCurrent() }
            PcFrozenResourceInputs.capture(listOf(root)).use { it.verifyCurrent() }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(owned)
            } }
        }
    }

    @Test fun one_audit_reuses_frozen_bytes_but_publication_detects_same_length_same_mtime_changes() {
        val root = Files.createTempDirectory("sp017-frozen-input-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("owned.bin")
            Files.write(file, byteArrayOf(1, 2, 3, 4))
            val modified = Files.getLastModifiedTime(file)
            PcFrozenResourceInputs.capture(listOf(root)).use { inputs ->
                val first = inputs.read(file, 16)
                assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", inputs.sha256(file))
                Files.write(file, byteArrayOf(4, 3, 2, 1))
                Files.setLastModifiedTime(file, modified)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), first)
                assertContentEquals(first, inputs.read(file, 16), "Pure audit consumers share the first checked bytes")
                val failure = assertFails { inputs.verifyCurrent() }
                assertTrue(failure.message.orEmpty().contains("RESOURCE_PACK_SOURCE_CHANGED"))
                Files.write(file, byteArrayOf(1, 2, 3, 4))
                Files.setLastModifiedTime(file, modified)
                inputs.verifyCurrent()
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { owned ->
                require(owned.toAbsolutePath().normalize().startsWith(root))
                Files.deleteIfExists(owned)
            } }
        }
    }
}
