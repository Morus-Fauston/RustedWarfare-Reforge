package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.io.BufferedWriter
import java.io.StringWriter
import java.io.IOException
import kotlin.test.*

class EvidenceInitializationFailureTest {
    private fun failedManifest(demo: Boolean) {
        val root = Files.createTempDirectory("p004-init-failure-")
        val output = root.resolve("run")
        val original = System.getProperty("java.class.path")
        var closes = 0
        val secondary = IOException("close failure after manifest failure")
        val writer = object : BufferedWriter(StringWriter()) {
            override fun close() { closes++; super.close(); throw secondary }
        }
        val primary: InvalidPathException
        try {
            System.setProperty("java.class.path", "\u0000")
            primary = assertFailsWith<InvalidPathException> {
                if (demo) DemoEvidence(output) { writer } else JsonlEvidence(output, "failed-init", root) { writer }
            }
        } finally { System.setProperty("java.class.path", original) }
        assertEquals(1, closes)
        assertSame(secondary, primary.suppressed.single())
    }
    @Test fun sandbox_manifest_failure_closes_its_owned_writer() = failedManifest(false)
    @Test fun demo_manifest_failure_closes_its_owned_writer() = failedManifest(true)
}
