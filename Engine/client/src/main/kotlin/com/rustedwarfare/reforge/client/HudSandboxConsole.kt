package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import kotlin.math.ceil

/**
 * Redesigned development-mode sandbox console docked bottom-center of the battlefield (HUD Spec §12).
 *
 * - Width: 460 ref units, centered over the battlefield (`(stageW - SIDEBAR_WIDTH) / 2 - 230`), bottom = 8.
 * - Top row: pick mode buttons (`生成` / `摧毁` / `调整生命` + repeat spawn).
 * - Middle row: dark well 30-tall monospace command TextField (keeps `sandbox-command-input` action ID).
 * - Bottom row: compact feedback/hint caption (`Esc 关闭 · 回车提交`).
 */
internal class HudSandboxConsole(
    private val skin: Skin,
    val field: TextField,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onPickSpawnOnce: () -> Unit,
    private val onPickSpawnRepeat: () -> Unit,
    private val onPickDestroy: () -> Unit,
    private val onPickHealth: () -> Unit,
) : Group() {

    private val glyphLayout = GlyphLayout()
    private val panelRect = Rectangle()
    private var language: UiLanguage = UiLanguage.ZH
    private var feedbackText: String = ""

    private class PickChipActor(
        val actionId: String,
        val labelKey: String,
        private val onClick: () -> Unit,
    ) : Actor() {
        var hovered: Boolean = false
            private set

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                    pointer == 0 && button == Input.Buttons.LEFT

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer == 0 && button == Input.Buttons.LEFT && x in 0f..width && y in 0f..height && !event.isTouchFocusCancel) {
                        onClick()
                    }
                }

                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }
            })
        }
    }

    private val bgBlocker = Actor().apply {
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = true
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float) = true
        })
    }

    private val pickButtons = listOf(
        PickChipActor("sandbox-pick-spawn", "pick.spawn", onPickSpawnOnce),
        PickChipActor("sandbox-pick-destroy", "pick.destroy", onPickDestroy),
        PickChipActor("sandbox-pick-health", "pick.health", onPickHealth),
        PickChipActor("sandbox-pick-repeat", "pick.repeat", onPickSpawnRepeat),
    )

    init {
        isVisible = false
        isTransform = false
        touchable = Touchable.childrenOnly
        addActor(bgBlocker)
        pickButtons.forEach(::addActor)
        addActor(field)
    }

    fun update(
        open: Boolean,
        stageWidth: Float,
        stageHeight: Float,
        feedback: String,
        lang: UiLanguage,
    ) {
        language = lang
        feedbackText = feedback
        isVisible = open
        if (!open) {
            panelRect.set(0f, 0f, 0f, 0f)
            return
        }

        val rect = computePanelRect(stageWidth, hasFeedback = feedback.isNotBlank())
        val x = rect.x
        val y = rect.y
        val w = rect.width
        val h = rect.height
        panelRect.set(rect)
        setBounds(0f, 0f, stageWidth, stageHeight)

        bgBlocker.setBounds(x, y, w, h)

        val innerX = x + PAD
        val innerW = w - PAD * 2f
        val topY = y + h - PAD

        // Top pick chips row
        val chipFont = bodyFont()
        var px = innerX
        val pickY = topY - PICK_H
        for (btn in pickButtons) {
            val label = labelFor(btn.labelKey, lang)
            glyphLayout.setText(chipFont, label)
            val bw = ceil(glyphLayout.width + 20f).coerceAtLeast(52f)
            btn.setBounds(px, pickY, bw, PICK_H)
            px += bw + 5f
        }

        // Middle command TextField (styled with dark well background + monospace font)
        val inputY = pickY - GAP - INPUT_H
        syncFieldStyle()
        field.setBounds(innerX, inputY, innerW, INPUT_H)
    }

    fun containsPoint(stageX: Float, stageY: Float): Boolean =
        isVisible && panelRect.contains(stageX, stageY)

    fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        val list = mutableListOf<DrawnAction>()
        list += DrawnAction("sandbox-command-input", field, field.text, enabled = true)
        for (btn in pickButtons) {
            list += DrawnAction(btn.actionId, btn, labelFor(btn.labelKey, language), enabled = true)
        }
        return list
    }

    private fun syncFieldStyle() {
        val mono = monoFont()
        val currentStyle = field.style
        if (currentStyle.font !== mono || currentStyle.fontColor != UiTokens.TEXT) {
            val style = TextField.TextFieldStyle(currentStyle).apply {
                font = mono
                fontColor = UiTokens.TEXT
                focusedFontColor = UiTokens.TEXT
                messageFont = mono
                messageFontColor = UiTokens.DIM
                background = skin.newDrawable("white", UiTokens.WELL)
                focusedBackground = skin.newDrawable("white", UiTokens.WELL)
                cursor = skin.newDrawable("white", UiTokens.VALUE)
                selection = skin.newDrawable("white", UiTokens.CELL)
            }
            style.background?.leftWidth = 8f
            style.background?.rightWidth = 8f
            style.focusedBackground?.leftWidth = 8f
            style.focusedBackground?.rightWidth = 8f
            field.style = style
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (!isVisible) return
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val alpha = parentAlpha * color.a

        // Outer panel box (#252A2E solid + #727B81 border)
        fillRect(batch, white, panelRect.x, panelRect.y, panelRect.width, panelRect.height, UiTokens.SOLID, alpha)
        strokeRect(batch, white, panelRect.x, panelRect.y, panelRect.width, panelRect.height, BORDER_COLOR, alpha)

        // Pick mode chips
        val chipFont = bodyFont()
        for (btn in pickButtons) {
            val bg = if (btn.hovered) CELL_HOVER else UiTokens.CELL
            fillRect(batch, white, btn.x, btn.y, btn.width, btn.height, bg, alpha)
            drawTextCenter(batch, chipFont, labelFor(btn.labelKey, language), btn.x + btn.width * 0.5f, btn.y + btn.height * 0.5f, UiTokens.TEXT, alpha)
        }

        // Draw TextField (via super.draw) and stroke its well border
        super.draw(batch, parentAlpha)
        strokeRect(batch, white, field.x, field.y, field.width, field.height, UiTokens.LINE, alpha)

        // Feedback line (optional) & footnote
        val innerX = panelRect.x + PAD
        if (feedbackText.isNotBlank()) {
            drawTextLeft(batch, smallFont(), feedbackText, innerX, panelRect.y + PAD + FOOT_H + 4f + FEEDBACK_H * 0.5f, UiTokens.VALUE, alpha)
        }
        drawTextLeft(batch, smallFont(), UiStrings.get("escClose", language), innerX, panelRect.y + PAD + FOOT_H * 0.5f, UiTokens.DIM, alpha)

        batch.packedColor = orig
    }

    private fun labelFor(key: String, lang: UiLanguage): String = UiStrings.get(key, lang)

    private fun bodyFont(): BitmapFont = fontsProvider()?.command ?: fallbackFontProvider()
    private fun monoFont(): BitmapFont = fontsProvider()?.countMono ?: fallbackFontProvider()
    private fun smallFont(): BitmapFont = fontsProvider()?.small ?: fallbackFontProvider()

    private fun fillRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(c.r, c.g, c.b, c.a * alpha)
        batch.draw(white, x, y, w, h)
    }

    private fun strokeRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        fillRect(batch, white, x, y + h - 1f, w, 1f, c, alpha)
        fillRect(batch, white, x, y, w, 1f, c, alpha)
        fillRect(batch, white, x, y, 1f, h, c, alpha)
        fillRect(batch, white, x + w - 1f, y, 1f, h, c, alpha)
    }

    private fun drawTextLeft(batch: Batch, font: BitmapFont, text: String, x: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, x, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawTextCenter(batch: Batch, font: BitmapFont, text: String, centerX: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, centerX - glyphLayout.width * 0.5f, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    companion object {
        const val CONSOLE_WIDTH: Float = 460f
        val PICK_ACTION_IDS: List<String> = listOf(
            "sandbox-pick-spawn",
            "sandbox-pick-destroy",
            "sandbox-pick-health",
            "sandbox-pick-repeat",
        )
        const val COMMAND_INPUT_ACTION_ID: String = "sandbox-command-input"

        fun computePanelRect(stageWidth: Float, hasFeedback: Boolean): Rectangle {
            val bfWidth = (stageWidth - UiTokens.SIDEBAR_WIDTH).coerceAtLeast(320f)
            val w = CONSOLE_WIDTH.coerceAtMost(bfWidth - 16f)
            val h = PAD * 2f + PICK_H + GAP + INPUT_H + GAP + FOOT_H + if (hasFeedback) (FEEDBACK_H + 4f) else 0f
            val x = ((bfWidth - w) * 0.5f).coerceAtLeast(UiTokens.SCREEN_MARGIN)
            val y = UiTokens.SCREEN_MARGIN
            return Rectangle(x, y, w, h)
        }
        private const val PAD: Float = 8f
        private const val GAP: Float = 6f
        private const val PICK_H: Float = 26f
        private const val INPUT_H: Float = 30f
        private const val FOOT_H: Float = 15f
        private const val FEEDBACK_H: Float = 16f
        private val BORDER_COLOR: Color = UiTokens.hex(0x727B81)
        private val CELL_HOVER: Color = UiTokens.hex(0x3D454B)
    }
}
