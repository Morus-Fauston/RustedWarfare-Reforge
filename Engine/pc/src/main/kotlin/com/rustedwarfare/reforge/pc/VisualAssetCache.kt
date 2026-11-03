package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter.OutputType
import java.nio.ByteBuffer
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Collections
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CancellationException

internal fun moveVisualAtomically(source: Path, target: Path, replace: Boolean) {
    if (replace) Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    else Files.move(source, target, ATOMIC_MOVE)
}

internal fun checkVisualCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("Visual preparation cancelled")
}

internal data class CachedVisualImage(val bytes: Int, val width: Int, val height: Int, val sha256: String)
internal data class CachedPcmSound(val bytes: Int, val channels: Int, val sampleRate: Int, val frames: Int, val sha256: String)

internal class PcPreparedVisualAssets(val generation: Path, outputs: Map<String, Path>,
    identities: Map<String, CachedVisualImage>, private val manifestSha256: String? = null,
    soundIdentities: Map<String, CachedPcmSound> = emptyMap()) {
    val outputs: Map<String, Path> = Collections.unmodifiableMap(LinkedHashMap(outputs))
    private val identities = LinkedHashMap(identities)
    private val soundIdentities = LinkedHashMap(soundIdentities)
    init { require(identities.keys.intersect(soundIdentities.keys).isEmpty() && outputs.keys == identities.keys + soundIdentities.keys) }
    /** Called again before GL recovery; never resolves the moving ready pointer. */
    fun verify() {
        try {
            if (manifestSha256 != null) require(visualHash(Files.readAllBytes(generation.resolve("manifest.json"))) == manifestSha256) {
                "PREPARE_FAILED: fixed cache manifest changed: $generation"
            }
            for ((id, path) in outputs) {
                soundIdentities[id]?.let { expected ->
                    require(expected.bytes in 44..PcmAudioDecoder.MAX_BYTES && Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                        path.toRealPath() == path && Files.size(path) == expected.bytes.toLong()) { "PREPARE_FAILED: fixed sound cache changed: $path" }
                    val bytes = Files.newInputStream(path).use { it.readNBytes(expected.bytes + 1) }
                    require(bytes.size == expected.bytes && visualHash(bytes) == expected.sha256) { "PREPARE_FAILED: fixed sound cache changed: $path" }
                    val pcm = PcmAudioDecoder.decode(bytes, path.toString())
                    require(pcm.channels == expected.channels && pcm.sampleRate == expected.sampleRate && pcm.frames == expected.frames) {
                        "PREPARE_FAILED: fixed sound format changed: $path"
                    }
                    return@let
                }
                if (id in soundIdentities) continue
                val expected = identities.getValue(id)
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path && Files.size(path) == expected.bytes.toLong()) {
                    "PREPARE_FAILED: fixed cache changed: $path"
                }
                val bytes = Files.newInputStream(path).use { it.readNBytes(expected.bytes + 1) }
                require(bytes.size == expected.bytes && bytes.size >= 24 &&
                    ByteBuffer.wrap(bytes, 0, 8).long == -8552249625308161526L &&
                    ByteBuffer.wrap(bytes, 16, 8).let { it.int == expected.width && it.int == expected.height } &&
                    visualHash(bytes) == expected.sha256) { "PREPARE_FAILED: fixed cache changed: $path" }
            }
        } catch (failure: java.io.IOException) {
            throw IllegalArgumentException("PREPARE_FAILED: cannot read fixed cache generation: $generation", failure)
        }
    }
}

internal class VisualAssetCache(private val cache: Path, private val profile: VisualAssetProfile,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun <T> locked(operation: () -> T): T {
        checkVisualCancellation()
        Files.createDirectories(cache)
        FileChannel.open(cache.resolve("prepare.lock"), CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            require(lock != null) { "CACHE_BUSY: another preparation owns this profile" }
            lock.use { return operation() }
        }
    }

    fun prepare(beforePublish: () -> Unit = ::checkVisualCancellation, produce: (Path) -> Unit): PcPreparedVisualAssets {
        checkVisualCancellation()
        Files.createDirectories(cache.resolve("generations"))
        val ready = cache.resolve("ready.json")
        if (Files.exists(ready)) {
            val hit = runCatching {
                val pointer = JsonReader().parse(Files.readString(ready))
                val id = UUID.fromString(pointer.getString("generation")).toString()
                val directory = cache.resolve("generations/$id")
                val bytes = Files.readAllBytes(directory.resolve("manifest.json"))
                require(visualHash(bytes) == pointer.getString("manifestSha256"))
                read(directory, bytes).also { it.verify() }
            }.getOrNull()
            if (hit != null) { beforePublish(); return hit }
        }
        val id = UUID.randomUUID().toString()
        val staging = Files.createDirectory(cache.resolve(".preparing-$id"))
        produce(staging)
        beforePublish()
        val entries = profile.recipes.map { (name, recipe) ->
            val bytes = Files.readAllBytes(staging.resolve(recipe.getString("file")))
            if (recipe.getString("kind", "image") == "pcm16") {
                val pcm = PcmAudioDecoder.decode(bytes, recipe.getString("file"))
                require(pcm.channels == recipe.getInt("channels") && pcm.sampleRate == recipe.getInt("sampleRate") &&
                    pcm.frames == recipe.getInt("frames") && visualHash(bytes) == recipe.getString("sha256")) { "PREPARE_FAILED: sound recipe differs: $name" }
                return@map "\"$name\":{\"kind\":\"pcm16\",\"bytes\":${bytes.size},\"channels\":${pcm.channels},\"sampleRate\":${pcm.sampleRate},\"frames\":${pcm.frames},\"sha256\":\"${visualHash(bytes)}\"}"
            }
            require(recipe.getString("kind", "image") == "image") { "PREPARE_FAILED: unknown output kind: $name" }
            val target = recipe.get("target").asIntArray()
            "\"$name\":{\"bytes\":${bytes.size},\"width\":${target[0]},\"height\":${target[1]},\"sha256\":\"${visualHash(bytes)}\"}"
        }.joinToString(",")
        val manifest = ("{\"schemaVersion\":1,\"complete\":true,\"profile\":\"${profile.key}\"," +
            "\"identity\":${profile.json.get("identity").toJson(OutputType.json)}," +
            "\"sources\":${profile.json.get("sources").toJson(OutputType.json)},\"outputs\":{$entries}}").toByteArray()
        Files.write(staging.resolve("manifest.json"), manifest)
        read(staging, manifest).verify()
        val generation = cache.resolve("generations/$id")
        beforePublish()
        atomicMove(staging, generation, false)
        val temporary = Files.createTempFile(cache, "ready-", ".tmp")
        Files.writeString(temporary, "{\"generation\":\"$id\",\"manifestSha256\":\"${visualHash(manifest)}\"}")
        beforePublish()
        atomicMove(temporary, ready, true)
        return read(generation, manifest)
    }

    private fun read(directory: Path, bytes: ByteArray): PcPreparedVisualAssets {
        val manifest = JsonReader().parse(bytes.toString(Charsets.UTF_8))
        require(manifest.getInt("schemaVersion") == 1 && manifest.getBoolean("complete") && manifest.getString("profile") == profile.key)
        require(manifest.get("sources").toJson(OutputType.json) == profile.json.get("sources").toJson(OutputType.json))
        require(manifest.get("identity").toJson(OutputType.json) == profile.json.get("identity").toJson(OutputType.json))
        val entries = manifest.get("outputs").toList()
        require(entries.all { it.getString("kind", "image") in setOf("image", "pcm16") })
        val identities = entries.filter { it.getString("kind", "image") == "image" }.associate {
            it.name to CachedVisualImage(it.getInt("bytes"), it.getInt("width"), it.getInt("height"), it.getString("sha256"))
        }
        val sounds = entries.filter { it.getString("kind", "image") == "pcm16" }.associate {
            it.name to CachedPcmSound(it.getInt("bytes"), it.getInt("channels"), it.getInt("sampleRate"), it.getInt("frames"), it.getString("sha256"))
        }
        require(identities.keys + sounds.keys == profile.recipes.keys)
        for ((name, identity) in identities) {
            val recipe = profile.recipes.getValue(name)
            require(recipe.getString("kind", "image") == "image")
            val target = recipe.get("target").asIntArray()
            require(identity.width == target[0] && identity.height == target[1] && identity.bytes >= 24)
        }
        for ((name, sound) in sounds) {
            val recipe = profile.recipes.getValue(name)
            require(recipe.getString("kind", "image") == "pcm16" && sound.bytes in 44..PcmAudioDecoder.MAX_BYTES &&
                sound.channels == recipe.getInt("channels") && sound.sampleRate == recipe.getInt("sampleRate") &&
                sound.frames == recipe.getInt("frames") && sound.sha256 == recipe.getString("sha256"))
        }
        return PcPreparedVisualAssets(directory, profile.recipes.mapValues { (_, recipe) ->
            directory.resolve(recipe.getString("file"))
        }, identities, visualHash(bytes), sounds)
    }
}
