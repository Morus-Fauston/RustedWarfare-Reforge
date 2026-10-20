package com.rustedwarfare.reforge.pc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsLaunchOptionsTest {
    @Test fun we01_default_sandbox_uses_the_formal_p002_input_root() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val options = WindowsLaunchOptions.parse(emptyArray(), repository)
            assertEquals("sandbox", options.mode)
            assertEquals(repository.resolve("TestLab/local/P002-知识沙盒输入"), options.inputs)
            assertEquals(repository.resolve("Engine/pc/build/sandbox-runs"), options.evidence.parent)
            assertEquals("RustedWarfare-Reforge | P002 Windows sandbox", options.title)
            assertEquals(options.runId, options.evidence.fileName.toString())
            assertTrue(Regex("run-\\d{8}T\\d{9}Z-[a-f0-9]{8}").matches(options.runId))
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test fun we01_explicit_p001_and_evidence_paths_are_preserved() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val explicitInputs = repository.resolve("P001 inputs")
            val explicitEvidence = repository.resolve("review/run")
            val options = WindowsLaunchOptions.parse(arrayOf("--inputs", explicitInputs.toString(),
                "--evidence", explicitEvidence.toString()), repository)
            assertEquals(explicitInputs, options.inputs)
            assertEquals(explicitEvidence, options.evidence)
            val relative = WindowsLaunchOptions.parse(arrayOf("--inputs", "old/../P001 inputs"), repository)
            assertEquals(explicitInputs, relative.inputs)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test fun we01_demo_and_invalid_argument_rules_remain_explicit() {
        val repository = Files.createTempDirectory("reforge-windows-launch-test-")
        try {
            val demo = WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), repository)
            assertEquals("demo", demo.mode)
            assertNull(demo.inputs)
            assertEquals("RustedWarfare-Reforge | Field Demo", demo.title)
            assertEquals(repository.resolve("Engine/pc/build/demo-runs"), demo.evidence.parent)
            assertFailsWith<IllegalArgumentException> {
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--inputs", "anything"), repository)
            }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--inputs"), repository) }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--other", "x"), repository) }
            assertFailsWith<IllegalArgumentException> { WindowsLaunchOptions.parse(arrayOf("--mode", "other"), repository) }
        } finally {
            repository.toFile().deleteRecursively()
        }
    }
}
