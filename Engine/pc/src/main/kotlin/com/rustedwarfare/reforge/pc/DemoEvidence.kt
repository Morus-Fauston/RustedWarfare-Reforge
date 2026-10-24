package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ClientEvidence
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Local demo diagnostics, separate from original-game trajectory and performance acceptance. */
class DemoEvidence internal constructor(directory: Path, createWriter: (Path) -> java.io.BufferedWriter) : ClientEvidence, AutoCloseable {
    constructor(directory: Path) : this(directory, { Files.newBufferedWriter(it, StandardOpenOption.CREATE_NEW) })
    private val writer: java.io.BufferedWriter
    private var sequence = 0L

    init {
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        writer = createWriter(directory.resolve("demo.jsonl"))
        try {
            record("manifest", mapOf("format" to "reforge.windows-demo.v1", "mode" to "demo", "ticket" to "ENG-P002-006",
                "java" to System.getProperty("java.version"),
                "os" to System.getProperty("os.name"), "vision" to "circle-radius120-no-occlusion-memory",
                "units" to 50, "stepsPerSecond" to 60, "coreDelta" to 1.0, "originalGameExecuted" to false,
                "runtimeJars" to System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { name ->
                    val path = Path.of(name)
                    if (!Files.isRegularFile(path)) return@map mapOf("path" to path.toAbsolutePath().toString(), "kind" to "directory-or-missing")
                    mapOf("path" to path.toAbsolutePath().toString(), "sha256" to evidenceSha256(path))
                }))
        } catch (failure: Throwable) {
            preserveFailure(failure) { writer.close() }
            throw failure
        }
    }

    override fun record(kind: String, values: Map<String, Any?>) {
        val output = evidenceJson(mapOf("sequence" to sequence++, "at" to Instant.now().toString(), "kind" to kind) + values)
        writer.write(output); writer.newLine(); writer.flush()
    }

    override fun close() = writer.close()
}
