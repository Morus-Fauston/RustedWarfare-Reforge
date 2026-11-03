package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Fixed native ground builder metadata. Replacements and arbitrary original unit files are outside this format. */
internal object BuilderContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "displayRadius", "movementType",
        "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration",
        "price", "buildSpeed", "image", "image_dead", "fogOfWarSightRange", "constructionTypes")

    private val numbers = fields - setOf("format", "name", "movementType", "image", "image_dead", "constructionTypes")
    private val schema = UnitContentSchema(mapOf("core" to fields), numbers,
        setOf("dont_load"), fields - numbers,
        nativeFormats = (1..6).map { "rw-reforge.native-builder.v$it" }.toSet())

    fun read(path: Path): UnitDefinition = readWithSources(path).definition

    fun readWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent = UnitSourceReader(schema, validatePath).load(path) { loaded ->
        definition(path, loaded.values.getValue("core"), validatePath)
    }

    private fun definition(path: Path, values: Map<String, String>, validatePath: (Path) -> Unit): UnitDefinition {
        fun required(key: String): String = requiredContentField(values[key], path, null, key,
            "Required builder field is absent or empty")
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
            validatePath(result)
            try { readPngMetadata(result, allowIndexed = true) } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        val mech = required("format") == "rw-reforge.native-builder.v5"
        val air = required("format") == "rw-reforge.native-builder.v6"
        val defense = required("format") == "rw-reforge.native-builder.v4"
        val economy = defense || required("format") == "rw-reforge.native-builder.v3"
        val construction = air || mech || economy || required("format") == "rw-reforge.native-builder.v2"
        val targets = if (mech || air) required("constructionTypes").split(',').also { targets ->
            val allowed = if (air) setOf("landFactory", "mechFactory", "airFactory") else setOf("landFactory", "mechFactory")
            if (targets.isEmpty() || targets.distinct().size != targets.size || targets.any { it !in allowed })
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "constructionTypes", reason = "Expected a nonempty unique subset of ${allowed.joinToString(",")}")
        } else if (defense) listOf("extractorT1", "landFactory", "fabricatorT1", "c_turret_t1", "laserDefence")
            else if (economy) listOf("extractorT1", "landFactory", "fabricatorT1") else listOf("landFactory")
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
