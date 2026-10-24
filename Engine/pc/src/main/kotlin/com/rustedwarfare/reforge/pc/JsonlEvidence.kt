package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.ControlledScenario
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Window diagnostics, deliberately separate from the test line's formal trajectory schema. */
class JsonlEvidence internal constructor(private val directory: Path, private val runId: String, inputs: Path,
                                        createWriter: (Path) -> java.io.BufferedWriter) : ClientEvidence, AutoCloseable {
    constructor(directory: Path, runId: String, inputs: Path) : this(directory, runId, inputs,
        { Files.newBufferedWriter(it, StandardOpenOption.CREATE_NEW) })
    private val writer: java.io.BufferedWriter
    private var sequence = 0L
    private var contentRoot = inputs

    init {
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        Files.createDirectories(directory.resolve("reforge"))
        Files.createDirectories(directory.resolve("logs"))
        Files.createDirectories(directory.resolve("review"))
        writer = createWriter(directory.resolve("reforge/client-diagnostic.jsonl"))
        try {
            Files.writeString(directory.resolve("manifest.json"), json(mapOf(
                "format" to "p001.windows-diagnostic.v1", "runId" to runId,
                "phase" to "P002", "topic" to "03-windows-client", "ticket" to "ENG-P002-009",
                "startedAt" to Instant.now().toString(), "inputs" to inputs.toString(),
                "javaVersion" to System.getProperty("java.version"), "javaHome" to System.getProperty("java.home"),
                "osName" to System.getProperty("os.name"), "osVersion" to System.getProperty("os.version"),
                "osArch" to System.getProperty("os.arch"), "workingDirectory" to Path.of("").toAbsolutePath().toString(),
                "runtimeJars" to System.getProperty("java.class.path").split(java.io.File.pathSeparator)
                    .map { identity(Path.of(it)) },
                "originalGameExecuted" to false, "compatibilityVerdict" to "not-evaluated",
            )), StandardOpenOption.CREATE_NEW)
        } catch (failure: Throwable) {
            preserveFailure(failure) { writer.close() }
            throw failure
        }
    }

    override fun record(kind: String, values: Map<String, Any?>) {
        writer.write(json(mapOf("format" to "p001.windows-diagnostic.v1", "runId" to runId,
            "sequence" to sequence++, "wallTime" to Instant.now().toString(), "kind" to kind) + values))
        writer.newLine()
        if (kind != "observation" || values["phase"] != "step") writer.flush()
        if (kind == "load-request") contentRoot = Path.of(values.getValue("root") as String).toAbsolutePath().normalize()
        if (kind == "load-ready") {
            val files = mutableListOf(contentRoot.resolve("maps/p001-controlled.tmx"),
                contentRoot.resolve("tilesets/p001-ground.tsx"), contentRoot.resolve("tilesets/p001-rock.tsx"),
                contentRoot.resolve("units/weaponless_tank.ini"))
            val scenario = ControlledScenario.load(contentRoot)
            files += scenario.map.tileSets.map { it.imagePath }
            files += scenario.unitDefinitions.values.map { it.imagePath }
            val manifestFile = contentRoot.resolve("sandbox.properties")
            val manifest = manifestFile.takeIf { Files.exists(it) }?.let(::identity)
            if (manifest != null) files.add(manifestFile)
            record("input-identities", mapOf("generation" to values["generation"],
                "root" to contentRoot.toString(), "scenarioId" to scenario.scenarioId,
                "sandboxManifest" to (manifest ?: identity(manifestFile)), "files" to files.distinct().map(::identity)))
        }
    }

    override fun close() { writer.close() }

    private fun identity(path: Path): Map<String, Any?> {
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute)) return mapOf("path" to absolute.toString(), "exists" to false)
        return mapOf("path" to absolute.toString(), "bytes" to Files.size(absolute),
            "sha256" to evidenceSha256(absolute))
    }

    private fun json(value: Any?): String = evidenceJson(value)
}
