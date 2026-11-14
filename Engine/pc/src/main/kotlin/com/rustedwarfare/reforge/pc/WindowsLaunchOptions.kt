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
) {
    val hasVisualOverride: Boolean get() = visualSource != null || originalAssets != null
    companion object {
        fun parse(args: Array<String>, repoRoot: Path,
                  defaultScenarioSeed: Path = repoRoot.resolve("SimulationTest/public/scenarios/phase-six-a-json.json")): WindowsLaunchOptions {
            require(args.size % 2 == 0) { "Usage: [--mode sandbox|demo] [--inputs PATH] [--evidence NEW_RUN_DIRECTORY] [--visuals placeholder|original] [--original-assets PATH]" }
            val visualKeys = setOf("--visuals", "--original-assets")
            val pairs = args.toList().chunked(2)
            require(pairs.filter { it[0] in visualKeys }.map { it[0] }.distinct().size == pairs.count { it[0] in visualKeys }) {
                "Duplicate visual asset option"
            }
            val options = pairs.associate { (key, value) ->
                require(key in setOf("--mode", "--inputs", "--evidence", "--background-input", "--background-size") + visualKeys) { "Unknown option: $key" }
                key to value
            }
            val mode = options["--mode"] ?: "sandbox"
            require(mode in setOf("sandbox", "demo")) { "Unknown mode: $mode" }
            val backgroundInput = options["--background-input"]?.let { value ->
                require(value == "true" && pairs.count { it[0] == "--background-input" } == 1) {
                    "Expected one --background-input true option"
                }
                require(mode == "sandbox" && "--evidence" in options) { "Background input requires sandbox evidence" }
                true
            } ?: false
            val backgroundCompact = options["--background-size"]?.let { value ->
                require(backgroundInput && value == "960x640" && pairs.count { it[0] == "--background-size" } == 1) {
                    "Expected one --background-size 960x640 option with background input"
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
                require("--inputs" !in options) { "Demo uses packaged self-owned inputs" }
                return WindowsLaunchOptions(mode, null,
                    resolve(options["--evidence"] ?: "Engine/pc/build/demo-runs/$runId"), runId,
                    "RustedWarfare-Reforge | Field Demo", visualSource, originalAssets)
            }
            val explicitInputs = options["--inputs"]
            val seed = defaultScenarioSeed.toAbsolutePath().normalize()
            return WindowsLaunchOptions(mode,
                explicitInputs?.let(::resolve) ?: seed.parent.resolve("../inputs/phase-six-a-sandbox/json").normalize(),
                resolve(options["--evidence"] ?: "Engine/pc/build/sandbox-runs/$runId"),
                runId, if (explicitInputs == null) "RustedWarfare-Reforge | Phase VI A sandbox"
                else "RustedWarfare-Reforge | P002 Windows sandbox", visualSource, originalAssets,
                if (explicitInputs == null) seed else null, backgroundInput, backgroundCompact)
        }
    }
}
