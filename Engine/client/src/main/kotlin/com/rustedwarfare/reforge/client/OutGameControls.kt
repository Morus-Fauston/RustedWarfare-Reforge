package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.utils.GdxNativesLoader
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single-piece geometric downward triangle rasterizer & GL texture holder (UI Supplement §4.1).
 *
 * Guarantees a continuous, seamless filled triangle chevron with **zero two-segment center crack**
 * and zero JNI requirement during pure-JVM `:client:test` verification.
 */
object OutGameChevronSpec {
    const val DEFAULT_WIDTH_PX: Int = 36
    const val DEFAULT_HEIGHT_PX: Int = 24
    const val DEFAULT_SUPERSAMPLE: Int = 4

    /**
     * Builds a pure-white alpha-masked `RGBA8888` pixel buffer of a downward pointing triangle.
     * Top-down pixel coordinates: `y = 0` is top base edge, `y = heightPx - 1` is bottom apex.
     */
    fun buildDownTriangleRgbaBuffer(
        widthPx: Int = DEFAULT_WIDTH_PX,
        heightPx: Int = DEFAULT_HEIGHT_PX,
        supersample: Int = DEFAULT_SUPERSAMPLE,
    ): IntArray {
        val w = widthPx.coerceIn(12, 128)
        val h = heightPx.coerceIn(8, 128)
        val ss = supersample.coerceIn(1, 8)
        val totalSamples = ss * ss
        val buf = IntArray(w * h)

        // Symmetric inverted triangle vertices in pixel space
        val ax = w * 0.08f
        val ay = h * 0.16f
        val bx = w * 0.92f
        val by = h * 0.16f
        val cx = w * 0.50f
        val cy = h * 0.86f

        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, px: Float, py: Float): Float =
            (px - x0) * (y1 - y0) - (py - y0) * (x1 - x0)

        // Clockwise / counter-clockwise consistent sign check
        val orient = edge(ax, ay, bx, by, cx, cy)
        val sign = if (orient >= 0f) 1f else -1f

        for (py in 0 until h) {
            val rowOff = py * w
            for (px in 0 until w) {
                var hits = 0
                for (sy in 0 until ss) {
                    val sampleY = py + (sy + 0.5f) / ss.toFloat()
                    for (sx in 0 until ss) {
                        val sampleX = px + (sx + 0.5f) / ss.toFloat()
                        val e0 = edge(ax, ay, bx, by, sampleX, sampleY) * sign
                        val e1 = edge(bx, by, cx, cy, sampleX, sampleY) * sign
                        val e2 = edge(cx, cy, ax, ay, sampleX, sampleY) * sign
                        if (e0 >= 0f && e1 >= 0f && e2 >= 0f) {
                            hits++
                        }
                    }
                }
                if (hits > 0) {
                    val alpha = ((hits * 255 + totalSamples / 2) / totalSamples).coerceIn(0, 255)
                    buf[rowOff + px] = (0xFFFFFF shl 8) or alpha
                }
            }
        }
        return buf
    }
}

/**
 * Per-presentation-instance GL icon cache so Android `pause()`/`resume()` disposes and recreates cleanly.
 */
class OutGameControlIcons : AutoCloseable {
    private var chevronTexture: Texture? = null
    private var chevronRegion: TextureRegion? = null

    fun chevronRegion(): TextureRegion? {
        chevronRegion?.let { return it }
        if (Gdx.gl == null) return null
        runCatching { GdxNativesLoader.load() }
        val w = OutGameChevronSpec.DEFAULT_WIDTH_PX
        val h = OutGameChevronSpec.DEFAULT_HEIGHT_PX
        val buf = OutGameChevronSpec.buildDownTriangleRgbaBuffer(w, h)
        val pm = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pm.blending = Pixmap.Blending.None
        val tex = try {
            for (py in 0 until h) {
                val off = py * w
                for (px in 0 until w) pm.drawPixel(px, py, buf[off + px])
            }
            Texture(pm).also {
                it.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
            }
        } finally {
            pm.dispose()
        }
        chevronTexture = tex
        return TextureRegion(tex).also { chevronRegion = it }
    }

    override fun close() {
        chevronTexture?.let { runCatching { it.dispose() } }
        chevronTexture = null
        chevronRegion = null
    }
}

/**
 * Theme-driven out-game button supporting secondary, primary CTA, danger (`踢出`/`移除`), and dashed add-row variants.
 * Zero hardcoded colors: every state derives from [UiTheme].
 */
class OutGameButton(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    var labelProvider: () -> String,
    var variant: Variant = Variant.SECONDARY,
    var indicatorDotProvider: (() -> Boolean)? = null,
    private val onClick: () -> Unit,
) : Actor() {

    enum class Variant {
        SECONDARY,
        PRIMARY,
        DANGER,
        DASHED_ADD,
    }

    var enabled: Boolean = true
    var hovered: Boolean = false
        private set
    var pressed: Boolean = false
        private set

    private val glyphLayout = GlyphLayout()

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
                if (pointer != 0 || !enabled) return false
                pressed = true
                return true
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer != 0) return
                val wasPressed = pressed
                pressed = false
                if (wasPressed && enabled && x in 0f..width && y in 0f..height) {
                    onClick()
                }
            }
        })
    }

    fun triggerClick() {
        if (enabled) onClick()
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val activeHover = enabled && (hovered || pressed)
        val pressY = if (enabled && pressed) -1f else 0f
        val drawY = y + pressY

        when (variant) {
            Variant.SECONDARY -> {
                val bg = when {
                    !enabled -> theme.colors.well
                    activeHover -> theme.colors.cellHover
                    else -> theme.colors.cell
                }
                val border = when {
                    !enabled -> theme.colors.hair
                    activeHover -> theme.colors.value
                    else -> theme.colors.line
                }
                OutGameDraw.fillRect(batch, white, x, drawY, width, height, bg, parentAlpha)
                OutGameDraw.strokeRect(batch, white, x, drawY, width, height, 1f, border, parentAlpha)
            }

            Variant.PRIMARY -> {
                val bg = when {
                    !enabled -> theme.colors.well
                    activeHover -> theme.outGame.primaryBgHover
                    else -> theme.outGame.primaryBg
                }
                OutGameDraw.fillRect(batch, white, x, drawY, width, height, bg, parentAlpha)
                if (enabled) {
                    OutGameDraw.fillRect(batch, white, x, drawY, width, 2f, theme.outGame.primaryBevel, parentAlpha)
                    if (theme.id.contains("bone")) {
                        OutGameDraw.fillRect(batch, white, x, drawY, 3f, height, theme.colors.rust, parentAlpha)
                    }
                } else {
                    OutGameDraw.strokeRect(batch, white, x, drawY, width, height, 1f, theme.colors.line, parentAlpha)
                }
            }

            Variant.DANGER -> {
                val bg = when {
                    !enabled -> theme.colors.well
                    activeHover -> theme.outGame.dangerHoverBg
                    else -> theme.outGame.dangerBg
                }
                val border = when {
                    !enabled -> theme.colors.line
                    activeHover -> theme.outGame.dangerHoverBg
                    else -> theme.outGame.dangerBorder
                }
                OutGameDraw.fillRect(batch, white, x, drawY, width, height, bg, parentAlpha)
                OutGameDraw.strokeRect(batch, white, x, drawY, width, height, 1f, border, parentAlpha)
            }

            Variant.DASHED_ADD -> {
                if (activeHover) {
                    OutGameDraw.fillRect(batch, white, x, drawY, width, height, theme.outGame.hoverSoft, parentAlpha)
                }
                val dashCol = if (activeHover) theme.colors.value else theme.colors.line
                drawDashedRect(batch, white, x, drawY, width, height, dashCol, parentAlpha)
            }
        }

        val font = when (variant) {
            Variant.PRIMARY -> fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
            Variant.DANGER -> fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            Variant.SECONDARY, Variant.DASHED_ADD -> fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
        }
        val fg = when {
            !enabled -> theme.colors.disabledText
            variant == Variant.PRIMARY -> theme.outGame.primaryFg
            variant == Variant.DANGER -> if (activeHover) theme.outGame.dangerHoverFg else theme.outGame.dangerFg
            activeHover -> theme.colors.boneHi
            else -> theme.colors.text
        }

        val text = labelProvider()
        glyphLayout.setText(font, text)
        val showDot = indicatorDotProvider?.invoke() == true
        val dotGap = if (showDot) 12f else 0f
        val totalW = glyphLayout.width + dotGap
        val tx = x + (width - totalW) * 0.5f
        val ty = drawY + (height + glyphLayout.height) * 0.5f

        val prevFontCol = font.color.cpy()
        font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
        font.draw(batch, text, tx, ty)
        font.color = prevFontCol

        if (showDot) {
            val dotSize = 6f
            val dx = tx + glyphLayout.width + 6f
            val dy = drawY + (height - dotSize) * 0.5f
            OutGameDraw.fillRect(batch, white, dx, dy, dotSize, dotSize, theme.colors.rust, parentAlpha)
        }
        batch.packedColor = saved
    }

    companion object {
        internal fun drawDashedRect(
            batch: Batch,
            white: TextureRegion,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            color: Color,
            parentAlpha: Float,
            dash: Float = 5f,
            gap: Float = 3f,
        ) {
            val step = dash + gap
            var cx = x
            while (cx < x + w) {
                val seg = min(dash, x + w - cx)
                OutGameDraw.fillRect(batch, white, cx, y + h - 1f, seg, 1f, color, parentAlpha)
                OutGameDraw.fillRect(batch, white, cx, y, seg, 1f, color, parentAlpha)
                cx += step
            }
            var cy = y
            while (cy < y + h) {
                val seg = min(dash, y + h - cy)
                OutGameDraw.fillRect(batch, white, x, cy, 1f, seg, color, parentAlpha)
                OutGameDraw.fillRect(batch, white, x + w - 1f, cy, 1f, seg, color, parentAlpha)
                cy += step
            }
        }
    }
}

/**
 * Compact horizontal segmented control (`标准 | 全歼 | 无胜负`, `无迷雾 | 地形全明 | 记忆 | 硬核`, etc.).
 */
class SegmentedControl<T>(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    options: List<T>,
    initialSelected: T,
    private val labelOf: (T) -> String,
    private val onSelected: (T) -> Unit,
) : Group() {

    var options: List<T> = options.toList()
        private set

    var selected: T = initialSelected
        private set

    var enabled: Boolean = true

    private val segmentActorsList = ArrayList<SegmentActor>()
    val segmentActors: List<Actor> get() = segmentActorsList

    init {
        setTransform(false)
        rebuildSegments()
    }

    fun setOptionsAndSelection(newOptions: List<T>, newSelected: T) {
        options = newOptions.toList()
        selected = newSelected
        rebuildSegments()
        layoutSegments()
    }

    fun setSelectedSilently(value: T) {
        selected = value
    }

    fun select(value: T) {
        if (!enabled || value !in options) return
        if (selected != value) {
            selected = value
            onSelected(value)
        }
    }

    fun cycleNext() {
        if (!enabled || options.isEmpty()) return
        val idx = options.indexOf(selected)
        val next = options[(idx + 1).coerceAtLeast(0) % options.size]
        select(next)
    }

    override fun setBounds(x: Float, y: Float, width: Float, height: Float) {
        super.setBounds(x, y, width, height)
        layoutSegments()
    }

    override fun setSize(width: Float, height: Float) {
        super.setSize(width, height)
        layoutSegments()
    }

    private fun rebuildSegments() {
        clearChildren()
        segmentActorsList.clear()
        for (opt in options) {
            val seg = SegmentActor(opt)
            segmentActorsList += seg
            addActor(seg)
        }
    }

    private fun layoutSegments() {
        if (segmentActorsList.isEmpty() || width <= 0f || height <= 0f) return
        val font = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
        val layout = GlyphLayout()
        val naturalWidths = FloatArray(segmentActorsList.size) { idx ->
            layout.setText(font, labelOf(segmentActorsList[idx].item))
            layout.width + 18f
        }
        val sumNatural = naturalWidths.sum().coerceAtLeast(1f)
        var cursorX = 0f
        for (i in segmentActorsList.indices) {
            val segW = if (i == segmentActorsList.lastIndex) {
                max(0f, width - cursorX)
            } else {
                (width * (naturalWidths[i] / sumNatural)).roundToInt().toFloat()
            }
            segmentActorsList[i].setBounds(cursorX, 0f, segW, height)
            cursorX += segW
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.well, parentAlpha)
        batch.packedColor = saved
        super.draw(batch, parentAlpha)
        // Crisp 1px dividers and outer frame
        var accumX = x
        for (i in 0 until segmentActorsList.size - 1) {
            accumX += segmentActorsList[i].width
            OutGameDraw.fillRect(batch, white, accumX, y + 1f, 1f, height - 2f, theme.colors.line, parentAlpha)
        }
        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
        batch.packedColor = saved
    }

    private inner class SegmentActor(val item: T) : Actor() {
        private val glyphLayout = GlyphLayout()
        private var hovered = false

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
                    if (pointer != 0 || !enabled) return false
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0 || !enabled) return
                    if (x in 0f..width && y in 0f..height) {
                        select(item)
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val isActive = item == selected
            if (isActive) {
                OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.segActiveBg, parentAlpha)
            } else if (enabled && hovered) {
                OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.hoverSoft, parentAlpha)
            }

            val font = if (isActive) {
                fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            } else {
                fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
            }
            val fg = when {
                !enabled -> theme.colors.disabledText
                isActive -> theme.outGame.segActiveFg
                hovered -> theme.colors.boneHi
                else -> theme.colors.text
            }
            val text = labelOf(item)
            glyphLayout.setText(font, text)
            val prev = font.color.cpy()
            font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
            font.draw(batch, text, x + (width - glyphLayout.width) * 0.5f, y + (height + glyphLayout.height) * 0.5f)
            font.color = prev
            batch.packedColor = saved
        }
    }
}

/**
 * Compact unified dropdown with a **single-piece geometric downward triangle** and optional
 * semantic tinting for Team and Spawn Point columns (UI Supplement §4.1).
 *
 * Strictly zero text ellipsis (`...`) and zero two-line chevron seam cracks.
 */
class UnifiedDropdown<T>(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons?,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    options: List<T>,
    initialSelected: T,
    private val labelOf: (T) -> String,
    var tintColorProvider: (() -> Color?)? = null,
    private val useMonoFont: Boolean = false,
    private val onSelected: (T) -> Unit,
) : Actor() {

    var options: List<T> = options.toList()
        private set

    var selected: T = initialSelected
        private set

    var enabled: Boolean = true

    private val glyphLayout = GlyphLayout()
    private val stageCoordTmp = Vector2()
    private var hovered = false
    private var popupOverlay: Group? = null

    val isPopupOpen: Boolean
        get() = popupOverlay?.stage != null

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
                if (pointer != 0 || !enabled) return false
                return true
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer != 0 || !enabled) return
                if (x in 0f..width && y in 0f..height) {
                    togglePopup()
                }
            }
        })
    }

    fun setOptionsAndSelection(newOptions: List<T>, newSelected: T) {
        closePopup()
        options = newOptions.toList()
        selected = newSelected
    }

    fun setSelectedSilently(value: T) {
        selected = value
    }

    fun select(value: T) {
        closePopup()
        if (!enabled || value !in options) return
        if (selected != value) {
            selected = value
            onSelected(value)
        }
    }

    fun cycleNext() {
        closePopup()
        if (!enabled || options.isEmpty()) return
        val idx = options.indexOf(selected)
        val next = options[(idx + 1).coerceAtLeast(0) % options.size]
        select(next)
    }

    fun togglePopup() {
        if (isPopupOpen) {
            closePopup()
        } else {
            openPopup()
        }
    }

    fun closePopup() {
        val existing = popupOverlay ?: return
        popupOverlay = null
        existing.remove()
    }

    private fun openPopup() {
        val currentStage: Stage = stage ?: run {
            // In pure headless tests without a Stage, cycling gives deterministic direct selection
            cycleNext()
            return
        }
        closeAllOpenDropdowns(currentStage)

        val overlay = Group().apply {
            name = POPUP_OVERLAY_ACTOR_NAME
            userObject = this@UnifiedDropdown
            setTransform(false)
            setBounds(0f, 0f, currentStage.width, currentStage.height)
        }

        // Fullscreen transparent click-catcher so clicking anywhere outside dismisses cleanly
        val backdropCatcher = Actor().apply {
            setBounds(0f, 0f, currentStage.width, currentStage.height)
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    closePopup()
                    return true
                }

                override fun keyDown(event: InputEvent, keycode: Int): Boolean {
                    if (keycode == Input.Keys.ESCAPE || keycode == Input.Keys.BACK) {
                        closePopup()
                        return true
                    }
                    return false
                }
            })
        }
        overlay.addActor(backdropCatcher)

        localToStageCoordinates(stageCoordTmp.set(0f, 0f))
        val anchorX = stageCoordTmp.x
        val anchorY = stageCoordTmp.y

        val font = resolveFont(bold = false, sampleText = options.joinToString("") { labelOf(it) })
        var maxTextW = 0f
        for (opt in options) {
            glyphLayout.setText(font, labelOf(opt))
            maxTextW = max(maxTextW, glyphLayout.width)
        }
        val rowH = max(28f, height)
        val popupW = max(width, maxTextW + 26f)
        val popupH = options.size * rowH + 4f

        val placeBelowY = anchorY - popupH - 2f
        val popupY = if (placeBelowY >= 8f) {
            placeBelowY
        } else {
            min(currentStage.height - popupH - 8f, anchorY + height + 2f).coerceAtLeast(8f)
        }
        val popupX = anchorX.coerceIn(8f, max(8f, currentStage.width - popupW - 8f))

        val listBox = object : Group() {
            init {
                setTransform(false)
                setBounds(popupX, popupY, popupW, popupH)
            }

            override fun draw(batch: Batch, parentAlpha: Float) {
                val saved = batch.packedColor
                val theme = themeProvider.currentTheme()
                val white = skin.getRegion("white")
                val shadow = theme.outGame.panelShadow
                if (shadow.a > 0f) {
                    OutGameDraw.fillRect(batch, white, x + 2f, y - 4f, width, height, shadow, parentAlpha)
                }
                OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.solid, parentAlpha)
                batch.packedColor = saved
                super.draw(batch, parentAlpha)
                OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.popupBorder, parentAlpha)
                batch.packedColor = saved
            }
        }

        options.forEachIndexed { idx, opt ->
            val ry = popupH - 2f - (idx + 1) * rowH
            val optionActor = object : Actor() {
                private var optHover = false

                init {
                    setBounds(2f, ry, popupW - 4f, rowH)
                    touchable = Touchable.enabled
                    addListener(object : InputListener() {
                        override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                            if (pointer == -1) optHover = true
                        }

                        override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                            if (pointer == -1) optHover = false
                        }

                        override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                            pointer == 0

                        override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                            if (pointer == 0 && x in 0f..width && y in 0f..height) {
                                select(opt)
                            }
                        }
                    })
                }

                override fun draw(batch: Batch, parentAlpha: Float) {
                    val saved = batch.packedColor
                    val theme = themeProvider.currentTheme()
                    val white = skin.getRegion("white")
                    val isSel = opt == selected
                    when {
                        optHover -> OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.cellHover, parentAlpha)
                        isSel -> OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.hoverSoft, parentAlpha)
                    }
                    if (isSel) {
                        OutGameDraw.fillRect(batch, white, x, y + 3f, 2f, height - 6f, theme.colors.rust, parentAlpha)
                    }
                    val text = labelOf(opt)
                    val f = resolveFont(bold = isSel, sampleText = text)
                    glyphLayout.setText(f, text)
                    val fg = if (isSel || optHover) theme.outGame.textStrong else theme.colors.text
                    val prev = f.color.cpy()
                    f.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
                    f.draw(batch, text, x + 9f, y + (height + glyphLayout.height) * 0.5f)
                    f.color = prev
                    batch.packedColor = saved
                }
            }
            listBox.addActor(optionActor)
        }

        overlay.addActor(listBox)
        currentStage.addActor(overlay)
        overlay.toFront()
        currentStage.keyboardFocus = backdropCatcher
        popupOverlay = overlay
    }

    private fun resolveFont(bold: Boolean, sampleText: String = ""): BitmapFont {
        val fs = fontsProvider()
        val pureAscii = sampleText.all { it.code in 32..126 }
        return when {
            useMonoFont && pureAscii -> fs?.countMono ?: fs?.smallMono ?: fs?.keyBold ?: fallbackFontProvider()
            bold -> fs?.keyBold ?: fs?.command ?: fallbackFontProvider()
            else -> fs?.command ?: fs?.body ?: fallbackFontProvider()
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val tint = tintColorProvider?.invoke()
        val activeHover = enabled && (hovered || isPopupOpen)

        val bg: Color
        val border: Color
        val textFg: Color
        val chevronFg: Color

        if (tint != null) {
            val fillAlpha = (theme.metrics.tintedDropdownFillAlpha + if (activeHover) 0.10f else 0f).coerceIn(0f, 0.90f)
            val borderAlpha = (theme.metrics.tintedDropdownBorderAlpha + if (activeHover) 0.12f else 0f).coerceIn(0f, 1f)
            bg = theme.colors.cell.cpy().lerp(tint, fillAlpha).apply { a = 1f }
            border = theme.colors.line.cpy().lerp(tint, borderAlpha).apply { a = 1f }
            val candidateFg = if (enabled) theme.outGame.tintedDropdownFg else theme.colors.disabledText
            // Ensure readable contrast if a bright player swatch (e.g. P10 silver) produces a high-luminance fill on dark theme
            val fillLuma = 0.2126f * bg.r + 0.7152f * bg.g + 0.0722f * bg.b
            val fgLuma = 0.2126f * candidateFg.r + 0.7152f * candidateFg.g + 0.0722f * candidateFg.b
            textFg = if (enabled && fillLuma > 0.56f && fgLuma > 0.56f) theme.colors.ink else candidateFg
            chevronFg = textFg.cpy().apply { a *= 0.86f }
        } else {
            bg = when {
                !enabled -> theme.colors.well
                activeHover -> theme.colors.cellHover
                else -> theme.colors.cell
            }
            border = when {
                !enabled -> theme.colors.hair
                isPopupOpen -> theme.colors.rust
                activeHover -> theme.colors.value
                else -> theme.colors.line
            }
            textFg = if (!enabled) theme.colors.disabledText else theme.outGame.textStrong
            chevronFg = if (!enabled) theme.colors.disabledText else if (activeHover) theme.outGame.textStrong else theme.colors.dim
        }

        OutGameDraw.fillRect(batch, white, x, y, width, height, bg, parentAlpha)
        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, parentAlpha)

        val text = labelOf(selected)
        val font = resolveFont(bold = tint != null, sampleText = text)
        glyphLayout.setText(font, text)
        val prev = font.color.cpy()
        font.setColor(textFg.r, textFg.g, textFg.b, textFg.a * parentAlpha)
        val padLeft = if (width <= 62f) 8f else 10f
        font.draw(batch, text, x + padLeft, y + (height + glyphLayout.height) * 0.5f)
        font.color = prev

        // Single-piece downward triangle chevron (zero seam crack)
        val chevW = 8.5f
        val chevH = 5.5f
        val chevX = x + width - (if (width <= 62f) 7f else 9f) - chevW
        val chevY = y + (height - chevH) * 0.5f
        val region = iconsProvider()?.chevronRegion()
        if (region != null) {
            batch.setColor(chevronFg.r, chevronFg.g, chevronFg.b, chevronFg.a * parentAlpha)
            batch.draw(region, chevX, chevY, chevW, chevH)
        } else {
            // Pure integer scanline triangle fallback when Gdx.gl == null
            val rows = 5
            for (r in 0 until rows) {
                val frac = r.toFloat() / (rows - 1).toFloat()
                val span = chevW * (1f - frac)
                if (span >= 1f) {
                    OutGameDraw.fillRect(
                        batch,
                        white,
                        chevX + (chevW - span) * 0.5f,
                        chevY + chevH - 1f - r,
                        span,
                        1f,
                        chevronFg,
                        parentAlpha,
                    )
                }
            }
        }
        batch.packedColor = saved
    }

    companion object {
        const val POPUP_OVERLAY_ACTOR_NAME: String = "outgame-dropdown-popup"

        fun closeAllOpenDropdowns(stage: Stage?): Boolean {
            val s = stage ?: return false
            var closedAny = false
            val snapshot = s.actors.toArray()
            for (actor in snapshot) {
                if (actor != null && actor.name == POPUP_OVERLAY_ACTOR_NAME) {
                    val owner = actor.userObject as? UnifiedDropdown<*>
                    if (owner != null) {
                        owner.closePopup()
                    } else {
                        actor.remove()
                    }
                    closedAny = true
                }
            }
            return closedAny
        }

        fun bringOpenDropdownsToFront(stage: Stage?) {
            val s = stage ?: return
            val snapshot = s.actors.toArray()
            for (actor in snapshot) {
                if (actor != null && actor.name == POPUP_OVERLAY_ACTOR_NAME) {
                    actor.toFront()
                }
            }
        }
    }
}

/**
 * Theme-synchronized text input field for `.tmx` / `.json` / pack directory path loading.
 */
class OutGameTextInput(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private var placeholderKey: String,
    initialText: String = "",
    private val onChanged: (String) -> Unit = {},
    private val onEnterPressed: () -> Unit = {},
) : Group() {

    private var lastTheme: UiTheme? = null
    private var lastFont: BitmapFont? = null
    private var lastLang: UiLanguage? = null
    private var headlessText: String = initialText

    val textField: TextField? = if (Gdx.app != null) {
        TextField(initialText, buildStyle(themeProvider.currentTheme())).apply {
            setOnlyFontChars(false)
            messageText = UiStrings.get(placeholderKey)
            addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    headlessText = text
                    onChanged(text)
                }
            })
            setTextFieldListener { _, c ->
                if (c == '\r' || c == '\n') {
                    onEnterPressed()
                }
            }
        }
    } else {
        null
    }

    var text: String
        get() = textField?.text ?: headlessText
        set(value) {
            headlessText = value
            if (textField != null && textField.text != value) {
                textField.text = value
            }
        }

    var enabled: Boolean = true
        set(value) {
            field = value
            textField?.isDisabled = !value
        }

    init {
        setTransform(false)
        if (textField != null) addActor(textField)
    }

    fun setPlaceholderKey(key: String) {
        placeholderKey = key
        textField?.messageText = UiStrings.get(key)
    }

    override fun setBounds(x: Float, y: Float, width: Float, height: Float) {
        super.setBounds(x, y, width, height)
        textField?.setBounds(0f, 0f, width, height)
    }

    private fun safeDrawable(color: Color, minW: Float = 0f, padLeft: Float = 0f, padRight: Float = 0f): Drawable {
        val d = runCatching { skin.newDrawable("white", color) }.getOrElse { BaseDrawable() }
        if (minW > 0f) d.minWidth = minW
        if (padLeft > 0f) d.leftWidth = padLeft
        if (padRight > 0f) d.rightWidth = padRight
        return d
    }

    private fun buildStyle(theme: UiTheme): TextField.TextFieldStyle {
        val font = fontsProvider()?.body ?: fallbackFontProvider()
        return TextField.TextFieldStyle().apply {
            this.font = font
            fontColor = theme.outGame.textStrong.cpy()
            disabledFontColor = theme.colors.disabledText.cpy()
            messageFont = fontsProvider()?.body ?: font
            messageFontColor = theme.colors.dim.cpy()
            background = safeDrawable(Color.CLEAR, padLeft = 10f, padRight = 10f)
            focusedBackground = safeDrawable(Color.CLEAR, padLeft = 10f, padRight = 10f)
            disabledBackground = safeDrawable(Color.CLEAR, padLeft = 10f, padRight = 10f)
            cursor = safeDrawable(theme.colors.rust, minW = 2f)
            selection = safeDrawable(theme.colors.queueProgress)
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val font = fontsProvider()?.body ?: fallbackFontProvider()
        if (textField != null && (lastTheme !== theme || lastFont !== font || lastLang != UiStrings.currentLanguage)) {
            lastTheme = theme
            lastFont = font
            lastLang = UiStrings.currentLanguage
            textField.style = buildStyle(theme)
            textField.messageText = UiStrings.get(placeholderKey)
        }
        val white = skin.getRegion("white")
        val focused = textField != null && stage?.keyboardFocus === textField
        val bg = if (!enabled) theme.colors.solid else theme.colors.well
        val border = when {
            !enabled -> theme.colors.hair
            focused -> theme.colors.rust
            else -> theme.colors.line
        }
        OutGameDraw.fillRect(batch, white, x, y, width, height, bg, parentAlpha)
        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, parentAlpha)
        batch.packedColor = saved
        super.draw(batch, parentAlpha)
    }
}
