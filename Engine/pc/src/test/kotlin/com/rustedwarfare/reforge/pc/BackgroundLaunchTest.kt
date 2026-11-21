package com.rustedwarfare.reforge.pc

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun background_sizes_and_touch_flag_are_validated_strictly() {
        val root = Path.of(".").toAbsolutePath().normalize()
        val hidden = arrayOf("--evidence", "run", "--background-input", "true")
        val fhd = WindowsLaunchOptions.parse(hidden + arrayOf("--background-size", "1920x1080"), root)
        assertFalse(fhd.backgroundCompact); assertEquals(1920, fhd.backgroundWidth); assertEquals(1080, fhd.backgroundHeight)
        val touchRes = WindowsLaunchOptions.parse(hidden + arrayOf("--background-size", "2400x1080", "--development", "true", "--touch", "true"), root)
        assertEquals(2400, touchRes.backgroundWidth); assertEquals(1080, touchRes.backgroundHeight); assertTrue(touchRes.touchSimulation)
        val uw = WindowsLaunchOptions.parse(hidden + arrayOf("--background-size", "2560x1080"), root)
        assertEquals(2560, uw.backgroundWidth); assertEquals(1080, uw.backgroundHeight); assertFalse(uw.touchSimulation)

        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(hidden + arrayOf("--background-size", "800x600"), root)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--touch", "true"), root)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--development", "true", "--touch", "false"), root)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--development", "true", "--touch", "true", "--touch", "true"), root)
        }
    }
}
