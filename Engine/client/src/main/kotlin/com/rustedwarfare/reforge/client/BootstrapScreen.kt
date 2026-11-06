package com.rustedwarfare.reforge.client

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
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
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.viewport.FitViewport
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

class BootstrapScreen(
    private val initialRoot: Path = Paths.get("TestLab/local/P001-移动沙盒输入"),
    private val evidence: ClientEvidence = ClientEvidence.NONE,
    private val touchControls: Boolean = false,
    private val visualSettings: VisualAssetSettings? = null,
    private val soundFactory: PlaybackSoundFactory = GdxPlaybackSoundFactory,
) : ApplicationAdapter() {
    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var skin: Skin
    private lateinit var stage: Stage
    private val viewport = FitViewport(960f, 640f)
    private val loader = SandboxLoader()
    private var session: SandboxSession? = null
    private var graphics: SandboxGraphics? = null
    private var sounds: SoundPlayback? = null
    private lateinit var deploymentBox: CheckBox
    private lateinit var commandField: TextField
    private lateinit var commandFeedbackLabel: Label
    private lateinit var commandHintLabel: Label
    private lateinit var commandPanel: Table
    private var commandConsoleOpen = false
    private lateinit var rootField: TextField
    private lateinit var selectedLabel: Label
    private lateinit var selectionHeading: Label
    private lateinit var productionNoticeLabel: Label
    private lateinit var statusLabel: Label
    private lateinit var messageLabel: Label
    private lateinit var stopButton: TextButton
    private lateinit var patrolButton: TextButton
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
    private data class ProductionCard(val table: Table, val icon: Image, val text: Label, val count: Label,
                                     val input: ProductionButtonInput, var compact: Boolean? = null)
    private val productionCards = linkedMapOf<String, ProductionCard>()
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
    private var nativeSelection: Set<String> = emptySet()
    private val nativePanel: Boolean get() = NativeActionPanel.supports(session?.observation?.scenarioId) ||
        session?.hasConstructorDefinitions == true || session?.hasProductionDefinitions == true
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
    private var showContentPack = visualSettings == null
    private val launch = SandboxLaunchState(SandboxConfiguration(initialRoot.toAbsolutePath().normalize(), SandboxOptions(), visualState?.selection ?: VisualAssetSelection()))
    private var activeVisuals: PreparedVisualAssets? = null
    private var pendingLoad: SandboxLoad? = null
    private var loading = false
    private var menuDrawn = false
    private var menuEpoch = 0L
    private var lastStartAvailable: Boolean? = null
    private var firstDrawnGeneration: Long? = null
    private var generation = 0L
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
        val patrolMode: Boolean, val patrolPoints: Int, val reclaimMode: Boolean, val workerQueueText: String?, val menu: ActionMenuView?)
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
        batch = SpriteBatch().also { created += { it.dispose() } }
        shapes = ShapeRenderer().also { created += { it.dispose() } }
        skin = makeSkin().also { created += { it.dispose() } }
        stage = Stage(viewport, batch).also { created += { it.dispose() } }
        groupButtons.clear()
        upgradeButtons.clear()
        upgradeInputs.clear()
        upgradeHover = null
        constructionCards.clear()
        productionCards.clear()
        // Consume the whole panel, including empty space, for the full press chain.
        stage.addActor(Actor().apply {
            setBounds(736f, 0f, 224f, 592f)
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = true
            })
        })
        fun label(text: String, x: Float, y: Float, w: Float, h: Float): Label = Label(text, skin).also {
            it.setBounds(x, y - h, w, h); it.setAlignment(Align.topLeft); it.setWrap(true); stage.addActor(it)
        }
        label("RustedWarfare-Reforge", 16f, 628f, 440f, 28f).setFontScale(1.3f)
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
        for ((key, positions) in NativeActionPanel.upgrades) {
            fun endpoint(text: String, bounds: NativeCardBounds, cancel: Boolean): TextButton = TextButton(text, skin).also { button ->
                button.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
                val input = UpgradeButtonInput({ session }, { !paused && button.isVisible && !button.isDisabled },
                    key, cancel, { lastHudState = null; updateHud() })
                upgradeInputs += input
                button.addListener(input)
                button.addListener(object : InputListener() {
                    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                        if (pointer == -1) { upgradeHover = key; lastHudState = null }
                    }
                    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                        if (pointer == -1 && upgradeHover == key) { upgradeHover = null; lastHudState = null }
                    }
                })
                stage.addActor(button)
            }
            val buy = endpoint("", positions.first, false)
            val cancel = endpoint("Cancel", positions.second, true)
            upgradeButtons[key] = buy to cancel
        }
        statusLabel = label("", 16f, 52f, 480f, 44f)
        productionNoticeLabel = label("", 16f, 84f, 400f, 24f)
        messageLabel = label("Loading", 40f, 400f, 560f, 260f)
        stopButton = button("Stop (S)", 752f, 168f, 84f) { mapInput.cancel(); session?.stop(); updateHud() }
        patrolButton = button("Patrol (P)", 844f, 168f, 84f) { mapInput.beginPatrol(); updateHud() }
        exitButton = button("Exit match", 752f, 72f, 176f) { leaveMatch() }
        contentLabel = label("Content folder", 752f, 378f, 176f, 24f)
        rootField = TextField(launch.configuration.root.toString(), skin).also {
            it.setOnlyFontChars(false)
            it.setBounds(752f, 312f, 176f, 34f); stage.addActor(it)
            it.isDisabled = touchControls
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
        startButton = button("Start", 752f, 120f, 176f) { requestLoad() }
        visualPanel = visualState?.let { state -> VisualAssetSettingsPanel(state, skin, visualSettings?.let { it::createUiFont },
            contentChoice = packState::choice) {
            evidence.recordVisualAssetMenu(state)
            loadError = null; lastHudState = null; updateHud()
        }.also { it.setBounds(40f, 120f, 660f, 440f); stage.addActor(it) } }
        packPanel = ContentPackPanel(packState, skin, visualSettings?.let { it::createUiFont }) {
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
        }.also { it.setBounds(40f, 120f, 660f, 440f); stage.addActor(it) }
        visualsTab = button("Resource packs", 40f, 568f, 176f) {
            showContentPack = false; lastHudState = null; updateHud()
        }
        packTab = button("Content pack", 232f, 568f, 176f) {
            showContentPack = true; lastHudState = null; updateHud()
        }
        mapInput = SandboxInput({ session }, { graphics?.projection }, { x, y ->
            viewport.unproject(Vector2(x.toFloat(), y.toFloat())).let { Position(it.x.toDouble(), it.y.toDouble()) }
        }, { !paused && session != null }, { stage.keyboardFocus is TextField },
            { Gdx.input.isKeyPressed(it) }, { updateHud() }, evidence, touchControls, { x, y ->
                val bottom = Gdx.graphics.height - y
                x >= viewport.screenX && x < viewport.screenX + viewport.screenWidth &&
                    bottom >= viewport.screenY && bottom < viewport.screenY + viewport.screenHeight
            })
        for (index in 1..3) groupButtons += button("$index (0)", 508f + (index - 1) * 72f, 12f, 64f) { mapInput.group(index) }
        stage.addActor(Actor().apply {
            setBounds(752f, 456f, 176f, 112f)
            addListener(object : InputListener() {
                private var owned = false
                private fun locate(x: Float, y: Float, dragging: Boolean) {
                    if (paused || stage.keyboardFocus is TextField) return
                    val g = graphics ?: return
                    val target = g.minimap.locate(752.0 + x, 456.0 + y, dragging) ?: return
                    g.projection.centerOn(target)
                    evidence.record("camera", mapOf("reason" to "minimap",
                        "center" to g.projection.camera.center.fields(), "zoom" to g.projection.camera.zoom))
                }
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || button != Input.Buttons.LEFT || session == null || paused) return false
                    owned = true; locate(x, y, false); return true
                }
                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (owned && pointer == 0) locate(x, y, true)
                }
                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) { owned = false }
            })
        })
        commandPanel = Table().also {
            it.background = skin.newDrawable("white", Color(.07f, .10f, .09f, 1f))
            it.setBounds(40f, 100f, 660f, 140f)
            stage.addActor(it)
        }
        commandFeedbackLabel = label("", 48f, 232f, 644f, 84f).also {
            it.setWrap(true); it.setAlignment(Align.topLeft)
        }
        commandField = TextField("", skin).also {
            it.setOnlyFontChars(false); it.maxLength = 512
            it.setBounds(48f, 108f, 644f, 34f); stage.addActor(it)
        }
        commandHintLabel = label("Sandbox: / for commands", 40f, 100f, 660f, 24f)
        commandConsoleOpen = false
        Gdx.input.inputProcessor = InputMultiplexer(object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (keycode == Input.Keys.SLASH && !commandConsoleOpen && !paused && !loading &&
                    !touchControls && session?.canDeploy == true && stage.keyboardFocus !is TextField) {
                    mapInput.cancel()
                    commandConsoleOpen = true
                    commandField.text = ""
                    stage.keyboardFocus = commandField
                    lastHudState = null; updateHud()
                    evidence.record("sandbox-console", mapOf("generation" to generation, "open" to true))
                    return true
                }
                if (!commandConsoleOpen) return false
                if (keycode == Input.Keys.ESCAPE) {
                    closeCommandConsole(); updateHud(); return true
                }
                if (keycode == Input.Keys.ENTER) {
                    session?.submitSandboxCommand(commandField.text)
                    commandField.selectAll()
                    lastHudState = null; updateHud(); return true
                }
                return false
            }
            override fun keyTyped(character: Char): Boolean = commandConsoleOpen && (character == '\r' || character == '\n')
        }, stage, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (!touchControls || keycode != Input.Keys.BACK) return false
                leaveMatch()
                Gdx.app.exit()
                return true
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

    private fun makeSkin(): Skin {
        val result = Skin()
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE); pixmap.fill()
        val texture = try { Texture(pixmap) } finally { pixmap.dispose() }
        result.add("white", texture)
        val font = BitmapFont()
        result.add("default-font", font)
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

    private fun requestLoad() {
        if (loading) {
            evidence.record("load-request-ignored", mapOf("generation" to generation, "reason" to "already-loading"))
            return
        }
        try {
            val request = if (launch.canEdit) {
                check(visualState?.canStart != false) { visualState?.error ?: "Save and apply the visual selection first" }
                check(packState.canStart) { packState.error ?: "Finish checking the content package first" }
                launch.configure(launch.configuration.copy(root = Paths.get(rootField.text).toAbsolutePath().normalize(),
                    visuals = visualState?.selection ?: launch.configuration.visuals, contentPack = packState.choice()))
                launch.start()
            } else launch.reopen()
            val acceptedNanos = System.nanoTime()
            closeMatchResources()
            menuDrawn = false
            pendingLoad = request
            generation = request.generation
            activeRoot = request.configuration.root
            loading = true
            loadError = null
            evidence.record("load-request", mapOf("generation" to generation, "root" to activeRoot.toString(),
                "acceptedNanos" to acceptedNanos, "menuEpoch" to menuEpoch,
                "mode" to request.configuration.options.mode.name,
                "shareAlliedVision" to request.configuration.options.shareAlliedVision,
                "deploymentEnabled" to request.configuration.options.deploymentEnabled))
            val contentPack = request.configuration.contentPack
            val prepare = visualState?.prepareForLoad(contentPack)
                ?: visualSettings?.let { settings -> { settings.prepareForContent(request.configuration.visuals, contentPack) } }
            if (contentPack == null) loader.request(activeRoot, prepare)
            else loader.requestPack(activeRoot, contentPack, prepare)
        } catch (failure: Exception) { failLoad(failure) }
        updateHud()
    }

    private fun leaveMatch() {
        closeMatchResources()
        launch.exit()
        launch.configure(launch.configuration.copy(options = launch.configuration.options.copy(deploymentEnabled = false)))
        if (::deploymentBox.isInitialized) deploymentBox.isChecked = false
        if (visualState?.busy == true) visualState.cancel()
        menuDrawn = false
        if (presentationReady) { stage.unfocusAll(); updateHud() }
        evidence.record("match-exit", mapOf("generation" to generation))
    }

    private fun closeMatchResources() {
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
        activeVisuals = null
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
        loadError = failure.message ?: failure.toString()
        preserveFailure(failure) {
            evidence.recordWhenEnabled("load-error") { mapOf("generation" to generation, "message" to loadError) }
        }
        Gdx.app.error("p001-load", loadError, failure)
        updateHud()
    }

    override fun render() {
        if (!presentationReady || disposed) return
        if (loading) loader.pollPrepared()?.fold(onSuccess = { loaded ->
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
                pendingGraphics = SandboxGraphics.load(loaded, activeRoot)
                evidence.record("load-graphics-ready", mapOf("generation" to generation,
                    "startedNanos" to graphicsStartedNanos, "completedNanos" to System.nanoTime(),
                    "worldWidth" to pendingGraphics.projection.worldWidth, "worldHeight" to pendingGraphics.projection.worldHeight))
                val runGeneration = generation
                pendingSession = SandboxSession(scenario, evidence.withGeneration(runGeneration), request.configuration.options, generation = runGeneration)
                pendingSounds = loaded.visuals?.resolvedSounds?.let { resources -> SoundPlayback(
                    pendingSession.consumeSounds(pendingGraphics.projection.soundListener()), resources,
                    soundFactory, evidence.withGeneration(runGeneration)) }
                evidence.recordLoadReady(generation, loaded)
                check(launch.complete(request.generation)) { "Cancelled load generation" }
                loaded.visuals?.let { visualState?.rememberPublished(it, loaded.contentPack) }
                pendingLoad = null
                graphics = pendingGraphics
                activeVisuals = loaded.visuals
                session = pendingSession
                sounds = pendingSounds
                loaded.visuals?.let { visuals -> evidence.recordVisualAssetsReady(visuals, generation) }
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
                    sounds?.let { player -> player.consume(current.consumeSounds(checkNotNull(graphics).projection.soundListener())) }
                }
                recordSimulationPacing()
            } catch (failure: Exception) {
                preserveFailure(failure) { releaseSounds() }
                throw failure
            }
        }
        updateHud()
        updatePacingHud()
        Gdx.gl.glClearColor(0.075f, 0.09f, 0.085f, 1f)
        nativeTooltip.isVisible = nativePanel && !paused &&
            (productionHover != null && session?.productionActions?.any { it.typeId == productionHover } == true ||
                constructionHover != null && session?.constructionActions?.any { it.typeId == constructionHover } == true ||
                economicUpgradeHover && economicUpgradeCard.isVisible || reclaimHover && reclaimCard.isVisible ||
                upgradeHover != null && session?.selectedUpgrades?.any { it.actionKey == upgradeHover } == true)
        if (nativeTooltip.isVisible) nativeTooltip.toFront()
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0.105f, 0.13f, 0.12f, 1f)
        shapes.rect(736f, 0f, 224f, 592f)
        shapes.rect(0f, 592f, 960f, 48f)
        shapes.color = Color.BLACK
        shapes.rect(16f, 64f, 704f, 512f)
        shapes.end()
        if (current != null) {
            val clip = Rectangle()
            stage.calculateScissors(Rectangle(16f, 64f, 704f, 512f), clip)
            if (ScissorStack.pushScissors(clip)) try {
                batch.begin(); graphics?.draw(batch, current.frame, mapInput.constructionPreview); batch.end()
                shapes.begin(ShapeRenderer.ShapeType.Line)
                graphics?.drawOverlay(shapes, current.frame, current.selectedIds, mapInput.box, mapInput.constructionPreview)
                shapes.end()
            } finally { ScissorStack.popScissors() }
            graphics?.drawMinimap(shapes, current.frame)
        }
        stage.act(Gdx.graphics.deltaTime); stage.draw()
        val drawnNanos = System.nanoTime()
        if (current == null && !loading && launch.canEdit) {
            val available = !startButton.isDisabled
            if (!menuDrawn) {
                menuEpoch++
                evidence.record("startup-menu-drawn", mapOf("menuEpoch" to menuEpoch, "drawnNanos" to drawnNanos,
                    "canStart" to available, "visualBusy" to visualState?.busy,
                    "boundary" to "Stage draw submitted; native pixels are separate evidence"))
                menuDrawn = true; lastStartAvailable = null
            }
            if (lastStartAvailable != available) {
                evidence.record("start-availability", mapOf("menuEpoch" to menuEpoch, "drawnNanos" to drawnNanos,
                    "canStart" to available, "visualBusy" to visualState?.busy,
                    "actionId" to "start-match", "bounds" to mapOf("x" to startButton.x, "y" to startButton.y,
                        "width" to startButton.width, "height" to startButton.height)))
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
        button("start-match", startButton); button("exit-match", exitButton)
        rateButtons.forEach { (rate, actor) -> button("set-speed:${rate.multiplier}", actor) }
        button("pause-simulation", simulationPauseButton)
        button("content-tab", packTab)
        entries += DrawnAction("sandbox-deployment", deploymentBox, deploymentBox.text.toString(),
            enabled = !deploymentBox.isDisabled)
        entries += DrawnAction("sandbox-command-input", commandField, commandField.text,
            enabled = commandConsoleOpen && current?.canDeploy == true)
        packPanel?.let { entries += it.drawnActions() }
        button("stop", stopButton); button("choose-patrol", patrolButton)
        val nativeActive = nativePanel && !paused && current?.isOpen == true
        for ((type, card) in productionCards) {
            if (card.table.parent === productionContent && !productionCardExposed(card.table)) continue
            val enabled = nativeActive && menu?.production?.any { it.typeId == type } == true
            val text = card.text.text.toString() + if (card.count.text.isNotEmpty()) "\n${card.count.text}" else ""
            entries += DrawnAction("produce:$type", card.table, text, enabled = enabled)
            entries += DrawnAction("cancel-production:$type", card.table, text, button = "right", enabled = enabled)
        }
        entries += DrawnAction("production-list", productionScroll, "Products", enabled = nativeActive)
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
        val projection = graphics?.projection?.let { map -> mapOf("worldWidth" to map.worldWidth,
            "worldHeight" to map.worldHeight, "left" to map.left, "bottom" to map.bottom, "scale" to map.scale,
            "areaLeft" to map.mapArea.left, "areaBottom" to map.mapArea.bottom,
            "areaWidth" to map.mapArea.width, "areaHeight" to map.mapArea.height) }
        val frame = ActionLayoutFrame(generation, current?.selectionRevision ?: 0, selected.toList(),
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

    private fun updatePacingHud() {
        if (!presentationReady) return
        val visible = session?.isOpen == true
        rateButtons.forEach { (rate, actor) ->
            actor.isVisible = visible
            actor.isDisabled = paused || loading || !visible
            actor.setText("${rate.multiplier}x" + if (stepClock.rate == rate) " *" else "")
        }
        simulationPauseButton.isVisible = visible
        simulationPauseButton.isDisabled = paused || loading || !visible
        simulationPauseButton.setText(if (stepClock.isPaused) "Resume" else "Pause")
        pacingLabel.isVisible = visible
        val actual = if (paused) 0.0 else stepClock.actualMultiplier
        val state = if (paused) "Suspended" else if (stepClock.isPaused) "Paused" else "Speed"
        pacingLabel.setText("$state ${stepClock.rate.multiplier}x | actual " +
            (actual?.let { String.format(Locale.ROOT, "%.2fx", it) } ?: "--") +
            " | backlog ${lastPacingFrame.pendingSteps.toLong()}")
    }

    private fun closeCommandConsole() {
        if (!commandConsoleOpen) return
        commandConsoleOpen = false
        if (::commandField.isInitialized && stage.keyboardFocus === commandField) stage.keyboardFocus = null
        lastHudState = null
        evidence.record("sandbox-console", mapOf("generation" to generation, "open" to false))
    }

    private fun syncProductionCards(actions: List<ProductionActionView>) {
        val stale = productionCards.iterator()
        while (stale.hasNext()) {
            val (type, card) = stale.next()
            if (actions.none { it.typeId == type }) {
                card.input.cancel(); card.table.remove(); stale.remove()
                if (productionHover == type) productionHover = null
            }
        }
        val overflow = actions.size > 6
        productionScroll.isVisible = nativePanel && overflow
        if (!productionScroll.isVisible && stage.scrollFocus === productionScroll) stage.scrollFocus = null
        val rows = (actions.size + 1) / 2
        if (overflow) productionContent.setSize(176f, rows * 60f)
        actions.forEachIndexed { index, action ->
            val type = action.typeId
            val card = productionCards.getOrPut(type) {
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
                }
                val input = ProductionButtonInput({ session }, { nativePanel && !paused },
                    { lastHudState = null; updateHud() }, { hovered ->
                        if (hovered) productionHover = type else if (productionHover == type) productionHover = null
                        lastHudState = null
                    }, { type })
                table.addListener(input)
                ProductionCard(table, icon, text, count, input)
            }
            val parent = if (overflow) productionContent else productionLayer
            if (card.table.parent !== parent) { card.input.cancel(); parent.addActor(card.table) }
            card.table.isVisible = nativePanel
            when {
                overflow -> card.table.setBounds((index % 2) * 92f, (rows - 1 - index / 2) * 60f, 84f, 54f)
                actions.size <= 3 -> NativeActionPanel.production[index].let { card.table.setBounds(it.x, it.y, it.width, it.height) }
                else -> card.table.setBounds(752f + (index % 2) * 92f, 280f - (index / 2) * 60f, 84f, 54f)
            }
            setUnitIcon(card.icon, type)
            val title = if (type == "mechHeavyMissile") "Heavy mech" else action.title
            val compact = actions.size > 3
            if (card.compact != compact) {
                card.table.getCell(card.icon).size(if (compact) 24f else 28f)
                card.table.getCell(card.text).height(if (compact) 12f else 16f)
                card.table.getCell(card.count).height(if (compact) 10f else 12f)
                card.table.invalidate(); card.compact = compact
            }
            card.text.setFontScale(if (compact || title.length > 12 || type == "mechHeavyMissile") .7f else 1f)
            card.text.setText(title)
            card.count.setFontScale(if (compact) .6f else .7f)
            card.count.setText(if (action.count > 0) "(${action.count})" else "")
            card.text.color = if (action.affordable) Color(.9f, .92f, .9f, 1f) else Color(.55f, .58f, .55f, 1f)
            card.count.color = card.text.color
            card.table.background = skin.newDrawable("white", if (card.input.pressed)
                Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
        }
        if (overflow) productionScroll.invalidate()
    }

    private fun productionCardExposed(table: Table): Boolean {
        table.localToStageCoordinates(productionCorner.set(0f, 0f))
        return productionCorner.x >= productionScroll.x && productionCorner.y >= productionScroll.y &&
            productionCorner.x + table.width <= productionScroll.x + productionScroll.width &&
            productionCorner.y + table.height <= productionScroll.y + productionScroll.height
    }

    private fun syncConstructionCards(actions: List<ConstructionActionView>, economic: Boolean) {
        val stale = constructionCards.iterator()
        while (stale.hasNext()) {
            val (type, card) = stale.next()
            if (actions.none { it.typeId == type }) {
                card.input.cancel()
                card.table.remove()
                stale.remove()
            }
        }
        val overflow = actions.size > 6
        val legacy = economic && actions.all { it.typeId in setOf("extractorT1", "landFactory",
            "mechFactory", "airFactory", "seaFactory", "fabricatorT1", "c_turret_t1", "laserDefence") }
        constructionScroll.isVisible = nativePanel && overflow
        if (!constructionScroll.isVisible && stage.scrollFocus === constructionScroll) stage.scrollFocus = null
        val rows = (actions.size + 1) / 2
        if (overflow) constructionContent.setSize(176f, rows * 60f)
        actions.forEachIndexed { index, action ->
            val type = action.typeId
            val card = constructionCards.getOrPut(type) {
                val icon = Image().apply { touchable = Touchable.disabled }
                val text = Label("", skin).apply {
                    setAlignment(Align.center); touchable = Touchable.disabled; setFontScale(.7f); setEllipsis(true)
                }
                val table = Table(skin).apply {
                    touchable = Touchable.enabled
                    add(icon).size(28f, 28f).padTop(3f).row()
                    add(text).width(80f).height(21f)
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
            val parent = if (overflow) constructionContent else constructionLayer
            if (card.table.parent !== parent) {
                card.input.cancel()
                parent.addActor(card.table)
            }
            card.table.isVisible = nativePanel
            when {
                overflow -> card.table.setBounds((index % 2) * 92f, (rows - 1 - index / 2) * 60f, 84f, 54f)
                legacy -> card.table.setBounds(
                    if (type in setOf("extractorT1", "c_turret_t1", "laserDefence")) 844f else 752f,
                    when (type) { "extractorT1" -> 280f; "landFactory", "c_turret_t1" -> 220f; else -> 160f },
                    84f, 54f)
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
        if (current?.canDeploy != true) closeCommandConsole()
        val showConsole = commandConsoleOpen && current?.canDeploy == true
        commandPanel.isVisible = showConsole
        commandField.isVisible = showConsole
        commandFeedbackLabel.isVisible = showConsole
        commandFeedbackLabel.setText(current?.sandboxCommandFeedback ?: "")
        commandHintLabel.isVisible = current?.canDeploy == true && !showConsole && !touchControls
        val menu = current?.actionMenu
        val selected = current?.observation?.units?.find { it.unitId == current.selectedId }
        val state = HudState(selected, current?.selectedIds ?: emptySet(), (1..3).map { current?.groupCount(it) ?: 0 }, current?.feedback, current?.observation?.stepIndex,
            launch.canEdit, loading, visualState?.preparationStatus, loadError, current != null, menu?.balance, menu?.factoryQueue,
            mapInput.patrolMode, mapInput.patrolPointCount, mapInput.reclaimMode, menu?.workerQueueText, menu)
        if (state == lastHudState) return
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
            (if (selected.typeId.startsWith("c_turret")) "\n${selected.typeId}" else "") +
            (selected.pointDefense?.let { String.format(Locale.ROOT, "\nEnergy %.3f%s", it.energy,
                if (it.exhausted) " LOCKED" else "") } ?: ""))
        val queue = menu?.factoryQueue
        selectionHeading.isVisible = !nativePanel
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
        syncProductionCards(menu?.production.orEmpty())
        reclaimCard.isVisible = nativePanel && menu?.canMaintain == true
        reclaimCard.background = skin.newDrawable("white", if (reclaimInput.pressed || state.reclaimMode)
            Color(.37f, .48f, .4f, 1f) else Color(.23f, .28f, .26f, 1f))
        syncConstructionCards(menu?.construction.orEmpty(), current?.hasEconomicConstruction == true)
        val selectedInfo = menu?.selectedUnitInfo
        nativeInfo.isVisible = nativePanel && selectedInfo != null
        nativeIcon.isVisible = nativePanel && selected != null && state.selectedIds.size == 1
        if (nativeSelection != state.selectedIds) {
            productionCards.values.forEach { it.input.cancel() }
            productionHover = null
            economicUpgradeInput.cancel()
            upgradeInputs.forEach { it.cancel() }
            upgradeHover = null
            reclaimInput.cancel()
            constructionCards.values.forEach { it.input.cancel() }
            constructionHover = null
            nativeSelection = state.selectedIds.toSet()
        }
        if (nativePanel) {
            produceButton.isVisible = false
            cancelProductionButton.isVisible = false
            selectedLabel.setBounds(752f, 340f, 88f, 46f)
            selectedLabel.setText(if (selected == null) "None" else if (state.selectedIds.size > 1)
                "${state.selectedIds.size} selected" else "${menu?.selectedTitle} x1")
            setUnitIcon(nativeIcon, selected?.typeId)
            nativeInfo.setFontScale(if (state.workerQueueText != null) .7f else 1f)
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
            val action = upgrades.firstOrNull { it.actionKey == key }
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
        val economicPanel = nativePanel && (current?.hasEconomicConstruction == true || constructionCards.size > 4 || productionCards.size > 4)
        stopButton.setY(if (economicPanel) 112f else if (nativePanel) 144f else 168f)
        patrolButton.setY(if (economicPanel) 112f else if (nativePanel) 144f else 168f)
        startButton.setY(if (economicPanel) 64f else if (nativePanel) 96f else 120f)
        exitButton.setY(if (economicPanel) 16f else if (nativePanel) 48f else 72f)
        stopButton.isVisible = current != null
        patrolButton.isVisible = current != null && !touchControls
        patrolButton.isDisabled = current?.canPatrol != true
        patrolLabel.isVisible = current != null && !touchControls
        val taskText = selected?.tasks?.firstOrNull()?.let { head ->
                "Tasks ${selected.tasks.size}: ${head.kind.name.lowercase()}" +
                    (head.target?.let { String.format(Locale.ROOT, "\nPoint %.0f, %.0f", it.x, it.y) } ?: "")
            } ?: "Tasks 0"
        patrolLabel.setText(if (state.patrolMode) "Sent points ${state.patrolPoints}\n$taskText\nLeft: point / Esc: finish"
            else if (selected?.tasks?.isNotEmpty() == true) taskText else "P: set patrol points")
        // Factories cannot receive Patrol; keep this hint clear of the production controls.
        if (queue != null || upgrades.isNotEmpty() || menu?.construction?.isNotEmpty() == true) patrolLabel.isVisible = false
        selectedLabel.isVisible = current != null
        listOf<Actor>(contentLabel, rootField, modeBox, shareBox).forEach { it.isVisible = launch.canEdit }
        groupButtons.forEachIndexed { index, button -> button.setText("${index + 1} (${state.groups[index]})"); button.isDisabled = current == null }
        rootField.isDisabled = touchControls || !launch.canEdit
        modeBox.isDisabled = !launch.canEdit
        shareBox.isDisabled = !launch.canEdit
        deploymentBox.isVisible = launch.canEdit && !touchControls
        deploymentBox.isDisabled = loading || !launch.canEdit || touchControls
        startButton.setText(if (launch.canEdit) "Start" else "Reopen")
        startButton.isDisabled = loading || launch.canEdit && (visualState?.canStart == false || !packState.canStart)
        visualsTab.isVisible = current == null && visualPanel != null
        packTab.isVisible = current == null
        visualPanel?.let {
            it.isVisible = current == null && !showContentPack
            it.setEditable(launch.canEdit && !loading)
            it.setStatus(if (loading) state.preparationStatus ?: "Preparing scene..." else loadError)
        }
        packPanel?.let {
            it.isVisible = current == null && showContentPack
            it.setEditable(launch.canEdit && !loading)
            it.setStatus(if (loading) state.preparationStatus ?: "Preparing scene..." else loadError)
        }
        statusLabel.setText(when {
            state.reclaimMode -> "Select building to reclaim (Esc / right: cancel)"
            loading -> state.preparationStatus ?: "Preparing scene..."
            state.preparationStatus != null -> state.preparationStatus
            loadError != null -> if (visualRecoveryFailed) "Visual cache failed. Reopen or exit match to prepare again." else "Load failed"
            current == null -> "Ready"
            nativePanel -> current.playerFeedback
            else -> "${current.feedback}\nStep ${current.observation.stepIndex}"
        })
        messageLabel.isVisible = current == null && visualPanel == null
        messageLabel.setText(if (loading) "Loading" else loadError ?: "Ready")
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
        viewport.update(width, height, true)
        evidence.record("resize", mapOf("width" to width, "height" to height, "screenX" to viewport.screenX,
            "screenY" to viewport.screenY, "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight))
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
        if (touchControls) releasePresentation()
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
                graphics = session?.let { SandboxGraphics.load(activeVisuals?.let(it.renderResources::withVisuals) ?: it.renderResources, activeRoot) }
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
    }

    fun destroyPausedHost() {
        check(!presentationReady || disposed) { "Release presentation on the GL thread before Android host destruction" }
        dispose()
    }

    private fun releaseSounds() {
        val closing = sounds; sounds = null
        closing?.close()
    }

    private fun releasePresentation() {
        if (!presentationReady) return
        cancelInput()
        presentationReady = false
        Gdx.input.inputProcessor = null
        val closingGraphics = graphics; graphics = null
        listOf<() -> Unit>({ releaseSounds() }, { closingGraphics?.close() }, { visualPanel?.close() }, { packPanel?.close() }, { stage.dispose() }, { skin.dispose() },
            { shapes.dispose() }, { batch.dispose() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) {
                evidence.recordFailure("cleanup-error", failure)
                Gdx.app.error("p001-presentation", "Cleanup failed", failure)
            }
        }
        evidence.record("presentation-released", emptyMap())
    }

    private fun cancelInput() {
        constructionHover = null
        upgradeHover = null
        upgradeInputs.forEach { it.cancel() }
        reclaimHover = false
        if (::reclaimInput.isInitialized) reclaimInput.cancel()
        constructionCards.values.forEach { it.input.cancel() }
        productionHover = null
        productionCards.values.forEach { it.input.cancel() }
        if (::economicUpgradeInput.isInitialized) economicUpgradeInput.cancel()
        if (::nativeTooltip.isInitialized) nativeTooltip.isVisible = false
        if (::mapInput.isInitialized) mapInput.cancel()
        if (::stage.isInitialized) { stage.cancelTouchFocus(); stage.unfocusAll() }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Gdx.input.inputProcessor = null
        listOf<() -> Unit>({ leaveMatch() }, { loader.close() }, { visualState?.close() }, { packState.close() }, { releasePresentation() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) { Gdx.app.error("p001-dispose", "Cleanup failed", failure) }
        }
        evidence.record("application-disposed", emptyMap())
    }
}
