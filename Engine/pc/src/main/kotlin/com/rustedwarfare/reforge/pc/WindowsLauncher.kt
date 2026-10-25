package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.rustedwarfare.reforge.client.BootstrapScreen
import com.rustedwarfare.reforge.client.DemoScreen
import java.nio.file.Path

fun main(args: Array<String>) {
    val options = WindowsLaunchOptions.parse(args, Path.of(""))
    val runId = options.runId
    if (options.mode == "demo") {
        val output = options.evidence
        DemoEvidence(output).use { evidence ->
            val config = Lwjgl3ApplicationConfiguration().apply {
                setTitle(options.title)
                setWindowedMode(1200, 893)
                setWindowSizeLimits(900, 670, -1, -1)
                setForegroundFPS(60); useVsync(true); disableAudio(true)
            }
            evidence.record("launch", mapOf("args" to args.toList(), "output" to output.toString()))
            try {
                Lwjgl3Application(DemoScreen(evidence), config)
                evidence.record("process-completed", mapOf("status" to "normal"))
            } catch (failure: Throwable) {
                preserveFailure(failure) { evidence.record("process-failed", mapOf("message" to failure.toString())) }
                throw failure
            }
        }
        return
    }
    val inputs = requireNotNull(options.inputs)
    val output = options.evidence
    JsonlEvidence(output, runId, inputs).use { evidence ->
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle(options.title)
            setWindowedMode(1200, 800)
            setWindowSizeLimits(960, 640, -1, -1)
            setPauseWhenLostFocus(true)
            setForegroundFPS(60)
            useVsync(true)
            disableAudio(true)
        }
        evidence.record("launch", mapOf("args" to args.toList(), "inputs" to inputs.toString(),
            "output" to output.toString(), "presentationStepsPerSecond" to 60, "coreDelta" to 1.0,
            "deltaUnit" to "sim-tick", "pacingIsOriginalGameFact" to false))
        try {
            Lwjgl3Application(WindowPerfSampling(BootstrapScreen(inputs, evidence), evidence), config)
            evidence.record("process-completed", mapOf("status" to "normal"))
        } catch (failure: Throwable) {
            preserveFailure(failure) { evidence.record("process-failed", mapOf("message" to failure.toString())) }
            throw failure
        }
    }
}
