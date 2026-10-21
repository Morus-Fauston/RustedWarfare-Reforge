package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.ApplicationListener
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputProcessor
import com.rustedwarfare.reforge.client.ClientEvidence

/** Windows-only adapter. No changes to shared client or pacing; timestamps use one JVM clock. */
class WindowPerfSampling(private val application: ApplicationListener, private val evidence: ClientEvidence) : ApplicationListener by application {
    private val frames = mutableListOf<Map<String, Any?>>()
    private val inputs = mutableListOf<Map<String, Any?>>()
    private var previousStart: Long? = null
    private var frame = 0L
    private var inputId = 0L
    override fun create() {
        application.create()
        val processor = Gdx.input.inputProcessor ?: return
        Gdx.input.inputProcessor = object : InputProcessor by processor {
            override fun touchDown(x: Int, y: Int, pointer: Int, button: Int) = input("down", x, y) { processor.touchDown(x, y, pointer, button) }
            override fun touchUp(x: Int, y: Int, pointer: Int, button: Int) = input("up", x, y) { processor.touchUp(x, y, pointer, button) }
            override fun keyDown(keycode: Int) = input("key-down", keycode, 0) { processor.keyDown(keycode) }
        }
        evidence.record("perf-sampling-boundary", mapOf("pid" to ProcessHandle.current().pid(),
            "clock" to "System.nanoTime in this JVM", "frameScope" to "render-start intervals include backend input/present/pacing; work excludes present and sample flush",
            "inputScope" to "native callback dispatch and first subsequent render return; no OS-injection-to-photon/GPU fence guarantee"))
    }
    private fun input(kind: String, x: Int, y: Int, action: () -> Boolean): Boolean {
        val start = System.nanoTime()
        val consumed = action()
        inputs += mapOf("id" to inputId++, "type" to kind, "x" to x, "y" to y,
            "dispatchStartNanos" to start, "dispatchReturnNanos" to System.nanoTime(), "consumed" to consumed)
        return consumed
    }
    override fun render() {
        val start = System.nanoTime()
        application.render()
        val end = System.nanoTime()
        frames += mapOf("frame" to frame++, "startNanos" to start,
            "intervalNanos" to previousStart?.let { start - it }, "workNanos" to end - start)
        previousStart = start
        if (inputs.isNotEmpty()) {
            evidence.record("perf-input-response", mapOf("frame" to frame - 1, "renderReturnNanos" to end,
                "samples" to inputs.toList(), "scope" to "callback-to-first-render-return; correlate actions/observations separately"))
            inputs.clear()
        }
        if (frames.size >= 60) flush()
    }
    private fun flush() {
        if (frames.isNotEmpty()) {
            evidence.record("perf-frame-timing", mapOf("samples" to frames.toList()))
            frames.clear()
        }
    }
    override fun pause() { flush(); previousStart = null; application.pause() }
    override fun resume() { previousStart = null; application.resume() }
    override fun dispose() { flush(); application.dispose() }
}
