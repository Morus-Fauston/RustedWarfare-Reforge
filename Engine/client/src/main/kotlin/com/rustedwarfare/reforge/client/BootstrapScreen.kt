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
    private var loading = false
    private var generation = 0L
    private var activeRoot: Path = initialRoot
    private var accumulator = 0.0
    private var paused = false
    private var loadError: String? = null

    override fun create() {
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        skin = makeSkin()
        stage = Stage(viewport, batch)
        fun label(text: String, x: Float, y: Float, w: Float, h: Float): Label = Label(text, skin).also {
            it.setBounds(x, y - h, w, h); it.setAlignment(Align.topLeft); it.setWrap(true); stage.addActor(it)
        }
        label("RustedWarfare-Reforge", 24f, 598f, 600f, 30f).setFontScale(1.5f)
        label("P001 / Offline", 650f, 592f, 280f, 24f)
        label("p1  Local", 24f, 532f, 220f, 22f).color = Color(0.45f, 0.91f, 0.65f, 1f)
        label("p2  No controller", 290f, 532f, 300f, 22f).color = Color(1f, 0.72f, 0.3f, 1f)
        label("Selection", 650f, 532f, 280f, 24f)
        selectedLabel = label("None", 650f, 490f, 280f, 140f)
        statusLabel = label("", 24f, 76f, 600f, 56f)
        messageLabel = label("Loading", 40f, 400f, 560f, 260f)
        stopButton = button("Stop", 650f, 292f, 130f) { session?.stop(); updateHud() }
        button("Exit match", 800f, 292f, 130f) { leaveMatch() }
        label("Content folder", 650f, 270f, 280f, 24f)
        rootField = TextField(initialRoot.toAbsolutePath().normalize().toString(), skin).also {
            it.setOnlyFontChars(false)
            it.setBounds(650f, 206f, 280f, 34f); stage.addActor(it)
        }
        button("Reopen", 650f, 150f, 280f) { requestLoad() }
        Gdx.input.inputProcessor = InputMultiplexer(stage, object : InputAdapter() {
            override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
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
                if (button == Input.Buttons.LEFT) current.select(world) else current.move(world)
                updateHud()
                return true
            }
        })
        resize(Gdx.graphics.width, Gdx.graphics.height)
        requestLoad()
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
        return result
    }

    private fun button(text: String, x: Float, y: Float, width: Float, clicked: () -> Unit): TextButton =
        TextButton(text, skin).also { button ->
            button.setBounds(x, y, width, 40f)
            button.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) { clicked() } })
            stage.addActor(button)
        }

    private fun requestLoad() {
        leaveMatch()
        try {
            activeRoot = Paths.get(rootField.text).toAbsolutePath().normalize()
            generation++
            loading = true
            loadError = null
            evidence.record("load-request", mapOf("generation" to generation, "root" to activeRoot.toString()))
            loader.request(activeRoot)
        } catch (failure: Exception) { failLoad(failure) }
        updateHud()
    }

    private fun leaveMatch() {
        loader.cancel()
        loading = false
        loadError = null
        accumulator = 0.0
        val closingSession = session; session = null
        val closingGraphics = graphics; graphics = null
        listOf<() -> Unit>({ closingSession?.close() }, { closingGraphics?.close() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) {
                Gdx.app.error("p001-cleanup", "Cleanup failed", failure)
                try { evidence.record("cleanup-error", mapOf("message" to failure.toString())) }
                catch (diagnosticFailure: Exception) { Gdx.app.error("p001-cleanup", "Cannot record cleanup failure", diagnosticFailure) }
            }
        }
        if (::stage.isInitialized) { stage.unfocusAll(); updateHud() }
        evidence.record("match-exit", mapOf("generation" to generation))
    }

    private fun failLoad(failure: Throwable) {
        loading = false
        loadError = failure.message ?: failure.toString()
        evidence.record("load-error", mapOf("generation" to generation, "message" to loadError))
        Gdx.app.error("p001-load", loadError, failure)
        updateHud()
    }

    override fun render() {
        if (loading) loader.poll()?.fold(onSuccess = { scenario ->
            var pendingGraphics: SandboxGraphics? = null
            var pendingSession: SandboxSession? = null
            try {
                pendingGraphics = SandboxGraphics.load(scenario, activeRoot)
                val runGeneration = generation
                pendingSession = SandboxSession(scenario, ClientEvidence { kind, values ->
                    evidence.record(kind, values + ("generation" to runGeneration))
                })
                evidence.record("load-ready", mapOf("generation" to generation, "mapWidth" to scenario.map.widthTiles,
                    "mapHeight" to scenario.map.heightTiles, "blockedCount" to scenario.map.blocking.blockedCount))
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
            batch.begin(); graphics?.draw(batch, current.observation); batch.end()
            shapes.begin(ShapeRenderer.ShapeType.Line)
            graphics?.drawOverlay(shapes, current.observation, current.selectedId)
            shapes.end()
        }
        stage.act(Gdx.graphics.deltaTime); stage.draw()
    }

    private fun updateHud() {
        val current = session
        val selected = current?.observation?.units?.find { it.unitId == current.selectedId }
        selectedLabel.setText(if (selected == null) "None" else String.format(Locale.ROOT,
            "%s / %s\n%s\n\n%s\nx %.1f   y %.1f\nheading %.1f\nspeed %.2f",
            selected.unitId, selected.ownerPlayerId,
            if (selected.ownerPlayerId == "p1") "Local control" else "No controller",
            selected.movementState.name.lowercase(), selected.position.x, selected.position.y, selected.heading, selected.speed))
        stopButton.isDisabled = selected == null
        statusLabel.setText(when {
            loading -> "Loading"
            loadError != null -> "Load failed"
            current == null -> "Match closed"
            else -> "${current.feedback}\nStep ${current.observation.stepIndex}"
        })
        messageLabel.isVisible = current == null
        messageLabel.setText(if (loading) "Loading" else loadError ?: "Match closed")
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        evidence.record("resize", mapOf("width" to width, "height" to height, "screenX" to viewport.screenX,
            "screenY" to viewport.screenY, "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight))
    }
    override fun pause() { paused = true; accumulator = 0.0; evidence.record("pause", emptyMap()) }
    override fun resume() { paused = false; accumulator = 0.0; evidence.record("resume", emptyMap()) }

    override fun dispose() {
        Gdx.input.inputProcessor = null
        listOf<() -> Unit>({ leaveMatch() }, { loader.close() }, { if (::stage.isInitialized) stage.dispose() },
            { if (::skin.isInitialized) skin.dispose() }, { if (::shapes.isInitialized) shapes.dispose() },
            { if (::batch.isInitialized) batch.dispose() }).forEach { cleanup ->
            try { cleanup() } catch (failure: Exception) { Gdx.app.error("p001-dispose", "Cleanup failed", failure) }
        }
        evidence.record("application-disposed", emptyMap())
    }
}
