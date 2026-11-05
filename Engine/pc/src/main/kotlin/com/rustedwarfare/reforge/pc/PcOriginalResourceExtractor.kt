package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.PreparedImagePack
import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ResourceImageLayer
import com.rustedwarfare.reforge.client.ResourceImageDeclaration
import com.rustedwarfare.reforge.client.BuiltInResourcePacks
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Adds qualified audio to a new ordinary generation; it never edits an existing image package. */
internal class PcOriginalResourceExtractor(private val cache: Path, private val profile: PcOriginalResourceProfile?,
    private val extractImages: (Path) -> PreparedImagePack,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun staticBaseContracts(pack: PreparedImagePack, builtin: ResourceImageLayer): Map<ResourceId, ResourceImageDeclaration> =
        staticBaseContracts(pack, builtin, null)

    fun staticBaseContracts(pack: PreparedImagePack, builtin: ResourceImageLayer,
        inputs: PcFrozenResourceInputs?): Map<ResourceId, ResourceImageDeclaration> = staticBaseContracts(pack, builtin, inputs, null)

    fun staticBaseContracts(pack: PreparedImagePack, builtin: ResourceImageLayer,
        inputs: PcFrozenResourceInputs?, reviewed: PcTrustedOutputVersion?): Map<ResourceId, ResourceImageDeclaration> {
        val accepted = profileFor(pack.manifest, inputs)
        require(reviewed == null || reviewed.basis == PcTrustedOutputIndex.basis(accepted))
        if (inputs != null) {
            val qualification = PcOriginalImageContracts.qualify(pack, accepted.images, builtin, cache, atomicMove, inputs, reviewed)
            PcOriginalResourceContracts.verify(pack, accepted, inputs, qualification, reviewed)
            return qualification?.contracts.orEmpty()
        }
        val contracts = PcOriginalImageContracts.verify(pack, accepted.images, builtin, cache, atomicMove, inputs)
        PcOriginalResourceContracts.verify(pack, accepted, inputs)
        return contracts
    }

    fun profileFor(manifest: Path, inputs: PcFrozenResourceInputs?): PcOriginalResourceProfile =
        profile ?: PcOriginalProfileVersions.resources(manifest, inputs)

    fun extract(input: Path): PreparedImagePack {
        checkVisualCancellation()
        val source = input.toAbsolutePath().normalize()
        val image = extractImages(source)
        val accepted = profile ?: PcOriginalResourceProfile.bundled()
        require(accepted.id.matches(Regex("[a-z0-9_.-]+")) && accepted.id != accepted.images.id)
        require(accepted.sounds.isNotEmpty() && accepted.sounds.map { it.path }.distinct().size == accepted.sounds.size)
        require(accepted.events.values.all { name -> accepted.sounds.any { it.path == name } })
        accepted.events.keys.forEach(ResourceId::parse)
        fun checkSoundCollection(): List<Pair<String, Long>> {
            checkVisualCancellation()
            val discovered = sortedSetOf<String>()
            val music = mutableListOf<Pair<String, Long>>()
            val extensions = setOf("wav", "ogg", "mp3", "flac", "opus", "m4a", "aac", "aif", "aiff", "au", "wma", "mid", "midi")
            try {
                for (folder in listOf("assets", "res", "font")) Files.walk(source.resolve(folder)).use { files -> files.forEach { file ->
                    checkVisualCancellation()
                    require(!Files.isSymbolicLink(file) && file.toRealPath() == file) { "SOURCE_SOUND_SET_CHANGED: Linked official source $file" }
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                        val relative = source.relativize(file).toString().replace('\\', '/')
                        val extension = file.fileName.toString().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                        val prefix = Files.newInputStream(file).use { it.readNBytes(16) }
                        fun text(offset: Int, size: Int) = if (prefix.size >= offset + size) String(prefix, offset, size, Charsets.US_ASCII) else ""
                        val signature = text(0, 4) in setOf("OggS", "fLaC", "MThd", ".snd") || text(0, 3) == "ID3" ||
                            text(0, 4) == "RIFF" && text(8, 4) == "WAVE" ||
                            text(0, 4) == "FORM" && text(8, 4) in setOf("AIFF", "AIFC") ||
                            prefix.size >= 2 && (prefix[0].toInt() and 255) == 255 && (prefix[1].toInt() and 224) == 224
                        if (extension in extensions || signature) {
                            if (relative.startsWith("assets/music/")) music += relative to Files.size(file)
                            else discovered += relative
                        }
                    }
                } }
            } catch (failure: java.io.IOException) {
                throw IllegalArgumentException("SOURCE_SOUND_SET_CHANGED: Cannot enumerate official audio: ${failure.message}", failure)
            }
            val expected = accepted.sounds.map { it.path }.toSet()
            require(discovered == expected) { "SOURCE_SOUND_SET_CHANGED: Missing=${(expected - discovered).sorted()}; extra=${(discovered - expected).sorted()}" }
            return music.sortedBy { it.first }
        }
        val excludedMusic = checkSoundCollection()
        val originals = accepted.sounds.associateWith { member(source, it.path, it.bytes, it.sha256) }
        val audio = originals.mapValues { (entry, bytes) ->
            require(entry.format == if (entry.path.endsWith(".ogg")) "OGG_VORBIS" else "PCM16_WAV") { "Invalid trusted audio format" }
            PcmAudioDecoder.decode(bytes, entry.path).also {
                require(it.channels == entry.channels && it.sampleRate == entry.sampleRate) { "SOURCE_AUDIO_MISMATCH: ${entry.path}: Audio format differs" }
            }
        }
        fun recheckSource() {
            checkVisualCancellation()
            require(checkSoundCollection() == excludedMusic) { "SOURCE_SOUND_SET_CHANGED: Music exclusion list changed" }
            val checked = extractImages(source)
            require(checked.catalogEntry == image.catalogEntry) { "SOURCE_ASSET_MISMATCH: Image generation changed during complete preparation" }
            for (entry in accepted.sounds) member(source, entry.path, entry.bytes, entry.sha256)
            require(checkSoundCollection() == excludedMusic) { "SOURCE_SOUND_SET_CHANGED: Music exclusion list changed" }
            image.verify(); image.verifySources()
        }
        val output = cache.toAbsolutePath().normalize().resolveSibling("extracted-original-packs")
        var ancestor = output
        while (Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
        require(ancestor.toRealPath().resolve(ancestor.relativize(output)) == output && !output.startsWith(source) && !source.startsWith(output)) {
            "SOURCE_ROOT_INVALID: Complete resource output overlaps the source or is linked"
        }
        val profileRoot = output.resolve(accepted.id)
        directory(profileRoot)
        val lock = profileRoot.resolve("prepare.lock")
        if (Files.exists(lock, NOFOLLOW_LINKS)) require(Files.isRegularFile(lock, NOFOLLOW_LINKS) && lock.toRealPath() == lock) { "Linked complete-resource lock" }
        FileChannel.open(lock, CREATE, WRITE).use { channel -> channel.lock().use {
            checkVisualCancellation()
            val importer = PcResourcePackImporter(cache)
            fun checked(pack: PreparedImagePack) = pack.also {
                staticBaseContracts(it, ResourceImageLayer(BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER), emptyMap()))
            }
            data class CacheHit(val pack: PreparedImagePack, val readyBytes: ByteArray, val certificateHash: String)
            fun cached(): CacheHit? = try {
                val readyFile = profileRoot.resolve("ready.json")
                require(Files.isRegularFile(readyFile, NOFOLLOW_LINKS) && readyFile.toRealPath() == readyFile && Files.size(readyFile) <= 16 * 1024)
                val readyBytes = member(profileRoot, "ready.json")
                val ready = JsonReader().parse(readyBytes.toString(Charsets.UTF_8))
                require(ready.getInt("schemaVersion") == 1)
                val id = UUID.fromString(ready.getString("generation")).toString()
                val directory = profileRoot.resolve("generations/$id")
                require(Files.isDirectory(directory, NOFOLLOW_LINKS) && directory.toRealPath() == directory)
                val hash = ready.getString("resourceExtractionSha256")
                val certificate = member(directory, "resource-extraction.json", hash = hash)
                val proof = JsonReader().parse(certificate.toString(Charsets.UTF_8))
                require(proof.get("excludedMusic").map { it.getString("path") to it.getLong("bytes") }.sortedBy { it.first } == excludedMusic)
                val hit = checked(importer.load(directory))
                require(hit.metadata.id.value == "reforge:pc-original-resources-$id")
                member(directory, "resource-extraction.json", certificate.size, hash)
                CacheHit(hit, readyBytes, hash)
            } catch (failure: java.util.concurrent.CancellationException) { throw failure }
            catch (_: Exception) { checkVisualCancellation(); null }
            cached()?.let { hit ->
                recheckSource(); checked(hit.pack)
                member(hit.pack.manifest.parent, "resource-extraction.json", hash = hit.certificateHash)
                member(profileRoot, "ready.json", hit.readyBytes.size, visualHash(hit.readyBytes))
                return hit.pack
            }
            val generation = UUID.randomUUID().toString()
            val staging = Files.createDirectory(profileRoot.resolve(".preparing-$generation"))
            fun write(name: String, bytes: ByteArray) {
                checkVisualCancellation()
                val file = staging.resolve(name).normalize()
                require(file.startsWith(staging) && file != staging)
                Files.createDirectories(file.parent); Files.write(file, bytes, CREATE_NEW)
            }
            val imageRoot = image.manifest.parent
            val imageCertificate = member(imageRoot, "extraction.json")
            val imageProof = JsonReader().parse(imageCertificate.toString(Charsets.UTF_8))
            require(imageProof.getString("profileId") == accepted.images.id && imageProof.getBoolean("complete"))
            for (row in imageProof.get("outputs")) write(row.getString("file"), member(imageRoot, row.getString("file"), row.getInt("bytes"), row.getString("sha256")))
            val metadata = JsonReader().parse(Files.readString(staging.resolve("pack.json")))
            fun replace(value: JsonValue, name: String, field: JsonValue) { value.remove(name); value.addChild(name, field) }
            replace(metadata, "id", str("reforge:pc-original-resources-$generation"))
            replace(metadata, "name", str("PC original pictures and sounds"))
            replace(metadata, "description", str("Complete local PC pictures and sound effects. Only supported bindings are used."))
            replace(metadata, "requiresResourceFormat", num(2))
            val metadataBytes = json(metadata)
            Files.write(staging.resolve("pack.json"), metadataBytes)
            // The old proof retains its image-only range; the new proof below covers the entire package.
            val metadataRow = imageProof.get("outputs").single { it.getString("file") == "pack.json" }
            replace(metadataRow, "bytes", num(metadataBytes.size)); replace(metadataRow, "sha256", str(visualHash(metadataBytes)))
            write("extraction.json", json(imageProof))
            val sounds = obj(); val events = obj(); val originalRows = array(); val mapped = array(); val unmapped = array()
            fun address(path: String) = "reforge:original/pc/sounds/" + path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            for ((entry, decoded) in audio) {
                val raw = "original/${entry.path}"; write(raw, originals.getValue(entry))
                val canonical = "sounds/${visualHash(entry.path.toByteArray(Charsets.UTF_8))}.wav"
                val bytes = decoded.wavBytes(); write(canonical, bytes)
                val id = address(entry.path)
                sounds.addChild(id, obj("file" to str(canonical)))
                originalRows.addChild(obj("path" to str(entry.path), "file" to str(raw), "bytes" to num(entry.bytes), "sha256" to str(entry.sha256),
                    "format" to str(entry.format), "channels" to num(decoded.channels), "sampleRate" to num(decoded.sampleRate), "frames" to num(decoded.frames),
                    "resourceId" to str(id), "canonicalFile" to str(canonical), "canonicalBytes" to num(bytes.size), "canonicalSha256" to str(visualHash(bytes))))
                if (entry.path !in accepted.events.values) unmapped.addChild(str(entry.path))
            }
            for ((event, path) in accepted.events) {
                events.addChild(event, obj("sound" to str(address(path))))
                mapped.addChild(obj("eventId" to str(event), "sourcePath" to str(path), "resourceId" to str(address(path))))
            }
            write("sounds.json", json(obj("formatVersion" to num(1), "sounds" to sounds, "events" to events)))
            val outputs = array()
            Files.walk(staging).use { files -> files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                val bytes = Files.readAllBytes(file)
                outputs.addChild(obj("file" to str(staging.relativize(file).toString().replace('\\', '/')), "bytes" to num(bytes.size), "sha256" to str(visualHash(bytes))))
            } }
            val musicRows = array()
            for ((name, bytes) in excludedMusic) musicRows.addChild(obj("path" to str(name), "bytes" to JsonValue(bytes), "reason" to str("music-out-of-scope")))
            write("resource-extraction.json", json(obj("schemaVersion" to num(1), "complete" to JsonValue(true), "profileId" to str(accepted.id),
                "imageProfileId" to str(accepted.images.id), "imageExtractionSha256" to str(visualHash(Files.readAllBytes(staging.resolve("extraction.json")))),
                "originalSounds" to originalRows, "mappedSoundEvents" to mapped, "unmappedSounds" to unmapped, "excludedMusic" to musicRows, "outputs" to outputs)))
            checked(importer.load(staging)); recheckSource()
            directory(profileRoot.resolve("generations"))
            val destination = profileRoot.resolve("generations/$generation")
            atomicMove(staging, destination, false)
            val prepared = checked(importer.load(destination))
            val temporary = Files.createTempFile(profileRoot, "ready-", ".tmp")
            Files.write(temporary, json(obj("schemaVersion" to num(1), "generation" to str(generation),
                "resourceExtractionSha256" to str(visualHash(Files.readAllBytes(destination.resolve("resource-extraction.json")))))))
            recheckSource(); atomicMove(temporary, profileRoot.resolve("ready.json"), true)
            return prepared
        } }
    }

    private fun directory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) require(Files.isDirectory(path, NOFOLLOW_LINKS) && path.toRealPath() == path) { "Linked complete-resource directory: $path" }
        Files.createDirectories(path)
    }
    private fun member(root: Path, name: String, bytes: Int? = null, hash: String? = null): ByteArray {
        checkVisualCancellation()
        val file = root.resolve(name).normalize()
        require(file.startsWith(root) && file != root && name.split('/').none { it.isBlank() || it == "." || it == ".." }) { "SOURCE_AUDIO_MISMATCH: Unsafe source member $name" }
        var cursor = root
        for (part in root.relativize(file)) { cursor = cursor.resolve(part); require(!Files.isSymbolicLink(cursor) && cursor.toRealPath() == cursor) { "SOURCE_AUDIO_MISMATCH: Linked source $name" } }
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "SOURCE_AUDIO_MISMATCH: Missing source $name" }
        fun attributes() = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
        val before = attributes()
        require(before.first in 1..PcmAudioDecoder.MAX_BYTES.toLong() && (bytes == null || before.first == bytes.toLong())) { "SOURCE_AUDIO_MISMATCH: Source byte count differs: $name" }
        val content = Files.newInputStream(file).use { it.readNBytes(before.first.toInt() + 1) }
        require(content.size.toLong() == before.first && attributes() == before && (hash == null || visualHash(content) == hash)) { "SOURCE_AUDIO_MISMATCH: Source changed or SHA-256 differs: $name" }
        checkVisualCancellation(); return content
    }
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue(JsonValue.ValueType.`object`).also { value -> fields.forEach { (name, field) -> value.addChild(name, field) } }
    private fun array() = JsonValue(JsonValue.ValueType.array)
    private fun str(value: String) = JsonValue(value)
    private fun num(value: Int) = JsonValue(value.toLong())
    private fun json(value: JsonValue) = value.toJson(OutputType.json).toByteArray(Charsets.UTF_8)
}
