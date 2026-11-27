package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Centered modal overlay for the 5 low-frequency match rules (`更多设置`, UI Supplement §4.2):
 * 1. `经济倍率` (`0.5× / 1× / 1.5× / 2× / 3×`)
 * 2. `共享视野` (`关 / 开`)
 * 3. `出生点分配` (`固定 / 随机`)
 * 4. `开局单位` (`地图默认 / 标准基地`)
 * 5. `禁止核武` (`关 / 开`)
 *
 * Includes top-right `×` close button, bottom-left `恢复默认`, and bottom-right `确定`.
 */
class MoreMatchSettingsModal(
    private val skin: Skin,
    private val appShell: AppShell,
    private val lobbyState: SkirmishLobbyState,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onRulesUpdated: () -> Unit,
) : Group() {

    private val glyphLayout = GlyphLayout()

    val closeTopButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.close") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { dismiss() },
    )

    val restoreDefaultsButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.restoreDefault") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            lobbyState.restoreMoreRulesDefaults()
            syncFromState()
            onRulesUpdated()
        },
    )

    val confirmButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.confirm") },
        variant = OutGameButton.Variant.PRIMARY,
        onClick = { dismiss() },
    )

    val incomeSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishIncomeMultiplier.entries,
        initialSelected = lobbyState.moreRules.incomeMultiplier,
        labelOf = { it.displayText },
        onSelected = { chosen ->
            lobbyState.moreRules = lobbyState.moreRules.copy(incomeMultiplier = chosen)
            onRulesUpdated()
        },
    )

    val sharedVisionSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = lobbyState.moreRules.sharedVision,
        labelOf = { if (it) UiStrings.get("common.on") else UiStrings.get("common.off") },
        onSelected = { enabled ->
            lobbyState.moreRules = lobbyState.moreRules.copy(sharedVision = enabled)
            onRulesUpdated()
        },
    )

    val spawnAssignmentSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishSpawnAssignment.entries,
        initialSelected = lobbyState.moreRules.spawnAssignment,
        labelOf = { it.localizedLabel() },
        onSelected = { chosen ->
            lobbyState.moreRules = lobbyState.moreRules.copy(spawnAssignment = chosen)
            onRulesUpdated()
        },
    )

    val startingUnitsSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishStartingUnits.entries,
        initialSelected = lobbyState.moreRules.startingUnits,
        labelOf = { it.localizedLabel() },
        onSelected = { chosen ->
            lobbyState.moreRules = lobbyState.moreRules.copy(startingUnits = chosen)
            onRulesUpdated()
        },
    )

    val noNukesSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = lobbyState.moreRules.noNukes,
        labelOf = { if (it) UiStrings.get("common.on") else UiStrings.get("common.off") },
        onSelected = { disabled ->
            lobbyState.moreRules = lobbyState.moreRules.copy(noNukes = disabled)
            onRulesUpdated()
        },
    )

    private var cardX = 0f
    private var cardY = 0f
    private var cardW = 520f
    private var cardH = 356f
    private val rowLabelKeys = listOf(
        "lobby.incomeMultiplier",
        "lobby.sharedVision",
        "lobby.spawnAssignment",
        "lobby.startingUnits",
        "lobby.noNukes",
    )
    private val rowCenterYs = FloatArray(5)

    init {
        setTransform(false)
        isVisible = false
        touchable = Touchable.disabled

        // Clicking scrim outside modal card dismisses modal cleanly
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (!isVisible) return false
                val insideCard = x in cardX..(cardX + cardW) && y in cardY..(cardY + cardH)
                if (!insideCard) {
                    dismiss()
                }
                return true
            }
        })

        addActor(closeTopButton)
        addActor(incomeSegmented)
        addActor(sharedVisionSegmented)
        addActor(spawnAssignmentSegmented)
        addActor(startingUnitsSegmented)
        addActor(noNukesSegmented)
        addActor(restoreDefaultsButton)
        addActor(confirmButton)
    }

    fun open() {
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        stage?.unfocusAll()
        syncFromState()
        isVisible = true
        touchable = Touchable.enabled
        appShell.openModal(AppShell.MODAL_MORE_SETTINGS) {
            isVisible = false
            touchable = Touchable.disabled
            onRulesUpdated()
        }
        toFront()
    }

    fun dismiss() {
        if (appShell.activeModalId == AppShell.MODAL_MORE_SETTINGS || isVisible) {
            if (!appShell.closeModal(AppShell.MODAL_MORE_SETTINGS)) {
                isVisible = false
                touchable = Touchable.disabled
                onRulesUpdated()
            }
        }
    }

    fun syncFromState() {
        val r = lobbyState.moreRules
        incomeSegmented.setOptionsAndSelection(SkirmishIncomeMultiplier.entries, r.incomeMultiplier)
        sharedVisionSegmented.setOptionsAndSelection(listOf(false, true), r.sharedVision)
        spawnAssignmentSegmented.setOptionsAndSelection(SkirmishSpawnAssignment.entries, r.spawnAssignment)
        startingUnitsSegmented.setOptionsAndSelection(SkirmishStartingUnits.entries, r.startingUnits)
        noNukesSegmented.setOptionsAndSelection(listOf(false, true), r.noNukes)
        layoutModal()
    }

    fun updateModalBounds(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        layoutModal()
    }

    private fun layoutModal() {
        if (width <= 0f || height <= 0f) return
        cardW = 524f.coerceAtMost(max(320f, width - 48f))
        cardH = 356f.coerceAtMost(max(280f, height - 48f))
        cardX = ((width - cardW) * 0.5f).roundToInt().toFloat()
        cardY = ((height - cardH) * 0.5f).roundToInt().toFloat()

        val padX = 22f
        closeTopButton.setBounds(cardX + cardW - padX - 28f, cardY + cardH - 39f, 28f, 28f)

        val controls = listOf(
            incomeSegmented,
            sharedVisionSegmented,
            spawnAssignmentSegmented,
            startingUnitsSegmented,
            noNukesSegmented,
        )
        val headerH = 50f
        val footerH = 58f
        val bodyH = cardH - headerH - footerH
        val rowStep = bodyH / controls.size.toFloat()
        val ctrlW = (cardW * 0.64f).roundToInt().toFloat().coerceIn(220f, 336f)
        val ctrlX = cardX + cardW - padX - ctrlW
        val ctrlH = 31f

        for (i in controls.indices) {
            val centerY = cardY + cardH - headerH - (i + 0.5f) * rowStep
            rowCenterYs[i] = centerY
            controls[i].setBounds(ctrlX, (centerY - ctrlH * 0.5f).roundToInt().toFloat(), ctrlW, ctrlH)
        }

        val font = fontsProvider()?.command ?: fallbackFontProvider()
        glyphLayout.setText(font, UiStrings.get("common.restoreDefault"))
        val resetW = max(86f, (glyphLayout.width + 24f).roundToInt().toFloat())
        restoreDefaultsButton.setBounds(cardX + padX, cardY + 14f, resetW, 31f)
        confirmButton.setBounds(cardX + cardW - padX - 76f, cardY + 14f, 76f, 31f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (!isVisible) return
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")

        // Fullscreen modal scrim
        OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.modalScrim, parentAlpha)

        // Centered modal card with subtle shadow + border
        val shadow = theme.outGame.panelShadow
        if (shadow.a > 0f) {
            OutGameDraw.fillRect(batch, white, x + cardX - 2f, y + cardY - 6f, cardW + 4f, cardH + 4f, shadow, parentAlpha)
        }
        OutGameDraw.fillRect(batch, white, x + cardX, y + cardY, cardW, cardH, theme.colors.solid, parentAlpha)

        val headerDivY = y + cardY + cardH - 50f
        val footerDivY = y + cardY + 58f
        OutGameDraw.fillRect(batch, white, x + cardX, headerDivY, cardW, 1f, theme.colors.line, parentAlpha)
        OutGameDraw.fillRect(batch, white, x + cardX, footerDivY, cardW, 1f, theme.colors.line, parentAlpha)

        // Modal title `更多设置`
        val titleFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
        val titleText = UiStrings.get("lobby.moreSettings")
        glyphLayout.setText(titleFont, titleText)
        val prevTitle = titleFont.color.cpy()
        val strong = theme.outGame.textStrong
        titleFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
        titleFont.draw(batch, titleText, x + cardX + 22f, headerDivY + (50f + glyphLayout.height) * 0.5f)
        titleFont.color = prevTitle

        // Row labels
        val labelFont = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
        val prevLbl = labelFont.color.cpy()
        labelFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
        for (i in rowLabelKeys.indices) {
            val txt = UiStrings.get(rowLabelKeys[i])
            glyphLayout.setText(labelFont, txt)
            labelFont.draw(batch, txt, x + cardX + 22f, y + rowCenterYs[i] + glyphLayout.height * 0.5f)
        }
        labelFont.color = prevLbl

        batch.packedColor = saved
        super.draw(batch, parentAlpha)
        OutGameDraw.strokeRect(batch, white, x + cardX, y + cardY, cardW, cardH, 1f, theme.colors.popupBorder, parentAlpha)
        batch.packedColor = saved
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        return listOf(
            DrawnAction("more-settings-close", closeTopButton, UiStrings.get("common.close"), enabled = true),
            DrawnAction("more-settings-income", incomeSegmented, lobbyState.moreRules.incomeMultiplier.displayText, enabled = true),
            DrawnAction("match-shared-vision", sharedVisionSegmented, if (lobbyState.moreRules.sharedVision) UiStrings.get("common.on") else UiStrings.get("common.off"), enabled = true),
            DrawnAction("more-settings-spawn", spawnAssignmentSegmented, lobbyState.moreRules.spawnAssignment.localizedLabel(), enabled = true),
            DrawnAction("more-settings-units", startingUnitsSegmented, lobbyState.moreRules.startingUnits.localizedLabel(), enabled = true),
            DrawnAction("no-nukes", noNukesSegmented, if (lobbyState.moreRules.noNukes) UiStrings.get("common.on") else UiStrings.get("common.off"), enabled = true),
            DrawnAction("more-settings-reset", restoreDefaultsButton, UiStrings.get("common.restoreDefault"), enabled = true),
            DrawnAction("more-settings-confirm", confirmButton, UiStrings.get("common.confirm"), enabled = true),
        )
    }
}
