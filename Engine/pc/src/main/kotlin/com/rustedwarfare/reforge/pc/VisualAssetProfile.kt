package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.security.MessageDigest

internal fun visualHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** Fixed, bundled metadata. This parser is internal; launch arguments cannot select a profile. */
internal class VisualAssetProfile private constructor(val key: String, val json: JsonValue) {
    val id: String = json.getString("profileId")
    val recipes: Map<String, JsonValue> = listOf("unitOutputs", "terrainOutputs").flatMap { group ->
        json.get(group).map { it.name to it }
    }.toMap()

    companion object {
        fun parse(bytes: ByteArray): VisualAssetProfile {
            val json = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            require(json.getInt("schemaVersion") == 1) { "Unsupported visual profile" }
            return VisualAssetProfile(visualHash(bytes), json)
        }

        fun bundled(): VisualAssetProfile = parse(requireNotNull(VisualAssetProfile::class.java
            .getResourceAsStream("/visuals/original-profile.json")) { "Missing bundled visual profile" }.use { it.readBytes() })
    }
}
