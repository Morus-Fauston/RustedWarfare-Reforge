package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.rustedwarfare.reforge.client.PreparedImagePack
import com.rustedwarfare.reforge.client.ResourceId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Full resource proof checks, alongside the unchanged image recipe/pixel reconstruction. */
internal object PcOriginalResourceContracts {
    fun verify(pack: PreparedImagePack, profile: PcOriginalResourceProfile) {
        val root = pack.manifest.parent
        fun bad(reason: String): Nothing = throw IllegalArgumentException("SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID: ${pack.metadata.id} / $root: $reason")
        fun text(row: JsonValue, key: String): String {
            val value = row.get(key) ?: bad("Missing certificate text $key")
            if (!value.isString) bad("Invalid certificate text $key")
            return value.asString()
        }
        fun number(row: JsonValue, key: String): Int {
            val value = row.get(key) ?: bad("Missing certificate integer $key")
            if (!value.isLong || value.asLong() !in 0..Int.MAX_VALUE.toLong()) bad("Invalid certificate integer $key")
            return value.asInt()
        }
        fun rows(row: JsonValue, key: String): List<JsonValue> {
            val value = row.get(key) ?: bad("Missing certificate array $key")
            if (!value.isArray) bad("Invalid certificate array $key")
            return value.toList()
        }
        fun member(name: String, size: Int? = null, hash: String? = null): ByteArray {
            checkVisualCancellation()
            val file = root.resolve(name).normalize()
            if (!file.startsWith(root) || file == root || name.split('/').any { it.isBlank() || it == "." || it == ".." }) bad("Unsafe certificate member $name")
            var cursor = root
            for (part in root.relativize(file)) { cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor) || cursor.toRealPath() != cursor) bad("Linked certificate member $name") }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) bad("Missing certificate member $name")
            fun attributes() = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
            val before = attributes()
            if (before.first !in 1..PcmAudioDecoder.MAX_BYTES.toLong() || size != null && before.first != size.toLong()) bad("Certificate member size differs: $name")
            val bytes = Files.newInputStream(file).use { it.readNBytes(before.first.toInt() + 1) }
            if (bytes.size.toLong() != before.first || attributes() != before || hash != null && visualHash(bytes) != hash) bad("Certificate member changed or SHA-256 differs: $name")
            return bytes
        }
        try {
            val bytes = member("resource-extraction.json")
            val proof = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            if (number(proof, "schemaVersion") != 1 || proof.get("complete")?.isBoolean != true || !proof.getBoolean("complete") ||
                text(proof, "profileId") != profile.id || text(proof, "imageProfileId") != profile.images.id) bad("Unknown or incomplete resource certificate")
            if (pack.metadata.requiresResourceFormat != 2) bad("Expected the qualified pictures/sounds capability")
            val imageBytes = member("extraction.json", hash = text(proof, "imageExtractionSha256"))
            val imageProof = JsonReader().parse(imageBytes.toString(Charsets.UTF_8))
            if (number(imageProof, "schemaVersion") != 1 || imageProof.get("complete")?.isBoolean != true || !imageProof.getBoolean("complete") ||
                text(imageProof, "profileId") != profile.images.id) bad("Image qualification differs")
            val jar = imageProof.get("jar") ?: bad("Missing source identity")
            if (text(jar, "path") != "game-lib.jar" || number(jar, "bytes") != profile.images.jarBytes || text(jar, "sha256") != profile.images.jarSha256) bad("Image source identity differs")
            val originalImages = rows(imageProof, "originalImages")
            val byImage = originalImages.associateBy { text(it, "path") }
            if (originalImages.size != byImage.size || byImage.keys != profile.images.images.map { it.path }.toSet()) bad("Image source collection differs")
            val expectedImages = linkedSetOf("pack.json", "images.json")
            val addresses = linkedSetOf<ResourceId>()
            for (source in profile.images.images) {
                val row = byImage.getValue(source.path); val file = "original/${source.path}"
                if (text(row, "file") != file || number(row, "bytes") != source.bytes || text(row, "sha256") != source.sha256 || text(row, "format") != source.format) bad("Original image identity differs: ${source.path}")
                member(file, source.bytes, source.sha256); expectedImages += file
                if (source.format != "XCF") {
                    val address = "reforge:original/pc/" + source.path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                    val png = "renderable/${visualHash(source.path.toByteArray(Charsets.UTF_8))}.png"
                    val image = pack.images[ResourceId.parse(address)] ?: bad("Missing raw image $address")
                    if (text(row, "resourceId") != address || text(row, "renderableFile") != png || image.declaration.file != png ||
                        image.declaration.frameWidth != source.width || image.declaration.frameHeight != source.height || image.declaration.totalFrames != 1) bad("Raw image declaration differs: $address")
                    expectedImages += png; addresses += ResourceId.parse(address)
                }
            }
            val mappedImages = rows(imageProof, "mappedImages")
            for (row in mappedImages) { expectedImages += text(row, "file"); addresses += ResourceId.parse(text(row, "resourceId")) }
            if (profile.images.mappedImages == null && mappedImages.isNotEmpty()) bad("Unexpected image recipe mapping")
            if (pack.images.keys != addresses) bad("Image declaration collection differs")
            val imageOutputs = rows(imageProof, "outputs"); val imageByFile = imageOutputs.associateBy { text(it, "file") }
            if (imageOutputs.size != imageByFile.size || imageByFile.keys != expectedImages) bad("Image proof output range differs")
            val expectedFiles = expectedImages.toMutableSet().apply { add("extraction.json"); add("sounds.json") }
            val originals = rows(proof, "originalSounds"); val bySound = originals.associateBy { text(it, "path") }
            if (originals.size != bySound.size || bySound.keys != profile.sounds.map { it.path }.toSet()) bad("Sound source collection differs")
            fun address(path: String) = ResourceId.parse("reforge:original/pc/sounds/" + path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) })
            for (source in profile.sounds) {
                val row = bySound.getValue(source.path); val file = "original/${source.path}"
                val canonical = "sounds/${visualHash(source.path.toByteArray(Charsets.UTF_8))}.wav"
                if (text(row, "file") != file || number(row, "bytes") != source.bytes || text(row, "sha256") != source.sha256 || text(row, "format") != source.format ||
                    text(row, "resourceId") != address(source.path).value || text(row, "canonicalFile") != canonical) bad("Original audio identity differs: ${source.path}")
                val decoded = PcmAudioDecoder.decode(member(file, source.bytes, source.sha256), source.path)
                val wav = decoded.wavBytes(); val hash = visualHash(wav)
                if (decoded.channels != source.channels || decoded.sampleRate != source.sampleRate || number(row, "channels") != decoded.channels ||
                    number(row, "sampleRate") != decoded.sampleRate || number(row, "frames") != decoded.frames || number(row, "canonicalBytes") != wav.size ||
                    text(row, "canonicalSha256") != hash) bad("Canonical audio format differs: ${source.path}")
                member(canonical, wav.size, hash)
                val sound = pack.sounds[address(source.path)] ?: bad("Missing sound declaration ${source.path}")
                if (sound.sha256 != hash || sound.bytes != wav.size || sound.channels != decoded.channels || sound.sampleRate != decoded.sampleRate || sound.frames != decoded.frames) bad("Prepared audio differs: ${source.path}")
                expectedFiles += file; expectedFiles += canonical
            }
            if (pack.sounds.keys != profile.sounds.map { address(it.path) }.toSet()) bad("Sound declaration collection differs")
            if (pack.soundEvents.keys != profile.events.keys.map(ResourceId::parse).toSet()) bad("Sound event collection differs")
            val mapped = rows(proof, "mappedSoundEvents"); val mappedByEvent = mapped.associateBy { text(it, "eventId") }
            if (mapped.size != mappedByEvent.size || mappedByEvent.keys != profile.events.keys) bad("Sound mapping evidence differs")
            for ((id, path) in profile.events) {
                val event = pack.soundEvents.getValue(ResourceId.parse(id)); val row = mappedByEvent.getValue(id)
                if (event.append || event.candidates.size != 1 || event.candidates.single().sound != address(path) || event.candidates.single().weight != 1 ||
                    text(row, "sourcePath") != path || text(row, "resourceId") != address(path).value) bad("Qualified sound mapping differs: $id")
            }
            val unmapped = rows(proof, "unmappedSounds").map { if (!it.isString) bad("Invalid unmapped source"); it.asString() }
            if (unmapped.size != unmapped.toSet().size || unmapped.toSet() != profile.sounds.map { it.path }.toSet() - profile.events.values.toSet()) bad("Unmapped sound evidence differs")
            val music = rows(proof, "excludedMusic")
            if (music.map { text(it, "path") }.distinct().size != music.size || music.any { !text(it, "path").startsWith("assets/music/") || text(it, "reason") != "music-out-of-scope" }) bad("Music exclusion evidence differs")
            val outputs = rows(proof, "outputs"); val byFile = outputs.associateBy { text(it, "file") }
            if (outputs.size != byFile.size || byFile.keys != expectedFiles) bad("Complete resource output collection differs")
            for (row in outputs) member(text(row, "file"), number(row, "bytes"), text(row, "sha256"))
            val actual = Files.walk(root).use { files -> files.map { file ->
                if (Files.isSymbolicLink(file) || file.toRealPath() != file) bad("Linked complete package entry $file")
                file
            }.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { root.relativize(it).toString().replace('\\', '/') }.toList().toSet() }
            if (actual != expectedFiles + "resource-extraction.json") bad("Unaccounted complete resource package files")
            member("resource-extraction.json", bytes.size, visualHash(bytes)); pack.verify(); pack.verifySources()
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { bad(failure.message ?: failure.javaClass.name) }
    }
}
