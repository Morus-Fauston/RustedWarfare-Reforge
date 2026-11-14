package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.rustedwarfare.reforge.client.BootstrapScreen
import com.rustedwarfare.reforge.client.DemoStartupScreen
import java.nio.file.Path

fun main(args: Array<String>) {
    val userBase = PcVisualAssetPaths.userBase(System.getenv("LOCALAPPDATA"), System.getProperty("user.home"))
    val codeLocation = Path.of(PcVisualAssetSettings::class.java.protectionDomain.codeSource.location.toURI())
    val applicationRoot = PcVisualAssetPaths.applicationRoot(codeLocation,
        Path.of(""))
    val sceneRoot = applicationRoot.resolve(if (codeLocation.fileName.toString().endsWith(".jar"))
        "test-scenarios/scenarios" else "SimulationTest/public/scenarios")
    val options = WindowsLaunchOptions.parse(args, Path.of(""), sceneRoot.resolve("phase-six-a-json.json"))
    val runId = options.runId
    val visualSettings = PcVisualAssetSettings(VisualAssetSettingsStore(userBase.resolve("settings.properties")),
        userBase.resolve("cache"), applicationRoot, options)
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
                Lwjgl3Application(DemoStartupScreen(evidence, visualSettings), config)
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
            if (options.backgroundInput) {
                if (options.backgroundCompact) setWindowedMode(960, 640) else setWindowedMode(1920, 1080)
                setInitialVisible(false)
            } else {
                setWindowedMode(1200, 800)
            }
            setWindowSizeLimits(960, 640, -1, -1)
            setPauseWhenLostFocus(!options.backgroundInput)
            setForegroundFPS(60)
            useVsync(true)
            disableAudio(false)
        }
        evidence.record("launch", mapOf("args" to args.toList(), "inputs" to inputs.toString(),
            "output" to output.toString(), "presentationStepsPerSecond" to 60, "coreDelta" to 1.0,
            "deltaUnit" to "sim-tick", "pacingIsOriginalGameFact" to false))
        try {
            val screen = BootstrapScreen(inputs, evidence,
                visualSettings = visualSettings, soundFactory = PcPlaybackSoundFactory(),
                testScenarioSeed = options.testScenarioSeed ?: sceneRoot.resolve("dual-ship-production.json"),
                selectDefaultTestScenario = options.testScenarioSeed != null,
                alternateStartSeed = options.testScenarioSeed?.resolveSibling("phase-six-a-spider-json.json"))
            val client = WindowPerfSampling(screen, evidence)
            val application = if (options.backgroundInput)
                BackgroundClientInput(client, output, runId, evidence) { screen.inputGeneration }
            else client
            Lwjgl3Application(application, config)
            evidence.record("process-completed", mapOf("status" to "normal"))
        } catch (failure: Throwable) {
            preserveFailure(failure) { evidence.record("process-failed", mapOf("message" to failure.toString())) }
            throw failure
        }
    }
}
