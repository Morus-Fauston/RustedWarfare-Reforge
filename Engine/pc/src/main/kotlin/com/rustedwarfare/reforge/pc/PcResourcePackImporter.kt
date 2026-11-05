package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.*
import com.rustedwarfare.reforge.core.content.ContentSourceIdentity
import com.rustedwarfare.reforge.core.content.PngImages
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** Bounded local import around the existing PNG validator and visual cache publisher. */
internal class PcResourcePackImporter(private val cacheRoot: Path,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun load(input: Path): PreparedImagePack = load(input, null)

    fun load(input: Path, inputs: PcFrozenResourceInputs?): PreparedImagePack {
        checkVisualCancellation()
        val absolute = input.toAbsolutePath().normalize()
        var pack = absolute.toString()
        fun bad(file: Path, reason: String): Nothing =
            throw IllegalArgumentException("RESOURCE_PACK_IMAGE_INVALID: $pack / $file: $reason")
        fun audioBad(file: Path, reason: String): Nothing =
            throw IllegalArgumentException("RESOURCE_PACK_AUDIO_INVALID: $pack / $file: $reason")
        try {
            val names = listOf("pack.json", "pack.hjson")
            val manifest = if (Files.isDirectory(absolute, NOFOLLOW_LINKS)) {
                val candidates = names.map(absolute::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
                if (candidates.size != 1) bad(absolute, "Expected exactly one pack.json or pack.hjson")
                candidates.single()
            } else absolute
            if (manifest.fileName.toString() !in names) bad(manifest, "Expected pack.json or pack.hjson")
            val root = requireNotNull(manifest.parent)
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root) || root.toRealPath() != root)
                bad(root, "Expected an ordinary canonical package root")
            val requestedCache = cacheRoot.toAbsolutePath().normalize()
            var ancestor = requestedCache
            while (Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
            val cache = ancestor.toRealPath().resolve(ancestor.relativize(requestedCache))
            if (cache.startsWith(root) || root.startsWith(cache)) bad(root, "Package source and private cache must not overlap")

            fun ordinary(path: Path) {
                if (!path.startsWith(root) || path == root) bad(path, "Input escapes package root")
                var cursor = root
                for (part in root.relativize(path)) {
                    cursor = cursor.resolve(part)
                    if (Files.isSymbolicLink(cursor) || cursor.toRealPath() != cursor) bad(path, "Linked package input")
                }
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) bad(path, "Expected an ordinary input file")
            }
            fun read(path: Path, frozen: Boolean = true): ByteArray {
                if (frozen && inputs != null) return inputs.read(path, 16 * 1024 * 1024)
                try {
                    checkVisualCancellation(); ordinary(path)
                    fun attributes() = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        .let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
                    val before = attributes()
                    if (before.first > 16L * 1024 * 1024) bad(path, "Input exceeds existing 16 MiB read bound")
                    val bytes = Files.newInputStream(path).use { it.readNBytes(16 * 1024 * 1024 + 1) }
                    if (bytes.size > 16 * 1024 * 1024 || before.first != bytes.size.toLong() || attributes() != before)
                        bad(path, "Input changed during reading or exceeds existing bound")
                    return bytes
                } catch (failure: java.io.IOException) { bad(path, "Cannot recheck package input: ${failure.message}") }
                catch (failure: SecurityException) { bad(path, "Cannot access package input: ${failure.message}") }
            }
            val captured = linkedMapOf<Path, ByteArray>()
            fun capture(path: Path): ByteArray {
                val bytes = read(path)
                val earlier = captured.putIfAbsent(path, bytes)
                if (earlier != null && !earlier.contentEquals(bytes)) bad(path, "Source changed between declarations")
                return earlier ?: bytes
            }
            val metadata = ResourcePackMetadata.read(capture(manifest), manifest.toString())
            pack = metadata.id.value
            if (VisualAssetSource.entries.any { it.resourcePackId == pack }) bad(manifest, "User pack claims a built-in ID")
            val lists = listOf("images.json", "images.hjson").map(root::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
            if (lists.size != 1) bad(root, "Expected exactly one images.json or images.hjson")
            val declarations = ResourceImageManifest.read(capture(lists.single()), lists.single().toString())
            val soundLists = listOf("sounds.json", "sounds.hjson").map(root::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
            if (soundLists.size > 1) audioBad(root, "Expected at most one sounds.json or sounds.hjson")
            if (soundLists.isNotEmpty() && metadata.requiresResourceFormat < 2)
                audioBad(soundLists.single(), "Sound declarations require resource capability 2")
            val soundDeclarations = soundLists.singleOrNull()?.let { ResourceSoundManifest.read(capture(it), it.toString()) }
            if (soundDeclarations != null && metadata.requiresResourceFormat < soundDeclarations.requiredResourceFormat)
                audioBad(soundLists.single(), "Sound declaration format ${soundDeclarations.formatVersion} requires resource capability ${soundDeclarations.requiredResourceFormat}")
            if (metadata.icon !in declarations.images) bad(manifest, "Pack must provide its own icon ${metadata.icon}")
            data class Image(val id: ResourceId, val declaration: ResourceImageDeclaration, val source: Path, val width: Int, val height: Int)
            val decodedImages = linkedMapOf<Path, com.rustedwarfare.reforge.core.content.PngImageDimensions>()
            val images = declarations.images.toSortedMap(compareBy { it.value }).map { (id, declaration) ->
                val file = root.resolve(declaration.file).normalize()
                try {
                    val bytes = capture(file)
                    val dimensions = decodedImages.getOrPut(file) {
                        PngImages.inspect(bytes).also { dimensions ->
                            MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
                                val reader = ImageIO.getImageReadersByFormatName("png").next()
                                try {
                                    reader.input = stream
                                    val decoded = reader.read(0)
                                    require(decoded.width == dimensions.width && decoded.height == dimensions.height) { "Decoded PNG dimensions differ" }
                                } finally { reader.dispose() }
                            }
                        }
                    }
                    val width = dimensions.width; val height = dimensions.height
                    require(width % declaration.totalFrames == 0) { "Frame count does not divide PNG width" }
                    require((width / declaration.totalFrames).toLong() * declaration.frameHeight == height.toLong() * declaration.frameWidth) {
                        "PNG frame aspect differs from declared logical layout"
                    }
                    Image(id, declaration, file, width, height)
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { bad(file, "$id: ${failure.message}") }
            }
            data class Sound(val id: ResourceId, val source: Path, val pcm: PcmAudio)
            val decodedSounds = linkedMapOf<Path, PcmAudio>()
            val sounds = soundDeclarations?.sounds.orEmpty().toSortedMap(compareBy { it.value }).map { (id, declaration) ->
                val file = root.resolve(declaration.file).normalize()
                try {
                    val bytes = capture(file)
                    val pcm = decodedSounds.getOrPut(file) { PcmAudioDecoder.decode(bytes, file.toString()) }
                    Sound(id, file, pcm)
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { audioBad(file, "$id: ${failure.message}") }
            }
            val canonicalSounds = decodedSounds.mapValues { (_, pcm) -> pcm.wavBytes() }
            val soundIndices = decodedSounds.keys.withIndex().associate { (index, file) -> file to index }
            val sources = captured.entries.sortedBy { it.key.toString() }.map { (path, bytes) ->
                ContentSourceIdentity(path, bytes.size.toLong(), inputs?.sha256(path) ?: visualHash(bytes))
            }
            fun quote(text: String) = OutputType.json.quoteValue(text)
            val sourceJson = sources.joinToString(",", "[", "]") {
                "{\"path\":${quote(it.path.toString())},\"bytes\":${it.bytes},\"sha256\":${quote(it.sha256)}}"
            }
            val identity = visualHash(sourceJson.toByteArray(Charsets.UTF_8))
            fun verifySources() {
                val currentLists = listOf("images.json", "images.hjson").map(root::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
                if (currentLists != lists) bad(root, "Image declaration discovery changed after checking: $currentLists")
                val currentSoundLists = listOf("sounds.json", "sounds.hjson").map(root::resolve).filter { Files.exists(it, NOFOLLOW_LINKS) }
                if (currentSoundLists != soundLists) audioBad(root, "Sound declaration discovery changed after checking: $currentSoundLists")
                for (source in sources) {
                    val current = try { read(source.path, frozen = false) } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        if (source.path in decodedSounds || source.path in soundLists) audioBad(source.path, failure.message ?: "Cannot recheck sound source")
                        throw failure
                    }
                    if (current.size.toLong() != source.bytes || visualHash(current) != source.sha256)
                        if (source.path in decodedSounds || source.path in soundLists) audioBad(source.path, "Source changed after checking; check this pack again")
                        else bad(source.path, "Source changed after checking; check this pack again")
                }
            }
            val outputs = images.mapIndexed { index, image ->
                "\"image$index\":{\"file\":\"images/$index.png\",\"target\":[${image.width},${image.height}]}"
            }.joinToString(",")
            val soundOutputs = decodedSounds.entries.mapIndexed { index, (file, pcm) ->
                "\"sound$index\":{\"file\":\"sounds/$index.wav\",\"kind\":\"pcm16\",\"channels\":${pcm.channels},\"sampleRate\":${pcm.sampleRate},\"frames\":${pcm.frames},\"sha256\":\"${visualHash(canonicalSounds.getValue(file))}\"}"
            }.joinToString(",")
            val soundProfile = if (decodedSounds.isEmpty()) "" else ",\"soundOutputs\":{$soundOutputs}"
            val profile = VisualAssetProfile.parse(("{\"schemaVersion\":1,\"profileId\":${quote("resource-pack:$pack")}," +
                "\"identity\":{\"packId\":${quote(pack)},\"sourceDigest\":${quote(identity)}},\"sources\":$sourceJson," +
                "\"unitOutputs\":{$outputs},\"terrainOutputs\":{}$soundProfile}").toByteArray(Charsets.UTF_8))
            val publisher = VisualAssetCache(cache.resolve("image-packs").resolve(profile.key), profile, atomicMove)
            val prepared = publisher.locked {
                publisher.prepare(beforePublish = ::verifySources) { staging ->
                    Files.createDirectory(staging.resolve("images"))
                    images.forEachIndexed { index, image ->
                        checkVisualCancellation()
                        Files.write(staging.resolve("images/$index.png"), captured.getValue(image.source), CREATE_NEW)
                    }
                    if (decodedSounds.isNotEmpty()) Files.createDirectory(staging.resolve("sounds"))
                    canonicalSounds.entries.forEachIndexed { index, (_, bytes) ->
                        checkVisualCancellation()
                        Files.write(staging.resolve("sounds/$index.wav"), bytes, CREATE_NEW)
                    }
                }
            }
            return PreparedImagePack(metadata, manifest, identity, images.mapIndexed { index, image ->
                image.id to PreparedResourceImage(prepared.outputs.getValue("image$index"), image.declaration, image.width, image.height)
            }.toMap(), sources, prepared::verify, ::verifySources, sounds.associate { sound ->
                val output = prepared.outputs.getValue("sound${soundIndices.getValue(sound.source)}")
                val bytes = canonicalSounds.getValue(sound.source)
                sound.id to PreparedResourceSound(output, sound.pcm.channels, sound.pcm.sampleRate, sound.pcm.frames, bytes.size, visualHash(bytes))
            }, soundDeclarations?.events.orEmpty())
        } catch (failure: CancellationException) { throw failure }
        catch (failure: IllegalArgumentException) {
            if (failure.message.orEmpty().startsWith("RESOURCE_PACK")) throw failure
            bad(absolute, failure.message ?: "Invalid package input")
        } catch (failure: java.io.IOException) { bad(absolute, "Cannot read package input: ${failure.message}") }
    }
}
