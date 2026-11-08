package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Four-legged OVER_CLIFF movement slice on the existing shared syntax and provenance path. */
internal object HeavyMechContentFields {
    fun schema(base: UnitContentSchema): UnitContentSchema {
        val mech = MechContentFields.schema(base)
        val leg = mech.fields.getValue("leg_1") + setOf("favourOppositeSideNeighbours", "dust_effect")
        return mech.copy(fields = mech.fields + mapOf(
            "leg_1" to leg, "leg_2" to leg, "leg_3" to leg, "leg_4" to leg,
            "attack" to (mech.fields.getValue("attack") + "turretRotateWithBody"),
            "movement" to (mech.fields.getValue("movement") + "moveSlidingDir"),
            "turret_1" to (mech.fields.getValue("turret_1") + setOf("image", "invisible", "image_drawOffsetX", "image_drawOffsetY")),
            "projectile_1" to (mech.fields.getValue("projectile_1") + setOf("areaDamage", "areaRadius", "autoTargetingOnDeadTarget"))),
            numericFields = mech.numericFields + setOf("moveSlidingDir", "image_drawOffsetX", "image_drawOffsetY", "areaDamage", "areaRadius"),
            booleanFields = mech.booleanFields + setOf("turretRotateWithBody", "invisible", "autoTargetingOnDeadTarget", "favourOppositeSideNeighbours", "dust_effect"),
            timeFieldLocations = mech.timeFieldLocations + (1..4).map { "leg_$it" to "moveWarmUp" })
    }

    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections, validatePath: (Path) -> Unit): MechDefinition {
        fun bad(section: String, key: String, code: ContentErrorCode, reason: String): Nothing {
            val origin = loaded.origins[section to key]
            reject(code, origin?.path ?: path, origin?.section ?: section, origin?.field ?: key, reason = reason)
        }
        fun value(section: String, key: String): String = loaded.values[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: bad(section, key, ContentErrorCode.MISSING_REQUIRED_FIELD, "Required heavy mech field")
        fun number(section: String, key: String): Double = value(section, key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: bad(section, key, ContentErrorCode.MALFORMED_SYNTAX, "Expected a finite number")
        fun fixed(section: String, key: String, expected: String) {
            if (value(section, key) != expected) bad(section, key, ContentErrorCode.UNSUPPORTED_FEATURE,
                "Expected $expected in this finite four-legged profile")
        }
        fixed("graphics", "lock_body_rotation_with_main_turret", "false")
        fixed("graphics", "lock_leg_rotation_with_main_turret", "false")
        fixed("attack", "turretRotateWithBody", "true")
        val legs = (1..4).map { index ->
            val section = "leg_$index"
            fun image(key: String): Path {
                val image = resolveReference(path, value(section, key), section, key)
                validatePath(image)
                try { readPngMetadata(image, allowIndexed = true, allowGrayAlpha = true) }
                catch (failure: Exception) { bad(section, key, ContentErrorCode.BAD_REFERENCE, "Invalid leg PNG: ${failure.message}") }
                return image
            }
            val warm = loaded.values[section]?.get("moveWarmUp")?.let { text ->
                (if (text.endsWith('s')) text.dropLast(1).toDoubleOrNull()?.times(60.0) else text.toDoubleOrNull())
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: bad(section, "moveWarmUp", ContentErrorCode.MALFORMED_SYNTAX, "Expected nonnegative ticks or seconds")
            } ?: 0.0
            fixed(section, "favourOppositeSideNeighbours", "true")
            fixed(section, "dust_effect", "false")
            MechLegDefinition(number(section, "x"), number(section, "y"), number(section, "attach_x"), number(section, "attach_y"),
                number(section, "moveSpeed"), warm, number(section, "rotateSpeed"), number(section, "heightSpeed"),
                number(section, "holdDisMin"), number(section, "holdDisMax"), number(section, "estimatingPositionMultiplier"),
                image("image_leg"), image("image_foot"), number(section, "resetAngle"), favourOppositeSideNeighbours = true)
        }
        return MechDefinition(MechBodyDefinition(1.0, .5, 0.0, 180.0, 0.0, 0.0, 0.0, 0), legs, MechKind.FOUR_LEGGED).also {
            if (!it.isValid()) bad("leg_1", "x", ContentErrorCode.MALFORMED_SYNTAX, "Invalid finite four-legged metadata or inconsistent shared images")
        }
    }
}
