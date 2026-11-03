package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Uses the shared unit syntax, inheritance and provenance for the single-rotor AIR subset. */
internal object HelicopterContentFields {
    private val movementFields = setOf("moveSlidingDir", "reverseSpeedPercentage", "targetHeight",
        "targetHeightDrift", "heightChangeRate", "startingHeightOffset")
    fun schema(base: UnitContentSchema): UnitContentSchema = base.copy(
        fields = base.fields + mapOf(
            "movement" to (base.fields.getValue("movement") + movementFields),
            "arm_1" to setOf("x", "y", "image_end", "drawOverBody", "spinRate")),
        numericFields = base.numericFields + movementFields + "spinRate",
        booleanFields = base.booleanFields + "drawOverBody",
        textFields = base.textFields + "image_end")

    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections, validatePath: (Path) -> Unit): Pair<FlightProfile, RotorDefinition> {
        fun value(section: String, key: String): String = loaded.values[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required helicopter field")
        fun number(section: String, key: String): Double = value(section, key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = "Expected a finite number")
        for (key in listOf("x", "y")) if (number("arm_1", key) != 0.0)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "arm_1", key, reason = "The finite rotor must be at the body origin")
        if (value("arm_1", "drawOverBody") != "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "arm_1", "drawOverBody", reason = "The finite rotor must draw above the body")
        fun nonnegative(key: String): Double = number("movement", key).also {
            if (it < 0.0) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", key,
                reason = "Expected a nonnegative flight dimension")
        }
        val targetHeight = nonnegative("targetHeight")
        val drift = nonnegative("targetHeightDrift")
        val heightRate = number("movement", "heightChangeRate")
        if (heightRate < 0.0 && heightRate != -1.0)
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", "heightChangeRate",
                reason = "Expected automatic (-1) or a nonnegative height rate")
        val offset = nonnegative("startingHeightOffset")
        if (!(targetHeight + drift).isFinite()) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", "targetHeightDrift",
            reason = "Target height plus drift must remain finite")
        val flight = FlightProfile(targetHeight, drift, heightRate, offset)
        val reference = value("arm_1", "image_end")
        val image = try {
            validatePath(path.parent.resolve(reference).toAbsolutePath().normalize())
            resolveReference(path, reference, "arm_1", "image_end")
        } catch (failure: ContentReadException) {
            reject(failure.error.code, path, "arm_1", "image_end", failure.error.objectRef ?: reference, failure.error.reason)
        } catch (failure: java.nio.file.InvalidPathException) {
            reject(ContentErrorCode.BAD_REFERENCE, path, "arm_1", "image_end", reference, "Invalid rotor image path")
        } catch (failure: java.io.IOException) {
            reject(ContentErrorCode.BAD_REFERENCE, path, "arm_1", "image_end", reference, failure.message ?: "Cannot resolve rotor image")
        }
        return flight to RotorDefinition(image, number("arm_1", "spinRate"))
    }
}
