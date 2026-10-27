package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Explicit native landFactory fixture, not an original INI or a general building loader. */
internal object FactoryContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "productionUnit", "image", "image_back", "image_dead", "fogOfWarSightRange",
        "productionUnits", "placementFootprint", "price", "buildSpeed")

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
        val construction = required("format") == "rw-reforge.native-land-factory.v2"
        val newFields = setOf("productionUnits", "placementFootprint", "price", "buildSpeed")
        if (construction) {
            only("productionUnits", "builder,c_tank")
            only("placementFootprint", "-1,-1,1,3")
            only("price", "700")
            only("buildSpeed", "0.001")
            only("maxHp", "1200")
            only("radius", "30")
            only("fogOfWarSightRange", "15")
            if ("productionUnit" in values) reject(ContentErrorCode.UNSUPPORTED_FIELD, path,
                field = "productionUnit", reason = "The v2 format declares productionUnits")
        } else {
            only("format", "rw-reforge.native-land-factory.v1")
            only("productionUnit", "c_tank")
            values.keys.firstOrNull { it in newFields }?.let { reject(ContentErrorCode.UNSUPPORTED_FIELD, path,
                field = it, reason = "Construction requires the v2 format") }
        }
        only("name", "landFactory")
        only("movementType", "NONE")
        only("footprint", "-1,-1,1,1")
        val sight = required("fogOfWarSightRange").toIntOrNull()?.takeIf { it >= 0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = "fogOfWarSightRange", reason = "Expected nonnegative integer")
        val radius = positive("radius")
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            if (construction) try {
                TurretContentReader.validatePng(result, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                java.io.DataInputStream(Files.newInputStream(result)).use { input ->
                    input.readLong(); input.readInt(); input.readInt()
                    require(input.readInt() == 50 && input.readInt() == if (key == "image_dead") 69 else 74) {
                        "Expected native factory PNG dimensions"
                    }
                }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        return UnitDefinition("landFactory", "NativeLandFactoryFixture", radius, radius, 1,
            image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), sight,
            durability = DurabilityProfile(positive("maxHp"), image("image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1),
                image("image_back"), placementFootprint = if (construction) BuildingFootprint(-1, -1, 1, 3)
                    else BuildingFootprint(-1, -1, 1, 1)),
            production = ProductionProfile(if (construction) listOf("builder", "c_tank") else listOf("c_tank")),
            build = if (construction) BuildProfile(700L, .001f) else null,
            construction = if (construction) ConstructionProfile(700L, .001f) else null,
            directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING)
    }
}
