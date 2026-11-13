package com.rustedwarfare.reforge.pc

import java.util.Collections
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.PreparedImagePack

/** Trusted bundled/test qualification data, never selected from a user-supplied profile. */
internal data class PcOriginalImageSource(val path: String, val bytes: Int, val sha256: String,
    val format: String, val width: Int? = null, val height: Int? = null)

internal class PcOriginalImageProfile(val id: String, val jarBytes: Int, val jarSha256: String,
    images: List<PcOriginalImageSource>, val mappedImages: VisualAssetProfile? = null) {
    val images: List<PcOriginalImageSource> = Collections.unmodifiableList(ArrayList(images))

    companion object {
        /** Fixed qualification and unchanged reviewed recipes, not an arbitrary profile input. */
        fun bundled(): PcOriginalImageProfile = readBundled(PcOriginalProfileVersions.CURRENT_VERSION)
        fun versionOne(): PcOriginalImageProfile = readBundled(1)
        fun versionTwo(): PcOriginalImageProfile = readBundled(2)
        fun versionThree(): PcOriginalImageProfile = readBundled(3)
        fun versionFour(): PcOriginalImageProfile = readBundled(4)
        fun versionFive(): PcOriginalImageProfile = readBundled(5)
        fun forPackage(pack: PreparedImagePack): PcOriginalImageProfile = PcOriginalProfileVersions.images(pack.manifest, null)

        private fun readBundled(version: Int): PcOriginalImageProfile {
            val path = "/visuals/pc-original-images-profile${if (version == PcOriginalProfileVersions.CURRENT_VERSION) "" else "-v$version"}.json"
            val bytes = requireNotNull(PcOriginalImageProfile::class.java.getResourceAsStream(path)) { "Missing bundled PC image qualification" }.use { it.readBytes() }
            val root = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == 1)
            val identity = root.get("identity")
            val images = root.get("images").map { row -> PcOriginalImageSource(row.getString("path"), row.getInt("bytes"),
                row.getString("sha256"), row.getString("format"), row.get("width")?.asInt(), row.get("height")?.asInt()) }
            val old = when (version) {
                1 -> VisualAssetProfile.versionOne()
                2 -> VisualAssetProfile.versionTwo()
                3 -> VisualAssetProfile.versionThree()
                4 -> VisualAssetProfile.versionFour()
                else -> VisualAssetProfile.bundled()
            }
            require(old.key == root.get("mappingBasis").getString("androidProfileSha256")) { "Reviewed mapping profile changed; PC qualification must be updated" }
            val mapped = JsonReader().parse(old.json.toJson(OutputType.json))
            mapped.remove("profileId"); mapped.addChild("profileId", JsonValue(root.getString("profileId") + ".mapped"))
            mapped.remove("identity")
            mapped.addChild("identity", JsonValue(JsonValue.ValueType.`object`).also { value ->
                value.addChild("manifestPath", JsonValue("game-lib.jar")); value.addChild("manifestBytes", JsonValue(identity.getInt("jarBytes").toLong()))
                value.addChild("manifestSha256", JsonValue(identity.getString("jarSha256")))
                value.addChild("scope", JsonValue("Verified PC image snapshot and reviewed static mappings only; no original code loading"))
            })
            val byPath = images.associateBy { it.path }
            // PC-only additions are qualified against this PC snapshot, not an Android source claim.
            fun clone(value: JsonValue) = JsonReader().parse(value.toJson(OutputType.json))
            root.get("mappingSources")?.forEach { mapped.get("sources").addChild(clone(it)) }
            root.get("mappingUnitOutputs")?.forEach { mapped.get("unitOutputs").addChild(it.name, clone(it)) }
            root.get("mappingUnitBindings")?.forEach {
                mapped.get("unitBindings").remove(it.name)
                mapped.get("unitBindings").addChild(it.name, clone(it))
            }
            for (source in mapped.get("sources")) {
                val pathName = root.get("mappedSourceIds").getString(source.getString("id"))
                require(pathName == source.getString("path"))
                val pc = byPath.getValue(pathName)
                require(pc.format == "PNG" && pc.width == source.getInt("width") && pc.height == source.getInt("height"))
                source.remove("bytes"); source.addChild("bytes", JsonValue(pc.bytes.toLong()))
                source.remove("sha256"); source.addChild("sha256", JsonValue(pc.sha256))
            }
            return PcOriginalImageProfile(root.getString("profileId"), identity.getInt("jarBytes"), identity.getString("jarSha256"),
                images, VisualAssetProfile.parse(mapped.toJson(OutputType.json).toByteArray(Charsets.UTF_8)))
        }
    }
}
