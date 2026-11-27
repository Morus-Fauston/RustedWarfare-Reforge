package com.rustedwarfare.reforge.pc

import java.util.Collections
import com.badlogic.gdx.utils.JsonReader
import com.rustedwarfare.reforge.client.PreparedImagePack

/** Trusted qualification data, shared by the producer and independent published-package checks. */
internal data class PcOriginalSoundSource(val path: String, val bytes: Int, val sha256: String,
    val format: String, val channels: Int, val sampleRate: Int)

internal class PcOriginalResourceProfile(val id: String, val images: PcOriginalImageProfile,
    sounds: List<PcOriginalSoundSource>, events: Map<String, String>) {
    val sounds: List<PcOriginalSoundSource> = Collections.unmodifiableList(ArrayList(sounds))
    /** Existing project event ID to a qualified source path; this does not create a gameplay event. */
    val events: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(events))

    companion object {
        fun bundled(): PcOriginalResourceProfile = readBundled()
        fun forPackage(pack: PreparedImagePack): PcOriginalResourceProfile = PcOriginalProfileVersions.resources(pack.manifest, null)

        private fun readBundled(): PcOriginalResourceProfile {
            fun resource(name: String) = requireNotNull(PcOriginalResourceProfile::class.java.getResourceAsStream("/visuals/$name")) {
                "Missing bundled PC resource qualification"
            }.use { it.readNBytes(PcmAudioDecoder.MAX_BYTES + 1) }.also { require(it.size <= PcmAudioDecoder.MAX_BYTES) }
            val root = JsonReader().parse(resource("pc-original-resources-profile.json").toString(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == 1)
            require(root.getString("imageQualificationSha256") == visualHash(resource("pc-original-images-profile.json"))) {
                "Reviewed PC image qualification changed; complete resource qualification must be updated"
            }
            val image = PcOriginalImageProfile.bundled()
            require(image.id == root.getString("imageProfileId") && image.jarBytes == root.get("identity").getInt("bytes") &&
                image.jarSha256 == root.get("identity").getString("sha256"))
            val sounds = root.get("sounds").map { row -> PcOriginalSoundSource(row.getString("path"), row.getInt("bytes"),
                row.getString("sha256"), row.getString("format"), row.getInt("channels"), row.getInt("sampleRate")) }
            val events = root.get("events").associate { it.getString("eventId") to it.getString("sourcePath") }
            require(events.size == root.get("events").size)
            return PcOriginalResourceProfile(root.getString("profileId"), image, sounds, events)
        }
    }
}
