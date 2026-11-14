package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

internal object TransportContent {
    fun read(path: Path, loaded: UnitSourceReader.EffectiveSections): TransportSettings? {
        val declarations = loaded.values.entries.filter { (name, fields) ->
            name.startsWith("ability_") && fields["type"] == "transport"
        }
        if (declarations.isEmpty()) return null
        if (!loaded.structured || declarations.size != 1)
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, declarations.last().key, "type",
                reason = "Only one structured transport ability can own cargo")
        val (section, fields) = declarations.single()
        fun invalid(field: String): Nothing {
            val origin = loaded.origins[section to field] ?: loaded.origins[section to "type"]
            val error = ContentError(ContentErrorCode.MALFORMED_SYNTAX, path.toString(), section, field,
                fields[field], "Invalid transport ability parameter")
            throw ContentReadException(origin?.locate(error, "[$section].$field") ?: error)
        }
        val capacity = fields["capacity"]?.toIntOrNull()?.takeIf { it in 1..1000 } ?: invalid("capacity")
        val movement = MovementType.entries.singleOrNull { it.name == fields["passengerMovement"] && it != MovementType.NONE }
            ?: invalid("passengerMovement")
        val terrain = TransportUnloadTerrain.entries.singleOrNull { it.name.lowercase() == fields["unloadTerrain"] }
            ?: invalid("unloadTerrain")
        val ticks = fields["unloadTicks"]?.toIntOrNull()?.takeIf { it in 1..36000 } ?: invalid("unloadTicks")
        for (field in fields.keys - setOf("type", "capacity", "passengerMovement", "unloadTerrain", "unloadTicks")) invalid(field)
        return TransportSettings(capacity, movement, terrain, ticks)
    }
}
