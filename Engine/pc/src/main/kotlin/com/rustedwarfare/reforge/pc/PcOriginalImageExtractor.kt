package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedImagePack
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.core.content.PngImages
import com.rustedwarfare.reforge.client.BuiltInResourcePacks
import com.rustedwarfare.reforge.client.ResourceImagePreparation
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** PC source admission, separate from the existing Android unpacked-image profile. */
internal class PcOriginalImageExtractor(private val cacheRoot: Path, private val profile: PcOriginalImageProfile? = null,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun staticBaseContracts(pack: PreparedImagePack, builtin: com.rustedwarfare.reforge.client.ResourceImageLayer) =
        staticBaseContracts(pack, builtin, null)

    fun staticBaseContracts(pack: PreparedImagePack, builtin: com.rustedwarfare.reforge.client.ResourceImageLayer,
        inputs: PcFrozenResourceInputs?) =
        if (!pack.metadata.id.value.startsWith("reforge:pc-original-images-")) emptyMap()
        else PcOriginalImageContracts.verify(pack, profile ?: PcOriginalImageProfile.forPackage(pack), builtin, cacheRoot, atomicMove, inputs)

    fun extract(input: Path): PreparedImagePack {
        checkVisualCancellation()
        val root = input.toAbsolutePath().normalize()
        val jar = root.resolve("game-lib.jar")
        fun reject(reason: String): Nothing = throw IllegalArgumentException("SOURCE_VERSION_UNSUPPORTED: $jar: $reason")
        val bytes = try {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root ||
                !Files.isRegularFile(jar, NOFOLLOW_LINKS) || jar.toRealPath() != jar)
                reject("Expected an ordinary PC installation with game-lib.jar")
            fun attributes() = Files.readAttributes(jar, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
            val before = attributes()
            if (before.first > 16L * 1024 * 1024) reject("PC code identity exceeds the existing 16 MiB input bound")
            Files.newInputStream(jar).use { it.readNBytes(16 * 1024 * 1024 + 1) }.also {
                checkVisualCancellation()
                if (before.first != it.size.toLong() || attributes() != before)
                    reject("PC code identity changed during reading")
            }
        } catch (failure: java.io.IOException) { reject("Cannot read PC code identity: ${failure.message}") }
        val accepted = profile ?: PcOriginalImageProfile.bundled()
        if (bytes.size != accepted.jarBytes || visualHash(bytes) != accepted.jarSha256)
            reject("Observed bytes=${bytes.size}, SHA-256=${visualHash(bytes)}; expected ${accepted.id}")
        require(accepted.id.matches(Regex("[a-z0-9_.-]+")) && accepted.images.isNotEmpty()) { "Invalid trusted PC image profile" }

        fun read(source: PcOriginalImageSource): ByteArray {
            checkVisualCancellation()
            fun bad(reason: String): Nothing = throw IllegalArgumentException("SOURCE_ASSET_MISMATCH: ${accepted.id} / ${source.path}: $reason")
            val file = root.resolve(source.path).normalize()
            if (!file.startsWith(root) || file == root || source.path.split('/').any { it.isBlank() || it == "." || it == ".." }) bad("Unsafe profile member")
            var cursor = root
            for (part in root.relativize(file)) {
                cursor = cursor.resolve(part)
                if (Files.isSymbolicLink(cursor) || cursor.toRealPath() != cursor) bad("Linked source member")
            }
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || source.bytes !in 1..16 * 1024 * 1024) bad("Expected a bounded ordinary source file")
            fun attributes() = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
            val before = attributes()
            if (before.first != source.bytes.toLong()) bad("Source byte count differs")
            val content = Files.newInputStream(file).use { it.readNBytes(source.bytes + 1) }
            if (content.size != source.bytes || visualHash(content) != source.sha256 || attributes() != before) bad("Source SHA-256 or identity changed")
            checkVisualCancellation()
            return content
        }

        fun checkCollection() {
            checkVisualCancellation()
            val extensions = setOf("png", "jpg", "jpeg", "jfif", "gif", "bmp", "tga", "webp", "ico", "dds", "ktx", "ktx2", "pvr", "xcf", "svg", "avif", "heic", "heif")
            val discovered = sortedSetOf<String>()
            fun bad(reason: String): Nothing = throw IllegalArgumentException("SOURCE_IMAGE_SET_CHANGED: ${accepted.id} / $root: $reason")
            try {
                for (folder in listOf("assets", "res", "font")) {
                    val directory = root.resolve(folder)
                    if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || directory.toRealPath() != directory) bad("Missing or linked official source directory $folder")
                    Files.walk(directory).use { entries -> entries.forEach { file ->
                        checkVisualCancellation()
                        val relative = root.relativize(file).toString().replace('\\', '/')
                        if (Files.isSymbolicLink(file) || file.toRealPath() != file) bad("Linked source entry $relative")
                        if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                            val extension = file.fileName.toString().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                            val prefix = Files.newInputStream(file).use { it.readNBytes(16) }.joinToString("") { "%02x".format(it) }
                            val imageSignature = listOf("89504e470d0a1a0a", "ffd8ff", "474946383761", "474946383961", "424d", "67696d702078636620", "44445320", "ab4b545820", "00000100")
                                .any(prefix::startsWith) || prefix.startsWith("52494646") && prefix.length >= 24 && prefix.substring(16, 24) == "57454250"
                            if (extension in extensions || imageSignature) discovered += relative
                        }
                    } }
                }
            } catch (failure: java.io.IOException) { bad("Cannot enumerate complete image source: ${failure.message}") }
            val expected = accepted.images.map { it.path }.toSet()
            if (discovered != expected) bad("Missing=${(expected - discovered).sorted()}; extra=${(discovered - expected).sorted()}")
        }
        checkCollection()
        val originals = accepted.images.associateWith(::read)
        require(originals.size == accepted.images.size) { "Invalid duplicate trusted PC image sources" }
        val decoded = originals.filterKeys { it.format != "XCF" }.mapValues { (source, content) ->
            require(source.format in setOf("PNG", "JPEG") && source.width != null && source.height != null) { "Invalid trusted source format" }
            if (source.format == "PNG") {
                val size = PngImages.inspect(content, allowRgb = true)
                require(size.width == source.width && size.height == source.height) { "SOURCE_ASSET_MISMATCH: ${source.path}: PNG dimensions differ" }
            }
            MemoryCacheImageInputStream(ByteArrayInputStream(content)).use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                require(readers.hasNext()) { "SOURCE_ASSET_MISMATCH: ${source.path}: No accepted image reader" }
                val reader = readers.next()
                try {
                    reader.input = stream
                    require(reader.getWidth(0) == source.width && reader.getHeight(0) == source.height && reader.formatName.equals(source.format, true)) {
                        "SOURCE_ASSET_MISMATCH: ${source.path}: Image format or dimensions differ"
                    }
                    reader.read(0)
                } finally { reader.dispose() }
            }
        }
        require(decoded.isNotEmpty()) { "SOURCE_ASSET_MISMATCH: No renderable images in accepted PC profile" }
        fun recheckSource() {
            checkVisualCancellation(); checkCollection()
            for (source in accepted.images) read(source)
            if (!Files.isRegularFile(jar, NOFOLLOW_LINKS) || jar.toRealPath() != jar) reject("PC code identity is no longer an ordinary source")
            fun attributes() = Files.readAttributes(jar, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                .let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
            val before = attributes()
            if (before.first != accepted.jarBytes.toLong()) reject("PC code identity size changed before publication")
            val current = Files.newInputStream(jar).use { it.readNBytes(accepted.jarBytes + 1) }
            if (current.size != accepted.jarBytes || attributes() != before || visualHash(current) != accepted.jarSha256)
                reject("PC code identity changed before publication")
        }
        val output = cacheRoot.toAbsolutePath().normalize().resolveSibling("extracted-original-packs")
        var ancestor = output
        while (Files.notExists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
        val canonicalOutput = ancestor.toRealPath().resolve(ancestor.relativize(output))
        require(canonicalOutput == output && !output.startsWith(root) && !root.startsWith(output)) { "SOURCE_ROOT_INVALID: PC source and extraction output overlap or are linked" }
        val profileRoot = output.resolve(accepted.id)
        if (Files.exists(profileRoot, NOFOLLOW_LINKS)) require(Files.isDirectory(profileRoot, NOFOLLOW_LINKS) && profileRoot.toRealPath() == profileRoot) {
            "SOURCE_ROOT_INVALID: Linked extraction profile output"
        }
        Files.createDirectories(profileRoot)
        val lockFile = profileRoot.resolve("prepare.lock")
        if (Files.exists(lockFile, NOFOLLOW_LINKS)) require(Files.isRegularFile(lockFile, NOFOLLOW_LINKS) && lockFile.toRealPath() == lockFile) {
            "SOURCE_ROOT_INVALID: Linked extraction lock file"
        }
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            channel.lock().use {
                checkVisualCancellation()
                val importer = PcResourcePackImporter(cacheRoot)
                fun cached(): PreparedImagePack? = try {
                    val readyFile = profileRoot.resolve("ready.json")
                    require(Files.isRegularFile(readyFile, NOFOLLOW_LINKS) && readyFile.toRealPath() == readyFile && Files.size(readyFile) <= 16 * 1024)
                    val ready = JsonReader().parse(Files.readString(readyFile))
                    val id = UUID.fromString(ready.getString("generation")).toString()
                    val directory = profileRoot.resolve("generations/$id")
                    require(Files.isDirectory(directory, NOFOLLOW_LINKS) && directory.toRealPath() == directory)
                    fun member(name: String, expectedBytes: Int? = null, expectedHash: String? = null): ByteArray {
                        checkVisualCancellation()
                        val file = directory.resolve(name).normalize()
                        require(file.startsWith(directory) && file != directory && name.split('/').none { it.isBlank() || it == "." || it == ".." })
                        var part = directory
                        for (segment in directory.relativize(file)) { part = part.resolve(segment); require(!Files.isSymbolicLink(part) && part.toRealPath() == part) }
                        require(Files.isRegularFile(file, NOFOLLOW_LINKS))
                        val size = Files.size(file)
                        require(size in 1..16L * 1024 * 1024 && (expectedBytes == null || size == expectedBytes.toLong()))
                        val content = Files.newInputStream(file).use { it.readNBytes(size.toInt() + 1) }
                        require(content.size.toLong() == size && (expectedHash == null || visualHash(content) == expectedHash))
                        return content
                    }
                    val certificate = member("extraction.json")
                    require(visualHash(certificate) == ready.getString("extractionSha256"))
                    val proof = JsonReader().parse(certificate.toString(Charsets.UTF_8))
                    require(proof.getInt("schemaVersion") == 1 && proof.getBoolean("complete") && proof.getString("profileId") == accepted.id)
                    require(proof.get("jar").getInt("bytes") == accepted.jarBytes && proof.get("jar").getString("sha256") == accepted.jarSha256)
                    val rawRows = proof.get("originalImages")
                    require(rawRows.isArray && rawRows.size == accepted.images.size)
                    val byPath = rawRows.associateBy { it.getString("path") }
                    require(byPath.size == rawRows.size && byPath.keys == accepted.images.map { it.path }.toSet())
                    val expectedUnmappedFiles = linkedSetOf("pack.json", "images.json")
                    for (source in accepted.images) {
                        val row = byPath.getValue(source.path); val file = "original/${source.path}"
                        require(row.getString("file") == file && row.getInt("bytes") == source.bytes && row.getString("sha256") == source.sha256 && row.getString("format") == source.format)
                        member(file, source.bytes, source.sha256); expectedUnmappedFiles += file
                        if (source.format != "XCF") expectedUnmappedFiles += "renderable/${visualHash(source.path.toByteArray(Charsets.UTF_8))}.png"
                    }
                    require(proof.getInt("renderableSourceImages") == decoded.size)
                    val outputs = proof.get("outputs"); require(outputs.isArray)
                    val entries = outputs.associateBy { it.getString("file") }; require(entries.size == outputs.size)
                    for ((file, row) in entries) member(file, row.getInt("bytes"), row.getString("sha256"))
                    if (accepted.mappedImages == null) require(entries.keys == expectedUnmappedFiles && proof.getInt("mappedOutputFiles") == 0 && proof.getInt("mappedResourceImages") == 0)
                    val hit = importer.load(directory).also { it.verify(); it.verifySources() }
                    require(hit.metadata.id.value == "reforge:pc-original-images-$id")
                    if (accepted.mappedImages != null) PcOriginalImageContracts.verify(hit, accepted,
                        com.rustedwarfare.reforge.client.ResourceImageLayer(BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER), emptyMap()), cacheRoot, atomicMove)
                    member("extraction.json", certificate.size, visualHash(certificate))
                    hit
                } catch (failure: java.util.concurrent.CancellationException) { throw failure }
                catch (_: Exception) { checkVisualCancellation(); null }
                cached()?.let { hit -> recheckSource(); return hit }
                val generation = UUID.randomUUID().toString()
                val staging = Files.createDirectory(profileRoot.resolve(".preparing-$generation"))
                fun write(path: String, content: ByteArray) {
                    checkVisualCancellation()
                    val file = staging.resolve(path).normalize()
                    require(file.startsWith(staging) && file != staging)
                    Files.createDirectories(file.parent)
                    Files.write(file, content, java.nio.file.StandardOpenOption.CREATE_NEW)
                }
                val originalEntries = array()
                val originalRows = linkedMapOf<PcOriginalImageSource, JsonValue>()
                for ((source, content) in originals) {
                    write("original/${source.path}", content)
                    val row = obj("path" to str(source.path), "file" to str("original/${source.path}"),
                        "bytes" to num(source.bytes), "sha256" to str(source.sha256), "format" to str(source.format)).also { row ->
                        source.width?.let { row.addChild("width", num(it)) }; source.height?.let { row.addChild("height", num(it)) }
                    }
                    originalEntries.addChild(row); originalRows[source] = row
                }
                val declarations = obj()
                var icon: String? = null
                for ((source, image) in decoded) {
                    checkVisualCancellation()
                    val pathBytes = source.path.toByteArray(Charsets.UTF_8)
                    val address = "reforge:original/pc/" + pathBytes.joinToString("") { "%02x".format(it) }
                    val filename = "renderable/${visualHash(pathBytes)}.png"
                    val rgba = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                    rgba.setRGB(0, 0, image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), 0, image.width)
                    val file = staging.resolve(filename); Files.createDirectories(file.parent)
                    require(ImageIO.write(rgba, "png", file.toFile())) { "PC_IMAGE_EXTRACTION_FAILED: Cannot write renderable PNG" }
                    declarations.addChild(address, obj("file" to str(filename), "frameWidth" to num(image.width),
                        "frameHeight" to num(image.height), "totalFrames" to num(1)))
                    originalRows.getValue(source).also { it.addChild("resourceId", str(address)); it.addChild("renderableFile", str(filename)) }
                    if (icon == null) icon = address
                }
                val mappedEntries = array()
                val mappedFiles = linkedMapOf<Path, String>()
                accepted.mappedImages?.let { mapping ->
                    val visuals = PcVisualAssetService(cacheRoot, mapping, atomicMove).prepareVisuals(
                        VisualAssetSelection(source = VisualAssetSource.ORIGINAL, originalDirectory = root))
                    val layer = ResourceImagePreparation.baseLayer(visuals, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL))
                    for ((address, image) in layer.images) {
                        val recipe = mapping.recipes.entries.single { image.path.endsWith(Path.of(it.value.getString("file"))) }.key
                        val filename = mappedFiles.getOrPut(image.path) {
                            "mapped/${visualHash(recipe.toByteArray(Charsets.UTF_8))}.png".also { write(it, Files.readAllBytes(image.path)) }
                        }
                        val declaration = image.declaration
                        declarations.addChild(address.value, obj("file" to str(filename), "frameWidth" to num(declaration.frameWidth),
                            "frameHeight" to num(declaration.frameHeight), "totalFrames" to num(declaration.totalFrames)))
                        mappedEntries.addChild(obj("resourceId" to str(address.value), "file" to str(filename), "recipe" to str(recipe)))
                        if (address.value == "reforge:units/c_tank/body") icon = address.value
                    }
                    visuals.verify()
                }
                write("images.json", json(obj("formatVersion" to num(1), "images" to declarations)))
                write("pack.json", json(obj("formatVersion" to num(1), "requiresResourceFormat" to num(1),
                    "id" to str("reforge:pc-original-images-$generation"), "name" to str("PC original images"),
                    "description" to str("Locally extracted images from the verified ${accepted.id}; only implemented bindings are used."),
                    "icon" to str(checkNotNull(icon)))))
                val outputs = array()
                Files.walk(staging).use { files -> files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                    val content = Files.readAllBytes(file)
                    outputs.addChild(obj("file" to str(staging.relativize(file).toString().replace('\\', '/')),
                        "bytes" to num(content.size), "sha256" to str(visualHash(content))))
                } }
                write("extraction.json", json(obj("schemaVersion" to num(1), "complete" to JsonValue(true),
                    "profileId" to str(accepted.id), "jar" to obj("path" to str("game-lib.jar"), "bytes" to num(bytes.size), "sha256" to str(visualHash(bytes))),
                    "originalImages" to originalEntries, "renderableSourceImages" to num(decoded.size),
                    "mappedResourceImages" to num(mappedEntries.size), "mappedOutputFiles" to num(mappedFiles.size),
                    "mappedImages" to mappedEntries, "outputs" to outputs)))
                importer.load(staging).also { it.verify(); it.verifySources() }
                recheckSource()
                val destination = profileRoot.resolve("generations/$generation")
                if (Files.exists(destination.parent, NOFOLLOW_LINKS)) require(Files.isDirectory(destination.parent, NOFOLLOW_LINKS) && destination.parent.toRealPath() == destination.parent) {
                    "SOURCE_ROOT_INVALID: Linked extraction generations output"
                }
                Files.createDirectories(destination.parent)
                atomicMove(staging, destination, false)
                val prepared = importer.load(destination).also { it.verify(); it.verifySources() }
                val temporary = Files.createTempFile(profileRoot, "ready-", ".tmp")
                Files.write(temporary, json(obj("schemaVersion" to num(1), "generation" to str(generation),
                    "extractionSha256" to str(visualHash(Files.readAllBytes(destination.resolve("extraction.json")))))))
                recheckSource()
                atomicMove(temporary, profileRoot.resolve("ready.json"), true)
                return prepared
            }
        }
    }

    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue(JsonValue.ValueType.`object`).also { value -> fields.forEach { (name, field) -> value.addChild(name, field) } }
    private fun array() = JsonValue(JsonValue.ValueType.array)
    private fun str(value: String) = JsonValue(value)
    private fun num(value: Int) = JsonValue(value.toLong())
    private fun json(value: JsonValue) = value.toJson(OutputType.json).toByteArray(Charsets.UTF_8)
}
