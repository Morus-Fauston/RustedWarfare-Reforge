package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.rustedwarfare.reforge.client.BootstrapScreen
import com.rustedwarfare.reforge.client.DemoScreen
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

fun main(args: Array<String>) {
    require(args.size % 2 == 0) { "Usage: [--mode sandbox|demo] [--inputs PATH] [--evidence NEW_RUN_DIRECTORY]" }
    val options = args.toList().chunked(2).associate { (key, value) ->
        require(key in setOf("--mode", "--inputs", "--evidence")) { "Unknown option: $key" }; key to value
    }
    val mode = options["--mode"] ?: "sandbox"
    require(mode in setOf("sandbox", "demo")) { "Unknown mode: $mode" }
    val inputs = Path.of(options["--inputs"] ?: "TestLab/local/P001-移动沙盒输入").toAbsolutePath().normalize()
    val runId = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) +
        "-" + UUID.randomUUID().toString().take(8)
    if (mode == "demo") {
        require("--inputs" !in options) { "Demo uses packaged self-owned inputs" }
        val output = Path.of(options["--evidence"] ?: "Engine/pc/build/demo-runs/$runId").toAbsolutePath().normalize()
        DemoEvidence(output).use { evidence ->
            val config = Lwjgl3ApplicationConfiguration().apply {
                setTitle("RustedWarfare-Reforge | Field Demo")
                setWindowedMode(1200, 893)
                setWindowSizeLimits(900, 670, -1, -1)
                setForegroundFPS(60); useVsync(true); disableAudio(true)
            }
            evidence.record("launch", mapOf("args" to args.toList(), "output" to output.toString()))
            try {
                Lwjgl3Application(DemoScreen(evidence), config)
                evidence.record("process-completed", mapOf("status" to "normal"))
            } catch (failure: Throwable) {
                evidence.record("process-failed", mapOf("message" to failure.toString()))
                throw failure
            }
        }
        return
    }
    val output = Path.of(options["--evidence"] ?: "TestLab/runs/P001-自身行为验证/03-windows-client/$runId").toAbsolutePath().normalize()
    JsonlEvidence(output, runId, inputs).use { evidence ->
        val config = Lwjgl3ApplicationConfiguration().apply {
            setTitle("RustedWarfare-Reforge | P001 Windows sandbox")
            setWindowedMode(1200, 800)
            setWindowSizeLimits(960, 640, -1, -1)
            setForegroundFPS(60)
            useVsync(true)
            disableAudio(true)
        }
        evidence.record("launch", mapOf("args" to args.toList(), "inputs" to inputs.toString(),
            "output" to output.toString(), "presentationStepsPerSecond" to 60, "coreDelta" to 1.0,
            "deltaUnit" to "sim-tick", "pacingIsOriginalGameFact" to false))
        try {
            Lwjgl3Application(BootstrapScreen(inputs, evidence), config)
            evidence.record("process-completed", mapOf("status" to "normal"))
        } catch (failure: Throwable) {
            evidence.record("process-failed", mapOf("message" to failure.toString()))
            throw failure
        }
    }
}
