package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

internal object NavalInterceptionContent {
    val fields = setOf("weapon", "landingRange", "trackingRange", "minimumHeight", "ammoPrice", "ammoBuildRate", "ammoLimit")

    data class Declaration(val weapon: String, val ranges: ProjectileInterceptionProfile, val actions: CustomActionsProfile)

    fun read(path: Path, content: UnitSourceReader.EffectiveSections, mounts: List<NamedWeaponDefinition>): Declaration? {
        val abilities = content.values.entries.filter { (section, fields) ->
            section.startsWith("ability_") && fields["type"] == "navalInterception"
        }
        if (abilities.isEmpty()) return null
        if (!content.structured || abilities.size > 1)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, abilities.last().key, "type",
                reason = "Expected one structured naval interception ability")
        val (section, values) = abilities.single()
        fun invalid(field: String, reason: String, code: ContentErrorCode = ContentErrorCode.MALFORMED_SYNTAX): Nothing =
            reject(code, path, section, field, values[field], reason)
        for (field in values.keys - fields - "type") invalid(field, "Unsupported naval interception parameter")
        fun range(name: String, allowZero: Boolean = false): Float =
            values[name]?.toFloatOrNull()?.takeIf { it >= 0f && (allowZero || it > 0f) && (it * it).isFinite() }
                ?: invalid(name, "Expected a finite range or height")
        val weapon = mounts.singleOrNull { it.name == values["weapon"] && NavalInterceptionRules.validMount(it) }
            ?: invalid("weapon", "Expected a non-firing named mount with a qualified interceptor projectile", ContentErrorCode.BAD_REFERENCE)
        val price = values["ammoPrice"]?.toLongOrNull()?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }
            ?: invalid("ammoPrice", "Expected a nonnegative integer credit cost")
        val rate = values["ammoBuildRate"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in Float.MIN_VALUE..1f }
            ?: invalid("ammoBuildRate", "Expected positive production progress per tick, at most one")
        val limit = values["ammoLimit"]?.toIntOrNull()?.takeIf { it in 1..100 }
            ?: invalid("ammoLimit", "Expected an integer stock and queue limit from one to one hundred")
        return Declaration(weapon.name, ProjectileInterceptionProfile(range("landingRange"), range("trackingRange"),
            range("minimumHeight", allowZero = true)), CustomActionsProfile(listOf(NavalInterceptionRules.ammunition(price, rate, limit))))
    }
}
