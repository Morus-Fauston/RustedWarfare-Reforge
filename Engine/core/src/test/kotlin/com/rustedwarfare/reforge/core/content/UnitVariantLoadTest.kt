package com.rustedwarfare.reforge.core.content

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun inputs(): Path {
    val configured = checkNotNull(System.getProperty("p001.inputs.dir")) {
        "Missing p001.inputs.dir; run the core Gradle test task"
    }
    return Path.of(configured).also { check(Files.isDirectory(it)) { "Missing input directory: $it" } }
}

internal fun unitPath(): Path = inputs().resolve("units/weaponless_tank.ini")
internal fun mapPath(): Path = inputs().resolve("maps/p001-controlled.tmx")
internal fun unitImageReference(): String = Files.readAllLines(unitPath())
    .single { it.trim().startsWith("image:") }.substringAfter(':').trim()
internal fun unitImagePath(): Path = unitPath().parent.resolve(unitImageReference()).toAbsolutePath().normalize()
internal fun tilesetImageReference(name: String): String = Regex("<image[^>]*source=\"([^\"]+)\"")
    .find(Files.readString(inputs().resolve("tilesets/$name.tsx")))!!.groupValues[1]
internal fun tilesetImagePath(name: String): Path = inputs().resolve("tilesets")
    .resolve(tilesetImageReference(name)).toAbsolutePath().normalize()

class UnitVariantLoadTest {
    @Test
    fun loadsActualWeaponlessVariantAndMovementFields() {
        val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(unitPath())).value
        assertEquals("p001_weaponless_tank", unit.id)
        assertEquals("CustomUnitMetadata", unit.className)
        assertEquals(11.0, unit.radius)
        assertEquals(11.0, unit.displayRadius)
        assertEquals(3, unit.totalFrames)
        assertEquals(0.8, unit.imageScale)
        assertTrue(unit.imagePath.isAbsolute && Files.isRegularFile(unit.imagePath))
        assertEquals(MovementType.LAND, unit.movement.type)
        assertEquals(1.1, unit.movement.moveSpeed)
        assertEquals(0.07, unit.movement.moveAccelerationSpeed)
        assertEquals(0.17, unit.movement.moveDecelerationSpeed)
        assertEquals(4.1, unit.movement.maxTurnSpeed)
        assertEquals(0.25, unit.movement.turnAcceleration)
        assertEquals(false, unit.movement.slidingMode)
        assertEquals(false, unit.movement.ignoringBody)
        assertEquals(setOf("price", "maxHp", "mass", "techLevel", "buildSpeed", "isBio",
            "softCollisionOnAll", "image_shadow", "shadowOffsetX", "shadowOffsetY",
            "animation_moving_start", "animation_moving_end", "animation_moving_speed", "dustEffect"),
            unit.retainedUnconsumed)
    }

    @Test
    fun loadsDifferentFileNameAndParametersWithDefaultImageScale() {
        val path = Files.createTempFile("p001-unit-", ".ini")
        try {
            val image = unitImagePath()
            val text = Files.readString(unitPath()).replace("moveSpeed: 1.1", "moveSpeed : 2.0")
                .replace("imageScale: 0.8", "")
                .replace(unitImageReference(), image.toString())
            Files.writeString(path, text)
            val unit = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadUnitVariant(path)).value
            assertEquals(2.0, unit.movement.moveSpeed)
            assertEquals(1.0, unit.imageScale)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
