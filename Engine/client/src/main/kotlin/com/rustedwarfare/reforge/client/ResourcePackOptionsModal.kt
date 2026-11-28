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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Centered constructivist modal for configuring declarative presentation variant options (`options` in `pack.json`,
 * ADR-014 & UI Supplement §7).
 *
 * - Opened via the vector gear button on any enabled resource pack card with non-empty [DualColumnPackEntry.options]
 *   (including bottom-locked `reforge:base` which hosts the 3 built-in UI themes `dark / white / bone`).
 * - In-match (`inMatchMode = true`), pure [UiTheme] style options (`!option.affectsMediaAssets`) remain live-editable,
 *   whereas media-swapping options (`option.affectsMediaAssets`) are read-only locked with an amber banner.
 */
class ResourcePackOptionsModal(
    private val skin: Skin,
    private val appShell: AppShell,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons?,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val optionValuesProvider: (packId: String) -> Map<String, String>,
    private val onSelectOption: (packId: String, optionId: String, value: String) -> Unit,
    private val onResetDefaults: (packId: String) -> Unit,
) : Group() {

    private sealed class OptionControlSlot(
        val option: ResourcePackOption,
        val actor: Actor,
    ) {
        abstract val selectedValue: String
        abstract var enabled: Boolean
        abstract fun setSelectedSilently(value: String)
        abstract fun selectAndNotify(value: String): Boolean

        class SegmentedSlot(
            option: ResourcePackOption,
            val control: SegmentedControl<String>,
        ) : OptionControlSlot(option, control) {
            override val selectedValue: String get() = control.selected
            override var enabled: Boolean
                get() = control.enabled
                set(value) {
                    control.enabled = value
                }

            override fun setSelectedSilently(value: String) {
                control.setSelectedSilently(value)
            }

            override fun selectAndNotify(value: String): Boolean {
                if (!control.enabled || value !in control.options) return false
                control.select(value)
                return true
            }
        }

        class DropdownSlot(
            option: ResourcePackOption,
            val control: UnifiedDropdown<String>,
        ) : OptionControlSlot(option, control) {
            override val selectedValue: String get() = control.selected
            override var enabled: Boolean
                get() = control.enabled
                set(value) {
                    control.enabled = value
                }

            override fun setSelectedSilently(value: String) {
                control.setSelectedSilently(value)
            }

            override fun selectAndNotify(value: String): Boolean {
                if (!control.enabled || value !in control.options) return false
                control.select(value)
                return true
            }
        }
    }

    private val glyphLayout = GlyphLayout()
    private val optionSlots = ArrayList<OptionControlSlot>()
    private val rowCenterYs = ArrayList<Float>()

    var currentEntry: DualColumnPackEntry? = null
        private set

    val isOpen: Boolean
        get() = currentEntry != null && isVisible

    var inMatchMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                syncOptionEnablement()
                if (width > 0f && height > 0f) updateModalBounds(width, height)
            }
        }

    private var cardX = 0f
    private var cardY = 0f
    private var cardW = 560f
    private var cardH = 260f
    private var noticeBoxY = 0f
    private var noticeBoxH = 0f

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
            val entry = currentEntry ?: return@OutGameButton
            onResetDefaults(entry.id)
            syncSelectionsFromStore()
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

    init {
        setTransform(false)
        isVisible = false
        touchable = Touchable.disabled

        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (!isOpen || pointer != 0) return false
                val insideCard = x >= cardX && x <= cardX + cardW && y >= cardY && y <= cardY + cardH
                if (!insideCard) {
                    dismiss()
                }
                return true
            }

            override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                if (!isOpen) return false
                stage?.scrollFocus = this@ResourcePackOptionsModal
                return true
            }

            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean = isOpen
        })

        addActor(closeTopButton)
        addActor(restoreDefaultsButton)
        addActor(confirmButton)
    }

    fun open(entry: DualColumnPackEntry, inMatch: Boolean) {
        if (entry.options.isEmpty()) return
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        currentEntry = entry
        inMatchMode = inMatch
        rebuildOptionControls(entry)
        isVisible = true
        touchable = Touchable.enabled
        toFront()
        appShell.openModal(MODAL_ID) {
            closeInternal()
        }
        if (width > 0f && height > 0f) {
            updateModalBounds(width, height)
        }
    }

    fun dismiss() {
        if (!isOpen) return
        if (appShell.activeModalId == MODAL_ID) {
            appShell.closeModal(MODAL_ID)
        } else {
            closeInternal()
        }
    }

    private fun closeInternal() {
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        clearOptionControls()
        currentEntry = null
        isVisible = false
        touchable = Touchable.disabled
    }

    private fun clearOptionControls() {
        for (slot in optionSlots) {
            slot.actor.remove()
        }
        optionSlots.clear()
        rowCenterYs.clear()
    }

    private fun rebuildOptionControls(entry: DualColumnPackEntry) {
        clearOptionControls()
        val activeValues = optionValuesProvider(entry.id)
        for (opt in entry.options) {
            val initialVal = opt.resolveValueOrDefault(activeValues[opt.id])
            val slot: OptionControlSlot = when {
                opt.type == ResourcePackOptionType.BOOLEAN -> {
                    val seg = SegmentedControl(
                        skin = skin,
                        themeProvider = themeProvider,
                        fontsProvider = fontsProvider,
                        fallbackFontProvider = fallbackFontProvider,
                        options = listOf("false", "true"),
                        initialSelected = initialVal,
                        labelOf = { v -> UiStrings.get(if (v == "true") "common.on" else "common.off") },
                        onSelected = { chosen ->
                            onSelectOption(entry.id, opt.id, chosen)
                        },
                    )
                    OptionControlSlot.SegmentedSlot(opt, seg)
                }
                opt.choices.size <= 3 -> {
                    val values = opt.choices.map { it.value }
                    val seg = SegmentedControl(
                        skin = skin,
                        themeProvider = themeProvider,
                        fontsProvider = fontsProvider,
                        fallbackFontProvider = fallbackFontProvider,
                        options = values,
                        initialSelected = initialVal,
                        labelOf = { v -> opt.choices.firstOrNull { it.value == v }?.localizedLabel() ?: v },
                        onSelected = { chosen ->
                            onSelectOption(entry.id, opt.id, chosen)
                        },
                    )
                    OptionControlSlot.SegmentedSlot(opt, seg)
                }
                else -> {
                    val values = opt.choices.map { it.value }
                    val dd = UnifiedDropdown(
                        skin = skin,
                        themeProvider = themeProvider,
                        iconsProvider = iconsProvider,
                        fontsProvider = fontsProvider,
                        fallbackFontProvider = fallbackFontProvider,
                        options = values,
                        initialSelected = initialVal,
                        labelOf = { v -> opt.choices.firstOrNull { it.value == v }?.localizedLabel() ?: v },
                        useMonoFont = false,
                        onSelected = { chosen ->
                            onSelectOption(entry.id, opt.id, chosen)
                        },
                    )
                    OptionControlSlot.DropdownSlot(opt, dd)
                }
            }
            optionSlots += slot
            addActor(slot.actor)
        }
        syncOptionEnablement()
    }

    fun syncSelectionsFromStore() {
        val entry = currentEntry ?: return
        val activeValues = optionValuesProvider(entry.id)
        for (slot in optionSlots) {
            slot.setSelectedSilently(slot.option.resolveValueOrDefault(activeValues[slot.option.id]))
        }
        syncOptionEnablement()
    }

    private fun syncOptionEnablement() {
        val entry = currentEntry
        for (slot in optionSlots) {
            slot.enabled = !inMatchMode || !slot.option.affectsMediaAssets
        }
        restoreDefaultsButton.enabled = entry != null && (!inMatchMode || entry.options.any { !it.affectsMediaAssets })
    }

    fun selectOptionForTest(optionId: String, value: String): Boolean {
        val slot = optionSlots.firstOrNull { it.option.id == optionId } ?: return false
        return slot.selectAndNotify(value)
    }

    fun isOptionControlEnabled(optionId: String): Boolean =
        optionSlots.firstOrNull { it.option.id == optionId }?.enabled == true

    fun currentSelectedValue(optionId: String): String? =
        optionSlots.firstOrNull { it.option.id == optionId }?.selectedValue

    fun updateModalBounds(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        val entry = currentEntry
        if (entry == null) return

        val hasLockedMedia = inMatchMode && entry.options.any { it.affectsMediaAssets }
        noticeBoxH = if (hasLockedMedia) 32f else 0f
        val noticeExtra = if (hasLockedMedia) 42f else 0f
        val rowsCount = max(1, optionSlots.size)
        val rowStep = 48f
        val headerH = 44f
        val footerH = 54f

        cardW = min(560f, max(380f, stageWidth - 48f)).roundToInt().toFloat()
        cardH = min(stageHeight - 40f, headerH + footerH + 20f + noticeExtra + rowsCount * rowStep).roundToInt().toFloat()
        cardX = ((stageWidth - cardW) * 0.5f).roundToInt().toFloat()
        cardY = ((stageHeight - cardH) * 0.5f).roundToInt().toFloat()

        closeTopButton.setBounds(cardX + cardW - 40f, cardY + cardH - 36f, 28f, 28f)

        val bodyTopY = cardY + cardH - headerH - 10f
        val rowsStartTopY = if (hasLockedMedia) {
            noticeBoxY = bodyTopY - noticeBoxH
            noticeBoxY - 10f
        } else {
            noticeBoxY = bodyTopY
            bodyTopY
        }

        val padX = 22f
        val innerW = cardW - padX * 2f
        val ctrlW = min(296f, innerW * 0.56f).roundToInt().toFloat()
        val ctrlH = 32f

        rowCenterYs.clear()
        optionSlots.forEachIndexed { idx, slot ->
            val rTop = rowsStartTopY - idx * rowStep
            val rCenter = rTop - rowStep * 0.5f
            rowCenterYs += rCenter
            slot.actor.setBounds(cardX + padX + innerW - ctrlW, (rCenter - ctrlH * 0.5f).roundToInt().toFloat(), ctrlW, ctrlH)
        }

        val font = fontsProvider()?.command ?: fallbackFontProvider()
        glyphLayout.setText(font, UiStrings.get("common.restoreDefault"))
        val restoreW = max(96f, (glyphLayout.width + 26f).roundToInt().toFloat())
        restoreDefaultsButton.setBounds(cardX + padX, cardY + 12f, restoreW, 32f)
        confirmButton.setBounds(cardX + cardW - padX - 92f, cardY + 12f, 92f, 32f)
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val entry = currentEntry ?: return
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")

        // Scrim + centered constructivist modal shell
        OutGameDraw.fillRect(batch, white, 0f, 0f, width, height, theme.outGame.modalScrim, parentAlpha)
        OutGameDraw.fillRect(batch, white, cardX + 2f, cardY - 6f, cardW, cardH, theme.outGame.panelShadow, parentAlpha)
        OutGameDraw.fillRect(batch, white, cardX, cardY, cardW, cardH, theme.colors.solid, parentAlpha)

        val headerH = 44f
        val headerBottomY = cardY + cardH - headerH
        OutGameDraw.fillRect(batch, white, cardX + 1f, headerBottomY, cardW - 2f, headerH - 1f, theme.outGame.railSolid, parentAlpha)
        OutGameDraw.fillRect(batch, white, cardX + 1f, headerBottomY, cardW - 2f, 1f, theme.colors.line, parentAlpha)

        val footerTopY = cardY + 54f
        OutGameDraw.fillRect(batch, white, cardX + 1f, footerTopY, cardW - 2f, 1f, theme.colors.hair, parentAlpha)
        OutGameDraw.strokeRect(batch, white, cardX, cardY, cardW, cardH, 1f, theme.colors.line, parentAlpha)

        val padX = 22f
        val hdFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
        val titleText = OutGameDraw.ellipsizeMiddle(
            hdFont,
            UiStrings.format("settings.packs.optionsTitleFormat", "NAME" to entry.title),
            (cardW - padX - 56f).coerceAtLeast(120f),
            glyphLayout,
        )
        glyphLayout.setText(hdFont, titleText)
        val prevHd = hdFont.color.cpy()
        hdFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
        hdFont.draw(batch, titleText, cardX + padX, headerBottomY + (headerH + glyphLayout.height) * 0.5f)
        hdFont.color = prevHd

        if (noticeBoxH > 0f) {
            val innerW = cardW - padX * 2f
            OutGameDraw.fillRect(batch, white, cardX + padX, noticeBoxY, innerW, noticeBoxH, theme.colors.well, parentAlpha)
            OutGameDraw.strokeRect(batch, white, cardX + padX, noticeBoxY, innerW, noticeBoxH, 1f, theme.colors.amber, parentAlpha)
            val noteFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            val noteStr = UiStrings.get("settings.packs.inMatchMediaLocked")
            glyphLayout.setText(noteFont, noteStr)
            val prevN = noteFont.color.cpy()
            noteFont.setColor(theme.colors.amber.r, theme.colors.amber.g, theme.colors.amber.b, theme.colors.amber.a * parentAlpha)
            noteFont.draw(batch, noteStr, cardX + padX + 10f, noticeBoxY + (noticeBoxH + glyphLayout.height) * 0.5f)
            noteFont.color = prevN
        }

        val rowFont = fontsProvider()?.body ?: fallbackFontProvider()
        val innerW = cardW - padX * 2f
        val maxLblW = max(80f, innerW * 0.42f)
        optionSlots.forEachIndexed { idx, slot ->
            val cy = rowCenterYs.getOrNull(idx) ?: return@forEachIndexed
            if (idx < optionSlots.lastIndex) {
                OutGameDraw.fillRect(batch, white, cardX + padX, (cy - 24f).roundToInt().toFloat(), innerW, 1f, theme.colors.hair, parentAlpha)
            }
            val rawLabel = slot.option.localizedLabel()
            val fitLbl = OutGameDraw.ellipsizeMiddle(rowFont, rawLabel, maxLblW, glyphLayout)
            glyphLayout.setText(rowFont, fitLbl)
            val prevR = rowFont.color.cpy()
            val col = if (slot.enabled) theme.colors.text else theme.colors.dim
            rowFont.setColor(col.r, col.g, col.b, col.a * parentAlpha)
            rowFont.draw(batch, fitLbl, cardX + padX, cy + glyphLayout.height * 0.5f)
            rowFont.color = prevR
        }

        batch.packedColor = saved
        super.draw(batch, parentAlpha)
    }

    internal fun drawnActions(): List<DrawnAction> {
        val entry = currentEntry ?: return emptyList()
        if (!isVisible) return emptyList()
        val out = ArrayList<DrawnAction>(optionSlots.size + 3)
        out += DrawnAction("pack-options-close", closeTopButton, UiStrings.get("common.close"), enabled = closeTopButton.enabled)
        for (slot in optionSlots) {
            out += DrawnAction(
                "pack-option:${entry.id}:${slot.option.id}",
                slot.actor,
                slot.option.localizedValueLabel(slot.selectedValue),
                enabled = slot.enabled,
            )
        }
        out += DrawnAction("pack-options-restore", restoreDefaultsButton, UiStrings.get("common.restoreDefault"), enabled = restoreDefaultsButton.enabled)
        out += DrawnAction("pack-options-confirm", confirmButton, UiStrings.get("common.confirm"), enabled = confirmButton.enabled)
        return out
    }

    companion object {
        const val MODAL_ID: String = "resource-pack-options"
    }
}
