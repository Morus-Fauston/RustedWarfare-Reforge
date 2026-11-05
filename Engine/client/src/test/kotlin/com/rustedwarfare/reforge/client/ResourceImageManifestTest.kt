package com.rustedwarfare.reforge.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceImageManifestTest {
    private val id = "reforge:units/c_tank/body"
    private val entry = """{"file":"images/tank.png","frameWidth":20,"frameHeight":18,"totalFrames":3}"""
    private fun document(value: String = entry) = """{"formatVersion":1,"images":{"$id":$value}}"""

    @Test
    fun json_and_hjson_declare_immutable_logical_layouts_without_resolving_a_host_path() {
        val json = ResourceImageManifest.read(document().toByteArray(), "owned/images.json")
        val hjson = ResourceImageManifest.read("""
            formatVersion: 1
            images: {
              "$id": {
                file: images/tank.png
                frameWidth: 20
                frameHeight: 18
                totalFrames: 3
              }
            }
        """.trimIndent().toByteArray(), "owned/images.hjson")
        assertEquals(json.images, hjson.images)
        val image = json.images.getValue(ResourceId.parse(id))
        assertEquals("images/tank.png", image.file)
        assertEquals(20, image.frameWidth)
        assertEquals(18, image.frameHeight)
        assertEquals(3, image.totalFrames)
        assertFailsWith<UnsupportedOperationException> { (json.images as MutableMap).clear() }
    }

    @Test
    fun invalid_versions_fields_addresses_paths_and_lexical_layout_numbers_name_the_source() {
        val bad = mutableListOf(
            "[]", "{}", """{"formatVersion":1,"images":[]}""",
            document().replace("\"formatVersion\":1", "\"formatVersion\":2"),
            document().replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            document().replace("\"formatVersion\":1", "\"formatVersion\":1e0"),
            document().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            document().replace("\"images\":", "\"unknown\":0,\"images\":"),
            document().replace(id, "reforge:units/../body"),
            """{"formatVersion":1,"images":{"$id":$entry,"$id":$entry}}""",
            document("null"), document(entry.replace("\"file\":", "\"extra\":0,\"file\":")),
            document(entry.replace("\"file\":", "\"file\":\"other.png\",\"file\":")),
            document(entry.replace(",\"totalFrames\":3", "")),
            document(entry.replace("\"images/tank.png\"", "false")),
        )
        for (value in listOf("0", "-1", "3.0", "3e0", "\"3\"", "true", "null", "2147483648"))
            bad += document(entry.replace("\"totalFrames\":3", "\"totalFrames\":$value"))
        for (path in listOf("../tank.png", "/tank.png", "C:/tank.png", "images//tank.png", "./tank.png", "images/../tank.png", "images/./tank.png", "not-an-image.txt"))
            bad += document(entry.replace("images/tank.png", path))
        bad += document(entry.replace("images/tank.png", "images\\\\tank.png"))
        for (extension in listOf("json", "hjson")) for (text in bad) {
            val source = "owned/images.$extension"
            val failure = assertFailsWith<IllegalArgumentException>(text) { ResourceImageManifest.read(text.toByteArray(), source) }
            assertTrue(failure.message.orEmpty().contains("RESOURCE_PACK_IMAGES_INVALID"), failure.message)
            assertTrue(failure.message.orEmpty().contains(source), failure.message)
        }
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        assertTrue(assertFailsWith<IllegalArgumentException> { ResourceImageManifest.read(invalidUtf8, "owned/images.json") }
            .message.orEmpty().contains("UTF-8"))
        assertFailsWith<IllegalArgumentException> { ResourceImageManifest.read(document().toByteArray(), "images.ini") }
    }
}
