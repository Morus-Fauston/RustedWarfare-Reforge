package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.ClientEvidence
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

/** Input and pixel capture run on the same render thread used by the actual client. */
internal class BackgroundClientInput(
    private val client: ApplicationListener,
    private val directory: Path,
    private val runId: String,
    private val evidence: ClientEvidence,
    private val currentGeneration: () -> Long,
) : ApplicationListener by client {
    private val bridge = directory.resolve("background-input")
    private val reader = JsonReader()
    private var lastRequest = 0L
    private var renderedFrames = 0L
    private var closing = false

    override fun create() {
        Files.createDirectory(bridge)
        client.create()
        write("ready.json", displayIdentity())
    }

    override fun render() {
        client.render()
        renderedFrames++
        if (closing) return
        val id = lastRequest + 1
        val request = bridge.resolve("request-$id.json")
        if (!Files.isRegularFile(request, NOFOLLOW_LINKS)) return
        lastRequest = id
        val response = try {
            require(Files.size(request) <= 4096) { "Request too large" }
            val message = reader.parse(Files.readString(request))
            require(message.getString("runId") == runId && message.getLong("id") == id) { "Stale input session" }
            val details = when (message.getString("action")) {
                "status" -> emptyMap()
                "click" -> {
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    val x = message.getInt("x")
                    val y = message.getInt("y")
                    require(x in 0 until Gdx.graphics.width && y in 0 until Gdx.graphics.height) {
                        "Click is outside the current client viewport"
                    }
                    val button = when (message.getString("button")) {
                        "left" -> Input.Buttons.LEFT
                        "right" -> Input.Buttons.RIGHT
                        else -> error("Unknown button")
                    }
                    val processor = checkNotNull(Gdx.input.inputProcessor) { "Client is not accepting input" }
                    val down = processor.touchDown(x, y, 0, button)
                    val up = processor.touchUp(x, y, 0, button)
                    mapOf("input" to mapOf("x" to x, "y" to y, "button" to message.getString("button"),
                        "downConsumed" to down, "upConsumed" to up))
                }
                "screenshot" -> {
                    val name = "frame-$id.png"
                    val pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
                    try {
                        PixmapIO.PNG().usePng { png -> png.write(FileHandle(bridge.resolve(name).toFile()), pixels) }
                    } finally {
                        pixels.dispose()
                    }
                    mapOf("image" to name)
                }
                "close" -> {
                    closing = true
                    emptyMap()
                }
                else -> error("Unknown background action")
            }
            displayIdentity() + details + mapOf("ok" to true, "id" to id, "action" to message.getString("action"))
        } catch (error: Exception) {
            displayIdentity() + mapOf("ok" to false, "id" to id, "message" to error.message)
        }
        evidence.record("background-input", response)
        write("response-$id.json", response)
        if (closing) Gdx.app.exit()
    }

    private fun displayIdentity() = mapOf(
        "runId" to runId, "pid" to ProcessHandle.current().pid(), "frame" to renderedFrames,
        "generation" to currentGeneration(),
        "width" to Gdx.graphics.width, "height" to Gdx.graphics.height,
        "framebufferWidth" to Gdx.graphics.backBufferWidth, "framebufferHeight" to Gdx.graphics.backBufferHeight,
    )

    private fun write(name: String, values: Map<String, Any?>) {
        val temporary = bridge.resolve("$name.tmp")
        Files.writeString(temporary, evidenceJson(values), CREATE_NEW)
        Files.move(temporary, bridge.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

private inline fun PixmapIO.PNG.usePng(action: (PixmapIO.PNG) -> Unit) {
    try {
        action(this)
    } finally {
        dispose()
    }
}
