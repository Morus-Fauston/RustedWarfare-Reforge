package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Fixed native laserDefence metadata, never a general original-unit interpreter. */
internal object PointDefenseContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "image", "image_dead", "fogOfWarSightRange")

    fun read(path: Path, enableConstruction: Boolean = false): UnitDefinition {
        val values = readNativeFields(path, fields, "defense")
        fun required(key: String): String = requiredContentField(values[key], path, null, key,
            "Required defense field is absent or empty")
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
            try { readPngMetadata(result, allowIndexed = true) } catch (failure: Exception) {
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
            building = BuildingProfile(BuildingFootprint(0, 0, 1, 1),
                anchor = if (enableConstruction) FootprintAnchor(20f, 20f) else null),
            directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING, pointDefense = PointDefenseProfile(),
            construction = if (enableConstruction) ConstructionProfile(1200L, .001f) else null)
    }
}
