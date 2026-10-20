package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import kotlin.test.*

class UnitSightRangeTest {
    @Test fun av04_ini_sight_is_an_integer_defaulting_to_fifteen_and_preserves_nonpositive_values() {
        val directory = Files.createTempDirectory("reforge-sight-")
        val image = directory.resolve("owned.png")
        Files.write(image, byteArrayOf(0))
        val path = directory.resolve("owned.ini")
        val base = """
            [core]
            name: owned_sight
            class: CustomUnitMetadata
            radius: 1
            displayRadius: 1
            [graphics]
            total_frames: 1
            image: owned.png
            [movement]
            movementType: LAND
            moveSpeed: 1
            moveAccelerationSpeed: 1
            moveDecelerationSpeed: 1
            maxTurnSpeed: 1
            turnAcceleration: 1
            moveSlidingMode: false
            moveIgnoringBody: false
        """.trimIndent()
        try {
            for ((raw, expected) in listOf(null to 15, "1" to 1, "0" to 0, "-2" to -2)) {
                Files.writeString(path, base.replace("[graphics]", "${raw?.let { "fogOfWarSightRange: $it\n" } ?: ""}[graphics]"))
                assertEquals(expected, assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(path)).value.fogOfWarSightRange)
            }
            for (raw in listOf("1.5", "", "2147483648", "NaN")) {
                Files.writeString(path, base.replace("[graphics]", "fogOfWarSightRange: $raw\n[graphics]"))
                val error = assertIs<ContentResult.Failure>(ContentLoader.loadUnitVariant(path)).error
                assertEquals(ContentErrorCode.MALFORMED_SYNTAX, error.code)
                assertEquals(path.toAbsolutePath().toString(), error.file)
                assertEquals("core", error.section)
                assertEquals("fogOfWarSightRange", error.field)
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(image)
            Files.deleteIfExists(directory)
        }
    }
}
