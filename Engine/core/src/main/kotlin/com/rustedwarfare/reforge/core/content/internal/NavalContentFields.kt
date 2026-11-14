package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Naval additions on the common unit syntax and provenance path. */
internal object NavalContentFields {
    fun schema(base: UnitContentSchema): UnitContentSchema = base.copy(
        fields = base.fields + mapOf(
            "core" to (base.fields.getValue("core") + "transportSlotsNeeded"),
            "movement" to (base.fields.getValue("movement") + "reverseSpeedPercentage"),
            "turret_1" to (base.fields.getValue("turret_1") + setOf("idleDirReversing", "invisible", "image")),
            "projectile_1" to (base.fields.getValue("projectile_1") + setOf("targetGround", "areaDamage", "areaRadius",
                "targetSpeed", "targetSpeedAcceleration", "deflectionPower", "ballistic", "ballistic_delaymove_height", "ballistic_height"))),
        numericFields = base.numericFields + setOf("transportSlotsNeeded", "reverseSpeedPercentage", "idleDirReversing",
            "areaDamage", "areaRadius", "targetSpeed", "targetSpeedAcceleration", "deflectionPower",
            "ballistic_delaymove_height", "ballistic_height", "shoot_sound_vol"),
        booleanFields = base.booleanFields + setOf("invisible", "targetGround", "ballistic"),
        textFields = base.textFields + "shoot_sound")

    fun projectile(path: Path, loaded: UnitSourceReader.EffectiveSections, section: String): ProjectileProfile {
        val values = loaded.values.getValue(section)
        fun bad(key: String, reason: String): Nothing = reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = reason)
        fun number(key: String, default: Double? = null): Double = values[key]?.let { value ->
            value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: bad(key, "Expected finite nonnegative naval projectile value")
        } ?: default ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required naval projectile value")
        fun bool(key: String): Boolean = when (values[key]) {
            null, "false" -> false
            "true" -> true
            else -> bad(key, "Expected a boolean")
        }
        val ground = bool("targetGround")
        val ballistic = bool("ballistic")
        val retarget = bool("autoTargetingOnDeadTarget")
        if (ground && retarget) bad("autoTargetingOnDeadTarget", "A fixed ground snapshot cannot select a replacement target")
        if (ground && ballistic) bad("ballistic", "Combined ground snapshot and custom ballistic is outside the finite naval profile")
        val direct = number("directDamage", 0.0)
        if (ground && direct != 0.0) bad("directDamage", "The finite naval ground snapshot has area damage only")
        val area = if (ground) GroundProjectileProfile(number("areaDamage").toFloat(), number("areaRadius").toFloat(), 0f, 0f, 0f) else null
        if (!ground && ("areaDamage" in values || "areaRadius" in values)) bad("areaDamage", "Naval area fields require targetGround")
        val acceleration = values["targetSpeed"]?.let {
            LinearProjectileAcceleration(number("targetSpeed"), number("targetSpeedAcceleration", .1))
        }
        if (acceleration == null && "targetSpeedAcceleration" in values) bad("targetSpeedAcceleration", "Acceleration requires a target speed")
        val height = if (ballistic) CustomBallisticProfile(number("ballistic_delaymove_height").toFloat(), number("ballistic_height").toFloat()) else null
        if (!ballistic && ("ballistic_delaymove_height" in values || "ballistic_height" in values)) bad("ballistic", "Height fields require ballistic")
        val nonDeflectable = values["deflectionPower"]?.let {
            if (it.toDoubleOrNull() != -1.0) bad("deflectionPower", "The finite torpedo uses -1")
            true
        } ?: false
        val life = number("life")
        val speed = number("speed")
        if (life <= 0.0 || speed <= 0.0 || acceleration?.let { it.targetSpeed <= 0.0 || it.acceleration <= 0.0 } == true ||
            area?.let { !it.areaDamage.isFinite() || !it.areaRadius.isFinite() || it.areaRadius <= 0f } == true ||
            height?.let { !it.delayMoveHeight.isFinite() || !it.apex.isFinite() || it.apex < it.delayMoveHeight } == true)
            bad("life", "Expected positive finite derived naval projectile dimensions")
        return ProjectileProfile(direct, life, speed, nonDeflectable = nonDeflectable,
            ground = area, acceleration = acceleration, ballistic = height, retargetOnDeath = retarget)
    }

    fun sound(path: Path, values: Map<String, String>, section: String): FireSoundProfile? {
        val name = values["shoot_sound"]
        if (name == null) {
            if ("shoot_sound_vol" in values) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, "shoot_sound_vol", reason = "Volume requires a sound")
            return null
        }
        val sound = when (name) {
            "large_gun_fire2" -> FireSoundProfile.NAVAL_MAIN
            "missile_fire" -> FireSoundProfile.NAVAL_MISSILE
            else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, "shoot_sound", reason = "Unsupported naval firing event")
        }
        if (values["shoot_sound_vol"]?.toFloatOrNull() != sound.volume)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, "shoot_sound_vol", reason = "Expected the finite source firing volume")
        return sound
    }
}
