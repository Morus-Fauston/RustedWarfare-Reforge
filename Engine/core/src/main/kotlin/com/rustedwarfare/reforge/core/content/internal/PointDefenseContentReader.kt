package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Fixed native laserDefence metadata, never a general original-unit interpreter. */
internal object PointDefenseContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "image", "image_dead", "fogOfWarSightRange")

    fun read(path: Path): UnitDefinition {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                reason = "Expected defense field=value at line ${index + 1}")
            val key = line.substring(0, separator).trim()
            if (key !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key,
                reason = "Unsupported defense field")
            if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate defense field")
        }
        fun required(key: String): String = values[key]?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, field = key, reason = "Required defense field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only $expected is supported")
        }
        fun fixedNumber(key: String, expected: Double): Double {
            val number = required(key).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite positive number")
            if (number != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only native value $expected is supported")
            return number
        }
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            try { TurretContentReader.validatePng(result, allowIndexed = true) } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        only("format", "rw-reforge.native-laser-defence.v1")
        only("name", "laserDefence")
        only("movementType", "NONE")
        only("footprint", "0,0,1,1")
        val sight = required("fogOfWarSightRange").toIntOrNull()?.takeIf { it >= 0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = "fogOfWarSightRange", reason = "Expected nonnegative integer")
        if (sight != 15) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "fogOfWarSightRange",
            reason = "Only native sight 15 is supported")
        val radius = fixedNumber("radius", 19.0)
        return UnitDefinition("laserDefence", "NativeLaserDefenceFixture", radius, radius, 2, image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), sight,
            durability = DurabilityProfile(fixedNumber("maxHp", 500.0), image("image_dead")),
            building = BuildingProfile(BuildingFootprint(0, 0, 1, 1)),
            directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING, pointDefense = PointDefenseProfile())
    }
}
