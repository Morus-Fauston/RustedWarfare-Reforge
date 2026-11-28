package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSettings
import com.rustedwarfare.reforge.client.VisualAssetSource
import com.rustedwarfare.reforge.client.BuiltInResourcePacks
import com.rustedwarfare.reforge.client.ResourceImageComposer
import com.rustedwarfare.reforge.client.ResourceImageLayer
import com.rustedwarfare.reforge.client.ResourceImagePreparation
import com.rustedwarfare.reforge.client.PreparedImagePack
import com.rustedwarfare.reforge.client.ContentPackChoice
import com.rustedwarfare.reforge.client.contentPackVisuals
import com.rustedwarfare.reforge.client.ResourceSoundComposer
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CancellationException

internal enum class PcResourcePreparationPath { FULL_AUDIT, PROCESS_REUSE, DISK_REUSE, MANAGED_REUSE }

internal class PcVisualAssetSettings @JvmOverloads constructor(private val store: VisualAssetSettingsStore, private val cache: Path,
    private val applicationRoot: Path, private val options: WindowsLaunchOptions,
    private val prepareOriginal: ((VisualAssetSelection) -> PreparedVisualAssets)? = null,
    private val imageExtractionProfile: PcOriginalImageProfile? = null,
    private val imageExtractionMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically,
    private val resourceExtractionProfile: PcOriginalResourceProfile? = null,
    private val trustedOutputs: PcTrustedOutputIndex = PcTrustedOutputIndex.bundled(),
) : VisualAssetSettings {
    private val auditedVisuals = PcAuditedVisualReuse()
    private val placeholders = PcPlaceholderVisualAssets()
    private val importer = PcResourcePackImporter(cache)
    private val acceptedResources = PcAcceptedResourceStore(cache, imageExtractionMove, ::originalQualificationBasis)
    private val finishedOriginal = PcOriginalPreparedPack(cache, imageExtractionMove)
    private var lastCompleteExtraction: Pair<com.rustedwarfare.reforge.client.ResourcePackCatalogEntry, PreparedImagePack>? = null
    private val originalProducerBasis by lazy {
        PcTrustedOutputIndex.currentProducers().toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" }
    }
    private val pcImageExtractor = PcOriginalImageExtractor(cache, imageExtractionProfile ?: resourceExtractionProfile?.images, imageExtractionMove)
    private val pcResourceExtractor = PcOriginalResourceExtractor(cache, resourceExtractionProfile, pcImageExtractor::extract, imageExtractionMove)
    private val baseSoundResources = PcBaseSoundResources(cache)
    private val diskAudits = PcDiskAuditStore(cache)
    private data class Publication(val assets: PreparedVisualAssets, val roots: List<Path>, val configuration: String,
        val outputBasis: String, val packs: List<PcDiskAuditPack>)
    private var completeProof: Publication? = null
    private var pendingPublication: Publication? = null
    @Volatile var lastDiskReuseReason: String? = null
        private set
    override val availableResourcePacks = BuiltInResourcePacks.all
    override val supportsOriginalImageExtraction = true
    override val supportsOriginalResourceExtraction = true
    override val initialSelection: VisualAssetSelection
    override val initialError: String?
    @Volatile override var preparationStatus: String? = null
        private set
    @Volatile override var preparationDiagnostics: Map<String, Any?> = emptyMap()
        private set
    /** Successful worker result for diagnostics; never a saved qualification flag. */
    @Volatile var lastPreparationPath: PcResourcePreparationPath? = null
        private set
    init {
        val loaded = runCatching { store.load() }
        val saved = loaded.getOrDefault(VisualAssetSelection())
        initialError = loaded.exceptionOrNull()?.message
        initialSelection = saved.copy(source = options.visualSource ?: saved.source,
            originalDirectory = options.originalAssets ?: saved.originalDirectory ?: applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize(),
            temporary = options.hasVisualOverride)
    }

    override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets = prepareForContent(selection, null)

    @Synchronized
    override fun prepareForContent(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        lastPreparationPath = null
        lastDiskReuseReason = null
        pendingPublication = null
        preparationStatus = "Checking selected content..."
        preparationDiagnostics = emptyMap()
        try { return prepareAudited(selection, content) }
        finally { preparationStatus = null }
    }

    private fun prepareAudited(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        val checked = content?.revalidate()
        val resolved = selection.copy(originalDirectory = selection.originalDirectory?.toAbsolutePath()?.normalize()
            ?: if (selection.source == VisualAssetSource.ORIGINAL) applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize() else null,
            temporary = options.hasVisualOverride)
        val roots = resolved.packs.enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId }
            .map { id -> resolved.packs.catalog.single { it.id == id }.manifest }
            .filterNot(acceptedResources::owns).map { requireNotNull(it.parent) }
        // Original-directory extraction retains its existing audit path. User packs (including v5)
        // may reuse only this settings instance's independently audited, non-GL description.
        val key = if (resolved.source == VisualAssetSource.PLACEHOLDER)
            listOf(resolved, checked?.unitId, checked?.content?.sources) else null
        preparationStatus = "Checking resource sources..."
        var preparationPath = PcResourcePreparationPath.PROCESS_REUSE
        var producedProof: Publication? = null
        var fullPackImports = 0
        var acceptedVersions: List<Map<String, String>> = emptyList()
        val result = auditedVisuals.prepare(key, roots) { verifyAuditedInputs, inputs ->
            preparationPath = PcResourcePreparationPath.FULL_AUDIT
            preparationStatus = "Preparing built-in pictures..."
            val placeholderBase = placeholders.prepare(resolved.copy(source = VisualAssetSource.PLACEHOLDER))
            val base = checked?.let { contentPackVisuals(placeholderBase, it) } ?: placeholderBase
            val metadata = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER)
            val builtinImages = ResourceImagePreparation.baseLayer(placeholderBase, metadata)
            val baseImages = if (checked == null) builtinImages else {
                // Inspect the checked built-in generation once. Only authored images need
                // another adapter pass; filter by the final bindings so removed slots stay absent.
                val contentOnly = contentPackVisuals(PreparedVisualAssets(VisualAssetSelection(),
                    "content-image-base", "content-image-base", emptyMap(), emptyMap()), checked)
                val authored = ResourceImagePreparation.baseLayer(contentOnly, metadata)
                val available = builtinImages.images + authored.images
                ResourceImageLayer(metadata, base.resourcePack.resources.mapValues { (id, path) ->
                    requireNotNull(available[id]).also { require(it.path == path) { "RESOURCE_PACK_BINDING_INVALID: $id" } }
                }, base.identity)
            }
            val original = if (resolved.source == VisualAssetSource.ORIGINAL) prepareOriginalVisuals(resolved) else null
            preparationStatus = "Preparing built-in sounds..."
            val baseSounds = baseSoundResources.prepare()
            preparationStatus = "Reading selected pictures and sounds..."
            val saved = if (key != null && inputs != null && trustedOutputs.identity != null)
                diskAudits.read(roots, requireNotNull(trustedOutputs.identity)).orEmpty() else emptyList()
            lastDiskReuseReason = trustedOutputs.unavailableReason ?: if (saved.isEmpty()) "No compatible published audit" else null
            val restoredContracts = mutableMapOf<String, Map<com.rustedwarfare.reforge.client.ResourceId,
                com.rustedwarfare.reforge.client.ResourceImageDeclaration>>()
            val users = resolved.packs.enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId }.map { id ->
                val expected = resolved.packs.catalog.single { it.id == id }
                if (acceptedResources.owns(expected.manifest)) {
                    val held = acceptedResources.restore(expected.manifest)
                    require(held.catalogEntry == expected) { "RESOURCE_PACK_ACCEPTANCE_INVALID: Catalog identity differs" }
                    preparationPath = PcResourcePreparationPath.MANAGED_REUSE
                    return@map held
                }
                val restored = if (inputs != null && key != null && id.startsWith("reforge:pc-original-resources-")) try {
                    val previous = saved.singleOrNull { it.id == id && it.manifest == expected.manifest && it.identity == expected.identity }
                    if (previous == null) null else {
                        val accepted = pcResourceExtractor.profileFor(expected.manifest, inputs)
                        val facts = requireNotNull(trustedOutputs.forProfile(accepted)) { "Missing independent output basis" }
                        require(previous.profile == accepted.id && previous.basis == facts.basis &&
                            previous.source == inputs.snapshotIdentity(expected.manifest.parent)) { "Published source or profile differs" }
                        val pack = importer.restoreReviewed(expected.manifest, inputs, facts, previous.generation)
                        require(pack.catalogEntry == expected) { "Published catalog identity differs" }
                        require(visualHash(Files.readAllBytes(previous.generation.resolve("manifest.json"))) == previous.manifestSha256) {
                            "Published fixed manifest differs"
                        }
                        restoredContracts[id] = pcResourceExtractor.staticBaseContracts(pack, builtinImages, inputs, facts)
                        preparationPath = PcResourcePreparationPath.DISK_REUSE
                        pack
                    }
                } catch (failure: CancellationException) { throw failure }
                  catch (failure: Exception) { lastDiskReuseReason = failure.message ?: failure.javaClass.simpleName; null }
                else null
                (restored ?: run { fullPackImports++; importer.load(expected.manifest, inputs) }).also { pack ->
                    require(pack.catalogEntry == expected) { "RESOURCE_PACK_SOURCE_CHANGED: $id / ${expected.manifest}: Pack changed after checking; check it again" }
                }
            }
            acceptedVersions = users.filter { it.clientHeld }.map {
                mapOf("id" to it.metadata.id.value, "identity" to it.identity, "manifest" to it.manifest.toString())
            }
            preparationStatus = "Checking picture and sound qualifications..."
            val resolvedVariantsByPackId = users.associate { pack ->
                pack.metadata.id.value to pack.resolveActiveVariant(resolved.optionsFor(pack.metadata.id.value))
            }
            val layers = users.associate { pack ->
                val activeVar = resolvedVariantsByPackId.getValue(pack.metadata.id.value)
                pack.metadata.id.value to
                    ResourceImageLayer(pack.metadata, activeVar.images, pack.identity, pack.sources) {
                        if (key == null) pack.verifySources() else verifyAuditedInputs()
                    }
                        .withSounds(activeVar.sounds, activeVar.soundEvents)
                        .withStaticBaseContracts(
                            if (pack.clientHeld) pack.staticBaseContracts else restoredContracts[pack.metadata.id.value]
                                ?: if (pack.metadata.id.value.startsWith("reforge:pc-original-resources-"))
                                    pcResourceExtractor.staticBaseContracts(pack, builtinImages, inputs, reviewedOutputs(pack, inputs))
                                else pcImageExtractor.staticBaseContracts(pack, builtinImages, inputs)
                        )
            }.toMutableMap()
            original?.let { layers[VisualAssetSource.ORIGINAL.resourcePackId] =
                ResourceImagePreparation.baseLayer(it, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL)) }
            val images = ResourceImageComposer.compose(baseImages.withSounds(baseSounds.sounds, baseSounds.events),
                resolved.packs.enabled.map(layers::getValue))
            preparationStatus = "Checking prepared resources before loading..."
            val sounds = ResourceSoundComposer.compose(images)
            val used = (base.resourcePack.resources.keys.map { images.images.getValue(it).path } + sounds.sounds.values.map { it.path }).toSet()
            val userPackRootsHighToLow = users.mapNotNull { it.manifest.parent }
            val themePatches = mutableListOf<com.rustedwarfare.reforge.client.UiThemePatch>()
            val unmanagedThemeHashes = linkedMapOf<Pair<Path, String>, Triple<Int, Long, String>>()
            val unmanagedAbsentThemes = mutableListOf<Path>()
            fun readBoundedFile(file: Path, limit: Int): ByteArray = Files.newInputStream(file).use {
                it.readNBytes(limit + 1)
            }.also {
                require(it.size <= limit) { "UI_THEME_INVALID: $file: File exceeds $limit byte limit" }
            }
            fun recordUnmanagedPatchAssets(root: Path, relThemeFile: String, themeBytes: ByteArray, patch: com.rustedwarfare.reforge.client.UiThemePatch) {
                val themeLimit = com.rustedwarfare.reforge.client.UiThemeComposer.MAX_THEME_BYTES
                unmanagedThemeHashes[root to relThemeFile] =
                    Triple(themeLimit, themeBytes.size.toLong(), visualHash(themeBytes))
                for (relAsset in patch.assets.values.filterNotNull().distinct()) {
                    val assetFile = com.rustedwarfare.reforge.client.UiThemeComposer.resolveStrictPackFile(root, relAsset) ?: continue
                    val assetLimit = 16 * 1024 * 1024
                    val assetBytes = if (inputs != null) inputs.read(assetFile, assetLimit) else readBoundedFile(assetFile, assetLimit)
                    unmanagedThemeHashes[root to relAsset] = Triple(assetLimit, assetBytes.size.toLong(), visualHash(assetBytes))
                }
            }
            for (pack in users) {
                val root = pack.manifest.parent ?: continue
                val activeVar = resolvedVariantsByPackId.getValue(pack.metadata.id.value)
                if (activeVar.uiThemePatchesLowToHigh.isNotEmpty()) {
                    // Within a single pack, later options override earlier options and base `ui/theme.json`;
                    // `themePatches` is ordered high-to-low across and within packs.
                    themePatches.addAll(activeVar.uiThemePatchesLowToHigh.asReversed())
                    if (!pack.clientHeld) {
                        val bPatch = pack.baseUiThemePatch
                        val bBytes = pack.baseUiThemeRawBytes
                        if (bPatch != null && bBytes != null) {
                            recordUnmanagedPatchAssets(
                                root,
                                com.rustedwarfare.reforge.client.UiThemeComposer.PACK_THEME_FILE,
                                bBytes,
                                bPatch,
                            )
                        }
                        for (opt in pack.metadata.options) {
                            val chosen = opt.resolveValueOrDefault(resolved.optionsFor(pack.metadata.id.value)[opt.id])
                            val varOverlay = pack.variants[com.rustedwarfare.reforge.client.PackVariantKey(opt.id, chosen)]
                            val vPatch = varOverlay?.uiThemePatch
                            val vBytes = varOverlay?.uiThemeRawBytes
                            val vRel = varOverlay?.uiThemeSourceRelPath
                            if (vPatch != null && vBytes != null && vRel != null) {
                                recordUnmanagedPatchAssets(root, vRel, vBytes, vPatch)
                            }
                        }
                    }
                    continue
                }
                val themeFile = com.rustedwarfare.reforge.client.UiThemeComposer.resolveStrictPackFile(
                    root,
                    com.rustedwarfare.reforge.client.UiThemeComposer.PACK_THEME_FILE,
                )
                if (themeFile == null) {
                    if (!pack.clientHeld) unmanagedAbsentThemes.add(root)
                    continue
                }
                val themeLimit = com.rustedwarfare.reforge.client.UiThemeComposer.MAX_THEME_BYTES
                val themeBytes = if (!pack.clientHeld && inputs != null) {
                    inputs.read(themeFile, themeLimit)
                } else {
                    readBoundedFile(themeFile, themeLimit)
                }
                val patch = com.rustedwarfare.reforge.client.UiThemePatch.read(themeBytes, themeFile.toString(), requireFull = false)
                themePatches += patch
                if (!pack.clientHeld) {
                    recordUnmanagedPatchAssets(root, com.rustedwarfare.reforge.client.UiThemeComposer.PACK_THEME_FILE, themeBytes, patch)
                }
            }
            val baseThemeChoice = com.rustedwarfare.reforge.client.BuiltInUiThemeId.fromCodeOrDefault(
                resolved.optionsFor(VisualAssetSource.PLACEHOLDER.resourcePackId)[com.rustedwarfare.reforge.client.UserSettings.BASE_THEME_OPTION_ID],
                com.rustedwarfare.reforge.client.BuiltInUiThemeId.DARK,
            )
            val composedTheme = com.rustedwarfare.reforge.client.UiThemeComposer.compose(
                overridesHighToLow = themePatches,
                base = com.rustedwarfare.reforge.client.UiTheme.builtIn(baseThemeChoice),
            )
            val themeAssetResolver = com.rustedwarfare.reforge.client.UiThemeAssetResolver(userPackRootsHighToLow)
            fun verifyUnmanagedThemes() {
                for (absentRoot in unmanagedAbsentThemes) {
                    val nowPresent = com.rustedwarfare.reforge.client.UiThemeComposer.resolveStrictPackFile(
                        absentRoot,
                        com.rustedwarfare.reforge.client.UiThemeComposer.PACK_THEME_FILE,
                    )
                    require(nowPresent == null) { "RESOURCE_PACK_SOURCE_CHANGED: $nowPresent: Theme appeared after checking; check this pack again" }
                }
                for ((keyPair, expected) in unmanagedThemeHashes) {
                    val (parentRoot, rel) = keyPair
                    val (limit, expectedSize, expectedSha) = expected
                    val rechecked = com.rustedwarfare.reforge.client.UiThemeComposer.resolveStrictPackFile(parentRoot, rel)
                    require(rechecked != null) { "RESOURCE_PACK_SOURCE_CHANGED: $parentRoot/$rel: Theme resource missing after checking" }
                    val currentBytes = readBoundedFile(rechecked, limit)
                    require(currentBytes.size.toLong() == expectedSize && visualHash(currentBytes) == expectedSha) {
                        "RESOURCE_PACK_SOURCE_CHANGED: $rechecked: Theme resource changed after checking; check this pack again"
                    }
                }
            }
            val prepared = base.withConfiguration(resolved, original?.profileId ?: base.profileId, original?.identity ?: base.identity)
                .withResourceImages(images) {
                    baseSounds.verify()
                    original?.verify()
                    users.forEach { it.verifyUsed(used) }
                    verifyUnmanagedThemes()
                }
                .withResourceSounds(sounds)
                .withUiTheme(composedTheme, themeAssetResolver, themePatches)
                .also { it.verifyForPublication() }
            if (key != null && inputs != null && trustedOutputs.identity != null) {
                val records = users.mapNotNull { pack ->
                    if (pack.clientHeld || !pack.metadata.id.value.startsWith("reforge:pc-original-resources-")) return@mapNotNull null
                    val accepted = pcResourceExtractor.profileFor(pack.manifest, inputs)
                    val facts = trustedOutputs.forProfile(accepted) ?: return@mapNotNull null
                    // A semantically valid alternate encoding may pass the full audit but cannot
                    // reuse this distribution's exact-byte output facts.
                    if (pack.images.values.any { image ->
                        val known = facts.images[image.declaration.file]
                        val source = inputs.identityFor(pack.manifest.parent.resolve(image.declaration.file))
                        known == null || source.bytes != known.bytes.toLong() || source.sha256 != known.sha256
                    }) return@mapNotNull null
                    val generation = pack.images.values.firstOrNull()?.path?.parent?.parent ?: return@mapNotNull null
                    PcDiskAuditPack(pack.metadata.id.value, pack.manifest, pack.identity, accepted.id, facts.basis,
                        inputs.snapshotIdentity(pack.manifest.parent), generation, visualHash(Files.readAllBytes(generation.resolve("manifest.json"))))
                }
                if (records.isNotEmpty()) producedProof = Publication(prepared, roots,
                    PcDiskAuditStore.configuration(resolved, checked), requireNotNull(trustedOutputs.identity), records)
            }
            prepared
        }
        if (preparationPath != PcResourcePreparationPath.PROCESS_REUSE) completeProof = producedProof
        pendingPublication = completeProof?.takeIf { it.assets === result }
        lastPreparationPath = preparationPath
        if (preparationPath == PcResourcePreparationPath.PROCESS_REUSE) {
            acceptedVersions = result.resolvedImages?.layers.orEmpty().filter {
                layer -> resolved.packs.catalog.any { it.id == layer.metadata.id.value && acceptedResources.owns(it.manifest) }
            }.map { layer -> val entry = resolved.packs.catalog.single { it.id == layer.metadata.id.value }
                mapOf("id" to entry.id, "identity" to entry.identity, "manifest" to entry.manifest.toString()) }
        }
        preparationDiagnostics = mapOf("path" to preparationPath.name, "wholePackImports" to fullPackImports,
            "externalSourceTreeRoots" to roots.map { it.toString() }, "acceptedVersions" to acceptedVersions,
            "activeProfileId" to result.profileId,
            "activeUnitVisualCount" to result.units.size, "resolvedUnitImageCount" to (result.resolvedImages?.images?.keys?.count { it.value.startsWith("reforge:units/") } ?: 0),
            "boundary" to "Worker branch receipt; media integrity checks remain on selected fixed paths")
        return result
    }

    /**
     * Resolves the verified original visual profile for direct `--original-assets` launch (UI-P003 §4.2):
     * - When the supplied original directory contains `game-lib.jar` (PC Steam installation) and no explicit
     *   custom preparer was injected, selects the bundled qualified PC 66-unit mapped profile
     *   (`pc-original-images-profile.json`, strict jar + 768/85 PNG SHA-256 verification under ADR-008).
     * - Otherwise retains the existing verified Android 25-unit `original-profile.json` path.
     */
    private fun prepareOriginalVisuals(selection: VisualAssetSelection): PreparedVisualAssets {
        prepareOriginal?.let { return it(selection) }
        val root = selection.originalDirectory?.toAbsolutePath()?.normalize()
        val isPcInstall = root != null &&
            Files.isRegularFile(root.resolve("game-lib.jar"), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val profile = if (isPcInstall) {
            val pcProfile = imageExtractionProfile ?: resourceExtractionProfile?.images ?: PcOriginalImageProfile.bundled()
            requireNotNull(pcProfile.mappedImages) { "VISUAL_MAPPING_UNSUPPORTED: PC original profile lacks mappedImages" }
        } else {
            VisualAssetProfile.bundled()
        }
        return PcVisualAssetService(cache, profile, imageExtractionMove).prepareVisuals(selection)
    }

    /** Complete fresh qualification may use independently derived distribution facts as
     * well. It is still FULL_AUDIT: no prior descriptor/generation is admitted here, all
     * members and original identities are checked, and publication rechecks remain. A
     * valid alternate encoding without exact-byte facts retains reconstruction below. */
    private fun reviewedOutputs(pack: PreparedImagePack, inputs: PcFrozenResourceInputs?): PcTrustedOutputVersion? {
        if (inputs == null) return null
        val facts = trustedOutputs.forProfile(pcResourceExtractor.profileFor(pack.manifest, inputs)) ?: return null
        return try {
            if (pack.images.values.map { it.declaration.file }.toSet() != facts.images.keys) return null
            fun same(file: String, bytes: Int, hash: String): Boolean = inputs.identityFor(pack.manifest.parent.resolve(file)).let {
                it.bytes == bytes.toLong() && it.sha256 == hash
            }
            if (facts.images.all { (file, known) -> same(file, known.bytes, known.sha256) } &&
                facts.sounds.all { (file, known) -> same(file, known.bytes, known.sha256) }) facts else null
        } catch (failure: CancellationException) { throw failure }
          catch (_: IllegalArgumentException) { null }
    }

    @Synchronized override fun applyPrepared(prepared: PreparedVisualAssets) {
        prepared.verify()
        store.save(prepared.selection.copy(temporary = options.hasVisualOverride))
        onPublished(prepared)
    }

    @Synchronized override fun onPublished(prepared: PreparedVisualAssets) {
        val publication = pendingPublication?.takeIf { it.assets === prepared } ?: return
        pendingPublication = null
        try { diskAudits.write(publication.roots, publication.configuration, publication.outputBasis, publication.packs) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { lastDiskReuseReason = "Audit was not persisted: ${failure.message ?: failure.javaClass.simpleName}" }
    }

    @Synchronized
    override fun checkImagePack(path: Path): PreparedImagePack {
        if (acceptedResources.owns(path)) return acceptedResources.restore(path).also { pack ->
            pack.verifyUsed(setOf(pack.images.getValue(pack.metadata.icon).path))
        }
        val pack = importer.load(path)
        if (pack.metadata.id.value.startsWith("reforge:pc-original-resources-")) {
            val qualificationRoot = if (pack.metadata.requiresResourceFormat == 4) finishedOriginal.qualificationRoot(pack) else pack.manifest.parent
            return PcFrozenResourceInputs.capture(listOf(pack.manifest.parent, qualificationRoot).distinct()).use { inputs ->
                val qualified = if (pack.metadata.requiresResourceFormat == 4) importer.load(qualificationRoot, inputs) else pack
                val profile = pcResourceExtractor.profileFor(qualified.manifest, inputs)
                val base = placeholders.prepare(initialSelection.copy(source = VisualAssetSource.PLACEHOLDER))
                val builtin = ResourceImagePreparation.baseLayer(base, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
                val contracts = pcResourceExtractor.staticBaseContracts(qualified, builtin, inputs, reviewedOutputs(qualified, inputs))
                if (pack.metadata.requiresResourceFormat == 4) finishedOriginal.verifyEquivalent(pack, qualified)
                val qualification = PcAcceptedOriginalQualification(profile.id, requireNotNull(originalQualificationBasis(profile.id)),
                    inputs.snapshotIdentity(qualificationRoot), inputs.sha256(qualificationRoot.resolve("extraction.json")),
                    inputs.sha256(qualificationRoot.resolve("resource-extraction.json")), contracts, inputs::verifyCurrent)
                acceptedResources.accept(pack, qualification)
            }
        }
        return if (pack.metadata.requiresResourceFormat == 4) acceptedResources.accept(pack) else pack
    }

    /** Qualifier and producer identities are distribution-owned; package hints never supply either. */
    private fun originalQualificationBasis(id: String): String? {
        val profile = if (resourceExtractionProfile != null) resourceExtractionProfile.takeIf { it.id == id }
            else PcOriginalProfileVersions.supportedVersions.asSequence().map(PcOriginalProfileVersions::version).firstOrNull { it.id == id }
        return profile?.let { visualHash((PcTrustedOutputIndex.basis(it) + "\u0000" + originalProducerBasis).toByteArray(Charsets.UTF_8)) }
    }

    @Synchronized override fun inspectSavedImagePack(path: Path): PreparedImagePack =
        if (acceptedResources.owns(path)) acceptedResources.restore(path).also { pack ->
            pack.verifyUsed(setOf(pack.images.getValue(pack.metadata.icon).path))
        } else importer.load(path)
    override fun extractOriginalImagePack(path: Path): PreparedImagePack = pcImageExtractor.extract(path)
    override fun extractOriginalResourcePack(path: Path): PreparedImagePack {
        return pcResourceExtractor.extract(path)
    }
    override fun extractOriginalPreparedResourcePack(path: Path): PreparedImagePack =
        finishedOriginal.export(pcResourceExtractor.extract(path))
    @Synchronized override fun extractAndCheckOriginalResources(path: Path): PreparedImagePack {
        val original = pcResourceExtractor.extract(path)
        lastCompleteExtraction?.takeIf { it.first == original.catalogEntry }?.second?.let { previous ->
            try {
                val restored = acceptedResources.restore(previous.manifest)
                restored.verify(); original.verify(); original.verifySources()
                return restored
            } catch (failure: CancellationException) { throw failure }
              catch (_: Exception) { /* A damaged held version is repaired by a new full import. */ }
        }
        return checkImagePack(finishedOriginal.export(original).manifest).also {
            lastCompleteExtraction = original.catalogEntry to it
        }
    }

    override fun loadSavedSelection(): VisualAssetSelection {
        val saved = store.load()
        return saved.copy(originalDirectory = saved.originalDirectory ?: applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize(),
            temporary = options.hasVisualOverride)
    }
}
