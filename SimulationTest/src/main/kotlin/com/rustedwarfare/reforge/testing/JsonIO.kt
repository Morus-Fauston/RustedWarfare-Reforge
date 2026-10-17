package com.rustedwarfare.reforge.testing

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal val json = Json { prettyPrint = false; isLenient = false }
internal fun obj(vararg entries: Pair<String, JsonElement?>) = JsonObject(entries.associate { it.first to (it.second ?: JsonNull) })
internal fun s(value: String) = JsonPrimitive(value)
internal fun n(value: Number): JsonPrimitive {
    if (!value.toDouble().isFinite()) throw CaptureError("Nonfinite number in collected output")
    return JsonPrimitive(value)
}
internal fun b(value: Boolean) = JsonPrimitive(value)
internal fun array(values: Iterable<JsonElement>) = JsonArray(values.toList())
internal fun JsonObject.required(key: String): JsonElement = get(key) ?: throw InputError("Missing field: $key")
internal fun JsonObject.text(key: String): String {
    val value = required(key) as? JsonPrimitive ?: throw InputError("Expected string: $key")
    if (!value.isString || value.content.isBlank()) throw InputError("Expected nonempty string: $key")
    return value.content
}
internal fun JsonObject.id(key: String): String = text(key).also {
    if (!Regex("[A-Za-z0-9._:-]+").matches(it)) throw InputError("Invalid ID: $key")
}
internal fun JsonObject.number(key: String): Double {
    val value = required(key) as? JsonPrimitive ?: throw InputError("Expected number: $key")
    if (value.isString) throw InputError("Expected number: $key")
    return (value.doubleOrNull ?: throw InputError("Expected number: $key")).also {
        if (!it.isFinite()) throw InputError("Nonfinite number: $key")
    }
}
internal fun JsonObject.integer(key: String): Long {
    val value = required(key) as? JsonPrimitive ?: throw InputError("Expected integer: $key")
    if (value.isString) throw InputError("Expected integer: $key")
    return value.longOrNull ?: throw InputError("Expected integer: $key")
}
internal fun JsonObject.objectAt(key: String) = required(key) as? JsonObject ?: throw InputError("Expected object: $key")
internal fun JsonObject.arrayAt(key: String) = required(key) as? JsonArray ?: throw InputError("Expected array: $key")
internal fun JsonObject.header(kind: String) {
    if (text("kind") != kind || integer("schemaVersion") != 1L) throw InputError("Unsupported kind/version: $kind")
}
internal fun readObject(path: Path): JsonObject = try {
    json.parseToJsonElement(Files.readString(path)) as? JsonObject ?: throw InputError("Expected object: $path")
} catch (e: IllegalArgumentException) { throw InputError("Invalid JSON at $path: ${e.message}") }
internal fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun sha256(path: Path): String = Files.newInputStream(path).use { stream ->
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
    hash.digest().joinToString("") { "%02x".format(it) }
}
internal fun record(kind: String, vararg fields: Pair<String, JsonElement?>) = obj(
    "schemaVersion" to n(1), "kind" to s(kind), *fields,
)
internal class InputError(message: String) : RuntimeException(message)
internal class CaptureError(message: String) : RuntimeException(message)
