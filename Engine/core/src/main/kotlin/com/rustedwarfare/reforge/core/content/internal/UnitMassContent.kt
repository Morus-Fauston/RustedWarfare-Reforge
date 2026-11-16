package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Path

internal object UnitMassContent {
    fun read(path: Path, value: String?, movable: Boolean, originalIni: Boolean = false): Double? {
        if (value == null || originalIni && value == "IGNORE") {
            if (!originalIni && !movable) return null
            reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "core", "mass",
                reason = "The effective unit definition requires mass")
        }
        if (originalIni) {
            return value.toIntOrNull()?.toDouble()
                ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "mass", value,
                    "Expected a static integer mass")
        }
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "mass", value,
                "Expected a finite positive mass")
    }
}
