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
        fun bundled(): PcOriginalResourceProfile = readBundled(3)
        fun versionOne(): PcOriginalResourceProfile = readBundled(1)
        fun versionTwo(): PcOriginalResourceProfile = readBundled(2)
        fun forPackage(pack: PreparedImagePack): PcOriginalResourceProfile =
            when (originalCertificateProfile(pack, "resource-extraction.json")) {
                "rw.pc.steam.9902063.resources.v1" -> versionOne()
                "rw.pc.steam.9902063.resources.v2" -> versionTwo()
                else -> bundled()
            }

        private fun readBundled(version: Int): PcOriginalResourceProfile {
            fun resource(name: String) = requireNotNull(PcOriginalResourceProfile::class.java.getResourceAsStream("/visuals/$name")) {
                "Missing bundled PC resource qualification"
            }.use { it.readNBytes(PcmAudioDecoder.MAX_BYTES + 1) }.also { require(it.size <= PcmAudioDecoder.MAX_BYTES) }
            val suffix = if (version == 3) "" else "-v$version"
            val root = JsonReader().parse(resource("pc-original-resources-profile$suffix.json").toString(Charsets.UTF_8))
            require(root.getInt("schemaVersion") == 1)
            require(root.getString("imageQualificationSha256") == visualHash(resource("pc-original-images-profile$suffix.json"))) {
                "Reviewed PC image qualification changed; complete resource qualification must be updated"
            }
            val image = when (version) {
                1 -> PcOriginalImageProfile.versionOne()
                2 -> PcOriginalImageProfile.versionTwo()
                else -> PcOriginalImageProfile.bundled()
            }
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
