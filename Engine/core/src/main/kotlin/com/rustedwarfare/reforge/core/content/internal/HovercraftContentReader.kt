package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Native ar$11 landing craft; the transport lifecycle belongs to the shared simulation module. */
internal object HovercraftContentReader {
    private val schema = UnitContentSchema.native(
        setOf("format", "name", "maxHp", "radius", "price", "buildSpeed", "image", "image_dead"),
        setOf("rw-reforge.native-hovercraft.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(values[key], path, "core", key, "Required native landing craft field")
            if (required("name") != "hovercraft") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected native hovercraft")
            for ((key, expected) in mapOf("maxHp" to 450.0, "radius" to 15.0, "price" to 600.0, "buildSpeed" to .003)) {
                val number = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite native value")
                if (number != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected native value $expected")
            }
            fun image(key: String): Path = resolveReference(path, required(key), "graphics", key).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == 20 && png.height == 32) { "Expected 20x32 landing craft PNG" }
                } catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid native landing craft PNG: ${failure.message}")
                }
            }
            UnitDefinition("hovercraft", "NativeHovercraft", 15.0, 15.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.HOVER, .9, .03, .05, 1.4, .1, false, false,
                    hover = HoverProfile(3.0, 1.5, 4.0, .1)), emptySet(),
                durability = DurabilityProfile(450.0, image("image_dead")), build = BuildProfile(600L, .003f),
                transport = TransportProfile.NATIVE_HOVERCRAFT)
        }
}
