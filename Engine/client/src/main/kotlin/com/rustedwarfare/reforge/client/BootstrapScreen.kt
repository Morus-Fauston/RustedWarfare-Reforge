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
import com.badlogic.gdx.scenes.scene2d.Actor
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
) : ApplicationAdapter() {
    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var skin: Skin
    private lateinit var stage: Stage
    private val viewport = FitViewport(960f, 640f)
    private val loader = SandboxLoader()
    private var session: SandboxSession? = null
    private var graphics: SandboxGraphics? = null
    private lateinit var rootField: TextField
    private lateinit var selectedLabel: Label
    private lateinit var statusLabel: Label
    private lateinit var messageLabel: Label
    private lateinit var stopButton: TextButton
    private lateinit var productionLabel: Label
    private lateinit var produceButton: TextButton
    private lateinit var cancelProductionButton: TextButton
    private lateinit var modeBox: SelectBox<String>
    private lateinit var shareBox: CheckBox
    private lateinit var startButton: TextButton
    private val launch = SandboxLaunchState(SandboxConfiguration(initialRoot.toAbsolutePath().normalize(), SandboxOptions()))
    private var pendingLoad: SandboxLoad? = null
    private var loading = false
    private var generation = 0L
    private var activeRoot: Path = initialRoot
    private var accumulator = 0.0
    private var paused = false
    private var loadError: String? = null
    private var presentationReady = false
    private var disposed = false
    private data class HudState(val selected: com.rustedwarfare.reforge.core.sim.UnitObservation?,
        val selectedId: String?, val feedback: String?, val step: Long?, val canEdit: Boolean,
        val loading: Boolean, val error: String?, val hasSession: Boolean,
        val balance: Long?, val queue: com.rustedwarfare.reforge.core.sim.FactoryQueueObservation?)
    private var lastHudState: HudState? = null

    override fun create() {
        createPresentation()
        updateHud()
    }

    private fun createPresentation() {
        lastHudState = null
        val created = mutableListOf<() -> Unit>()
        try {
        batch = SpriteBatch().also { created += { it.dispose() } }
        shapes = ShapeRenderer().also { created += { it.dispose() } }
        skin = makeSkin().also { created += { it.dispose() } }
        stage = Stage(viewport, batch).also { created += { it.dispose() } }
        fun label(text: String, x: Float, y: Float, w: Float, h: Float): Label = Label(text, skin).also {
            it.setBounds(x, y - h, w, h); it.setAlignment(Align.topLeft); it.setWrap(true); stage.addActor(it)
        }
        label("RustedWarfare-Reforge", 24f, 598f, 600f, 30f).setFontScale(1.5f)
        label("Offline sandbox", 650f, 592f, 280f, 24f)
        label("p1  Local", 24f, 532f, 220f, 22f).color = Color(0.45f, 0.91f, 0.65f, 1f)
        label("p2  No controller", 290f, 532f, 300f, 22f).color = Color(1f, 0.72f, 0.3f, 1f)
        label("Selection", 650f, 532f, 280f, 24f)
        selectedLabel = label("None", 650f, 490f, 280f, 96f)
        productionLabel = label("", 650f, 394f, 280f, 18f)
        produceButton = button("Produce", 650f, 336f, 130f) { session?.produce(); updateHud() }
        cancelProductionButton = button("Cancel last", 800f, 336f, 130f) { session?.cancelProduction(); updateHud() }
        statusLabel = label("", 24f, 76f, 600f, 56f)
        messageLabel = label("Loading", 40f, 400f, 560f, 260f)
        stopButton = button("Stop", 650f, 292f, 130f) { session?.stop(); updateHud() }
        button("Exit match", 800f, 292f, 130f) { leaveMatch() }
        label("Content folder", 650f, 270f, 280f, 24f)
        rootField = TextField(launch.configuration.root.toString(), skin).also {
            it.setOnlyFontChars(false)
            it.setBounds(650f, 206f, 280f, 34f); stage.addActor(it)
            it.isDisabled = touchControls
        }
        modeBox = SelectBox<String>(skin).also {
            it.setItems("NoFog", "Memory", "Hardcore")
            it.selectedIndex = launch.configuration.options.mode.ordinal
            it.setBounds(650f, 156f, 280f, 40f)
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
            it.setBounds(650f, 108f, 280f, 40f)
            it.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) {
                    if (launch.canEdit) launch.configure(launch.configuration.copy(options =
                        launch.configuration.options.copy(shareAlliedVision = shareBox.isChecked)))
                }
            })
            stage.addActor(it)
        }
        startButton = button("Start", 650f, 60f, 280f) { requestLoad() }
        Gdx.input.inputProcessor = InputMultiplexer(stage, object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (!touchControls || keycode != Input.Keys.BACK) return false
                leaveMatch()
                Gdx.app.exit()
                return true
            }
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                if (paused) return false
                if (pointer != 0 || button !in listOf(Input.Buttons.LEFT, Input.Buttons.RIGHT)) return false
                val current = session ?: return false
                val map = graphics?.projection ?: return false
                val bottomY = Gdx.graphics.height - screenY
                if (screenX < viewport.screenX || screenX >= viewport.screenX + viewport.screenWidth ||
                    bottomY < viewport.screenY || bottomY >= viewport.screenY + viewport.screenHeight) return false
                val ui = viewport.unproject(Vector2(screenX.toFloat(), screenY.toFloat()))
                val world = map.toWorld(ui.x.toDouble(), ui.y.toDouble()) ?: return false
                evidence.record("pointer", mapOf("generation" to generation, "screenX" to screenX,
                    "screenY" to screenY, "windowWidth" to Gdx.graphics.width, "windowHeight" to Gdx.graphics.height,
                    "uiX" to ui.x, "uiY" to ui.y, "world" to world.fields(), "button" to button))
                stage.unfocusAll()
                if (touchControls) current.tap(world)
                else if (button == Input.Buttons.LEFT) current.select(world) else current.command(world)
                updateHud()
                return true
            }
        })
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
        try {
            val request = if (launch.canEdit) {
                launch.configure(launch.configuration.copy(root = Paths.get(rootField.text).toAbsolutePath().normalize()))
                launch.start()
            } else launch.reopen()
            closeMatchResources()
            pendingLoad = request
            generation = request.generation
            activeRoot = request.configuration.root
            loading = true
            loadError = null
            evidence.record("load-request", mapOf("generation" to generation, "root" to activeRoot.toString(),
                "mode" to request.configuration.options.mode.name,
                "shareAlliedVision" to request.configuration.options.shareAlliedVision))
            loader.request(activeRoot)
        } catch (failure: Exception) { failLoad(failure) }
        updateHud()
    }

    private fun leaveMatch() {
        closeMatchResources()
        launch.exit()
        if (presentationReady) { stage.unfocusAll(); updateHud() }
        evidence.record("match-exit", mapOf("generation" to generation))
    }

    private fun closeMatchResources() {
        loader.cancel()
        pendingLoad = null
        loading = false
        loadError = null
        accumulator = 0.0
        val closingSession = session; session = null
        val closingGraphics = graphics; graphics = null
        listOf<() -> Unit>({ closingSession?.close() }, { closingGraphics?.close() }).forEach { cleanup ->
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
        if (loading) loader.poll()?.fold(onSuccess = { scenario ->
            val request = pendingLoad ?: return@fold
            var pendingGraphics: SandboxGraphics? = null
            var pendingSession: SandboxSession? = null
            try {
                pendingGraphics = SandboxGraphics.load(scenario, activeRoot)
                val runGeneration = generation
                pendingSession = SandboxSession(scenario, if (evidence === ClientEvidence.NONE) evidence else ClientEvidence { kind, values ->
                    evidence.record(kind, values + ("generation" to runGeneration))
                }, request.configuration.options)
                evidence.record("load-ready", mapOf("generation" to generation, "mapWidth" to scenario.map.widthTiles,
                    "mapHeight" to scenario.map.heightTiles, "blockedCount" to scenario.map.blocking.blockedCount))
                check(launch.complete(request.generation)) { "Cancelled load generation" }
                pendingLoad = null
                graphics = pendingGraphics
                session = pendingSession
                loading = false
                accumulator = 0.0
            } catch (failure: Exception) {
                try { pendingSession?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                try { pendingGraphics?.close() } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                failLoad(failure)
            }
        }, onFailure = { failLoad(it) })
        val current = session
        if (current != null && !paused) {
            // Presentation pacing only, not the original game's tick frequency.
            accumulator += Gdx.graphics.deltaTime.toDouble()
            var steps = 0
            while (accumulator >= 1.0 / 60 && steps < 8) {
                current.tick(); accumulator -= 1.0 / 60; steps++
            }
        }
        updateHud()
        Gdx.gl.glClearColor(0.075f, 0.09f, 0.085f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0.105f, 0.13f, 0.12f, 1f)
        shapes.rect(638f, 0f, 322f, 560f)
        shapes.end()
        if (current != null) {
            batch.begin(); graphics?.draw(batch, current.frame); batch.end()
            shapes.begin(ShapeRenderer.ShapeType.Line)
            graphics?.drawOverlay(shapes, current.frame, current.selectedId)
            shapes.end()
        }
        stage.act(Gdx.graphics.deltaTime); stage.draw()
    }

    private fun updateHud() {
        if (!presentationReady) return
        val current = session
        val selected = current?.observation?.units?.find { it.unitId == current.selectedId }
        val state = HudState(selected, current?.selectedId, current?.feedback, current?.observation?.stepIndex,
            launch.canEdit, loading, loadError, current != null, current?.observation?.balance, current?.selectedFactoryQueue)
        if (state == lastHudState) return
        lastHudState = state
        selectedLabel.setText(if (selected == null) "None" else String.format(Locale.ROOT,
            "%s / %s\n%s / %s\nx %.1f   y %.1f\nheading %.1f  speed %.2f",
            if (selected.unitId.startsWith("produced:")) selected.typeId else selected.unitId, selected.ownerPlayerId,
            if (selected.ownerPlayerId == "p1") "Local control" else "No controller",
            selected.movementState.name.lowercase(), selected.position.x, selected.position.y, selected.heading, selected.speed) +
            (selected.health?.let { "\nHP ${it.toInt()} / ${selected.maxHealth?.toInt()}" } ?: ""))
        val queue = current?.selectedFactoryQueue
        productionLabel.setText(state.balance?.let { balance ->
            "Funds $balance" + (queue?.let { "  Queue ${it.items.size}" +
                (it.items.firstOrNull()?.let { head -> "  ${(head.progress * 100f).toInt()}%" } ?: "") } ?: "")
        } ?: "")
        produceButton.isVisible = state.balance != null
        cancelProductionButton.isVisible = state.balance != null
        produceButton.isDisabled = current?.productionPrice == null
        produceButton.setText(current?.productionPrice?.let { "Produce $it" } ?: "Produce")
        cancelProductionButton.isDisabled = queue?.items?.isEmpty() != false
        stopButton.isDisabled = selected == null
        rootField.isDisabled = touchControls || !launch.canEdit
        modeBox.isDisabled = !launch.canEdit
        shareBox.isDisabled = !launch.canEdit
        startButton.setText(if (launch.canEdit) "Start" else "Reopen")
        statusLabel.setText(when {
            loading -> "Loading"
            loadError != null -> "Load failed"
            current == null -> "Ready"
            else -> "${current.feedback}\nStep ${current.observation.stepIndex}"
        })
        messageLabel.isVisible = current == null
        messageLabel.setText(if (loading) "Loading" else loadError ?: "Ready")
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        evidence.record("resize", mapOf("width" to width, "height" to height, "screenX" to viewport.screenX,
            "screenY" to viewport.screenY, "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight))
    }
    override fun pause() {
        if (disposed) return
        paused = true
        accumulator = 0.0
        stage.cancelTouchFocus(); stage.unfocusAll()
        // Release GL and native buffers on the GL thread before Android suspends it.
        // A later non-finishing Activity destroy can then close the host without GL calls.
        if (touchControls) releasePresentation()
        evidence.record("pause", emptyMap())
    }

    override fun resume() {
        if (disposed) return
        if (touchControls && !presentationReady) {
            try {
                createPresentation()
                graphics = session?.let { SandboxGraphics.load(it.renderResources, activeRoot) }
                updateHud()
            } catch (failure: Exception) {
                preserveFailure(failure) { leaveMatch() }
                preserveFailure(failure) { releasePresentation() }
                preserveFailure(failure) { failLoad(failure) }
                throw failure
            }
        }
        paused = false
        accumulator = 0.0
        evidence.record("resume", emptyMap())
    }

    fun destroyPausedHost() {
        check(!presentationReady || disposed) { "Release presentation on the GL thread before Android host destruction" }
        dispose()
    }

    private fun releasePresentation() {
        if (!presentationReady) return
        presentationReady = false
        Gdx.input.inputProcessor = null
        val closingGraphics = graphics; graphics = null
        listOf<() -> Unit>({ closingGraphics?.close() }, { stage.dispose() }, { skin.dispose() },
            { shapes.dispose() }, { batch.dispose() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) {
                evidence.recordFailure("cleanup-error", failure)
                Gdx.app.error("p001-presentation", "Cleanup failed", failure)
            }
        }
        evidence.record("presentation-released", emptyMap())
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Gdx.input.inputProcessor = null
        listOf<() -> Unit>({ leaveMatch() }, { loader.close() }, { releasePresentation() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) { Gdx.app.error("p001-dispose", "Cleanup failed", failure) }
        }
        evidence.record("application-disposed", emptyMap())
    }
}
