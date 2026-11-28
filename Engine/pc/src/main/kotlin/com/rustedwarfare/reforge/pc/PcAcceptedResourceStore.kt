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
internal class PcAcceptedOriginalQualification(
    val profile: String,
    val basis: String,
    val sourceTree: String,
    val imageCertificate: String,
    val resourceCertificate: String,
    val contracts: Map<ResourceId, ResourceImageDeclaration>,
    val verifyCurrent: () -> Unit,
)

/**
 * Accepted versions belong to this client. External JSON never constitutes an acceptance receipt.
 * Authenticates base media, `ui/theme.json`, and all declarative `options` variant overlays (ADR-008 & ADR-014).
 */
internal class PcAcceptedResourceStore(
    cache: Path,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically,
    private val originalBasis: (String) -> String? = { null },
) {
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
        require(
            if (originalId) pack.metadata.id.value.startsWith("reforge:pc-original-resources-") &&
                pack.metadata.requiresResourceFormat in setOf(2, 4) && original != null && original.basis == originalBasis(original.profile)
            else pack.metadata.requiresResourceFormat in setOf(1, 2, 3, 4) && original == null
        ) {
            "RESOURCE_PACK_ACCEPTANCE_INVALID: Original packages require their independent qualification adapter"
        }
        val stage = Files.createDirectory(versions.resolve("pending-${UUID.randomUUID()}"))
        val metadata = pack.metadata
        val meta = JsonObject().add("formatVersion", 1).add("requiresResourceFormat", 4)
            .add("id", metadata.id.value).add("name", metadata.name).add("description", metadata.description).add("icon", metadata.icon.value)
        metadata.displayTextZh?.let { meta.add("displayText_zh", it) }
        metadata.descriptionZh?.let { meta.add("description_zh", it) }
        metadata.displayTextEn?.let { meta.add("displayText_en", it) }
        metadata.descriptionEn?.let { meta.add("description_en", it) }
        if (metadata.options.isNotEmpty()) {
            val optsArr = JsonValue(JsonValue.ValueType.array)
            for (opt in metadata.options) {
                optsArr.add(JsonReader().parse(opt.toPlainJsonString()))
            }
            meta.add("options", optsArr)
        }
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

        val uiMembers = JsonValue(JsonValue.ValueType.array)
        val reservedTopNames = setOf("pack.json", "pack.hjson", "receipt.json", "receipt.mac", "images.json", "images.hjson", "sounds.json", "sounds.hjson")
        val sourceRoot = pack.manifest.parent

        fun stageThemeAssets(parsedPatch: UiThemePatch) {
            if (sourceRoot == null) return
            for (relAsset in parsedPatch.assets.values.filterNotNull().distinct()) {
                if (relAsset in reservedTopNames || relAsset.matches(Regex("media-[0-9]+\\.(png|wav)"))) {
                    throw IllegalArgumentException("UI_THEME_INVALID: $relAsset: Conflicts with reserved resource pack file")
                }
                val srcAsset = UiThemeComposer.resolveStrictPackFile(sourceRoot, relAsset) ?: continue
                val assetBytes = read(srcAsset)
                val destAsset = stage.resolve(relAsset).normalize()
                require(destAsset.startsWith(stage) && destAsset != stage) { "UI_THEME_INVALID: $relAsset: Escapes stage root" }
                Files.createDirectories(destAsset.parent)
                if (Files.notExists(destAsset, NOFOLLOW_LINKS)) {
                    Files.write(destAsset, assetBytes, CREATE_NEW)
                    members.add(relAsset, JsonObject().add("bytes", assetBytes.size).add("sha256", visualHash(assetBytes)))
                    uiMembers.add(JsonValue(relAsset))
                }
            }
        }

        val themeSource = sourceRoot?.let { UiThemeComposer.resolveStrictPackFile(it, UiThemeComposer.PACK_THEME_FILE) }
        if (sourceRoot != null && themeSource != null) {
            val themeBytes = read(themeSource, UiThemeComposer.MAX_THEME_BYTES)
            val parsedPatch = UiThemePatch.read(themeBytes, themeSource.toString(), requireFull = false)
            val destTheme = stage.resolve(UiThemeComposer.PACK_THEME_FILE)
            Files.createDirectories(destTheme.parent)
            Files.write(destTheme, themeBytes, CREATE_NEW)
            members.add(UiThemeComposer.PACK_THEME_FILE, JsonObject().add("bytes", themeBytes.size).add("sha256", visualHash(themeBytes)))
            uiMembers.add(JsonValue(UiThemeComposer.PACK_THEME_FILE))
            stageThemeAssets(parsedPatch)
        }

        if (pack.variants.isNotEmpty()) {
            val variantsJson = JsonObject()
            var varThemeIdx = 0
            for ((vKey, overlay) in pack.variants.entries.sortedWith(compareBy({ it.key.optionId }, { it.key.choiceValue }))) {
                val vObj = JsonObject().add("optionId", vKey.optionId).add("choiceValue", vKey.choiceValue)
                val varThemePatch = overlay.uiThemePatch
                val varThemeBytes = overlay.uiThemeRawBytes
                if (varThemePatch != null && varThemeBytes != null) {
                    val relThemeName = "ui/variants/theme-${varThemeIdx++}.json"
                    val destVarTheme = stage.resolve(relThemeName)
                    Files.createDirectories(destVarTheme.parent)
                    Files.write(destVarTheme, varThemeBytes, CREATE_NEW)
                    members.add(relThemeName, JsonObject().add("bytes", varThemeBytes.size).add("sha256", visualHash(varThemeBytes)))
                    uiMembers.add(JsonValue(relThemeName))
                    stageThemeAssets(varThemePatch)
                    vObj.add("uiThemeFile", relThemeName)
                }
                if (overlay.images.isNotEmpty()) {
                    val vImgs = JsonObject()
                    for ((id, image) in overlay.images.toSortedMap(compareBy { it.value })) {
                        val layout = image.declaration
                        vImgs.add(id.value, JsonObject().add("file", copy(image.path, "png"))
                            .add("frameWidth", layout.frameWidth).add("frameHeight", layout.frameHeight).add("totalFrames", layout.totalFrames)
                            .add("pixelWidth", image.pixelWidth).add("pixelHeight", image.pixelHeight))
                    }
                    vObj.add("images", vImgs)
                }
                if (overlay.sounds.isNotEmpty()) {
                    val vSnds = JsonObject()
                    for ((id, sound) in overlay.sounds.toSortedMap(compareBy { it.value })) {
                        vSnds.add(id.value, JsonObject().add("file", copy(sound.path, "wav"))
                            .add("channels", sound.channels).add("sampleRate", sound.sampleRate).add("frames", sound.frames))
                    }
                    vObj.add("sounds", vSnds)
                }
                if (overlay.soundEvents.isNotEmpty()) {
                    val vEvts = JsonObject()
                    for ((id, event) in overlay.soundEvents.toSortedMap(compareBy { it.value })) {
                        val candidates = JsonValue(JsonValue.ValueType.array)
                        event.candidates.forEach { candidates.add(JsonObject().add("sound", it.sound.value).add("weight", it.weight)) }
                        vEvts.add(id.value, JsonObject().add("candidates", candidates).add("append", event.append))
                    }
                    vObj.add("events", vEvts)
                }
                variantsJson.add("${vKey.optionId}=${vKey.choiceValue}", vObj)
            }
            receipt.add("variants", variantsJson)
        }

        receipt.add("images", images).add("sounds", sounds).add("events", events).add("members", members)
        if (uiMembers.size > 0) receipt.add("uiMembers", uiMembers)
        val bytes = receipt.toJson(OutputType.json).toByteArray(Charsets.UTF_8)
        val identity = visualHash(bytes)
        Files.write(stage.resolve("receipt.json"), bytes, CREATE_NEW)
        Files.write(stage.resolve("receipt.mac"), authenticate(bytes), CREATE_NEW)
        Files.writeString(stage.resolve("pack.json"), meta.toJson(OutputType.json), CREATE_NEW)
        pack.verify(); pack.verifySources(); original?.verifyCurrent?.invoke(); checkVisualCancellation()
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
        } else {
            require(original == null); emptyMap()
        }
        val members = receipt.get("members").asObject()
        val uiKeysToCheck = linkedSetOf<String>()
        var uiNode = receipt.get("uiMembers")?.child
        while (uiNode != null) {
            uiKeysToCheck.add(uiNode.asString())
            uiNode = uiNode.next
        }
        var memNode = members.child
        while (memNode != null) {
            val n = memNode.name
            if (n != null && !n.matches(Regex("media-[0-9]+\\.(png|wav)"))) {
                uiKeysToCheck.add(n)
            }
            memNode = memNode.next
        }
        fun member(file: String): Path {
            require(file.matches(Regex("media-[0-9]+\\.(png|wav)")) && members.get(file) != null)
            return directory.resolve(file)
        }
        fun parseImagesNode(imgNode: JsonValue?): Map<ResourceId, PreparedResourceImage> {
            if (imgNode == null) return emptyMap()
            val nodeLayouts = layouts(imgNode)
            return imgNode.asObject().associate { row ->
                val value = row.asObject(); val file = value.getString("file", "")
                ResourceId.parse(row.name) to PreparedResourceImage(member(file), nodeLayouts.getValue(ResourceId.parse(row.name)),
                    value.getInt("pixelWidth", 0), value.getInt("pixelHeight", 0))
            }
        }
        fun parseSoundsAndEvents(sndNode: JsonValue?, evtNode: JsonValue?, allSoundDecls: JsonValue? = null): Pair<Map<ResourceId, PreparedResourceSound>, Map<ResourceId, ResourceSoundEvent>> {
            val mergedDeclFiles = LinkedHashMap<String, String>()
            if (allSoundDecls != null) {
                for (c in allSoundDecls) mergedDeclFiles[c.name] = c.asObject().getString("file", "")
            }
            val parsedSounds = sndNode?.asObject()?.associate { row ->
                val value = row.asObject(); val file = value.getString("file", "")
                mergedDeclFiles[row.name] = file
                val expected = members.get(file).asObject()
                ResourceId.parse(row.name) to PreparedResourceSound(member(file), value.getInt("channels", 0),
                    value.getInt("sampleRate", 0), value.getInt("frames", 0), expected.getInt("bytes", 0), expected.getString("sha256", ""))
            }.orEmpty()
            val parsedEvents = if (evtNode != null) {
                val declarations = JsonObject()
                for ((id, file) in mergedDeclFiles) {
                    declarations.add(id, JsonObject().add("file", file))
                }
                val soundManifest = JsonObject().add("formatVersion", 2).add("sounds", declarations).add("events", evtNode)
                ResourceSoundManifest.read(soundManifest.toJson(OutputType.json).toByteArray(), "sounds.json").events
            } else emptyMap()
            return parsedSounds to parsedEvents
        }

        val images = parseImagesNode(receipt.get("images"))
        val baseSoundRows = receipt.get("sounds")?.asObject()
        val (sounds, events) = parseSoundsAndEvents(baseSoundRows, receipt.get("events"))

        val baseThemeFile = if (UiThemeComposer.PACK_THEME_FILE in uiKeysToCheck) {
            UiThemeComposer.resolveStrictPackFile(directory, UiThemeComposer.PACK_THEME_FILE)
        } else null
        val baseThemeBytes = baseThemeFile?.let { read(it, UiThemeComposer.MAX_THEME_BYTES) }
        val baseThemePatch = baseThemeBytes?.let { UiThemePatch.read(it, baseThemeFile.toString(), requireFull = false) }

        val restoredVariants = LinkedHashMap<PackVariantKey, PreparedPackVariantOverlay>()
        receipt.get("variants")?.asObject()?.forEach { vRow ->
            val vObj = vRow.asObject()
            val optId = vObj.getString("optionId")
            val choiceVal = vObj.getString("choiceValue")
            val relTheme = vObj.get("uiThemeFile")?.asString()
            val varThemeBytes = relTheme?.let {
                val tf = requireNotNull(UiThemeComposer.resolveStrictPackFile(directory, it)) {
                    "RESOURCE_PACK_ACCEPTANCE_INVALID: Missing variant theme file: $it"
                }
                read(tf, UiThemeComposer.MAX_THEME_BYTES)
            }
            val varThemePatch = varThemeBytes?.let { UiThemePatch.read(it, relTheme, requireFull = false) }
            val vImages = parseImagesNode(vObj.get("images"))
            val (vSounds, vEvents) = parseSoundsAndEvents(vObj.get("sounds"), vObj.get("events"), baseSoundRows)
            restoredVariants[PackVariantKey(optId, choiceVal)] = PreparedPackVariantOverlay(
                uiThemePatch = varThemePatch,
                uiThemeRawBytes = varThemeBytes,
                uiThemeSourceRelPath = relTheme,
                images = vImages,
                sounds = vSounds,
                soundEvents = vEvents,
            )
        }

        val allMediaPaths = buildSet {
            images.values.forEach { add(it.path) }
            sounds.values.forEach { add(it.path) }
            restoredVariants.values.forEach { v ->
                v.images.values.forEach { add(it.path) }
                v.sounds.values.forEach { add(it.path) }
            }
        }

        fun verify(paths: Set<Path>) {
            checkVisualCancellation()
            require(read(directory.resolve("receipt.json"), 4 * 1024 * 1024).contentEquals(bytes)) {
                "RESOURCE_PACK_ACCEPTANCE_INVALID: Fixed receipt changed"
            }
            for (relName in uiKeysToCheck) {
                val entry = requireNotNull(members.get(relName)) {
                    "RESOURCE_PACK_ACCEPTANCE_INVALID: Missing UI member metadata: $relName"
                }.asObject()
                val memberFile = try {
                    UiThemeComposer.resolveStrictPackFile(directory, relName)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("RESOURCE_PACK_ACCEPTANCE_INVALID: Invalid UI member path $relName: ${e.message}", e)
                } ?: throw IllegalArgumentException("RESOURCE_PACK_ACCEPTANCE_INVALID: Missing accepted UI theme file: $relName")
                val actual = read(memberFile)
                require(actual.size == entry.getInt("bytes", -1) && visualHash(actual) == entry.getString("sha256", "")) {
                    "RESOURCE_PACK_ACCEPTANCE_INVALID: Accepted UI theme file changed: $relName"
                }
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
        return PreparedImagePack(
            metadata = metadata,
            manifest = manifest,
            identity = identity,
            images = images,
            sources = emptyList(),
            verifyPrepared = { verify(allMediaPaths) },
            recheckSources = ::checkVisualCancellation,
            sounds = sounds,
            soundEvents = events,
            verifySelected = ::verify,
            clientHeld = true,
            staticBaseContracts = contracts,
            baseUiThemePatch = baseThemePatch,
            baseUiThemeRawBytes = baseThemeBytes,
            variants = restoredVariants,
        )
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
