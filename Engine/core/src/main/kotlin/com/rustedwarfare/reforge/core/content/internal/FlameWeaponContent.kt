package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

internal object FlameWeaponContent {
    private val booleans = setOf("flameWeapon")
    private val numbers = setOf("areaDamage", "areaRadius", "targetGroundSpread", "speedSpread", "targetSpeed",
        "targetSpeedAcceleration", "deflectionPower")
    private val fields = numbers + booleans + "targetGround"

    fun schema(base: UnitContentSchema): UnitContentSchema = base.copy(
        fields = base.fields + ("projectile_1" to (base.fields.getValue("projectile_1") + fields)),
        numericFields = base.numericFields + numbers,
        booleanFields = base.booleanFields + booleans + "targetGround")

    fun read(path: Path, content: UnitSourceReader.EffectiveSections, section: String): ProjectileProfile? {
        val values = content.values.getValue(section)
        fun invalid(key: String, reason: String): Nothing =
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key, values[key], reason)
        if (values["flameWeapon"] != "true") {
            val unused = values.keys.firstOrNull { it in fields - "flameWeapon" }
            if (unused != null) invalid(unused, "This projectile field requires flameWeapon")
            return null
        }
        if (values["targetGround"] != "true")
            invalid("targetGround", "A flame shot requires a captured ground point")
        val permitted = fields + setOf("directDamage", "life", "speed", "frame", "drawSize")
        for (field in values.keys - permitted) invalid(field, "Unsupported flame projectile field")
        fun number(key: String, maximum: Double = Float.MAX_VALUE.toDouble(), default: Double? = null): Double =
            (values[key]?.toDoubleOrNull() ?: if (key !in values) default else null)
                ?.takeIf { it in 0.0..maximum } ?: invalid(key, "Expected finite nonnegative flame projectile value")
        if (number("directDamage", default = 0.0) != 0.0)
            invalid("directDamage", "Flame uses area damage at its captured point")
        val speed = number("speed")
        val speedSpread = number("speedSpread", 100.0, 0.0).toFloat()
        if (speed.toFloat() <= speedSpread || !(speed.toFloat() * speed.toFloat()).isFinite()) invalid("speed", "Initial flame speed must exceed its speed spread")
        val life = number("life", 36000.0)
        if (life.toFloat() <= 0f) invalid("life", "Expected positive flame lifetime")
        val radius = number("areaRadius").toFloat()
        if (radius <= 0f || !(radius * radius).isFinite()) invalid("areaRadius", "Expected finite positive flame radius")
        val acceleration = if ("targetSpeed" in values) {
            val target = number("targetSpeed")
            val rate = number("targetSpeedAcceleration", default = .1)
            if (target.toFloat() <= 0f || rate.toFloat() <= 0f)
                invalid("targetSpeed", "Expected positive flame target speed and acceleration")
            LinearProjectileAcceleration(target, rate)
        } else null
        if (acceleration == null && "targetSpeedAcceleration" in values)
            invalid("targetSpeedAcceleration", "Flame acceleration requires a target speed")
        val deflection = values["deflectionPower"]
        if (deflection != null && deflection.toDoubleOrNull() != -1.0)
            invalid("deflectionPower", "Flame accepts the non-deflectable value -1")
        return ProjectileProfile(0.0, life, speed, nonDeflectable = deflection != null, acceleration = acceleration,
            ground = GroundProjectileProfile(number("areaDamage").toFloat(), radius,
                number("targetGroundSpread", 10_000.0, 0.0).toFloat(), 0f, 0f,
                speedSpread = speedSpread, resolveAtSnapshot = true), flame = true)
    }
}
