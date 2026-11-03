package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.content.StructuredNumberLexemes
import org.hjson.JsonObject
import org.hjson.JsonValue
import org.hjson.ParseException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Collections

data class ResourceSoundDeclaration internal constructor(val file: String)
data class ResourceSoundCandidate internal constructor(val sound: ResourceId, val weight: Int = 1) {
    init { require(weight > 0) { "Expected a positive sound candidate weight" } }
}

class ResourceSoundEvent internal constructor(candidates: List<ResourceSoundCandidate>, val append: Boolean = false) {
    internal constructor(sound: ResourceId) : this(listOf(ResourceSoundCandidate(sound)))
    val candidates: List<ResourceSoundCandidate> = Collections.unmodifiableList(ArrayList(candidates))
    init { require(this.candidates.isNotEmpty()) { "Expected a nonempty sound candidate list" } }
    val totalWeight: Long = this.candidates.fold(0L) { total, candidate -> Math.addExact(total, candidate.weight.toLong()) }
    /** Compatibility query for genuinely single-candidate events; never flatten a weighted table. */
    val sound: ResourceId get() {
        check(candidates.size == 1) { "RESOURCE_SOUND_SELECTION_REQUIRED: Multi-candidate event requires weighted selection" }
        return candidates.single().sound
    }
    override fun equals(other: Any?) = other is ResourceSoundEvent && candidates == other.candidates && append == other.append
    override fun hashCode() = 31 * candidates.hashCode() + append.hashCode()
}

/** Event-to-resource indirection only; never determines when or who may hear an event. */
class ResourceSoundManifest private constructor(val formatVersion: Int, sounds: Map<ResourceId, ResourceSoundDeclaration>,
    events: Map<ResourceId, ResourceSoundEvent>) {
    val requiredResourceFormat: Int get() = if (formatVersion == 2) 3 else 2
    val sounds: Map<ResourceId, ResourceSoundDeclaration> = Collections.unmodifiableMap(LinkedHashMap(sounds))
    val events: Map<ResourceId, ResourceSoundEvent> = Collections.unmodifiableMap(LinkedHashMap(events))

    companion object {
        fun read(bytes: ByteArray, source: String): ResourceSoundManifest {
            fun bad(field: String, reason: String): Nothing =
                throw IllegalArgumentException("RESOURCE_PACK_SOUNDS_INVALID: $source / $field: $reason")
            val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { bad("encoding", "Expected UTF-8 sound declarations") }
            val parsed = try {
                when {
                    source.endsWith(".json") -> JsonValue.readJSON(text)
                    source.endsWith(".hjson") -> JsonValue.readHjson(text)
                    else -> bad("format", "Expected .json or .hjson sound declarations")
                }
            } catch (failure: ParseException) { bad("syntax", "${failure.line}:${failure.column}: ${failure.message}") }
            val numbers = try { StructuredNumberLexemes.read(text, parsed) }
            catch (failure: IllegalArgumentException) { bad("syntax", failure.message ?: "Cannot retain numeric source tokens") }
            fun fields(value: JsonValue, allowed: Set<String>, where: String, optional: Set<String> = emptySet()): JsonObject {
                if (!value.isObject) bad(where, "Expected an object")
                val result = value.asObject(); val seen = mutableSetOf<String>()
                for (member in result) {
                    if (!seen.add(member.name)) bad("$where.${member.name}", "Duplicate member")
                    if (member.name !in allowed) bad("$where.${member.name}", "Unknown member")
                }
                for (key in allowed - optional) if (result.get(key) == null) bad("$where.$key", "Required member")
                return result
            }
            fun resource(text: String, where: String): ResourceId = try { ResourceId.parse(text) }
            catch (failure: IllegalArgumentException) { bad(where, failure.message ?: "Invalid resource ID") }
            fun objectEntries(value: JsonValue, where: String): JsonObject {
                if (!value.isObject) bad(where, "Expected a resource object")
                return value.asObject()
            }
            val root = fields(parsed, setOf("formatVersion", "sounds", "events"), "sounds")
            if (!root.get("formatVersion").isNumber) bad("formatVersion", "Expected supported integer 1 or 2")
            val formatVersion = when (numbers[root.get("formatVersion")]) {
                "1" -> 1
                "2" -> 2
                else -> bad("formatVersion", "Expected supported integer 1 or 2")
            }
            val sounds = linkedMapOf<ResourceId, ResourceSoundDeclaration>()
            for (member in objectEntries(root.get("sounds"), "sounds")) {
                val where = "sounds.${member.name}"; val id = resource(member.name, where)
                if (id in sounds) bad(where, "Duplicate sound declaration")
                val entry = fields(member.value, setOf("file"), where)
                val value = entry.get("file")
                if (!value.isString) bad("$where.file", "Expected a package-relative WAV or Ogg path")
                val path = value.asString()
                if (!(path.endsWith(".wav", true) || path.endsWith(".ogg", true)) ||
                    path.any { it == ':' || it == '\\' || it.isISOControl() } ||
                    path.split('/').any { it.isBlank() || it == "." || it == ".." })
                    bad("$where.file", "Expected an ordinary relative WAV or Ogg path without parent or absolute segments")
                sounds[id] = ResourceSoundDeclaration(path)
            }
            val events = linkedMapOf<ResourceId, ResourceSoundEvent>()
            for (member in objectEntries(root.get("events"), "events")) {
                val where = "events.${member.name}"; val id = resource(member.name, where)
                if (id in events) bad(where, "Duplicate event declaration")
                if (formatVersion == 1) {
                    val entry = fields(member.value, setOf("sound"), where)
                    val value = entry.get("sound")
                    if (!value.isString) bad("$where.sound", "Expected a logical sound resource ID")
                    events[id] = ResourceSoundEvent(resource(value.asString(), "$where.sound"))
                } else {
                    val entry = fields(member.value, setOf("candidates", "append"), where, setOf("append"))
                    val values = entry.get("candidates")
                    if (!values.isArray || values.asArray().isEmpty) bad("$where.candidates", "Expected a nonempty candidate array")
                    val append = entry.get("append")?.let { value ->
                        if (!value.isBoolean) bad("$where.append", "Expected a boolean")
                        value.asBoolean()
                    } ?: false
                    val seen = mutableSetOf<ResourceId>()
                    val candidates = values.asArray().mapIndexed { index, value ->
                        val at = "$where.candidates[$index]"
                        val item = fields(value, setOf("sound", "weight"), at, setOf("weight"))
                        val address = item.get("sound")
                        if (!address.isString) bad("$at.sound", "Expected a logical sound resource ID")
                        val sound = resource(address.asString(), "$at.sound")
                        if (!seen.add(sound)) bad("$at.sound", "Duplicate candidate resource $sound")
                        val weight = item.get("weight")?.let { number ->
                            val token = numbers[number]
                            val parsed = if (number.isNumber && token != null && token.matches(Regex("[1-9][0-9]*"))) token.toIntOrNull() else null
                            parsed ?: bad("$at.weight", "Expected a positive lexical 32-bit integer")
                        } ?: 1
                        ResourceSoundCandidate(sound, weight)
                    }
                    events[id] = ResourceSoundEvent(candidates, append)
                }
            }
            return ResourceSoundManifest(formatVersion, sounds, events)
        }
    }
}
