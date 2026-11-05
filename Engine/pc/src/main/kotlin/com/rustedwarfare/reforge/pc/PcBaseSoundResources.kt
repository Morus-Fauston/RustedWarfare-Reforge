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
        val expected = linkedMapOf(
            "tank-fire" to "39c5dcfa85477207ebbbba6bcb51b15c6fcd961acc1bab76e588ca8348dcd8b2",
            "naval-main-fire" to "959fe8f1f1f783b0e2cdf2710b8ce0606f3ca8ae756a3722111015e4f665550c",
            "naval-missile-fire" to "eaa2dedbc783b55c0cc87c33c14dac99d9f6a9c0eb0eb4a2a93bc449f005f837")
        fun soundId(name: String) = ResourceId.parse("reforge:sounds/$name")
        fun eventId(name: String) = ResourceId.parse("reforge:events/$name")
        require(declarations.sounds.keys == expected.keys.map(::soundId).toSet() &&
            declarations.events.keys == expected.keys.map(::eventId).toSet())
        val decoded = expected.mapValues { (name, hash) ->
            require(declarations.sounds.getValue(soundId(name)).file == "$name.wav" &&
                declarations.events.getValue(eventId(name)).sound == soundId(name))
            val input = resource("$name.wav")
            require(visualHash(input) == hash) { "PREPARE_FAILED: bundled $name sound identity changed" }
            val pcm = PcmAudioDecoder.decode(input, "bundled/$name.wav")
            pcm to pcm.wavBytes()
        }
        val sources = expected.entries.joinToString(",") { (name, hash) ->
            "{\"resource\":\"sounds/$name.wav\",\"sha256\":\"$hash\"}"
        }
        val outputs = decoded.entries.mapIndexed { index, (_, value) ->
            val (pcm, bytes) = value
            "\"sound$index\":{\"kind\":\"pcm16\",\"file\":\"sounds/$index.wav\"," +
                "\"channels\":${pcm.channels},\"sampleRate\":${pcm.sampleRate},\"frames\":${pcm.frames},\"sha256\":\"${visualHash(bytes)}\"}"
        }.joinToString(",")
        val profile = VisualAssetProfile.parse(("{\"schemaVersion\":1,\"profileId\":\"owned-base-sounds-v2\"," +
            "\"identity\":{\"manifestSha256\":\"${visualHash(metadata)}\"}," +
            "\"sources\":[{\"resource\":\"sounds/sounds.json\",\"sha256\":\"${visualHash(metadata)}\"},$sources]," +
            "\"unitOutputs\":{},\"terrainOutputs\":{},\"soundOutputs\":{$outputs}}").toByteArray())
        val publisher = VisualAssetCache(cacheRoot.toAbsolutePath().normalize().resolve("base-sounds").resolve(profile.key), profile)
        val fixed = publisher.locked { publisher.prepare {
            Files.createDirectory(it.resolve("sounds"))
            decoded.values.forEachIndexed { index, (_, bytes) -> Files.write(it.resolve("sounds/$index.wav"), bytes, CREATE_NEW) }
        } }
        val sounds = decoded.entries.mapIndexed { index, (name, value) ->
            val (pcm, bytes) = value
            soundId(name) to PreparedResourceSound(fixed.outputs.getValue("sound$index"), pcm.channels,
                pcm.sampleRate, pcm.frames, bytes.size, visualHash(bytes))
        }.toMap()
        return PcPreparedBaseSounds(sounds, declarations.events, fixed)
    }
}
