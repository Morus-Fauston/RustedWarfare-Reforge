package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.utils.GdxNativesLoader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Supersampled vector stencil rasterizer & drawable for `Docs/规格/八期界面演示稿/RWRE-logo.svg`
 * (`viewBox="0 0 404 118"` — Ordnance Stencil / 军械喷涂蓝图).
 *
 * Strictly binds all four visual layers to [UiThemeOutGameColors]:
 * - `logoAccent`: Left `8×110` vertical grade stripe + `E` middle bar;
 * - `logoFrame`: Four corner `2px` reticle brackets;
 * - `logoMain`: Stencil `R-W-R-E` polygons with parallel `5.5px` stencil bridge cuts on `W`;
 * - `logoTicks`: Bottom `y=112` precision tick line (`dasharray="1 7"`, `stroke-width="3"`).
 */
object RwreLogoVectorSpec {
    init {
        runCatching { GdxNativesLoader.load() }
    }

    const val VIEW_WIDTH: Float = 404f
    const val VIEW_HEIGHT: Float = 118f
    const val ASPECT_RATIO: Float = VIEW_WIDTH / VIEW_HEIGHT

    private data class Point(val x: Float, val y: Float)
    private data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val halfWidth: Float)

    // Glyph transform: translate(28, 13) scale(0.94, 0.88)
    private const val GX = 28f
    private const val GY = 13f
    private const val GSX = 0.94f
    private const val GSY = 0.88f

    private fun gp(localX: Float, localY: Float, offsetX: Float = 0f): Point =
        Point(GX + (localX + offsetX) * GSX, GY + localY * GSY)

    private fun rectPoly(x: Float, y: Float, w: Float, h: Float, offsetX: Float = 0f): List<Point> = listOf(
        gp(x, y, offsetX),
        gp(x + w, y, offsetX),
        gp(x + w, y + h, offsetX),
        gp(x, y + h, offsetX),
    )

    private fun rPolys(offsetX: Float): List<List<Point>> = listOf(
        rectPoly(0f, 0f, 20f, 100f, offsetX),
        listOf(
            gp(25f, 0f, offsetX), gp(60f, 0f, offsetX), gp(76f, 16f, offsetX), gp(76f, 42f, offsetX),
            gp(62f, 56f, offsetX), gp(25f, 56f, offsetX), gp(25f, 39f, offsetX), gp(51f, 39f, offsetX),
            gp(56f, 34f, offsetX), gp(56f, 23f, offsetX), gp(51f, 18f, offsetX), gp(25f, 18f, offsetX),
        ),
        listOf(
            gp(43f, 61f, offsetX), gp(64f, 61f, offsetX), gp(79f, 100f, offsetX), gp(57f, 100f, offsetX),
        ),
    )

    private val wPoly: List<Point> = listOf(
        gp(0f, 0f, 88f), gp(21f, 0f, 88f), gp(32f, 65f, 88f), gp(45f, 18f, 88f),
        gp(63f, 18f, 88f), gp(76f, 65f, 88f), gp(87f, 0f, 88f), gp(108f, 0f, 88f),
        gp(92f, 100f, 88f), gp(69f, 100f, 88f), gp(54f, 50f, 88f), gp(39f, 100f, 88f),
        gp(16f, 100f, 88f),
    )

    // Parallel 5.5px stencil bridge cuts inside W local coordinates (transformed to viewBox space)
    // `<line x1="29.5" y1="54" x2="38.5" y2="104" stroke="#000" stroke-width="5.5"/>`
    // `<line x1="78.5" y1="54" x2="69.5" y2="104" stroke="#000" stroke-width="5.5"/>`
    private data class LocalCut(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val halfWidth: Float)
    private val wLocalCuts = listOf(
        LocalCut(29.5f, 54f, 38.5f, 104f, 2.75f),
        LocalCut(78.5f, 54f, 69.5f, 104f, 2.75f),
    )

    private val mainPolysWithoutW: List<List<Point>> = buildList {
        addAll(rPolys(0f))
        addAll(rPolys(208f))
        // E main stem, top bar, bottom bar (offsetX = 298)
        add(rectPoly(0f, 0f, 20f, 100f, 298f))
        add(listOf(gp(25f, 0f, 298f), gp(72f, 0f, 298f), gp(66f, 19f, 298f), gp(25f, 19f, 298f)))
        add(rectPoly(25f, 81f, 47f, 19f, 298f))
    }

    private val eAccentPoly: List<Point> = listOf(
        gp(25f, 41f, 298f), gp(64f, 41f, 298f), gp(58f, 59f, 298f), gp(25f, 59f, 298f),
    )

    // Corner reticle stroke segments (`M16,18 V4 H32 M388,4 H402 V18 M16,100 V114 H32 M388,114 H402 V100`, stroke-width=2)
    private val frameRects: List<FloatArray> = listOf(
        // Top-left
        floatArrayOf(15f, 3f, 2f, 15f),
        floatArrayOf(15f, 3f, 17f, 2f),
        // Top-right
        floatArrayOf(388f, 3f, 15f, 2f),
        floatArrayOf(401f, 3f, 2f, 15f),
        // Bottom-left
        floatArrayOf(15f, 100f, 2f, 15f),
        floatArrayOf(15f, 113f, 17f, 2f),
        // Bottom-right
        floatArrayOf(388f, 113f, 15f, 2f),
        floatArrayOf(401f, 100f, 2f, 15f),
    )

    private fun pointInPolygon(px: Float, py: Float, poly: List<Point>): Boolean {
        var inside = false
        var j = poly.lastIndex
        for (i in poly.indices) {
            val xi = poly[i].x
            val yi = poly[i].y
            val xj = poly[j].x
            val yj = poly[j].y
            val intersect = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / ((yj - yi).takeIf { abs(it) > 1e-6f } ?: 1e-6f) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun inCutLine(viewX: Float, viewY: Float, cut: LocalCut): Boolean {
        // Inverse-map view coordinates into W local coordinates (translate(88,0) inside translate(28,13) scale(0.94,0.88))
        val lx = (viewX - GX) / GSX - 88f
        val ly = (viewY - GY) / GSY
        val vx = cut.x2 - cut.x1
        val vy = cut.y2 - cut.y1
        val wx = lx - cut.x1
        val wy = ly - cut.y1
        val lenSq = vx * vx + vy * vy
        val t = ((wx * vx + wy * vy) / lenSq).coerceIn(0f, 1f)
        val dx = wx - t * vx
        val dy = wy - t * vy
        return (dx * dx + dy * dy) <= cut.halfWidth * cut.halfWidth
    }

    private fun sampleLayerAt(viewX: Float, viewY: Float): Int {
        // Layer codes: 0 = transparent, 1 = logoAccent, 2 = logoFrame, 3 = logoMain, 4 = logoTicks
        // 1. Left vertical accent bar: <rect x="0" y="4" width="8" height="110"/>
        if (viewX in 0f..8f && viewY in 4f..114f) return 1

        // 2. E middle accent bar
        if (pointInPolygon(viewX, viewY, eAccentPoly)) return 1

        // 3. W polygon minus parallel stencil bridge cuts
        if (pointInPolygon(viewX, viewY, wPoly)) {
            val maskedOut = wLocalCuts.any { cut -> inCutLine(viewX, viewY, cut) }
            if (!maskedOut) return 3
        }

        // 4. R, R, E main polygons
        for (poly in mainPolysWithoutW) {
            if (pointInPolygon(viewX, viewY, poly)) return 3
        }

        // 5. Corner reticle brackets
        for (r in frameRects) {
            if (viewX >= r[0] && viewX <= r[0] + r[2] && viewY >= r[1] && viewY <= r[1] + r[3]) return 2
        }

        // 6. Bottom dashed tick line: x1=28, y1=112, x2=386, y2=112, stroke-width=3, dasharray="1 7"
        if (viewX in 28f..386f && viewY in 110.5f..113.5f) {
            val offset = viewX - 28f
            val phase = offset - floor(offset / 8f) * 8f
            if (phase <= 1.05f) return 4
        }

        return 0
    }

    /**
     * Pure Kotlin supersampled (`4x4` subpixel) rasterizer producing a row-major `RGBA8888` [IntArray].
     * Zero native JNI dependency — runs identically in `:client:test` headless JVM, PC, and Android.
     */
    fun renderToRgbaBuffer(pixelWidth: Int, pixelHeight: Int, theme: UiTheme): IntArray {
        val w = pixelWidth.coerceIn(64, 2048)
        val h = pixelHeight.coerceIn(24, 1024)
        val buffer = IntArray(w * h)

        val colors = arrayOf(
            Color(0f, 0f, 0f, 0f),
            theme.outGame.logoAccent,
            theme.outGame.logoFrame,
            theme.outGame.logoMain,
            theme.outGame.logoTicks,
        )

        val sub = 4
        val invSub = 1f / sub
        val invSamples = 1f / (sub * sub)

        for (py in 0 until h) {
            val rowOffset = py * w
            for (px in 0 until w) {
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumA = 0f
                for (sy in 0 until sub) {
                    val vy = ((py + (sy + 0.5f) * invSub) / h.toFloat()) * VIEW_HEIGHT
                    for (sx in 0 until sub) {
                        val vx = ((px + (sx + 0.5f) * invSub) / w.toFloat()) * VIEW_WIDTH
                        val code = sampleLayerAt(vx, vy)
                        if (code != 0) {
                            val c = colors[code]
                            val ca = c.a
                            sumR += c.r * ca
                            sumG += c.g * ca
                            sumB += c.b * ca
                            sumA += ca
                        }
                    }
                }
                if (sumA > 1e-4f) {
                    val outA = (sumA * invSamples).coerceIn(0f, 1f)
                    val outR = (sumR / sumA).coerceIn(0f, 1f)
                    val outG = (sumG / sumA).coerceIn(0f, 1f)
                    val outB = (sumB / sumA).coerceIn(0f, 1f)
                    buffer[rowOffset + px] = Color.rgba8888(outR, outG, outB, outA)
                }
            }
        }
        return buffer
    }

    /**
     * Copies [renderToRgbaBuffer] into a LibGDX [Pixmap] on hosts with loaded GDX native libraries.
     */
    fun renderToPixmap(pixelWidth: Int, pixelHeight: Int, theme: UiTheme): Pixmap {
        val w = pixelWidth.coerceIn(64, 2048)
        val h = pixelHeight.coerceIn(24, 1024)
        val rgba = renderToRgbaBuffer(w, h, theme)
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        for (py in 0 until h) {
            val rowOffset = py * w
            for (px in 0 until w) {
                val col = rgba[rowOffset + px]
                if (col != 0) pm.drawPixel(px, py, col)
            }
        }
        return pm
    }
}

/**
 * Scene2D [BaseDrawable] that caches a crisp supersampled GPU [Texture] of the `RWRE` vector stencil logo
 * and automatically refreshes when [UiTheme] colors or target pixel dimensions change.
 */
class RwreLogoDrawable(
    private val themeProvider: UiThemeProvider,
    private val pixelScaleProvider: () -> Float = { 1f },
) : BaseDrawable(), AutoCloseable {

    private data class CacheKey(
        val pixelW: Int,
        val pixelH: Int,
        val accentBits: Int,
        val frameBits: Int,
        val mainBits: Int,
        val ticksBits: Int,
    )

    private var cachedKey: CacheKey? = null
    private var cachedTexture: Texture? = null

    init {
        minWidth = 360f
        minHeight = 360f / RwreLogoVectorSpec.ASPECT_RATIO
    }

    override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
        if (width <= 1f || height <= 1f) return
        val theme = themeProvider.currentTheme()
        val scale = pixelScaleProvider().coerceIn(0.75f, 3f)
        val targetPxW = (width * scale).roundToInt().coerceIn(180, 1440)
        val targetPxH = (height * scale).roundToInt().coerceIn(52, 420)
        val key = CacheKey(
            pixelW = targetPxW,
            pixelH = targetPxH,
            accentBits = Color.rgba8888(theme.outGame.logoAccent),
            frameBits = Color.rgba8888(theme.outGame.logoFrame),
            mainBits = Color.rgba8888(theme.outGame.logoMain),
            ticksBits = Color.rgba8888(theme.outGame.logoTicks),
        )
        if (cachedKey != key || cachedTexture == null) {
            cachedTexture?.dispose()
            val pm = RwreLogoVectorSpec.renderToPixmap(targetPxW, targetPxH, theme)
            cachedTexture = try {
                Texture(pm).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
            } finally {
                pm.dispose()
            }
            cachedKey = key
        }
        val tex = cachedTexture ?: return
        val prevColor = batch.packedColor
        val incomingAlpha = batch.color.a
        // Subtle theme-bound drop shadow for legibility over panning map backdrop
        val shadow = theme.outGame.panelShadow
        if (shadow.a > 0.05f) {
            batch.setColor(shadow.r, shadow.g, shadow.b, (shadow.a * 0.55f).coerceAtMost(0.65f) * incomingAlpha)
            batch.draw(tex, x + 2f, y - 4f, width, height)
        }
        batch.setColor(1f, 1f, 1f, incomingAlpha)
        batch.draw(tex, x, y, width, height)
        batch.packedColor = prevColor
    }

    override fun close() {
        cachedTexture?.dispose()
        cachedTexture = null
        cachedKey = null
    }
}

/**
 * Frameless Scene2D [Actor] hosting [RwreLogoDrawable] above the Main Menu item stack.
 */
class RwreLogoActor(
    themeProvider: UiThemeProvider,
    pixelScaleProvider: () -> Float = { 1f },
) : Actor(), AutoCloseable {
    val drawable = RwreLogoDrawable(themeProvider, pixelScaleProvider)

    init {
        touchable = Touchable.disabled
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        drawable.draw(batch, x, y, width, height)
        batch.packedColor = saved
    }

    override fun close() {
        drawable.close()
    }
}
