package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen 10-second auto-revert confirmation modal for UI scale changes
 * (`AppShell.MODAL_UI_SCALE_CONFIRM`, UI Supplement §7 & `UI-P004-005`).
 *
 * Behavior:
 * - Triggered immediately when the user selects a different UI scale multiplier (`85% / 100% / 110% / 125%`).
 * - Applies the new scale live for visual inspection while running a 10.0-second countdown.
 * - Clicking `保留` (`keep`) persists the new scale to `UserSettingsStore` and closes the modal.
 * - Clicking `还原` (`revert`), pressing `Esc` / Android `BACK`, or letting the 10.0-second timer expire
 *   automatically restores [previousScale] without persisting the unconfirmed multiplier.
 */
class UiScaleConfirmModal(
    private val skin: Skin,
    private val appShell: AppShell,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onConfirmKeep: (confirmedScale: Float) -> Unit,
    private val onRevertScale: (revertedScale: Float) -> Unit,
) : Group() {

    private val glyphLayout = GlyphLayout()

    var previousScale: Float = 1.0f
        private set

    var proposedScale: Float = 1.0f
        private set

    var remainingSeconds: Float = AUTO_REVERT_SECONDS
        private set

    val isOpen: Boolean
        get() = isVisible

    private var cardX = 0f
    private var cardY = 0f
    private var cardW = 560f
    private var cardH = 224f
    private var closingGuard = false

    val revertButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("settings.scaleModal.revert") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { revertAndClose() },
    )

    val keepButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("settings.scaleModal.keep") },
        variant = OutGameButton.Variant.PRIMARY,
        onClick = { confirmKeepAndClose() },
    )

    private val scrimAndCardActor = object : Actor() {
        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    // Consume all background clicks so underlying settings cannot be mutated while modal is open
                    return true
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")

            // Full-screen alert scrim (`scaleAlertScrim`)
            OutGameDraw.fillRect(batch, white, 0f, 0f, width, height, theme.outGame.scaleAlertScrim, parentAlpha)

            // Centered warning card (`amber` border + 4px `amber` top accent bar)
            OutGameDraw.fillRect(batch, white, cardX + 2f, cardY - 6f, cardW, cardH, theme.outGame.panelShadow, parentAlpha)
            OutGameDraw.fillRect(batch, white, cardX, cardY, cardW, cardH, theme.colors.solid, parentAlpha)
            OutGameDraw.strokeRect(batch, white, cardX, cardY, cardW, cardH, 1f, theme.colors.amber, parentAlpha)
            OutGameDraw.fillRect(batch, white, cardX, cardY + cardH - 4f, cardW, 4f, theme.colors.amber, parentAlpha)

            val padX = 30f
            val innerW = cardW - padX * 2f

            // Heading: `是否保留新的 UI 缩放比例（125%）？`
            val titleFont = fontsProvider()?.subPageTitle ?: fontsProvider()?.sectionTitle ?: fallbackFontProvider()
            val titleText = UiStrings.format(
                "settings.scaleModal.title",
                "SCALE" to formatPercent(proposedScale),
            )
            val prevTitle = titleFont.color.cpy()
            titleFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
            val wrappedTitle = OutGameDraw.wrapTextCjk(titleFont, titleText, innerW, glyphLayout)
            var titleY = cardY + cardH - 30f
            for (line in wrappedTitle.take(2)) {
                titleFont.draw(batch, line, cardX + padX, titleY)
                titleY -= 26f
            }
            titleFont.color = prevTitle

            // Countdown box (`sa-timer`)
            val timerBoxH = 66f
            val timerBoxY = cardY + 78f
            OutGameDraw.fillRect(batch, white, cardX + padX, timerBoxY, innerW, timerBoxH, theme.colors.well, parentAlpha)
            OutGameDraw.strokeRect(batch, white, cardX + padX, timerBoxY, innerW, timerBoxH, 1f, theme.colors.hair, parentAlpha)

            val hintFont = fontsProvider()?.body ?: fallbackFontProvider()
            val prevText = UiStrings.format(
                "settings.scaleModal.autoRevert",
                "PREV" to formatPercent(previousScale),
            )
            val prevHint = hintFont.color.cpy()
            hintFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
            hintFont.draw(batch, prevText, cardX + padX + 16f, timerBoxY + timerBoxH - 16f)
            hintFont.color = prevHint

            val displaySec = ceil(remainingSeconds.coerceAtLeast(0f).toDouble()).toInt().coerceIn(0, 10)
            val secFormatted = String.format(Locale.ROOT, "%02d", displaySec)
            val secText = UiStrings.format("settings.scaleModal.seconds", "SEC" to secFormatted)
            val secFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.creditsMono ?: fallbackFontProvider()
            glyphLayout.setText(secFont, secText)
            val prevSec = secFont.color.cpy()
            secFont.setColor(theme.colors.amber.r, theme.colors.amber.g, theme.colors.amber.b, theme.colors.amber.a * parentAlpha)
            secFont.draw(batch, secText, cardX + padX + innerW - 16f - glyphLayout.width, timerBoxY + timerBoxH - 14f)
            secFont.color = prevSec

            // Countdown progress bar (`8px`, `rust` fill proportional to remainingSeconds / 10s)
            val barX = cardX + padX + 16f
            val barY = timerBoxY + 14f
            val barW = innerW - 32f
            val barH = 8f
            OutGameDraw.fillRect(batch, white, barX, barY, barW, barH, theme.colors.cell, parentAlpha)
            val frac = (remainingSeconds / AUTO_REVERT_SECONDS).coerceIn(0f, 1f)
            val fillW = (barW * frac).roundToInt().toFloat()
            if (fillW > 0f) {
                OutGameDraw.fillRect(batch, white, barX, barY, fillW, barH, theme.colors.rust, parentAlpha)
            }

            batch.packedColor = saved
        }
    }

    init {
        setTransform(false)
        isVisible = false
        addActor(scrimAndCardActor)
        addActor(revertButton)
        addActor(keepButton)
    }

    fun open(previous: Float, proposed: Float) {
        previousScale = previous
        proposedScale = proposed
        remainingSeconds = AUTO_REVERT_SECONDS
        isVisible = true
        closingGuard = false
        appShell.openModal(AppShell.MODAL_UI_SCALE_CONFIRM) {
            if (!closingGuard && isVisible) {
                closingGuard = true
                isVisible = false
                onRevertScale(previousScale)
                closingGuard = false
            }
        }
        if (width > 0f && height > 0f) {
            updateModalBounds(width, height)
        }
        toFront()
    }

    fun confirmKeepAndClose() {
        if (!isVisible || closingGuard) return
        closingGuard = true
        isVisible = false
        appShell.closeModal(AppShell.MODAL_UI_SCALE_CONFIRM)
        val confirmed = proposedScale
        closingGuard = false
        onConfirmKeep(confirmed)
    }

    fun revertAndClose() {
        if (!isVisible || closingGuard) return
        closingGuard = true
        isVisible = false
        appShell.closeModal(AppShell.MODAL_UI_SCALE_CONFIRM)
        val target = previousScale
        closingGuard = false
        onRevertScale(target)
    }

    fun stepTimer(deltaSeconds: Float) {
        if (!isVisible || deltaSeconds <= 0f || !deltaSeconds.isFinite()) return
        remainingSeconds = (remainingSeconds - deltaSeconds).coerceAtLeast(0f)
        if (remainingSeconds <= 0f) {
            revertAndClose()
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        stepTimer(delta)
    }

    fun updateModalBounds(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        scrimAndCardActor.setBounds(0f, 0f, stageWidth, stageHeight)

        cardW = min(560f, max(420f, stageWidth - 48f)).roundToInt().toFloat()
        cardH = 228f
        cardX = ((stageWidth - cardW) * 0.5f).roundToInt().toFloat()
        cardY = ((stageHeight - cardH) * 0.5f).roundToInt().toFloat()

        val btnH = 40f
        val btnW = 104f
        val btnY = cardY + 22f
        val rightEdge = cardX + cardW - 30f
        keepButton.setBounds(rightEdge - btnW, btnY, btnW, btnH)
        revertButton.setBounds(rightEdge - btnW * 2f - 10f, btnY, btnW, btnH)
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        return listOf(
            DrawnAction("ui-scale-revert", revertButton, UiStrings.get("settings.scaleModal.revert"), enabled = true),
            DrawnAction("ui-scale-keep", keepButton, UiStrings.get("settings.scaleModal.keep"), enabled = true),
        )
    }

    companion object {
        const val AUTO_REVERT_SECONDS: Float = 10.0f

        fun formatPercent(multiplier: Float): String =
            "${(multiplier * 100f).roundToInt()}%"
    }
}
