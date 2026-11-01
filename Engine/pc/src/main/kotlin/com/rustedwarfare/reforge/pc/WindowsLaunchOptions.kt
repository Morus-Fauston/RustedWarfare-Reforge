package com.rustedwarfare.reforge.pc

import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class WindowsLaunchOptions(
    val mode: String,
    val inputs: Path?,
    val evidence: Path,
    val runId: String,
    val title: String,
) {
    companion object {
        fun parse(args: Array<String>, repoRoot: Path): WindowsLaunchOptions {
            require(args.size % 2 == 0) { "Usage: [--mode sandbox|demo] [--inputs PATH] [--evidence NEW_RUN_DIRECTORY]" }
            val options = args.toList().chunked(2).associate { (key, value) ->
                require(key in setOf("--mode", "--inputs", "--evidence")) { "Unknown option: $key" }
                key to value
            }
            val mode = options["--mode"] ?: "sandbox"
            require(mode in setOf("sandbox", "demo")) { "Unknown mode: $mode" }
            val runId = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now()) +
                "-" + UUID.randomUUID().toString().take(8)
            fun resolve(path: String): Path = repoRoot.resolve(path).toAbsolutePath().normalize()
            if (mode == "demo") {
                require("--inputs" !in options) { "Demo uses packaged self-owned inputs" }
                return WindowsLaunchOptions(mode, null,
                    resolve(options["--evidence"] ?: "Engine/pc/build/demo-runs/$runId"), runId,
                    "RustedWarfare-Reforge | Field Demo")
            }
            val explicitInputs = options["--inputs"]
            return WindowsLaunchOptions(mode,
                explicitInputs?.let(::resolve) ?: PhaseFourInputs.root,
                resolve(options["--evidence"] ?: "Engine/pc/build/sandbox-runs/$runId"),
                runId, if (explicitInputs == null) "RustedWarfare-Reforge | Phase IV sandbox"
                else "RustedWarfare-Reforge | P002 Windows sandbox")
        }
    }
}
