package com.rustedwarfare.reforge.pc

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PcCatalogSceneOptionTest {
    @Test
    fun launch_options_accept_valid_catalog_scene_and_reject_unknown_or_conflicting_map() {
        val repo = Path.of("").toAbsolutePath().normalize()
        val parsed = WindowsLaunchOptions.parse(arrayOf("--scene", "rocks-small-100"), repo)
        assertEquals("rocks-small-100", parsed.catalogSceneId)

        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--scene", "does-not-exist"), repo)
        }
        assertFailsWith<IllegalArgumentException> {
            WindowsLaunchOptions.parse(arrayOf("--scene", "rocks-small-100", "--map", "foo.tmx"), repo)
        }
    }
}
