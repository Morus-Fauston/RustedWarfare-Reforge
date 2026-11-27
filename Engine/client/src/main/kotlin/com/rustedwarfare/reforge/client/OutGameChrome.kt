package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Horizontal margin policy shared by [SubPageTopBar] and [OutGamePanelShell] (UI Supplement §2, §4.3, §7).
 */
enum class OutGameWidthMode {
    /** Standard 24px horizontal inset for dual-column lobbies & sandbox screens. */
    FULL_INSET,

    /** Symmetric wide margins for fullscreen `MapPicker` and `Settings` (108px on 1280x720, safe 24px floor on 960x640). */
    SYMMETRIC_WIDE,
    ;

    fun horizontalInset(stageWidth: Float): Float = when (this) {
        FULL_INSET -> 24f
        SYMMETRIC_WIDE -> if (stageWidth <= 1040f) {
            24f
        } else {
            ((stageWidth - 1064f) * 0.5f).coerceIn(24f, 180f)
        }
    }
}

/**
 * Low-level pixel-crisp primitive drawing helpers backed by Skin's 1x1 `"white"` region.
 * Every color originates from [UiTheme] tokens; zero hardcoded color literals exist here.
 */
internal object OutGameDraw {
    fun fillRect(
        batch: Batch,
        white: TextureRegion,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        color: Color,
        parentAlpha: Float = 1f,
    ) {
        if (w <= 0f || h <= 0f || color.a <= 0f) return
        batch.setColor(color.r, color.g, color.b, color.a * parentAlpha)
        batch.draw(white, x, y, w, h)
    }

    fun strokeRect(
        batch: Batch,
        white: TextureRegion,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        thickness: Float,
        color: Color,
        parentAlpha: Float = 1f,
    ) {
        if (w <= 0f || h <= 0f || thickness <= 0f || color.a <= 0f) return
        val t = thickness.coerceAtMost(minOf(w, h) * 0.5f)
        fillRect(batch, white, x, y + h - t, w, t, color, parentAlpha)
        fillRect(batch, white, x, y, w, t, color, parentAlpha)
        if (h > t * 2f) {
            fillRect(batch, white, x, y + t, t, h - t * 2f, color, parentAlpha)
            fillRect(batch, white, x + w - t, y + t, t, h - t * 2f, color, parentAlpha)
        }
    }

    /**
     * Renders the precision constructivist tick ruler (`8px` minor hair ticks + `40px` major ticks).
     */
    fun drawRuler(
        batch: Batch,
        white: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float = 6f,
        theme: UiTheme,
        flipVertical: Boolean = false,
        parentAlpha: Float = 1f,
    ) {
        val minorH = (height * 0.5f).coerceAtLeast(1f)
        val steps = (width / 8f).toInt()
        for (i in 0..steps) {
            val tx = x + i * 8f
            if (tx >= x + width) break
            val isMajor = i % 5 == 0
            val th = if (isMajor) height else minorH
            val ty = if (flipVertical) y else y + height - th
            val col = if (isMajor) theme.outGame.rulerMajor else theme.colors.hair
            fillRect(batch, white, tx, ty, 1f, th, col, parentAlpha)
        }
    }
}

/**
 * Unified secondary sub-page top bar (UI Supplement §2):
 * - Top-left fixed `← 返回` (`nav.back`) button;
 * - Centered bold page title (`titleKey`);
 * - Strictly no folder tab strip or subtitle clutter.
 */
class SubPageTopBar(
    private val skin: Skin,
    private val titleKey: String,
    private val widthMode: OutGameWidthMode = OutGameWidthMode.FULL_INSET,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onBack: () -> Unit,
) : Group() {
    private val glyphLayout = GlyphLayout()
    private var backHovered = false
    private var backPressed = false

    val backButtonActor: Actor = object : Actor() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val bg = if (backHovered || backPressed) theme.colors.cellHover else theme.colors.rail
            val border = if (backHovered || backPressed) theme.colors.value else theme.colors.line
            val fg = if (backHovered || backPressed) theme.colors.boneHi else theme.colors.text
            val pressOffset = if (backPressed) -1f else 0f

            OutGameDraw.fillRect(batch, white, x, y + pressOffset, width, height, bg, parentAlpha)
            OutGameDraw.strokeRect(batch, white, x, y + pressOffset, width, height, 1f, border, parentAlpha)

            val font = fontsProvider()?.command ?: fallbackFontProvider()
            val text = UiStrings.get("nav.back")
            glyphLayout.setText(font, text)
            val prevColor = font.color.cpy()
            font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
            val tx = x + (width - glyphLayout.width) * 0.5f
            val ty = y + pressOffset + (height + glyphLayout.height) * 0.5f
            font.draw(batch, text, tx, ty)
            font.color = prevColor
        }
    }.apply {
        touchable = Touchable.enabled
        addListener(object : InputListener() {
            override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                if (pointer == -1) backHovered = true
            }

            override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                if (pointer == -1) backHovered = false
            }

            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (pointer != 0) return false
                backPressed = true
                return true
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer != 0) return
                val wasPressed = backPressed
                backPressed = false
                if (wasPressed && x in 0f..width && y in 0f..height) {
                    onBack()
                }
            }
        })
    }

    init {
        setTransform(false)
        addActor(backButtonActor)
    }

    fun updateBounds(stageWidth: Float, stageHeight: Float) {
        val inset = widthMode.horizontalInset(stageWidth)
        val barW = max(120f, stageWidth - inset * 2f)
        val barH = 36f
        val barY = stageHeight - 12f - barH
        setBounds(inset, barY, barW, barH)

        val font = fontsProvider()?.command ?: fallbackFontProvider()
        glyphLayout.setText(font, UiStrings.get("nav.back"))
        val btnW = max(84f, (glyphLayout.width + 28f).roundToInt().toFloat())
        backButtonActor.setBounds(0f, 2f, btnW, 32f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val savedColor = batch.packedColor
        val theme = themeProvider.currentTheme()
        val font = fontsProvider()?.subPageTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
        val titleText = UiStrings.get(titleKey)
        glyphLayout.setText(font, titleText)
        val prevFontColor = font.color.cpy()
        font.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
        val tx = x + (width - glyphLayout.width) * 0.5f
        val ty = y + (height + glyphLayout.height) * 0.5f
        font.draw(batch, titleText, tx, ty)
        font.color = prevFontColor
        batch.packedColor = savedColor
        super.draw(batch, parentAlpha)
    }

    internal fun drawnBackAction(): DrawnAction =
        DrawnAction("startup-back", backButtonActor, UiStrings.get("nav.back"), enabled = isVisible)
}

/**
 * Framed solid out-game main panel shell positioned directly below [SubPageTopBar] (`top = 56px, bottom = 20px`).
 */
open class OutGamePanelShell(
    private val skin: Skin,
    private val widthMode: OutGameWidthMode = OutGameWidthMode.FULL_INSET,
    private val themeProvider: UiThemeProvider,
) : Group() {
    init {
        setTransform(false)
    }

    fun updateShellBounds(stageWidth: Float, stageHeight: Float) {
        val inset = widthMode.horizontalInset(stageWidth)
        val shellW = max(200f, stageWidth - inset * 2f)
        val topOffset = 56f
        val bottomOffset = 20f
        val shellH = max(160f, stageHeight - topOffset - bottomOffset)
        setBounds(inset, bottomOffset, shellW, shellH)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val savedColor = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val shadow = theme.outGame.panelShadow
        if (shadow.a > 0f) {
            val softShadow = shadow.cpy().apply { a *= 0.42f }
            OutGameDraw.fillRect(batch, white, x - 3f, y - 6f, width + 6f, height + 4f, softShadow, parentAlpha)
            OutGameDraw.fillRect(batch, white, x - 1f, y - 3f, width + 2f, height + 2f, shadow, parentAlpha)
        }
        OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.solid, parentAlpha)
        batch.packedColor = savedColor
        super.draw(batch, parentAlpha)
        // Draw crisp 1px outer border on top of child fills so internal panes flush cleanly to edges.
        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
        batch.packedColor = savedColor
    }
}
