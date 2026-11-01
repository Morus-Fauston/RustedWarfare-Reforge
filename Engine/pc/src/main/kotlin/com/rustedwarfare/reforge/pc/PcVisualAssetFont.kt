package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import kotlin.math.ceil

/** Uses the local platform's logical font in memory; no font file is bundled or written. Desktop only. */
internal object PcVisualAssetFont {
    class Raster(val image: BufferedImage, private val descriptor: String) {
        fun data(): BitmapFont.BitmapFontData = BitmapFont.BitmapFontData(object : FileHandle("runtime-visual-font.fnt") {
            override fun read() = ByteArrayInputStream(descriptor.toByteArray(Charsets.UTF_8))
        }, false).also { it.missingGlyph = it.getGlyph('?') }
    }

    fun rasterize(characters: String): Raster {
        val chars = ((32..126).map(Int::toChar) + characters.filter { it >= ' ' && !it.isSurrogate() }.toList()).distinct().sorted()
        val font = Font(Font.DIALOG, Font.PLAIN, 16)
        val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val metrics = measure.createGraphics().let { graphics ->
            try { graphics.getFontMetrics(font) } finally { graphics.dispose(); measure.flush() }
        }
        val cellWidth = metrics.maxAdvance.coerceAtLeast(16) + 6
        val cellHeight = metrics.height + 6
        val columns = (1024 / cellWidth).coerceAtLeast(1)
        val width = columns * cellWidth
        val height = ceil(chars.size.toDouble() / columns).toInt() * cellHeight
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        val descriptor = StringBuilder("info face=\"Platform UI\" size=16 padding=0,0,0,0 spacing=0,0\n")
            .append("common lineHeight=${metrics.height} base=${metrics.ascent} scaleW=$width scaleH=$height pages=1 packed=0\n")
            .append("page id=0 file=\"runtime-visual-font.png\"\nchars count=${chars.size}\n")
        try {
            graphics.font = font; graphics.color = Color.WHITE
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            for ((index, char) in chars.withIndex()) {
                val x = index % columns * cellWidth + 3
                val y = index / columns * cellHeight + 3
                val bounds = font.createGlyphVector(graphics.fontRenderContext, char.toString())
                    .getPixelBounds(graphics.fontRenderContext, 0f, 0f)
                graphics.drawString(char.toString(), x, y + metrics.ascent)
                descriptor.append("char id=${char.code} x=${x + bounds.x} y=${y + metrics.ascent + bounds.y} " +
                    "width=${bounds.width} height=${bounds.height} xoffset=${bounds.x} yoffset=${metrics.ascent + bounds.y} " +
                    "xadvance=${metrics.charWidth(char)} page=0 chnl=15\n")
            }
        } finally { graphics.dispose() }
        return Raster(image, descriptor.toString())
    }

    /** Render thread only: the font owns the resulting GL texture. */
    fun create(characters: String): BitmapFont {
        val raster = rasterize(characters)
        val pixels = Pixmap(raster.image.width, raster.image.height, Pixmap.Format.RGBA8888)
        try {
            pixels.blending = Pixmap.Blending.None
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val argb = raster.image.getRGB(x, y)
                pixels.drawPixel(x, y, (argb shl 8) or (argb ushr 24))
            }
            val texture = Texture(pixels)
            try { return BitmapFont(raster.data(), TextureRegion(texture), true).also { it.setOwnsTexture(true) } }
            catch (failure: Exception) { texture.dispose(); throw failure }
        } finally { pixels.dispose(); raster.image.flush() }
    }
}
