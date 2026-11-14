package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentError
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.ContentReadException
import com.rustedwarfare.reforge.core.content.ShieldProfile
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Path

internal object ShieldContent {
    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections, required: Boolean = false,
             allowBlink: Boolean = false): ShieldProfile? {
        val abilities = loaded.values.keys.filter {
            it.startsWith("ability_") && (!allowBlink || loaded.values[it]?.get("type") != "blink")
        }
        val section = abilities.firstOrNull()
        if (abilities.size > 1) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, abilities[1], "type",
            reason = "Only one shield ability can manage a unit's shield")
        val core = loaded.values.getValue("core")
        val hasIniShield = listOf("maxShield", "shieldRegen", "startShieldAtZero").any(core::containsKey)
        if (section == null) {
            if (!required && !hasIniShield) return null
            if (loaded.structured && !hasIniShield)
                reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, field = "abilities", reason = "Expected a shield ability")
            return ShieldProfile(number(path, loaded, "core", "maxShield", positive = true),
                number(path, loaded, "core", "shieldRegen", positive = false),
                core["startShieldAtZero"] == "true")
        }
        for (field in loaded.values.getValue(section).keys) {
            if (field !in setOf("type", "maximum", "regeneration", "startAtZero"))
                reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, field, reason = "Unsupported shield parameter")
        }
        if (hasIniShield)
            reject(ContentErrorCode.IDENTITY_CONFLICT, path, section, "type",
                reason = "A structured shield ability cannot be combined with inherited INI shield fields")
        val values = loaded.values.getValue(section)
        val type = requiredContentField(values["type"], path, section, "type", "Expected a supported ability type")
        if (type != "shield") reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, "type", type,
            "Unsupported ability type")
        return ShieldProfile(number(path, loaded, section, "maximum", positive = true),
            values["regeneration"]?.let { number(path, loaded, section, "regeneration", positive = false) } ?: 0f,
            values["startAtZero"] == "true")
    }

    fun validateQualified(path: Path, loaded: UnitSourceReader.EffectiveSections,
                          actual: ShieldProfile?, expected: ShieldProfile?) {
        if (actual == expected) return
        val section = loaded.values.keys.firstOrNull { it.startsWith("ability_") } ?: "core"
        val field = when {
            actual == null || expected == null -> "type"
            actual.maximum != expected.maximum -> if (section == "core") "maxShield" else "maximum"
            actual.regeneration != expected.regeneration -> if (section == "core") "shieldRegen" else "regeneration"
            else -> if (section == "core") "startShieldAtZero" else "startAtZero"
        }
        val origin = loaded.origins[section to field] ?: loaded.origins[section to "type"]
        val error = ContentError(ContentErrorCode.UNSUPPORTED_FEATURE, path.toString(), section, field,
            reason = "Invalid shield for this qualified unit form")
        throw ContentReadException(origin?.locate(error, "[$section].$field") ?: error)
    }

    private fun number(path: Path, loaded: UnitSourceReader.EffectiveSections, section: String, field: String,
                       positive: Boolean): Float {
        val text = requiredContentField(loaded.values[section]?.get(field), path, section, field,
            "Required shield field")
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.toFloat()
        if (value == null || !value.isFinite() || positive && value <= 0f)
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, field, text,
                "Expected a finite ${if (positive) "positive" else "nonnegative"} shield float")
        return value
    }
}
