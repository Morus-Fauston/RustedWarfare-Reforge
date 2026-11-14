package com.rustedwarfare.reforge.pc

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundLaunchTest {
    @Test fun hidden_sandbox_input_is_an_explicit_launch_option() {
        val root = Path.of("").toAbsolutePath()
        assertFalse(WindowsLaunchOptions.parse(emptyArray(), root).backgroundInput)
        assertTrue(WindowsLaunchOptions.parse(arrayOf("--evidence", "run", "--background-input", "true"), root).backgroundInput)
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--background-input", "true"), root)
        }
        val hidden = arrayOf("--evidence", "run", "--background-input", "true")
        assertFalse(WindowsLaunchOptions.parse(hidden, root).backgroundCompact)
        assertTrue(WindowsLaunchOptions.parse(hidden + arrayOf("--background-size", "960x640"), root).backgroundCompact)
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--background-size", "960x640"), root)
        }
    }
}
