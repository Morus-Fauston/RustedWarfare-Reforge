package com.rustedwarfare.reforge.client

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import com.rustedwarfare.reforge.core.content.StructuredNumberLexemes
import org.hjson.JsonValue
import org.hjson.ParseException

/** Declarative presentation metadata. This reader performs no file, image or renderer access. */
class ResourcePackMetadata private constructor(val id: ResourceId, val name: String, val description: String,
    val icon: ResourceId, val formatVersion: Int, val requiresResourceFormat: Int) {
    companion object {
        const val CURRENT_RESOURCE_FORMAT = 3
        fun read(bytes: ByteArray, source: String): ResourcePackMetadata {
            fun bad(field: String, reason: String): Nothing =
                throw IllegalArgumentException("RESOURCE_PACK_METADATA_INVALID: $source / $field: $reason")
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { bad("encoding", "Expected UTF-8 metadata") }
            val parsed = try {
                when {
                    source.endsWith(".json") -> JsonValue.readJSON(text)
                    source.endsWith(".hjson") -> JsonValue.readHjson(text)
                    else -> bad("format", "Expected .json or .hjson metadata")
                }
            } catch (failure: ParseException) { bad("syntax", "${failure.line}:${failure.column}: ${failure.message}") }
            if (!parsed.isObject) bad("metadata", "Expected an object")
            val metadata = parsed.asObject()
            val allowed = setOf("formatVersion", "requiresResourceFormat", "id", "name", "description", "icon")
            val seen = mutableSetOf<String>()
            for (member in metadata) {
                if (!seen.add(member.name)) bad(member.name, "Duplicate metadata member")
                if (member.name !in allowed) bad(member.name, "Unknown metadata member")
            }
            val numbers = try { StructuredNumberLexemes.read(text, parsed) }
            catch (failure: IllegalArgumentException) { bad("syntax", failure.message ?: "Cannot retain numeric source tokens") }
            fun required(field: String) = metadata.get(field) ?: bad(field, "Required metadata member")
            fun version(field: String, maximum: Int = 1): Int {
                val value = required(field)
                val token = numbers[value]
                val integer = token?.toIntOrNull()
                if (!value.isNumber || integer == null || token != integer.toString() || integer !in 1..maximum)
                    bad(field, "Expected supported lexical integer in 1..$maximum")
                return integer
            }
            fun string(field: String): String {
                val value = required(field)
                if (!value.isString || value.asString().isBlank() || value.asString().any(Char::isISOControl))
                    bad(field, "Expected nonblank text without control characters")
                return value.asString()
            }
            fun resource(field: String): ResourceId = try { ResourceId.parse(string(field)) }
            catch (failure: IllegalArgumentException) { bad(field, failure.message ?: "Invalid resource ID") }
            val format = version("formatVersion")
            val requires = version("requiresResourceFormat", CURRENT_RESOURCE_FORMAT)
            return ResourcePackMetadata(resource("id"), string("name"), string("description"), resource("icon"), format, requires)
        }
    }
}
