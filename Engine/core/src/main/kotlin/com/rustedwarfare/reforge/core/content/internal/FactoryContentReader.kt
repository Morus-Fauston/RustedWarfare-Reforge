package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Explicit native landFactory fixture, not an original INI or a general building loader. */
internal object FactoryContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "productionUnit", "image", "image_back", "image_dead", "fogOfWarSightRange")

    fun read(path: Path): UnitDefinition {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                reason = "Expected factory field=value at line ${index + 1}")
            val key = line.substring(0, separator).trim()
            if (key !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key,
                reason = "Unsupported factory field")
            if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate factory field")
        }
        fun required(key: String): String = values[key]?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, field = key, reason = "Required factory field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only $expected is supported")
        }
        fun positive(key: String): Double = required(key).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite positive number")
        only("format", "rw-reforge.native-land-factory.v1")
        only("name", "landFactory")
        only("movementType", "NONE")
        only("productionUnit", "c_tank")
        only("footprint", "-1,-1,1,1")
        val sight = required("fogOfWarSightRange").toIntOrNull()?.takeIf { it >= 0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = "fogOfWarSightRange", reason = "Expected nonnegative integer")
        val radius = positive("radius")
        return UnitDefinition("landFactory", "NativeLandFactoryFixture", radius, radius, 1,
            resolveReference(path, required("image"), "graphics", "image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), sight,
            durability = DurabilityProfile(positive("maxHp"), resolveReference(path, required("image_dead"), "graphics", "image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1),
                resolveReference(path, required("image_back"), "graphics", "image_back")),
            production = ProductionProfile("c_tank"), directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING)
    }
}
