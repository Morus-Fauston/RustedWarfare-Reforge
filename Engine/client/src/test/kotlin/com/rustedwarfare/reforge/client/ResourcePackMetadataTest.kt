package com.rustedwarfare.reforge.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackMetadataTest {
    private val valid = """{"formatVersion":1,"requiresResourceFormat":1,"id":"reforge:base",
        "name":"基础素材","description":"Self-owned pictures.","icon":"reforge:units/c_tank/body"}"""

    @Test fun resource_capability_three_admits_weighted_sound_pack_metadata_in_both_author_formats() {
        val text = valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":3")
        for (source in listOf("weighted/pack.json", "weighted/pack.hjson")) {
            val metadata = ResourcePackMetadata.read(text.toByteArray(), source)
            assertEquals(1, metadata.formatVersion)
            assertEquals(3, metadata.requiresResourceFormat)
            assertEquals("reforge:base", metadata.id.value)
        }
    }

    @Test fun resource_capability_two_accepts_sound_packs_without_changing_the_metadata_schema() {
        val text = valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":2")
        for (source in listOf("sound/pack.json", "sound/pack.hjson")) {
            val metadata = ResourcePackMetadata.read(text.toByteArray(), source)
            assertEquals(1, metadata.formatVersion)
            assertEquals(2, metadata.requiresResourceFormat)
        }
    }

    @Test
    fun json_and_hjson_metadata_share_the_same_explicit_version_and_namespaced_identity() {
        val hjson = """
            {
              // Own format; this is not a Minecraft package.
              formatVersion: 1
              requiresResourceFormat: 1
              id: "reforge:base"
              name: "基础素材"
              description: "Self-owned pictures."
              icon: "reforge:units/c_tank/body"
            }
        """.trimIndent()
        for ((text, name) in listOf(valid to "owned/pack.json", hjson to "owned/pack.hjson")) {
            val metadata = ResourcePackMetadata.read(text.toByteArray(Charsets.UTF_8), name)
            assertEquals("reforge:base", metadata.id.value)
            assertEquals("基础素材", metadata.name)
            assertEquals("Self-owned pictures.", metadata.description)
            assertEquals("reforge:units/c_tank/body", metadata.icon.value)
            assertEquals(1, metadata.formatVersion); assertEquals(1, metadata.requiresResourceFormat)
        }
    }

    @Test
    fun invalid_members_types_versions_ids_and_utf8_point_to_the_metadata_source_and_field() {
        val cases = listOf(
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1.0") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1e0") to "formatVersion",
            valid.replace("\"formatVersion\":1", "\"formatVersion\":\"1\"") to "formatVersion",
            valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":null") to "requiresResourceFormat",
            valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":true") to "requiresResourceFormat",
            valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4") to "requiresResourceFormat",
            valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":2.0") to "requiresResourceFormat",
            valid.replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":2e0") to "requiresResourceFormat",
            valid.replace("\"name\":\"基础素材\"", "\"name\":\"\"") to "name",
            valid.replace("\"name\":\"基础素材\"", "\"name\":12") to "name",
            valid.replace("\"name\":\"基础素材\",", "") to "name",
            valid.replace("reforge:base", "Reforge:base") to "id",
            valid.replace("reforge:units/c_tank/body", "reforge:units/../body") to "icon",
            valid.dropLast(1) + ",\"priority\":3}" to "priority",
            valid.dropLast(1) + ",\"id\":\"reforge:base\"}" to "id",
        )
        for (source in listOf("owned/pack.json", "owned/pack.hjson")) for ((text, field) in cases) {
            val message = assertFailsWith<IllegalArgumentException>("$source: $text") {
                ResourcePackMetadata.read(text.toByteArray(Charsets.UTF_8), source)
            }.message.orEmpty()
            assertTrue(message.contains("RESOURCE_PACK_METADATA_INVALID") && message.contains(source) &&
                message.contains(field), "$field: $message")
        }
        assertTrue(assertFailsWith<IllegalArgumentException> {
            ResourcePackMetadata.read(byteArrayOf(0xc3.toByte(), 0x28), "owned/pack.json")
        }.message.orEmpty().contains("UTF-8"))
    }
}
