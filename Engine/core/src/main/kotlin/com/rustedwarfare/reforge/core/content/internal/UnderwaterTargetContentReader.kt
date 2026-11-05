package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Explicit owned evidence target on the common native syntax/source path. */
internal object UnderwaterTargetContentReader {
    private val schema = UnitContentSchema.native(setOf("format", "name", "maxHp", "radius", "height", "image", "image_dead"),
        setOf("rw-reforge.fixed-underwater-target.v1")).let {
        it.copy(numericFields = it.numericFields + "height", textFields = it.textFields - "height")
    }

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun value(key: String): String = requiredContentField(values[key], path, "core", key, "Required fixed target field")
            fun number(key: String, expected: Double): Double {
                val number = value(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite fixed target value")
                if (number != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected the finite fixed target value $expected")
                return number
            }
            if (value("name") != "navalUnderwaterTarget") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected navalUnderwaterTarget")
            fun image(key: String): Path {
                val image = resolveReference(path, value(key), "graphics", key)
                validatePath(image)
                try { readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true) }
                catch (failure: Exception) { reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key,
                    reason = "Invalid fixed target PNG: ${failure.message}") }
                return image
            }
            UnitDefinition("navalUnderwaterTarget", "OwnedFixedUnderwaterTarget", number("radius", 6.0), 6.0, 1,
                image("image"), 1.0, MovementProfile(MovementType.WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 0,
                durability = DurabilityProfile(number("maxHp", 1000.0), if (value("image_dead") == "NONE") null else image("image_dead")),
                fixedUnderwaterTarget = FixedUnderwaterTargetProfile(number("height", -8.0)))
        }
}
