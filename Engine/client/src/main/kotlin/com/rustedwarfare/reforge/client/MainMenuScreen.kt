package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.PixmapPacker
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.GdxNativesLoader
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU FreeType rasterizer for the default Dark theme's selected main-menu item diagonal-hatched prism extrusion
 * (`Docs/规格/八期界面演示稿/八期界面演示稿-主菜单字效版.html` lines 2065–2105, UI Supplement §3).
 *
 * Strictly gated on `theme.features.mainMenuExtrudeText == true` (enabled ONLY on `dark`, disabled on `white` & `bone`).
 * Uses LibGDX `FreeTypeFontGenerator` + `PixmapPacker` (zero `java.awt` dependency — 100% cross-platform PC & Android).
 */
object DarkMenuExtrudeRenderer {
    init {
        runCatching { GdxNativesLoader.load() }
    }

    fun isEnabledForTheme(theme: UiTheme): Boolean = theme.features.mainMenuExtrudeText

    data class RenderedExtrudePixmap(
        val pixmap: Pixmap,
        val marginPx: Int,
        val faceWidthPx: Int,
        val faceHeightPx: Int,
    )

    /**
     * Renders [text] with shallow bottom-right prism extrusion (`dx=+6.0, dy=+4.6` logical),
     * `-52°` diagonal hatch fill, outer accent rim, dark stroke separator, and crisp `boneHi` face.
     */
    fun renderToPixmap(
        text: String,
        scale: Float,
        theme: UiTheme,
        boldFontFile: FileHandle,
        refFontSize: Int = 35,
    ): RenderedExtrudePixmap {
        val safeScale = scale.coerceIn(0.65f, 3.0f)
        val pxSize = (refFontSize * safeScale).roundToInt().coerceIn(18, 120)
        val letterSpacingPx = (2.4f * safeScale).roundToInt().coerceAtLeast(1)

        val uniqueChars = buildString {
            val seen = LinkedHashSet<Char>()
            for (c in text) if (!c.isISOControl() && seen.add(c)) append(c)
            if (isEmpty()) append(' ')
        }

        val packer = PixmapPacker(1024, 256, Pixmap.Format.RGBA8888, 2, false)
        val glyphMaskWidth: Int
        val glyphMaskHeight: Int
        val marginPx = ceil(11f * safeScale).toInt().coerceAtLeast(8)
        val extrudeDx = 6.0f * safeScale
        val extrudeDy = 4.6f * safeScale
        val canvasW: Int
        val canvasH: Int
        val baseAlpha: FloatArray

        try {
            val generator = FreeTypeFontGenerator(boldFontFile)
            try {
                val param = FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                    size = pxSize
                    characters = uniqueChars
                    incremental = false
                    kerning = true
                    this.packer = packer
                    hinting = FreeTypeFontGenerator.Hinting.AutoMedium
                }
                val data = generator.generateData(param)
                try {
                    data class PlacedGlyph(val glyph: BitmapFont.Glyph, val penX: Int, val rawTop: Int)
                    val placed = mutableListOf<PlacedGlyph>()
                    var penX = 0
                    var minRawTop = Int.MAX_VALUE
                    var maxRawBottom = Int.MIN_VALUE
                    var maxRight = 0
                    var prevGlyph: BitmapFont.Glyph? = null

                    for (ch in text) {
                        val g = data.getGlyph(ch) ?: continue
                        if (prevGlyph != null) {
                            penX += prevGlyph.getKerning(ch)
                        }
                        if (g.width > 0 && g.height > 0) {
                            val rawTop = -g.yoffset - g.height
                            val rawBottom = rawTop + g.height
                            minRawTop = min(minRawTop, rawTop)
                            maxRawBottom = max(maxRawBottom, rawBottom)
                            maxRight = max(maxRight, penX + g.xoffset + g.width)
                            placed += PlacedGlyph(g, penX, rawTop)
                        }
                        penX += g.xadvance + letterSpacingPx
                        prevGlyph = g
                    }

                    if (placed.isEmpty()) {
                        val empty = Pixmap(16, 16, Pixmap.Format.RGBA8888)
                        return RenderedExtrudePixmap(empty, marginPx, 1, 1)
                    }

                    glyphMaskWidth = max(1, maxRight)
                    glyphMaskHeight = max(1, maxRawBottom - minRawTop)
                    val extraRight = ceil(extrudeDx + 6f * safeScale).toInt()
                    val extraBottom = ceil(extrudeDy + 6f * safeScale).toInt()
                    canvasW = glyphMaskWidth + marginPx * 2 + extraRight
                    canvasH = glyphMaskHeight + marginPx * 2 + extraBottom
                    baseAlpha = FloatArray(canvasW * canvasH)

                    val pages = packer.pages
                    for (item in placed) {
                        val g = item.glyph
                        val pagePm = pages.get(g.page).pixmap
                        val dstStartX = marginPx + (item.penX + g.xoffset)
                        val dstStartY = marginPx + (item.rawTop - minRawTop)
                        for (gy in 0 until g.height) {
                            val dy = dstStartY + gy
                            if (dy !in 0 until canvasH) continue
                            for (gx in 0 until g.width) {
                                val dx = dstStartX + gx
                                if (dx !in 0 until canvasW) continue
                                val pixel: Int = pagePm.getPixel(g.srcX + gx, g.srcY + gy)
                                val a: Float = (pixel and 0xFF).toFloat() / 255f
                                if (a > baseAlpha[dy * canvasW + dx]) {
                                    baseAlpha[dy * canvasW + dx] = a
                                }
                            }
                        }
                    }
                } finally {
                    data.dispose()
                }
            } finally {
                generator.dispose()
            }
        } finally {
            packer.dispose()
        }

        val rgbaBuffer = composeExtrudeRgbaBuffer(baseAlpha, canvasW, canvasH, safeScale, theme)
        val outPm = Pixmap(canvasW, canvasH, Pixmap.Format.RGBA8888)
        outPm.blending = Pixmap.Blending.None
        for (y in 0 until canvasH) {
            val rowOff = y * canvasW
            for (x in 0 until canvasW) {
                val col = rgbaBuffer[rowOff + x]
                if (col != 0) outPm.drawPixel(x, y, col)
            }
        }

        return RenderedExtrudePixmap(
            pixmap = outPm,
            marginPx = marginPx,
            faceWidthPx = glyphMaskWidth,
            faceHeightPx = glyphMaskHeight,
        )
    }

    /**
     * Pure Kotlin diagonal-hatched prism extrusion compositor (zero JNI dependency for headless `:client:test`).
     */
    fun composeExtrudeRgbaBuffer(
        baseAlpha: FloatArray,
        canvasW: Int,
        canvasH: Int,
        scale: Float,
        theme: UiTheme,
    ): IntArray {
        val safeScale = scale.coerceIn(0.65f, 3.0f)
        val extrudeDx = 6.0f * safeScale
        val extrudeDy = 4.6f * safeScale

        fun sampleBilinear(buf: FloatArray, fx: Float, fy: Float): Float {
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = fx - x0
            val ty = fy - y0
            fun at(ix: Int, iy: Int): Float =
                if (ix in 0 until canvasW && iy in 0 until canvasH) buf[iy * canvasW + ix] else 0f
            val v00 = at(x0, y0)
            val v10 = at(x0 + 1, y0)
            val v01 = at(x0, y0 + 1)
            val v11 = at(x0 + 1, y0 + 1)
            return v00 * (1f - tx) * (1f - ty) +
                v10 * tx * (1f - ty) +
                v01 * (1f - tx) * ty +
                v11 * tx * ty
        }

        fun dilateCircular(src: FloatArray, radius: Float): FloatArray {
            if (radius <= 0.05f) return src.copyOf()
            val out = FloatArray(canvasW * canvasH)
            val rCeil = ceil(radius + 1f).toInt()
            for (y in 0 until canvasH) {
                val minY = max(0, y - rCeil)
                val maxY = min(canvasH - 1, y + rCeil)
                for (x in 0 until canvasW) {
                    var best = src[y * canvasW + x]
                    if (best >= 0.999f) {
                        out[y * canvasW + x] = 1f
                        continue
                    }
                    val minX = max(0, x - rCeil)
                    val maxX = min(canvasW - 1, x + rCeil)
                    for (ny in minY..maxY) {
                        val dy = (ny - y).toFloat()
                        for (nx in minX..maxX) {
                            val sa = src[ny * canvasW + nx]
                            if (sa <= 0.01f) continue
                            val dist = sqrt(((nx - x) * (nx - x)).toFloat() + dy * dy)
                            val cov = (radius + 0.5f - dist).coerceIn(0f, 1f) * sa
                            if (cov > best) best = cov
                        }
                    }
                    out[y * canvasW + x] = best
                }
            }
            return out
        }

        val extrudeUnion = FloatArray(canvasW * canvasH)
        val steps = 16
        for (i in 1..steps) {
            val t = 0.05f + 0.95f * (i.toFloat() / steps.toFloat())
            val sx = extrudeDx * t
            val sy = extrudeDy * t
            for (y in 0 until canvasH) {
                for (x in 0 until canvasW) {
                    val v = sampleBilinear(baseAlpha, x - sx, y - sy)
                    if (v > extrudeUnion[y * canvasW + x]) {
                        extrudeUnion[y * canvasW + x] = v
                    }
                }
            }
        }

        val frontCutout = dilateCircular(baseAlpha, 1.35f * safeScale)
        val frontStroke = dilateCircular(baseAlpha, 1.45f * safeScale)
        val extrudeOuter = dilateCircular(extrudeUnion, 0.95f * safeScale)

        val outRgba = IntArray(canvasW * canvasH)
        val accent = theme.outGame.menuExtrudeAccent
        val strokeCol = theme.outGame.menuExtrudeStroke
        val faceCol = theme.colors.boneHi

        val angleRad = (52.0 * PI / 180.0).toFloat()
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val period = (5.0f * safeScale).coerceAtLeast(3.0f)
        val halfLine = (0.74f * safeScale).coerceAtLeast(0.55f)

        fun blendOver(dstR: Float, dstG: Float, dstB: Float, dstA: Float, srcCol: Color, srcAlpha: Float): FloatArray {
            val sa = (srcCol.a * srcAlpha).coerceIn(0f, 1f)
            if (sa <= 1e-4f) return floatArrayOf(dstR, dstG, dstB, dstA)
            val outA = sa + dstA * (1f - sa)
            if (outA <= 1e-5f) return floatArrayOf(0f, 0f, 0f, 0f)
            val outR = (srcCol.r * sa + dstR * dstA * (1f - sa)) / outA
            val outG = (srcCol.g * sa + dstG * dstA * (1f - sa)) / outA
            val outB = (srcCol.b * sa + dstB * dstA * (1f - sa)) / outA
            return floatArrayOf(outR, outG, outB, outA)
        }

        for (y in 0 until canvasH) {
            for (x in 0 until canvasW) {
                val idx = y * canvasW + x
                val faceA = baseAlpha[idx]
                val fStrokeA = frontStroke[idx]
                val cutoutA = frontCutout[idx]
                val extInA = (extrudeUnion[idx] * (1f - cutoutA)).coerceIn(0f, 1f)
                val extRingA = (extrudeOuter[idx] * (1f - extrudeUnion[idx]) * (1f - cutoutA)).coerceIn(0f, 1f)

                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f

                if (extInA > 1e-3f) {
                    val wash = blendOver(r, g, b, a, accent, 0.14f * extInA)
                    r = wash[0]; g = wash[1]; b = wash[2]; a = wash[3]

                    // SVG `patternTransform="rotate(-52)"` vertical line normal in top-down Pixmap coords
                    val rotX = x * cosA - y * sinA
                    val mod = rotX - floor(rotX / period) * period
                    val distToCenter = min(mod, period - mod)
                    val lineCov = (halfLine + 0.5f - distToCenter).coerceIn(0f, 1f)
                    if (lineCov > 1e-3f) {
                        val hatch = blendOver(r, g, b, a, accent, lineCov * extInA)
                        r = hatch[0]; g = hatch[1]; b = hatch[2]; a = hatch[3]
                    }
                }

                if (extRingA > 1e-3f) {
                    val rim = blendOver(r, g, b, a, accent, extRingA)
                    r = rim[0]; g = rim[1]; b = rim[2]; a = rim[3]
                }

                if (fStrokeA > 1e-3f) {
                    val st = blendOver(r, g, b, a, strokeCol, fStrokeA)
                    r = st[0]; g = st[1]; b = st[2]; a = st[3]
                }

                if (faceA > 1e-3f) {
                    val fc = blendOver(r, g, b, a, faceCol, faceA)
                    r = fc[0]; g = fc[1]; b = fc[2]; a = fc[3]
                }

                if (a > 1e-3f) {
                    outRgba[idx] = Color.rgba8888(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
                }
            }
        }
        return outRgba
    }
}

/**
 * Out-game Main Menu screen (`OutGameRoute.MAIN_MENU`, UI Supplement §3 & `RWRE-logo.svg` / `主菜单字效版.html`):
 * - Frameless floating `RWRE` ordnance stencil vector logo aligned with the left menu column;
 * - Frameless 4-row menu (`遭遇战 / 测试与沙盒 / 设置 / 退出`) with `12×12` solid `rust` marker on active row;
 * - Dark-theme-only diagonal hatched extruded selected text (`theme.features.mainMenuExtrudeText`);
 * - Frameless top-right `RustedWarfare-Reforge` + right-aligned version;
 * - Frameless bottom-right `© Fauston Morus & Rust Tech`;
 * - Centered two-step exit confirmation modal when `appShell.exitConfirmVisible` is active.
 */
class MainMenuScreen(
    private val skin: Skin,
    private val appShell: AppShell,
    private val themeProvider: UiThemeProvider,
    private val assetResolverProvider: () -> UiThemeAssetResolver,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val scaleProvider: () -> Float,
    private val touchControls: Boolean = false,
    private val onSelectSkirmish: () -> Unit,
    private val onSelectSandbox: () -> Unit,
    private val onSelectSettings: () -> Unit,
    private val onSelectQuit: () -> Unit,
) : Group(), AutoCloseable {

    enum class MenuItemKind(val labelKey: String, val actionId: String) {
        SKIRMISH("mainMenu.skirmish", "skirmish-setup"),
        SANDBOX("mainMenu.sandbox", "main-menu-sandbox"),
        SETTINGS("mainMenu.settings", "main-menu-settings"),
        QUIT("mainMenu.quit", "main-menu-quit"),
    }

    private data class ExtrudeCacheKey(
        val text: String,
        val scaleBits: Int,
        val accentBits: Int,
        val strokeBits: Int,
        val faceBits: Int,
        val fontBoldPath: String,
        val packRootsHash: Int,
    )

    private data class CachedExtrudeEntry(
        val texture: Texture,
        val marginStage: Float,
        val logicalWidth: Float,
        val logicalHeight: Float,
        val faceHeightStage: Float,
    )

    var selectedIndex: Int = 0
        private set

    private val glyphLayout = GlyphLayout()
    private val logoActor = RwreLogoActor(themeProvider, scaleProvider)
    private val rowActors = ArrayList<MenuRowActor>(MenuItemKind.entries.size)
    private val extrudeCache = LinkedHashMap<ExtrudeCacheKey, CachedExtrudeEntry>()

    // Exit confirmation modal buttons (`appShell.exitConfirmVisible`)
    private var confirmCancelHovered = false
    private var confirmOkHovered = false

    val confirmCancelActor: Actor = ModalButtonActor(isPrimary = false, labelKey = "mainMenu.quitConfirmCancel") {
        appShell.closeModal(AppShell.MODAL_EXIT_CONFIRM)
        syncModalInteractiveState()
    }

    val confirmOkActor: Actor = ModalButtonActor(isPrimary = true, labelKey = "mainMenu.quitConfirmOk") {
        appShell.confirmExit()
        syncModalInteractiveState()
    }

    private var modalCardX = 0f
    private var modalCardY = 0f
    private var modalCardW = 380f
    private var modalCardH = 152f
    private var lastSyncedModalOpen: Boolean? = null

    fun rowActorForTest(index: Int): Actor = rowActors[index]

    init {
        setTransform(false)
        addActor(logoActor)
        MenuItemKind.entries.forEachIndexed { idx, kind ->
            val actor = MenuRowActor(idx, kind)
            rowActors += actor
            addActor(actor)
        }
        addActor(confirmCancelActor)
        addActor(confirmOkActor)
        updateLayout(960f, 640f)
    }

    fun syncModalInteractiveState() {
        val stageW = width.takeIf { it > 0f } ?: 960f
        val stageH = height.takeIf { it > 0f } ?: 640f
        val modalOpen = appShell.exitConfirmVisible
        lastSyncedModalOpen = modalOpen
        for (row in rowActors) {
            row.touchable = if (modalOpen) Touchable.disabled else Touchable.enabled
        }
        modalCardW = min(400f, stageW - 48f)
        modalCardH = 152f
        modalCardX = (stageW - modalCardW) * 0.5f
        modalCardY = (stageH - modalCardH) * 0.5f
        confirmCancelActor.isVisible = modalOpen
        confirmOkActor.isVisible = modalOpen
        confirmCancelActor.touchable = if (modalOpen) Touchable.enabled else Touchable.disabled
        confirmOkActor.touchable = if (modalOpen) Touchable.enabled else Touchable.disabled
        if (modalOpen) {
            val btnW = 104f
            val btnH = 36f
            val footY = modalCardY + 18f
            confirmOkActor.setBounds(modalCardX + modalCardW - 22f - btnW, footY, btnW, btnH)
            confirmCancelActor.setBounds(confirmOkActor.x - 10f - btnW, footY, btnW, btnH)
            confirmCancelActor.toFront()
            confirmOkActor.toFront()
        }
    }

    fun selectMenuIndex(index: Int) {
        selectedIndex = Math.floorMod(index, MenuItemKind.entries.size)
    }

    fun triggerItem(kind: MenuItemKind) {
        selectedIndex = kind.ordinal
        when (kind) {
            MenuItemKind.SKIRMISH -> onSelectSkirmish()
            MenuItemKind.SANDBOX -> onSelectSandbox()
            MenuItemKind.SETTINGS -> onSelectSettings()
            MenuItemKind.QUIT -> onSelectQuit()
        }
        syncModalInteractiveState()
    }

    fun handleKeyDown(keycode: Int): Boolean {
        if (!isVisible) return false
        if (appShell.exitConfirmVisible) {
            return when (keycode) {
                Input.Keys.ENTER -> {
                    appShell.confirmExit()
                    true
                }
                else -> false
            }
        }
        return when (keycode) {
            Input.Keys.UP, Input.Keys.W -> {
                selectMenuIndex(selectedIndex - 1)
                true
            }
            Input.Keys.DOWN, Input.Keys.S -> {
                selectMenuIndex(selectedIndex + 1)
                true
            }
            Input.Keys.ENTER, Input.Keys.SPACE -> {
                triggerItem(MenuItemKind.entries[selectedIndex])
                true
            }
            else -> false
        }
    }

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        val compact = (stageHeight / 720f).coerceIn(0.80f, 1f)
        val leftX = if (stageWidth < 1020f) 34f else 44f
        val colWidth = if (stageWidth < 1020f) 332f else 360f
        val rowHeight = (66f * compact).roundToInt().toFloat().coerceIn(52f, 68f)
        val menuBottom = (46f * compact).roundToInt().toFloat().coerceAtLeast(32f)

        val count = rowActors.size
        for (i in 0 until count) {
            // Item 0 (`遭遇战`) at top, Item 3 (`退出`) at bottom
            val rowBottom = menuBottom + (count - 1 - i) * rowHeight
            rowActors[i].setBounds(leftX, rowBottom, colWidth, rowHeight)
        }

        val menuTop = menuBottom + count * rowHeight
        val logoGap = (22f * compact).roundToInt().toFloat().coerceAtLeast(16f)
        val logoW = colWidth
        val logoH = logoW / RwreLogoVectorSpec.ASPECT_RATIO
        logoActor.setBounds(leftX, menuTop + logoGap, logoW, logoH)

        syncModalInteractiveState()
    }

    override fun act(delta: Float) {
        if (lastSyncedModalOpen != appShell.exitConfirmVisible) {
            syncModalInteractiveState()
        }
        super.act(delta)
    }

    private fun getOrCreateExtrudeEntry(text: String, theme: UiTheme): CachedExtrudeEntry? {
        if (!DarkMenuExtrudeRenderer.isEnabledForTheme(theme)) return null
        val scale = scaleProvider().coerceIn(0.65f, 3.0f)
        val resolver = assetResolverProvider()
        val key = ExtrudeCacheKey(
            text = text,
            scaleBits = (scale * 100f).roundToInt(),
            accentBits = Color.rgba8888(theme.outGame.menuExtrudeAccent),
            strokeBits = Color.rgba8888(theme.outGame.menuExtrudeStroke),
            faceBits = Color.rgba8888(theme.colors.boneHi),
            fontBoldPath = theme.assets.fontBold,
            packRootsHash = resolver.hashCode(),
        )
        extrudeCache[key]?.let { return it }
        if (extrudeCache.size >= 8) {
            val oldest = extrudeCache.entries.first()
            oldest.value.texture.dispose()
            extrudeCache.remove(oldest.key)
        }
        val boldHandle = runCatching {
            resolver.resolve(theme.assets.fontBold)
        }.getOrElse {
            Gdx.files?.classpath(UiFontSet.BOLD_FONT_PATH) ?: return null
        }
        val rendered = runCatching {
            DarkMenuExtrudeRenderer.renderToPixmap(text, scale, theme, boldHandle, refFontSize = 35)
        }.getOrNull() ?: return null
        val tex = try {
            Texture(rendered.pixmap).also { it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
        } finally {
            rendered.pixmap.dispose()
        }
        val entry = CachedExtrudeEntry(
            texture = tex,
            marginStage = rendered.marginPx / scale,
            logicalWidth = tex.width / scale,
            logicalHeight = tex.height / scale,
            faceHeightStage = rendered.faceHeightPx / scale,
        )
        extrudeCache[key] = entry
        return entry
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (lastSyncedModalOpen != appShell.exitConfirmVisible) {
            syncModalInteractiveState()
        }
        val savedColor = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val compact = (height / 720f).coerceIn(0.80f, 1f)
        val rightEdge = width - (if (width < 1020f) 34f else 44f)

        // 1. Top-right frameless project title + right-aligned version
        val heroFont = fontsProvider()?.heroMono ?: fontsProvider()?.subPageTitle ?: fallbackFontProvider()
        val verFont = fontsProvider()?.subHeroMono ?: fontsProvider()?.smallMono ?: fallbackFontProvider()
        val titleText = ReforgeVersion.PROJECT_TITLE
        glyphLayout.setText(heroFont, titleText)
        val titleTopY = height - (38f * compact).roundToInt().toFloat()
        val titleX = rightEdge - glyphLayout.width
        val prevHeroCol = heroFont.color.cpy()
        heroFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
        heroFont.draw(batch, titleText, titleX, titleTopY)
        heroFont.color = prevHeroCol

        val verTopY = titleTopY - glyphLayout.height - 10f
        val verText = ReforgeVersion.DISPLAY_VERSION
        glyphLayout.setText(verFont, verText)
        val prevVerCol = verFont.color.cpy()
        verFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
        verFont.draw(batch, verText, rightEdge - glyphLayout.width, verTopY)
        verFont.color = prevVerCol

        // 2. Bottom-right frameless copyright notice
        val copyFont = fontsProvider()?.small ?: fallbackFontProvider()
        val copyText = ReforgeVersion.COPYRIGHT_NOTICE
        glyphLayout.setText(copyFont, copyText)
        val copyBottomY = (30f * compact).roundToInt().toFloat() + glyphLayout.height
        val copyX = rightEdge - glyphLayout.width
        val prevCopyCol = copyFont.color.cpy()
        if (theme.outGame.panelShadow.a > 0.25f) {
            copyFont.setColor(theme.colors.ink.r, theme.colors.ink.g, theme.colors.ink.b, 0.72f * parentAlpha)
            copyFont.draw(batch, copyText, copyX + 1f, copyBottomY - 1f)
        }
        val cfg = theme.outGame.copyrightFg
        copyFont.setColor(cfg.r, cfg.g, cfg.b, cfg.a * parentAlpha)
        copyFont.draw(batch, copyText, copyX, copyBottomY)
        copyFont.color = prevCopyCol

        // 3. Draw logo & menu rows
        if (!appShell.exitConfirmVisible) {
            batch.packedColor = savedColor
            super.draw(batch, parentAlpha)
            return
        }

        // When exit confirmation modal is active, draw menu underneath then modal scrim + dialog card on top
        logoActor.draw(batch, parentAlpha)
        for (row in rowActors) row.draw(batch, parentAlpha)

        OutGameDraw.fillRect(batch, white, 0f, 0f, width, height, theme.outGame.modalScrim, parentAlpha)
        OutGameDraw.fillRect(batch, white, modalCardX, modalCardY, modalCardW, modalCardH, theme.colors.solid, parentAlpha)
        OutGameDraw.strokeRect(batch, white, modalCardX, modalCardY, modalCardW, modalCardH, 1f, theme.colors.line, parentAlpha)
        OutGameDraw.fillRect(batch, white, modalCardX, modalCardY + modalCardH - 3f, modalCardW, 3f, theme.colors.rust, parentAlpha)

        val modalFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
        val prompt = UiStrings.get("mainMenu.quitConfirmTitle")
        glyphLayout.setText(modalFont, prompt)
        val prevModalCol = modalFont.color.cpy()
        modalFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
        modalFont.draw(batch, prompt, modalCardX + 24f, modalCardY + modalCardH - 36f)
        modalFont.color = prevModalCol

        confirmCancelActor.draw(batch, parentAlpha)
        confirmOkActor.draw(batch, parentAlpha)
        batch.packedColor = savedColor
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        if (lastSyncedModalOpen != appShell.exitConfirmVisible) {
            syncModalInteractiveState()
        }
        if (appShell.exitConfirmVisible) {
            return listOf(
                DrawnAction("exit-confirm-cancel", confirmCancelActor, UiStrings.get("mainMenu.quitConfirmCancel"), enabled = true),
                DrawnAction("exit-confirm-ok", confirmOkActor, UiStrings.get("mainMenu.quitConfirmOk"), enabled = true),
            )
        }
        return rowActors.map { row ->
            DrawnAction(row.kind.actionId, row, UiStrings.get(row.kind.labelKey), enabled = true)
        }
    }

    override fun close() {
        logoActor.close()
        for (entry in extrudeCache.values) {
            runCatching { entry.texture.dispose() }
        }
        extrudeCache.clear()
    }

    private inner class MenuRowActor(
        private val index: Int,
        val kind: MenuItemKind,
    ) : Actor() {
        private var pressed = false

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1 && !appShell.exitConfirmVisible) {
                        selectedIndex = index
                    }
                }

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || appShell.exitConfirmVisible) return false
                    selectedIndex = index
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && !appShell.exitConfirmVisible && x in 0f..width && y in 0f..height) {
                        triggerItem(kind)
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val isActive = index == selectedIndex
            val isLast = index == MenuItemKind.entries.lastIndex

            // Active row translucent fill + subtle light-theme hairline inset (mockup lines 417, 671, 681)
            if (isActive) {
                OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.rail, parentAlpha)
                if (!theme.features.mainMenuExtrudeText) {
                    OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
                } else if (!isLast) {
                    OutGameDraw.fillRect(batch, white, x, y, width, 1f, theme.colors.hair, parentAlpha)
                }
            } else if (!isLast) {
                OutGameDraw.fillRect(batch, white, x, y, width, 1f, theme.colors.hair, parentAlpha)
            }

            // Left 12x12 solid `rust` marker when active; blank space when unselected so text aligns identically
            val markerSize = 12f
            val markerX = x + 12f
            val markerY = y + (height - markerSize) * 0.5f
            if (isActive) {
                OutGameDraw.fillRect(batch, white, markerX, markerY, markerSize, markerSize, theme.colors.rust, parentAlpha)
            }

            val textStartX = markerX + markerSize + 16f
            val labelText = UiStrings.get(kind.labelKey)

            // Dark-theme-only diagonal hatched extruded selected text
            val extrudeEntry = if (isActive && DarkMenuExtrudeRenderer.isEnabledForTheme(theme)) {
                getOrCreateExtrudeEntry(labelText, theme)
            } else {
                null
            }

            if (extrudeEntry != null) {
                val drawX = textStartX - extrudeEntry.marginStage
                val faceBottomY = y + (height - extrudeEntry.faceHeightStage) * 0.5f
                val drawY = faceBottomY - (extrudeEntry.logicalHeight - extrudeEntry.marginStage - extrudeEntry.faceHeightStage)
                batch.setColor(1f, 1f, 1f, parentAlpha)
                batch.draw(extrudeEntry.texture, drawX, drawY, extrudeEntry.logicalWidth, extrudeEntry.logicalHeight)
            } else {
                val font = fontsProvider()?.menuItem ?: fontsProvider()?.subPageTitle ?: fallbackFontProvider()
                glyphLayout.setText(font, labelText)
                val fg = if (isActive) theme.colors.boneHi else theme.colors.text
                val prev = font.color.cpy()
                font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
                font.draw(batch, labelText, textStartX, y + (height + glyphLayout.height) * 0.5f)
                font.color = prev
            }
            batch.packedColor = saved
        }
    }

    private inner class ModalButtonActor(
        private val isPrimary: Boolean,
        private val labelKey: String,
        private val onClick: () -> Unit,
    ) : Actor() {
        private var hovered = false
        private var pressed = false

        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0) return false
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && x in 0f..width && y in 0f..height) onClick()
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val bg = when {
                isPrimary && hovered -> theme.outGame.primaryBgHover
                isPrimary -> theme.outGame.primaryBg
                hovered -> theme.colors.cellHover
                else -> theme.colors.cell
            }
            val border = if (isPrimary) bg else if (hovered) theme.colors.value else theme.colors.line
            val fg = if (isPrimary) theme.outGame.primaryFg else theme.colors.text
            OutGameDraw.fillRect(batch, white, x, y, width, height, bg, parentAlpha)
            if (isPrimary) {
                OutGameDraw.fillRect(batch, white, x, y, width, 3f, theme.outGame.primaryBevel, parentAlpha)
            } else {
                OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, parentAlpha)
            }
            val font = fontsProvider()?.command ?: fallbackFontProvider()
            val text = UiStrings.get(labelKey)
            glyphLayout.setText(font, text)
            val prev = font.color.cpy()
            font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
            font.draw(batch, text, x + (width - glyphLayout.width) * 0.5f, y + (height + glyphLayout.height) * 0.5f)
            font.color = prev
            batch.packedColor = saved
        }
    }
}
