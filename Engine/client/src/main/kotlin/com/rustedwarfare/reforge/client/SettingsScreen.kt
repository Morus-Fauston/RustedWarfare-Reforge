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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Seven top-level settings categories in [SettingsScreen] (UI Supplement §7 & `UI-P004-005`).
 */
enum class SettingsCategory(val labelKey: String, val navActionId: String) {
    GENERAL("settings.nav.general", "settings-nav-general"),
    AUDIO("settings.nav.audio", "settings-nav-audio"),
    VIDEO("settings.nav.video", "settings-nav-video"),
    CONTROLS("settings.nav.controls", "settings-nav-controls"),
    RESOURCE_PACKS("settings.nav.resourcePacks", "resource-tab"),
    CONTENT_PACKS("settings.nav.contentPacks", "content-tab"),
    ABOUT("settings.nav.about", "settings-nav-about"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)
}

/**
 * Theme-driven constructivist horizontal slider (`0%..100%` or `0.25×..3.00×`, UI Supplement §7).
 */
class OutGameSlider(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    val minValue: Float,
    val maxValue: Float,
    val stepSize: Float,
    initialValue: Float,
    private val valueFormatter: (Float) -> String,
    private val onValueChanged: (Float) -> Unit,
) : Actor() {

    var value: Float = clampAndSnap(initialValue)
        private set

    var enabled: Boolean = true
    private var dragging = false
    private val glyphLayout = GlyphLayout()

    init {
        touchable = Touchable.enabled
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                if (pointer != 0 || !enabled) return false
                dragging = true
                updateFromLocalX(x)
                return true
            }

            override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                if (pointer == 0 && dragging && enabled) {
                    updateFromLocalX(x)
                }
            }

            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer == 0) dragging = false
            }
        })
    }

    fun setValueSilently(next: Float) {
        value = clampAndSnap(next)
    }

    fun setValueAndNotify(next: Float) {
        if (!enabled) return
        val snapped = clampAndSnap(next)
        if (abs(snapped - value) > 1e-4f) {
            value = snapped
            onValueChanged(snapped)
        }
    }

    private fun clampAndSnap(raw: Float): Float {
        val clamped = raw.takeIf(Float::isFinite)?.coerceIn(minValue, maxValue) ?: minValue
        if (stepSize <= 0f) return clamped
        val steps = ((clamped - minValue) / stepSize).roundToInt()
        return (minValue + steps * stepSize).coerceIn(minValue, maxValue)
    }

    private fun updateFromLocalX(localX: Float) {
        val readoutW = 54f
        val trackW = max(40f, width - readoutW - 12f)
        val frac = (localX / trackW).coerceIn(0f, 1f)
        setValueAndNotify(minValue + frac * (maxValue - minValue))
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")

        val readoutW = 54f
        val trackW = max(40f, width - readoutW - 12f)
        val trackH = 6f
        val trackY = y + (height - trackH) * 0.5f

        OutGameDraw.fillRect(batch, white, x, trackY, trackW, trackH, theme.colors.well, parentAlpha)
        val frac = ((value - minValue) / (maxValue - minValue).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
        val fillW = (trackW * frac).roundToInt().toFloat()
        if (fillW > 0f) {
            OutGameDraw.fillRect(batch, white, x, trackY, fillW, trackH, theme.colors.value, parentAlpha)
        }
        OutGameDraw.strokeRect(batch, white, x, trackY, trackW, trackH, 1f, theme.colors.line, parentAlpha)

        val thumbW = 12f
        val thumbH = 20f
        val thumbX = (x + fillW - thumbW * 0.5f).coerceIn(x, x + trackW - thumbW)
        val thumbY = y + (height - thumbH) * 0.5f
        OutGameDraw.fillRect(batch, white, thumbX, thumbY, thumbW, thumbH, theme.outGame.primaryBg, parentAlpha)
        OutGameDraw.fillRect(batch, white, thumbX, thumbY, thumbW, 2f, theme.outGame.primaryBevel, parentAlpha)

        val valFont = fontsProvider()?.countMono ?: fontsProvider()?.smallMono ?: fallbackFontProvider()
        val text = valueFormatter(value)
        glyphLayout.setText(valFont, text)
        val prev = valFont.color.cpy()
        valFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)
        valFont.draw(batch, text, x + width - glyphLayout.width, y + (height + glyphLayout.height) * 0.5f)
        valFont.color = prev

        batch.packedColor = saved
    }
}

/**
 * Unified 7-category Settings screen (`设置`, UI Supplement §7–§8 & `UI-P004-005`).
 *
 * Supports both:
 * - Out-of-game page (`OutGameRoute.SETTINGS`, `inMatchMode = false`)
 * - In-match centered modal overlay (`≡ 菜单 → 设置`, `inMatchMode = true`, locking `资源包` & `内容包`
 *   while allowing instant live language, audio, theme, UI scale, and control updates).
 */
class SettingsScreen(
    private val skin: Skin,
    private val appShell: AppShell,
    val settingsStore: UserSettingsStore,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons? = { null },
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val showExtractOriginalButton: Boolean = false,
    private val resourcePacksProvider: () -> List<DualColumnPackEntry> = { defaultBuiltInResourceEntries() },
    private val contentPacksProvider: () -> List<DualColumnPackEntry> = { defaultBuiltInContentEntries() },
    private val resourceStatusProvider: () -> Pair<String?, Boolean> = { null to false },
    private val contentStatusProvider: () -> Pair<String?, Boolean> = { null to false },
    private val onNavigateBack: () -> Unit,
    private val onLanguageChanged: (UiLanguage) -> Unit = {},
    private val onThemeChanged: (BuiltInUiThemeId) -> Unit = {},
    private val onPreviewUiScale: (Float) -> Unit = {},
    private val onCommitUiScale: (Float) -> Unit = {},
    private val onRevertUiScale: (Float) -> Unit = {},
    private val onAudioOrControlsChanged: (UserSettings) -> Unit = {},
    private val onOpenResourceFolder: () -> String? = { null },
    private val onOpenContentFolder: () -> String? = { null },
    private val onMoveResourcePack: (packId: String, delta: Int) -> Unit = { _, _ -> },
    private val onSetResourcePackEnabled: (packId: String, enabled: Boolean) -> Unit = { _, _ -> },
    private val onGetResourcePackOptionValues: (packId: String) -> Map<String, String> = { packId ->
        settingsStore.effective.optionsForPack(packId)
    },
    private val onSetResourcePackOption: (packId: String, optionId: String, value: String) -> Unit = { packId, optionId, value ->
        settingsStore.update { it.withPackOption(packId, optionId, value) }
        if (packId == UserSettings.BASE_RESOURCE_PACK_ID && optionId == UserSettings.BASE_THEME_OPTION_ID) {
            onThemeChanged(BuiltInUiThemeId.fromCodeOrDefault(value))
        }
    },
    private val onResetResourcePackOptions: (packId: String) -> Unit = { packId ->
        settingsStore.update { it.resetPackOptions(packId) }
        if (packId == UserSettings.BASE_RESOURCE_PACK_ID) {
            onThemeChanged(BuiltInUiThemeId.DARK)
        }
    },
    private val onImportResourcePackPath: (rawPath: String) -> Unit = {},
    private val onExtractOriginalResourcePath: (rawPath: String) -> Unit = {},
    private val onMoveContentPack: (packId: String, delta: Int) -> Unit = { _, _ -> },
    private val onSetContentPackEnabled: (packId: String, enabled: Boolean) -> Unit = { _, _ -> },
    private val onImportContentPackPath: (rawPath: String) -> Unit = {},
) : Group(), AutoCloseable {

    private val glyphLayout = GlyphLayout()

    var currentCategory: SettingsCategory = SettingsCategory.GENERAL
        private set

    var inMatchMode: Boolean = false
        set(value) {
            field = value
            resourcePackManager.inMatchMode = value
            contentPackManager.inMatchMode = value
            packOptionsModal.inMatchMode = value
            openFolderButton.enabled = !value
        }

    private var committedScaleBaseline: Float = settingsStore.effective.uiScaleMultiplier
    private var folderFeedbackNotice: String? = null
    private var aboutDocModalTitleKey: String? = null
    private var aboutDocModalBodyKey: String? = null

    val topBar = SubPageTopBar(
        skin = skin,
        titleKey = "settings.title",
        widthMode = OutGameWidthMode.SYMMETRIC_WIDE,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onBack = {
            if (uiScaleModal.isOpen) {
                uiScaleModal.revertAndClose()
            } else if (packOptionsModal.isOpen) {
                packOptionsModal.dismiss()
            } else if (aboutDocModalTitleKey != null) {
                closeAboutDocModal()
            } else {
                stage?.unfocusAll()
                onNavigateBack()
            }
        },
    )

    private val panelShell = OutGamePanelShell(
        skin = skin,
        widthMode = OutGameWidthMode.SYMMETRIC_WIDE,
        themeProvider = themeProvider,
    )

    private val navActors = SettingsCategory.entries.map { cat -> NavRowActor(cat) }

    // 1. GENERAL (`常规`)
    val languageSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(UiLanguage.ZH, UiLanguage.EN),
        initialSelected = settingsStore.effective.language,
        labelOf = { lang -> UiStrings.get(if (lang == UiLanguage.ZH) "settings.lang.zh" else "settings.lang.en") },
        onSelected = { lang ->
            UiStrings.currentLanguage = lang
            settingsStore.update { it.copy(language = lang) }
            onLanguageChanged(lang)
            refreshPackLists()
        },
    )

    // 2. AUDIO (`音频`)
    val masterVolumeSlider = OutGameSlider(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        minValue = 0f,
        maxValue = 1f,
        stepSize = 0.05f,
        initialValue = settingsStore.effective.masterVolume,
        valueFormatter = { v -> "${(v * 100f).roundToInt()}%" },
        onValueChanged = { v ->
            val updated = settingsStore.update { it.copy(masterVolume = v) }
            onAudioOrControlsChanged(updated)
        },
    )

    val sfxVolumeSlider = OutGameSlider(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        minValue = 0f,
        maxValue = 1f,
        stepSize = 0.05f,
        initialValue = settingsStore.effective.sfxVolume,
        valueFormatter = { v -> "${(v * 100f).roundToInt()}%" },
        onValueChanged = { v ->
            val updated = settingsStore.update { it.copy(sfxVolume = v) }
            onAudioOrControlsChanged(updated)
        },
    )

    val muteSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = settingsStore.effective.muted,
        labelOf = { muted -> UiStrings.get(if (muted) "common.on" else "common.off") },
        onSelected = { muted ->
            val updated = settingsStore.update { it.copy(muted = muted) }
            onAudioOrControlsChanged(updated)
        },
    )

    // 3. VIDEO (`画面`) — declare `uiScaleModal` with explicit type before `uiScaleSegmented`
    val uiScaleSegmented: SegmentedControl<Float>

    val uiScaleModal: UiScaleConfirmModal = UiScaleConfirmModal(
        skin = skin,
        appShell = appShell,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onConfirmKeep = { confirmed ->
            committedScaleBaseline = confirmed
            settingsStore.update { it.copy(uiScaleMultiplier = confirmed) }
            uiScaleSegmented.setSelectedSilently(nearestPresetScale(confirmed))
            onCommitUiScale(confirmed)
        },
        onRevertScale = { reverted ->
            committedScaleBaseline = reverted
            uiScaleSegmented.setSelectedSilently(nearestPresetScale(reverted))
            onRevertUiScale(reverted)
        },
    )

    init {
        uiScaleSegmented = SegmentedControl(
            skin = skin,
            themeProvider = themeProvider,
            fontsProvider = fontsProvider,
            fallbackFontProvider = fallbackFontProvider,
            options = UserSettings.STANDARD_UI_SCALE_PRESETS,
            initialSelected = nearestPresetScale(settingsStore.effective.uiScaleMultiplier),
            labelOf = { mult -> UiScaleConfirmModal.formatPercent(mult) },
            onSelected = { nextScale ->
                val prev = committedScaleBaseline
                if (abs(nextScale - prev) <= 1e-3f) {
                    uiScaleModal.revertAndClose()
                } else {
                    onPreviewUiScale(nextScale)
                    uiScaleModal.open(previous = prev, proposed = nextScale)
                }
            },
        )
    }

    val packOptionsModal = ResourcePackOptionsModal(
        skin = skin,
        appShell = appShell,
        themeProvider = themeProvider,
        iconsProvider = iconsProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        optionValuesProvider = onGetResourcePackOptionValues,
        onSelectOption = { packId, optId, value ->
            onSetResourcePackOption(packId, optId, value)
            refreshPackLists()
        },
        onResetDefaults = { packId ->
            onResetResourcePackOptions(packId)
            refreshPackLists()
        },
    )

    // 4. CONTROLS (`操作`)
    val touchJoystickSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = settingsStore.effective.touchCameraJoystick,
        labelOf = { on -> UiStrings.get(if (on) "common.on" else "common.off") },
        onSelected = { on ->
            val updated = settingsStore.update { it.copy(touchCameraJoystick = on) }
            onAudioOrControlsChanged(updated)
        },
    )

    val edgeScrollSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = settingsStore.effective.windowEdgeScroll,
        labelOf = { on -> UiStrings.get(if (on) "common.on" else "common.off") },
        onSelected = { on ->
            val updated = settingsStore.update { it.copy(windowEdgeScroll = on) }
            onAudioOrControlsChanged(updated)
        },
    )

    val panSensitivitySlider = OutGameSlider(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        minValue = UserSettings.MIN_SENSITIVITY,
        maxValue = UserSettings.MAX_SENSITIVITY,
        stepSize = 0.05f,
        initialValue = settingsStore.effective.cameraPanSensitivity,
        valueFormatter = { v -> String.format(Locale.ROOT, "%.2f×", v) },
        onValueChanged = { v ->
            val updated = settingsStore.update { it.copy(cameraPanSensitivity = v) }
            onAudioOrControlsChanged(updated)
        },
    )

    val zoomSensitivitySlider = OutGameSlider(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        minValue = UserSettings.MIN_SENSITIVITY,
        maxValue = UserSettings.MAX_SENSITIVITY,
        stepSize = 0.05f,
        initialValue = settingsStore.effective.cameraZoomSensitivity,
        valueFormatter = { v -> String.format(Locale.ROOT, "%.2f×", v) },
        onValueChanged = { v ->
            val updated = settingsStore.update { it.copy(cameraZoomSensitivity = v) }
            onAudioOrControlsChanged(updated)
        },
    )

    // 5 & 6. Dual-column pack managers (`资源包` & `内容包`) + top-right `打开目录` button
    val openFolderButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = {
            if (currentCategory == SettingsCategory.CONTENT_PACKS) {
                UiStrings.get("settings.packs.openContentDir")
            } else {
                UiStrings.get("settings.packs.openResourceDir")
            }
        },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            if (!inMatchMode) {
                val activeMgr = if (currentCategory == SettingsCategory.CONTENT_PACKS) contentPackManager else resourcePackManager
                val nextShow = !activeMgr.showPathImportBar
                activeMgr.showPathImportBar = nextShow
                if (nextShow) {
                    val pathShown = if (currentCategory == SettingsCategory.CONTENT_PACKS) {
                        onOpenContentFolder()
                    } else {
                        onOpenResourceFolder()
                    }
                    if (!pathShown.isNullOrBlank()) {
                        folderFeedbackNotice = UiStrings.format("settings.packs.dirPathFormat", "PATH" to pathShown)
                    }
                } else {
                    folderFeedbackNotice = null
                }
                refreshPackLists()
            }
        },
    )

    val resourcePackManager = DualColumnPackManagerView(
        skin = skin,
        kind = PackManagerKind.RESOURCE_PACKS,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        showExtractOriginalButton = showExtractOriginalButton,
        onMovePriority = { id, delta ->
            onMoveResourcePack(id, delta)
            refreshPackLists()
        },
        onSetPackEnabled = { id, enabled ->
            onSetResourcePackEnabled(id, enabled)
            refreshPackLists()
        },
        onOpenPackOptions = { entry ->
            packOptionsModal.open(entry, inMatchMode)
            if (width > 0f && height > 0f) updateLayout(width, height)
        },
        onImportPath = { raw ->
            onImportResourcePackPath(raw)
            refreshPackLists()
        },
        onExtractOriginalPath = { raw ->
            onExtractOriginalResourcePath(raw)
            refreshPackLists()
        },
    )

    val contentPackManager = DualColumnPackManagerView(
        skin = skin,
        kind = PackManagerKind.CONTENT_PACKS,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        showExtractOriginalButton = false,
        onMovePriority = { id, delta ->
            onMoveContentPack(id, delta)
            refreshPackLists()
        },
        onSetPackEnabled = { id, enabled ->
            onSetContentPackEnabled(id, enabled)
            refreshPackLists()
        },
        onImportPath = { raw ->
            onImportContentPackPath(raw)
            refreshPackLists()
        },
    )

    // 7. ABOUT (`关于`) buttons & viewer modal
    val viewLicenseButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.view") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { openAboutDocModal("settings.about.license", "settings.about.licenseBody") },
    )

    val viewThirdPartyButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.view") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { openAboutDocModal("settings.about.thirdParty", "settings.about.thirdPartyBody") },
    )

    val closeAboutDocButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.confirm") },
        variant = OutGameButton.Variant.PRIMARY,
        onClick = { closeAboutDocModal() },
    )

    private var navRailW = 188f
    private var mainPadX = 26f
    private var mainInnerW = 600f
    private var paneHeaderBottomY = 0f
    private val currentRowSpecs = ArrayList<Pair<String, Actor>>()

    private val inMatchBackdropActor = object : Actor() {
        init {
            val selfBackdrop = this
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean = inMatchMode
                override fun mouseMoved(event: InputEvent, x: Float, y: Float): Boolean {
                    if (inMatchMode) stage?.scrollFocus = selfBackdrop
                    return inMatchMode
                }
                override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean = inMatchMode
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            if (!inMatchMode) return
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            OutGameDraw.fillRect(batch, white, 0f, 0f, width, height, theme.outGame.mapScrimDeep, parentAlpha)
            batch.packedColor = saved
        }
    }

    private val panelInteriorActor = object : Actor() {
        init {
            touchable = Touchable.disabled
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val ox = x
            val oy = y
            val shW = panelShell.width
            val shH = panelShell.height

            // Left navigation rail background & right 1px divider (no bottom-left filler box per §7!)
            OutGameDraw.fillRect(batch, white, ox + 1f, oy + 1f, navRailW - 1f, shH - 2f, theme.outGame.railSolid, parentAlpha)
            OutGameDraw.fillRect(batch, white, ox + navRailW, oy + 1f, 1f, shH - 2f, theme.colors.line, parentAlpha)

            // Right pane heading (`常规 / 音频 / 画面 / 操作 / 资源包 / 内容包 / 关于`) + 1px bottom line
            val rightStartX = ox + navRailW + mainPadX
            val hdFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
            val prevHd = hdFont.color.cpy()
            hdFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
            hdFont.draw(batch, currentCategory.localizedLabel(), rightStartX, oy + paneHeaderBottomY + 26f)
            hdFont.color = prevHd
            OutGameDraw.fillRect(batch, white, rightStartX, oy + paneHeaderBottomY, mainInnerW, 1f, theme.colors.line, parentAlpha)

            // Setting rows (`常规 / 音频 / 画面 / 操作`): strictly label + control + 1px hair divider, NO sub-row captions!
            if (currentRowSpecs.isNotEmpty()) {
                val rowFont = fontsProvider()?.title ?: fontsProvider()?.body ?: fallbackFontProvider()
                val rowH = 52f
                var rowTopY = oy + paneHeaderBottomY
                currentRowSpecs.forEachIndexed { idx, (labelKey, _) ->
                    val rowBottomY = rowTopY - rowH
                    if (idx < currentRowSpecs.lastIndex) {
                        OutGameDraw.fillRect(batch, white, rightStartX, rowBottomY, mainInnerW, 1f, theme.colors.hair, parentAlpha)
                    }
                    val label = UiStrings.get(labelKey)
                    glyphLayout.setText(rowFont, label)
                    val prev = rowFont.color.cpy()
                    rowFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)
                    rowFont.draw(batch, label, rightStartX, rowBottomY + (rowH + glyphLayout.height) * 0.5f)
                    rowFont.color = prev
                    rowTopY = rowBottomY
                }
            } else if (currentCategory == SettingsCategory.ABOUT) {
                val kvH = 46f
                val bodyFont = fontsProvider()?.body ?: fallbackFontProvider()
                val monoFont = fontsProvider()?.creditsMono ?: fontsProvider()?.countMono ?: bodyFont
                val prevB = bodyFont.color.cpy()
                bodyFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)

                // KV Row 1: `当前版本` -> `v0.6.4`
                val r1Bottom = oy + paneHeaderBottomY - kvH
                OutGameDraw.fillRect(batch, white, rightStartX, r1Bottom, mainInnerW, 1f, theme.colors.hair, parentAlpha)
                val verLbl = UiStrings.get("settings.about.version")
                glyphLayout.setText(bodyFont, verLbl)
                bodyFont.draw(batch, verLbl, rightStartX, r1Bottom + (kvH + glyphLayout.height) * 0.5f)

                val prevM = monoFont.color.cpy()
                val strong = theme.outGame.textStrong
                monoFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
                glyphLayout.setText(monoFont, ReforgeVersion.DISPLAY_VERSION)
                monoFont.draw(batch, ReforgeVersion.DISPLAY_VERSION, rightStartX + mainInnerW - glyphLayout.width, r1Bottom + (kvH + glyphLayout.height) * 0.5f)
                monoFont.color = prevM

                // KV Row 2: `开源许可证`
                val r2Bottom = r1Bottom - kvH
                OutGameDraw.fillRect(batch, white, rightStartX, r2Bottom, mainInnerW, 1f, theme.colors.hair, parentAlpha)
                val licLbl = UiStrings.get("settings.about.license")
                glyphLayout.setText(bodyFont, licLbl)
                bodyFont.draw(batch, licLbl, rightStartX, r2Bottom + (kvH + glyphLayout.height) * 0.5f)

                // KV Row 3: `第三方开源组件与素材声明`
                val r3Bottom = r2Bottom - kvH
                OutGameDraw.fillRect(batch, white, rightStartX, r3Bottom, mainInnerW, 1f, theme.colors.hair, parentAlpha)
                val tpLbl = UiStrings.get("settings.about.thirdParty")
                glyphLayout.setText(bodyFont, tpLbl)
                bodyFont.draw(batch, tpLbl, rightStartX, r3Bottom + (kvH + glyphLayout.height) * 0.5f)
                bodyFont.color = prevB

                // Unofficial Project Notice Box (`.decl-box`)
                val boxTopY = r3Bottom - 16f
                val boxH = 98f
                val boxY = boxTopY - boxH
                OutGameDraw.fillRect(batch, white, rightStartX, boxY, mainInnerW, boxH, theme.colors.well, parentAlpha)
                OutGameDraw.strokeRect(batch, white, rightStartX, boxY, mainInnerW, boxH, 1f, theme.colors.line, parentAlpha)

                val boldFont = fontsProvider()?.command ?: bodyFont
                val prevBold = boldFont.color.cpy()
                boldFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)
                boldFont.draw(batch, UiStrings.get("settings.about.unofficialTitle"), rightStartX + 14f, boxY + boxH - 16f)
                boldFont.color = prevBold

                val prevDim = bodyFont.color.cpy()
                bodyFont.setColor(theme.colors.dim.r, theme.colors.dim.g, theme.colors.dim.b, theme.colors.dim.a * parentAlpha)
                val lines = OutGameDraw.wrapTextCjk(bodyFont, UiStrings.get("settings.about.unofficialBody"), mainInnerW - 28f, glyphLayout)
                lines.take(3).forEachIndexed { idx, line ->
                    bodyFont.draw(batch, line, rightStartX + 14f, boxY + boxH - 42f - idx * 21f)
                }
                bodyFont.color = prevDim
            }

            batch.packedColor = saved
        }
    }

    private val aboutDocModalActor = object : Actor() {
        init {
            touchable = Touchable.enabled
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                    aboutDocModalTitleKey != null
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val tKey = aboutDocModalTitleKey ?: return
            val bKey = aboutDocModalBodyKey ?: return
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")

            OutGameDraw.fillRect(batch, white, 0f, 0f, width, height, theme.outGame.modalScrim, parentAlpha)
            val mw = min(560f, width - 64f)
            val mh = 220f
            val mx = ((width - mw) * 0.5f).roundToInt().toFloat()
            val my = ((height - mh) * 0.5f).roundToInt().toFloat()
            OutGameDraw.fillRect(batch, white, mx, my, mw, mh, theme.colors.solid, parentAlpha)
            OutGameDraw.fillRect(batch, white, mx + 1f, my + mh - 42f, mw - 2f, 41f, theme.outGame.railSolid, parentAlpha)
            OutGameDraw.fillRect(batch, white, mx + 1f, my + mh - 42f, mw - 2f, 1f, theme.colors.line, parentAlpha)
            OutGameDraw.strokeRect(batch, white, mx, my, mw, mh, 1f, theme.colors.line, parentAlpha)

            val hdFont = fontsProvider()?.sectionTitle ?: fallbackFontProvider()
            val prevHd = hdFont.color.cpy()
            hdFont.setColor(theme.colors.boneHi.r, theme.colors.boneHi.g, theme.colors.boneHi.b, theme.colors.boneHi.a * parentAlpha)
            hdFont.draw(batch, UiStrings.get(tKey), mx + 20f, my + mh - 14f)
            hdFont.color = prevHd

            val bodyFont = fontsProvider()?.body ?: fallbackFontProvider()
            val prevB = bodyFont.color.cpy()
            bodyFont.setColor(theme.colors.text.r, theme.colors.text.g, theme.colors.text.b, theme.colors.text.a * parentAlpha)
            val lines = OutGameDraw.wrapTextCjk(bodyFont, UiStrings.get(bKey), mw - 40f, glyphLayout)
            lines.take(4).forEachIndexed { idx, line ->
                bodyFont.draw(batch, line, mx + 20f, my + mh - 64f - idx * 22f)
            }
            bodyFont.color = prevB

            batch.packedColor = saved
        }
    }

    init {
        setTransform(false)
        panelShell.addActor(panelInteriorActor)
        navActors.forEach(panelShell::addActor)

        panelShell.addActor(languageSegmented)
        panelShell.addActor(masterVolumeSlider)
        panelShell.addActor(sfxVolumeSlider)
        panelShell.addActor(muteSegmented)
        panelShell.addActor(uiScaleSegmented)
        panelShell.addActor(touchJoystickSegmented)
        panelShell.addActor(edgeScrollSegmented)
        panelShell.addActor(panSensitivitySlider)
        panelShell.addActor(zoomSensitivitySlider)
        panelShell.addActor(openFolderButton)
        panelShell.addActor(resourcePackManager)
        panelShell.addActor(contentPackManager)
        panelShell.addActor(viewLicenseButton)
        panelShell.addActor(viewThirdPartyButton)

        addActor(inMatchBackdropActor)
        addActor(panelShell)
        addActor(topBar)
        addActor(aboutDocModalActor)
        addActor(closeAboutDocButton)
        addActor(packOptionsModal)
        addActor(uiScaleModal)

        aboutDocModalActor.isVisible = false
        closeAboutDocButton.isVisible = false
        refreshPackLists()
        syncCategoryVisibility()
    }

    fun selectCategory(category: SettingsCategory) {
        if (currentCategory == category) return
        stage?.unfocusAll()
        folderFeedbackNotice = null
        currentCategory = category
        refreshPackLists()
        syncCategoryVisibility()
        if (width > 0f && height > 0f) {
            updateLayout(width, height)
        }
    }

    fun syncControlsFromStore() {
        val eff = settingsStore.effective
        committedScaleBaseline = eff.uiScaleMultiplier
        languageSegmented.setOptionsAndSelection(listOf(UiLanguage.ZH, UiLanguage.EN), eff.language)
        masterVolumeSlider.setValueSilently(eff.masterVolume)
        sfxVolumeSlider.setValueSilently(eff.sfxVolume)
        muteSegmented.setOptionsAndSelection(listOf(false, true), eff.muted)
        if (!uiScaleModal.isOpen) {
            uiScaleSegmented.setOptionsAndSelection(UserSettings.STANDARD_UI_SCALE_PRESETS, nearestPresetScale(eff.uiScaleMultiplier))
        }
        if (packOptionsModal.isOpen) {
            packOptionsModal.syncSelectionsFromStore()
        }
        touchJoystickSegmented.setOptionsAndSelection(listOf(false, true), eff.touchCameraJoystick)
        edgeScrollSegmented.setOptionsAndSelection(listOf(false, true), eff.windowEdgeScroll)
        panSensitivitySlider.setValueSilently(eff.cameraPanSensitivity)
        zoomSensitivitySlider.setValueSilently(eff.cameraZoomSensitivity)
        refreshPackLists()
        syncCategoryVisibility()
    }

    fun refreshPackLists() {
        val (resMsg, resErr) = resourceStatusProvider()
        resourcePackManager.statusNotice = folderFeedbackNotice?.takeIf { currentCategory == SettingsCategory.RESOURCE_PACKS } ?: resMsg
        resourcePackManager.statusIsError = resErr
        resourcePackManager.setEntries(resourcePacksProvider())

        val (modMsg, modErr) = contentStatusProvider()
        contentPackManager.statusNotice = folderFeedbackNotice?.takeIf { currentCategory == SettingsCategory.CONTENT_PACKS } ?: modMsg
        contentPackManager.statusIsError = modErr
        contentPackManager.setEntries(contentPacksProvider())
    }

    private fun openAboutDocModal(titleKey: String, bodyKey: String) {
        aboutDocModalTitleKey = titleKey
        aboutDocModalBodyKey = bodyKey
        aboutDocModalActor.isVisible = true
        closeAboutDocButton.isVisible = true
        appShell.openModal("settings-about-doc") {
            aboutDocModalTitleKey = null
            aboutDocModalBodyKey = null
            aboutDocModalActor.isVisible = false
            closeAboutDocButton.isVisible = false
        }
        if (width > 0f && height > 0f) {
            updateLayout(width, height)
        }
    }

    private fun closeAboutDocModal() {
        if (aboutDocModalTitleKey == null) return
        appShell.closeModal("settings-about-doc")
    }

    fun dismissAllModals() {
        if (uiScaleModal.isOpen) {
            uiScaleModal.revertAndClose()
        }
        if (packOptionsModal.isOpen) {
            packOptionsModal.dismiss()
        }
        if (aboutDocModalTitleKey != null) {
            closeAboutDocModal()
        }
    }

    private fun syncCategoryVisibility() {
        val allControls = listOf(
            languageSegmented,
            masterVolumeSlider,
            sfxVolumeSlider,
            muteSegmented,
            uiScaleSegmented,
            touchJoystickSegmented,
            edgeScrollSegmented,
            panSensitivitySlider,
            zoomSensitivitySlider,
            openFolderButton,
            resourcePackManager,
            contentPackManager,
            viewLicenseButton,
            viewThirdPartyButton,
        )
        allControls.forEach { it.isVisible = false }
        currentRowSpecs.clear()

        when (currentCategory) {
            SettingsCategory.GENERAL -> {
                languageSegmented.isVisible = true
                currentRowSpecs += "settings.language" to languageSegmented
            }
            SettingsCategory.AUDIO -> {
                masterVolumeSlider.isVisible = true
                sfxVolumeSlider.isVisible = true
                muteSegmented.isVisible = true
                currentRowSpecs += "settings.masterVolume" to masterVolumeSlider
                currentRowSpecs += "settings.sfxVolume" to sfxVolumeSlider
                currentRowSpecs += "settings.mute" to muteSegmented
            }
            SettingsCategory.VIDEO -> {
                uiScaleSegmented.isVisible = true
                currentRowSpecs += "settings.uiScale" to uiScaleSegmented
            }
            SettingsCategory.CONTROLS -> {
                touchJoystickSegmented.isVisible = true
                edgeScrollSegmented.isVisible = true
                panSensitivitySlider.isVisible = true
                zoomSensitivitySlider.isVisible = true
                currentRowSpecs += "settings.touchJoystick" to touchJoystickSegmented
                currentRowSpecs += "settings.edgeScroll" to edgeScrollSegmented
                currentRowSpecs += "settings.panSensitivity" to panSensitivitySlider
                currentRowSpecs += "settings.zoomSensitivity" to zoomSensitivitySlider
            }
            SettingsCategory.RESOURCE_PACKS -> {
                openFolderButton.isVisible = true
                openFolderButton.enabled = !inMatchMode
                resourcePackManager.isVisible = true
            }
            SettingsCategory.CONTENT_PACKS -> {
                openFolderButton.isVisible = true
                openFolderButton.enabled = !inMatchMode
                contentPackManager.isVisible = true
            }
            SettingsCategory.ABOUT -> {
                viewLicenseButton.isVisible = true
                viewThirdPartyButton.isVisible = true
            }
        }
    }

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        inMatchBackdropActor.isVisible = inMatchMode
        inMatchBackdropActor.setBounds(0f, 0f, stageWidth, stageHeight)

        topBar.updateBounds(stageWidth, stageHeight)
        panelShell.updateShellBounds(stageWidth, stageHeight)
        packOptionsModal.updateModalBounds(stageWidth, stageHeight)
        uiScaleModal.updateModalBounds(stageWidth, stageHeight)

        aboutDocModalActor.setBounds(0f, 0f, stageWidth, stageHeight)
        if (aboutDocModalTitleKey != null) {
            val mw = min(560f, stageWidth - 64f)
            val mh = 220f
            val mx = ((stageWidth - mw) * 0.5f).roundToInt().toFloat()
            val my = ((stageHeight - mh) * 0.5f).roundToInt().toFloat()
            closeAboutDocButton.setBounds(mx + mw - 112f, my + 16f, 92f, 34f)
        }

        val shW = panelShell.width
        val shH = panelShell.height
        panelInteriorActor.setBounds(0f, 0f, shW, shH)

        navRailW = if (shW < 920f) 164f else 188f
        val navRowH = 44f
        var navCursorY = shH - 8f
        for (nav in navActors) {
            navCursorY -= navRowH
            nav.setBounds(1f, navCursorY, navRailW - 1f, navRowH)
        }

        mainPadX = if (shW < 920f) 18f else 26f
        val rightStartX = navRailW + mainPadX
        mainInnerW = max(240f, shW - navRailW - mainPadX * 2f)
        paneHeaderBottomY = shH - 54f

        if (currentRowSpecs.isNotEmpty()) {
            val rowH = 52f
            val ctrlW = min(280f, mainInnerW * 0.48f).roundToInt().toFloat()
            val ctrlH = 32f
            var rowTopY = paneHeaderBottomY
            for ((_, ctrl) in currentRowSpecs) {
                val rowBottomY = rowTopY - rowH
                ctrl.setBounds(rightStartX + mainInnerW - ctrlW, rowBottomY + (rowH - ctrlH) * 0.5f, ctrlW, ctrlH)
                rowTopY = rowBottomY
            }
        } else if (currentCategory == SettingsCategory.RESOURCE_PACKS || currentCategory == SettingsCategory.CONTENT_PACKS) {
            val btnFont = fontsProvider()?.command ?: fallbackFontProvider()
            val folderLabel = openFolderButton.labelProvider()
            glyphLayout.setText(btnFont, folderLabel)
            val folderBtnW = max(124f, (glyphLayout.width + 26f).roundToInt().toFloat())
            openFolderButton.setBounds(rightStartX + mainInnerW - folderBtnW, paneHeaderBottomY + 8f, folderBtnW, 30f)

            val mgrBottomPad = 16f
            val mgrTopY = paneHeaderBottomY - 12f
            val mgrH = max(160f, mgrTopY - mgrBottomPad)
            if (currentCategory == SettingsCategory.RESOURCE_PACKS) {
                resourcePackManager.setBounds(rightStartX, mgrBottomPad, mainInnerW, mgrH)
            } else {
                contentPackManager.setBounds(rightStartX, mgrBottomPad, mainInnerW, mgrH)
            }
        } else if (currentCategory == SettingsCategory.ABOUT) {
            val kvH = 46f
            val btnW = 72f
            val btnH = 30f
            val r2Bottom = paneHeaderBottomY - kvH * 2f
            val r3Bottom = paneHeaderBottomY - kvH * 3f
            viewLicenseButton.setBounds(rightStartX + mainInnerW - btnW, r2Bottom + (kvH - btnH) * 0.5f, btnW, btnH)
            viewThirdPartyButton.setBounds(rightStartX + mainInnerW - btnW, r3Bottom + (kvH - btnH) * 0.5f, btnW, btnH)
        }
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        if (uiScaleModal.isOpen) {
            return uiScaleModal.drawnActions()
        }
        if (packOptionsModal.isOpen) {
            return packOptionsModal.drawnActions()
        }
        if (aboutDocModalTitleKey != null) {
            return listOf(DrawnAction("settings-about-doc-close", closeAboutDocButton, UiStrings.get("common.confirm"), enabled = true))
        }
        val out = ArrayList<DrawnAction>(36)
        out += topBar.drawnBackAction()
        for (nav in navActors) {
            out += DrawnAction(nav.category.navActionId, nav, nav.category.localizedLabel(), enabled = true)
        }
        when (currentCategory) {
            SettingsCategory.GENERAL -> {
                out += DrawnAction("settings-language", languageSegmented, settingsStore.effective.language.code, enabled = true)
            }
            SettingsCategory.AUDIO -> {
                out += DrawnAction("settings-master-volume", masterVolumeSlider, "${(masterVolumeSlider.value * 100f).roundToInt()}%", enabled = true)
                out += DrawnAction("settings-sfx-volume", sfxVolumeSlider, "${(sfxVolumeSlider.value * 100f).roundToInt()}%", enabled = true)
                out += DrawnAction("settings-mute", muteSegmented, UiStrings.get(if (muteSegmented.selected) "common.on" else "common.off"), enabled = true)
            }
            SettingsCategory.VIDEO -> {
                out += DrawnAction("settings-ui-scale", uiScaleSegmented, UiScaleConfirmModal.formatPercent(uiScaleSegmented.selected), enabled = true)
            }
            SettingsCategory.CONTROLS -> {
                out += DrawnAction("settings-touch-joystick", touchJoystickSegmented, UiStrings.get(if (touchJoystickSegmented.selected) "common.on" else "common.off"), enabled = true)
                out += DrawnAction("settings-edge-scroll", edgeScrollSegmented, UiStrings.get(if (edgeScrollSegmented.selected) "common.on" else "common.off"), enabled = true)
                out += DrawnAction("settings-pan-sensitivity", panSensitivitySlider, String.format(Locale.ROOT, "%.2f×", panSensitivitySlider.value), enabled = true)
                out += DrawnAction("settings-zoom-sensitivity", zoomSensitivitySlider, String.format(Locale.ROOT, "%.2f×", zoomSensitivitySlider.value), enabled = true)
            }
            SettingsCategory.RESOURCE_PACKS -> {
                out += DrawnAction("settings-open-resource-dir", openFolderButton, openFolderButton.labelProvider(), enabled = openFolderButton.enabled)
                out += resourcePackManager.drawnActions()
            }
            SettingsCategory.CONTENT_PACKS -> {
                out += DrawnAction("settings-open-content-dir", openFolderButton, openFolderButton.labelProvider(), enabled = openFolderButton.enabled)
                out += contentPackManager.drawnActions()
            }
            SettingsCategory.ABOUT -> {
                out += DrawnAction("settings-view-license", viewLicenseButton, UiStrings.get("common.view"), enabled = true)
                out += DrawnAction("settings-view-third-party", viewThirdPartyButton, UiStrings.get("common.view"), enabled = true)
            }
        }
        return out
    }

    override fun close() {
        resourcePackManager.close()
        contentPackManager.close()
    }

    private inner class NavRowActor(val category: SettingsCategory) : Actor() {
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
                    if (wasPressed && x in 0f..width && y in 0f..height) {
                        selectCategory(category)
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val isActive = category == currentCategory

            when {
                isActive -> OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.solid, parentAlpha)
                hovered || pressed -> OutGameDraw.fillRect(batch, white, x, y, width, height, theme.outGame.hoverSoft, parentAlpha)
            }
            OutGameDraw.fillRect(batch, white, x, y, width, 1f, theme.colors.hair, parentAlpha)
            if (isActive) {
                // Strictly ONLY left 3px vertical `rust` indicator bar + background (no right dot or number per §7!)
                OutGameDraw.fillRect(batch, white, x, y, 3f, height, theme.colors.rust, parentAlpha)
            }

            val font = if (isActive) {
                fontsProvider()?.command ?: fontsProvider()?.body ?: fallbackFontProvider()
            } else {
                fontsProvider()?.body ?: fallbackFontProvider()
            }
            val label = category.localizedLabel()
            glyphLayout.setText(font, label)
            val prev = font.color.cpy()
            val fg = if (isActive || hovered) theme.colors.text else theme.colors.dim
            font.setColor(fg.r, fg.g, fg.b, fg.a * parentAlpha)
            font.draw(batch, label, x + 22f, y + (height + glyphLayout.height) * 0.5f)
            font.color = prev

            batch.packedColor = saved
        }
    }

    companion object {
        fun nearestPresetScale(raw: Float): Float =
            UserSettings.STANDARD_UI_SCALE_PRESETS.minByOrNull { abs(it - raw) } ?: 1.0f

        fun defaultBuiltInResourceEntries(language: UiLanguage = UiStrings.currentLanguage): List<DualColumnPackEntry> {
            val baseMeta = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER)
            return listOf(
                DualColumnPackEntry(
                    id = UserSettings.BASE_RESOURCE_PACK_ID,
                    title = LocalizedMetadata.resourcePackName(baseMeta, language),
                    subtitle = LocalizedMetadata.resourcePackDescription(baseMeta, language),
                    enabled = true,
                    lockedBottom = true,
                    canEnable = false,
                    badgeStyle = PackIconBadgeStyle.BASE_RESOURCE,
                    options = baseMeta.options,
                ),
            )
        }

        fun defaultBuiltInContentEntries(language: UiLanguage = UiStrings.currentLanguage): List<DualColumnPackEntry> {
            val baseId = UserSettings.DEFAULT_BASE_CONTENT_PACK_ID
            val sixAId = "phase-six-a.json"
            val sixARoot = listOf(
                Paths.get("SimulationTest/public/inputs/phase-six-a-sandbox/json/package"),
                Paths.get("test-scenarios/inputs/phase-six-a-sandbox/json/package"),
                Paths.get("../SimulationTest/public/inputs/phase-six-a-sandbox/json/package"),
            ).firstOrNull { Files.isRegularFile(it.resolve("pack.json")) }

            return buildList {
                if (sixARoot != null) {
                    add(
                        DualColumnPackEntry(
                            id = sixAId,
                            title = LocalizedMetadata.contentPackName(sixAId, "Phase VI-A Sandbox Pack", sixARoot, language),
                            subtitle = LocalizedMetadata.contentPackDescription(sixAId, sixAId, sixARoot, language),
                            enabled = false,
                            lockedBottom = false,
                            canEnable = true,
                            badgeStyle = PackIconBadgeStyle.CUSTOM_CONTENT,
                        ),
                    )
                }
                add(
                    DualColumnPackEntry(
                        id = baseId,
                        title = LocalizedMetadata.contentPackName(baseId, "Phase VII Sandbox Pack", null, language),
                        subtitle = LocalizedMetadata.contentPackDescription(baseId, baseId, null, language),
                        enabled = true,
                        lockedBottom = true,
                        canEnable = false,
                        badgeStyle = PackIconBadgeStyle.BASE_CONTENT,
                    ),
                )
            }
        }
    }
}
