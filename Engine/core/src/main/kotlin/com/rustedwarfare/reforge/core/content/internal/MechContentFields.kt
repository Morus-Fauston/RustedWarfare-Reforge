package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.io.IOException

/** Finite gait metadata on the same syntax/provenance path as combat declarations. */
internal object MechContentFields {
    private val legFields = setOf("x", "y", "attach_x", "attach_y", "moveSpeed", "moveWarmUp",
        "rotateSpeed", "heightSpeed", "resetAngle", "holdDisMin", "holdDisMax",
        "estimatingPositionMultiplier", "image_leg", "image_foot")
    private val sweepFields = setOf("idleSweepAngle", "idleSweepSpeed", "idleSweepDelay",
        "idleSweepAddRandomDelay", "idleSweepCondition")

    fun schema(base: UnitContentSchema): UnitContentSchema = base.copy(
        fields = base.fields + mapOf(
            "graphics" to (base.fields.getValue("graphics") + setOf("lock_body_rotation_with_main_turret", "lock_leg_rotation_with_main_turret")),
            "movement" to (base.fields.getValue("movement") + "reverseSpeedPercentage"),
            "turret_1" to (base.fields.getValue("turret_1") + sweepFields),
            "projectile_1" to (base.fields.getValue("projectile_1") + setOf("targetSpeed", "targetSpeedAcceleration")),
            "leg_1" to legFields, "leg_2" to legFields),
        numericFields = base.numericFields + (legFields - setOf("moveWarmUp", "image_leg", "image_foot")) +
            (sweepFields - "idleSweepCondition") + setOf("reverseSpeedPercentage", "targetSpeed", "targetSpeedAcceleration"),
        booleanFields = base.booleanFields + setOf("lock_body_rotation_with_main_turret", "lock_leg_rotation_with_main_turret"),
        textFields = base.textFields + setOf("image_leg", "image_foot", "idleSweepCondition"),
        timeFieldLocations = base.timeFieldLocations + setOf("leg_1" to "moveWarmUp", "leg_2" to "moveWarmUp"))

    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections, validatePath: (Path) -> Unit): MechDefinition {
        val sections = loaded.values
        fun bad(section: String, key: String, reason: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = reason)
        fun value(section: String, key: String): String = sections[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required mech field")
        fun number(section: String, key: String): Double = value(section, key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: bad(section, key, "Expected a finite number")
        fun fixed(section: String, key: String, expected: String) {
            if (value(section, key) != expected)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key, reason = "Expected $expected in this finite mech profile")
        }
        fixed("graphics", "lock_body_rotation_with_main_turret", "true")
        fixed("graphics", "lock_leg_rotation_with_main_turret", "false")
        fixed("turret_mainBody", "idleSweepCondition", "if not self.isMoving()")
        if (sections.keys.filter { it.startsWith("turret_") } != listOf("turret_mainBody"))
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, reason = "The two-legged representative requires one mainBody weapon")
        val random = number("turret_mainBody", "idleSweepAddRandomDelay")
        if (random < 0 || random > Int.MAX_VALUE || random.toInt().toDouble() != random)
            bad("turret_mainBody", "idleSweepAddRandomDelay", "Expected a nonnegative integer")
        val body = MechBodyDefinition(number("turret_mainBody", "turnSpeed"),
            number("turret_mainBody", "turnSpeedAcceleration"), number("turret_mainBody", "idleDir"), 180.0,
            number("turret_mainBody", "idleSweepAngle"), number("turret_mainBody", "idleSweepSpeed"),
            number("turret_mainBody", "idleSweepDelay"), random.toInt())
        val legs = (1..2).map { index ->
            val s = "leg_$index"
            fun image(key: String): Path {
                val reference = value(s, key)
                return try {
                    validatePath(path.parent.resolve(reference).toAbsolutePath().normalize())
                    resolveReference(path, reference, s, key)
                } catch (failure: InvalidPathException) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, s, key, reference, "Invalid mech image reference path")
                } catch (failure: IOException) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, s, key, reference, failure.message ?: "Cannot resolve mech image")
                } catch (failure: SecurityException) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, s, key, reference, "Mech image access denied")
                }
            }
            val time = value(s, "moveWarmUp")
            val warmup = (if (time.endsWith('s')) time.dropLast(1).toDoubleOrNull()?.times(60.0) else time.toDoubleOrNull())
                ?.takeIf { it.isFinite() && it >= 0 } ?: bad(s, "moveWarmUp", "Expected finite nonnegative ticks or seconds")
            MechLegDefinition(number(s, "x"), number(s, "y"), number(s, "attach_x"), number(s, "attach_y"),
                number(s, "moveSpeed"), warmup, number(s, "rotateSpeed"), number(s, "heightSpeed"),
                number(s, "holdDisMin"), number(s, "holdDisMax"), number(s, "estimatingPositionMultiplier"),
                image("image_leg"), image("image_foot"), number(s, "resetAngle"))
        }
        for ((field, different) in listOf("image_leg" to (legs[0].legImage != legs[1].legImage),
            "image_foot" to (legs[0].footImage != legs[1].footImage))) if (different)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "leg_2", field,
                reason = "The finite shared LEG/FOOT resource profile requires the same image on both legs")
        return MechDefinition(body, legs).also {
            if (!it.isValid()) reject(ContentErrorCode.MALFORMED_SYNTAX, path, reason = "Invalid finite two-legged mech profile")
        }
    }
}
