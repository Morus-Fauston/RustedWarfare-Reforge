package com.rustedwarfare.reforge.client

import org.hjson.JsonArray
import org.hjson.JsonValue
import org.hjson.JsonObject
import java.nio.file.Path

/** Strict JSON setting payloads; filesystem work and atomic publication belong to the platform. */
object ResourcePackSelectionCodec {
    fun read(catalog: String, enabled: String): ResourcePackSelection {
        val entries = JsonValue.readJSON(catalog)
        require(entries.isArray) { "Expected resource pack catalog array" }
        val checked = entries.asArray().map { entry ->
            require(entry.isObject) { "Expected catalog entry object" }
            val fields = entry.asObject()
            val seen = mutableSetOf<String>()
            for (member in fields) {
                require(seen.add(member.name)) { "Duplicate catalog member: ${member.name}" }
                require(member.name in setOf("id", "manifest", "identity")) { "Unknown catalog member: ${member.name}" }
            }
            fun text(key: String): String {
                val value = fields.get(key)
                require(value != null && value.isString && value.asString().isNotBlank() &&
                    value.asString().none(Char::isISOControl)) { "Expected catalog $key string" }
                return value.asString()
            }
            ResourcePackCatalogEntry(text("id"), Path.of(text("manifest")), text("identity"))
        }
        val order = JsonValue.readJSON(enabled)
        require(order.isArray) { "Expected enabled resource pack array" }
        val ids = order.asArray().map { value ->
            require(value.isString) { "Expected enabled resource pack ID" }
            value.asString()
        }
        return ResourcePackSelection(checked, ids)
    }

    fun catalog(value: ResourcePackSelection): String = JsonArray().apply {
        value.catalog.forEach { entry -> add(JsonObject().add("id", entry.id).add("manifest", entry.manifest.toString()).add("identity", entry.identity)) }
    }.toString()

    fun enabled(value: ResourcePackSelection): String = JsonArray().apply { value.enabled.forEach(::add) }.toString()
}
