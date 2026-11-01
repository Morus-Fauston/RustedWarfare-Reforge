package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualAssetPlaceholderTest {
    @Test fun bundled_self_owned_visuals_cover_all_bindings_without_an_original_directory() {
        val root = Files.createTempDirectory("placeholder-absent-original-")
        try {
            val selection = VisualAssetSelection(VisualAssetSource.PLACEHOLDER, root.resolve("原版 missing"))
            val prepared = PcPlaceholderVisualAssets().prepare(selection)
            assertEquals(selection, prepared.selection)
            assertEquals(16, prepared.units.size)
            assertEquals(5, prepared.terrain.size)
            assertEquals(3, prepared.units.getValue("c_tank").totalFrames)
            assertEquals(1, prepared.units.getValue("demo_tank").totalFrames)
            assertTrue(Files.notExists(selection.originalDirectory))
            assertEquals(60, ImageIO.read(prepared.units.getValue("c_tank").body.toFile()).width)
            assertEquals(100, ImageIO.read(prepared.terrain.getValue("construction-strip-5").toFile()).width)
            prepared.verify()
        } finally { root.toFile().deleteRecursively() }
    }
}
