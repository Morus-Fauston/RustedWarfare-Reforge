package com.rustedwarfare.reforge.client

import com.badlogic.gdx.*
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener
import com.badlogic.gdx.utils.viewport.FitViewport
import com.rustedwarfare.reforge.client.demo.DemoScenario
import com.rustedwarfare.reforge.client.demo.DemoSession
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.core.sim.Scenario

/** Presentation-only host for the explicitly approved, self-owned Windows demonstration. */
class DemoScreen(private val evidence: ClientEvidence = ClientEvidence.NONE) : ApplicationAdapter() {
    private val viewport = FitViewport(1344f, 1000f)
    private lateinit var scenario: Scenario
    private lateinit var session: DemoSession
    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var skin: Skin
    private lateinit var stage: Stage
    private val textures = mutableListOf<Texture>()
    private val tiles = mutableMapOf<Int, TextureRegion>()
    private val unitImages = mutableMapOf<String, TextureRegion>()
    private lateinit var remembered: IntArray
    private lateinit var ground: IntArray
    private lateinit var status: Label
    private lateinit var feedback: Label
    private lateinit var pauseButton: TextButton
    private var paused = false
    private var accumulator = 0.0
    private var showPaths = true
    private var disposed = false
    private var generation = 0
    private var lastSample = -1L
    private var hudObservation: com.rustedwarfare.reforge.core.sim.Observation? = null
    private var hudSelection: Set<String>? = null
    private var hudFeedback: String? = null
    private var previousFrameStart = 0L
    private val frameSamples = mutableListOf<Map<String, Any?>>()
    private val mapX = 32f
    private val mapY = 100f

    override fun create() {
        try {
            batch = SpriteBatch()
            shapes = ShapeRenderer()
            skin = makeSkin()
            stage = Stage(viewport, batch)
            scenario = DemoScenario.load()
            ground = scenario.map.groundGids
            for (set in scenario.map.tileSets) {
                val image = texture(set.imagePath.toString())
                for (index in 0 until image.width / set.tileWidth) {
                    tiles[set.firstGid + index] = TextureRegion(image, index * set.tileWidth, 0, set.tileWidth, set.tileHeight)
                }
            }
            for ((id, definition) in scenario.unitDefinitions) {
                val image = texture(definition.imagePath.toString())
                unitImages[id] = TextureRegion(image, 0, 0, image.width / definition.totalFrames, image.height)
            }
            restart()
            label("RustedWarfare-Reforge", 32f, 944f, 650f, 30f).setFontScale(1.7f)
            label("50 UNITS  /  TERRAIN MEMORY     Left click: select    Right click: move    Advance: demonstration route", 34f, 915f, 1080f, 22f)
                .color = Color(0.56f, 0.73f, 0.68f, 1f)
            label("DEMO: CIRCLE 6 / NO OCCLUSION", 1000f, 946f, 315f, 26f).color = Color(1f, 0.74f, 0.4f, 1f)
            button("Select all 50", 32f, 48f, 150f) { session.selectAll(); record("select-all") }
            button("Advance", 194f, 48f, 140f) { session.moveSelected(Position(1140.0, 400.0)); record("advance-command") }
            button("Stop", 346f, 48f, 110f) { session.stopSelected(); record("stop-command") }
            pauseButton = button("Pause", 468f, 48f, 110f) {
                paused = !paused; accumulator = 0.0
                pauseButton.setText(if (paused) "Resume" else "Pause"); record("pause-command")
            }
            button("Restart", 590f, 48f, 110f) { restart(); pauseButton.setText("Pause"); record("restart-command") }
            val paths = CheckBox(" Paths", skin).also { it.isChecked = true }
            paths.setBounds(724f, 48f, 105f, 36f)
            paths.addListener(object : ChangeListener() {
                override fun changed(event: ChangeEvent, actor: Actor) { showPaths = paths.isChecked }
            })
            stage.addActor(paths)
            status = label("", 32f, 12f, 1000f, 26f)
            feedback = label("", 850f, 50f, 462f, 32f)
            Gdx.input.inputProcessor = InputMultiplexer(stage, object : InputAdapter() {
                override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || button !in listOf(Input.Buttons.LEFT, Input.Buttons.RIGHT)) return false
                    val bottom = Gdx.graphics.height - screenY
                    if (screenX !in viewport.screenX until viewport.screenX + viewport.screenWidth ||
                        bottom !in viewport.screenY until viewport.screenY + viewport.screenHeight) return false
                    val point = viewport.unproject(Vector2(screenX.toFloat(), screenY.toFloat()))
                    if (point.x < mapX || point.x >= mapX + 1280 || point.y < mapY || point.y >= mapY + 800) return false
                    val world = Position((point.x - mapX).toDouble(), (800 - point.y + mapY).toDouble())
                    if (button == Input.Buttons.LEFT) session.selectAt(world) else session.moveSelected(world)
                    evidence.record("pointer-command", mapOf("button" to button, "screenX" to screenX,
                        "screenY" to screenY, "world" to world.fields(), "generation" to generation))
                    record("command-observation")
                    return true
                }
            })
            resize(Gdx.graphics.width, Gdx.graphics.height)
            record("demo-ready")
        } catch (failure: Throwable) {
            evidence.recordFailure("demo-failed", failure)
            preserveFailure(failure) { dispose() }
            throw failure
        }
    }

    private fun texture(path: String): Texture = Texture(Gdx.files.absolute(path)).also {
        textures += it
        it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
    }

    private fun restart() {
        if (::session.isInitialized) session.close()
        session = DemoSession(scenario)
        remembered = IntArray(64 * 40)
        generation++
        paused = false
        accumulator = 0.0
        lastSample = -1
        updateMemory()
    }

    private fun updateMemory() {
        val knowledge = session.knowledge
        for (row in 0 until 40) for (column in 0 until 64) {
            if (knowledge.cell(column, row).visible) remembered[row * 64 + column] = ground[row * 64 + column]
        }
    }

    override fun render() {
        if (disposed) return
        val frameStart = if (evidence !== ClientEvidence.NONE) System.nanoTime() else 0L
        var steps = 0
        if (!paused) {
            accumulator += Gdx.graphics.deltaTime
            while (accumulator >= 1.0 / 60 && steps < 8) {
                session.tick(); updateMemory(); accumulator -= 1.0 / 60; steps++
            }
        }
        if (session.observation.stepIndex / 60 != lastSample) {
            lastSample = session.observation.stepIndex / 60
            record("periodic-observation")
        }
        Gdx.gl.glClearColor(0.065f, 0.075f, 0.075f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        viewport.apply()
        batch.projectionMatrix = viewport.camera.combined
        shapes.projectionMatrix = viewport.camera.combined
        val knowledge = session.knowledge
        batch.begin()
        for (row in 0 until 40) for (column in 0 until 64) {
            val gid = remembered[row * 64 + column]
            if (gid == 0) continue
            val tint = if (knowledge.cell(column, row).visible) 1f else 0.32f
            batch.setColor(tint, tint, tint, 1f)
            batch.draw(tiles.getValue(gid), mapX + column * 20, mapY + (39 - row) * 20, 20f, 20f)
        }
        batch.color = Color.WHITE
        for (unit in session.observation.units) {
            val image = unitImages.getValue(unit.typeId)
            val definition = scenario.unitDefinitions.getValue(unit.typeId)
            val width = image.regionWidth * definition.imageScale.toFloat()
            val height = image.regionHeight * definition.imageScale.toFloat()
            batch.draw(image, x(unit.position.x) - width / 2, y(unit.position.y) - height / 2,
                width / 2, height / 2, width, height, 1f, 1f, -unit.heading.toFloat())
        }
        batch.end()
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.color = Color(0.26f, 0.32f, 0.30f, 1f)
        shapes.rect(mapX, mapY, 1280f, 800f)
        val selectedIds = session.selectedIds
        for (unit in session.observation.units) {
            if (unit.unitId !in selectedIds) continue
            shapes.color = Color(0.46f, 0.95f, 0.67f, 0.9f)
            shapes.circle(x(unit.position.x), y(unit.position.y), 10f, 20)
            if (showPaths) {
                shapes.color = Color(0.49f, 0.78f, 0.78f, 0.55f)
                var previous = unit.position
                for (point in unit.pathPoints) {
                    shapes.line(x(previous.x), y(previous.y), x(point.x), y(point.y)); previous = point
                }
            }
        }
        shapes.color = Color(1f, 0.69f, 0.32f, 1f)
        val target = session.lastTarget ?: Position(1140.0, 400.0)
        shapes.circle(x(target.x), y(target.y), 12f, 24)
        shapes.line(x(target.x) - 18, y(target.y), x(target.x) + 18, y(target.y))
        shapes.line(x(target.x), y(target.y) - 18, x(target.x), y(target.y) + 18)
        shapes.end()
        if (hudObservation !== session.observation || hudSelection != selectedIds) {
            val explored = remembered.count { it != 0 } * 100 / remembered.size
            val moving = session.observation.units.count { it.speed > 0.0 }
            status.setText("${selectedIds.size}/50 selected     $moving moving     $explored% explored     ${session.replans} replans     Step ${session.observation.stepIndex}")
            hudObservation = session.observation
            hudSelection = selectedIds
        }
        val currentFeedback = if (paused) "Paused" else session.feedback
        if (hudFeedback != currentFeedback) { feedback.setText(currentFeedback); hudFeedback = currentFeedback }
        stage.act(Gdx.graphics.deltaTime); stage.draw()
        if (frameStart != 0L) {
            frameSamples += mapOf("generation" to generation, "step" to session.observation.stepIndex,
                "steps" to steps, "paused" to paused,
                "intervalNanos" to if (previousFrameStart == 0L) null else frameStart - previousFrameStart,
                "workNanos" to System.nanoTime() - frameStart)
            previousFrameStart = frameStart
            if (frameSamples.size >= 60) flushFrameSamples()
        }
    }

    private fun flushFrameSamples() {
        if (frameSamples.isEmpty()) return
        evidence.record("frame-timing", mapOf("samples" to frameSamples.toList(),
            "intervalScope" to "render-start-to-start-including-input-and-present",
            "workScope" to "render-body-excluding-present-and-evidence-flush"))
        frameSamples.clear()
    }

    private fun x(world: Double) = mapX + world.toFloat()
    private fun y(world: Double) = mapY + 800 - world.toFloat()

    private fun record(kind: String) {
        if (evidence === ClientEvidence.NONE) return
        val knowledge = session.knowledge
        evidence.record(kind, mapOf("generation" to generation, "paused" to paused,
            "selected" to session.selectedIds.toList(), "replans" to session.replans,
            "exploredCells" to remembered.count { it != 0 }, "visibleCells" to (0 until 40).sumOf { row ->
                (0 until 64).count { column -> knowledge.cell(column, row).visible }
            }, "observation" to session.observation.fields()))
    }

    private fun label(text: String, x: Float, y: Float, w: Float, h: Float) = Label(text, skin).also {
        it.setBounds(x, y, w, h); stage.addActor(it)
    }

    private fun button(text: String, x: Float, y: Float, w: Float, action: () -> Unit) = TextButton(text, skin).also {
        it.setBounds(x, y, w, 36f)
        it.addListener(object : ChangeListener() {
            override fun changed(event: ChangeEvent, actor: Actor) = action()
        })
        stage.addActor(it)
    }

    private fun makeSkin(): Skin = Skin().also { result ->
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE); pixmap.fill()
        val white = try { Texture(pixmap) } finally { pixmap.dispose() }
        result.add("white", white)
        val font = BitmapFont(); result.add("default-font", font)
        val text = Color(0.9f, 0.93f, 0.91f, 1f)
        result.add("default", Label.LabelStyle(font, text))
        result.add("default", TextButton.TextButtonStyle().apply {
            this.font = font; fontColor = text
            up = result.newDrawable("white", Color(0.19f, 0.24f, 0.23f, 1f))
            over = result.newDrawable("white", Color(0.28f, 0.36f, 0.32f, 1f))
            down = result.newDrawable("white", Color(0.36f, 0.46f, 0.39f, 1f))
        })
        result.add("default", CheckBox.CheckBoxStyle().apply {
            this.font = font; fontColor = text
            checkboxOff = result.newDrawable("white", Color(0.26f, 0.3f, 0.29f, 1f)).apply { minWidth = 16f; minHeight = 16f }
            checkboxOn = result.newDrawable("white", Color(0.46f, 0.95f, 0.67f, 1f)).apply { minWidth = 16f; minHeight = 16f }
        })
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        evidence.record("resize", mapOf("width" to width, "height" to height,
            "screenX" to viewport.screenX, "screenY" to viewport.screenY,
            "screenWidth" to viewport.screenWidth, "screenHeight" to viewport.screenHeight))
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        Gdx.input.inputProcessor = null
        val cleanup = mutableListOf<() -> Unit>()
        cleanup += { flushFrameSamples() }
        if (::session.isInitialized) cleanup += { session.close() }
        if (::stage.isInitialized) cleanup += { stage.dispose() }
        textures.forEach { texture -> cleanup += { texture.dispose() } }
        if (::skin.isInitialized) cleanup += { skin.dispose() }
        if (::shapes.isInitialized) cleanup += { shapes.dispose() }
        if (::batch.isInitialized) cleanup += { batch.dispose() }
        cleanup.forEach { close ->
            try { close() } catch (failure: Exception) {
                // A failing diagnostic sink must not prevent the remaining resources closing.
                evidence.recordFailure("cleanup-error", failure)
                Gdx.app.error("demo-cleanup", "Cleanup failed", failure)
            }
        }
        evidence.record("demo-disposed", emptyMap())
    }
}
