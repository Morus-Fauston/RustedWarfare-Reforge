package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

/** Explicit ordinary c_tank subset. This does not register global replacements or load game code. */
internal object CombatContentReader {
    private val fields = mapOf(
        "core" to setOf("name", "overrideAndReplace", "class", "price", "maxHp", "mass", "techLevel",
            "buildSpeed", "radius", "displayRadius", "isBio", "softCollisionOnAll", "displayLocaleKey", "fogOfWarSightRange"),
        "graphics" to setOf("total_frames", "image", "image_wreak", "image_turret", "imageScale", "turretImageScale",
            "image_shadow", "shadowOffsetX", "shadowOffsetY", "animation_moving_start", "animation_moving_end",
            "animation_moving_speed", "dustEffect"),
        "attack" to setOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits",
            "turretSize", "turretTurnSpeed", "maxAttackRange", "shootDelay"),
        "turret_1" to setOf("x", "y", "projectile", "turnSpeedAcceleration", "shoot_sound", "shoot_sound_vol",
            "shoot_flame", "shoot_light", "canShoot", "recoilOffset"),
        "projectile_1" to setOf("directDamage", "life", "speed", "frame", "drawSize"),
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
            "maxTurnSpeed", "turnAcceleration", "moveSlidingMode", "moveIgnoringBody"),
    )
    private val retained = mapOf(
        "core" to setOf("mass", "techLevel", "isBio", "softCollisionOnAll", "displayLocaleKey"),
        "graphics" to setOf("image_shadow", "shadowOffsetX", "shadowOffsetY", "animation_moving_start",
            "animation_moving_end", "animation_moving_speed", "dustEffect"),
        "turret_1" to setOf("shoot_sound", "shoot_sound_vol", "shoot_flame", "shoot_light", "recoilOffset"),
        "projectile_1" to setOf("frame", "drawSize"),
    )

    fun read(path: Path): UnitDefinition {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var section: String? = null
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line.startsWith('[')) {
                if (!line.endsWith(']') || line.count { it == '[' } != 1 || line.count { it == ']' } != 1) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid section at line ${index + 1}")
                }
                val next = line.substring(1, line.length - 1).trim()
                if (next !in fields) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, next, reason = "Unsupported combat section")
                if (sections.putIfAbsent(next, linkedMapOf()) != null) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, next, reason = "Duplicate section at line ${index + 1}")
                }
                section = next
            } else {
                val colon = line.indexOf(':')
                val current = section
                if (colon <= 0 || current == null) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Invalid field at line ${index + 1}")
                }
                val key = line.substring(0, colon).trim()
                if (key !in fields.getValue(current)) {
                    reject(ContentErrorCode.UNSUPPORTED_FIELD, path, current, key, reason = "Unsupported combat field")
                }
                if (sections.getValue(current).putIfAbsent(key, line.substring(colon + 1).trim()) != null) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, key, reason = "Duplicate field at line ${index + 1}")
                }
            }
        }
        fun required(s: String, key: String): String = sections[s]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, s, key, reason = "Required combat field is absent or empty")
        fun only(s: String, key: String, expected: String) {
            val value = required(s, key)
            if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, s, key, value, "Only $expected is supported")
        }
        fun number(s: String, key: String, positive: Boolean = false): Double {
            val value = required(s, key).toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0.0 || (positive && value == 0.0)) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, s, key, reason = "Expected finite ${if (positive) "positive" else "nonnegative"} number")
            }
            return value
        }
        fun integer(s: String, key: String, positive: Boolean = false): Int {
            val value = required(s, key).toIntOrNull()
            if (value == null || value < 0 || (positive && value == 0)) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, s, key, reason = "Expected ${if (positive) "positive" else "nonnegative"} integer")
            }
            return value
        }
        val price = required("core", "price").toLongOrNull()?.takeIf { it >= 0L }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "price", reason = "Expected nonnegative integer cost")
        val buildRate = required("core", "buildSpeed").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "buildSpeed", reason = "Expected finite positive binary32 build rate")
        only("core", "name", "c_tank")
        only("core", "overrideAndReplace", "tank")
        only("core", "class", "CustomUnitMetadata")
        only("core", "isBio", "false")
        if (number("core", "softCollisionOnAll") != 0.0) {
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "softCollisionOnAll",
                reason = "Soft unit collision is not supported by the ordinary combat subset")
        }
        only("attack", "canAttack", "true")
        only("attack", "canAttackFlyingUnits", "false")
        only("attack", "canAttackLandUnits", "true")
        only("attack", "canAttackUnderwaterUnits", "false")
        only("turret_1", "canShoot", "true")
        only("turret_1", "projectile", "1")
        only("turret_1", "x", "0")
        only("turret_1", "y", "0")
        only("movement", "movementType", "LAND")
        only("movement", "moveSlidingMode", "false")
        only("movement", "moveIgnoringBody", "false")
        val sight = sections["core"]?.get("fogOfWarSightRange")?.let {
            it.toIntOrNull() ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "fogOfWarSightRange", reason = "Expected integer sight range")
        } ?: 15
        val imageScale = if ("imageScale" in sections["graphics"].orEmpty()) number("graphics", "imageScale", true) else 1.0
        val turretScale = if ("turretImageScale" in sections["graphics"].orEmpty()) number("graphics", "turretImageScale", true) else 1.0
        val profile = CombatProfile(
            integer("core", "maxHp", true).toDouble(), number("attack", "maxAttackRange", true),
            number("attack", "shootDelay", true), number("attack", "turretSize"),
            number("attack", "turretTurnSpeed"), number("turret_1", "turnSpeedAcceleration"),
            ProjectileProfile(integer("projectile_1", "directDamage").toDouble(),
                number("projectile_1", "life", true), number("projectile_1", "speed", true)),
            resolveReference(path, required("graphics", "image_turret"), "graphics", "image_turret"),
            resolveReference(path, required("graphics", "image_wreak"), "graphics", "image_wreak"), turretScale,
        )
        return UnitDefinition("c_tank", "CustomUnitMetadata", number("core", "radius", true),
            number("core", "displayRadius", true), integer("graphics", "total_frames", true),
            resolveReference(path, required("graphics", "image"), "graphics", "image"), imageScale,
            MovementProfile(MovementType.LAND, number("movement", "moveSpeed"), number("movement", "moveAccelerationSpeed"),
                number("movement", "moveDecelerationSpeed"), number("movement", "maxTurnSpeed"),
                number("movement", "turnAcceleration"), false, false),
            sections.flatMap { (s, values) -> values.keys.filter { it in retained[s].orEmpty() } }.toSet(), sight, profile,
            build = BuildProfile(price, buildRate))
    }
}
