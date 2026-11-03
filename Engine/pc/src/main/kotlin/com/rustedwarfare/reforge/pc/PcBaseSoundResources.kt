package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedResourceSound
import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ResourceSoundEvent
import com.rustedwarfare.reforge.client.ResourceSoundManifest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

internal class PcPreparedBaseSounds(val sounds: Map<ResourceId, PreparedResourceSound>,
    val events: Map<ResourceId, ResourceSoundEvent>, private val fixed: PcPreparedVisualAssets) {
    fun verify() = fixed.verify()
}

/** Bundled owned audio uses the same typed fixed-generation publisher as user packs. */
internal class PcBaseSoundResources(private val cacheRoot: Path) {
    private val prepared by lazy { prepareOnce() }
    fun prepare(): PcPreparedBaseSounds = prepared.also { it.verify() }
    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/sounds/$name")) {
        "PREPARE_FAILED: missing bundled sound $name"
    }.use { it.readNBytes(PcmAudioDecoder.MAX_BYTES + 1) }

    private fun prepareOnce(): PcPreparedBaseSounds {
        checkVisualCancellation()
        val metadata = resource("sounds.json")
        val declarations = ResourceSoundManifest.read(metadata, "bundled/sounds.json")
        val id = ResourceId.parse("reforge:sounds/tank-fire")
        require(declarations.sounds.keys == setOf(id) && declarations.events.keys == setOf(ResourceId.parse("reforge:events/tank-fire")))
        val input = resource(declarations.sounds.getValue(id).file)
        require(visualHash(input) == "39c5dcfa85477207ebbbba6bcb51b15c6fcd961acc1bab76e588ca8348dcd8b2") { "PREPARE_FAILED: bundled tank sound identity changed" }
        val pcm = PcmAudioDecoder.decode(input, "bundled/tank-fire.wav"); val bytes = pcm.wavBytes(); val sha = visualHash(bytes)
        val profile = VisualAssetProfile.parse(("{\"schemaVersion\":1,\"profileId\":\"owned-base-sounds-v1\"," +
            "\"identity\":{\"manifestSha256\":\"${visualHash(metadata)}\",\"audioSha256\":\"${visualHash(input)}\"}," +
            "\"sources\":[{\"resource\":\"sounds/sounds.json\",\"sha256\":\"${visualHash(metadata)}\"},{\"resource\":\"sounds/tank-fire.wav\",\"sha256\":\"${visualHash(input)}\"}]," +
            "\"unitOutputs\":{},\"terrainOutputs\":{},\"soundOutputs\":{\"sound0\":{\"kind\":\"pcm16\",\"file\":\"sounds/0.wav\"," +
            "\"channels\":${pcm.channels},\"sampleRate\":${pcm.sampleRate},\"frames\":${pcm.frames},\"sha256\":\"$sha\"}}}").toByteArray())
        val publisher = VisualAssetCache(cacheRoot.toAbsolutePath().normalize().resolve("base-sounds").resolve(profile.key), profile)
        val fixed = publisher.locked { publisher.prepare {
            Files.createDirectory(it.resolve("sounds")); Files.write(it.resolve("sounds/0.wav"), bytes, CREATE_NEW)
        } }
        return PcPreparedBaseSounds(mapOf(id to PreparedResourceSound(fixed.outputs.getValue("sound0"), pcm.channels,
            pcm.sampleRate, pcm.frames, bytes.size, sha)), declarations.events, fixed)
    }
}
