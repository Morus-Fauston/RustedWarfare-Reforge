package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.DeathExplosionProfile
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Path

internal object DeathExplosionContent {
    val fields = setOf("effect", "damage", "radius", "expandTicks")

    fun read(path: Path, content: UnitSourceReader.EffectiveSections): DeathExplosionProfile? {
        val abilities = content.values.entries.filter { (section, values) ->
            section.startsWith("ability_") && values["type"] == "deathExplosion"
        }
        if (abilities.isEmpty()) return null
        if (!content.structured || abilities.size > 1)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, abilities.last().key, "type",
                reason = "Expected one structured death explosion ability")
        val (section, values) = abilities.single()
        fun bad(field: String, reason: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, field, values[field], reason)
        for (field in values.keys - fields - "type") bad(field, "Unsupported death explosion parameter")
        val nuclear = when (values["effect"]) {
            "ordinary" -> false
            "nuclear" -> true
            else -> bad("effect", "Expected the ordinary or nuclear area effect")
        }
        val damage = values["damage"]?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
            ?: bad("damage", "Expected finite nonnegative damage")
        val radius = values["radius"]?.toFloatOrNull()?.takeIf { it > 0f && (it * it).isFinite() }
            ?: bad("radius", "Expected a positive radius with finite squared distance")
        val ticks = values["expandTicks"]?.toIntOrNull()?.takeIf { it in 1..36000 }
            ?: bad("expandTicks", "Expected integer expansion ticks from one to 36000")
        return DeathExplosionProfile(damage, radius, ticks.toFloat(), nuclear, authored = true)
    }
}
