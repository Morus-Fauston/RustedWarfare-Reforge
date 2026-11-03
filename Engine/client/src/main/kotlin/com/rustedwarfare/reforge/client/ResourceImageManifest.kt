package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.StructuredNumberLexemes
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.ParseException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Collections

data class ResourceImageDeclaration internal constructor(val file: String, val frameWidth: Int,
    val frameHeight: Int, val totalFrames: Int)

/** Logical layouts only. Platform import checks PNG bytes, source boundaries and frozen paths. */
class ResourceImageManifest private constructor(images: Map<ResourceId, ResourceImageDeclaration>) {
    val images: Map<ResourceId, ResourceImageDeclaration> = Collections.unmodifiableMap(LinkedHashMap(images))

    companion object {
        fun read(bytes: ByteArray, source: String): ResourceImageManifest {
            fun bad(field: String, reason: String): Nothing =
                throw IllegalArgumentException("RESOURCE_PACK_IMAGES_INVALID: $source / $field: $reason")
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { bad("encoding", "Expected UTF-8 image declarations") }
            val parsed = try {
                when {
                    source.endsWith(".json") -> JsonValue.readJSON(text)
                    source.endsWith(".hjson") -> JsonValue.readHjson(text)
                    else -> bad("format", "Expected .json or .hjson image declarations")
                }
            } catch (failure: ParseException) { bad("syntax", "${failure.line}:${failure.column}: ${failure.message}") }
            val numbers = try { StructuredNumberLexemes.read(text, parsed) }
            catch (failure: IllegalArgumentException) { bad("syntax", failure.message ?: "Cannot retain numeric source tokens") }
            fun fields(value: JsonValue, allowed: Set<String>, where: String): JsonObject {
                if (!value.isObject) bad(where, "Expected an object")
                val result = value.asObject()
                val seen = mutableSetOf<String>()
                for (member in result) {
                    if (!seen.add(member.name)) bad("$where.${member.name}", "Duplicate member")
                    if (member.name !in allowed) bad("$where.${member.name}", "Unknown member")
                }
                for (key in allowed) if (result.get(key) == null) bad("$where.$key", "Required member")
                return result
            }
            val root = fields(parsed, setOf("formatVersion", "images"), "images")
            if (!root.get("formatVersion").isNumber || numbers[root.get("formatVersion")] != "1")
                bad("formatVersion", "Expected supported integer 1")
            val entries = root.get("images")
            if (!entries.isObject) bad("images", "Expected a resource-to-image object")
            val result = linkedMapOf<ResourceId, ResourceImageDeclaration>()
            for (member in entries.asObject()) {
                val id = try { ResourceId.parse(member.name) }
                catch (failure: IllegalArgumentException) { bad("images.${member.name}", failure.message ?: "Invalid resource ID") }
                val where = "images.$id"
                if (id in result) bad(where, "Duplicate resource declaration")
                val entry = fields(member.value, setOf("file", "frameWidth", "frameHeight", "totalFrames"), where)
                val file = entry.get("file")
                if (!file.isString) bad("$where.file", "Expected a package-relative PNG path")
                val path = file.asString()
                if (!path.endsWith(".png", ignoreCase = true) || path.any { it == ':' || it == '\\' || it.isISOControl() } ||
                    path.split('/').any { it.isBlank() || it == "." || it == ".." })
                    bad("$where.file", "Expected an ordinary relative PNG path without parent or absolute segments")
                fun positive(key: String): Int {
                    val value = entry.get(key)
                    val token = numbers[value]
                    if (!value.isNumber || token == null || !token.matches(Regex("[1-9][0-9]*")))
                        bad("$where.$key", "Expected a positive lexical integer")
                    return token.toIntOrNull()?.takeIf { it > 0 } ?: bad("$where.$key", "Integer exceeds the supported range")
                }
                result[id] = ResourceImageDeclaration(path, positive("frameWidth"), positive("frameHeight"), positive("totalFrames"))
            }
            return ResourceImageManifest(result)
        }
    }
}
