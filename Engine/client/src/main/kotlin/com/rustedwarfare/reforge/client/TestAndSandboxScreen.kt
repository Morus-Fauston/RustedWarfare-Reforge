package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.rustedwarfare.reforge.core.content.MapDefinition
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class TestSandboxMode(val labelKey: String) {
    FREE_SANDBOX("sandbox.mode.free"),
    TEST_SCENES("sandbox.mode.scenes"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)
}

enum class SandboxCameraMode(val labelKey: String) {
    SCRIPTED("sandbox.camera.scripted"),
    FREE("sandbox.camera.free"),
    ;

    fun localizedLabel(language: UiLanguage = UiStrings.currentLanguage): String =
        UiStrings.get(labelKey, language)
}

/**
 * Preview actor for locked SHA-256 test scenario seeds (`TEST_SCENES` mode, UI Supplement §5.2).
 * Renders the selected scenario's locked [MapDefinition] and starting unit formation pips colored by owner.
 */
class LockedScenePreviewActor(
    private val skin: Skin,
    private val themeProvider: UiThemeProvider,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val choiceProvider: () -> TestScenarioChoice?,
    private val errorProvider: () -> String?,
    private val fallbackMapProvider: () -> MapDefinition,
) : Actor(), AutoCloseable {

    private data class SceneTexKey(
        val id: String,
        val widthTiles: Int,
        val heightTiles: Int,
        val themeId: String,
        val wellBits: Int,
        val boneHiBits: Int,
        val allyBits: Int,
    )

    private val glyphLayout = GlyphLayout()
    private var cachedKey: SceneTexKey? = null
    private var cachedTexture: Texture? = null

    init {
        touchable = Touchable.disabled
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (width <= 0f || height <= 0f) return
        val saved = batch.packedColor
        val theme = themeProvider.currentTheme()
        val white = skin.getRegion("white")
        val choice = choiceProvider()
        val err = errorProvider()

        OutGameDraw.fillRect(batch, white, x, y, width, height, theme.colors.well, parentAlpha)

        val mapDef = choice?.scenario?.map ?: fallbackMapProvider()
        val mapId = choice?.id ?: "fallback-preview"
        val tex = ensureTexture(mapId, mapDef, theme)
        if (tex != null) {
            val mapAlpha = if (choice != null) parentAlpha else parentAlpha * 0.36f
            batch.setColor(1f, 1f, 1f, mapAlpha)
            batch.draw(tex, x, y, width, height)
        }

        if (choice != null) {
            val worldW = (mapDef.widthTiles * mapDef.tileWidth).toDouble().coerceAtLeast(1.0)
            val worldH = (mapDef.heightTiles * mapDef.tileHeight).toDouble().coerceAtLeast(1.0)
            val playerIndexById = choice.scenario.players.mapIndexed { idx, p -> p.playerId to idx }.toMap()
            val pipSize = if (min(width, height) >= 320f) 10f else 8f
            val frameCol = if (theme.colors.well.r < theme.colors.boneHi.r) theme.colors.well else theme.colors.boneHi

            for (unit in choice.scenario.units) {
                val nx = (unit.position.x / worldW).toFloat().coerceIn(0.02f, 0.98f)
                val ny = (unit.position.y / worldH).toFloat().coerceIn(0.02f, 0.98f)
                val px = (x + nx * width - pipSize * 0.5f).roundToInt().toFloat()
                // Core world Y increases downward in TMX space
                val py = (y + (1f - ny) * height - pipSize * 0.5f).roundToInt().toFloat()
                val pIdx = playerIndexById[unit.ownerPlayerId] ?: 0
                val pCol = theme.palettes.playerColor(pIdx)
                OutGameDraw.fillRect(batch, white, px - 1f, py - 1f, pipSize + 2f, pipSize + 2f, frameCol, parentAlpha)
                OutGameDraw.fillRect(batch, white, px, py, pipSize, pipSize, pCol, parentAlpha)
            }
        } else {
            val msg = err?.takeIf { it.isNotBlank() } ?: UiStrings.get("sandbox.scene.pickPrompt")
            val font = fontsProvider()?.body ?: fallbackFontProvider()
            glyphLayout.setText(font, msg, if (err != null) theme.colors.foe else theme.colors.dim, width - 36f, com.badlogic.gdx.utils.Align.center, true)
            val boxH = (glyphLayout.height + 24f).coerceAtLeast(44f)
            val boxY = y + (height - boxH) * 0.5f
            OutGameDraw.fillRect(batch, white, x + 14f, boxY, width - 28f, boxH, theme.outGame.railSolid, parentAlpha * 0.92f)
            OutGameDraw.strokeRect(batch, white, x + 14f, boxY, width - 28f, boxH, 1f, theme.colors.line, parentAlpha)
            font.draw(batch, glyphLayout, x + 18f, boxY + (boxH + glyphLayout.height) * 0.5f)
        }

        if (choice != null && !err.isNullOrBlank()) {
            val font = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            glyphLayout.setText(font, err)
            val prev = font.color.cpy()
            font.setColor(theme.colors.foe.r, theme.colors.foe.g, theme.colors.foe.b, theme.colors.foe.a * parentAlpha)
            font.draw(batch, err, x + 10f, y + 18f)
            font.color = prev
        }

        OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, theme.colors.line, parentAlpha)
        batch.packedColor = saved
    }

    private fun ensureTexture(mapId: String, map: MapDefinition, theme: UiTheme): Texture? {
        if (Gdx.gl == null) return null
        val key = SceneTexKey(
            id = mapId,
            widthTiles = map.widthTiles,
            heightTiles = map.heightTiles,
            themeId = theme.id,
            wellBits = Color.rgba8888(theme.colors.well),
            boneHiBits = Color.rgba8888(theme.colors.boneHi),
            allyBits = Color.rgba8888(theme.outGame.ally),
        )
        if (cachedKey == key && cachedTexture != null) return cachedTexture
        cachedTexture?.let { runCatching { it.dispose() } }
        val pm = OutGameMapPreviewRenderer.renderTacticalPixmap(map, 512, 384, theme)
        val tex = try {
            Texture(pm).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
        } finally {
            pm.dispose()
        }
        cachedKey = key
        cachedTexture = tex
        return tex
    }

    override fun close() {
        cachedTexture?.let { runCatching { it.dispose() } }
        cachedTexture = null
        cachedKey = null
    }
}

/**
 * Dual-mode `测试与沙盒` (`TestAndSandboxScreen`, UI Supplement §5 & `UI-P004-004`).
 *
 * Directly reuses the Skirmish Lobby dual-column constructivist skeleton:
 * - [TestSandboxMode.FREE_SANDBOX] (default, available to all builds):
 *   `模式` (hidden when `development == false`), `战争迷雾` (default `NONE`), `初始资金` (default `INFINITE`),
 *   `快速建造` (`关 / 开`), 1–10 seat [MatchSlotTableView] (`待机` target dummy), [TacticalBattlefieldPreviewActor],
 *   shared `MapPicker` button, `.tmx` path load, and `进入自由沙盒`.
 * - [TestSandboxMode.TEST_SCENES] (visible ONLY when `development == true`):
 *   `模式` + `镜头控制` (`跟随种子脚本 / 自由镜头`), SHA-256 locked scenario list with 100% preserved
 *   [TestScenarioPanel] `DrawnAction` IDs (`test-scene-use`, `test-scene-spider`/`test-scene-custom`,
 *   `test-scene-camera`), [LockedScenePreviewActor], `.json` seed path load (`选择地图` hidden), and `载入测试场景`.
 */
class TestAndSandboxScreen(
    private val skin: Skin,
    val freeSandboxState: SkirmishLobbyState,
    val testScenes: TestScenarioMenu,
    val development: Boolean,
    private val testScenarioSeed: Path? = null,
    private val alternateStartSeed: Path? = null,
    private val cameraStartSeed: Path? = null,
    private val themeProvider: UiThemeProvider,
    private val iconsProvider: () -> OutGameControlIcons?,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val canStartBaseProvider: () -> Boolean = { true },
    private val canStartTestSceneProvider: () -> Boolean = { canStartBaseProvider() },
    private val statusMessageProvider: () -> String? = { null },
    private val onNavigateBack: () -> Unit,
    private val onOpenMapPicker: (currentMapId: String, onChosen: (MapPickerEntry) -> Unit) -> Unit,
    private val onLoadCustomTmxPath: (rawPath: String) -> MapPickerEntry?,
    private val onTestSceneMutated: () -> Unit = {},
    private val onEnterFreeSandbox: (SkirmishLaunchConfig) -> Unit,
    private val onLoadTestScene: (followScriptedCamera: Boolean) -> Unit,
    private val onStateMutated: () -> Unit = {},
) : Group(), AutoCloseable {

    private val glyphLayout = GlyphLayout()
    private var localErrorNotice: String? = null
    var editable: Boolean = true
        set(value) {
            field = value
            syncInteractiveState()
        }

    var currentMode: TestSandboxMode = TestSandboxMode.FREE_SANDBOX
        private set

    var cameraMode: SandboxCameraMode = SandboxCameraMode.SCRIPTED
        private set

    val followScriptedCamera: Boolean
        get() = cameraMode == SandboxCameraMode.SCRIPTED

    val topBar = SubPageTopBar(
        skin = skin,
        titleKey = "sandbox.title",
        widthMode = OutGameWidthMode.FULL_INSET,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onBack = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            onNavigateBack()
        },
    )

    private val panelShell = OutGamePanelShell(
        skin = skin,
        widthMode = OutGameWidthMode.FULL_INSET,
        themeProvider = themeProvider,
    )

    val modeSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = if (development) TestSandboxMode.entries else listOf(TestSandboxMode.FREE_SANDBOX),
        initialSelected = TestSandboxMode.FREE_SANDBOX,
        labelOf = { it.localizedLabel() },
        onSelected = { mode ->
            selectMode(mode)
        },
    )

    val fogModeSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishFogMode.entries,
        initialSelected = freeSandboxState.fogMode,
        labelOf = { it.localizedLabel() },
        onSelected = { mode ->
            freeSandboxState.fogMode = mode
            onStateMutated()
        },
    )

    val startingCreditsDropdown = UnifiedDropdown(
        skin = skin,
        themeProvider = themeProvider,
        iconsProvider = iconsProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SkirmishStartingCredits.SANDBOX_OPTIONS,
        initialSelected = freeSandboxState.startingCredits,
        labelOf = { it.localizedLabel() },
        useMonoFont = true,
        onSelected = { credits ->
            freeSandboxState.startingCredits = credits
            onStateMutated()
        },
    )

    val fastBuildSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = listOf(false, true),
        initialSelected = freeSandboxState.fastBuild,
        labelOf = { on -> UiStrings.get(if (on) "common.on" else "common.off") },
        onSelected = { on ->
            freeSandboxState.fastBuild = on
            onStateMutated()
        },
    )

    val cameraControlSegmented = SegmentedControl(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        options = SandboxCameraMode.entries,
        initialSelected = cameraMode,
        labelOf = { it.localizedLabel() },
        onSelected = { selected ->
            cameraMode = selected
            onStateMutated()
        },
    )

    val slotTableView = MatchSlotTableView(
        skin = skin,
        lobbyState = freeSandboxState,
        themeProvider = themeProvider,
        iconsProvider = iconsProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        onStateChanged = { onStateMutated() },
    )

    val freePreviewActor = TacticalBattlefieldPreviewActor(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        mapEntryProvider = { freeSandboxState.selectedMap },
        seatsProvider = { freeSandboxState.seats },
        maxSpawnsProvider = { freeSandboxState.availableSpawnCount },
    )

    val scenePreviewActor = LockedScenePreviewActor(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        choiceProvider = { testScenes.choice },
        errorProvider = { localErrorNotice ?: testScenes.error },
        fallbackMapProvider = { freeSandboxState.selectedMap.mapDefinition },
    )

    val selectMapButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("lobby.selectMap") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            onOpenMapPicker(freeSandboxState.selectedMap.id) { chosen ->
                applySelectedMap(chosen)
            }
        },
    )

    val freeTmxPathInput = OutGameTextInput(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        placeholderKey = "lobby.tmxPathPlaceholder",
        onEnterPressed = { loadPathFromActiveInput() },
    )

    val sceneJsonPathInput = OutGameTextInput(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        placeholderKey = "sandbox.jsonPathPlaceholder",
        onEnterPressed = { loadPathFromActiveInput() },
    )

    val loadPathButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = { UiStrings.get("common.load") },
        variant = OutGameButton.Variant.SECONDARY,
        onClick = { loadPathFromActiveInput() },
    )

    val primaryLaunchButton = OutGameButton(
        skin = skin,
        themeProvider = themeProvider,
        fontsProvider = fontsProvider,
        fallbackFontProvider = fallbackFontProvider,
        labelProvider = {
            if (currentMode == TestSandboxMode.TEST_SCENES) {
                UiStrings.get("sandbox.loadScene")
            } else {
                UiStrings.get("sandbox.enterFree")
            }
        },
        variant = OutGameButton.Variant.PRIMARY,
        onClick = {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage?.unfocusAll()
            if (currentMode == TestSandboxMode.TEST_SCENES) {
                if (canLoadSelectedTestScene()) {
                    onLoadTestScene(followScriptedCamera)
                }
            } else if (canEnterFreeSandbox()) {
                onEnterFreeSandbox(freeSandboxState.buildLaunchConfig())
            }
        },
    )

    private data class PresetSceneSpec(
        val actionId: String,
        val codeProvider: () -> String,
        val titleProvider: () -> String,
        val enabledProvider: () -> Boolean,
        val isActiveProvider: () -> Boolean,
        val onTrigger: () -> Unit,
    )

    private val presetSpecs = ArrayList<PresetSceneSpec>()
    private val presetRowActors = ArrayList<SceneRowActor>()
    private val sceneListGroup = Group().apply { setTransform(false) }

    // Layout coordinates inside panelShell
    private var leftColW = 612f
    private var rulesDividerY = 0f
    private var modeLabelX = 0f
    private var modeLabelY = 0f
    private var fogLabelX = 0f
    private var fogLabelY = 0f
    private var fundsLabelX = 0f
    private var fundsLabelY = 0f
    private var fastLabelX = 0f
    private var fastLabelY = 0f
    private var camLabelX = 0f
    private var camLabelY = 0f
    private var sceneHeaderBaselineY = 0f
    private var rightPadX = 0f
    private var rightInnerW = 400f
    private var rightHeaderBaselineY = 0f

    private val panelOverlayLabels = object : Actor() {
        init {
            touchable = Touchable.disabled
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val ox = x
            val oy = y

            OutGameDraw.fillRect(batch, white, ox + leftColW, oy + 1f, 1f, panelShell.height - 2f, theme.colors.line, parentAlpha)
            OutGameDraw.fillRect(batch, white, ox + 20f, oy + rulesDividerY, leftColW - 40f, 1f, theme.colors.line, parentAlpha)

            val lblFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            val prevLbl = lblFont.color.cpy()
            val dim = theme.colors.dim
            lblFont.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)

            if (development) {
                lblFont.draw(batch, UiStrings.get("sandbox.mode"), ox + modeLabelX, oy + modeLabelY)
            }
            if (currentMode == TestSandboxMode.FREE_SANDBOX) {
                lblFont.draw(batch, UiStrings.get("lobby.fog"), ox + fogLabelX, oy + fogLabelY)
                lblFont.draw(batch, UiStrings.get("lobby.startingFunds"), ox + fundsLabelX, oy + fundsLabelY)
                lblFont.draw(batch, UiStrings.get("sandbox.fastBuild"), ox + fastLabelX, oy + fastLabelY)
            } else {
                lblFont.draw(batch, UiStrings.get("sandbox.cameraControl"), ox + camLabelX, oy + camLabelY)
            }
            lblFont.color = prevLbl

            val secFont = fontsProvider()?.sectionTitle ?: fontsProvider()?.title ?: fallbackFontProvider()
            val strong = theme.outGame.textStrong

            if (currentMode == TestSandboxMode.TEST_SCENES) {
                // Left section header: `固定种子场景` + right mono `SHA-256 锁定输入`
                val prevSecL = secFont.color.cpy()
                secFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
                secFont.draw(batch, UiStrings.get("sandbox.lockedScenes"), ox + 20f, oy + sceneHeaderBaselineY)
                secFont.color = prevSecL

                val badgeFont = fontsProvider()?.small ?: lblFont
                val badgeText = UiStrings.get("sandbox.sha256Badge")
                glyphLayout.setText(badgeFont, badgeText)
                val prevB = badgeFont.color.cpy()
                badgeFont.setColor(dim.r, dim.g, dim.b, dim.a * parentAlpha)
                badgeFont.draw(batch, badgeText, ox + leftColW - 20f - glyphLayout.width, oy + sceneHeaderBaselineY - 2f)
                badgeFont.color = prevB
            }

            // Right column header
            val rightTitle = if (currentMode == TestSandboxMode.TEST_SCENES) {
                UiStrings.get("sandbox.scenePreview")
            } else {
                UiStrings.get("lobby.battlefieldMap")
            }
            val prevSecR = secFont.color.cpy()
            glyphLayout.setText(secFont, rightTitle)
            val rightTitleW = glyphLayout.width
            secFont.setColor(strong.r, strong.g, strong.b, strong.a * parentAlpha)
            secFont.draw(batch, rightTitle, ox + rightPadX, oy + rightHeaderBaselineY)
            secFont.color = prevSecR

            val rightInfo = if (currentMode == TestSandboxMode.TEST_SCENES) {
                val chosen = testScenes.choice
                if (chosen != null) {
                    UiStrings.format(
                        "sandbox.scene.tilesFormat",
                        "ID" to chosen.id,
                        "W" to chosen.scenario.map.widthTiles,
                        "H" to chosen.scenario.map.heightTiles,
                    )
                } else {
                    UiStrings.get("sandbox.scene.noneSelected")
                }
            } else {
                UiStrings.format(
                    "sandbox.mapNoWinFormat",
                    "NAME" to freeSandboxState.selectedMap.localizedTitle(),
                )
            }
            val infoFont = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
            val fittedRightInfo = OutGameDraw.ellipsizeMiddle(infoFont, rightInfo, (rightInnerW - rightTitleW - 14f).coerceAtLeast(48f), glyphLayout)
            glyphLayout.setText(infoFont, fittedRightInfo)
            val prevInfo = infoFont.color.cpy()
            val infoCol = if (currentMode == TestSandboxMode.TEST_SCENES && testScenes.choice == null) dim else theme.colors.value
            infoFont.setColor(infoCol.r, infoCol.g, infoCol.b, infoCol.a * parentAlpha)
            infoFont.draw(batch, fittedRightInfo, ox + rightPadX + rightInnerW - glyphLayout.width, oy + rightHeaderBaselineY)
            infoFont.color = prevInfo

            if (currentMode == TestSandboxMode.FREE_SANDBOX) {
                val errMsg = localErrorNotice ?: statusMessageProvider()?.takeIf { it.isNotBlank() }
                if (!errMsg.isNullOrBlank()) {
                    val noteFont = fontsProvider()?.small ?: lblFont
                    val prevN = noteFont.color.cpy()
                    val col = if (localErrorNotice != null) theme.colors.foe else theme.colors.amber
                    noteFont.setColor(col.r, col.g, col.b, col.a * parentAlpha)
                    noteFont.draw(batch, errMsg, ox + rightPadX + 8f, oy + freePreviewActor.y + 18f)
                    noteFont.color = prevN
                }
            }

            batch.packedColor = saved
        }
    }

    init {
        setTransform(false)
        buildPresetSceneRows()

        panelShell.addActor(panelOverlayLabels)
        panelShell.addActor(modeSegmented)
        panelShell.addActor(fogModeSegmented)
        panelShell.addActor(startingCreditsDropdown)
        panelShell.addActor(fastBuildSegmented)
        panelShell.addActor(cameraControlSegmented)
        panelShell.addActor(slotTableView)
        panelShell.addActor(sceneListGroup)
        panelShell.addActor(freePreviewActor)
        panelShell.addActor(scenePreviewActor)
        panelShell.addActor(selectMapButton)
        panelShell.addActor(freeTmxPathInput)
        panelShell.addActor(sceneJsonPathInput)
        panelShell.addActor(loadPathButton)
        panelShell.addActor(primaryLaunchButton)

        addActor(panelShell)
        addActor(topBar)
        syncModeVisibility()
    }

    private fun normalizeSeed(path: Path?): Path? =
        path?.toAbsolutePath()?.normalize()

    private fun seedDisplayCode(seed: Path?, fallbackCode: String): String {
        val raw = seed?.fileName?.toString()?.removeSuffix(".json")?.trim().orEmpty()
        return if (raw.isNotEmpty()) raw.uppercase(Locale.ROOT) else fallbackCode
    }

    private fun buildPresetSceneRows() {
        presetSpecs.clear()
        presetRowActors.clear()
        sceneListGroup.clearChildren()
        if (!development) return

        val normPrimary = normalizeSeed(testScenarioSeed)
        val normSpider = normalizeSeed(alternateStartSeed)
        val normCamera = normalizeSeed(cameraStartSeed)

        // 1. `test-scene-use` (100% compatible with TestScenarioPanel)
        presetSpecs += PresetSceneSpec(
            actionId = "test-scene-use",
            codeProvider = { seedDisplayCode(testScenarioSeed, "SEED-01") },
            titleProvider = {
                if (alternateStartSeed == null) {
                    UiStrings.get("sandbox.scene.useDefault")
                } else {
                    UiStrings.get("sandbox.scene.use")
                }
            },
            enabledProvider = { editable && testScenarioSeed != null },
            isActiveProvider = {
                testScenes.selected && normPrimary != null && normalizeSeed(testScenes.choice?.seed) == normPrimary
            },
            onTrigger = {
                localErrorNotice = null
                testScenarioSeed?.let(testScenes::choose)
                onTestSceneMutated()
            },
        )

        // 2. `test-scene-spider` OR `test-scene-custom` (exact TestScenarioPanel branching)
        if (alternateStartSeed == null) {
            presetSpecs += PresetSceneSpec(
                actionId = "test-scene-custom",
                codeProvider = { "CUSTOM" },
                titleProvider = { UiStrings.get("sandbox.scene.custom") },
                enabledProvider = { editable && testScenes.selected },
                isActiveProvider = { !testScenes.selected },
                onTrigger = {
                    localErrorNotice = null
                    testScenes.useCustomContent()
                    onTestSceneMutated()
                },
            )
        } else {
            presetSpecs += PresetSceneSpec(
                actionId = "test-scene-spider",
                codeProvider = { seedDisplayCode(alternateStartSeed, "SPIDER-02") },
                titleProvider = { UiStrings.get("sandbox.scene.spider") },
                enabledProvider = { editable },
                isActiveProvider = {
                    testScenes.selected && normSpider != null && normalizeSeed(testScenes.choice?.seed) == normSpider
                },
                onTrigger = {
                    localErrorNotice = null
                    testScenes.choose(alternateStartSeed)
                    onTestSceneMutated()
                },
            )
        }

        // 3. `test-scene-camera` (when cameraStartSeed is provided)
        if (cameraStartSeed != null) {
            presetSpecs += PresetSceneSpec(
                actionId = "test-scene-camera",
                codeProvider = { seedDisplayCode(cameraStartSeed, "CAMERA-03") },
                titleProvider = { UiStrings.get("sandbox.scene.camera") },
                enabledProvider = { editable },
                isActiveProvider = {
                    testScenes.selected && normCamera != null && normalizeSeed(testScenes.choice?.seed) == normCamera
                },
                onTrigger = {
                    localErrorNotice = null
                    testScenes.choose(cameraStartSeed)
                    onTestSceneMutated()
                },
            )
        }

        presetSpecs.forEachIndexed { idx, spec ->
            val actor = SceneRowActor(idx + 1, spec)
            presetRowActors += actor
            sceneListGroup.addActor(actor)
        }
    }

    fun selectMode(mode: TestSandboxMode) {
        val target = if (!development) TestSandboxMode.FREE_SANDBOX else mode
        if (currentMode == target) {
            modeSegmented.setSelectedSilently(target)
            syncModeVisibility()
            return
        }
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        stage?.unfocusAll()
        localErrorNotice = null
        currentMode = target
        modeSegmented.setSelectedSilently(target)
        syncModeVisibility()
        if (width > 0f && height > 0f) {
            updateLayout(width, height)
        }
        onStateMutated()
    }

    fun selectCameraMode(mode: SandboxCameraMode) {
        cameraMode = mode
        cameraControlSegmented.setSelectedSilently(mode)
        onStateMutated()
    }

    fun triggerSceneActionForTest(actionId: String): Boolean {
        val spec = presetSpecs.firstOrNull { it.actionId == actionId } ?: return false
        if (!spec.enabledProvider()) return false
        spec.onTrigger()
        return true
    }

    fun applySelectedMap(entry: MapPickerEntry) {
        localErrorNotice = null
        freeSandboxState.selectMapEntry(entry)
        slotTableView.syncRows(forceRebuild = false)
        onStateMutated()
    }

    fun loadPathFromActiveInput(): Boolean {
        if (currentMode == TestSandboxMode.TEST_SCENES) {
            if (!development || !editable) return false
            val raw = sceneJsonPathInput.text.trim()
            if (raw.isEmpty()) return false
            localErrorNotice = null
            val parsedPath = runCatching { Paths.get(raw) }.getOrElse { err ->
                localErrorNotice = err.message ?: UiStrings.get("fallback.unknownReason")
                onStateMutated()
                return false
            }
            testScenes.choose(parsedPath)
            onTestSceneMutated()
            return testScenes.choice != null && testScenes.error == null
        } else {
            val raw = freeTmxPathInput.text.trim()
            if (raw.isEmpty()) return false
            val loaded = onLoadCustomTmxPath(raw)
            return if (loaded != null) {
                applySelectedMap(loaded)
                true
            } else {
                localErrorNotice = statusMessageProvider()
                onStateMutated()
                false
            }
        }
    }

    private fun canEnterFreeSandbox(): Boolean =
        editable && canStartBaseProvider() && freeSandboxState.selectedMap.tmxPath != null

    private fun canLoadSelectedTestScene(): Boolean =
        development && editable && canStartTestSceneProvider() && testScenes.selected && testScenes.choice != null && testScenes.error == null

    fun syncControlsFromState() {
        if (!development && currentMode != TestSandboxMode.FREE_SANDBOX) {
            currentMode = TestSandboxMode.FREE_SANDBOX
        }
        modeSegmented.setOptionsAndSelection(
            if (development) TestSandboxMode.entries else listOf(TestSandboxMode.FREE_SANDBOX),
            currentMode,
        )
        fogModeSegmented.setOptionsAndSelection(SkirmishFogMode.entries, freeSandboxState.fogMode)
        startingCreditsDropdown.setOptionsAndSelection(SkirmishStartingCredits.SANDBOX_OPTIONS, freeSandboxState.startingCredits)
        fastBuildSegmented.setOptionsAndSelection(listOf(false, true), freeSandboxState.fastBuild)
        cameraControlSegmented.setOptionsAndSelection(SandboxCameraMode.entries, cameraMode)
        slotTableView.syncRows(forceRebuild = false)
        syncModeVisibility()
        syncInteractiveState()
    }

    private fun syncModeVisibility() {
        val isFree = currentMode == TestSandboxMode.FREE_SANDBOX
        modeSegmented.isVisible = development
        fogModeSegmented.isVisible = isFree
        startingCreditsDropdown.isVisible = isFree
        fastBuildSegmented.isVisible = isFree
        cameraControlSegmented.isVisible = !isFree && development

        slotTableView.isVisible = isFree
        sceneListGroup.isVisible = !isFree && development

        freePreviewActor.isVisible = isFree
        scenePreviewActor.isVisible = !isFree && development
        selectMapButton.isVisible = isFree
        freeTmxPathInput.isVisible = isFree
        sceneJsonPathInput.isVisible = !isFree && development
    }

    private fun syncInteractiveState() {
        modeSegmented.enabled = editable && development
        fogModeSegmented.enabled = editable
        startingCreditsDropdown.enabled = editable
        fastBuildSegmented.enabled = editable
        cameraControlSegmented.enabled = editable
        slotTableView.enabled = editable
        selectMapButton.enabled = editable
        freeTmxPathInput.enabled = editable
        sceneJsonPathInput.enabled = editable
        loadPathButton.enabled = editable
        primaryLaunchButton.enabled = if (currentMode == TestSandboxMode.TEST_SCENES) {
            canLoadSelectedTestScene()
        } else {
            canEnterFreeSandbox()
        }
    }

    fun updateLayout(stageWidth: Float, stageHeight: Float) {
        setBounds(0f, 0f, stageWidth, stageHeight)
        topBar.updateBounds(stageWidth, stageHeight)
        panelShell.updateShellBounds(stageWidth, stageHeight)
        syncModeVisibility()
        syncInteractiveState()

        val shellW = panelShell.width
        val shellH = panelShell.height
        panelOverlayLabels.setBounds(0f, 0f, shellW, shellH)

        leftColW = when {
            shellW >= 1220f -> 612f
            shellW >= 1120f -> 584f
            else -> (shellW * 0.56f).roundToInt().toFloat().coerceIn(488f, 584f)
        }
        val leftPad = 20f
        val leftInnerW = leftColW - leftPad * 2f

        val font = fontsProvider()?.keyBold ?: fontsProvider()?.command ?: fallbackFontProvider()
        fun measureSegmentedWidth(labels: List<String>, extraPerSeg: Float = 13f): Float {
            var sum = 0f
            for (l in labels) {
                glyphLayout.setText(font, l)
                sum += glyphLayout.width + extraPerSeg
            }
            return sum.roundToInt().toFloat()
        }

        val ctrlH = 30f
        val gap = 8f

        if (currentMode == TestSandboxMode.FREE_SANDBOX) {
            val modeW = if (development) measureSegmentedWidth(TestSandboxMode.entries.map { it.localizedLabel() }, 13f) else 0f
            val fogW = measureSegmentedWidth(SkirmishFogMode.entries.map { it.localizedLabel() }, 12f)
            val lblFont = fontsProvider()?.small ?: fontsProvider()?.body ?: fallbackFontProvider()
            glyphLayout.setText(lblFont, UiStrings.get("lobby.startingFunds"))
            val fundsW = max(82f, (glyphLayout.width + 6f).roundToInt().toFloat())
            val fastW = measureSegmentedWidth(listOf(UiStrings.get("common.off"), UiStrings.get("common.on")), 16f).coerceAtLeast(66f)
            val neededSingleRow = (if (development) modeW + gap else 0f) + fogW + gap + fundsW + gap + fastW

            if (neededSingleRow <= leftInnerW) {
                val ctrlY = shellH - 18f - 18f - ctrlH
                val lblY = ctrlY + ctrlH + 15f
                var cx = leftPad
                if (development) {
                    modeLabelX = cx
                    modeLabelY = lblY
                    modeSegmented.setBounds(cx, ctrlY, modeW, ctrlH)
                    cx += modeW + gap
                }
                fogLabelX = cx
                fogLabelY = lblY
                fogModeSegmented.setBounds(cx, ctrlY, fogW, ctrlH)
                cx += fogW + gap

                fundsLabelX = cx
                fundsLabelY = lblY
                startingCreditsDropdown.setBounds(cx, ctrlY, fundsW, ctrlH)

                val fastX = leftPad + leftInnerW - fastW
                fastLabelX = fastX
                fastLabelY = lblY
                fastBuildSegmented.setBounds(fastX, ctrlY, fastW, ctrlH)
                rulesDividerY = ctrlY - 14f
            } else {
                val row1CtrlY = shellH - 16f - 16f - ctrlH
                val row1LblY = row1CtrlY + ctrlH + 13f
                var cx = leftPad
                if (development) {
                    modeLabelX = cx
                    modeLabelY = row1LblY
                    modeSegmented.setBounds(cx, row1CtrlY, modeW, ctrlH)
                    cx += modeW + gap
                }
                fundsLabelX = cx
                fundsLabelY = row1LblY
                startingCreditsDropdown.setBounds(cx, row1CtrlY, fundsW, ctrlH)

                val fastX = leftPad + leftInnerW - fastW
                fastLabelX = fastX
                fastLabelY = row1LblY
                fastBuildSegmented.setBounds(fastX, row1CtrlY, fastW, ctrlH)

                val row2CtrlY = row1CtrlY - 24f - ctrlH
                fogLabelX = leftPad
                fogLabelY = row2CtrlY + ctrlH + 14f
                fogModeSegmented.setBounds(leftPad, row2CtrlY, fogW.coerceAtMost(leftInnerW), ctrlH)
                rulesDividerY = row2CtrlY - 12f
            }

            val tableBottomPad = 14f
            val tableTopY = rulesDividerY - 14f
            slotTableView.setBounds(leftPad, tableBottomPad, leftInnerW, max(140f, tableTopY - tableBottomPad))
        } else {
            val modeW = measureSegmentedWidth(TestSandboxMode.entries.map { it.localizedLabel() }, 14f)
            val camW = measureSegmentedWidth(SandboxCameraMode.entries.map { it.localizedLabel() }, 16f)
            val ctrlY = shellH - 18f - 18f - ctrlH
            val lblY = ctrlY + ctrlH + 15f

            modeLabelX = leftPad
            modeLabelY = lblY
            modeSegmented.setBounds(leftPad, ctrlY, modeW, ctrlH)

            val camX = leftPad + modeW + 12f
            camLabelX = camX
            camLabelY = lblY
            cameraControlSegmented.setBounds(camX, ctrlY, camW.coerceAtMost(leftInnerW - modeW - 12f), ctrlH)
            rulesDividerY = ctrlY - 14f

            sceneHeaderBaselineY = rulesDividerY - 16f
            val listTopY = sceneHeaderBaselineY - 22f
            val listBottomPad = 16f
            val listH = max(120f, listTopY - listBottomPad)
            sceneListGroup.setBounds(leftPad, listBottomPad, leftInnerW, listH)

            val rowH = 44f
            val rowGap = 7f
            var curTop = listH
            for (rowActor in presetRowActors) {
                curTop -= rowH
                rowActor.setBounds(0f, curTop, leftInnerW, rowH)
                curTop -= rowGap
            }
        }

        // Right wide column
        val rPad = 22f
        rightPadX = leftColW + rPad
        rightInnerW = max(220f, shellW - leftColW - rPad * 2f)
        rightHeaderBaselineY = shellH - 18f

        val startBtnH = 42f
        val startBtnY = 18f
        primaryLaunchButton.setBounds(rightPadX, startBtnY, rightInnerW, startBtnH)

        val pathRowH = 32f
        val pathRowY = startBtnY + startBtnH + 10f
        val loadBtnW = 64f
        freeTmxPathInput.setBounds(rightPadX, pathRowY, rightInnerW - loadBtnW - 8f, pathRowH)
        sceneJsonPathInput.setBounds(rightPadX, pathRowY, rightInnerW - loadBtnW - 8f, pathRowH)
        loadPathButton.setBounds(rightPadX + rightInnerW - loadBtnW, pathRowY, loadBtnW, pathRowH)

        val previewTopY = shellH - 42f
        if (currentMode == TestSandboxMode.FREE_SANDBOX) {
            val selectMapBtnH = 34f
            val selectMapBtnY = pathRowY + pathRowH + 8f
            selectMapButton.setBounds(rightPadX, selectMapBtnY, rightInnerW, selectMapBtnH)
            val previewBottomY = selectMapBtnY + selectMapBtnH + 12f
            freePreviewActor.setBounds(rightPadX, previewBottomY, rightInnerW, max(120f, previewTopY - previewBottomY))
        } else {
            val previewBottomY = pathRowY + pathRowH + 12f
            scenePreviewActor.setBounds(rightPadX, previewBottomY, rightInnerW, max(120f, previewTopY - previewBottomY))
        }
    }

    internal fun drawnActions(): List<DrawnAction> {
        if (!isVisible) return emptyList()
        val out = ArrayList<DrawnAction>(48)
        out += topBar.drawnBackAction()
        if (development && modeSegmented.isVisible) {
            modeSegmented.segmentActors.getOrNull(0)?.let { freeSeg ->
                out += DrawnAction("free-sandbox-tab", freeSeg, UiStrings.get("sandbox.mode.free"), enabled = modeSegmented.enabled)
            }
            modeSegmented.segmentActors.getOrNull(1)?.let { sceneSeg ->
                out += DrawnAction("test-scenes-tab", sceneSeg, UiStrings.get("sandbox.mode.scenes"), enabled = modeSegmented.enabled)
            }
        }
        if (currentMode == TestSandboxMode.FREE_SANDBOX) {
            out += DrawnAction("match-vision-mode", fogModeSegmented, freeSandboxState.fogMode.localizedLabel(), enabled = fogModeSegmented.enabled)
            out += DrawnAction("sandbox-starting-credits", startingCreditsDropdown, freeSandboxState.startingCredits.localizedLabel(), enabled = startingCreditsDropdown.enabled)
            out += DrawnAction("sandbox-fast-build", fastBuildSegmented, UiStrings.get(if (freeSandboxState.fastBuild) "common.on" else "common.off"), enabled = fastBuildSegmented.enabled)
            out += slotTableView.drawnActions("sandbox")
            out += DrawnAction("skirmish-maps", selectMapButton, UiStrings.get("lobby.selectMap"), enabled = selectMapButton.enabled)
            out += DrawnAction("sandbox-tmx-input", freeTmxPathInput, freeTmxPathInput.text, enabled = freeTmxPathInput.enabled)
            out += DrawnAction("sandbox-tmx-load", loadPathButton, UiStrings.get("common.load"), enabled = loadPathButton.enabled)
            out += DrawnAction("start-match", primaryLaunchButton, UiStrings.get("sandbox.enterFree"), enabled = primaryLaunchButton.enabled)
        } else {
            out += DrawnAction("sandbox-camera-mode", cameraControlSegmented, cameraMode.localizedLabel(), enabled = cameraControlSegmented.enabled)
            for (row in presetRowActors) {
                out += DrawnAction(
                    actionId = row.spec.actionId,
                    actor = row,
                    text = "${row.spec.codeProvider()} ${row.spec.titleProvider()}",
                    enabled = row.spec.enabledProvider(),
                )
            }
            out += DrawnAction("sandbox-json-input", sceneJsonPathInput, sceneJsonPathInput.text, enabled = sceneJsonPathInput.enabled)
            out += DrawnAction("sandbox-json-load", loadPathButton, UiStrings.get("common.load"), enabled = loadPathButton.enabled)
            out += DrawnAction("start-match", primaryLaunchButton, UiStrings.get("sandbox.loadScene"), enabled = primaryLaunchButton.enabled)
        }
        return out
    }

    override fun close() {
        UnifiedDropdown.closeAllOpenDropdowns(stage)
        freePreviewActor.close()
        scenePreviewActor.close()
    }

    private inner class SceneRowActor(
        private val ordinal: Int,
        val spec: PresetSceneSpec,
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
                    if (pointer != 0 || !spec.enabledProvider()) return false
                    pressed = true
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0) return
                    val wasPressed = pressed
                    pressed = false
                    if (wasPressed && spec.enabledProvider() && x in 0f..width && y in 0f..height) {
                        spec.onTrigger()
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            val saved = batch.packedColor
            val theme = themeProvider.currentTheme()
            val white = skin.getRegion("white")
            val isEnabled = spec.enabledProvider()
            val isActive = spec.isActiveProvider()
            val isHover = isEnabled && (hovered || pressed)

            val bg = when {
                isActive -> theme.colors.cellHover
                isHover -> theme.colors.cell
                else -> theme.colors.well
            }
            val border = when {
                isActive -> theme.colors.rust
                isHover -> theme.colors.value
                else -> theme.colors.hair
            }

            OutGameDraw.fillRect(batch, white, x, y, width, height, bg, parentAlpha)
            OutGameDraw.strokeRect(batch, white, x, y, width, height, 1f, border, parentAlpha)
            if (isActive) {
                OutGameDraw.fillRect(batch, white, x, y, 3f, height, theme.colors.rust, parentAlpha)
            }

            val rnText = String.format(Locale.ROOT, "%02d", ordinal)
            val monoFont = fontsProvider()?.countMono ?: fontsProvider()?.smallMono ?: fallbackFontProvider()
            glyphLayout.setText(monoFont, rnText)
            val prevM = monoFont.color.cpy()
            val rnCol = if (isActive) theme.colors.rust else theme.colors.dim
            monoFont.setColor(rnCol.r, rnCol.g, rnCol.b, rnCol.a * parentAlpha)
            monoFont.draw(batch, rnText, x + 12f, y + (height + glyphLayout.height) * 0.5f)

            val codeText = spec.codeProvider()
            glyphLayout.setText(monoFont, codeText)
            val codeCol = if (!isEnabled) theme.colors.dim else theme.colors.value
            monoFont.setColor(codeCol.r, codeCol.g, codeCol.b, codeCol.a * parentAlpha)
            val codeX = x + 42f
            monoFont.draw(batch, codeText, codeX, y + (height + glyphLayout.height) * 0.5f)
            val codeEndX = codeX + glyphLayout.width + 14f
            monoFont.color = prevM

            val titleFont = fontsProvider()?.body ?: fallbackFontProvider()
            val titleText = spec.titleProvider()
            glyphLayout.setText(titleFont, titleText)
            val prevT = titleFont.color.cpy()
            val titleCol = when {
                !isEnabled -> theme.colors.dim
                isActive -> theme.colors.boneHi
                else -> theme.colors.text
            }
            titleFont.setColor(titleCol.r, titleCol.g, titleCol.b, titleCol.a * parentAlpha)
            titleFont.draw(batch, titleText, codeEndX.coerceAtLeast(x + 178f), y + (height + glyphLayout.height) * 0.5f)
            titleFont.color = prevT

            batch.packedColor = saved
        }
    }
}
