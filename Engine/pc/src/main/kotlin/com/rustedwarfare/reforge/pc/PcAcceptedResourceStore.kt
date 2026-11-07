package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.*
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonWriter.OutputType
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Supplied only after the platform's independent complete original audit, never parsed from a package. */
internal class PcAcceptedOriginalQualification(val profile: String, val basis: String, val sourceTree: String,
    val imageCertificate: String, val resourceCertificate: String,
    val contracts: Map<ResourceId, ResourceImageDeclaration>, val verifyCurrent: () -> Unit)

/** Accepted versions belong to this client. External JSON never constitutes an acceptance receipt.
 * The local authentication key separates receipts from editable packages/cache locator records;
 * it does not claim protection against an attacker controlling the user's entire account. */
internal class PcAcceptedResourceStore(cache: Path,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically,
    private val originalBasis: (String) -> String? = { null }) {
    private val root = cache.toAbsolutePath().normalize().resolve("accepted-resources")
    private val versions = root.resolve("versions")

    fun owns(manifest: Path): Boolean = manifest.isAbsolute && manifest == manifest.normalize() &&
        manifest.parent?.parent == versions && manifest.fileName.toString() == "pack.json"

    private fun ordinary(path: Path) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path) {
            "RESOURCE_PACK_ACCEPTANCE_INVALID: Missing or linked client file: $path"
        }
    }

    private fun read(path: Path, limit: Int = 16 * 1024 * 1024): ByteArray {
        checkVisualCancellation(); ordinary(path)
        require(Files.size(path) in 1..limit.toLong()) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Oversized client file: $path" }
        return Files.newInputStream(path).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) }
    }

    private fun key(): ByteArray = read(root.resolve("acceptance.key"), 32).also { require(it.size == 32) }
    private fun authenticate(bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key(), "HmacSHA256")); doFinal(bytes)
    }

    private fun <T> locked(action: () -> T): T {
        checkVisualCancellation()
        Files.createDirectories(versions)
        require(versions.toRealPath() == versions) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Linked client store" }
        val lockFile = root.resolve("publish.lock")
        if (Files.exists(lockFile, NOFOLLOW_LINKS)) ordinary(lockFile)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            require(lock != null) { "CACHE_BUSY: accepted resource publication is already owned" }
            lock.use {
                val secret = root.resolve("acceptance.key")
                if (!Files.exists(secret, NOFOLLOW_LINKS)) {
                    val temporary = root.resolve("key-${UUID.randomUUID()}.tmp")
                    Files.write(temporary, ByteArray(32).also { SecureRandom().nextBytes(it) }, CREATE_NEW)
                    checkVisualCancellation(); atomicMove(temporary, secret, false)
                }
                key()
                return action()
            }
        }
    }

    /** Only call after the full public importer (and, for original inputs, independent qualification). */
    fun accept(pack: PreparedImagePack, original: PcAcceptedOriginalQualification? = null): PreparedImagePack = locked {
        pack.verify(); pack.verifySources()
        original?.verifyCurrent?.invoke()
        val originalId = pack.metadata.id.value.startsWith("reforge:pc-original-")
        require(if (originalId) pack.metadata.id.value.startsWith("reforge:pc-original-resources-") &&
            pack.metadata.requiresResourceFormat in setOf(2, 4) && original != null && original.basis == originalBasis(original.profile)
            else pack.metadata.requiresResourceFormat == 4 && original == null) {
            "RESOURCE_PACK_ACCEPTANCE_INVALID: Original packages require their independent qualification adapter"
        }
        val stage = Files.createDirectory(versions.resolve("pending-${UUID.randomUUID()}"))
        val metadata = pack.metadata
        val meta = JsonObject().add("formatVersion", 1).add("requiresResourceFormat", 4)
            .add("id", metadata.id.value).add("name", metadata.name).add("description", metadata.description).add("icon", metadata.icon.value)
        val receipt = JsonObject().add("rules", RULES).add("metadata", meta)
        if (original != null) {
            val contracts = JsonObject()
            original.contracts.toSortedMap(compareBy { it.value }).forEach { (id, layout) ->
                contracts.add(id.value, declaration(layout.file, layout))
            }
            receipt.add("originalQualification", JsonObject().add("profile", original.profile).add("basis", original.basis)
                .add("sourceManifest", pack.manifest.toString()).add("sourceIdentity", pack.identity)
                .add("sourceTree", original.sourceTree).add("imageCertificate", original.imageCertificate)
                .add("resourceCertificate", original.resourceCertificate).add("contracts", contracts))
        }
        val images = JsonObject(); val sounds = JsonObject(); val events = JsonObject()
        val members = JsonObject()
        val copied = linkedMapOf<Path, String>()
        fun copy(path: Path, extension: String): String = copied.getOrPut(path) {
            val bytes = read(path)
            val name = "media-${copied.size}.$extension"
            Files.write(stage.resolve(name), bytes, CREATE_NEW)
            members.add(name, JsonObject().add("bytes", bytes.size).add("sha256", visualHash(bytes)))
            name
        }
        for ((id, image) in pack.images.toSortedMap(compareBy { it.value })) {
            val layout = image.declaration
            images.add(id.value, JsonObject().add("file", copy(image.path, "png"))
                .add("frameWidth", layout.frameWidth).add("frameHeight", layout.frameHeight).add("totalFrames", layout.totalFrames)
                .add("pixelWidth", image.pixelWidth).add("pixelHeight", image.pixelHeight))
        }
        for ((id, sound) in pack.sounds.toSortedMap(compareBy { it.value })) {
            sounds.add(id.value, JsonObject().add("file", copy(sound.path, "wav"))
                .add("channels", sound.channels).add("sampleRate", sound.sampleRate).add("frames", sound.frames))
        }
        for ((id, event) in pack.soundEvents.toSortedMap(compareBy { it.value })) {
            val candidates = JsonValue(JsonValue.ValueType.array)
            event.candidates.forEach { candidates.add(JsonObject().add("sound", it.sound.value).add("weight", it.weight)) }
            events.add(id.value, JsonObject().add("candidates", candidates).add("append", event.append))
        }
        receipt.add("images", images).add("sounds", sounds).add("events", events).add("members", members)
        val bytes = receipt.toJson(OutputType.json).toByteArray(Charsets.UTF_8)
        val identity = visualHash(bytes)
        Files.write(stage.resolve("receipt.json"), bytes, CREATE_NEW)
        Files.write(stage.resolve("receipt.mac"), authenticate(bytes), CREATE_NEW)
        Files.writeString(stage.resolve("pack.json"), meta.toJson(OutputType.json), CREATE_NEW)
        pack.verify(); pack.verifySources(); original?.verifyCurrent?.invoke(); checkVisualCancellation()
        // Reimport can repair a damaged version without overwriting its evidence or a running match.
        val target = versions.resolve("$identity-${UUID.randomUUID()}")
        atomicMove(stage, target, false)
        original?.verifyCurrent?.invoke()
        val restored = restore(target.resolve("pack.json"))
        restored.verify()
        restored
    }

    fun restore(manifest: Path): PreparedImagePack {
        require(owns(manifest)) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Not a client-held version" }
        val directory = manifest.parent
        val bytes = read(directory.resolve("receipt.json"), 4 * 1024 * 1024)
        val identity = visualHash(bytes)
        require(directory.fileName.toString().substringBefore('-') == identity &&
            MessageDigest.isEqual(authenticate(bytes), read(directory.resolve("receipt.mac"), 32))) {
            "RESOURCE_PACK_ACCEPTANCE_INVALID: Receipt identity or authentication differs"
        }
        val receipt = JsonReader().parse(bytes.toString(Charsets.UTF_8)).asObject()
        require(receipt.getInt("rules", -1) == RULES) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Validation rules differ; reimport" }
        val metaBytes = receipt.get("metadata").toJson(OutputType.json).toByteArray(Charsets.UTF_8)
        require(read(manifest).contentEquals(metaBytes)) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Client metadata differs" }
        val metadata = ResourcePackMetadata.read(metaBytes, manifest.toString())
        require(metadata.requiresResourceFormat == 4)
        val original = receipt.get("originalQualification")
        val contracts = if (metadata.id.value.startsWith("reforge:pc-original-")) {
            require(metadata.id.value.startsWith("reforge:pc-original-resources-") && original != null &&
                original.getString("basis") == originalBasis(original.getString("profile"))) {
                "RESOURCE_PACK_ACCEPTANCE_INVALID: Independent original qualification differs; reimport"
            }
            layouts(original.get("contracts").asObject())
        } else { require(original == null); emptyMap() }
        val members = receipt.get("members").asObject()
        fun member(file: String): Path {
            require(file.matches(Regex("media-[0-9]+\\.(png|wav)")) && members.get(file) != null)
            return directory.resolve(file)
        }
        val layouts = layouts(receipt.get("images"))
        val images = receipt.get("images").asObject().associate { row ->
            val value = row.asObject(); val file = value.getString("file", "")
            ResourceId.parse(row.name) to PreparedResourceImage(member(file), layouts.getValue(ResourceId.parse(row.name)),
                value.getInt("pixelWidth", 0), value.getInt("pixelHeight", 0))
        }
        val soundRows = receipt.get("sounds").asObject()
        val declarations = JsonObject()
        val sounds = soundRows.associate { row ->
            val value = row.asObject(); val file = value.getString("file", "")
            declarations.add(row.name, JsonObject().add("file", file))
            val expected = members.get(file).asObject()
            ResourceId.parse(row.name) to PreparedResourceSound(member(file), value.getInt("channels", 0),
                value.getInt("sampleRate", 0), value.getInt("frames", 0), expected.getInt("bytes", 0), expected.getString("sha256", ""))
        }
        val soundManifest = JsonObject().add("formatVersion", 2).add("sounds", declarations).add("events", receipt.get("events"))
        val events = ResourceSoundManifest.read(soundManifest.toJson(OutputType.json).toByteArray(), "sounds.json").events
        fun verify(paths: Set<Path>) {
            checkVisualCancellation()
            require(read(directory.resolve("receipt.json"), 4 * 1024 * 1024).contentEquals(bytes)) {
                "RESOURCE_PACK_ACCEPTANCE_INVALID: Fixed receipt changed"
            }
            for (path in paths) {
                if (path.parent != directory) continue
                val expected = requireNotNull(members.get(path.fileName.toString())).asObject()
                val content = read(path)
                require(content.size == expected.getInt("bytes", -1) && visualHash(content) == expected.getString("sha256", "")) {
                    "RESOURCE_PACK_ACCEPTANCE_INVALID: Accepted media changed: $path"
                }
            }
        }
        return PreparedImagePack(metadata, manifest, identity, images, emptyList(),
            { verify((images.values.map { it.path } + sounds.values.map { it.path }).toSet()) },
            ::checkVisualCancellation, sounds, events, ::verify, clientHeld = true, staticBaseContracts = contracts)
    }

    private fun declaration(file: String, layout: ResourceImageDeclaration) = JsonObject().add("file", file)
        .add("frameWidth", layout.frameWidth).add("frameHeight", layout.frameHeight).add("totalFrames", layout.totalFrames)
    private fun layouts(rows: JsonValue): Map<ResourceId, ResourceImageDeclaration> {
        val declarations = JsonObject()
        for (row in rows) declarations.add(row.name, JsonObject().add("file", row.getString("file"))
            .add("frameWidth", row.getInt("frameWidth")).add("frameHeight", row.getInt("frameHeight"))
            .add("totalFrames", row.getInt("totalFrames")))
        return ResourceImageManifest.read(JsonObject().add("formatVersion", 1).add("images", declarations)
            .toJson(OutputType.json).toByteArray(Charsets.UTF_8), "images.json").images
    }

    private fun JsonObject() = JsonValue(JsonValue.ValueType.`object`)
    private fun JsonValue.asObject(): JsonValue = also { require(it.isObject) }
    private fun JsonValue.add(name: String, value: String): JsonValue = add(name, JsonValue(value))
    private fun JsonValue.add(name: String, value: Int): JsonValue = add(name, JsonValue(value.toLong()))
    private fun JsonValue.add(name: String, value: Boolean): JsonValue = add(name, JsonValue(value))
    private fun JsonValue.add(name: String, value: JsonValue): JsonValue = apply { addChild(name, value) }
    private fun JsonValue.add(value: JsonValue) { addChild(value) }
    companion object { private const val RULES = 1 }
}
