package com.rustedwarfare.reforge.pc

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter.OutputType
import com.rustedwarfare.reforge.client.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

/** Reconstructs the reviewed static mapping from certified original bytes, never from an author flag. */
internal object PcOriginalImageContracts {
    /** Only the completed audit below can construct a qualification. It is scoped to one
     * immutable pack object, trusted profile and frozen input request, never a persisted flag. */
    sealed interface Qualification {
        val contracts: Map<ResourceId, ResourceImageDeclaration>
        fun certificate(pack: PreparedImagePack, profile: PcOriginalImageProfile,
            inputs: PcFrozenResourceInputs?, sha256: String): JsonValue
    }
    private class CheckedQualification(override val contracts: Map<ResourceId, ResourceImageDeclaration>,
        private val owner: PreparedImagePack, private val trusted: PcOriginalImageProfile,
        private val request: PcFrozenResourceInputs?, private val sha256: String, private val proof: JsonValue) : Qualification {
        override fun certificate(pack: PreparedImagePack, profile: PcOriginalImageProfile,
            inputs: PcFrozenResourceInputs?, sha256: String): JsonValue {
            require(inputs != null && request === inputs && owner === pack && trusted === profile && this.sha256 == sha256) {
                "SOURCE_GENERATED_RESOURCE_PACKAGE_INVALID: Image qualification belongs to another audit"
            }
            return proof
        }
    }

    /** Compare against decoded bytes whose identity is anchored by the trusted source profile. */
    fun verifyRawPixels(source: PcOriginalImageSource, bytes: ByteArray, image: PreparedResourceImage) {
        checkVisualCancellation()
        val original = requireNotNull(ImageIO.read(java.io.ByteArrayInputStream(bytes)))
        val rendered = requireNotNull(ImageIO.read(image.path.toFile()))
        require(original.width == source.width && original.height == source.height &&
            rendered.width == original.width && rendered.height == original.height &&
            original.getRGB(0, 0, original.width, original.height, null, 0, original.width).contentEquals(
                rendered.getRGB(0, 0, rendered.width, rendered.height, null, 0, rendered.width))) {
            "Raw image pixels differ: ${source.path}"
        }
    }

    fun verify(pack: PreparedImagePack, profile: PcOriginalImageProfile, builtin: ResourceImageLayer, cache: Path,
        atomicMove: (Path, Path, Boolean) -> Unit): Map<ResourceId, ResourceImageDeclaration> =
        verify(pack, profile, builtin, cache, atomicMove, null)

    fun verify(pack: PreparedImagePack, profile: PcOriginalImageProfile, builtin: ResourceImageLayer, cache: Path,
        atomicMove: (Path, Path, Boolean) -> Unit, inputs: PcFrozenResourceInputs?): Map<ResourceId, ResourceImageDeclaration> =
        qualify(pack, profile, builtin, cache, atomicMove, inputs)?.contracts.orEmpty()

    fun qualify(pack: PreparedImagePack, profile: PcOriginalImageProfile, builtin: ResourceImageLayer, cache: Path,
        atomicMove: (Path, Path, Boolean) -> Unit, inputs: PcFrozenResourceInputs?): Qualification? =
        qualify(pack, profile, builtin, cache, atomicMove, inputs, null)

    fun qualify(pack: PreparedImagePack, profile: PcOriginalImageProfile, builtin: ResourceImageLayer, cache: Path,
        atomicMove: (Path, Path, Boolean) -> Unit, inputs: PcFrozenResourceInputs?, reviewed: PcTrustedOutputVersion?): Qualification? {
        require(reviewed == null || inputs != null)
        val mapping = profile.mappedImages
        val root = pack.manifest.parent
        val certificate = root.resolve("extraction.json")
        if (Files.notExists(certificate, NOFOLLOW_LINKS)) return null
        fun bad(reason: String): Nothing = throw IllegalArgumentException("SOURCE_GENERATED_PACKAGE_INVALID: ${pack.metadata.id} / $certificate: $reason")
        try {
            fun member(name: String, size: Int? = null, hash: String? = null): ByteArray {
                checkVisualCancellation()
                val file = root.resolve(name).normalize()
                if (!file.startsWith(root) || file == root || name.split('/').any { it.isBlank() || it == "." || it == ".." }) bad("Unsafe certificate member $name")
                if (inputs != null) {
                    val frozen = inputs.read(file, 16 * 1024 * 1024)
                    if (frozen.isEmpty() || size != null && frozen.size != size || hash != null && inputs.sha256(file) != hash)
                        bad("Certificate member changed or SHA-256 differs: $name")
                    return frozen
                }
                var cursor = root
                for (part in root.relativize(file)) {
                    cursor = cursor.resolve(part)
                    if (Files.isSymbolicLink(cursor) || cursor.toRealPath() != cursor) bad("Linked certificate member $name")
                }
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) bad("Not an ordinary certificate member $name")
                fun attributes() = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    .let { Triple(it.size(), it.lastModifiedTime(), it.fileKey()) }
                val before = attributes()
                if (before.first !in 1..16L * 1024 * 1024 || size != null && before.first != size.toLong()) bad("Certificate member size differs: $name")
                val bytes = Files.newInputStream(file).use { it.readNBytes(before.first.toInt() + 1) }
                if (bytes.size.toLong() != before.first || attributes() != before || hash != null && visualHash(bytes) != hash) bad("Certificate member changed or SHA-256 differs: $name")
                return bytes
            }
            fun number(row: JsonValue, key: String): Int {
                val value = row.get(key) ?: bad("Missing certificate integer $key")
                if (!value.isLong || value.asLong() !in 0..Int.MAX_VALUE.toLong()) bad("Invalid certificate integer $key")
                return value.asInt()
            }
            val bytes = member("extraction.json")
            val proof = JsonReader().parse(bytes.toString(Charsets.UTF_8))
            if (number(proof, "schemaVersion") != 1 || proof.get("complete")?.isBoolean != true || !proof.getBoolean("complete") ||
                proof.getString("profileId") != profile.id) bad("Unknown or incomplete static-source certificate")
            val jar = proof.get("jar") ?: bad("Missing source identity")
            if (jar.getString("path") != "game-lib.jar" || number(jar, "bytes") != profile.jarBytes || jar.getString("sha256") != profile.jarSha256) bad("Source PC identity differs")
            val originalArray = proof.get("originalImages") ?: bad("Missing original source collection")
            if (!originalArray.isArray || originalArray.size != profile.images.size) bad("Original source count differs")
            val originals = originalArray.associateBy { it.getString("path") }
            if (originals.size != originalArray.size || originals.keys != profile.images.map { it.path }.toSet()) bad("Original source collection differs")
            val expectedFiles = linkedSetOf("pack.json", "images.json")
            for (source in profile.images) {
                val row = originals.getValue(source.path)
                val file = "original/${source.path}"
                if (row.getString("file") != file || number(row, "bytes") != source.bytes || row.getString("sha256") != source.sha256 ||
                    row.getString("format") != source.format) bad("Original identity differs: ${source.path}")
                if (reviewed != null) requireNotNull(inputs).requireMember(root.resolve(file), source.bytes.toLong(), source.sha256)
                else member(file, source.bytes, source.sha256)
                expectedFiles += file
                if (source.format != "XCF") {
                    val address = "reforge:original/pc/" + source.path.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
                    val png = "renderable/${visualHash(source.path.toByteArray(Charsets.UTF_8))}.png"
                    if (number(row, "width") != source.width || number(row, "height") != source.height || row.getString("resourceId") != address || row.getString("renderableFile") != png ||
                        pack.images[ResourceId.parse(address)]?.declaration?.file != png) bad("Original renderable binding differs: ${source.path}")
                    val actual = requireNotNull(pack.images[ResourceId.parse(address)])
                    require(actual.declaration.frameWidth == source.width && actual.declaration.frameHeight == source.height &&
                        actual.declaration.totalFrames == 1) { "Raw image declaration differs: $address" }
                    if (reviewed == null) verifyRawPixels(source, member(file, source.bytes, source.sha256), actual)
                    else reviewed.images.getValue(png).let { known ->
                        require(known.width == source.width && known.height == source.height &&
                            actual.pixelWidth == known.width && actual.pixelHeight == known.height)
                        requireNotNull(inputs).requireMember(root.resolve(png), known.bytes.toLong(), known.sha256)
                    }
                    expectedFiles += png
                }
            }
            if (number(proof, "renderableSourceImages") != profile.images.count { it.format != "XCF" }) bad("Renderable source count differs")

            val reference = mapping?.takeIf { reviewed == null }?.let { rule ->
                val copiedMapping = JsonReader().parse(rule.json.toJson(OutputType.json))
                copiedMapping.remove("profileId"); copiedMapping.addChild("profileId", JsonValue(profile.id + ".certified-package"))
                copiedMapping.remove("identity")
                copiedMapping.addChild("identity", JsonValue(JsonValue.ValueType.`object`).also {
                    it.addChild("manifestPath", JsonValue("extraction.json")); it.addChild("manifestBytes", JsonValue(bytes.size.toLong()))
                    it.addChild("manifestSha256", JsonValue(visualHash(bytes)))
                })
                for (source in copiedMapping.get("sources")) {
                    val path = source.getString("path")
                    source.remove("path"); source.addChild("path", JsonValue("original/$path"))
                }
                PcVisualAssetService(cache, VisualAssetProfile.parse(copiedMapping.toJson(OutputType.json).toByteArray(Charsets.UTF_8)), atomicMove)
                    .prepareVisuals(VisualAssetSelection(source = VisualAssetSource.ORIGINAL, originalDirectory = root), inputs)
            }
            val expectedImages = reviewed?.mapped?.mapValues { (id, row) ->
                val image = reviewed.images.getValue(row.file)
                val declaration = com.rustedwarfare.reforge.client.ResourceImageManifest.read(
                    ("{\"formatVersion\":1,\"images\":{${OutputType.json.quoteValue(id.value)}:" +
                        "{\"file\":${OutputType.json.quoteValue(row.file)},\"frameWidth\":${row.frameWidth}," +
                        "\"frameHeight\":${row.frameHeight},\"totalFrames\":${row.totalFrames}}}}").toByteArray(Charsets.UTF_8),
                    "reviewed-output.json").images.getValue(id)
                PreparedResourceImage(root.resolve(row.file), declaration,
                    image.width, image.height)
            } ?: reference?.let {
                ResourceImagePreparation.baseLayer(it, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL)).images
            }.orEmpty()
            val mappedArray = proof.get("mappedImages") ?: bad("Missing static mapping collection")
            if (!mappedArray.isArray || mappedArray.size != expectedImages.size || number(proof, "mappedResourceImages") != expectedImages.size) bad("Static mapping count differs")
            val declared = mappedArray.associateBy { it.getString("resourceId") }
            if (declared.size != mappedArray.size || declared.keys != expectedImages.keys.map { it.value }.toSet()) bad("Static mapping identities differ")
            val distinct = linkedSetOf<String>()
            val comparedPixels = linkedSetOf<Pair<String, String>>()
            for ((id, image) in expectedImages) {
                checkVisualCancellation()
                val recipe = reviewed?.mapped?.getValue(id)?.recipe ?: requireNotNull(mapping).recipes.entries.single {
                    image.path.endsWith(Path.of(it.value.getString("file"))) }.key
                val filename = "mapped/${visualHash(recipe.toByteArray(Charsets.UTF_8))}.png"
                val row = declared.getValue(id.value)
                val actual = pack.images[id] ?: bad("Missing static image $id")
                val a = actual.declaration; val e = image.declaration
                if (row.getString("recipe") != recipe || row.getString("file") != filename || a.file != filename ||
                    a.frameWidth != e.frameWidth || a.frameHeight != e.frameHeight || a.totalFrames != e.totalFrames ||
                    actual.pixelWidth != image.pixelWidth || actual.pixelHeight != image.pixelHeight) bad("Static layout differs: $id")
                // Aliases still prove each declaration/layout above, but compare one fixed
                // output pair only once. Both generations are freshly verified below.
                if (reviewed != null) reviewed.images.getValue(filename).let { known ->
                    requireNotNull(inputs).requireMember(root.resolve(filename), known.bytes.toLong(), known.sha256)
                } else {
                  val actualBytes = Files.readAllBytes(actual.path)
                  val expectedBytes = Files.readAllBytes(image.path)
                  if (comparedPixels.add(visualHash(actualBytes) to visualHash(expectedBytes))) {
                    val actualPixels = requireNotNull(ImageIO.read(java.io.ByteArrayInputStream(actualBytes)))
                    val expectedPixels = requireNotNull(ImageIO.read(java.io.ByteArrayInputStream(expectedBytes)))
                    if (!actualPixels.getRGB(0, 0, actualPixels.width, actualPixels.height, null, 0, actualPixels.width).contentEquals(
                            expectedPixels.getRGB(0, 0, expectedPixels.width, expectedPixels.height, null, 0, expectedPixels.width))) bad("Static pixels differ: $id")
                  }
                }
                expectedFiles += filename; distinct += filename
            }
            if (number(proof, "mappedOutputFiles") != distinct.size) bad("Distinct static output count differs")
            val outputArray = proof.get("outputs") ?: bad("Missing complete output collection")
            if (!outputArray.isArray) bad("Invalid output collection")
            val outputs = outputArray.associateBy { it.getString("file") }
            if (outputs.size != outputArray.size || outputs.keys != expectedFiles) bad("Complete output collection differs")
            for ((file, row) in outputs) {
                if (reviewed != null) requireNotNull(inputs).requireMember(root.resolve(file), number(row, "bytes").toLong(), row.getString("sha256"))
                else member(file, number(row, "bytes"), row.getString("sha256"))
            }
            member("extraction.json", bytes.size, visualHash(bytes))
            // The reviewed path consumes the current frozen capture and the importer
            // has verified its fixed generation. The outer preparation and loader
            // retain independent publication checks; do not repeat them mid-proof.
            if (reviewed == null) { pack.verify(); reference?.verify() }
            val contracts = expectedImages.keys.filter { it in builtin.images }.associateWith { builtin.images.getValue(it).declaration }
            return CheckedQualification(contracts, pack, profile, inputs, inputs?.sha256(certificate) ?: visualHash(bytes), proof)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { bad(failure.message ?: failure.javaClass.name) }
    }
}
