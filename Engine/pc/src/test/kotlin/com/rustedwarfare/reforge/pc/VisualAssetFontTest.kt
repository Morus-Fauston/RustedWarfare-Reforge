package com.rustedwarfare.reforge.pc

import kotlin.test.Test
import kotlin.test.assertTrue

class VisualAssetFontTest {
    @Test fun platform_font_raster_is_self_contained_and_has_visible_directory_glyphs_without_GL() {
        val raster = PcVisualAssetFont.rasterize("A path:/?")
        val data = raster.data()
        for (character in "Apath:/?") {
            assertTrue(data.hasGlyph(character))
            val glyph = data.getGlyph(character)
            assertTrue(glyph.width > 0 && glyph.height > 0)
            assertTrue((glyph.srcY until glyph.srcY + glyph.height).any { y ->
                (glyph.srcX until glyph.srcX + glyph.width).any { x -> raster.image.getRGB(x, y) ushr 24 != 0 }
            })
        }
    }
}
