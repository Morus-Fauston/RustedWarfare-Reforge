package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Files
import java.nio.file.Path

internal object IniReader {
    private val consumed = mapOf(
        "core" to setOf("name", "class", "radius", "displayRadius", "fogOfWarSightRange"),
        "graphics" to setOf("total_frames", "image", "imageScale"),
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
            "maxTurnSpeed", "turnAcceleration", "moveSlidingMode", "moveIgnoringBody"),
    )
    private val retained = mapOf(
        "core" to setOf("displayLocaleKey", "price", "maxHp", "mass", "techLevel", "buildSpeed", "isBio", "softCollisionOnAll"),
        "graphics" to setOf("image_shadow", "shadowOffsetX", "shadowOffsetY", "dustEffect", "animation_moving_start",
            "animation_moving_end", "animation_moving_speed"),
        "movement" to emptySet(),
    )

    fun read(path: Path): UnitDefinition {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: String? = null
        Files.readAllLines(path).forEachIndexed { index, raw ->
            val line = raw.trim()
            val reason = "Invalid INI at line ${index + 1}"
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith("[")) {
                if (!line.endsWith("]") || line.count { it == '[' } != 1 || line.count { it == ']' } != 1) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, current, reason = reason)
                }
                val section = line.substring(1, line.length - 1).trim()
                if (section in setOf("attack", "turret_1", "projectile_1")) {
                    reject(ContentErrorCode.WEAPON_SECTION_PRESENT, path, section, reason = "Weapon sections are forbidden")
                }
                if (section !in consumed) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, reason = "Unsupported section at line ${index + 1}")
                if (sections.containsKey(section)) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = "Duplicate section at line ${index + 1}")
                sections[section] = linkedMapOf()
                current = section
            } else {
                val colon = line.indexOf(':')
                val section = current
                if (colon <= 0 || section == null) reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, reason = reason)
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (key == "overrideAndReplace") reject(ContentErrorCode.IDENTITY_CONFLICT, path, section, key, reason = "Global replacement is forbidden")
                if (key !in consumed.getValue(section) && key !in retained.getValue(section)) {
                    reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, key, reason = "Unsupported field at line ${index + 1}")
                }
                if (sections.getValue(section).putIfAbsent(key, value) != null) {
                    reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = "Duplicate field at line ${index + 1}")
                }
            }
        }
        fun required(section: String, key: String): String = sections[section]?.get(key)?.takeIf { it.isNotBlank() }
            ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required field is absent or empty")
        fun number(section: String, key: String, positive: Boolean = false): Double {
            val value = required(section, key).toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0 || (positive && value == 0.0)) {
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = "Expected finite ${if (positive) "positive" else "nonnegative"} number")
            }
            return value
        }
        fun only(section: String, key: String, expected: String): String = required(section, key).also {
            if (it != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key, it, "Only $expected is supported")
        }
        val id = required("core", "name")
        if (id in setOf("c_tank", "tank")) reject(ContentErrorCode.IDENTITY_CONFLICT, path, "core", "name", id, "Variant needs an independent identity")
        val className = only("core", "class", "CustomUnitMetadata")
        val frames = required("graphics", "total_frames").toIntOrNull()
        if (frames == null || frames <= 0) reject(ContentErrorCode.MALFORMED_SYNTAX, path, "graphics", "total_frames", reason = "Expected positive frame count")
        val image = resolveReference(path, required("graphics", "image"), "graphics", "image")
        val scale = if (sections["graphics"]?.containsKey("imageScale") == true) number("graphics", "imageScale", true) else 1.0
        val sight = sections["core"]?.get("fogOfWarSightRange")?.let {
            it.toIntOrNull() ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", "fogOfWarSightRange", reason = "Expected integer sight range")
        } ?: 15
        only("movement", "movementType", "LAND")
        only("movement", "moveSlidingMode", "false")
        only("movement", "moveIgnoringBody", "false")
        return UnitDefinition(id, className, number("core", "radius", true), number("core", "displayRadius", true),
            frames, image, scale, MovementProfile(MovementType.LAND, number("movement", "moveSpeed"),
                number("movement", "moveAccelerationSpeed"), number("movement", "moveDecelerationSpeed"),
                number("movement", "maxTurnSpeed"), number("movement", "turnAcceleration"), false, false),
            sections.flatMap { (section, fields) -> fields.keys.filter { it in retained.getValue(section) } }.toSet(), sight)
    }
}
