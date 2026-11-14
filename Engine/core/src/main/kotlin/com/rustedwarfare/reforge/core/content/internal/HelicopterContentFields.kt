package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Uses the shared unit syntax, inheritance and provenance for the single-rotor AIR subset. */
internal object HelicopterContentFields {
    private val movementFields = setOf("moveSlidingDir", "reverseSpeedPercentage", "targetHeight",
        "targetHeightDrift", "heightChangeRate", "startingHeightOffset")
    fun schema(base: UnitContentSchema, rotor: Boolean = true): UnitContentSchema {
        val shared = base.copy(
            fields = base.fields + ("movement" to (base.fields.getValue("movement") + movementFields)),
            numericFields = base.numericFields + movementFields)
        return if (rotor) shared.copy(
            fields = shared.fields + ("arm_1" to setOf("x", "y", "image_end", "drawOverBody", "spinRate")),
            numericFields = shared.numericFields + "spinRate",
            booleanFields = shared.booleanFields + "drawOverBody",
            textFields = shared.textFields + "image_end")
        else shared.copy(
            fields = shared.fields + ("movement" to (shared.fields.getValue("movement") + "landOnGround")),
            booleanFields = shared.booleanFields + "landOnGround")
    }

    private fun value(path: Path, loaded: UnitSourceReader.EffectiveSections, section: String, key: String): String =
        loaded.values[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required flight field")

    private fun number(path: Path, loaded: UnitSourceReader.EffectiveSections, section: String, key: String): Double =
        value(path, loaded, section, key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = "Expected a finite number")

    fun flight(path: Path, loaded: UnitSourceReader.EffectiveSections): FlightProfile {
        fun nonnegative(key: String): Double = number(path, loaded, "movement", key).also {
            if (it < 0.0) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", key,
                reason = "Expected a nonnegative flight dimension")
        }
        val targetHeight = nonnegative("targetHeight")
        val drift = nonnegative("targetHeightDrift")
        val heightRate = number(path, loaded, "movement", "heightChangeRate")
        if (heightRate < 0.0 && heightRate != -1.0)
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", "heightChangeRate",
                reason = "Expected automatic (-1) or a nonnegative height rate")
        val offset = nonnegative("startingHeightOffset")
        if (!(targetHeight + drift).isFinite()) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "movement", "targetHeightDrift",
            reason = "Target height plus drift must remain finite")
        return FlightProfile(targetHeight, drift, heightRate, offset,
            landWhenStationary = loaded.values["movement"]?.get("landOnGround") == "true")
    }

    fun rotor(path: Path, loaded: UnitSourceReader.EffectiveSections, validatePath: (Path) -> Unit): RotorDefinition {
        for (key in listOf("x", "y")) if (number(path, loaded, "arm_1", key) != 0.0)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "arm_1", key, reason = "The finite rotor must be at the body origin")
        if (value(path, loaded, "arm_1", "drawOverBody") != "true")
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "arm_1", "drawOverBody", reason = "The finite rotor must draw above the body")
        val reference = value(path, loaded, "arm_1", "image_end")
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
        return RotorDefinition(image, number(path, loaded, "arm_1", "spinRate"))
    }
}
