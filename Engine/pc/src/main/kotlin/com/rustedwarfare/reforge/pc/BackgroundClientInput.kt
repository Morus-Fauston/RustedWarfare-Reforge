package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.core.sim.SandboxCommand
import com.rustedwarfare.reforge.core.sim.SandboxCommandReceipt
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
    private val deployProjectiles: (String, SandboxCommand.ProjectileBatch) -> SandboxCommandReceipt,
    private val deploymentReceipt: (Long) -> SandboxCommandReceipt?,
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
            require(Files.size(request) <= 64 * 1024) { "Request too large" }
            val message = reader.parse(Files.readString(request))
            require(message.getString("runId") == runId && message.getLong("id") == id) { "Stale input session" }
            val details = when (message.getString("action")) {
                "status" -> emptyMap()
                "deploy_projectiles", "deployment_receipt" -> {
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    val receipt = if (message.getString("action") == "deploy_projectiles") {
                        val entries = message.get("entries")
                        require(entries.isArray && entries.size in 1..32) { "Expected 1 to 32 projectile entries" }
                        val requestId = message.get("requestId")
                        require(requestId != null && requestId.isString &&
                            requestId.asString().matches(Regex("[A-Za-z0-9._:-]{1,64}"))) { "Expected a deployment request ID" }
                        deployProjectiles(requestId.asString(), SandboxCommand.ProjectileBatch(entries.map(::deployment)))
                    } else {
                        val sequence = message.get("sequence")
                        require(sequence != null && sequence.isLong && sequence.asLong() > 0) { "Expected sequence" }
                        checkNotNull(deploymentReceipt(sequence.asLong())) { "Unknown deployment sequence" }
                    }
                    mapOf("deployment" to mapOf("sequence" to receipt.sequence, "generation" to receipt.generation,
                        "worldId" to receipt.worldId, "stepIndex" to receipt.stepIndex, "status" to receipt.status.name,
                        "code" to receipt.code, "failedEntry" to receipt.failedEntry, "projectileIds" to receipt.projectileIds))
                }
                "click", "scroll" -> {
                    val action = message.getString("action")
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    val x = message.getInt("x")
                    val y = message.getInt("y")
                    require(x in 0 until Gdx.graphics.width && y in 0 until Gdx.graphics.height) {
                        "Input is outside the current client viewport"
                    }
                    val processor = checkNotNull(Gdx.input.inputProcessor) { "Client is not accepting input" }
                    val input = if (action == "scroll") {
                        val amount = message.get("amount")
                        require(amount.isLong && amount.asLong() in -20L..20L && amount.asLong() != 0L) {
                            "Expected a nonzero scroll amount between -20 and 20"
                        }
                        processor.mouseMoved(x, y)
                        mapOf("amount" to amount.asInt(), "consumed" to processor.scrolled(0f, amount.asFloat()))
                    } else {
                        val name = message.getString("button")
                        val button = when (name) {
                            "left" -> Input.Buttons.LEFT
                            "right" -> Input.Buttons.RIGHT
                            else -> error("Unknown button")
                        }
                        val down = processor.touchDown(x, y, 0, button)
                        val up = processor.touchUp(x, y, 0, button)
                        mapOf("button" to name, "downConsumed" to down, "upConsumed" to up)
                    }
                    mapOf("input" to (mapOf("x" to x, "y" to y) + input))
                }
                "drag" -> {
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    fun coord(name: String, limit: Int): Int {
                        val node = message.get(name)
                        require(node != null && node.isLong) { "Expected integer coordinate $name" }
                        val value = node.asLong()
                        require(value in 0L until limit.toLong()) { "Input $name is outside the current client viewport" }
                        return value.toInt()
                    }
                    val x1 = coord("x1", Gdx.graphics.width)
                    val y1 = coord("y1", Gdx.graphics.height)
                    val x2 = coord("x2", Gdx.graphics.width)
                    val y2 = coord("y2", Gdx.graphics.height)
                    val buttonNode = message.get("button")
                    val name = when {
                        buttonNode == null -> "left"
                        buttonNode.isString -> buttonNode.asString()
                        else -> throw IllegalArgumentException("Expected string button")
                    }
                    val button = when (name) {
                        "left" -> Input.Buttons.LEFT
                        "right" -> Input.Buttons.RIGHT
                        else -> throw IllegalArgumentException("Unknown button: $name")
                    }
                    val processor = checkNotNull(Gdx.input.inputProcessor) { "Client is not accepting input" }
                    val down = processor.touchDown(x1, y1, 0, button)
                    val midX = (x1 + x2) / 2
                    val midY = (y1 + y2) / 2
                    val drag1 = processor.touchDragged(midX, midY, 0)
                    val drag2 = processor.touchDragged(x2, y2, 0)
                    val up = processor.touchUp(x2, y2, 0, button)
                    mapOf("input" to mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "button" to name,
                        "downConsumed" to down, "dragConsumed" to (drag1 || drag2), "upConsumed" to up))
                }
                "key" -> {
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    val keyNode = message.get("keycode")
                    require(keyNode != null && keyNode.isLong) { "Expected integer keycode" }
                    val rawKey = keyNode.asLong()
                    require(rawKey in 0L..Input.Keys.MAX_KEYCODE.toLong()) {
                        "Keycode must be between 0 and ${Input.Keys.MAX_KEYCODE}"
                    }
                    val keycode = rawKey.toInt()
                    val processor = checkNotNull(Gdx.input.inputProcessor) { "Client is not accepting input" }
                    val down = processor.keyDown(keycode)
                    val up = processor.keyUp(keycode)
                    mapOf("input" to mapOf("keycode" to keycode, "downConsumed" to down, "upConsumed" to up))
                }
                "type" -> {
                    require(message.getLong("generation") == currentGeneration()) { "Stale client generation" }
                    val text = message.getString("text")
                    require(text.length in 1..512 && text.none { it.isISOControl() && it != '\b' }) { "Expected 1 to 512 typed characters" }
                    val processor = checkNotNull(Gdx.input.inputProcessor) { "Client is not accepting input" }
                    var consumed = 0
                    for (character in text) if (processor.keyTyped(character)) consumed++
                    mapOf("input" to mapOf("characters" to text.length, "consumed" to consumed))
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

    private fun deployment(entry: JsonValue): SandboxCommand.ProjectileDeployment {
        val required = setOf("alias", "sourceUnitId", "targetUnitId")
        require(entry.isObject && entry.size in 3..4 && entry.map { it.name }.toSet() ==
            (required + if (entry.has("weapon")) setOf("weapon") else emptySet())) { "Unexpected projectile fields" }
        fun text(key: String): String {
            val value = entry.get(key)
            require(value != null && value.isString) { "Expected projectile field $key" }
            return value.asString().also { require(it.length in 1..96 && it.none(Char::isISOControl)) {
                "Invalid projectile field $key"
            } }
        }
        return SandboxCommand.ProjectileDeployment(text("alias"), text("sourceUnitId"), text("targetUnitId"),
            if (entry.has("weapon")) text("weapon") else null)
    }

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
