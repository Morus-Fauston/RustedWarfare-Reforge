package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.BlinkProfile
import com.rustedwarfare.reforge.core.content.ContentErrorCode
import com.rustedwarfare.reforge.core.content.reject
import java.nio.file.Path

internal object BlinkContent {
    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections): BlinkProfile? {
        val sections = loaded.values.filter { (name, fields) ->
            name.startsWith("ability_") && fields["type"] == "blink"
        }
        if (sections.isEmpty()) return null
        val section = sections.keys.first()
        if (!loaded.structured || sections.size != 1)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, sections.keys.last(), "type",
                reason = "Only one structured blink ability is supported")
        val fields = sections.getValue(section)
        val supported = setOf("type", "range", "chargeTicks", "cooldownTicks", "unlockCredits", "unlockTicks", "locked", "queueSpeed")
        for (field in fields.keys - supported)
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, field, reason = "Unsupported blink parameter")
        fun invalid(field: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, field, fields[field],
                "Expected a valid blink parameter")
        fun ticks(field: String) = fields[field]?.toIntOrNull()?.takeIf { it in 1..36000 } ?: invalid(field)
        val range = fields["range"]?.toDoubleOrNull()?.takeIf {
            val value = it.toFloat()
            value > 0f && (value * value).isFinite()
        } ?: invalid("range")
        val credits = fields["unlockCredits"]?.let {
            it.toLongOrNull()?.takeIf { cost -> cost > 0L } ?: invalid("unlockCredits")
        }
        if (credits == null && "unlockTicks" in fields) invalid("unlockCredits")
        val locked = when (fields["locked"]) {
            null, "false" -> false
            "true" -> true
            else -> invalid("locked")
        }
        val speed = fields["queueSpeed"]?.let {
            it.toFloatOrNull()?.takeIf { rate -> rate in .01f..100f } ?: invalid("queueSpeed")
        } ?: 1f
        return BlinkProfile(range, ticks("chargeTicks"), ticks("cooldownTicks"),
            credits ?: 0L, if (credits == null) 0 else ticks("unlockTicks"), locked, speed)
    }
}
