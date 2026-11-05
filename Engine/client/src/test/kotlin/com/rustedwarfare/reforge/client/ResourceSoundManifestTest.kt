package com.rustedwarfare.reforge.client

import kotlin.test.*

class ResourceSoundManifestTest {
    private val valid = """{"formatVersion":1,
        "sounds":{"owned:sounds/cannon":{"file":"audio/cannon.ogg"}},
        "events":{"reforge:events/tank-fire":{"sound":"owned:sounds/cannon"}}}"""

    @Test fun weighted_declarations_reject_bad_weights_candidate_tables_and_append_types_and_remain_immutable() {
        val candidate = """{"sound":"owned:sounds/cannon","weight":2}"""
        val event = """{"candidates":[$candidate]}"""
        val weighted = valid.replace("\"formatVersion\":1", "\"formatVersion\":2")
            .replace("""{"sound":"owned:sounds/cannon"}""", event)
        val cases = listOf("0", "-1", "1.5", "1e0", "null", "true", "\"2\"", "2147483648").map {
            weighted.replace("\"weight\":2", "\"weight\":$it") to "weight"
        } + listOf(
            weighted.replace("[$candidate]", "[]") to "candidates",
            weighted.replace("[$candidate]", "{}") to "candidates",
            weighted.replace("[$candidate]", "[null]") to "candidates",
            weighted.replace("[$candidate]", "[$candidate,$candidate]") to "Duplicate",
            weighted.replace("\"weight\":2", "\"weight\":2,\"weight\":1") to "weight",
            weighted.replace(event, """{"candidates":[$candidate],"append":null}""") to "append",
            weighted.replace(event, """{"candidates":[$candidate],"append":"true"}""") to "append",
            weighted.replace(event, """{"candidates":[$candidate],"volume":2}""") to "volume",
            weighted.replace("\"formatVersion\":2", "\"formatVersion\":2.0") to "formatVersion",
            weighted.replace("\"formatVersion\":2", "\"formatVersion\":2e0") to "formatVersion",
        )
        for (source in listOf("own/sounds.json", "own/sounds.hjson")) for ((text, field) in cases) {
            assertNotEquals(weighted, text)
            val error = assertFailsWith<IllegalArgumentException> { ResourceSoundManifest.read(text.toByteArray(), source) }
            assertTrue(error.message.orEmpty().contains(source) && error.message.orEmpty().contains(field), error.message)
        }
        val parsed = ResourceSoundManifest.read(weighted.toByteArray(), "own/sounds.json")
        assertEquals(2, parsed.formatVersion); assertEquals(3, parsed.requiredResourceFormat)
        val declaration = parsed.events.values.single()
        assertEquals(2L, declaration.totalWeight); assertFalse(declaration.append)
        assertFailsWith<UnsupportedOperationException> { (declaration.candidates as MutableList).clear() }
    }

    @Test fun version_two_single_candidate_defaults_keep_the_legacy_single_resource_query() {
        val text = valid.replace("\"formatVersion\":1", "\"formatVersion\":2")
            .replace("{\"sound\":\"owned:sounds/cannon\"}", "{\"candidates\":[{\"sound\":\"owned:sounds/cannon\"}]}")
        for ((source, declaration) in listOf("sounds.json" to text,
                "sounds.hjson" to text.replaceFirst("{", "{\n# Own weighted schema\n"))) {
            val manifest = ResourceSoundManifest.read(declaration.toByteArray(), source)
            val sound = ResourceId.parse("owned:sounds/cannon")
            assertEquals(sound, manifest.events.getValue(ResourceId.parse("reforge:events/tank-fire")).sound)
            assertEquals("audio/cannon.ogg", manifest.sounds.getValue(sound).file)
            assertEquals(1, manifest.events.size)
        }
    }

    @Test fun json_and_hjson_map_a_gameplay_event_to_a_logical_sound_resource_and_not_a_path() {
        for ((source, text) in listOf("sounds.json" to valid, "sounds.hjson" to valid.replaceFirst("{", "{\n# Actual Hjson comment\n"))) {
            val manifest = ResourceSoundManifest.read(text.toByteArray(), source)
            val sound = ResourceId.parse("owned:sounds/cannon")
            assertEquals("audio/cannon.ogg", manifest.sounds.getValue(sound).file)
            assertEquals(sound, manifest.events.getValue(ResourceId.parse("reforge:events/tank-fire")).sound)
            assertEquals(setOf(sound), manifest.sounds.keys)
        }
    }

    @Test fun malformed_declarations_report_the_source_and_field_instead_of_becoming_missing_events() {
        val cases = listOf(
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1.0") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1e0") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":3") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"unknown\":true") to "unknown",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1") to "formatVersion",
            valid.replace("\"file\":\"audio/cannon.ogg\"", "\"file\":null") to "file",
            valid.replace("\"file\":\"audio/cannon.ogg\"", "\"file\":\"audio/cannon.ogg\",\"weight\":1") to "weight",
            valid.replace("\"sound\":\"owned:sounds/cannon\"", "\"sound\":\"audio/cannon.ogg\"") to "sound",
            valid.replace("\"sound\":\"owned:sounds/cannon\"", "\"sound\":false") to "sound",
            valid.replace("\"file\":\"audio/cannon.ogg\"", "\"path\":\"audio/cannon.ogg\"") to "path",
            valid.replace("\"sounds\":{", "\"sounds\":[],\"replacement\":{") to "replacement",
            valid.replace("\"owned:sounds/cannon\":{\"file\":\"audio/cannon.ogg\"}",
                "\"owned:sounds/cannon\":{\"file\":\"audio/cannon.ogg\"},\"owned:sounds/cannon\":{\"file\":\"other.wav\"}") to "Duplicate",
            valid.replace("\"reforge:events/tank-fire\":{\"sound\":\"owned:sounds/cannon\"}",
                "\"reforge:events/tank-fire\":{\"sound\":\"owned:sounds/cannon\"},\"reforge:events/tank-fire\":{\"sound\":\"owned:sounds/cannon\"}") to "Duplicate",
        ) + listOf("../outside.wav", "/absolute.wav", "C:/clip.wav", "audio//clip.wav", "audio/./clip.wav", "audio/../clip.wav",
            "audio/clip.mp3", "audio/clip.ogg\\u0000", "audio\\\\clip.wav").map { path -> valid.replace("audio/cannon.ogg", path) to "file" }
        for ((text, field) in cases) {
            val error = assertFailsWith<IllegalArgumentException> { ResourceSoundManifest.read(text.toByteArray(), "owned/sounds.json") }
            assertTrue(error.message.orEmpty().contains("owned/sounds.json"), error.message)
            assertTrue(error.message.orEmpty().contains(field), error.message)
        }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            ResourceSoundManifest.read(byteArrayOf(0xc3.toByte(), 0x28), "sounds.hjson")
        }.message.orEmpty().contains("encoding"))
        assertFailsWith<IllegalArgumentException> { ResourceSoundManifest.read(("# comment\n" + valid).toByteArray(), "sounds.json") }
        val manifest = ResourceSoundManifest.read(valid.toByteArray(), "sounds.json")
        assertFailsWith<UnsupportedOperationException> { (manifest.events as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (manifest.sounds as MutableMap).clear() }
    }
}
