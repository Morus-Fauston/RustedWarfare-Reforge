package com.rustedwarfare.reforge.pc

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.rustedwarfare.reforge.client.VisualAssetSource

data class WindowsLaunchOptions(
    val mode: String,
    val inputs: Path?,
    val evidence: Path,
    val runId: String,
    val title: String,
    val visualSource: VisualAssetSource? = null,
    val originalAssets: Path? = null,
    val testScenarioSeed: Path? = null,
    val backgroundInput: Boolean = false,
    val backgroundCompact: Boolean = false,
    val backgroundWidth: Int = 1920,
    val backgroundHeight: Int = 1080,
    val touchSimulation: Boolean = false,
    val development: Boolean = false,
    val diagnostics: Boolean = false,
    val skirmishMap: Path? = null,
) {
    val hasVisualOverride: Boolean get() = visualSource != null || originalAssets != null
    companion object {
        fun parse(args: Array<String>, repoRoot: Path,
                  defaultScenarioSeed: Path = repoRoot.resolve("SimulationTest/public/scenarios/phase-six-a-json.json")): WindowsLaunchOptions {
            require(args.size % 2 == 0) { "Usage: [--mode sandbox|demo] [--inputs PATH] [--map PATH] [--development true] [--evidence NEW_RUN_DIRECTORY] [--visuals placeholder|original] [--original-assets PATH]" }
            val visualKeys = setOf("--visuals", "--original-assets")
            val pairs = args.toList().chunked(2)
            require(pairs.filter { it[0] in visualKeys }.map { it[0] }.distinct().size == pairs.count { it[0] in visualKeys }) {
                "Duplicate visual asset option"
            }
            val options = pairs.associate { (key, value) ->
                require(key in setOf("--mode", "--inputs", "--map", "--development", "--evidence", "--background-input", "--background-size", "--touch") + visualKeys) { "Unknown option: $key" }
                key to value
            }
            val mode = options["--mode"] ?: "sandbox"
            require(mode in setOf("sandbox", "demo")) { "Unknown mode: $mode" }
            val development = options["--development"]?.let { value ->
                require(value == "true" && mode == "sandbox" && pairs.count { it[0] == "--development" } == 1) {
                    "Expected one --development true option in sandbox mode"
                }
                true
            } ?: false
            val backgroundInput = options["--background-input"]?.let { value ->
                require(value == "true" && pairs.count { it[0] == "--background-input" } == 1) {
                    "Expected one --background-input true option"
                }
                require(mode == "sandbox" && "--evidence" in options) { "Background input requires sandbox evidence" }
                true
            } ?: false
            val (backgroundCompact, bgWidth, bgHeight) = options["--background-size"]?.let { value ->
                require(backgroundInput && pairs.count { it[0] == "--background-size" } == 1) {
                    "Expected one --background-size option with background input"
                }
                when (value) {
                    "960x640" -> Triple(true, 960, 640)
                    "1280x800" -> Triple(false, 1280, 800)
                    "1920x1080" -> Triple(false, 1920, 1080)
                    "2400x1080" -> Triple(false, 2400, 1080)
                    "2560x1080" -> Triple(false, 2560, 1080)
                    "2560x1440" -> Triple(false, 2560, 1440)
                    "3840x2160" -> Triple(false, 3840, 2160)
                    else -> throw IllegalArgumentException("Unsupported --background-size: $value")
                }
            } ?: Triple(false, 1920, 1080)
            val touchSimulation = options["--touch"]?.let { value ->
                require(development && value == "true" && pairs.count { it[0] == "--touch" } == 1) {
                    "Expected one --touch true option in development mode"
                }
                true
            } ?: false
            val runId = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) +
                "-" + UUID.randomUUID().toString().take(8)
            fun resolve(path: String): Path = repoRoot.resolve(path).toAbsolutePath().normalize()
            val visualSource = options["--visuals"]?.let { name ->
                requireNotNull(VisualAssetSource.entries.firstOrNull { it.value == name }) { "Unknown visual source: $name" }
            } ?: if ("--original-assets" in options) VisualAssetSource.ORIGINAL else null
            require(visualSource != VisualAssetSource.PLACEHOLDER || "--original-assets" !in options) {
                "--visuals placeholder conflicts with --original-assets"
            }
            val originalAssets = options["--original-assets"]?.let { value ->
                require(value.isNotBlank()) { "--original-assets requires a directory" }; resolve(value)
            }
            if (mode == "demo") {
                require("--inputs" !in options && "--map" !in options) { "Demo uses packaged self-owned inputs" }
                return WindowsLaunchOptions(mode, null,
                    resolve(options["--evidence"] ?: "Engine/pc/build/demo-runs/$runId"), runId,
                    "RustedWarfare-Reforge | Field Demo", visualSource, originalAssets)
            }
            val explicitInputs = options["--inputs"]
            val skirmishMap = options["--map"]?.let { value -> require(value.isNotBlank()) { "--map requires a .tmx path" }; resolve(value) }
            val seed = defaultScenarioSeed.toAbsolutePath().normalize()
            return WindowsLaunchOptions(mode,
                explicitInputs?.let(::resolve) ?: seed.parent.resolve("../inputs/phase-six-a-sandbox/json").normalize(),
                resolve(options["--evidence"] ?: "Engine/pc/build/sandbox-runs/$runId"),
                runId, when {
                    explicitInputs != null -> "RustedWarfare-Reforge | P002 Windows sandbox"
                    development -> "RustedWarfare-Reforge | Phase VI A sandbox"
                    else -> "RustedWarfare-Reforge"
                }, visualSource, originalAssets,
                if (development && explicitInputs == null && skirmishMap == null) seed else null, backgroundInput, backgroundCompact,
                bgWidth, bgHeight, touchSimulation, development, development || "--evidence" in options, skirmishMap)
        }
    }
}
