package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ResourceImagePreparation
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CancellationException

internal data class PcTrustedPngOutput(val bytes: Int, val sha256: String, val pixelSha256: String,
    val width: Int, val height: Int)
internal data class PcTrustedMappedOutput(val file: String, val recipe: String,
    val frameWidth: Int, val frameHeight: Int, val totalFrames: Int)

internal class PcTrustedOutputVersion(val id: String, val basis: String,
    images: Map<String, PcTrustedPngOutput>, mapped: Map<ResourceId, PcTrustedMappedOutput>, sounds: Map<String, CachedPcmSound>) {
    val images: Map<String, PcTrustedPngOutput> = Collections.unmodifiableMap(LinkedHashMap(images))
    val mapped: Map<ResourceId, PcTrustedMappedOutput> = Collections.unmodifiableMap(LinkedHashMap(mapped))
    val sounds: Map<String, CachedPcmSound> = Collections.unmodifiableMap(LinkedHashMap(sounds))
}

/** Distribution-owned facts only. Neither a user pack nor a cache file can supply this index.
 * Missing or incompatible facts disable the shortcut; the ordinary audit remains available. */
internal class PcTrustedOutputIndex private constructor(private val versions: Map<String, PcTrustedOutputVersion>,
    val unavailableReason: String?, val identity: String?) {
    fun forProfile(profile: PcOriginalResourceProfile): PcTrustedOutputVersion? {
        val value = versions[profile.id] ?: return null
        if (value.basis != basis(profile)) return null
        val raw = profile.images.images.filter { it.format != "XCF" }.associateBy {
            "renderable/${visualHash(it.path.toByteArray(Charsets.UTF_8))}.png"
        }
        val rules = profile.images.mappedImages
        val used = linkedSetOf<String>()
        rules?.json?.get("unitBindings")?.forEach { unit -> unit.forEach { slot ->
            if (slot.name == "weaponTurrets") slot.forEach { used += it.asString() } else used += slot.asString()
        } }
        rules?.json?.get("terrainOutputs")?.forEach { used += it.name }
        val mappedFiles = used.associateBy { "mapped/${visualHash(it.toByteArray(Charsets.UTF_8))}.png" }
        if (value.images.keys != raw.keys + mappedFiles.keys || value.mapped.values.map { it.file }.toSet() != mappedFiles.keys) return null
        for ((file, source) in raw) {
            val image = value.images.getValue(file)
            if (image.width != source.width || image.height != source.height) return null
        }
        for ((file, recipe) in mappedFiles) {
            val image = value.images.getValue(file)
            val target = requireNotNull(rules).recipes.getValue(recipe).get("target").asIntArray()
            if (target.size != 2 || image.width != target[0] || image.height != target[1]) return null
        }
        for ((_, row) in value.mapped) {
            if (mappedFiles[row.file] != row.recipe) return null
            val image = value.images.getValue(row.file)
            if (image.width % row.totalFrames != 0 ||
                (image.width / row.totalFrames).toLong() * row.frameHeight != image.height.toLong() * row.frameWidth) return null
        }
        val sounds = profile.sounds.associateBy { "sounds/${visualHash(it.path.toByteArray(Charsets.UTF_8))}.wav" }
        if (value.sounds.keys != sounds.keys) return null
        if (sounds.any { (file, source) -> value.sounds.getValue(file).let { it.channels != source.channels || it.sampleRate != source.sampleRate } }) return null
        return value
    }

    companion object {
        fun unavailable(reason: String) = PcTrustedOutputIndex(emptyMap(), reason, null)

        fun bundled(): PcTrustedOutputIndex = try {
            val bytes = PcTrustedOutputIndex::class.java.getResourceAsStream("/visuals/independent-output-basis-v1.json")
                ?.use { it.readNBytes(4 * 1024 * 1024 + 1) }
            if (bytes == null) unavailable("No independent output basis is bundled") else fromReviewed(bytes, currentProducers())
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { unavailable("The bundled output basis is unavailable") }

        /** Expected producer identities are supplied by trusted code, never read from the local cache. */
        fun fromReviewed(bytes: ByteArray, expectedProducers: Map<String, String>): PcTrustedOutputIndex = try {
            require(bytes.size in 1..4 * 1024 * 1024)
            require(expectedProducers.isNotEmpty() && expectedProducers.values.all { it.matches(Regex("[0-9a-f]{64}")) })
            fun fields(row: JsonValue, names: Set<String>) {
                require(row.isObject && row.map { it.name }.toSet() == names && row.size == names.size)
            }
            fun text(row: JsonValue, key: String): String = requireNotNull(row.get(key)).also { require(it.isString) }.asString()
            fun number(row: JsonValue, key: String): Int = requireNotNull(row.get(key)).also {
                require(it.isLong && it.asLong() in 1..Int.MAX_VALUE.toLong())
            }.asInt()
            fun hash(row: JsonValue, key: String): String = text(row, key).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            fun rows(row: JsonValue, key: String): List<JsonValue> = requireNotNull(row.get(key)).also {
                require(it.isArray && it.size <= 4096)
            }.toList()
            fun file(row: JsonValue, suffix: String): String = text(row, "file").also { path ->
                require(path.endsWith(suffix) && path.split('/').all { it.isNotBlank() && it != "." && it != ".." } &&
                    path.none { it == '\\' || it == ':' || it.isISOControl() })
            }
            val root = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            fields(root, setOf("schemaVersion", "producers", "profiles")); require(number(root, "schemaVersion") == 1)
            val producers = requireNotNull(root.get("producers")); fields(producers, expectedProducers.keys)
            require(producers.associate { it.name to it.also { value -> require(value.isString) }.asString() } == expectedProducers)
            val versions = linkedMapOf<String, PcTrustedOutputVersion>()
            for (profile in rows(root, "profiles")) {
                checkVisualCancellation()
                fields(profile, setOf("id", "basis", "images", "mapped", "sounds"))
                val id = text(profile, "id"); require(id.matches(Regex("[a-z0-9_.-]+")))
                val images = linkedMapOf<String, PcTrustedPngOutput>()
                for (row in rows(profile, "images")) {
                    fields(row, setOf("file", "bytes", "sha256", "pixelSha256", "width", "height"))
                    val path = file(row, ".png"); require(path.startsWith("renderable/") || path.startsWith("mapped/"))
                    val value = PcTrustedPngOutput(number(row, "bytes"), hash(row, "sha256"), hash(row, "pixelSha256"), number(row, "width"), number(row, "height"))
                    require(value.bytes in 24..16 * 1024 * 1024 && images.put(path, value) == null)
                }
                val mapped = linkedMapOf<ResourceId, PcTrustedMappedOutput>()
                for (row in rows(profile, "mapped")) {
                    fields(row, setOf("resourceId", "file", "recipe", "frameWidth", "frameHeight", "totalFrames"))
                    val path = file(row, ".png"); require(path.startsWith("mapped/") && path in images)
                    val value = PcTrustedMappedOutput(path, text(row, "recipe"), number(row, "frameWidth"), number(row, "frameHeight"), number(row, "totalFrames"))
                    require(mapped.put(ResourceId.parse(text(row, "resourceId")), value) == null)
                }
                val sounds = linkedMapOf<String, CachedPcmSound>()
                for (row in rows(profile, "sounds")) {
                    fields(row, setOf("file", "bytes", "sha256", "channels", "sampleRate", "frames"))
                    val path = file(row, ".wav"); require(path.startsWith("sounds/"))
                    val value = CachedPcmSound(number(row, "bytes"), number(row, "channels"), number(row, "sampleRate"), number(row, "frames"), hash(row, "sha256"))
                    require(value.channels in 1..2 && value.bytes in 44..PcmAudioDecoder.MAX_BYTES &&
                        44L + value.frames.toLong() * value.channels * 2 == value.bytes.toLong() && sounds.put(path, value) == null)
                }
                require(versions.put(id, PcTrustedOutputVersion(id, hash(profile, "basis"), images, mapped, sounds)) == null)
            }
            require(versions.isNotEmpty())
            PcTrustedOutputIndex(Collections.unmodifiableMap(versions), null, visualHash(bytes))
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { unavailable("Independent output facts or producer identities did not validate") }

        fun basis(profile: PcOriginalResourceProfile): String {
            val fields = mutableListOf(profile.id, profile.images.id, profile.images.jarBytes.toString(), profile.images.jarSha256,
                profile.images.mappedImages?.key.orEmpty())
            profile.images.images.sortedBy { it.path }.forEach { fields += listOf(it.path, it.bytes.toString(), it.sha256,
                it.format, it.width.toString(), it.height.toString()) }
            profile.sounds.sortedBy { it.path }.forEach { fields += listOf(it.path, it.bytes.toString(), it.sha256,
                it.format, it.channels.toString(), it.sampleRate.toString()) }
            profile.events.toSortedMap().forEach { (event, path) -> fields += listOf(event, path) }
            return visualHash(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        }

        /** Encoded-output facts are tied to the renderer, conversion, layout and codec implementations. */
        fun currentProducers(): Map<String, String> {
            // Include nested Kotlin classes and helpers, not only the selected top-level
            // entry classes. Resource entries are excluded so the output index has no
            // circular identity and a source-directory build agrees with its packaged JAR.
            fun module(type: Class<*>): String {
                val location = Path.of(type.protectionDomain.codeSource.location.toURI())
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val names = hashSetOf<String>()
                fun entry(name: String, bytes: ByteArray) {
                    checkVisualCancellation()
                    require(names.add(name)) { "Duplicate producer class entry" }
                    val encoded = name.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array()); digest.update(encoded)
                    digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); digest.update(bytes)
                }
                if (Files.isDirectory(location)) Files.walk(location).use { files ->
                    files.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                        .map { location.relativize(it).toString().replace('\\', '/') to it }.toList().sortedBy { it.first }
                        .forEach { (name, file) -> entry(name, Files.readAllBytes(file)) }
                } else java.util.jar.JarFile(location.toFile()).use { jar ->
                    jar.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.sortedBy { it.name }
                        .forEach { row -> entry(row.name, jar.getInputStream(row).use { it.readBytes() }) }
                }
                return java.util.HexFormat.of().formatHex(digest.digest())
            }
            val result = listOf(PcVisualAssetService::class.java, PreparedVisualAssets::class.java, ContentSourceIdentity::class.java)
                .associate { "module:" + it.name to module(it) }.toMutableMap()
            for (type in listOf(com.badlogic.gdx.backends.lwjgl3.audio.OggInputStream::class.java,
                com.jcraft.jorbis.DspState::class.java, com.badlogic.gdx.utils.JsonReader::class.java,
                Class.forName("org.hjson.JsonValue"), kotlin.Unit::class.java)) {
                val archive = Path.of(type.protectionDomain.codeSource.location.toURI())
                require(Files.isRegularFile(archive))
                result["archive:" + type.name] = visualHash(Files.readAllBytes(archive))
            }
            result["java.runtime"] = visualHash((System.getProperty("java.runtime.version") + "/" +
                System.getProperty("java.vendor")).toByteArray(Charsets.UTF_8))
            return result
        }
    }
}
