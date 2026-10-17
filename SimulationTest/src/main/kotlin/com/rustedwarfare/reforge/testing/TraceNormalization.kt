package com.rustedwarfare.reforge.testing

import kotlinx.serialization.json.*

internal fun normalizeRecord(record: JsonObject, line: Int, coordinate: String? = null, time: String? = null): JsonObject {
    val fields = record.toMutableMap()
    fields["source"] = obj("path" to s("reforge/raw.jsonl"), "line" to n(line))
    fields["layer"] = s("normalized-gameplay")
    if (coordinate != null && time != null) {
        fields["coordinateModel"] = s(coordinate)
        fields["timeModel"] = s(time)
        fields["angleUnit"] = s("degree-clockwise-zero-up")
    }
    (record["observation"] as? JsonObject)?.let { observation ->
        fields["observation"] = JsonObject(observation + ("units" to array(observation.arrayAt("units").map {
            val unit = it.jsonObject
            JsonObject(unit + mapOf("owner" to unit.required("ownerPlayerId"), "type" to unit.required("typeId"),
                "movementState" to s(unit.text("movementState").lowercase()), "rawMovementState" to unit.required("movementState")))
        })))
    }
    return JsonObject(fields)
}
