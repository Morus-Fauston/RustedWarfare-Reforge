package com.rustedwarfare.reforge.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceIdTest {
    @Test
    fun host_paths_relative_segments_uppercase_and_incomplete_addresses_are_rejected() {
        for (value in listOf("", "tank", ":tank", "reforge:", "reforge::tank", "Reforge:tank", "reforge:Tank",
            "reforge:/tank", "reforge:tank/", "reforge:tank//body", "reforge:units/../tank", "reforge:./tank",
            "C:\\pictures\\tank.png", "C:/pictures/tank.png", "reforge:units\\tank", "reforge:units/tank body")) {
            assertTrue(assertFailsWith<IllegalArgumentException>(value) { ResourceId.parse(value) }
                .message.orEmpty().contains("RESOURCE_ID_INVALID"))
        }
    }

    @Test
    fun logical_resource_addresses_keep_namespace_and_path_separate_from_host_paths() {
        val id = ResourceId.parse("reforge:units/c_tank/body")
        assertEquals("reforge", id.namespace)
        assertEquals("units/c_tank/body", id.path)
        assertEquals("reforge:units/c_tank/body", id.value)
        assertEquals(id, ResourceId.parse("reforge:units/c_tank/body"))
        assertEquals("my-pack.v1:terrain/controlled-strip-2", ResourceId.parse("my-pack.v1:terrain/controlled-strip-2").value)
    }
}
