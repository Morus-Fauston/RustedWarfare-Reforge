package com.rustedwarfare.reforge.client

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.SandboxCommand
import com.rustedwarfare.reforge.core.sim.SandboxCommandReceipt
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.ScreenViewport
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

class BootstrapScreen(
    private val initialRoot: Path = Paths.get("TestLab/local/P001-移动沙盒输入"),
    private val evidence: ClientEvidence = ClientEvidence.NONE,
    private val touchControls: Boolean = false,
    private val visualSettings: VisualAssetSettings? = null,
    private val soundFactory: PlaybackSoundFactory = GdxPlaybackSoundFactory,
    private val testScenarioSeed: Path? = null,
    private val selectDefaultTestScenario: Boolean = false,
    private val alternateStartSeed: Path? = null,
    private val development: Boolean = false,
    private val initialContent: Boolean = true,
    openSetup: Boolean = true,
    private val cameraStartSeed: Path? = null,
    private val initialSkirmishMap: Path? = null,
    private val initialUiThemePacks: List<Path> = emptyList(),
    private val initialCatalogSceneId: String? = null,
    val userSettingsStore: UserSettingsStore? = null,
) : ApplicationAdapter() {
    private enum class StartupPage { HOME, SETUP, CONTENT, RESOURCES, DEVELOPMENT, MAPS }
    private data class ContentCandidate(val root: Path, val content: ContentPackChoice?)
    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var skin: Skin
    private lateinit var stage: Stage
    private lateinit var battleActions: BattleActionList
    private lateinit var hudActionPanel: HudActionPanel
    private lateinit var minimapActor: Actor
    private lateinit var hudOverlay: InGameHudOverlay
    private lateinit var hudGroupBar: HudControlGroupBar
    private lateinit var hudDebugOverlay: HudDebugOverlay
    private lateinit var hudConsole: HudSandboxConsole
    private var activeBuiltInThemeId: BuiltInUiThemeId = userSettingsStore?.effective?.themeId ?: BuiltInUiThemeId.DARK
    private var userScaleMultiplier: Float = userSettingsStore?.effective?.uiScaleMultiplier ?: 1.0f
    private var activeUiTheme: UiTheme = composeUiThemeForBuiltIn(activeBuiltInThemeId, null)
    private var activeThemeAssetResolver: UiThemeAssetResolver = UiThemeAssetResolver(initialUiThemePacks)
    private val uiThemeProvider: UiThemeProvider = UiThemeProvider { activeUiTheme }
    val currentBuiltInThemeId: BuiltInUiThemeId get() = activeBuiltInThemeId
    val currentUserScaleMultiplier: Float get() = userScaleMultiplier
    private var commandIcons: HudCommandIconAtlas? = null
    private val hudEvents = HudEventStream()
    private var hudMenuOpen = false
    private var lastObservedEventSampleId: String? = null
    private val lastObservedActionResultIds = LinkedHashSet<String>()
    private var lastArrivalNoticeKey: String? = null
    private var lastHudSnapshot: HudSnapshot? = null
    private val viewport = ScreenViewport()
    private var uiMetrics: UiMetrics = UiScale.metrics(960, 640, touchControls, userScaleMultiplier = userScaleMultiplier)
    private var fontSet: UiFontSet? = null
    private var fallbackFont: BitmapFont? = null
    private val fontDebouncer = FontRebuildDebouncer()
    private val battlefieldCamera = OrthographicCamera()
    private val battlefieldWidth: Float get() = battlefieldCamera.viewportWidth.takeIf { it > 0f } ?: viewport.worldWidth
    private val battlefieldHeight: Float get() = battlefieldCamera.viewportHeight.takeIf { it > 0f } ?: viewport.worldHeight
    private val loader = SandboxLoader()
    private var session: SandboxSession? = null
    private var debugLayerState = HudDebugLayerState()
    private var f3Held = false
    private var f3ComboUsed = false
    private var lastFrameMs: Float = 0f
    private var maxFrameMsWindow: Float = 0f
    private var maxFrameMsResetTimer: Float = 0f
    private var lastCursorWorld: Position? = null
    private var activeMapId: String = "sandbox" 
    private var graphics: SandboxGraphics? = null
    private var scriptedCamera: OfflineCameraController? = null
    private var suspendedCamera: MapCamera? = null
    private val cameraJoystick = FixedCameraJoystick()
    private lateinit var cameraJoystickBox: CheckBox
    private lateinit var cameraJoystickArea: Actor
    private var sounds: SoundPlayback? = null
    private lateinit var deploymentBox: CheckBox
    private lateinit var noNukesBox: CheckBox
    private lateinit var commandField: TextField
    private lateinit var commandFeedbackLabel: Label
    private lateinit var commandHintLabel: Label
    private lateinit var commandPanel: Table
    private lateinit var commandPickHint: Label
    private val commandPickButtons = linkedMapOf<String, TextButton>()
    private var commandPickError: String? = null
    private var commandConsoleOpen = false
    private var swallowNextSlashTyped = false
    private lateinit var rootField: TextField
    private lateinit var titleLabel: Label
    private lateinit var selectedLabel: Label
    private lateinit var selectionHeading: Label
    private lateinit var productionNoticeLabel: Label
    private lateinit var statusLabel: Label
    private lateinit var stopButton: TextButton
    private lateinit var patrolButton: TextButton
    private lateinit var depthButton: TextButton
    private lateinit var depthInput: SubmarineDepthButtonInput
    private lateinit var flightInput: AmphibiousFlightButtonInput
    private lateinit var unloadInput: TransportUnloadButtonInput
    private val customActionButtons = linkedMapOf<String, TextButton>()
    private val customActionInputs = mutableListOf<CustomUnitActionButtonInput>()
    private var customActionHover: String? = null
    private val rateButtons = linkedMapOf<OfflineRate, TextButton>()
    private lateinit var simulationPauseButton: TextButton
    private lateinit var pacingLabel: Label
    private lateinit var patrolLabel: Label
    private lateinit var productionLabel: Label
    private lateinit var produceButton: TextButton
    private lateinit var cancelProductionButton: TextButton
    private lateinit var nativeInfo: Label
    private lateinit var nativeIcon: Image
    private lateinit var nativeTooltip: Table
    private lateinit var nativeTooltipIcon: Image
    private lateinit var nativeTooltipText: Label
    private data class FactoryActionCard(val table: Table, val icon: Image, val text: Label, val count: Label,
                                     val input: OwnedActionInput, var compact: Boolean? = null)
    private val productionCards = linkedMapOf<String, FactoryActionCard>()
    private val factoryUpgradeCards = linkedMapOf<String, FactoryActionCard>()
    private var factoryUpgradeHover: String? = null
    private lateinit var productionLayer: Group
    private lateinit var productionContent: Group
    private lateinit var productionScroll: ScrollPane
    private val productionCorner = Vector2()
    private var productionHover: String? = null
    private data class ConstructionCard(val table: Table, val icon: Image, val text: Label, val input: ConstructionButtonInput)
    private val constructionCards = linkedMapOf<String, ConstructionCard>()
    private lateinit var constructionLayer: Group
    private lateinit var constructionContent: Group
    private lateinit var constructionScroll: ScrollPane
    private val constructionCorner = Vector2()
    private var constructionHover: String? = null
    private lateinit var reclaimCard: Table
    private lateinit var reclaimText: Label
    private lateinit var reclaimInput: ReclaimButtonInput
    private var reclaimHover = false
    private lateinit var repairCard: Table
    private lateinit var repairText: Label
    private lateinit var repairInput: OwnedActionInput
    private var repairHover = false
    private var flightHover = false
    private var nativeSelection: Set<String> = emptySet()
    private val nativePanel: Boolean get() = false
    private val upgradeButtons = mutableMapOf<String, Pair<TextButton, TextButton>>()
    private val upgradeInputs = mutableListOf<UpgradeButtonInput>()
    private var upgradeHover: String? = null
    private lateinit var economicUpgradeCard: Table
    private lateinit var economicUpgradeIcon: Image
    private lateinit var economicUpgradeText: Label
    private lateinit var economicUpgradeProgress: ProgressBar
    private lateinit var economicUpgradeInput: EconomyUpgradeInput
    private var economicUpgradeHover = false
    private lateinit var modeBox: SelectBox<String>
    private lateinit var shareBox: CheckBox
    private lateinit var startButton: TextButton
    private lateinit var exitButton: TextButton
    private lateinit var mapInput: SandboxInput
    private lateinit var contentLabel: Label
    private val groupButtons = mutableListOf<TextButton>()
    private val visualState = visualSettings?.let(::VisualAssetSettingsSession)
    private var visualPanel: VisualAssetSettingsPanel? = null
    private val packState = ContentPackSelectionSession()
    private var packPanel: ContentPackPanel? = null
    private lateinit var visualsTab: TextButton
    private lateinit var packTab: TextButton
    private lateinit var setupButton: TextButton
    private lateinit var retryContentButton: TextButton
    private lateinit var menuBackButton: TextButton
    private lateinit var quitButton: TextButton
    private lateinit var previewHeading: Label
    private lateinit var startupTitle: Label
    private lateinit var startupStatus: Label
    private lateinit var startupNotice: ScrollPane
    private lateinit var startupFrame: Image
    private lateinit var mapPreview: Actor
    private lateinit var dynamicMapBackdrop: DynamicMapBackdrop
    private var outGameControlIcons: OutGameControlIcons? = null
    val skirmishLobbyState: SkirmishLobbyState = SkirmishLobbyState()
    var lastSkirmishLaunchConfig: SkirmishLaunchConfig? = null
        private set
    private var lobbyExplicitMapChosen: Boolean = false
    lateinit var mainMenuScreen: MainMenuScreen
        private set
    lateinit var mapPickerScreen: MapPickerScreen
        private set
    lateinit var skirmishLobbyScreen: SkirmishLobbyScreen
        private set
    private var startupPage = if (openSetup) StartupPage.SETUP else StartupPage.HOME
    private var settingsBack = startupPage
    private var activeSettingsPage: StartupPage = StartupPage.RESOURCES
    private var syncingShellRoute = false
    internal val currentStartupPageNameForTest: String get() = startupPage.name
    val appShell: AppShell = AppShell(
        initialRoute = if (openSetup) OutGameRoute.SKIRMISH_LOBBY else OutGameRoute.MAIN_MENU,
        onRouteChanged = { route -> syncStartupPageFromShell(route) },
        onCancelLoading = { if (loading) leaveMatch() },
        onRequestExitApp = { Gdx.app?.exit() },
    )
    init {
        userSettingsStore?.effective?.let { effective ->
            UiStrings.currentLanguage = effective.language
        }
    }
    private val qualification = SandboxLoader()
    private var contentInput: String? = null
    private var contentCandidate: ContentCandidate? = null
    private var qualifiedContent: Result<LoadedSandbox>? = null
    private val testScenes = TestScenarioMenu().apply {
        if (development && selectDefaultTestScenario) choose(requireNotNull(testScenarioSeed))
    }
    private var testScenePanel: TestScenarioPanel? = null
    private lateinit var testSceneTab: TextButton
    private val skirmishMapState = SkirmishMapSelectionState()
    private var skirmishMapPanel: SkirmishMapPanel? = null
    private lateinit var skirmishMapTab: TextButton
    private var matchStartupNotices: List<String> = emptyList()
    private var customRootText = if (initialContent) initialRoot.toAbsolutePath().normalize().toString() else ""
    private val launch = SandboxLaunchState(SandboxConfiguration(initialRoot.toAbsolutePath().normalize(), SandboxOptions(deploymentEnabled = development && !touchControls), visualState?.selection ?: VisualAssetSelection()))
    private var activeVisuals: PreparedVisualAssets? = null
    private var pendingLoad: SandboxLoad? = null
    private var loading = false
    private var loadingDrawn = false
    private var menuDrawn = false
    private var menuEpoch = 0L
    private var lastStartAvailable: Boolean? = null
    private var firstDrawnGeneration: Long? = null
    private var generation = 0L
    val inputGeneration: Long get() = launch.generation
    fun deployProjectiles(requestId: String, batch: SandboxCommand.ProjectileBatch): SandboxCommandReceipt {
        val active = checkNotNull(session) { "No active match" }
        check(development && active.canDeploy) { "Deployment is disabled" }
        return active.submitSandboxProjectiles(requestId, batch)
    }

    fun deploymentReceipt(sequence: Long): SandboxCommandReceipt? =
        session?.takeIf { development && it.canDeploy }?.sandboxReceipt(sequence)
    private var activeRoot: Path = initialRoot
    private val stepClock = OfflineStepClock()
    private var lastPacingFrame = OfflinePacingFrame(0, 0.0)
    private var ignoreNextHostFrame = true
    private var lastPacingRecordedNanos = 0L
    private var paused = false
    private var loadError: String? = null
    private var visualRecoveryFailed = false
    private var presentationReady = false
    private var disposed = false
    private data class HudState(val selected: com.rustedwarfare.reforge.core.sim.UnitObservation?,
        val selectedIds: Set<String>, val groups: List<Int>, val feedback: String?, val step: Long?, val canEdit: Boolean,
        val loading: Boolean, val preparationStatus: String?, val error: String?, val hasSession: Boolean,
        val balance: Long?, val queue: com.rustedwarfare.reforge.core.sim.FactoryQueueObservation?,
        val patrolMode: Boolean, val patrolPoints: Int, val reclaimMode: Boolean, val repairMode: Boolean,
        val workerQueueText: String?, val menu: ActionMenuView?,
        val hudMenuOpen: Boolean, val simPaused: Boolean, val simRate: OfflineRate, val language: UiLanguage,
        val constructionTypeId: String?, val placementState: PlacementState?, val groundActionName: String?, val sandboxPick: SandboxPick?,
        val debugLayerState: HudDebugLayerState = HudDebugLayerState())
    private var lastHudState: HudState? = null
    private val drawnActionLayout = DrawnActionLayout()

    override fun create() {
        createPresentation()
        updateHud()
    }

    private fun createPresentation() {
        lastHudState = null
        drawnActionLayout.invalidate()
        val created = mutableListOf<() -> Unit>()
        try {
        val initW = Gdx.graphics?.width?.takeIf { it > 0 } ?: 960
        val initH = Gdx.graphics?.height?.takeIf { it > 0 } ?: 640
        val initPpc = Gdx.graphics?.ppcY?.takeIf { it > 0f } ?: 0f
        val initPpi = Gdx.graphics?.ppiY?.takeIf { it > 0f } ?: 0f
        uiMetrics = UiScale.metricsFromDensity(initW, initH, touchControls, initPpc, initPpi, userScaleMultiplier)
        viewport.unitsPerPixel = 1f / uiMetrics.scale
        viewport.update(initW, initH, true)
        battlefieldCamera.setToOrtho(false, viewport.worldWidth, viewport.worldHeight)
        batch = SpriteBatch().also {
            it.setBlendFunctionSeparate(
                GL20.GL_SRC_ALPHA,
                GL20.GL_ONE_MINUS_SRC_ALPHA,
                GL20.GL_ONE,
                GL20.GL_ONE_MINUS_SRC_ALPHA,
            )
            created += { it.dispose() }
        }
        shapes = ShapeRenderer().also { created += { it.dispose() } }
        val createdFonts = createSafeFontSet(uiMetrics.scale, collectFontPreloadStrings())
        fontSet = createdFonts
        fallbackFont = if (createdFonts == null) BitmapFont() else null
        if (createdFonts != null) {
            fontDebouncer.markApplied(uiMetrics.scale)
            created += { createdFonts.close(); if (fontSet === createdFonts) fontSet = null }
        } else {
            val fb = fallbackFont
            created += { fb?.dispose(); if (fallbackFont === fb) fallbackFont = null }
        }
        skin = makeSkin(createdFonts?.body ?: checkNotNull(fallbackFont)).also { s ->
            created += {
                if (s.has("default-font", BitmapFont::class.java)) {
                    s.remove("default-font", BitmapFont::class.java)
                }
                s.dispose()
            }
        }
        stage = Stage(viewport, batch).also { created += { it.dispose() } }
        groupButtons.clear()
        upgradeButtons.clear()
        upgradeInputs.clear()
        upgradeHover = null
        constructionCards.clear()
        productionCards.clear()
        factoryUpgradeCards.clear()
        factoryUpgradeHover = null

        fun label(text: String, x: Float, y: Float, w: Float, h: Float): Label = Label(text, skin).also {
            it.setBounds(x, y - h, w, h); it.setAlignment(Align.topLeft); it.setWrap(true)
            it.touchable = Touchable.disabled; stage.addActor(it)
        }
        titleLabel = label("RustedWarfare-Reforge", 16f, 628f, 440f, 28f).apply { setFontScale(1.3f) }
        productionLabel = label("", 470f, 628f, 474f, 28f)
        rateButtons.clear()
        OfflineRate.entries.forEachIndexed { index, rate ->
            rateButtons[rate] = button("${rate.multiplier}x", 40f + index * 66f, 576f, 60f) {
                if (!paused && !loading && session?.isOpen == true) {
                    stepClock.setRate(rate)
                    recordSimulationControl("speed")
                }
            }.apply { height = 24f }
        }
        simulationPauseButton = button("Pause", 238f, 576f, 96f) {
            if (!paused && !loading && session?.isOpen == true) {
                stepClock.setPaused(!stepClock.isPaused)
                ignoreNextHostFrame = true
                cancelInput()
                recordSimulationControl("pause")
            }
        }.apply { height = 24f }
        pacingLabel = label("", 344f, 600f, 376f, 24f)

        selectionHeading = label("Selection", 752f, 442f, 176f, 22f)
        selectedLabel = label("None", 752f, 414f, 176f, 100f)
        patrolLabel = label("", 752f, 306f, 176f, 72f)
        produceButton = button("Produce", 752f, 264f, 176f) { session?.produce(); updateHud() }
        cancelProductionButton = button("Cancel last", 752f, 216f, 176f) { session?.cancelProduction(); updateHud() }
        nativeIcon = Image().also { it.setBounds(760f, 390f, 40f, 40f); it.touchable = Touchable.disabled; stage.addActor(it) }
        nativeInfo = label("", 848f, 434f, 92f, 94f)
        productionLayer = Group().also { it.setTransform(false); stage.addActor(it) }
        productionContent = Group().apply { setTransform(false) }
        productionScroll = ScrollPane(productionContent, ScrollPane.ScrollPaneStyle().apply {
            vScroll = skin.newDrawable("white", Color(.12f, .15f, .14f, 1f)).apply { minWidth = 6f }
            vScrollKnob = skin.newDrawable("white", Color(.45f, .5f, .47f, 1f)).apply { minWidth = 6f; minHeight = 24f }
        }).also { pane ->
            pane.setBounds(752f, 160f, 184f, 174f)
            pane.setScrollingDisabled(true, false)
            pane.setFadeScrollBars(false)
            pane.setSmoothScrolling(false)
            pane.setOverscroll(false, false)
            pane.addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) stage.scrollFocus = pane
                }
                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1 && (toActor == null || !toActor.isDescendantOf(pane)) && stage.scrollFocus === pane)
                        stage.scrollFocus = null
                }
            })
            productionLayer.addActor(pane)
        }
        constructionLayer = Group().also { stage.addActor(it) }
        constructionContent = Group()
        constructionScroll = ScrollPane(constructionContent, ScrollPane.ScrollPaneStyle().apply {
            vScroll = skin.newDrawable("white", Color(.12f, .15f, .14f, 1f)).apply { minWidth = 6f }
            vScrollKnob = skin.newDrawable("white", Color(.45f, .5f, .47f, 1f)).apply { minWidth = 6f; minHeight = 24f }
        }).also {
            it.setBounds(752f, 160f, 184f, 174f)
            it.setScrollingDisabled(true, false)
            it.setFadeScrollBars(false)
            it.setSmoothScrolling(false)
            it.setOverscroll(false, false)
            it.addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) stage.scrollFocus = constructionScroll
                }
                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1 && toActor?.isDescendantOf(constructionScroll) != true &&
                        stage.scrollFocus === constructionScroll) stage.scrollFocus = null
                }
            })
            it.isVisible = false
            constructionLayer.addActor(it)
        }
        reclaimCard = Table(skin).also { table ->
            table.touchable = Touchable.enabled
            table.setBounds(752f, 280f, 84f, 54f)
            reclaimText = Label("Reclaim", skin).apply { setAlignment(Align.center); touchable = Touchable.disabled }
            table.add(reclaimText)
            reclaimInput = ReclaimButtonInput({ session }, { nativePanel && !paused },
                { mapInput.beginReclaim() }, { lastHudState = null; updateHud() },
                { reclaimHover = it; lastHudState = null }, modeRevision = { mapInput.modeRevision })
            table.addListener(reclaimInput)
            stage.addActor(table)
        }
        repairCard = Table(skin).also { table ->
            table.touchable = Touchable.enabled
            table.setBounds(844f, 280f, 84f, 54f)
            repairText = Label("Repair", skin).apply { setAlignment(Align.center); touchable = Touchable.disabled }
            table.add(repairText)
            repairInput = OwnedActionInput({ session }, { nativePanel && !paused }, setOf(Input.Buttons.LEFT), true,
                identity = { current -> InputActionIdentity("repair", mapInput.modeRevision).takeIf { current.canRepair } },
                submit = { _, _, _ -> mapInput.beginRepair() }, changed = { lastHudState = null; updateHud() },
                hovered = { repairHover = it; lastHudState = null }, notifyDragCancel = true)
            table.addListener(repairInput)
            stage.addActor(table)
        }
        economicUpgradeIcon = Image().apply { touchable = Touchable.disabled }
        economicUpgradeText = Label("", skin).apply { setAlignment(Align.center); setFontScale(.85f); touchable = Touchable.disabled }
        economicUpgradeProgress = ProgressBar(0f, 1f, .0001f, false, ProgressBar.ProgressBarStyle(
            skin.newDrawable("white", Color(.10f, .13f, .11f, 1f)).apply { minHeight = 6f }, null).apply {
                knobBefore = skin.newDrawable("white", Color(.45f, .91f, .65f, 1f))
            }).apply { touchable = Touchable.disabled }
        economicUpgradeCard = Table(skin).also { table ->
            table.touchable = Touchable.enabled
            table.setBounds(752f, 238f, 176f, 90f)
            table.add(economicUpgradeIcon).size(30f).padTop(4f).row()
            table.add(economicUpgradeText).height(39f).row()
            table.add(economicUpgradeProgress).width(160f).height(6f).padBottom(4f)
            economicUpgradeInput = EconomyUpgradeInput({ session }, { nativePanel && !paused },
                { lastHudState = null; updateHud() }, { economicUpgradeHover = it; lastHudState = null })
            table.addListener(economicUpgradeInput)
            stage.addActor(table)
        }
        nativeTooltipIcon = Image()
        nativeTooltipText = Label("", skin).apply { setWrap(true) }
        nativeTooltip = Table(skin).also { table ->
            table.background = skin.newDrawable("white", Color(.12f, .15f, .14f, 1f))
            table.touchable = Touchable.disabled
            table.isVisible = false
            table.setBounds(508f, 256f, 220f, 176f)
            table.add(nativeTooltipIcon).size(36f, 36f).pad(8f).row()
            table.add(nativeTooltipText).width(204f).pad(8f)
            stage.addActor(table)
        }
        // Legacy NativeActionPanel upgrade buttons removed in UI-P001-005 (replaced by HudActionPanel)
        statusLabel = label("", 16f, 52f, 480f, 44f)
        productionNoticeLabel = label("", 16f, 84f, 400f, 24f)
        stopButton = button("Stop (S)", 752f, 168f, 84f) { mapInput.cancel(); session?.stop(); updateHud() }
        patrolButton = button("Patrol (P)", 844f, 168f, 84f) { mapInput.beginPatrol(); updateHud() }
        depthButton = TextButton("Surface", skin).also { actor ->
            actor.setBounds(752f, 188f, 176f, 40f)
            depthInput = SubmarineDepthButtonInput({ session }, { !paused && !loading && session?.isOpen == true },
                { lastHudState = null; updateHud() })
            actor.addListener(depthInput)
            flightInput = AmphibiousFlightButtonInput({ session }, { !paused && !loading && session?.isOpen == true },
                { lastHudState = null; updateHud() })
            actor.addListener(flightInput)
            unloadInput = TransportUnloadButtonInput({ session }, { !paused && !loading && session?.isOpen == true },
                { lastHudState = null; updateHud() })
            actor.addListener(unloadInput)
            actor.addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) { flightHover = true; lastHudState = null }
                }
                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) { flightHover = false; lastHudState = null }
                }
            })
            stage.addActor(actor)
        }
        // Legacy NativeActionPanel customActions removed in UI-P001-005 (replaced by HudActionPanel)
        exitButton = button("Return to menu", 752f, 72f, 176f) { leaveMatch() }
        contentLabel = label("Content folder", 752f, 378f, 176f, 24f)
        rootField = TextField(testScenes.choice?.root?.toString() ?: customRootText, skin).also {
            it.setOnlyFontChars(false)
            it.setBounds(752f, 312f, 176f, 34f); stage.addActor(it)
            it.isDisabled = touchControls
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (!testScenes.selected) customRootText = it.text
                    lastHudState = null
                    updateHud()
                }
            })
        }
        modeBox = SelectBox<String>(skin).also {
            it.setItems("NoFog", "Memory", "Hardcore")
            it.selectedIndex = launch.configuration.options.mode.ordinal
            it.setBounds(752f, 264f, 176f, 40f)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(mode = SandboxMode.entries[modeBox.selectedIndex])))
                }
            })
            stage.addActor(it)
        }
        shareBox = CheckBox(" Shared vision", skin).also {
            it.isChecked = launch.configuration.options.shareAlliedVision
            it.setBounds(752f, 216f, 176f, 40f)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(shareAlliedVision = shareBox.isChecked)))
                }
            })
            stage.addActor(it)
        }
        deploymentBox = CheckBox(" Sandbox deployment", skin).also {
            it.isChecked = launch.configuration.options.deploymentEnabled
            it.setBounds(752f, 176f, 176f, 40f)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(deploymentEnabled = deploymentBox.isChecked)))
                }
            })
            stage.addActor(it)
        }
        noNukesBox = CheckBox(" No nukes", skin).also {
            it.isChecked = !launch.configuration.options.nukesEnabled
            it.setBounds(752f, 136f, 176f, 36f)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(nukesEnabled = !noNukesBox.isChecked)))
                }
            })
            stage.addActor(it)
        }
        cameraJoystickBox = CheckBox(" Camera joystick", skin).also {
            it.isChecked = launch.configuration.options.cameraJoystickEnabled
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (touchControls && launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(cameraJoystickEnabled = cameraJoystickBox.isChecked)))
                }
            })
            stage.addActor(it)
        }
        startupFrame = Image(skin.newDrawable("white", Color(.14f, .17f, .15f, 1f))).also {
            it.touchable = Touchable.disabled
            stage.addActor(it)
            it.zIndex = 0
        }
        dynamicMapBackdrop = DynamicMapBackdrop(
            skin = skin,
            themeProvider = uiThemeProvider,
            activeMapProvider = {
                val selectedTmx = skirmishMapState.selected
                val selectedCat = skirmishMapState.selectedCatalog
                when {
                    selectedTmx != null -> selectedTmx.path.toString() to selectedTmx.map
                    selectedCat != null -> selectedCat.entry.id to selectedCat.map
                    else -> skirmishLobbyState.selectedMap.id to skirmishLobbyState.selectedMap.mapDefinition
                }
            },
            deepScrimProvider = {
                appShell.currentRoute != OutGameRoute.MAIN_MENU && appShell.currentRoute != OutGameRoute.LOADING
            },
        ).also {
            stage.addActor(it)
            it.zIndex = 0
            created += { it.close() }
        }
        mainMenuScreen = MainMenuScreen(
            skin = skin,
            appShell = appShell,
            themeProvider = uiThemeProvider,
            assetResolverProvider = { activeThemeAssetResolver },
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            scaleProvider = { uiMetrics.scale },
            touchControls = touchControls,
            onSelectSkirmish = { showStartupPage(StartupPage.SETUP) },
            onSelectSandbox = {
                showStartupPage(if (development && testScenarioSeed != null) StartupPage.DEVELOPMENT else StartupPage.SETUP)
            },
            onSelectSettings = { openSettingsPage(StartupPage.RESOURCES) },
            onSelectQuit = {
                if (touchControls) {
                    appShell.handleBackKey(requireMainMenuExitConfirm = true)
                    mainMenuScreen.syncModalInteractiveState()
                    lastHudState = null
                    if (presentationReady) updateHud()
                } else {
                    Gdx.app?.exit()
                }
            },
        ).also {
            stage.addActor(it)
            created += { it.close() }
        }
        val createdControlIcons = OutGameControlIcons()
        outGameControlIcons = createdControlIcons
        created += {
            createdControlIcons.close()
            if (outGameControlIcons === createdControlIcons) outGameControlIcons = null
        }
        mapPickerScreen = MapPickerScreen(
            skin = skin,
            themeProvider = uiThemeProvider,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            onNavigateOpen = { showStartupPage(StartupPage.MAPS) },
            onNavigateClose = {
                appShell.handleBackKey(requireMainMenuExitConfirm = false)
            },
        ).also {
            stage.addActor(it)
            created += { it.close() }
        }
        skirmishLobbyScreen = SkirmishLobbyScreen(
            skin = skin,
            appShell = appShell,
            lobbyState = skirmishLobbyState,
            themeProvider = uiThemeProvider,
            iconsProvider = { outGameControlIcons },
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            canStartProvider = { canStartFromSkirmishLobby() },
            statusMessageProvider = {
                loadError ?: skirmishMapState.error ?: packState.error ?: packState.compositionError ?: visualState?.error
            },
            onNavigateBack = {
                appShell.handleBackKey(requireMainMenuExitConfirm = false)
            },
            onOpenMapPicker = { currentId, onChosen ->
                openMapPicker(currentId, MapPickerFilter.SKIRMISH) { chosen ->
                    lobbyExplicitMapChosen = true
                    chosen.tmxPath?.let { tmx ->
                        skirmishMapState.select(
                            tmx,
                            visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory,
                            chosen.localizedTitle(),
                        )
                    }
                    onChosen(chosen)
                }
            },
            onLoadCustomTmxPath = { rawPath ->
                val origDir = visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory
                if (skirmishMapState.selectPath(rawPath, origDir)) {
                    lobbyExplicitMapChosen = true
                    val sel = checkNotNull(skirmishMapState.selected)
                    OutGameMapCatalog.findByIdOrPath(sel.path.toString())
                        ?: OutGameMapCatalog.entryFromCustomTmx(sel.path, sel.displayName, sel.map)
                } else {
                    null
                }
            },
            onStartMatch = { config ->
                launchFromSkirmishLobby(config)
            },
            onConfigMutated = {
                loadError = null
                lastHudState = null
                if (presentationReady) updateHud()
            },
        ).also {
            stage.addActor(it)
            created += { it.close() }
        }
        startupTitle = label("", 130f, 616f, 700f, 52f).apply { setAlignment(Align.center); setFontScale(1.9f) }
        previewHeading = label("", 178f, 449f, 256f, 52f).apply { setAlignment(Align.topLeft) }
        mapPreview = object : Actor() {
            override fun draw(batch: Batch, parentAlpha: Float) {
                val map = skirmishMapState.activeMap ?: testScenes.choice?.scenario?.map ?: qualifiedContent?.getOrNull()?.scenario?.map ?: return
                val pxPerUnitX = if (viewport.worldWidth > 0f && viewport.screenWidth > 0) viewport.screenWidth.toFloat() / viewport.worldWidth else 1f
                val pxPerUnitY = if (viewport.worldHeight > 0f && viewport.screenHeight > 0) viewport.screenHeight.toFloat() / viewport.worldHeight else 1f
                val maxPxW = width * pxPerUnitX
                val maxPxH = height * pxPerUnitY
                val pxScale = minOf(maxPxW / (map.widthTiles * map.tileWidth), maxPxH / (map.heightTiles * map.tileHeight))
                val cellWidth = (map.tileWidth * pxScale) / pxPerUnitX
                val cellHeight = (map.tileHeight * pxScale) / pxPerUnitY
                val left = x + (width - cellWidth * map.widthTiles) / 2f
                val bottom = y + (height - cellHeight * map.heightTiles) / 2f
                val columnStride = (map.widthTiles / 80).coerceAtLeast(1)
                val rowStride = (map.heightTiles / 60).coerceAtLeast(1)
                val original = batch.packedColor
                val tile = skin.getRegion("white")
                for (row in 0 until map.heightTiles step rowStride) for (col in 0 until map.widthTiles step columnStride) {
                    when {
                        map.isResourcePool(col, row) -> batch.setColor(.89f, .73f, .24f, parentAlpha)
                        !map.waterBlocking.isBlocked(col, row) && map.blocking.isBlocked(col, row) -> batch.setColor(.16f, .42f, .61f, parentAlpha)
                        map.blocking.isBlocked(col, row) -> batch.setColor(.34f, .36f, .32f, parentAlpha)
                        else -> batch.setColor(.36f, .52f, .31f, parentAlpha)
                    }
                    val tilesHigh = minOf(rowStride, map.heightTiles - row)
                    batch.draw(tile, left + col * cellWidth, bottom + (map.heightTiles - row - tilesHigh) * cellHeight,
                        minOf(columnStride, map.widthTiles - col) * cellWidth, tilesHigh * cellHeight)
                }
                batch.packedColor = original
            }
        }.apply { setBounds(178f, 213f, 256f, 174f); touchable = Touchable.disabled }
        stage.addActor(mapPreview)
        startupStatus = Label("", skin).apply { setAlignment(Align.center); setWrap(true) }
        startupNotice = ScrollPane(startupStatus, ScrollPane.ScrollPaneStyle().apply {
            vScrollKnob = skin.newDrawable("white", Color(.45f, .55f, .48f, 1f)).apply { minWidth = 6f }
        }).apply {
            setBounds(146f, 122f, 668f, 48f)
            setScrollingDisabled(true, false)
            setFadeScrollBars(false)
            setOverscroll(false, false)
            addListener(object : InputListener() {
                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) this@BootstrapScreen.stage.scrollFocus = this@apply
                }
            })
        }
        stage.addActor(startupNotice)
        retryContentButton = button("Check again", 178f, 174f, 144f) {
            resetContentQualification()
            refreshContentQualification()
            updateHud()
        }.apply { height = 34f }
        setupButton = button("Skirmish", 346f, 359f, 268f) { showStartupPage(StartupPage.SETUP) }
        menuBackButton = button("Back", 146f, 76f, 136f) {
            appShell.handleBackKey(requireMainMenuExitConfirm = false)
        }
        quitButton = button("Quit", 346f, 187f, 268f) { Gdx.app.exit() }
        startButton = button("Start", 752f, 96f, 176f) { requestLoad() }
        visualPanel = visualState?.let { state -> VisualAssetSettingsPanel(state, skin,
            contentChoice = { testScenes.choice?.content ?: packState.choice() }) {
            evidence.recordVisualAssetMenu(state)
            loadError = null; lastHudState = null; updateHud()
        }.also { it.setBounds(150f, 114f, 660f, 440f); stage.addActor(it) } }
        packPanel = ContentPackPanel(packState, skin) {
            if (visualState?.busy == true) visualState.cancel()
            evidence.recordWhenEnabled("content-pack-menu") { mapOf("busy" to packState.busy,
                "enabled" to packState.enabled, "canEnable" to packState.canEnable, "canStart" to packState.canStart,
                "input" to packState.inputPath?.toString(), "checkedId" to packState.checked?.id,
                "unitId" to packState.selectedUnitId, "availableUnits" to packState.availableUnits.toList(), "error" to packState.error,
                "enabledIds" to packState.enabledPackages.map { it.id }, "compositionError" to packState.compositionError,
                "effectiveSources" to packState.composition?.provenance?.mapValues { (_, origin) -> mapOf(
                    "packageId" to origin.packageId, "entryPath" to origin.entryPath.toString(),
                    "replacedPackageIds" to origin.replacedPackageIds) }) }
            loadError = null; lastHudState = null; updateHud()
        }.also { it.setBounds(150f, 114f, 660f, 440f); stage.addActor(it) }
        visualsTab = button("Resource packs", 40f, 568f, 176f) {
            openSettingsPage(StartupPage.RESOURCES)
        }
        packTab = button("Content pack", 232f, 568f, 176f) {
            if (testScenes.selected) {
                testScenes.useCustomContent()
                rootField.text = customRootText
            }
            openSettingsPage(StartupPage.CONTENT)
        }
        testSceneTab = button(if (alternateStartSeed == null) "Development" else "Starting units", 424f, 568f, 176f) {
            openSettingsPage(StartupPage.DEVELOPMENT)
        }
        skirmishMapTab = button("Maps & Scenes", 424f, 568f, 128f) {
            openMapPicker(
                currentMapId = skirmishMapState.selected?.path?.fileName?.toString()?.removeSuffix(".tmx")
                    ?: OutGameMapCatalog.defaultEntry().id,
                filter = MapPickerFilter.SKIRMISH,
            ) { chosen ->
                val tmx = chosen.tmxPath
                if (tmx != null) {
                    skirmishMapState.select(
                        tmx,
                        visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory,
                        chosen.localizedTitle(),
                    )
                    if (skirmishMapState.hasSelection && testScenes.selected) {
                        testScenes.useCustomContent()
                        rootField.text = customRootText
                    }
                    evidence.record("skirmish-map-choice", mapOf(
                        "selected" to skirmishMapState.hasSelection,
                        "path" to skirmishMapState.selected?.path?.toString(),
                        "catalogSceneId" to skirmishMapState.selectedCatalog?.entry?.id,
                        "displayName" to skirmishMapState.activeDisplayName,
                        "error" to skirmishMapState.error,
                    ))
                    loadError = null
                    lastHudState = null
                    updateHud()
                }
            }
        }
        val discoveredSandboxSeeds = buildList {
            testScenarioSeed?.let { add("Command Center" to it) }
            alternateStartSeed?.let { add("Modular Spider" to it) }
            cameraStartSeed?.let { add("Camera Demo" to it) }
        }
        skirmishMapPanel = SkirmishMapPanel(
            skirmishMapState,
            skin,
            originalDirectoryProvider = { visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory },
            themeProvider = uiThemeProvider,
            sandboxPresetSeeds = discoveredSandboxSeeds,
            onSelectSandboxSeed = { seed ->
                testScenes.choose(seed)
                if (testScenes.selected) {
                    if (!rootField.isDisabled) customRootText = rootField.text
                    testScenes.choice?.let { rootField.text = it.root.toString() }
                }
            },
            onClearSandboxSeed = {
                if (testScenes.selected) {
                    testScenes.useCustomContent()
                    rootField.text = customRootText
                }
            },
        ) {
            if (skirmishMapState.hasSelection && testScenes.selected) {
                testScenes.useCustomContent()
                rootField.text = customRootText
            }
            evidence.record("skirmish-map-choice", mapOf(
                "selected" to skirmishMapState.hasSelection,
                "path" to skirmishMapState.selected?.path?.toString(),
                "catalogSceneId" to skirmishMapState.selectedCatalog?.entry?.id,
                "displayName" to skirmishMapState.activeDisplayName,
                "error" to skirmishMapState.error,
            ))
            loadError = null; lastHudState = null; updateHud()
        }.also {
            it.setBounds(150f, 114f, 660f, 440f)
            stage.addActor(it)
            initialSkirmishMap?.let { preselected ->
                if (skirmishMapState.select(preselected, visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory)) {
                    lobbyExplicitMapChosen = true
                    skirmishMapState.selected?.let { sel ->
                        val entry = OutGameMapCatalog.findByIdOrPath(sel.path.toString())
                            ?: OutGameMapCatalog.entryFromCustomTmx(sel.path, sel.displayName, sel.map)
                        skirmishLobbyState.selectMapEntry(entry)
                        if (::skirmishLobbyScreen.isInitialized) skirmishLobbyScreen.syncControlsFromState()
                    }
                    it.refresh()
                }
            }
            if (!skirmishMapState.hasSelection && initialCatalogSceneId != null) {
                if (skirmishMapState.selectCatalog(initialCatalogSceneId)) {
                    it.refresh()
                }
            }
        }
        testScenePanel = TestScenarioPanel(testScenes, testScenarioSeed, alternateStartSeed, cameraStartSeed, skin) {
            if (visualState?.busy == true) visualState.cancel()
            if (testScenes.selected) {
                skirmishMapState.clear()
                if (!rootField.isDisabled) customRootText = rootField.text
                testScenes.choice?.let { rootField.text = it.root.toString() }
            } else rootField.text = customRootText
            val selected = testScenes.choice
            evidence.record("test-scenario-choice", mapOf("selected" to testScenes.selected, "id" to selected?.id,
                "seed" to selected?.seed?.toString(), "inputRoot" to selected?.root?.toString(),
                "unitId" to selected?.content?.unitId, "error" to testScenes.error))
            loadError = null; lastHudState = null; updateHud()
        }.also { it.setBounds(150f, 114f, 660f, 440f); stage.addActor(it) }
        mapInput = SandboxInput({ session }, { graphics?.projection }, { x, y ->
            battlefieldPoint(x, y, Gdx.graphics.height, viewport.screenX, viewport.screenY,
                viewport.screenWidth, viewport.screenHeight, battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
        }, { !paused && session != null && !hudMenuOpen && !stepClock.isPaused }, { stage.keyboardFocus is TextField },
            { Gdx.input.isKeyPressed(it) }, { updateHud() }, evidence, touchControls, { x, y ->
                val bottom = Gdx.graphics.height - y
                x >= viewport.screenX && x < viewport.screenX + viewport.screenWidth &&
                    bottom >= viewport.screenY && bottom < viewport.screenY + viewport.screenHeight
            }, battlefieldAt = { x, y -> battlefieldAt(x, y) },
            cameraTakeover = { reason -> takeOverCamera(reason) },
            cameraFollowsUnit = { scriptedCamera?.followsUnit == true },
            onGuardUnsupported = {
                hudEvents.pushWarning(UiStrings.get("unsupported.guard"))
                lastHudState = null
                updateHud()
            })
        for (index in 1..3) groupButtons += button("$index (0)", 508f + (index - 1) * 72f, 12f, 64f) { mapInput.group(index) }
        commandIcons = runCatching { HudCommandIconAtlas.load(activeUiTheme, activeThemeAssetResolver) }.onFailure { err ->
            Gdx.app?.error("ui-p001-icons", "Failed to load HudCommandIconAtlas", err)
        }.getOrNull()
        created += { commandIcons?.close(); commandIcons = null }
        hudOverlay = InGameHudOverlay(
            skin = skin,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            unitIconProvider = { graphics?.unitIcon(it) },
            commandIconProvider = { name -> commandIcons?.icon(name) },
            themeProvider = uiThemeProvider,
            callbacks = object : InGameHudCallbacks {
                override fun onToggleMenu() {
                    if (paused || loading || session?.isOpen != true) return
                    hudMenuOpen = !hudMenuOpen
                    if (hudMenuOpen) {
                        closeCommandConsole()
                        cancelInput()
                    }
                    lastHudState = null
                    updateHud()
                }
                override fun onTogglePause() {
                    if (paused || loading || session?.isOpen != true) return
                    hudMenuOpen = false
                    stepClock.setPaused(!stepClock.isPaused)
                    if (stepClock.isPaused) {
                        closeCommandConsole()
                        cancelInput()
                    }
                    recordSimulationControl(if (stepClock.isPaused) "pause" else "resume")
                    lastHudState = null
                    updateHud()
                }
                override fun onSelectRate(rate: OfflineRate) {
                    if (paused || loading || session?.isOpen != true) return
                    stepClock.setRate(rate)
                    recordSimulationControl("speed")
                    lastHudState = null
                    updateHud()
                }
                override fun onMenuResume() {
                    hudMenuOpen = false
                    lastHudState = null
                    updateHud()
                }
                override fun onMenuRestart() {
                    hudMenuOpen = false
                    requestLoad()
                }
                override fun onMenuBackToStart() {
                    hudMenuOpen = false
                    leaveMatch()
                }
                override fun onMenuSettings() {
                    hudMenuOpen = false
                    hudEvents.pushWarning(UiStrings.get("unsupported.settings"))
                    lastHudState = null
                    updateHud()
                }
                    override fun onToggleDebug() {
                    if (development && touchControls && session?.isOpen == true && !loading && !paused && !hudMenuOpen && !stepClock.isPaused) {
                        toggleDebugLayer()
                    }
                }
            override fun onSelectPrimaryType(typeId: String) {
                    val active = session ?: return
                    if (active.selectPrimaryByType(typeId)) {
                        mapInput.cancel()
                        lastHudState = null
                        updateHud()
                    }
                }
                override fun onCancelQueueSlot(kind: HudQueueKind, slot: HudQueueSlot, slotIndex: Int) {
                    val active = session ?: return
                    val typeId = slot.typeId ?: return
                    when (kind) {
                        HudQueueKind.PRODUCTION -> {
                            active.cancelProduction(typeId)
                            lastHudState = null
                            updateHud()
                        }
                        HudQueueKind.UPGRADE -> {
                            val key = slot.upgradeActionKey
                            if (key != null) {
                                active.cancelUpgrade(key)
                                lastHudState = null
                                updateHud()
                            }
                        }
                        HudQueueKind.BUILD -> {
                            hudEvents.pushWarning(UiStrings.get("unsupported.cancelBuildQueue"))
                            hudOverlay.syncEventStream(hudEvents)
                        }
                    }
                }
            },
        ).also(stage::addActor)
        minimapActor = Actor().apply {
            val area = if (touchControls) MapArea(752.0, 456.0, 176.0, 112.0) else SandboxGraphics.DESKTOP_MINIMAP_AREA
            setBounds(area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
            addListener(object : InputListener() {
                private var owned = false
                private fun locate(x: Float, y: Float, dragging: Boolean) {
                    if (paused || stage.keyboardFocus is TextField) return
                    val g = graphics ?: return
                    val area = g.minimap.area
                    val target = g.minimap.locate(area.left + x, area.bottom + y, dragging) ?: return
                    takeOverCamera("minimap")
                    g.projection.centerOn(target)
                    evidence.record("camera", mapOf("reason" to "minimap",
                        "center" to g.projection.camera.center.fields(), "zoom" to g.projection.camera.zoom))
                }
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || session == null || paused) return false
                    if (button == Input.Buttons.LEFT) { owned = true; locate(x, y, false) }
                    return true
                }
                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (owned && pointer == 0) locate(x, y, true)
                }
                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) { owned = false }
                override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float) = true
            })
        }.also(stage::addActor)
        hudActionPanel = HudActionPanel(
            skin = skin,
            stage = stage,
            touchControls = touchControls,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            unitIconProvider = { graphics?.unitIcon(it) },
            commandIconProvider = { name -> commandIcons?.icon(name) },
            onCommonCommand = { cmdId ->
                val active = session ?: return@HudActionPanel
                when (cmdId) {
                    "stop" -> {
                        mapInput.cancel()
                        active.stop()
                    }
                    "choose-patrol" -> mapInput.beginPatrol()
                    "choose-guard" -> hudEvents.pushWarning(UiStrings.get("unsupported.guard"))
                }
                lastHudState = null
                updateHud()
            },
            onExclusiveAction = { key, cancel ->
                val owner = session ?: return@HudActionPanel
                val arg = key.id.substringAfter(':')
                when (key.id.substringBefore(':')) {
                    "produce" -> if (cancel) owner.cancelProduction(arg) else owner.produce(arg)
                    "upgrade" -> if (cancel) owner.cancelUpgrade(arg) else owner.upgrade(arg)
                    "choose-construction" -> mapInput.beginConstruction(arg)
                    "unit-action" -> if (key.isTargetAction && !cancel) mapInput.beginGroundAction(arg)
                        else owner.unitAction(arg, cancelQueued = cancel)
                    "submarine-surface", "submarine-dive" -> owner.submarineDepth(key.id == "submarine-surface")
                    "amphibious-fly", "amphibious-dive" -> owner.amphibiousFlight(key.id == "amphibious-fly")
                    "transport-unload-target" -> mapInput.beginGroundAction("transport-unload-target")
                    "transport-unload", "transport-cancel-unload" -> owner.unload(key.id == "transport-unload")
                    "cancel-construction" -> {
                        mapInput.cancel()
                        owner.cancelConstruction()
                    }
                    "choose-reclaim" -> mapInput.beginReclaim()
                    "choose-repair" -> mapInput.beginRepair()
                    else -> hudEvents.pushWarning(UiStrings.get("unsupported.generic"))
                }
                lastHudState = null
                updateHud()
            },
            onDisabledFlash = { key ->
                when (key.state) {
                    HudActionKeyState.SHORT_FUNDS -> hudEvents.pushWarning(UiStrings.get("noFunds"))
                    HudActionKeyState.DISABLED -> key.noteText?.let { hudEvents.pushWarning(it) }
                    else -> {}
                }
            },
            onSelectSlot = { slotNum ->
                val owner = session ?: return@HudActionPanel
                mapInput.cancel()
                val currentSlot = owner.observation.units.firstOrNull { it.unitId == owner.selectedId }?.attachmentOverview?.selectedSlot
                if (currentSlot == slotNum) {
                    owner.unitAction("selectSlotBuild")
                } else {
                    owner.unitAction("selectSlot$slotNum")
                }
                lastHudState = null
                updateHud()
            },
            themeProvider = uiThemeProvider,
        )
        hudGroupBar = HudControlGroupBar(
            skin = skin,
            touchControls = touchControls,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            onPcGroupClick = { index ->
                mapInput.group(index)
                lastHudState = null
                updateHud()
            },
            onTouchGroupAction = { index, outcome ->
                val owner = session ?: return@HudControlGroupBar
                val prevSelection = owner.selectedIds.toSet()
                hudActionPanel.clearTouchPreview()
                when (outcome) {
                    TouchGroupReleaseOutcome.RECALL -> {
                        owner.recallGroup(index, additive = false)?.let { center ->
                            graphics?.projection?.let { map ->
                                takeOverCamera("group")
                                map.centerOn(center)
                            }
                        }
                    }
                    TouchGroupReleaseOutcome.SAVE_EMPTY, TouchGroupReleaseOutcome.REPLACE -> {
                        owner.saveGroup(index)
                    }
                    TouchGroupReleaseOutcome.APPEND_AND_RECALL -> {
                        owner.appendGroup(index)
                        owner.recallGroup(index, additive = false)?.let { center ->
                            graphics?.projection?.let { map ->
                                takeOverCamera("group")
                                map.centerOn(center)
                            }
                        }
                    }
                    TouchGroupReleaseOutcome.NONE -> Unit
                }
                if (owner.selectedIds != prevSelection) {
                    mapInput.cancel()
                }
                lastHudState = null
                updateHud()
            },
            hasSelection = { session?.selectedIds?.isNotEmpty() == true },
            allowTouchHold = {
                !paused && !hudMenuOpen && !stepClock.isPaused &&
                    mapInput.constructionTypeId == null && !mapInput.reclaimMode &&
                    !mapInput.repairMode && !mapInput.patrolMode && mapInput.groundActionName == null
            },
            themeProvider = uiThemeProvider,
        ).also(stage::addActor)
        hudDebugOverlay = HudDebugOverlay(
            skin = skin,
            touchControls = touchControls,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            onToggleBoxes = { toggleDebugSubLayer("boxes") },
            onToggleRoutes = { toggleDebugSubLayer("routes") },
            onToggleDetails = { toggleDebugSubLayer("details") },
            onToggleGrid = { toggleDebugSubLayer("grid") },
            themeProvider = uiThemeProvider,
        ).also(stage::addActor)
        battleActions = BattleActionList(skin, stage, { session }, { graphics?.unitIcon(it) }, { owner, row, cancel ->
            val key = row.id.substringAfter(':')
            when (row.id.substringBefore(':')) {
                "produce" -> if (cancel) owner.cancelProduction(key) else owner.produce(key)
                "upgrade" -> if (cancel) owner.cancelUpgrade(key) else owner.upgrade(key)
                "choose-construction" -> mapInput.beginConstruction(key)
                "unit-action" -> if (row.target && !cancel) mapInput.beginGroundAction(key)
                    else owner.unitAction(key, cancelQueued = cancel)
                "submarine-surface", "submarine-dive" -> owner.submarineDepth(row.id == "submarine-surface")
                "amphibious-fly", "amphibious-dive" -> owner.amphibiousFlight(row.id == "amphibious-fly")
                "transport-unload", "transport-cancel-unload" -> owner.unload(row.id == "transport-unload")
                "choose-reclaim" -> mapInput.beginReclaim()
                "choose-repair" -> mapInput.beginRepair()
                else -> error("Unsupported HUD action: ${row.id}")
            }
        }) { lastHudState = null; updateHud() }
        commandPanel = Table().also {
            it.background = skin.newDrawable("white", Color(.07f, .10f, .09f, 1f))
            it.setBounds(40f, 100f, 660f, 220f)
            stage.addActor(it)
        }
        cameraJoystickArea = Actor().also { area ->
            area.setBounds(34f, 78f, 112f, 112f)
            area.addListener(object : InputListener() {
                private fun horizontal(x: Float) = 2.0 * x / area.width - 1.0
                private fun vertical(y: Float) = 2.0 * y / area.height - 1.0

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (!cameraJoystickActive() || button != Input.Buttons.LEFT) return false
                    if (cameraJoystick.press(pointer, horizontal(x), vertical(y))) {
                        takeCameraFromJoystick()
                        recordCameraJoystick("pressed", pointer)
                    }
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (cameraJoystick.drag(pointer, horizontal(x), vertical(y))) {
                        takeCameraFromJoystick()
                        recordCameraJoystick("dragged", pointer)
                    }
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (event.isTouchFocusCancel) clearCameraJoystick("focus-cancel")
                    else if (cameraJoystick.release(pointer)) recordCameraJoystick("released", pointer)
                }
            })
            stage.addActor(area)
        }
        commandFeedbackLabel = label("", 48f, 232f, 644f, 84f).also {
            it.setWrap(true); it.setAlignment(Align.topLeft); it.isVisible = false
        }
        commandField = TextField("", skin).also {
            it.setOnlyFontChars(false); it.maxLength = 512
        }
        hudConsole = HudSandboxConsole(
            skin = skin,
            field = commandField,
            fontsProvider = { fontSet },
            fallbackFontProvider = { fallbackFont ?: skin.getFont("default") },
            onPickSpawnOnce = {
                beginPickedCommand(SandboxPick.parseSpawn(commandField.text, repeat = false), "Enter a unit type and player")
            },
            onPickSpawnRepeat = {
                beginPickedCommand(SandboxPick.parseSpawn(commandField.text, repeat = true), "Enter a unit type and player")
            },
            onPickDestroy = {
                beginPickedCommand(SandboxPick.Destroy, "")
            },
            onPickHealth = {
                beginPickedCommand(SandboxPick.parseHealth(commandField.text), "Enter a signed health amount")
            },
            themeProvider = uiThemeProvider,
        ).also(stage::addActor)
        commandPickHint = label("", 0f, 0f, 1f, 1f).also { it.isVisible = false }
        commandHintLabel = label("", 0f, 0f, 1f, 1f).also { it.isVisible = false }
        commandPanel.isVisible = false
        commandConsoleOpen = false
        Gdx.input.inputProcessor = InputMultiplexer(object : InputAdapter() {
            override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (cameraJoystickActive()) clearCameraJoystick("system-cancel", pointer)
                return false
            }

            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (touchControls && pointer == 0 && ::hudActionPanel.isInitialized && hudActionPanel.hasTouchPreview) {
                    val pt = stage.screenToStageCoordinates(Vector2(screenX.toFloat(), screenY.toFloat()))
                    if (hudActionPanel.isPointInTouchPreviewCard(pt.x, pt.y)) {
                        // Consume tap inside the touch preview card so it never leaks onto the battlefield (Spec §9.1/§9.3)
                        return true
                    }
                    if (!hudActionPanel.isPointOnExclusiveKey(pt.x, pt.y)) {
                        hudActionPanel.clearTouchPreview()
                    }
                }
                if (!touchControls && session != null && pointer == 0) {
                    graphics?.projection?.let { proj ->
                        val bfPt = battlefieldPoint(screenX, screenY, Gdx.graphics.height, viewport.screenX, viewport.screenY,
                            viewport.screenWidth, viewport.screenHeight, battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
                        if (bfPt != null) lastCursorWorld = proj.toWorld(bfPt.x, bfPt.y)
                    }
                }
                return !hudMenuOpen && !stepClock.isPaused && pointer == 0 && button == Input.Buttons.RIGHT && mapInput.cancelSandboxPick()
            }

            override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
                if (!touchControls && session != null) {
                    graphics?.projection?.let { proj ->
                        val bfPt = battlefieldPoint(screenX, screenY, Gdx.graphics.height, viewport.screenX, viewport.screenY,
                            viewport.screenWidth, viewport.screenHeight, battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
                        if (bfPt != null) lastCursorWorld = proj.toWorld(bfPt.x, bfPt.y)
                    }
                    if (!commandConsoleOpen) {
                        val point = stage.screenToStageCoordinates(Vector2(screenX.toFloat(), screenY.toFloat()))
                        val hit = stage.hit(point.x, point.y, true)
                        stage.scrollFocus = hudActionPanel.scrollTarget(hit) ?: minimapActor.takeIf { hit === it }
                    }
                }
                return false
            }

            override fun keyDown(keycode: Int): Boolean {
                if (development && !touchControls && session?.isOpen == true && !loading && !paused && !hudMenuOpen && !stepClock.isPaused) {
                    if (keycode == Input.Keys.F3) {
                        if (!f3Held) {
                            f3Held = true
                            f3ComboUsed = false
                        }
                        return true
                    }
                    val f3PressedNow = f3Held || Gdx.input.isKeyPressed(Input.Keys.F3)
                    if (f3PressedNow && debugLayerState.enabled) {
                        when (keycode) {
                            Input.Keys.B -> { f3ComboUsed = true; toggleDebugSubLayer("boxes"); return true }
                            Input.Keys.R -> { f3ComboUsed = true; toggleDebugSubLayer("routes"); return true }
                            Input.Keys.D -> { f3ComboUsed = true; toggleDebugSubLayer("details"); return true }
                            Input.Keys.G -> { f3ComboUsed = true; toggleDebugSubLayer("grid"); return true }
                        }
                    }
                }
                if (HudSandboxConsole.isConsoleOpenKey(keycode) && HudSandboxConsole.canOpenConsole(
                        development = development,
                        commandConsoleOpen = commandConsoleOpen,
                        appPaused = paused,
                        loading = loading,
                        hudMenuOpen = hudMenuOpen,
                        touchControls = touchControls,
                        canDeploy = session?.canDeploy == true,
                        textFieldFocused = stage.keyboardFocus is TextField,
                    )) {
                    openCommandConsole(swallowKeyTyped = true)
                    return true
                }
                if (!commandConsoleOpen) {
                    if (keycode == Input.Keys.ESCAPE || touchControls && keycode == Input.Keys.BACK) {
                        if (session == null) {
                            if (::stage.isInitialized && UnifiedDropdown.closeAllOpenDropdowns(stage)) {
                                lastHudState = null
                                if (presentationReady) updateHud()
                                return true
                            }
                            val action = appShell.handleBackKey(requireMainMenuExitConfirm = touchControls)
                            if (presentationReady) {
                                lastHudState = null
                                updateHud()
                            }
                            return action != BackKeyAction.NONE
                        }
                        if (hudMenuOpen) {
                            hudMenuOpen = false
                            lastHudState = null
                            updateHud()
                            return true
                        }
                        if (stepClock.isPaused) {
                            stepClock.setPaused(false)
                            recordSimulationControl("resume")
                            lastHudState = null
                            updateHud()
                            return true
                        }
                        val hasActiveTargetMode = mapInput.constructionTypeId != null ||
                            mapInput.reclaimMode || mapInput.repairMode || mapInput.patrolMode ||
                            mapInput.groundActionName != null || mapInput.sandboxPick != null
                        if (!hasActiveTargetMode) {
                            hudMenuOpen = true
                            cancelInput()
                            lastHudState = null
                            updateHud()
                            return true
                        }
                    }
                    if (session == null && !loading && ::mainMenuScreen.isInitialized && mainMenuScreen.isVisible) {
                        if (mainMenuScreen.handleKeyDown(keycode)) {
                            lastHudState = null
                            updateHud()
                            return true
                        }
                    }
                    return false
                }
                if (keycode == Input.Keys.ESCAPE) {
                    closeCommandConsole(); updateHud(); return true
                }
                if (keycode == Input.Keys.ENTER) {
                    commandPickError = null
                    session?.submitSandboxCommand(commandField.text)
                    commandField.selectAll()
                    lastHudState = null; updateHud(); return true
                }
                return false
            }
            override fun keyUp(keycode: Int): Boolean {
                if (keycode == Input.Keys.F3 && f3Held) {
                    val usedCombo = f3ComboUsed
                    f3Held = false
                    f3ComboUsed = false
                    if (!usedCombo && development && !touchControls && session?.isOpen == true && !loading && !paused && !hudMenuOpen && !stepClock.isPaused) {
                        toggleDebugLayer()
                    }
                    return true
                }
                return false
            }
            override fun keyTyped(character: Char): Boolean {
                if (swallowNextSlashTyped) {
                    swallowNextSlashTyped = false
                    if (HudSandboxConsole.isConsoleOpenChar(character)) return true
                }
                if (HudSandboxConsole.isConsoleOpenChar(character) && HudSandboxConsole.canOpenConsole(
                        development = development,
                        commandConsoleOpen = commandConsoleOpen,
                        appPaused = paused,
                        loading = loading,
                        hudMenuOpen = hudMenuOpen,
                        touchControls = touchControls,
                        canDeploy = session?.canDeploy == true,
                        textFieldFocused = stage.keyboardFocus is TextField,
                    )) {
                    openCommandConsole(swallowKeyTyped = false)
                    return true
                }
                return commandConsoleOpen && (character == '\r' || character == '\n')
            }
        }, stage, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (!touchControls || keycode != Input.Keys.BACK) return false
                if (session != null) {
                    leaveMatch()
                    return true
                }
                if (::stage.isInitialized && UnifiedDropdown.closeAllOpenDropdowns(stage)) {
                    return true
                }
                return appShell.handleBackKey(requireMainMenuExitConfirm = true) != BackKeyAction.NONE
            }
        }, mapInput)
        if (touchControls) Gdx.input.setCatchKey(Input.Keys.BACK, true)
        resize(Gdx.graphics.width, Gdx.graphics.height)
        presentationReady = true
        } catch (failure: Exception) {
            Gdx.input.inputProcessor = null
            created.asReversed().forEach { cleanup ->
                try { cleanup() } catch (error: Exception) { failure.addSuppressed(error) }
            }
            throw failure
        }
    }

    private fun composeUiThemeForBuiltIn(themeId: BuiltInUiThemeId, visuals: PreparedVisualAssets? = activeVisuals): UiTheme {
        val base = UiTheme.builtIn(themeId)
        return when {
            initialUiThemePacks.isNotEmpty() -> UiThemeComposer.composeFromPackDirectories(initialUiThemePacks, base = base)
            visuals != null -> visuals.composeUiTheme(base)
            else -> base
        }
    }

    fun setBuiltInTheme(themeId: BuiltInUiThemeId, persist: Boolean = true) {
        activeBuiltInThemeId = themeId
        if (persist) {
            userSettingsStore?.update { it.copy(themeId = themeId) }
        }
        val resolver = if (initialUiThemePacks.isNotEmpty()) UiThemeAssetResolver(initialUiThemePacks)
            else activeVisuals?.uiThemeAssetResolver ?: activeThemeAssetResolver
        applyLoadedUiTheme(composeUiThemeForBuiltIn(themeId), resolver)
        lastHudState = null
        if (presentationReady) updateHud()
    }

    fun setLanguage(language: UiLanguage, persist: Boolean = true) {
        UiStrings.currentLanguage = language
        if (persist) {
            userSettingsStore?.update { it.copy(language = language) }
        }
        lastHudState = null
        if (presentationReady) updateHud()
    }

    fun setUserScaleMultiplier(multiplier: Float, persist: Boolean = true) {
        val clamped = multiplier.coerceIn(UserSettings.MIN_UI_SCALE_MULTIPLIER, UserSettings.MAX_UI_SCALE_MULTIPLIER)
        userScaleMultiplier = clamped
        if (persist) {
            userSettingsStore?.update { it.copy(uiScaleMultiplier = clamped) }
        }
        if (presentationReady && Gdx.graphics != null) {
            resize(Gdx.graphics.width, Gdx.graphics.height)
        }
    }

    private fun applyLoadedUiTheme(nextTheme: UiTheme, nextResolver: UiThemeAssetResolver) {
        val prevTheme = activeUiTheme
        val iconsChanged = prevTheme.assets.commandIconsJson != nextTheme.assets.commandIconsJson ||
            prevTheme.assets.commandIconsPng != nextTheme.assets.commandIconsPng ||
            activeThemeAssetResolver != nextResolver
        val fontsChanged = prevTheme.assets.fontRegular != nextTheme.assets.fontRegular ||
            prevTheme.assets.fontBold != nextTheme.assets.fontBold ||
            prevTheme.assets.fontMono != nextTheme.assets.fontMono ||
            activeThemeAssetResolver != nextResolver
        activeUiTheme = nextTheme
        activeThemeAssetResolver = nextResolver
        if (iconsChanged && presentationReady) {
            val loadedAtlas = runCatching { HudCommandIconAtlas.load(nextTheme, nextResolver) }.onFailure { err ->
                Gdx.app?.error("ui-theme-icons", "Failed to reload HudCommandIconAtlas for theme ${nextTheme.id}, falling back to default", err)
            }.getOrNull() ?: runCatching {
                HudCommandIconAtlas.load(UiTheme.DEFAULT, UiThemeAssetResolver())
            }.getOrNull()
            if (loadedAtlas != null) {
                val oldAtlas = commandIcons
                commandIcons = loadedAtlas
                oldAtlas?.close()
            }
        }
        if (fontsChanged && presentationReady) {
            rebuildFontsForScale(uiMetrics.scale)
        }
    }

    private fun createSafeFontSet(scale: Float, preloadExtras: Iterable<String> = emptyList()): UiFontSet? =
        runCatching {
            UiFontSet.create(scale, activeUiTheme, activeThemeAssetResolver, UiStrings.preloadCharacters(preloadExtras))
        }.onFailure { err ->
            Gdx.app?.error("ui-p001-fonts", "Failed to create FreeType UiFontSet, falling back to default BitmapFont", err)
        }.getOrNull()

    private fun collectFontPreloadStrings(): List<String> = buildList {
        session?.renderResources?.units?.keys?.forEach(::add)
        session?.actionMenu?.let { menu ->
            menu.selectedTitle?.let(::add)
            menu.battleActions().forEach { add(it.title); add(it.hint) }
        }
        skirmishMapState.activeDisplayName?.let(::add)
    }

    private fun rebuildFontsForScale(targetScale: Float) {
        val next = createSafeFontSet(targetScale, collectFontPreloadStrings()) ?: return
        val oldSet = fontSet
        val oldFallback = fallbackFont
        val retiredFonts = LinkedHashSet<BitmapFont>().apply {
            oldSet?.allFonts?.let(::addAll)
            oldFallback?.let(::add)
        }
        fontSet = next
        fallbackFont = null
        if (::skin.isInitialized) {
            // Rebind live/cached actors FIRST while shared Skin styles still point to retired fonts,
            // so Label.setStyle() / TextField.setStyle() rebuild their internal BitmapFontCache!
            refreshActorFonts(stage.root, next.body, retiredFonts)
            productionCards.values.forEach { refreshActorFonts(it.table, next.body, retiredFonts) }
            factoryUpgradeCards.values.forEach { refreshActorFonts(it.table, next.body, retiredFonts) }
            constructionCards.values.forEach { refreshActorFonts(it.table, next.body, retiredFonts) }
            applyFontSetToSkin(skin, next, retiredFonts)
        }
        oldSet?.close()
        oldFallback?.dispose()
    }

    private fun applyFontSetToSkin(targetSkin: Skin, fonts: UiFontSet, retired: Set<BitmapFont>) {
        val body = fonts.body
        targetSkin.add("default-font", body, BitmapFont::class.java)
        targetSkin.getAll(Label.LabelStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
        }
        targetSkin.getAll(TextButton.TextButtonStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
        }
        targetSkin.getAll(TextField.TextFieldStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
            if (style.messageFont in retired) style.messageFont = body
        }
        targetSkin.getAll(com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
        }
        targetSkin.getAll(SelectBox.SelectBoxStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
            if (style.listStyle?.font in retired) style.listStyle.font = body
        }
        targetSkin.getAll(CheckBox.CheckBoxStyle::class.java)?.values()?.forEach { style ->
            if (style.font in retired) style.font = body
        }
    }

    /**
     * Rebinds ONLY actors whose style references a retired font from [retired] (preserving sub-panels'
     * own platform fonts) and explicitly refreshes hidden SelectBox dropdown lists.
     */
    private fun refreshActorFonts(actor: Actor, bodyFont: BitmapFont, retired: Set<BitmapFont>) {
        when (actor) {
            is Label -> {
                if (actor.style.font in retired) {
                    actor.style = Label.LabelStyle(actor.style).apply { font = bodyFont }
                }
            }
            is CheckBox -> {
                if (actor.style.font in retired) {
                    actor.style = CheckBox.CheckBoxStyle(actor.style).apply { font = bodyFont }
                }
            }
            is TextButton -> {
                if (actor.style.font in retired) {
                    actor.style = TextButton.TextButtonStyle(actor.style).apply { font = bodyFont }
                }
            }
            is TextField -> {
                if (actor.style.font in retired || actor.style.messageFont in retired) {
                    actor.style = TextField.TextFieldStyle(actor.style).apply {
                        if (font in retired) font = bodyFont
                        if (messageFont in retired) messageFont = bodyFont
                    }
                }
            }
            is com.badlogic.gdx.scenes.scene2d.ui.List<*> -> {
                if (actor.style.font in retired) {
                    actor.style = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(actor.style).apply { font = bodyFont }
                }
            }
            is SelectBox<*> -> {
                val styleNeeds = actor.style.font in retired || actor.style.listStyle?.font in retired
                val listNeeds = actor.list.style.font in retired
                if (styleNeeds || listNeeds) {
                    val copy = SelectBox.SelectBoxStyle(actor.style).apply {
                        if (font in retired) font = bodyFont
                        if (listStyle != null && listStyle.font in retired) {
                            listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(listStyle).apply { font = bodyFont }
                        }
                    }
                    actor.style = copy
                    if (actor.list.style.font in retired) {
                        actor.list.style = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(actor.list.style).apply { font = bodyFont }
                    }
                }
            }
        }
        if (actor is Group) {
            val children = actor.children
            for (i in 0 until children.size) {
                refreshActorFonts(children[i], bodyFont, retired)
            }
        }
    }

    private fun makeSkin(font: BitmapFont): Skin {
        val result = Skin()
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE); pixmap.fill()
        val texture = try { Texture(pixmap) } finally { pixmap.dispose() }
        result.add("white", texture)
        result.add("default-font", font, BitmapFont::class.java)
        val text = Color(0.9f, 0.92f, 0.9f, 1f)
        result.add("default", Label.LabelStyle(font, text))
        result.add("default", TextButton.TextButtonStyle().apply {
            this.font = font; fontColor = text
            up = result.newDrawable("white", Color(0.23f, 0.28f, 0.26f, 1f))
            over = result.newDrawable("white", Color(0.31f, 0.38f, 0.34f, 1f))
            down = result.newDrawable("white", Color(0.37f, 0.48f, 0.4f, 1f))
            disabled = result.newDrawable("white", Color(0.16f, 0.19f, 0.18f, 1f))
            disabledFontColor = Color(0.45f, 0.5f, 0.47f, 1f)
            pressedOffsetY = -1f
        })
        result.add("default", TextField.TextFieldStyle().apply {
            this.font = font; fontColor = text
            background = result.newDrawable("white", Color(0.12f, 0.15f, 0.14f, 1f)).apply {
                leftWidth = 8f; rightWidth = 8f
            }
            cursor = result.newDrawable("white", text).apply { minWidth = 1f }
            selection = result.newDrawable("white", Color(0.3f, 0.5f, 0.38f, 1f))
        })
        val field = result.newDrawable("white", Color(0.12f, 0.15f, 0.14f, 1f)).apply {
            leftWidth = 8f; rightWidth = 8f; topHeight = 8f; bottomHeight = 8f
        }
        val listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(font, text, text,
            result.newDrawable("white", Color(0.3f, 0.5f, 0.38f, 1f)))
        result.add("default", SelectBox.SelectBoxStyle(font, text, field,
            ScrollPane.ScrollPaneStyle().apply { background = field }, listStyle).apply {
            backgroundDisabled = result.newDrawable("white", Color(0.16f, 0.19f, 0.18f, 1f))
            disabledFontColor = Color(0.45f, 0.5f, 0.47f, 1f)
        })
        result.add("default", CheckBox.CheckBoxStyle(
            result.newDrawable("white", Color(0.3f, 0.34f, 0.32f, 1f)).apply { minWidth = 18f; minHeight = 18f },
            result.newDrawable("white", Color(0.45f, 0.91f, 0.65f, 1f)).apply { minWidth = 18f; minHeight = 18f },
            font, text).apply { disabledFontColor = Color(0.45f, 0.5f, 0.47f, 1f) })
        return result
    }

    private fun button(text: String, x: Float, y: Float, width: Float, clicked: () -> Unit): TextButton =
        TextButton(text, skin).also { button ->
            button.setBounds(x, y, width, 40f)
            button.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) { clicked() } })
            stage.addActor(button)
        }

    private fun syncStartupPageFromShell(route: OutGameRoute) {
        if (route != OutGameRoute.MAP_PICKER && ::mapPickerScreen.isInitialized) {
            mapPickerScreen.clearPendingSelectionCallback()
        }
        if (syncingShellRoute) return
        val mapped = when (route) {
            OutGameRoute.MAIN_MENU -> StartupPage.HOME
            OutGameRoute.SKIRMISH_LOBBY -> StartupPage.SETUP
            OutGameRoute.TEST_AND_SANDBOX -> if (development && testScenarioSeed != null) StartupPage.DEVELOPMENT else StartupPage.SETUP
            OutGameRoute.SETTINGS -> activeSettingsPage
            OutGameRoute.MAP_PICKER -> StartupPage.MAPS
            OutGameRoute.LOADING -> startupPage
        }
        startupPage = mapped
        if (mapped == StartupPage.HOME || mapped == StartupPage.SETUP || mapped == StartupPage.DEVELOPMENT) {
            settingsBack = mapped
        }
        if (presentationReady && ::stage.isInitialized) {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            if (::modeBox.isInitialized) modeBox.hideList()
            packPanel?.closeLists()
            if (mapped == StartupPage.SETUP && ::skirmishLobbyScreen.isInitialized) {
                skirmishLobbyScreen.syncControlsFromState()
            }
            stage.cancelTouchFocus()
            stage.unfocusAll()
            lastHudState = null
            updateHud()
        }
    }

    internal fun navigateStartupPageForTest(pageName: String) {
        showStartupPage(StartupPage.valueOf(pageName))
    }

    internal fun cancelOrLeaveMatchForTest() {
        leaveMatch()
    }

    internal val currentLaunchOptionsForTest: SandboxOptions
        get() = launch.configuration.options

    internal fun recordSkirmishLaunchForTest(config: SkirmishLaunchConfig) {
        lastSkirmishLaunchConfig = config
        if (launch.canEdit) {
            val updatedOpts = config.applyToSandboxOptions(
                launch.configuration.options,
                allowSandboxDeployment = config.isFreeSandbox && development && !touchControls,
            )
            launch.configure(launch.configuration.copy(options = updatedOpts))
            if (::modeBox.isInitialized) modeBox.selectedIndex = updatedOpts.mode.ordinal
            if (::shareBox.isInitialized) shareBox.isChecked = updatedOpts.shareAlliedVision
            if (::noNukesBox.isInitialized) noNukesBox.isChecked = !updatedOpts.nukesEnabled
        }
    }

    private fun canStartFromSkirmishLobby(): Boolean {
        if (visualState?.canStart == false || !packState.canStart) return false
        if (skirmishMapState.selectedCatalog != null || testScenes.selected) return true
        return skirmishLobbyState.selectedMap.tmxPath != null || canStartMatch()
    }

    private fun launchFromSkirmishLobby(config: SkirmishLaunchConfig) {
        recordSkirmishLaunchForTest(config)
        val preserveDefaultSeed = selectDefaultTestScenario && testScenes.selected && !lobbyExplicitMapChosen && skirmishMapState.selected == null
        if (!preserveDefaultSeed && skirmishMapState.selectedCatalog == null && config.tmxPath != null) {
            val origDir = visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory
            skirmishMapState.select(config.tmxPath, origDir, config.mapDisplayName)
            if (skirmishMapState.hasSelection && testScenes.selected) {
                testScenes.useCustomContent()
                if (::rootField.isInitialized) rootField.text = customRootText
            }
        }
        evidence.recordWhenEnabled("skirmish-launch-config") {
            mapOf(
                "mapId" to config.mapId,
                "winMode" to config.winMode.name,
                "fogMode" to config.fogMode.name,
                "startingCredits" to config.startingCredits.amount,
                "incomeMultiplier" to config.moreRules.incomeMultiplier.factor,
                "sharedVision" to config.moreRules.sharedVision,
                "spawnAssignment" to config.moreRules.spawnAssignment.name,
                "startingUnits" to config.moreRules.startingUnits.name,
                "noNukes" to config.moreRules.noNukes,
                "seatCount" to config.immutableSeats.size,
            )
        }
        requestLoad()
    }

    private fun showStartupPage(value: StartupPage) {
        if (loading || session != null) return
        if (::modeBox.isInitialized) modeBox.hideList()
        packPanel?.closeLists()
        if (value == StartupPage.CONTENT || value == StartupPage.RESOURCES) {
            activeSettingsPage = value
        }
        val targetRoute = when (value) {
            StartupPage.HOME -> OutGameRoute.MAIN_MENU
            StartupPage.SETUP -> OutGameRoute.SKIRMISH_LOBBY
            StartupPage.DEVELOPMENT -> OutGameRoute.TEST_AND_SANDBOX
            StartupPage.MAPS -> OutGameRoute.MAP_PICKER
            StartupPage.CONTENT, StartupPage.RESOURCES -> OutGameRoute.SETTINGS
        }
        val isLeafSubpage = targetRoute == OutGameRoute.SETTINGS || targetRoute == OutGameRoute.MAP_PICKER
        val currentIsLeafSubpage = appShell.currentRoute == OutGameRoute.SETTINGS || appShell.currentRoute == OutGameRoute.MAP_PICKER
        if (value != StartupPage.MAPS && ::mapPickerScreen.isInitialized) {
            mapPickerScreen.clearPendingSelectionCallback()
        }
        startupPage = value
        syncingShellRoute = true
        try {
            when {
                targetRoute == OutGameRoute.MAIN_MENU -> appShell.resetTo(OutGameRoute.MAIN_MENU)
                isLeafSubpage && currentIsLeafSubpage -> appShell.replace(targetRoute)
                else -> appShell.popToOrPush(targetRoute)
            }
        } finally {
            syncingShellRoute = false
        }
        if (presentationReady && ::stage.isInitialized) {
            stage.cancelTouchFocus()
            stage.unfocusAll()
            lastHudState = null
            updateHud()
        }
    }

    fun openMapPicker(
        currentMapId: String?,
        filter: MapPickerFilter = MapPickerFilter.SKIRMISH,
        onMapSelected: (MapPickerEntry) -> Unit,
    ) {
        val extraCustom = skirmishMapState.selected?.let { sel ->
            val known = OutGameMapCatalog.findByIdOrPath(sel.path.toString())
            if (known == null) listOf(OutGameMapCatalog.entryFromCustomTmx(sel.path, sel.displayName, sel.map)) else emptyList()
        }.orEmpty()
        mapPickerScreen.open(currentMapId, filter, extraCustom, onMapSelected)
    }

    private fun openSettingsPage(value: StartupPage) {
        if (startupPage == StartupPage.HOME || startupPage == StartupPage.SETUP) settingsBack = startupPage
        showStartupPage(value)
    }

    private fun resetContentQualification() {
        qualification.cancel()
        contentInput = null
        contentCandidate = null
        qualifiedContent = null
        lastHudState = null
    }

    private fun refreshContentQualification() {
        if (!launch.canEdit) return
        val content = if (!testScenes.selected && packState.canStart) packState.choice() else null
        val input = content?.content?.openingPack?.root?.toString()
            ?: rootField.text.takeIf { !testScenes.selected && packState.canStart && it.isNotBlank() }
        if (input != contentInput || content != contentCandidate?.content) {
            contentInput = input
            qualification.cancel()
            contentCandidate = null
            qualifiedContent = null
            lastHudState = null
            if (input != null) runCatching { ContentCandidate(Paths.get(input).toAbsolutePath().normalize(), content) }
                .onSuccess { candidate ->
                    contentCandidate = candidate
                    qualification.qualify(candidate.root, content)
                }.onFailure { failure -> qualifiedContent = Result.failure(failure) }
        }
        qualification.pollPrepared()?.let { result ->
            qualifiedContent = result
            lastHudState = null
        }
    }

    private fun canStartMatch(): Boolean = visualState?.canStart != false &&
        when {
            skirmishMapState.selectedCatalog != null -> true
            skirmishMapState.selected != null -> packState.canStart && (packState.choice() != null || qualifiedContent?.isSuccess == true || rootField.text.trim().takeIf { it.isNotEmpty() }?.let { raw -> runCatching { java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(raw).resolve("pack.json")) }.getOrDefault(false) } == true)
            testScenes.selected -> testScenes.choice != null
            else -> packState.canStart && qualifiedContent?.isSuccess == true
        }

    private fun requestLoad() {
        if (loading) {
            evidence.record("load-request-ignored", mapOf("generation" to generation, "reason" to "already-loading"))
            return
        }
        try {
            val request = if (launch.canEdit) {
                refreshContentQualification()
                if (!canStartMatch()) return
                val skirmish = skirmishMapState.selected
                val catalog = skirmishMapState.selectedCatalog
                val scene = if (skirmish != null || catalog != null) null else testScenes.choice
                val content = contentCandidate
                launch.configure(launch.configuration.copy(
                    root = scene?.root ?: content?.root ?: activeRoot,
                    visuals = visualState?.selection ?: launch.configuration.visuals,
                    contentPack = scene?.content ?: content?.content ?: packState.choice(),
                    testScenario = scene,
                    skirmishMapPath = skirmish?.path,
                    skirmishMapName = skirmish?.displayName ?: catalog?.entry?.displayName,
                    catalogScenarioId = catalog?.entry?.id,
                ))
                launch.start()
            } else launch.reopen()
            val acceptedNanos = System.nanoTime()
            closeMatchResources()
            menuDrawn = false
            pendingLoad = request
            generation = request.generation
            activeRoot = request.configuration.root
            loading = true
            loadingDrawn = false
            loadError = null
            appShell.beginLoading()
            evidence.record("load-request", mapOf("generation" to generation, "root" to activeRoot.toString(),
                "acceptedNanos" to acceptedNanos, "menuEpoch" to menuEpoch,
                "mode" to request.configuration.options.mode.name,
                "shareAlliedVision" to request.configuration.options.shareAlliedVision,
                "deploymentEnabled" to request.configuration.options.deploymentEnabled,
                "cameraJoystickEnabled" to request.configuration.options.cameraJoystickEnabled,
                "nukesEnabled" to request.configuration.options.nukesEnabled,
                "catalogScenarioId" to request.configuration.catalogScenarioId,
                "testScenarioId" to request.configuration.testScenario?.id, "testScenarioSeed" to request.configuration.testScenario?.seed?.toString()))
            val contentPack = request.configuration.contentPack
            val prepare = visualState?.prepareForLoad(contentPack)
                ?: visualSettings?.let { settings -> { settings.prepareForContent(request.configuration.visuals, contentPack) } }
            val skirmishMapPath = request.configuration.skirmishMapPath
            val catalogId = request.configuration.catalogScenarioId
            val catalogEntry = catalogId?.let(com.rustedwarfare.reforge.core.sim.ScenarioCatalog::byId)
            val testScenario = request.configuration.testScenario
            if (catalogEntry != null) {
                loader.requestCatalogScenario(catalogEntry, activeRoot, contentPack, prepare)
            } else if (skirmishMapPath != null) {
                val origDir = request.configuration.visuals.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory
                loader.requestSkirmishMap(skirmishMapPath, activeRoot, contentPack, origDir, request.configuration.skirmishMapName, prepare)
            } else if (testScenario != null) loader.requestTestScenario(testScenario, prepare)
            else if (contentPack == null) loader.request(activeRoot, prepare)
            else loader.requestPack(activeRoot, contentPack, prepare)
        } catch (failure: Exception) { failLoad(failure) }
        updateHud()
    }

    private fun leaveMatch() {
        val cancelled = loading
        val released = session != null
        val previousGeneration = generation
        closeMatchResources()
        launch.exit()
        launch.configure(launch.configuration.copy(options = launch.configuration.options.copy(deploymentEnabled = false)))
        if (::deploymentBox.isInitialized) deploymentBox.isChecked = false
        if (visualState?.busy == true) visualState.cancel()
        if (cancelled) appShell.cancelLoading() else appShell.returnToLobby()
        syncStartupPageFromShell(appShell.currentRoute)
        lastHudState = null
        menuDrawn = false
        if (presentationReady) { stage.unfocusAll(); updateHud() }
        if (cancelled || released) evidence.record(if (cancelled) "load-cancelled" else "match-exit",
            mapOf("generation" to previousGeneration, "menuGeneration" to launch.generation))
    }

    private fun closeMatchResources() {
        debugLayerState = debugLayerState.reset()
        f3Held = false
        f3ComboUsed = false
        lastCursorWorld = null
        hudMenuOpen = false
        hudEvents.clear()
        lastObservedEventSampleId = null
        lastObservedActionResultIds.clear()
        lastArrivalNoticeKey = null
        lastHudSnapshot = null
        closeCommandConsole()
        cancelInput()
        loader.cancel()
        pendingLoad = null
        loading = false
        loadError = null
        visualRecoveryFailed = false
        stepClock.reset()
        lastPacingFrame = OfflinePacingFrame(0, 0.0)
        ignoreNextHostFrame = true
        lastPacingRecordedNanos = 0L
        val closingSession = session; session = null
        val closingGraphics = graphics; graphics = null
        val closingSounds = sounds; sounds = null
        scriptedCamera = null
        suspendedCamera = null
        activeVisuals = null
        matchStartupNotices = emptyList()
        listOf<() -> Unit>({ closingSounds?.close() }, { closingSession?.close() }, { closingGraphics?.close() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) {
                evidence.recordFailure("cleanup-error", failure)
                Gdx.app.error("p001-cleanup", "Cleanup failed", failure)
            }
        }
    }

    private fun failLoad(failure: Throwable) {
        pendingLoad?.let { launch.fail(it.generation) }
        pendingLoad = null
        loading = false
        resetContentQualification()
        appShell.cancelLoading()
        syncStartupPageFromShell(appShell.currentRoute)
        loadError = failure.message ?: failure.toString()
        preserveFailure(failure) {
            evidence.recordWhenEnabled("load-error") { mapOf("generation" to generation, "message" to loadError) }
        }
        Gdx.app.error("p001-load", loadError, failure)
        updateHud()
    }

    override fun render() {
        if (!presentationReady || disposed) return
        refreshContentQualification()
        if (loading && loadingDrawn) loader.pollPrepared()?.fold(onSuccess = { loaded ->
            val scenario = loaded.scenario
            val request = pendingLoad ?: return@fold
            var pendingGraphics: SandboxGraphics? = null
            var pendingSession: SandboxSession? = null
            var pendingSounds: SoundPlayback? = null
            try {
                loaded.preparationTiming?.let { timing -> evidence.record("load-background-ready", mapOf(
                    "generation" to generation, "startedNanos" to timing.startedNanos,
                    "scenarioPreparedNanos" to timing.scenarioPreparedNanos,
                    "visualsPreparedNanos" to timing.visualsPreparedNanos, "completedNanos" to timing.completedNanos,
                    "observedNanos" to System.nanoTime())) }
                val graphicsStartedNanos = System.nanoTime()
                val resolvedMatchTheme = composeUiThemeForBuiltIn(activeBuiltInThemeId, loaded.visuals)
                pendingGraphics = SandboxGraphics.load(loaded, activeRoot, fullBattlefield = true, uiTheme = resolvedMatchTheme)
                pendingGraphics.projection.resizeBattlefield(battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
                evidence.record("load-graphics-ready", mapOf("generation" to generation,
                    "startedNanos" to graphicsStartedNanos, "completedNanos" to System.nanoTime(),
                    "battlefieldFill" to true,
                    "worldWidth" to pendingGraphics.projection.worldWidth, "worldHeight" to pendingGraphics.projection.worldHeight))
                val runGeneration = generation
                val generationEvidence = evidence.withGeneration(runGeneration)
                val cameraCues = request.configuration.testScenario?.cameraCues.orEmpty()
                pendingSession = SandboxSession(scenario, generationEvidence, request.configuration.options,
                    generation = runGeneration, cameraCues = cameraCues,
                    singleSelectionButtons = loaded.contentPack?.singleSelectionButtons.orEmpty())
                if (touchControls && pendingSession.options.cameraJoystickEnabled) {
                    val map = pendingGraphics.projection
                    map.restoreCamera(map.camera.copy(zoom = FixedCameraJoystick.startingZoom))
                }
                if (request.configuration.skirmishMapPath != null) {
                    val focus = pendingSession.observation.units.firstOrNull { it.alive && it.ownerPlayerId == "p1" }
                    if (focus != null) {
                        pendingGraphics.projection.centerOn(focus.position)
                    }
                    evidence.record("terrain-atlas-usage", mapOf(
                        "generation" to generation,
                        "loadedTerrainTextureCount" to pendingGraphics.loadedTerrainTextureCount,
                        "declaredTilesetCount" to scenario.map.tileSets.size,
                        "visualLayerCount" to scenario.map.visualLayers.size,
                    ))
                }
                val camera = if (cameraCues.isEmpty()) null else OfflineCameraController(pendingGraphics.projection, generationEvidence)
                pendingSounds = loaded.visuals?.resolvedSounds?.let { resources -> SoundPlayback(
                    pendingSession.consumeSounds(pendingGraphics.projection.soundListener()), resources,
                    soundFactory, evidence.withGeneration(runGeneration)) }
                matchStartupNotices = loaded.startupNotices
                hudEvents.clear()
                hudMenuOpen = false
                lastObservedEventSampleId = null
                lastObservedActionResultIds.clear()
                lastArrivalNoticeKey = null
                if (loaded.startupNotices.isNotEmpty()) {
                    evidence.record("skirmish-startup-notices", mapOf("generation" to generation, "notices" to loaded.startupNotices))
                    for (notice in loaded.startupNotices) {
                        hudEvents.pushWarning(notice)
                    }
                }
                evidence.recordLoadReady(generation, loaded)
                check(launch.complete(request.generation)) { "Cancelled load generation" }
                loaded.visuals?.let { visualState?.rememberPublished(it, loaded.contentPack) }
                pendingLoad = null
                pendingGraphics.updateMinimapAspect(viewport.screenWidth, viewport.screenHeight, viewport.worldWidth, viewport.worldHeight)
                syncMinimapActorBounds(pendingGraphics)
                graphics = pendingGraphics
                activeVisuals = loaded.visuals
                if (initialUiThemePacks.isEmpty()) {
                    loaded.visuals?.let { visuals ->
                        applyLoadedUiTheme(composeUiThemeForBuiltIn(activeBuiltInThemeId, visuals), visuals.uiThemeAssetResolver)
                    }
                }
                session = pendingSession
                appShell.enterMatch()
                scriptedCamera = camera
                if (touchControls) recordCameraJoystick("opened", null)
                sounds = pendingSounds
                loaded.visuals?.let { visuals -> evidence.recordVisualAssetsReady(visuals, generation) }
                fontSet?.let { fs ->
                    val extras = buildList<String> {
                        scenario.unitDefinitions.values.forEach { def ->
                            add(def.id)
                            def.customActions?.actions?.forEach { action ->
                                add(action.name)
                                add(action.actionId)
                            }
                        }
                        loaded.contentPack?.pack?.name?.let(::add)
                        loaded.contentPack?.content?.openingPack?.name?.let(::add)
                        loaded.startupNotices.forEach(::add)
                    }
                    if (extras.isNotEmpty()) {
                        val uniqueChars = LinkedHashSet<Char>()
                        for (s in extras) for (c in s) if (!c.isISOControl()) uniqueChars.add(c)
                        if (uniqueChars.isNotEmpty()) {
                            val extraText = String(uniqueChars.toCharArray())
                            val layout = com.badlogic.gdx.graphics.g2d.GlyphLayout()
                            layout.setText(fs.body, extraText)
                            layout.setText(fs.title, extraText)
                            layout.setText(fs.keyBold, extraText)
                        }
                    }
                }
                visualSettings?.preparationDiagnostics?.takeIf { it.isNotEmpty() }?.let {
                    evidence.record("resource-acceptance-reuse", it + mapOf("generation" to generation))
                }
                activeMapId = skirmishMapState.activeDisplayName
                    ?: testScenes.choice?.id
                    ?: "sandbox"
                debugLayerState = debugLayerState.reset()
                f3Held = false
                f3ComboUsed = false
                loading = false
                stepClock.reset()
                lastPacingFrame = OfflinePacingFrame(0, 0.0)
                ignoreNextHostFrame = true
                lastPacingRecordedNanos = 0L
            } catch (failure: Exception) {
                try { pendingSounds?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                try { pendingSession?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                try { pendingGraphics?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                failLoad(failure)
            }
        }, onFailure = { failLoad(it) })
        val current = session
        if (current != null && !paused) {
            // Every scheduled callback remains one complete unchanged simulation tick.
            val elapsed = if (ignoreNextHostFrame) 0.0 else Gdx.graphics.deltaTime.toDouble()
            ignoreNextHostFrame = false
            sounds?.beginFrame()
            try {
                lastPacingFrame = stepClock.advance(elapsed) {
                    current.tick()
                    scriptedCamera?.let(current::updateCamera)
                    sounds?.let { player -> player.consume(current.consumeSounds(checkNotNull(graphics).projection.soundListener())) }
                }
                if (cameraJoystickActive()) graphics?.projection?.let { map ->
                    if (cameraJoystick.apply(map, elapsed, ::takeCameraFromJoystick))
                        recordCameraJoystick("panned", cameraJoystick.pointer)
                }
                recordSimulationPacing()
            } catch (failure: Exception) {
                preserveFailure(failure) { releaseSounds() }
                throw failure
            }
        }
        if (current != null && !paused) {
            hudEvents.update(Gdx.graphics.deltaTime)
            if (::hudActionPanel.isInitialized) hudActionPanel.tickEffects(Gdx.graphics.deltaTime)
            syncHudEventsFromSession(current)
        }
        updateHud()
        updatePacingHud()
        Gdx.gl.glClearColor(0.075f, 0.09f, 0.085f, 1f)
        nativeTooltip.isVisible = false
        val frameDt = Gdx.graphics.deltaTime.coerceAtLeast(0f)
        lastFrameMs = frameDt * 1000f
        maxFrameMsResetTimer += frameDt
        if (maxFrameMsResetTimer >= 2.0f) {
            maxFrameMsResetTimer = 0f
            maxFrameMsWindow = lastFrameMs
        } else if (lastFrameMs > maxFrameMsWindow) {
            maxFrameMsWindow = lastFrameMs
        }
        fontDebouncer.pollReadyScale(frameDt)?.let { readyScale ->
            rebuildFontsForScale(readyScale)
        }
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined

        val graphics = graphics
        if (current != null && graphics != null) {
            val clip = Rectangle()
            val area = graphics.projection.mapArea
            val stagePerWorldX = viewport.worldWidth / battlefieldWidth
            val stagePerWorldY = viewport.worldHeight / battlefieldHeight
            stage.calculateScissors(Rectangle((area.left * stagePerWorldX).toFloat(), (area.bottom * stagePerWorldY).toFloat(),
                (area.width * stagePerWorldX).toFloat(), (area.height * stagePerWorldY).toFloat()), clip)
            batch.projectionMatrix = battlefieldCamera.combined
            shapes.projectionMatrix = battlefieldCamera.combined
            if (ScissorStack.pushScissors(clip)) try {
                batch.begin(); graphics.draw(batch, current.frame, mapInput.constructionPreview, current.selectedIds); batch.end()
                mapInput.groundActionPreview?.takeIf { it.areaRadius > 0.0 }?.let { preview ->
                    graphics.drawGroundActionAreaFill(shapes, preview)
                }
                shapes.begin(ShapeRenderer.ShapeType.Line)
                graphics.drawOverlay(shapes, current.frame, current.selectedIds, mapInput.box, mapInput.constructionPreview,
                    mapInput.groundActionPreview, current.selectedId.takeUnless { touchControls })
                mapInput.sandboxPickPreview?.let { graphics.drawSandboxPick(shapes, current.frame, it) }
                if (development && debugLayerState.enabled) {
                    CollisionBoxView.draw(
                        shapes,
                        current,
                        graphics.projection,
                        selectedIds = current.selectedIds,
                        showBoxes = debugLayerState.showBoxes,
                        showRoutes = debugLayerState.showRoutes,
                        showDetails = debugLayerState.showDetails,
                        showGrid = debugLayerState.showGrid,
                    )
                }
                current.moveArrivalMarker?.let { arrival ->
                    val projection = graphics.projection
                    val tx = projection.x(arrival.originalTarget.x)
                    val ty = projection.y(arrival.originalTarget.y)
                    val sx = projection.x(arrival.stoppedAt.x)
                    val sy = projection.y(arrival.stoppedAt.y)
                    shapes.color = Color.YELLOW
                    shapes.line(tx - 6f, ty - 6f, tx + 6f, ty + 6f)
                    shapes.line(tx - 6f, ty + 6f, tx + 6f, ty - 6f)
                    shapes.line(tx, ty, sx, sy)
                    shapes.color = Color.GREEN
                    shapes.circle(sx, sy, 7f)
                }
                shapes.end()
                graphics.drawFlameEffects(shapes, current.frame)
            } finally { ScissorStack.popScissors() }
            batch.projectionMatrix = viewport.camera.combined
            shapes.projectionMatrix = viewport.camera.combined
            graphics.drawMinimap(shapes, current.frame, batch)
            if (cameraJoystickActive()) drawCameraJoystick()
        }
        stage.act(Gdx.graphics.deltaTime); stage.draw()
        if (current != null && ::hudOverlay.isInitialized && (hudMenuOpen || stepClock.isPaused)) {
            batch.projectionMatrix = viewport.camera.combined
            batch.begin()
            hudOverlay.drawModalLayer(batch, 1f)
            batch.end()
        }
        loadingDrawn = loading
        val drawnNanos = System.nanoTime()
        if (current == null && !loading && launch.canEdit) {
            val lobbyActive = startupPage == StartupPage.SETUP && ::skirmishLobbyScreen.isInitialized && skirmishLobbyScreen.isVisible
            val startActor: Actor = if (lobbyActive) skirmishLobbyScreen.startMatchButton else startButton
            val available = if (lobbyActive) {
                skirmishLobbyScreen.startMatchButton.isVisible && skirmishLobbyScreen.startMatchButton.enabled
            } else {
                startButton.isVisible && !startButton.isDisabled
            }
            if (!menuDrawn) {
                menuEpoch++
                evidence.record("startup-menu-drawn", mapOf("menuEpoch" to menuEpoch, "drawnNanos" to drawnNanos,
                    "canStart" to available, "visualBusy" to visualState?.busy,
                    "boundary" to "Stage draw submitted; native pixels are separate evidence"))
                menuDrawn = true; lastStartAvailable = null
            }
            if (lastStartAvailable != available) {
                val stageBottomLeft = startActor.localToStageCoordinates(Vector2(0f, 0f))
                evidence.record("start-availability", mapOf("menuEpoch" to menuEpoch, "drawnNanos" to drawnNanos,
                    "canStart" to available, "visualBusy" to visualState?.busy,
                    "actionId" to "start-match", "bounds" to mapOf("x" to stageBottomLeft.x, "y" to stageBottomLeft.y,
                        "width" to startActor.width, "height" to startActor.height)))
                lastStartAvailable = available
            }
        } else if (current != null && !loading && firstDrawnGeneration != generation) {
            evidence.record("load-first-frame-drawn", mapOf("generation" to generation, "drawnNanos" to drawnNanos,
                "boundary" to "World and Stage draw submitted; native pixels/input receipts are separate evidence"))
            firstDrawnGeneration = generation
        }
        recordDrawnActions(drawnNanos)
    }

    private fun recordDrawnActions(drawnNanos: Long) {
        if (evidence === ClientEvidence.NONE) return
        val current = session
        val menu = current?.actionMenu
        val entries = mutableListOf<DrawnAction>()
        fun button(id: String, actor: TextButton) {
            entries += DrawnAction(id, actor, actor.text.toString(), enabled = !actor.isDisabled)
        }
        if (::hudOverlay.isInitialized && current != null) entries += hudOverlay.drawnActions()
        val lobbyVisible = current == null && ::skirmishLobbyScreen.isInitialized && skirmishLobbyScreen.isVisible
        if (current == null) {
            if (!lobbyVisible) button("start-match", startButton)
            button("exit-match", exitButton)
        }
        if (current == null && ::mainMenuScreen.isInitialized && mainMenuScreen.isVisible) {
            entries += mainMenuScreen.drawnActions()
        } else {
            button("skirmish-setup", setupButton)
        }
        if (lobbyVisible) {
            entries += skirmishLobbyScreen.drawnActions()
        } else if (current == null && ::mapPickerScreen.isInitialized && mapPickerScreen.isVisible) {
            entries += mapPickerScreen.drawnActions()
        } else {
            button("startup-back", menuBackButton)
        }
        button("check-start-content", retryContentButton)
        if (!lobbyVisible) button("skirmish-maps", skirmishMapTab)
        if (current == null) {
            rateButtons.forEach { (rate, actor) -> button("set-speed:${rate.multiplier}", actor) }
            button("pause-simulation", simulationPauseButton)
        }
        if (::hudDebugOverlay.isInitialized && development && debugLayerState.enabled) entries += hudDebugOverlay.drawnActions()
        button("content-tab", packTab)
        button("resource-tab", visualsTab)
        button("test-scenes-tab", testSceneTab)
        if (!lobbyVisible) {
            entries += DrawnAction("match-vision-mode", modeBox, modeBox.selected, enabled = !modeBox.isDisabled)
            entries += DrawnAction("match-shared-vision", shareBox, shareBox.text.toString(), enabled = !shareBox.isDisabled)
        }
        entries += DrawnAction("content-root", rootField, rootField.text, enabled = !rootField.isDisabled)
        testScenePanel?.let { entries += it.drawnActions() }
        visualPanel?.let { entries += it.drawnActions() }
        entries += DrawnAction("sandbox-deployment", deploymentBox, deploymentBox.text.toString(),
            enabled = !deploymentBox.isDisabled)
        entries += DrawnAction("camera-joystick-enable", cameraJoystickBox, cameraJoystickBox.text.toString(),
            enabled = !cameraJoystickBox.isDisabled)
        entries += DrawnAction("camera-joystick-area", cameraJoystickArea, "Camera joystick", enabled = cameraJoystickActive())
        if (!lobbyVisible) {
            entries += DrawnAction("no-nukes", noNukesBox, noNukesBox.text.toString(), enabled = !noNukesBox.isDisabled)
        }
        if (development && ::hudConsole.isInitialized && commandConsoleOpen && current?.canDeploy == true) {
            entries += hudConsole.drawnActions()
        }
        packPanel?.let { entries += it.drawnActions() }
        if (stopButton.isVisible) button("stop", stopButton)
        if (patrolButton.isVisible) button("choose-patrol", patrolButton)
        entries += DrawnAction("minimap", minimapActor, "Minimap", enabled = current != null)
        if (::hudActionPanel.isInitialized && current != null) entries += hudActionPanel.drawnActions()
        if (::hudGroupBar.isInitialized && current != null) entries += hudGroupBar.drawnActions()
        customActionButtons.forEach { (name, actor) ->
            val action = menu?.unitActions?.singleOrNull { it.name == name }
            entries += DrawnAction("unit-action:$name", actor, actor.text.toString(), enabled = action?.available == true)
            if (action?.cancellable == true) entries += DrawnAction("cancel-unit-action:$name", actor,
                actor.text.toString(), button = "right", enabled = true)
        }
        button(menu?.transportAction?.let { if (it.unloading) "transport-unload" else "transport-cancel-unload" }
            ?: menu?.flightAction?.let { if (it.flying) "amphibious-fly" else "amphibious-dive" }
            ?: if (menu?.depthAction?.surface == false) "submarine-dive" else "submarine-surface", depthButton)
        val nativeActive = nativePanel && !paused && current?.isOpen == true
        for ((type, card) in productionCards) {
            if (card.table.parent === productionContent && !productionCardExposed(card.table)) continue
            val enabled = nativeActive && menu?.production?.any { it.typeId == type } == true
            val text = card.text.text.toString() + if (card.count.text.isNotEmpty()) "\n${card.count.text}" else ""
            entries += DrawnAction("produce:$type", card.table, text, enabled = enabled)
            entries += DrawnAction("cancel-production:$type", card.table, text, button = "right", enabled = enabled)
        }
        for ((key, card) in factoryUpgradeCards) {
            if (card.table.parent === productionContent && !productionCardExposed(card.table)) continue
            val enabled = nativeActive && menu?.upgrades?.any { it.actionKey == key && it.kind == UpgradePresentationKind.FACTORY } == true
            val text = card.text.text.toString() + "\n${card.count.text}"
            entries += DrawnAction("upgrade:$key", card.table, text, enabled = enabled)
            entries += DrawnAction("cancel-upgrade:$key", card.table, text, button = "right", enabled = enabled)
        }
        entries += DrawnAction("production-list", productionScroll, "Factory actions", enabled = nativeActive)
        menu?.production?.firstOrNull()?.let { action ->
            button("produce:${action.typeId}", produceButton)
            button("cancel-production:${action.typeId}", cancelProductionButton)
        }
        for ((type, card) in constructionCards) {
            if (card.table.parent === constructionContent && !constructionCardExposed(card.table)) continue
            entries += DrawnAction("choose-construction:$type", card.table, card.text.text.toString(),
                enabled = nativeActive && menu?.construction?.any { it.typeId == type } == true)
        }
        entries += DrawnAction("choose-reclaim", reclaimCard, reclaimText.text.toString(), enabled = nativeActive && menu?.canMaintain == true)
        entries += DrawnAction("choose-repair", repairCard, repairText.text.toString(), enabled = nativeActive && menu?.canRepair == true)
        menu?.upgrades?.singleOrNull()?.takeIf { it.kind == UpgradePresentationKind.ECONOMIC }?.let { action ->
            entries += DrawnAction("upgrade:${action.actionKey}", economicUpgradeCard,
                economicUpgradeText.text.toString(), enabled = nativeActive)
            entries += DrawnAction("cancel-upgrade:${action.actionKey}", economicUpgradeCard,
                economicUpgradeText.text.toString(), button = "right", enabled = nativeActive)
        }
        for ((key, actors) in upgradeButtons) {
            val action = menu?.upgrades?.firstOrNull { it.actionKey == key && it.kind == UpgradePresentationKind.DEFENSE }
            entries += DrawnAction("upgrade:$key", actors.first, actors.first.text.toString(),
                enabled = nativeActive && action != null && !actors.first.isDisabled)
            entries += DrawnAction("cancel-upgrade:$key", actors.second, actors.second.text.toString(),
                enabled = nativeActive && action?.progress != null && !actors.second.isDisabled)
        }
        val selected = current?.selectedIds.orEmpty()
        val projection = graphics?.projection?.let { map ->
            val stagePerWorldX = viewport.worldWidth.toDouble() / battlefieldWidth
            mapOf("worldWidth" to map.worldWidth, "worldHeight" to map.worldHeight,
                "left" to map.left * stagePerWorldX, "bottom" to map.bottom,
                "scale" to map.scale, "scaleX" to map.scale * stagePerWorldX,
                "areaLeft" to map.mapArea.left * stagePerWorldX, "areaBottom" to map.mapArea.bottom,
                "areaWidth" to map.mapArea.width * stagePerWorldX, "areaHeight" to map.mapArea.height)
        }
        val frame = ActionLayoutFrame(inputGeneration, current?.selectionRevision ?: 0, selected.toList(),
            current?.observation?.units.orEmpty().filter { it.unitId in selected }.associate { it.unitId to it.typeId },
            menuEpoch, mapOf("width" to Gdx.graphics.width, "height" to Gdx.graphics.height,
                "screenX" to viewport.screenX, "screenY" to viewport.screenY,
                "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight), projection)
        drawnActionLayout.afterDraw(stage.root, frame, entries, drawnNanos)?.let { evidence.record("action-layout", it) }
    }

    private fun recordSimulationControl(action: String) {
        lastPacingRecordedNanos = 0L
        evidence.recordWhenEnabled("simulation-control") { mapOf("generation" to generation,
            "action" to action, "rate" to stepClock.rate.multiplier, "paused" to stepClock.isPaused,
            "stepIndex" to session?.observation?.stepIndex) }
        updatePacingHud()
    }

    private fun recordSimulationPacing() {
        if (evidence === ClientEvidence.NONE) return
        val now = System.nanoTime()
        if (lastPacingRecordedNanos != 0L && now - lastPacingRecordedNanos < 500_000_000L) return
        lastPacingRecordedNanos = now
        evidence.record("simulation-pacing", mapOf("generation" to generation,
            "rate" to stepClock.rate.multiplier, "paused" to stepClock.isPaused,
            "actualMultiplier" to stepClock.actualMultiplier,
            "frameSteps" to lastPacingFrame.executedSteps, "pendingSteps" to lastPacingFrame.pendingSteps,
            "stepIndex" to session?.observation?.stepIndex, "hostNanos" to now))
    }

    private fun toggleDebugLayer() {
        val current = session?.takeIf { it.isOpen } ?: return
        if (!development) return
        debugLayerState = debugLayerState.toggleEnabled()
        lastHudState = null
        evidence.recordWhenEnabled("collision-box-view") {
            mapOf(
                "enabled" to debugLayerState.enabled,
                "boxes" to debugLayerState.showBoxes,
                "routes" to debugLayerState.showRoutes,
                "details" to debugLayerState.showDetails,
                "grid" to debugLayerState.showGrid,
                "generation" to generation,
                "stepIndex" to current.observation.stepIndex,
            )
        }
        updateHud()
    }

    private fun toggleDebugSubLayer(layer: String) {
        val current = session?.takeIf { it.isOpen } ?: return
        if (!development || !debugLayerState.enabled) return
        debugLayerState = when (layer) {
            "boxes" -> debugLayerState.toggleBoxes()
            "routes" -> debugLayerState.toggleRoutes()
            "details" -> debugLayerState.toggleDetails()
            "grid" -> debugLayerState.toggleGrid()
            else -> debugLayerState
        }
        lastHudState = null
        evidence.recordWhenEnabled("collision-box-view") {
            mapOf(
                "enabled" to debugLayerState.enabled,
                "layer" to layer,
                "boxes" to debugLayerState.showBoxes,
                "routes" to debugLayerState.showRoutes,
                "details" to debugLayerState.showDetails,
                "grid" to debugLayerState.showGrid,
                "generation" to generation,
                "stepIndex" to current.observation.stepIndex,
            )
        }
        updateHud()
    }

    private fun syncHudEventsFromSession(current: SandboxSession) {
        val obs = current.observation
        hudEvents.setCameraFollowing(scriptedCamera?.followsUnit == true)
        if (obs.sampleId != lastObservedEventSampleId) {
            lastObservedEventSampleId = obs.sampleId
            for (result in obs.actionResults) {
                if (result is com.rustedwarfare.reforge.core.sim.ActionResult.Rejected) {
                    val key = "${result.actionId}:${result.error.code}"
                    if (lastObservedActionResultIds.add(key)) {
                        hudEvents.pushRejectionCode(result.error.code, showRawCode = development || debugLayerState.enabled)
                    }
                }
            }
            while (lastObservedActionResultIds.size > 128) {
                val iter = lastObservedActionResultIds.iterator()
                if (iter.hasNext()) { iter.next(); iter.remove() } else break
            }
            for (event in obs.events) {
                when (event.kind) {
                    "unit-produced" -> {
                        val unit = obs.units.firstOrNull { it.unitId == event.unitId && it.ownerPlayerId == obs.playerId }
                        if (unit != null) {
                            val title = current.unitTitle(unit.typeId)
                            hudEvents.pushInfo(UiStrings.format("done", "NAME" to title))
                        }
                    }
                    "unit-damaged" -> {
                        val unit = obs.units.firstOrNull { it.unitId == event.unitId && it.ownerPlayerId == obs.playerId }
                        if (unit != null) {
                            hudEvents.pushInfo(UiStrings.get("damage"))
                        }
                    }
                }
            }
            val arrivals = current.selectedMoveArrivals
            if (arrivals.isNotEmpty()) {
                val maxStep = arrivals.maxOf { it.stepIndex }
                val arrivalKey = "$maxStep:" + arrivals.joinToString("|") { m ->
                    "${m.originalTarget.x.toInt()},${m.originalTarget.y.toInt()}:${m.stoppedAt.x.toInt()},${m.stoppedAt.y.toInt()}"
                }
                if (arrivalKey != lastArrivalNoticeKey) {
                    lastArrivalNoticeKey = arrivalKey
                    val msg = if (arrivals.size > 1) {
                        UiStrings.format("arrivalDeviatedMulti", "N" to arrivals.size)
                    } else {
                        UiStrings.get("arrivalDeviated")
                    }
                    hudEvents.pushInfo(msg)
                }
            }
        }
    }

    private fun updatePacingHud() {
        if (!presentationReady) return
        val visible = session?.isOpen == true
        rateButtons.forEach { (_, actor) ->
            actor.isVisible = false
            actor.isDisabled = true
        }
        simulationPauseButton.isVisible = false
        simulationPauseButton.isDisabled = true
        pacingLabel.isVisible = false
        val actual = if (paused) 0.0 else stepClock.actualMultiplier
        val state = if (paused) "Suspended" else if (stepClock.isPaused) "Paused" else "Speed"
        pacingLabel.setText("$state ${stepClock.rate.multiplier}x | actual " +
            (actual?.let { String.format(Locale.ROOT, "%.2fx", it) } ?: "--") +
            " | backlog ${lastPacingFrame.pendingSteps.toLong()}")
    }

    private fun openCommandConsole(swallowKeyTyped: Boolean = false) {
        mapInput.cancel()
        commandConsoleOpen = true
        swallowNextSlashTyped = swallowKeyTyped
        commandPickError = null
        commandField.text = ""
        stage.keyboardFocus = commandField
        lastHudState = null
        updateHud()
        evidence.record("sandbox-console", mapOf("generation" to generation, "open" to true))
    }

    private fun closeCommandConsole() {
        swallowNextSlashTyped = false
        if (!commandConsoleOpen) return
        commandConsoleOpen = false
        if (::commandField.isInitialized && stage.keyboardFocus === commandField) stage.keyboardFocus = null
        lastHudState = null
        evidence.record("sandbox-console", mapOf("generation" to generation, "open" to false))
    }

    private fun beginPickedCommand(mode: SandboxPick?, error: String) {
        if (mode == null) {
            commandPickError = error
        } else {
            commandPickError = null
            closeCommandConsole()
            mapInput.beginSandboxPick(mode)
        }
        updateHud()
    }

    private fun factoryActionCard(input: OwnedActionInput): FactoryActionCard {
        val icon = Image().apply { touchable = Touchable.disabled }
        val text = Label("", skin).apply {
            setAlignment(Align.center); touchable = Touchable.disabled; setFontScale(.7f); setEllipsis(true)
        }
        val count = Label("", skin).apply { setAlignment(Align.center); touchable = Touchable.disabled }
        val table = Table(skin).apply {
            touchable = Touchable.enabled
            add(icon).size(28f, 28f).padTop(3f).row()
            add(text).width(80f).height(16f).row()
            add(count).width(80f).height(12f)
            addListener(input)
        }
        return FactoryActionCard(table, icon, text, count, input)
    }

    private fun placeFactoryCard(card: FactoryActionCard, index: Int, total: Int, title: String, naval: Boolean = false) {
        val overflow = total > 6
        val parent = if (overflow) productionContent else productionLayer
        if (card.table.parent !== parent) { card.input.cancel(); parent.addActor(card.table) }
        card.table.isVisible = nativePanel
        when {
            overflow -> card.table.setBounds((index % 2) * 92f, ((total + 1) / 2 - 1 - index / 2) * 60f, 84f, 54f)
            naval -> card.table.setBounds(752f, 270f, 84f, 50f)
            total <= 3 -> card.table.setBounds(752f + (index % 2) * 92f, 256f - (index / 2) * 68f, 84f, 64f)
            else -> card.table.setBounds(752f + (index % 2) * 92f, 280f - (index / 2) * 60f, 84f, 54f)
        }
        val compact = total > 3 || naval
        if (card.compact != compact) {
            card.table.getCell(card.icon).size(if (compact) 24f else 28f)
            card.table.getCell(card.text).height(if (compact) 12f else 16f)
            card.table.getCell(card.count).height(if (compact) 10f else 12f)
            card.table.invalidate(); card.compact = compact
        }
        card.text.setFontScale(if (compact || title.length > 12) .7f else 1f)
        card.text.setText(title)
        card.count.setFontScale(if (compact) .6f else .7f)
        card.table.background = skin.newDrawable("white", if (card.input.pressed)
            Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
    }

    private fun syncProductionCards(actions: List<ProductionActionView>, upgrades: List<UpgradeActionView>, naval: Boolean) {
        val stale = productionCards.iterator()
        while (stale.hasNext()) {
            val (type, card) = stale.next()
            if (actions.none { it.typeId == type }) {
                card.input.cancel(); card.table.remove(); stale.remove()
                if (productionHover == type) productionHover = null
            }
        }
        val staleUpgrades = factoryUpgradeCards.iterator()
        while (staleUpgrades.hasNext()) {
            val (key, card) = staleUpgrades.next()
            if (upgrades.none { it.actionKey == key }) {
                card.input.cancel(); card.table.remove(); staleUpgrades.remove()
                if (factoryUpgradeHover == key) factoryUpgradeHover = null
            }
        }
        val total = actions.size + upgrades.size
        val overflow = total > 6
        productionScroll.isVisible = nativePanel && overflow
        if (!productionScroll.isVisible && stage.scrollFocus === productionScroll) stage.scrollFocus = null
        if (overflow) productionContent.setSize(176f, ((total + 1) / 2) * 60f)
        actions.forEachIndexed { index, action ->
            val type = action.typeId
            val card = productionCards.getOrPut(type) {
                factoryActionCard(ProductionButtonInput({ session }, { nativePanel && !paused },
                    { lastHudState = null; updateHud() }, { hovered ->
                        if (hovered) productionHover = type else if (productionHover == type) productionHover = null
                        lastHudState = null
                    }, { type }))
            }
            val title = if (type == "mechHeavyMissile") "Heavy mech" else action.title
            placeFactoryCard(card, index, total, title, naval && total == 1)
            if (type == "mechHeavyMissile") card.text.setFontScale(.7f)
            setUnitIcon(card.icon, type)
            card.count.setText(if (action.count > 0) "(${action.count})" else "")
            card.text.color = if (action.affordable) Color(.9f, .92f, .9f, 1f) else Color(.55f, .58f, .55f, 1f)
            card.count.color = card.text.color
        }
        upgrades.forEachIndexed { index, action ->
            val key = action.actionKey
            val card = factoryUpgradeCards.getOrPut(key) {
                factoryActionCard(FactoryUpgradeInput({ session }, { nativePanel && !paused }, key,
                    { lastHudState = null; updateHud() }, { hovered ->
                        if (hovered) factoryUpgradeHover = key else if (factoryUpgradeHover == key) factoryUpgradeHover = null
                        lastHudState = null
                    }))
            }
            placeFactoryCard(card, actions.size + index, total, "Upgrade ${action.title}")
            setUnitIcon(card.icon, action.targetTypeId)
            card.count.setText(action.progress?.let { "(1) ${(it * 100f).toInt()}%" } ?: "\$${action.price}")
            card.text.color = if (action.affordable || action.progress != null) Color(.9f, .92f, .9f, 1f) else Color(.55f, .58f, .55f, 1f)
            card.count.color = card.text.color
        }
        if (overflow) productionScroll.invalidate()
    }

    private fun productionCardExposed(table: Table): Boolean {
        table.localToStageCoordinates(productionCorner.set(0f, 0f))
        return productionCorner.x >= productionScroll.x && productionCorner.y >= productionScroll.y &&
            productionCorner.x + table.width <= productionScroll.x + productionScroll.width &&
            productionCorner.y + table.height <= productionScroll.y + productionScroll.height
    }

    private fun syncConstructionCards(actions: List<ConstructionActionView>, economic: Boolean, maintenance: Boolean,
                                      spider: Boolean) {
        val stale = constructionCards.iterator()
        while (stale.hasNext()) {
            val (type, card) = stale.next()
            if (actions.none { it.typeId == type }) {
                card.input.cancel()
                card.table.remove()
                stale.remove()
            }
        }
        // Maintenance owns the row at y280. Keep construction below that row,
        // including the ScrollPane's clipping rectangle, so every drawn card can receive input.
        val overflow = actions.size > if (maintenance || spider) 4 else 6
        val legacy = economic && actions.all { it.typeId in setOf("extractorT1", "landFactory",
            "mechFactory", "airFactory", "seaFactory", "fabricatorT1", "c_turret_t1", "laserDefence") }
        constructionScroll.isVisible = nativePanel && overflow
        val pitch = if (spider) 44f else 60f
        val height = if (spider) 42f else 54f
        constructionScroll.setBounds(752f, if (spider) 154f else 160f, 184f,
            if (spider) 90f else if (maintenance) 114f else 174f)
        if (!constructionScroll.isVisible && stage.scrollFocus === constructionScroll) stage.scrollFocus = null
        val rows = (actions.size + 1) / 2
        if (overflow) constructionContent.setSize(176f, rows * pitch)
        actions.forEachIndexed { index, action ->
            val type = action.typeId
            val card = constructionCards.getOrPut(type) {
                val icon = Image().apply { touchable = Touchable.disabled }
                val text = Label("", skin).apply {
                    setAlignment(Align.center); touchable = Touchable.disabled; setFontScale(.7f); setEllipsis(true)
                }
                val table = Table(skin).apply {
                    touchable = Touchable.enabled
                    add(icon).padTop(3f).row()
                    add(text).width(80f)
                    background = skin.newDrawable("white", Color(.23f, .28f, .26f, 1f))
                }
                val input = ConstructionButtonInput({ session }, { nativePanel && !paused }, type,
                    { mapInput.beginConstruction(it) }, { hovered ->
                        if (hovered) constructionHover = type else if (constructionHover == type) constructionHover = null
                        lastHudState = null
                    }, modeRevision = { mapInput.modeRevision })
                table.addListener(input)
                ConstructionCard(table, icon, text, input)
            }
            val iconSize = if (spider) 16f else 28f
            card.table.getCell(card.icon).size(iconSize)
            card.table.getCell(card.text).height(if (spider) 22f else 21f)
            card.table.invalidate()
            val parent = if (overflow) constructionContent else constructionLayer
            if (card.table.parent !== parent) {
                card.input.cancel()
                parent.addActor(card.table)
            }
            card.table.isVisible = nativePanel
            when {
                overflow -> card.table.setBounds((index % 2) * 92f, (rows - 1 - index / 2) * pitch, 84f, height)
                spider -> card.table.setBounds(752f + index % 2 * 92f, 200f - index / 2 * pitch, 84f, height)
                legacy -> card.table.setBounds(
                    if (type in setOf("extractorT1", "c_turret_t1", "laserDefence")) 844f else 752f,
                    when (type) { "extractorT1" -> 280f; "landFactory", "c_turret_t1" -> 220f; else -> 160f },
                    84f, 54f)
                maintenance -> card.table.setBounds(752f + (index % 2) * 92f, 220f - (index / 2) * 60f, 84f, 54f)
                actions.size > 4 -> card.table.setBounds(752f + (index % 2) * 92f, 280f - (index / 2) * 60f, 84f, 54f)
                else -> card.table.setBounds(752f + (index % 2) * 92f, 256f - (index / 2) * 68f, 84f, 64f)
            }
        }
        if (overflow) constructionScroll.invalidate()
    }

    /** Only advertise complete drawn cards, including the ScrollPane's clipping boundary. */
    private fun constructionCardExposed(table: Table): Boolean {
        table.localToStageCoordinates(constructionCorner.set(0f, 0f))
        return constructionCorner.x >= constructionScroll.x && constructionCorner.y >= constructionScroll.y &&
            constructionCorner.x + table.width <= constructionScroll.x + constructionScroll.width &&
            constructionCorner.y + table.height <= constructionScroll.y + constructionScroll.height
    }

    private fun updateHud() {
        if (!presentationReady) return
        val current = session
        cameraJoystickArea.isVisible = cameraJoystickActive()
        if (current?.canDeploy != true) closeCommandConsole()
        val showConsole = development && commandConsoleOpen && current?.canDeploy == true
        commandPanel.isVisible = false
        commandFeedbackLabel.isVisible = false
        commandPickHint.isVisible = false
        commandHintLabel.isVisible = false
        if (::hudConsole.isInitialized) {
            hudConsole.update(
                open = showConsole,
                stageWidth = viewport.worldWidth,
                stageHeight = viewport.worldHeight,
                feedback = commandPickError ?: current?.sandboxCommandFeedback.orEmpty(),
                lang = UiStrings.currentLanguage,
            )
        }
        val menu = current?.actionMenu
        val selected = current?.observation?.units?.find { it.unitId == current.selectedId }
        val naval = selected?.transport != null && menu?.unitActions?.any { it.name == "buildAntiNuke" } == true
        val state = HudState(selected, current?.selectedIds ?: emptySet(), HudControlGroupLogic.GROUP_ORDER.map { current?.groupCount(it) ?: 0 }, current?.feedback, current?.observation?.stepIndex,
            launch.canEdit, loading, visualState?.preparationStatus, loadError, current != null, menu?.balance, menu?.factoryQueue,
            mapInput.patrolMode, mapInput.patrolPointCount, mapInput.reclaimMode, mapInput.repairMode, menu?.workerQueueText, menu,
            hudMenuOpen, stepClock.isPaused, stepClock.rate, UiStrings.currentLanguage,
            mapInput.constructionTypeId, mapInput.constructionPreview?.state, mapInput.groundActionName, mapInput.sandboxPick, debugLayerState)
        if (state == lastHudState) {
            if (current != null && ::hudOverlay.isInitialized) {
                refreshDebugOverlay(current)
                val dbgOpen = development && debugLayerState.enabled
                val belowY = if (dbgOpen && ::hudDebugOverlay.isInitialized) hudDebugOverlay.leftColumnBottomY else null
                hudOverlay.syncEventStream(hudEvents, noticesStartBelowY = belowY)
            }
            return
        }
        if (state.preparationStatus != null && state.preparationStatus != lastHudState?.preparationStatus) {
            evidence.record("resource-preparation-stage", mapOf("generation" to generation, "stage" to state.preparationStatus,
                "scope" to if (loading) "match-load" else "menu-preparation"))
        }
        lastHudState = state
        selectedLabel.setText(if (selected == null) "None" else if (state.selectedIds.size > 1)
            "${state.selectedIds.size} selected\n${current?.observation?.units?.count { it.unitId in state.selectedIds && it.alive && it.ownerPlayerId == "p1" }} controllable\nRight click: Move / Attack\nS: Stop" else String.format(Locale.ROOT,
            "%s / %s\n%s / %s\nx %.1f   y %.1f\nheading %.1f  speed %.2f",
            if (selected.unitId.startsWith("produced:") || current?.observation?.scenarioId == "p008-content-pack") selected.typeId else selected.unitId, selected.ownerPlayerId,
            if (selected.ownerPlayerId == "p1") "Local control" else "No controller",
            selected.movementState.name.lowercase(), selected.position.x, selected.position.y, selected.heading, selected.speed) +
            (selected.health?.let { "\nHP ${it.toInt()} / ${selected.maxHealth?.toInt()}" } ?: "") +
            (selected.shield?.let { "\nShield ${it.current.toInt()} / ${it.maximum.toInt()}" } ?: "") +
            (selected.energy?.let { String.format(Locale.ROOT, "\nEnergy %.3f / %.3f%s", it.current, it.maximum,
                if (it.recharging) " LOCKED" else "") } ?: "") +
            (if (selected.typeId.startsWith("c_turret")) "\n${selected.typeId}" else "") +
            (selected.pointDefense?.let { String.format(Locale.ROOT, "\nEnergy %.3f%s", it.energy,
                if (it.exhausted) " LOCKED" else "") } ?: ""))
        val queue = menu?.factoryQueue
        selectionHeading.isVisible = current != null && !nativePanel
        val notice = current?.productionNotice
        productionNoticeLabel.isVisible = nativePanel && notice != null && current.observation.stepIndex - notice.step < 240
        productionNoticeLabel.setText(notice?.text ?: "")
        productionLabel.setText(state.balance?.let { balance ->
            "Funds $balance" + (queue?.let { "  Queue ${it.items.size}" +
                (it.items.firstOrNull()?.let { head -> "  ${(head.progress * 100f).toInt()}%" } ?: "") } ?: "") +
                menu?.upgradeQueueText.orEmpty()
        } ?: "")
        produceButton.isVisible = queue != null
        cancelProductionButton.isVisible = queue != null
        produceButton.isDisabled = menu?.production?.firstOrNull()?.price == null
        produceButton.setText(menu?.production?.firstOrNull()?.price?.let { "Produce $it" } ?: "Produce")
        cancelProductionButton.isDisabled = queue?.items?.isEmpty() != false
        syncProductionCards(menu?.production.orEmpty(), menu?.upgrades.orEmpty().filter { it.kind == UpgradePresentationKind.FACTORY }, naval)
        val spider = selected?.attachmentOverview != null
        val maintenanceY = if (spider) 322f else 280f
        val maintenanceHeight = if (spider) 27f else 54f
        reclaimCard.setBounds(752f, maintenanceY, 84f, maintenanceHeight)
        repairCard.setBounds(844f, maintenanceY, 84f, maintenanceHeight)
        reclaimCard.isVisible = nativePanel && menu?.canMaintain == true
        reclaimCard.background = skin.newDrawable("white", if (reclaimInput.pressed || state.reclaimMode)
            Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
        repairCard.isVisible = nativePanel && menu?.canRepair == true
        repairCard.background = skin.newDrawable("white", if (repairInput.pressed || state.repairMode)
            Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
        syncConstructionCards(menu?.construction.orEmpty(), current?.hasEconomicConstruction == true, menu?.canMaintain == true, spider)
        val selectedInfo = menu?.selectedUnitInfo
        nativeInfo.isVisible = nativePanel && selectedInfo != null
        nativeIcon.isVisible = nativePanel && selected != null && state.selectedIds.size == 1
        if (nativeSelection != state.selectedIds) {
            productionCards.values.forEach { it.input.cancel() }
            factoryUpgradeCards.values.forEach { it.input.cancel() }
            factoryUpgradeHover = null
            productionHover = null
            economicUpgradeInput.cancel()
            upgradeInputs.forEach { it.cancel() }
            depthInput.cancel()
            flightInput.cancel()
            unloadInput.cancel()
            customActionInputs.forEach { it.cancel() }
            customActionHover = null
            flightHover = false
            upgradeHover = null
            reclaimInput.cancel()
            repairInput.cancel()
            constructionCards.values.forEach { it.input.cancel() }
            constructionHover = null
            nativeSelection = state.selectedIds.toSet()
        }
        if (nativePanel) {
            produceButton.isVisible = false
            cancelProductionButton.isVisible = false
            selectedLabel.setBounds(752f, if (spider) 350f else 340f, 88f, if (spider) 36f else 46f)
            selectedLabel.setText(if (selected == null) "None" else if (state.selectedIds.size > 1)
                "${state.selectedIds.size} selected" else "${menu?.selectedTitle} x1")
            setUnitIcon(nativeIcon, selected?.typeId)
            nativeInfo.setFontScale(if (state.workerQueueText != null) .7f
                else if (naval || selected?.submarine != null || selected?.amphibiousJet != null) .8f else 1f)
            nativeInfo.setText(selectedInfo ?: "")
            menu?.production?.firstOrNull { it.typeId == productionHover }?.let { action ->
                setUnitIcon(nativeTooltipIcon, action.typeId)
                nativeTooltipText.setText(action.tooltip)
            }
            menu?.construction?.forEach { action ->
                val card = constructionCards.getValue(action.typeId)
                setUnitIcon(card.icon, action.typeId)
                val cardTitle = if (touchControls &&
                    (action.typeId == "c_turret_t1" || action.typeId == "laserDefence")) action.title else action.panelTitle
                card.text.setText("$cardTitle\n\$${action.price}")
                if (constructionHover == action.typeId) {
                    nativeTooltipIcon.userObject = null
                    nativeTooltipIcon.drawable = card.icon.drawable
                    nativeTooltipText.setText(action.tooltip)
                }
            }
            menu?.upgrades?.firstOrNull { it.kind == UpgradePresentationKind.FACTORY && it.actionKey == factoryUpgradeHover }?.let { action ->
                setUnitIcon(nativeTooltipIcon, action.targetTypeId)
                nativeTooltipText.setText(action.tooltip)
            }
            productionLabel.setText(state.balance?.let { "\$$it" +
                (if (current?.hasEconomicConstruction == true) "  (+\$${menu?.totalNominalIncome?.toInt() ?: 0})" else "") } ?: "")
            productionLabel.color = Color(.45f, .91f, .65f, 1f)
        } else {
            selectedLabel.setBounds(752f, 314f, 176f, 100f)
            productionLabel.color = Color(.9f, .92f, .9f, 1f)
        }
        val upgrades = menu?.upgrades.orEmpty()
        if (reclaimHover && reclaimCard.isVisible) {
            nativeTooltipIcon.userObject = null
            nativeTooltipIcon.drawable = null
            nativeTooltipText.setText(menu?.reclaimTooltip.orEmpty())
        }
        if (repairHover && repairCard.isVisible) {
            nativeTooltipIcon.userObject = null
            nativeTooltipIcon.drawable = null
            nativeTooltipText.setText(menu?.repairTooltip.orEmpty())
        }
        val economicUpgrade = upgrades.singleOrNull()?.takeIf { it.kind == UpgradePresentationKind.ECONOMIC }
        economicUpgradeCard.isVisible = nativePanel && economicUpgrade != null
        if (economicUpgrade != null && economicUpgradeCard.isVisible) {
            setUnitIcon(economicUpgradeIcon, selected?.typeId)
            economicUpgradeText.setText(if (touchControls) economicUpgrade.cardText.replaceFirst("U ", "^ ")
                else economicUpgrade.cardText)
            economicUpgradeText.color = if (economicUpgrade.affordable || economicUpgrade.progress != null)
                Color(.9f, .92f, .9f, 1f) else Color(.55f, .58f, .55f, 1f)
            economicUpgradeProgress.isVisible = economicUpgrade.progress != null
            economicUpgradeProgress.setValue(economicUpgrade.progress ?: 0f)
            economicUpgradeCard.background = skin.newDrawable("white", if (economicUpgradeInput.pressed)
                Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
            if (economicUpgradeHover) {
                nativeTooltipIcon.userObject = null
                nativeTooltipIcon.drawable = economicUpgradeIcon.drawable
                nativeTooltipText.setText(economicUpgrade.tooltip)
            }
        }
        for ((key, buttons) in upgradeButtons) {
            val action = upgrades.firstOrNull { it.actionKey == key && it.kind == UpgradePresentationKind.DEFENSE }
            if (action != null) {
                buttons.first.setText(if (key == "upgrade_t2")
                    action.cardText.replaceFirst(action.title, "Factory T2") else action.cardText)
                if (upgradeHover == key) {
                    setUnitIcon(nativeTooltipIcon, selected?.typeId)
                    nativeTooltipText.setText(action.tooltip)
                }
            }
            buttons.first.isVisible = action != null
            buttons.second.isVisible = action != null
            buttons.first.isDisabled = action == null
            buttons.second.isDisabled = action?.progress == null
        }
        stopButton.isDisabled = selected == null
        depthButton.isVisible = menu?.depthAction != null || menu?.flightAction != null || menu?.transportAction != null
        depthButton.isVisible = false
        depthButton.isDisabled = true
        menu?.unitActions?.singleOrNull { it.name == customActionHover }?.let { action ->
            setUnitIcon(nativeTooltipIcon, action.iconTypeId ?: selected?.typeId)
            nativeTooltipText.setText(action.tooltip)
        }
        if (flightHover) menu?.flightAction?.let { action ->
            setUnitIcon(nativeTooltipIcon, selected?.typeId)
            nativeTooltipText.setText(action.tooltip)
        }
        // Legacy bottom stop/reopen/exit bounds replaced by InGameHudOverlay & HudActionPanel
        stopButton.isVisible = current != null
        patrolButton.isVisible = current != null && !touchControls
        patrolButton.isDisabled = current?.canPatrol != true
        patrolLabel.isVisible = current != null && !touchControls
        val taskText = selected?.tasks?.firstOrNull()?.let { head ->
                "Tasks ${selected.tasks.size}: ${taskLabel(head, current.observation).lowercase()}" +
                    (head.target?.let { String.format(Locale.ROOT, "\nPoint %.0f, %.0f", it.x, it.y) } ?: "")
            } ?: "Tasks 0"
        patrolLabel.setText(if (state.patrolMode) "Sent points ${state.patrolPoints}\n$taskText\nLeft: point / Esc: finish"
            else if (selected?.tasks?.isNotEmpty() == true) taskText else "P: set patrol points")
        // Dense action panels already explain their controls and use the patrol hint area.
        if (queue != null || upgrades.isNotEmpty() || menu?.construction?.isNotEmpty() == true || menu?.canMaintain == true ||
            selected?.unitActions?.stockpile != null || selected?.attachmentOverview != null)
            patrolLabel.isVisible = false
        selectedLabel.isVisible = current != null
        val setup = current == null && !loading && startupPage == StartupPage.SETUP
        val home = current == null && !loading && startupPage == StartupPage.HOME
        val mapPicking = current == null && !loading && startupPage == StartupPage.MAPS
        val managing = current == null && !loading && !setup && !home && !mapPicking
        titleLabel.isVisible = current != null
        statusLabel.isVisible = current != null
        groupButtons.forEachIndexed { index, actor ->
            actor.setText("${index + 1} (${state.groups[index]})"); actor.isVisible = current != null
        }
        val editable = launch.canEdit && !loading
        val sxOff = ((viewport.worldWidth - 960f) * 0.5f).coerceAtLeast(0f)
        val syOff = ((viewport.worldHeight - 640f) * 0.5f).coerceAtLeast(0f)
        dynamicMapBackdrop.isVisible = current == null
        dynamicMapBackdrop.setBounds(0f, 0f, viewport.worldWidth, viewport.worldHeight)
        dynamicMapBackdrop.zIndex = 0
        mainMenuScreen.isVisible = home
        if (home) {
            mainMenuScreen.updateLayout(viewport.worldWidth, viewport.worldHeight)
            mainMenuScreen.toFront()
        }
        mapPickerScreen.isVisible = mapPicking
        if (mapPicking) {
            mapPickerScreen.updateLayout(viewport.worldWidth, viewport.worldHeight)
            mapPickerScreen.toFront()
        }
        skirmishLobbyScreen.isVisible = setup
        if (setup) {
            skirmishLobbyScreen.updateLayout(viewport.worldWidth, viewport.worldHeight)
            skirmishLobbyScreen.toFront()
            UnifiedDropdown.bringOpenDropdownsToFront(stage)
        }
        val legacySetup = false
        startupFrame.isVisible = current == null && !home && !mapPicking && !setup
        if (startupFrame.isVisible) {
            startupFrame.setBounds(sxOff + 124f, syOff + 60f, 712f, 493f)
            startupFrame.zIndex = 1
        }
        startupTitle.isVisible = current == null && !home && !mapPicking && !setup
        startupTitle.setBounds(sxOff + 130f, syOff + 564f, 700f, 52f)
        previewHeading.setBounds(sxOff + 178f, syOff + 397f, 256f, 52f)
        mapPreview.setBounds(sxOff + 178f, syOff + 213f, 256f, 174f)
        startupNotice.setBounds(sxOff + 146f, syOff + 122f, 668f, 48f)
        retryContentButton.setBounds(sxOff + 178f, syOff + 174f, 144f, 34f)
        setupButton.setBounds(sxOff + 346f, syOff + 359f, 268f, 40f)
        menuBackButton.setBounds(sxOff + 146f, syOff + 76f, 136f, 40f)
        quitButton.setBounds(sxOff + 346f, syOff + 187f, 268f, 40f)
        visualPanel?.setBounds(sxOff + 150f, syOff + 114f, 660f, 440f)
        packPanel?.setBounds(sxOff + 150f, syOff + 114f, 660f, 440f)
        skirmishMapPanel?.setBounds(sxOff + 150f, syOff + 114f, 660f, 440f)
        testScenePanel?.setBounds(sxOff + 150f, syOff + 114f, 660f, 440f)
        startupTitle.setText(when {
            loading -> "Loading match"
            setup -> "Skirmish setup"
            startupPage == StartupPage.DEVELOPMENT -> "Development"
            else -> "RustedWarfare-Reforge"
        })
        setupButton.isVisible = false
        retryContentButton.isVisible = legacySetup && !testScenes.selected && !skirmishMapState.hasSelection && qualifiedContent?.isFailure == true
        quitButton.isVisible = false
        menuBackButton.isVisible = legacySetup || managing
        previewHeading.isVisible = legacySetup
        mapPreview.isVisible = legacySetup
        val selectedSummary = skirmishMapState.activePreviewSummary
        val prepared = testScenes.choice?.scenario ?: qualifiedContent?.getOrNull()?.scenario
        previewHeading.setText(when {
            selectedSummary != null -> selectedSummary
            prepared != null -> {
                val choice = contentCandidate?.content
                val name = testScenes.choice?.name ?: choice?.content?.openingPack?.name ?: choice?.pack?.name ?: "Selected map"
                "$name\n${prepared.map.widthTiles} x ${prepared.map.heightTiles} tiles"
            }
            else -> "No map selected"
        })
        val startupMessage = when {
            loading -> state.preparationStatus ?: "Preparing content and resources..."
            loadError != null -> loadError
            testScenes.selected && testScenes.error != null -> testScenes.error
            packState.error != null -> packState.error
            packState.compositionError != null -> packState.compositionError
            !skirmishMapState.hasSelection && qualifiedContent?.isFailure == true -> qualifiedContent?.exceptionOrNull()?.message
            visualState?.canStart == false -> visualState.error ?: "Apply the resource selection before starting."
            !skirmishMapState.hasSelection && contentCandidate != null && qualifiedContent == null -> "Checking map and content..."
            !canStartMatch() -> if (rootField.text.isBlank())
                "Check and enable a base package in Content packs."
            else "Choose a checked content folder and enable any content packs you want to use."
            else -> skirmishMapState.selectedCatalog?.entry?.knownLimitation ?: ""
        }.orEmpty()
        startupNotice.isVisible = (loading || legacySetup) && startupMessage.isNotEmpty()
        if (!startupStatus.textEquals(startupMessage)) {
            startupStatus.setText(startupMessage)
            startupNotice.scrollY = 0f
        }
        val packageOpening = contentCandidate?.content?.content?.openingPack != null
        contentLabel.isVisible = legacySetup && !packageOpening
        contentLabel.setBounds(sxOff + 488f, syOff + 420f, 296f, 24f)
        rootField.isVisible = legacySetup && !packageOpening
        rootField.setBounds(sxOff + 488f, syOff + 379f, 296f, 36f)
        rootField.isDisabled = touchControls || !editable || testScenes.selected
        modeBox.isVisible = legacySetup
        modeBox.setBounds(sxOff + 488f, syOff + 323f, 296f, 40f)
        modeBox.isDisabled = !editable
        shareBox.isVisible = legacySetup
        shareBox.setBounds(sxOff + 488f, syOff + 268f, 296f, 40f)
        shareBox.isDisabled = !editable
        noNukesBox.isVisible = legacySetup
        noNukesBox.setBounds(sxOff + 488f, syOff + 220f, 296f, 36f)
        noNukesBox.isDisabled = !editable
        deploymentBox.isVisible = legacySetup && development && !touchControls
        deploymentBox.setBounds(sxOff + 488f, syOff + 174f, 296f, 36f)
        deploymentBox.isDisabled = !editable || !development || touchControls
        cameraJoystickBox.isVisible = legacySetup && touchControls
        cameraJoystickBox.setBounds(sxOff + 488f, syOff + 174f, 296f, 36f)
        cameraJoystickBox.isDisabled = !editable || !touchControls
        startButton.isVisible = legacySetup || current != null
        startButton.setText(if (legacySetup) "Start" else "Reopen")
        startButton.isDisabled = loading || legacySetup && !canStartMatch()
        if (legacySetup) startButton.setBounds(sxOff + 666f, syOff + 76f, 148f, 40f)
        exitButton.isVisible = loading || current != null
        exitButton.setText(if (loading) "Cancel" else "Return")
        if (loading) exitButton.setBounds(sxOff + 406f, syOff + 76f, 148f, 40f)
        packTab.isVisible = legacySetup
        packTab.setBounds(sxOff + 290f, syOff + 76f, 108f, 40f)
        visualsTab.isVisible = legacySetup && visualPanel != null
        visualsTab.setBounds(sxOff + 404f, syOff + 76f, 108f, 40f)
        skirmishMapTab.isVisible = legacySetup
        skirmishMapTab.setBounds(sxOff + (if (retryContentButton.isVisible) 326f else 178f), syOff + 174f, 128f, 34f)
        testSceneTab.isVisible = legacySetup && development && testScenarioSeed != null && !touchControls
        testSceneTab.setBounds(sxOff + 520f, syOff + 76f, 118f, 40f)
        skirmishMapPanel?.let {
            it.isVisible = false
        }
        testScenePanel?.let {
            it.isVisible = managing && startupPage == StartupPage.DEVELOPMENT
            it.setEditable(launch.canEdit && !loading)
            it.refresh(if (loading) state.preparationStatus ?: "Preparing scene..." else loadError)
        }
        visualPanel?.let {
            it.isVisible = managing && startupPage == StartupPage.RESOURCES
            it.setEditable(launch.canEdit && !loading)
            it.setStatus(if (loading) state.preparationStatus ?: "Preparing scene..." else loadError)
        }
        packPanel?.let {
            it.isVisible = managing && startupPage == StartupPage.CONTENT
            it.setEditable(launch.canEdit && !loading)
            it.setStatus(if (loading) state.preparationStatus ?: "Preparing scene..." else loadError)
        }
        statusLabel.setText(when {
            state.reclaimMode -> "Select ${if (menu?.canRepair == true) "unit" else "building"} to reclaim (Esc / right: cancel)"
            state.repairMode -> "Select unit to repair (Esc / right: cancel)"
            loading -> state.preparationStatus ?: "Preparing scene..."
            state.preparationStatus != null -> state.preparationStatus
            loadError != null -> if (visualRecoveryFailed) "Visual cache failed. Reopen or exit match to prepare again." else "Load failed"
            current == null -> "Ready"
            nativePanel -> if (matchStartupNotices.isNotEmpty() && (current.playerFeedback.isBlank() || current.playerFeedback == "Ready")) "! " + matchStartupNotices.joinToString(" | ") else current.playerFeedback
            else -> "${current.feedback}\nStep ${current.observation.stepIndex}"
        })
        minimapActor.isVisible = current != null
        val hudSnap = current?.let {
            HudSidebarProjection.project(
                session = it,
                menuOpen = hudMenuOpen,
                simPaused = stepClock.isPaused,
                activeRate = stepClock.rate,
            )
        }
        lastHudSnapshot = hudSnap
        val worldW = graphics?.projection?.worldWidth ?: 1000.0
        val worldH = graphics?.projection?.worldHeight ?: 1000.0

        // First compute pre-actions layout so BattleActionList anchors flush beneath queue/card inside 232px rail
        hudEvents.setCameraFollowing(scriptedCamera?.followsUnit == true)
        hudOverlay.update(
            stageWidth = viewport.worldWidth,
            stageHeight = viewport.worldHeight,
            worldWidth = worldW,
            worldHeight = worldH,
            hudSnapshot = hudSnap,
            eventStream = hudEvents,
            externalActionsHeight = 0f,
        )
        val railLayout = hudOverlay.layout
        // Keep legacy BattleActionList empty/hidden now that HudActionPanel owns the action list
        battleActions.update(null, null, emptyList())
        val actionListSnap = if (current != null && !paused) {
            HudActionProjection.projectActionList(
                session = current,
                activeConstructionTypeId = mapInput.constructionTypeId,
                reclaimMode = mapInput.reclaimMode,
                repairMode = mapInput.repairMode,
                patrolMode = mapInput.patrolMode,
                activeGroundActionName = mapInput.groundActionName,
                touchControls = touchControls,
            )
        } else null
        val targetModeCard = if (current != null && !paused && !hudMenuOpen && !stepClock.isPaused) {
            HudActionProjection.projectTargetModeCard(
                session = current,
                constructionTypeId = mapInput.constructionTypeId,
                constructionPreview = mapInput.constructionPreview,
                reclaimMode = mapInput.reclaimMode,
                repairMode = mapInput.repairMode,
                patrolMode = mapInput.patrolMode,
                patrolPointCount = mapInput.patrolPointCount,
                groundActionName = mapInput.groundActionName,
                groundActionPreview = mapInput.groundActionPreview,
                sandboxPick = mapInput.sandboxPick,
                touchControls = touchControls,
            )
        } else null
        val effectiveHudSnap = hudSnap?.copy(showActionsSection = actionListSnap != null && !actionListSnap.isEmpty)
        hudActionPanel.update(
            stageWidth = viewport.worldWidth,
            stageHeight = viewport.worldHeight,
            sidebarLeft = railLayout.sidebarLeft,
            actionsTopY = railLayout.actionsTopY,
            sidebarWidth = railLayout.sidebarWidth,
            actionsMaxHeight = railLayout.actionsMaxHeight,
            actionSnapshot = actionListSnap,
            targetModeCard = targetModeCard,
            primaryUnitId = current?.selectedId,
            language = UiStrings.currentLanguage,
        )
        hudGroupBar.update(
            visible = current != null && !paused && !hudMenuOpen && !stepClock.isPaused,
            joystickVisible = cameraJoystickActive(),
            countOf = { idx -> current?.groupCount(idx) ?: 0 },
            lang = UiStrings.currentLanguage,
        )
        val showTouchDebugBtn = development && touchControls && current != null
        val debugOpen = development && debugLayerState.enabled && current != null
        // Update debug overlay first so we know its leftColumnBottomY for notice shift
        refreshDebugOverlay(current)
        val noticesBelowY = if (debugOpen && ::hudDebugOverlay.isInitialized) hudDebugOverlay.leftColumnBottomY else null
        // Re-sync hudOverlay with actual HudActionPanel occupied height so sidebarBottom shrink-wraps accurately
        hudOverlay.update(
            stageWidth = viewport.worldWidth,
            stageHeight = viewport.worldHeight,
            worldWidth = worldW,
            worldHeight = worldH,
            hudSnapshot = effectiveHudSnap,
            eventStream = hudEvents,
            externalActionsHeight = hudActionPanel.occupiedHeight,
            showTouchDebugButton = showTouchDebugBtn,
            debugLayerEnabled = debugOpen,
            noticesStartBelowY = noticesBelowY,
        )
        if (current != null) {
            if (hudMenuOpen || stepClock.isPaused) {
                hudOverlay.toFront()
            } else {
                minimapActor.toFront()
                if (::hudDebugOverlay.isInitialized && debugOpen) hudDebugOverlay.toFront()
                hudGroupBar.toFront()
                hudActionPanel.toFront()
                if (showConsole && ::hudConsole.isInitialized) {
                    hudConsole.toFront()
                }
            }
        }

        if (current != null) {
            // Hide legacy match chrome replaced by InGameHudOverlay on both desktop and touch (HUD Spec §4, §5, §12.1)
            titleLabel.isVisible = false
            productionLabel.isVisible = false
            productionNoticeLabel.isVisible = false
            statusLabel.isVisible = false
            patrolLabel.isVisible = false
            startButton.isVisible = false
            exitButton.isVisible = false
            produceButton.isVisible = false
            cancelProductionButton.isVisible = false
            productionLayer.isVisible = false
            productionScroll.isVisible = false
            constructionLayer.isVisible = false
            constructionScroll.isVisible = false
            reclaimCard.isVisible = false
            repairCard.isVisible = false
            economicUpgradeCard.isVisible = false
            depthButton.isVisible = false
            upgradeButtons.values.forEach { (first, second) -> first.isVisible = false; second.isVisible = false }
            customActionButtons.values.forEach { it.isVisible = false }
            nativeIcon.isVisible = false
            nativeInfo.isVisible = false
            selectedLabel.isVisible = false
            selectionHeading.isVisible = false
            stopButton.isVisible = false
            patrolButton.isVisible = false
            groupButtons.forEach { it.isVisible = false }
        } else {
            productionLayer.isVisible = true
            constructionLayer.isVisible = true
        }
    }

    private fun refreshDebugOverlay(current: SandboxSession?) {
        if (!::hudDebugOverlay.isInitialized) return
        if (!development || !debugLayerState.enabled || current == null || !current.isOpen) {
            hudDebugOverlay.update(viewport.worldWidth, viewport.worldHeight, hudOverlay.layout.systemBarRect.y, null)
            return
        }
        val metrics = HudDebugFrameMetrics(
            fps = Gdx.graphics?.framesPerSecond ?: 0,
            frameMs = lastFrameMs,
            maxFrameMs = maxFrameMsWindow,
            rateMultiplier = stepClock.rate.multiplier,
            actualMultiplier = if (paused) 0.0 else stepClock.actualMultiplier,
            backlogSteps = lastPacingFrame.pendingSteps.toLong(),
            mapId = activeMapId,
            cursorWorld = lastCursorWorld,
        )
        val snap = HudDebugProjection.project(current, graphics?.projection, debugLayerState, metrics, viewport.worldWidth)
        hudDebugOverlay.update(viewport.worldWidth, viewport.worldHeight, hudOverlay.layout.systemBarRect.y, snap)
    }

    private fun battlefieldAt(screenX: Int, screenY: Int): Boolean {
        val point = stage.screenToStageCoordinates(Vector2(screenX.toFloat(), screenY.toFloat()))
        if (::hudOverlay.isInitialized && hudOverlay.containsHudPoint(point.x, point.y)) return false
        if (::hudActionPanel.isInitialized && (hudActionPanel.containsPoint(point.x, point.y) || hudActionPanel.isPointInTouchPreviewCard(point.x, point.y))) return false
        if (::hudGroupBar.isInitialized && hudGroupBar.containsPoint(point.x, point.y)) return false
        if (::hudConsole.isInitialized && hudConsole.containsPoint(point.x, point.y)) return false
        val hit = stage.hit(point.x, point.y, true)
        return hit == null || hit === stage.root
    }

    /** GL icon identity changes with the loaded presentation, independently from progress/funds refreshes. */
    private fun setUnitIcon(image: Image, typeId: String?) {
        val identity = graphics to typeId
        if (image.userObject == identity) return
        image.userObject = identity
        image.drawable = typeId?.let { graphics?.unitIcon(it) }?.let(::TextureRegionDrawable)
    }

    override fun resize(width: Int, height: Int) {
        cancelInput()
        if (width > 0 && height > 0) {
            val ppc = Gdx.graphics?.ppcY?.takeIf { it > 0f } ?: 0f
            val ppi = Gdx.graphics?.ppiY?.takeIf { it > 0f } ?: 0f
            uiMetrics = UiScale.metricsFromDensity(width, height, touchControls, ppc, ppi, userScaleMultiplier)
            viewport.unitsPerPixel = 1f / uiMetrics.scale
            viewport.update(width, height, true)
            battlefieldCamera.setToOrtho(false, viewport.worldWidth, viewport.worldHeight)
            graphics?.projection?.resizeBattlefield(battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
            fontDebouncer.onScaleChanged(uiMetrics.scale)
        } else {
            viewport.update(width, height, true)
        }
        graphics?.let { g ->
            g.updateMinimapAspect(viewport.screenWidth, viewport.screenHeight, viewport.worldWidth, viewport.worldHeight)
            syncMinimapActorBounds(g)
        }
        if (presentationReady) {
            lastHudState = null
            updateHud()
        }
        evidence.record("resize", mapOf("width" to width, "height" to height,
            "uiScale" to uiMetrics.scale, "logicalWidth" to uiMetrics.logicalWidth, "logicalHeight" to uiMetrics.logicalHeight,
            "screenX" to viewport.screenX, "screenY" to viewport.screenY,
            "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight))
    }
    override fun pause() {
        if (disposed) return
        paused = true
        stepClock.discardCatchup()
        ignoreNextHostFrame = true
        cancelInput()
        releaseSounds()
        // Release GL and native buffers on the GL thread before Android suspends it.
        // A later non-finishing Activity destroy can then close the host without GL calls.
        if (touchControls) {
            suspendedCamera = graphics?.projection?.camera ?: suspendedCamera
            releasePresentation()
        }
        evidence.record("pause", emptyMap())
    }

    override fun resume() {
        if (disposed) return
        try { activeVisuals?.verify() }
        catch (failure: Exception) {
            paused = true
            visualRecoveryFailed = true
            loadError = failure.message ?: failure.toString()
            evidence.recordFailure("visual-assets-recovery-failed", failure)
            updateHud()
            return
        }
        if (visualRecoveryFailed) {
            visualRecoveryFailed = false
            loadError = null
            updateHud()
        }
        if (touchControls && !presentationReady) {
            try {
                createPresentation()
                graphics = session?.let {
                    val origRoot = TmxSkirmishScenarioBuilder.tilesetFallbackRoots(
                        visualState?.selection?.originalDirectory ?: visualSettings?.initialSelection?.originalDirectory
                    ).firstOrNull()
                    val bound = activeVisuals?.let { v -> it.renderResources.bindVisuals(v, originalTilesetRoot = origRoot) }
                        ?: it.renderResources.resolveTileAtlases(null, origRoot)
                    SandboxGraphics.load(bound, activeRoot, fullBattlefield = true, uiTheme = activeUiTheme)
                }
                graphics?.let { g ->
                    g.updateMinimapAspect(viewport.screenWidth, viewport.screenHeight, viewport.worldWidth, viewport.worldHeight)
                    syncMinimapActorBounds(g)
                    val map = g.projection
                    map.resizeBattlefield(battlefieldWidth.toDouble(), battlefieldHeight.toDouble())
                    suspendedCamera?.let(map::restoreCamera)
                    scriptedCamera?.rebind(map)
                }
                suspendedCamera = null
                updateHud()
            } catch (failure: Exception) {
                preserveFailure(failure) { leaveMatch() }
                preserveFailure(failure) { releasePresentation() }
                preserveFailure(failure) { failLoad(failure) }
                throw failure
            }
        }
        try {
            val current = session
            if (current != null && sounds == null) sounds = activeVisuals?.resolvedSounds?.let { resources -> SoundPlayback(
                current.consumeSounds(checkNotNull(graphics).projection.soundListener()), resources,
                soundFactory, evidence.withGeneration(generation)) }
        } catch (failure: Exception) {
            paused = true
            loadError = failure.message ?: failure.toString()
            evidence.recordFailure("sound-recovery-failed", failure)
            updateHud()
            return
        }
        paused = false
        stepClock.discardCatchup()
        ignoreNextHostFrame = true
        evidence.record("resume", emptyMap())
        if (touchControls && session != null) recordCameraJoystick("resumed", null)
    }

    fun destroyPausedHost() {
        check(!presentationReady || disposed) { "Release presentation on the GL thread before Android host destruction" }
        dispose()
    }

    private fun releaseSounds() {
        val closing = sounds; sounds = null
        closing?.close()
    }

    private fun cameraJoystickActive() =
        touchControls && !paused && session?.options?.cameraJoystickEnabled == true

    private fun takeOverCamera(reason: String) {
        scriptedCamera?.takeOver(reason)
        hudEvents.setCameraFollowing(scriptedCamera?.followsUnit == true)
        if (::hudOverlay.isInitialized) hudOverlay.syncEventStream(hudEvents)
    }

    private fun takeCameraFromJoystick() {
        if (cameraJoystick.steering) takeOverCamera("joystick")
    }

    private fun clearCameraJoystick(event: String, systemPointer: Int? = null) {
        val held = cameraJoystick.pointer
        if (cameraJoystick.cancel() || systemPointer != null) recordCameraJoystick(event, held, systemPointer)
    }

    private fun recordCameraJoystick(event: String, pointer: Int?, systemPointer: Int? = null) {
        evidence.recordWhenEnabled("camera-joystick") {
            val map = graphics?.projection
            mapOf("event" to event, "generation" to generation, "stepIndex" to session?.observation?.stepIndex,
                "enabled" to (session?.options?.cameraJoystickEnabled == true), "pointer" to pointer,
                "systemPointer" to systemPointer, "owner" to cameraJoystick.pointer,
                "center" to map?.camera?.center?.fields(), "zoom" to map?.camera?.zoom)
        }
    }

    private fun drawCameraJoystick() {
        val area = cameraJoystickArea
        val radius = area.width / 2f
        val x = area.x + radius
        val y = area.y + radius
        val direction = cameraJoystick.direction
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.setColor(UiTokens.RAIL.r, UiTokens.RAIL.g, UiTokens.RAIL.b, 0.45f)
        shapes.circle(x, y, radius, 40)
        shapes.setColor(0.788f, 0.820f, 0.835f, 0.55f)
        shapes.circle(x + direction.x.toFloat() * radius * .58f,
            y + direction.y.toFloat() * radius * .58f, radius * .35f, 28)
        shapes.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(UiTokens.LINE.r, UiTokens.LINE.g, UiTokens.LINE.b, 0.90f)
        shapes.circle(x, y, radius, 40)
        shapes.end()
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    private fun syncMinimapActorBounds(g: SandboxGraphics) {
        if (!::minimapActor.isInitialized) return
        val area = g.minimap.area
        minimapActor.setBounds(area.left.toFloat(), area.bottom.toFloat(), area.width.toFloat(), area.height.toFloat())
    }

    private fun releasePresentation() {
        if (!presentationReady) return
        cancelInput()
        presentationReady = false
        Gdx.input.inputProcessor = null
        val closingGraphics = graphics; graphics = null
        val closingCommandIcons = commandIcons; commandIcons = null
        val closingControlIcons = outGameControlIcons; outGameControlIcons = null
        val closingFonts = fontSet; fontSet = null
        val closingFallback = fallbackFont; fallbackFont = null
        listOf<() -> Unit>(
            { releaseSounds() },
            { closingGraphics?.close() },
            { closingCommandIcons?.close() },
            { closingControlIcons?.close() },
            { if (::dynamicMapBackdrop.isInitialized) dynamicMapBackdrop.close() },
            { if (::mainMenuScreen.isInitialized) mainMenuScreen.close() },
            { if (::mapPickerScreen.isInitialized) mapPickerScreen.close() },
            { if (::skirmishLobbyScreen.isInitialized) skirmishLobbyScreen.close() },
            { visualPanel?.close() },
            { packPanel?.close() },
            { testScenePanel?.close() },
            { skirmishMapPanel?.close() },
            { stage.dispose() },
            {
            if (::skin.isInitialized && skin.has("default-font", BitmapFont::class.java)) {
                skin.remove("default-font", BitmapFont::class.java)
            }
            skin.dispose()
        }, { closingFonts?.close() }, { closingFallback?.dispose() },
            { shapes.dispose() }, { batch.dispose() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) {
                evidence.recordFailure("cleanup-error", failure)
                Gdx.app.error("p001-presentation", "Cleanup failed", failure)
            }
        }
        evidence.record("presentation-released", emptyMap())
    }

    private fun cancelInput() {
        clearCameraJoystick("input-cancel")
        constructionHover = null
        upgradeHover = null
        upgradeInputs.forEach { it.cancel() }
        reclaimHover = false
        repairHover = false
        if (::repairInput.isInitialized) repairInput.cancel()
        if (::reclaimInput.isInitialized) reclaimInput.cancel()
        constructionCards.values.forEach { it.input.cancel() }
        productionHover = null
        productionCards.values.forEach { it.input.cancel() }
        factoryUpgradeCards.values.forEach { it.input.cancel() }
        factoryUpgradeHover = null
        if (::economicUpgradeInput.isInitialized) economicUpgradeInput.cancel()
        if (::nativeTooltip.isInitialized) nativeTooltip.isVisible = false
        f3Held = false
        f3ComboUsed = false
        if (::hudGroupBar.isInitialized) hudGroupBar.cancelHold()
        if (::hudActionPanel.isInitialized) hudActionPanel.clearTouchPreview()
        if (::mapInput.isInitialized) mapInput.cancel()
        if (::stage.isInitialized) {
            UnifiedDropdown.closeAllOpenDropdowns(stage)
            stage.cancelTouchFocus()
            stage.unfocusAll()
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Gdx.input?.inputProcessor = null
        listOf<() -> Unit>({ leaveMatch() }, { loader.close() }, { qualification.close() }, { visualState?.close() }, { packState.close() }, { releasePresentation() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) { Gdx.app?.error("p001-dispose", "Cleanup failed", failure) }
        }
        evidence.record("application-disposed", emptyMap())
    }
}
