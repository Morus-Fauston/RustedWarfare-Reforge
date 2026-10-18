package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonWriter
import com.rustedwarfare.reforge.client.ClientEvidence
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant

/** Local demo diagnostics, separate from original-game trajectory and performance acceptance. */
class DemoEvidence(directory: Path) : ClientEvidence, AutoCloseable {
    private val writer: java.io.BufferedWriter
    private var sequence = 0L

    init {
        Files.createDirectories(directory.parent)
        Files.createDirectory(directory)
        writer = Files.newBufferedWriter(directory.resolve("demo.jsonl"), StandardOpenOption.CREATE_NEW)
        record("manifest", mapOf("format" to "reforge.windows-demo.v1", "mode" to "demo", "ticket" to "ENG-P002-006",
            "java" to System.getProperty("java.version"),
            "os" to System.getProperty("os.name"), "vision" to "circle-radius120-no-occlusion-memory",
            "units" to 50, "stepsPerSecond" to 60, "coreDelta" to 1.0, "originalGameExecuted" to false,
            "runtimeJars" to System.getProperty("java.class.path").split(java.io.File.pathSeparator).map { name ->
                val path = Path.of(name)
                if (!Files.isRegularFile(path)) return@map mapOf("path" to path.toAbsolutePath().toString(), "kind" to "directory-or-missing")
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                mapOf("path" to path.toAbsolutePath().toString(), "sha256" to digest.digest().joinToString("") { "%02x".format(it) })
            }))
    }

    override fun record(kind: String, values: Map<String, Any?>) {
        val output = StringWriter()
        JsonWriter(output).use { json ->
            json.setOutputType(JsonWriter.OutputType.json)
            fun write(value: Any?) {
                when (value) {
                    is Map<*, *> -> { json.`object`(); value.forEach { (key, item) -> json.name(key.toString()); write(item) }; json.pop() }
                    is Iterable<*> -> { json.array(); value.forEach(::write); json.pop() }
                    else -> json.value(value)
                }
            }
            write(mapOf("sequence" to sequence++, "at" to Instant.now().toString(), "kind" to kind) + values)
        }
        writer.write(output.toString()); writer.newLine(); writer.flush()
    }

    override fun close() = writer.close()
}
