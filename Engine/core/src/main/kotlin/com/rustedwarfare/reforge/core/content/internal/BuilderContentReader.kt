package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Fixed native ground builder metadata. Replacements and arbitrary original unit files are outside this format. */
internal object BuilderContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "displayRadius", "movementType",
        "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration",
        "price", "buildSpeed", "image", "image_dead", "fogOfWarSightRange", "constructionTypes")

    fun read(path: Path): UnitDefinition {
        val values = linkedMapOf<String, String>()
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf('=')
            if (separator <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path,
                reason = "Expected builder field=value at line ${index + 1}")
            val key = line.substring(0, separator).trim()
            if (key !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = key,
                reason = "Unsupported builder field")
            if (values.putIfAbsent(key, line.substring(separator + 1).trim()) != null)
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Duplicate builder field")
        }
        fun required(key: String): String = values[key]?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, field = key, reason = "Required builder field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only $expected is supported")
        }
        fun number(key: String, expected: Double): Double {
            val value = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite number")
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only native value $expected is supported")
            return value
        }
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            try { TurretContentReader.validatePng(result, allowIndexed = true) } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        val economy = required("format") == "rw-reforge.native-builder.v3"
        val construction = economy || required("format") == "rw-reforge.native-builder.v2"
        val targets = if (economy) listOf("extractorT1", "landFactory", "fabricatorT1") else listOf("landFactory")
        if (construction) only("constructionTypes", targets.joinToString(","))
        else {
            only("format", "rw-reforge.native-builder.v1")
            if ("constructionTypes" in values) reject(ContentErrorCode.UNSUPPORTED_FIELD, path,
                field = "constructionTypes", reason = "Construction requires the v2 format")
        }
        only("name", "builder")
        only("movementType", "LAND")
        return UnitDefinition("builder", "NativeBuilderFixture", number("radius", 10.0), number("displayRadius", 12.0),
            1, image("image"), 1.0, MovementProfile(MovementType.LAND, number("moveSpeed", .8),
                number("moveAccelerationSpeed", .04), number("moveDecelerationSpeed", .1),
                number("maxTurnSpeed", 3.8), number("turnAcceleration", .35), false, false), emptySet(),
            number("fogOfWarSightRange", 15.0).toInt(),
            durability = DurabilityProfile(number("maxHp", 170.0), image("image_dead")),
            build = BuildProfile(number("price", 500.0).toLong(), number("buildSpeed", .002).toFloat()),
            builder = if (construction) BuilderProfile(targets) else null)
    }
}
