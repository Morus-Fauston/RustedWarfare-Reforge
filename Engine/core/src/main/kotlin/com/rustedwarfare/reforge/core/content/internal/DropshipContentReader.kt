package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Native ar$19 air transport; passenger state remains owned by the shared transport module. */
internal object DropshipContentReader {
    private val schema = UnitContentSchema.native(
        setOf("format", "name", "maxHp", "radius", "price", "buildSpeed", "image", "image_dead", "image_shadow"),
        setOf("rw-reforge.native-dropship.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(values[key], path, "core", key, "Required native air transport field")
            if (required("name") != "dropship") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected native dropship")
            for ((key, expected) in mapOf("maxHp" to 500.0, "radius" to 20.0, "price" to 800.0, "buildSpeed" to .001)) {
                val number = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite native value")
                if (number != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected native value $expected")
            }
            fun image(key: String): Path = resolveReference(path, required(key), "graphics", key).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == 45 && png.height == 47) { "Expected 45x47 air transport PNG" }
                } catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid native air transport PNG: ${failure.message}")
                }
            }
            UnitDefinition("dropship", "NativeDropship", 20.0, 20.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.AIR, 2.3, .03, .05, 1.4, 0.0, false, false, .6), emptySet(),
                durability = DurabilityProfile(500.0, image("image_dead")), build = BuildProfile(800L, .001f),
                shadowImagePath = image("image_shadow"), transport = TransportProfile.NATIVE_DROPSHIP)
        }
}
