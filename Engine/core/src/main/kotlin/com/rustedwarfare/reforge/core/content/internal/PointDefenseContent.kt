package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

internal object PointDefenseContent {
    val fields = setOf("range", "muzzleOffsetX", "muzzleOffsetY", "recharge", "cost", "initialEnergy")

    fun read(path: Path, source: UnitSourceReader.EffectiveSections): PointDefenseProfile? {
        val defenses = source.values.entries.filter { (name, declared) ->
            name.startsWith("ability_") && declared["type"] == "pointDefense"
        }
        if (defenses.isEmpty()) return null
        if (!source.structured || defenses.size > 1)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, defenses.last().key, "type",
                reason = "Expected one structured point defense ability")
        val (section, declared) = defenses.single()
        fun invalid(field: String, reason: String): Nothing {
            val origin = source.origins[section to field] ?: source.origins[section to "type"]
            val error = ContentError(ContentErrorCode.MALFORMED_SYNTAX, path.toString(), section, field,
                declared[field], reason)
            throw ContentReadException(origin?.locate(error, "[$section].$field") ?: error)
        }
        for (field in declared.keys - fields - "type") invalid(field, "Unsupported point defense parameter")
        fun number(field: String, bounds: ClosedFloatingPointRange<Double>): Double = declared[field]?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in bounds } ?: invalid(field, "Expected a finite number in $bounds")
        val range = number("range", 0.0..Double.MAX_VALUE)
        if (range <= 0.0 || !(range * range).isFinite()) invalid("range", "Expected a positive range with a finite square")
        val offsetBounds = -Double.MAX_VALUE..Double.MAX_VALUE
        return PointDefenseProfile(range = range,
            muzzleOffsetX = number("muzzleOffsetX", offsetBounds),
            muzzleOffsetY = number("muzzleOffsetY", offsetBounds),
            recharge = number("recharge", 0.0..1.0).toFloat(), cost = number("cost", 0.0..1.0).toFloat(),
            initialEnergy = number("initialEnergy", 0.0..1.0).toFloat(), authored = true)
    }
}
