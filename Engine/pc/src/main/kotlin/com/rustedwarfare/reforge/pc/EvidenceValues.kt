package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Mechanical encoding only. Protocol fields, sequence and flush stay with each writer. */
internal fun evidenceJson(value: Any?): String = StringWriter().also { output ->
    JsonWriter(output).use { json ->
        json.setOutputType(JsonWriter.OutputType.json)
        fun write(item: Any?) {
            when (item) {
                is Map<*, *> -> { json.`object`(); item.forEach { (key, entry) -> json.name(key.toString()); write(entry) }; json.pop() }
                is Iterable<*> -> { json.array(); item.forEach(::write); json.pop() }
                else -> json.value(item)
            }
        }
        write(value)
    }
}.toString()

internal fun evidenceSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
