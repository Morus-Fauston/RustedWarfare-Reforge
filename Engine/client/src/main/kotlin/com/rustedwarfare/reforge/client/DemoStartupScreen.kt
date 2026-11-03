package com.rustedwarfare.reforge.client

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.FitViewport
import com.rustedwarfare.reforge.client.demo.DemoScenario
import java.nio.file.Path

/** The demo keeps its original 50-unit session behind the same pre-match visual settings. */
class DemoStartupScreen(private val evidence: ClientEvidence, private val settings: VisualAssetSettings) : ApplicationAdapter() {
    private val state = VisualAssetSettingsSession(settings)
    private val loader = SandboxLoader { DemoScenario.load() }
    private val viewport = FitViewport(960f, 640f)
    private lateinit var stage: Stage
    private lateinit var batch: SpriteBatch
    private lateinit var skin: Skin
    private lateinit var panel: VisualAssetSettingsPanel
    private lateinit var start: TextButton
    private lateinit var cancel: TextButton
    private var game: DemoScreen? = null
    private var loading = false
    private var error: String? = null
    private var exitRequested = false
    private var restartRequested = false
    private var lastGeneration = 0
    private var disposed = false

    override fun create() {
        try {
            batch = SpriteBatch(); skin = makeSkin(); stage = Stage(viewport, batch)
            stage.addActor(Label("RustedWarfare-Reforge / Field Demo", skin).apply {
                setBounds(150f, 589f, 700f, 32f); setFontScale(1.25f)
            })
            panel = VisualAssetSettingsPanel(state, skin, settings::createUiFont) {
                evidence.recordVisualAssetMenu(state); error = null; refresh()
            }.also {
                it.setBounds(150f, 135f, 660f, 440f); stage.addActor(it)
            }
            fun button(text: String, x: Float, action: () -> Unit) = TextButton(text, skin).also {
                it.setBounds(x, 70f, 220f, 40f)
                it.addListener(object : ChangeListener() { override fun changed(event: ChangeEvent, actor: Actor) { action() } })
                stage.addActor(it)
            }
            start = button("Start demo", 150f, ::startMatch)
            cancel = button("Cancel loading", 390f) {
                loader.cancel(); loading = false; error = "Loading cancelled; no scene started."; refresh()
            }
            Gdx.input.inputProcessor = stage
            resize(Gdx.graphics.width, Gdx.graphics.height); refresh()
        } catch (failure: Exception) { preserveFailure(failure) { dispose() }; throw failure }
    }

    private fun startMatch() {
        if (!state.canStart || loading) return
        val selection = state.selection
        loading = true; error = null
        loader.request(Path.of(".")) { settings.prepare(selection) }
        evidence.record("demo-load-request", mapOf("source" to selection.source.value, "temporary" to selection.temporary))
        refresh()
    }

    private fun returnToStartup() {
        val closing = game
        game = null
        if (closing != null) { lastGeneration = closing.currentGeneration; closing.dispose() }
        Gdx.input.inputProcessor = stage
        loading = false; error = null; refresh()
    }

    private fun refresh() {
        if (!::start.isInitialized) return
        start.isDisabled = loading || !state.canStart
        cancel.isVisible = loading
        panel.setEditable(!loading)
        panel.setStatus(if (loading) "Loading demo and validating the selected visuals..." else error)
    }

    override fun render() {
        if (disposed) return
        if (exitRequested || restartRequested) {
            val reopen = restartRequested
            exitRequested = false; restartRequested = false
            returnToStartup()
            if (reopen) startMatch()
        }
        if (loading) loader.pollPrepared()?.fold(onSuccess = { loaded ->
            val candidate = DemoScreen(evidence, loaded.visuals, { exitRequested = true }, { restartRequested = true }, lastGeneration)
            try {
                candidate.create(); candidate.resize(Gdx.graphics.width, Gdx.graphics.height)
                game = candidate; loading = false
            } catch (failure: Exception) {
                preserveFailure(failure) { candidate.dispose() }
                error = failure.message ?: failure.toString(); loading = false
                Gdx.input.inputProcessor = stage; refresh()
                evidence.recordFailure("demo-load-error", failure)
            }
        }, onFailure = { failure ->
            error = failure.message ?: failure.toString(); loading = false; refresh()
            evidence.recordFailure("demo-load-error", failure)
        })
        val current = game
        if (current != null) { current.render(); return }
        Gdx.gl.glClearColor(.065f, .075f, .075f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply(); stage.act(Gdx.graphics.deltaTime); stage.draw()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        game?.resize(width, height)
        evidence.record("demo-startup-resize", mapOf("width" to width, "height" to height))
    }
    override fun pause() { game?.pause() }
    override fun resume() { game?.resume() }

    private fun makeSkin() = Skin().also { result ->
        val pixels = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixels.setColor(Color.WHITE); pixels.fill()
        val white = try { Texture(pixels) } finally { pixels.dispose() }
        result.add("white", white)
        val font = BitmapFont(); result.add("default-font", font)
        val text = Color(.9f, .93f, .91f, 1f)
        result.add("default", Label.LabelStyle(font, text))
        result.add("default", TextButton.TextButtonStyle().apply {
            this.font = font; fontColor = text; disabledFontColor = Color(.5f, .55f, .5f, 1f)
            up = result.newDrawable("white", Color(.19f, .24f, .23f, 1f))
            down = result.newDrawable("white", Color(.36f, .46f, .39f, 1f))
            over = result.newDrawable("white", Color(.28f, .36f, .32f, 1f))
        })
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        val closing = game; game = null
        for (close in listOf<() -> Unit>({ loader.close() }, { state.close() }, { closing?.dispose() },
            { if (::panel.isInitialized) panel.close() }, { if (::stage.isInitialized) stage.dispose() }, { if (::skin.isInitialized) skin.dispose() },
            { if (::batch.isInitialized) batch.dispose() })) try { close() }
            catch (failure: Exception) { evidence.recordFailure("cleanup-error", failure) }
        Gdx.input.inputProcessor = null
    }
}
