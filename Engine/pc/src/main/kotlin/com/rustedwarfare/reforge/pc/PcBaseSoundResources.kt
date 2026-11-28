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
            "naval-missile-fire" to "eaa2dedbc783b55c0cc87c33c14dac99d9f6a9c0eb0eb4a2a93bc449f005f837",
            "nuke-launch" to "c81c07337c473bc26bf6c18893515af93ce395bec8409c49c784f9a8538ef585",
            "nuke-explode" to "1a72aa4a39f33a415c9c5a950bf7a94890ad374d253043a81f245f7a31d9a132",
            "cannon-fire" to "ff530ed0deff177f0fcc152c0b6cad226b8e99a5abe90ba99960ee664a24ec14",
            "gun-fire" to "d07479ca6817b7094b104f6dc3f7209130266a31939e406efa8c6c619361d464",
            "autocannon-fire" to "ac61628271967b645f58a4b67406c9de423597d85fe047227dbd05131b184354",
            "heavy-autocannon-fire" to "6a020f83a47a03f898bdfd91f465a5b5e2c953dbd0add3fa3f454d46d3984ad7",
            "flak-cannon-fire" to "75c67392c9c2ed8ff1df5943939098f848e65f973470c098194d57b1dc6539d1",
            "missile-hit" to "30e662a46f23052ca8fd9c172a9bdc9f0bfcc212b377ccd0a50944fdbceb2df1",
            "plasma-fire" to "4205fed6d6e68f2f4f9195533eee684987641c1aaed8d21d1bbcb7112faac127",
            "heavy-plasma-fire" to "6ebffdcfda2d23b6e2671b2fd34de7faf4c4327d69ab32153cdbdb0a46855761",
            "lightning-burst" to "ba5aa65f82fa3aea8209ba77f501c60b146b7261fcec8d7052c135bb5d1c64c9",
            "laser-deflect-charge" to "f6bc1c4da739b3e087bb653dee0338ef535895bc3783dec00db99fc4556f929f",
            "point-defense-intercept" to "fe39baeb379cadbec73acf9502f3a9b60b1e22f14be96da63680b2dee9a8f565",
            "unit-explode" to "cf99de803636de0bb7fb9b1b03f2e08ffb1870e6acf777a3bb92a54f3c27945b",
            "unit-explode-small" to "18dcc87e0490301b1b8ec301de6caf501f9869707b6c1ed1e6293954f07d66dd",
            "building-explode" to "70007a46ca35983ff1349e65abf84111c961cd20c4567938afceb818a334b860",
            "bug-die" to "5dc3a23037abf6e1207381b2eb4202981987592b54caef0276bac85103b9a119",
            "bug-attack" to "577e977336b80d2fede245182abc2ab7bc2738541d71cf96408f0efb308abcac",
            "order-move" to "a0b4206197034fd2c7c4158493655fee841ed681712ff4bd72dcb0ef95768e45",
            "order-attack" to "7b83a3b9280495207cfe82a8bceac330eb48c47810bac103f070c722ee974301",
            "order-attack-alt" to "aa13b3ce187c31fdc8a24423181e559455f25092ea1b70e053a5cb745f886895",
            "ui-click" to "1621a64a940599a53f5c4236ad10b75f7ae3586b3b024318be2b0371b501d8fb",
            "ui-queue-add" to "6beecd51ee28b64688f26d737ee1f5a7a823b97c4125a1d91415afed614bec20",
            "ui-queue-remove" to "e0fa0e8e82577c8e2d69bf3257c4feccbd0ab5fa4634718a19f3c516a9e6ddc5",
            "ui-error" to "d0513e073f68faa5ed7ef5f48278d5fb8294d675f753bd6d1b904a2dd6a5d029",
            "alert-warning" to "6059a76e2f3c21ecbe295ff83a6173c60b71aa2b70272111ca21d348304c1621",
            "ui-message" to "d68bfac1a2109d7c41e92fba76a2f5e69de30cb6789c774909e9648b6a8521e5")
        fun soundId(name: String) = ResourceId.parse("reforge:sounds/$name")
        fun eventId(name: String) = ResourceId.parse("reforge:events/$name")
        // These owned placeholders can be replaced by independently qualified private recordings.
        val aliases = mapOf("hover-fire" to "heavy-plasma-fire", "amphibious-lightning-fire" to "lightning-burst",
            "spider-lightning-fire" to "lightning-burst", "scout-plasma-fire" to "plasma-fire",
            "spider-gun-fire" to "autocannon-fire", "nuke-intercept" to "building-explode",
            "bomber-impact" to "naval-missile-fire")
        require(declarations.sounds.keys == expected.keys.map(::soundId).toSet() &&
            declarations.events.keys == (expected.keys + aliases.keys).map(::eventId).toSet()) {
            "PREPARE_FAILED: bundled sound event or resource declarations differ"
        }
        for ((event, sound) in aliases) require(declarations.events.getValue(eventId(event)).sound == soundId(sound)) {
            "PREPARE_FAILED: bundled sound alias $event differs"
        }
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
