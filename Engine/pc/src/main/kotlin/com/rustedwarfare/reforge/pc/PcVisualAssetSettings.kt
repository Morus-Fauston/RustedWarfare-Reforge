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

internal enum class PcResourcePreparationPath { FULL_AUDIT, PROCESS_REUSE, DISK_REUSE }

internal class PcVisualAssetSettings @JvmOverloads constructor(private val store: VisualAssetSettingsStore, cache: Path,
    private val applicationRoot: Path, private val options: WindowsLaunchOptions,
    private val prepareOriginal: (VisualAssetSelection) -> PreparedVisualAssets = PcVisualAssetService(cache)::prepareVisuals,
    private val imageExtractionProfile: PcOriginalImageProfile? = null,
    imageExtractionMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically,
    private val resourceExtractionProfile: PcOriginalResourceProfile? = null,
    private val trustedOutputs: PcTrustedOutputIndex = PcTrustedOutputIndex.bundled(),
) : VisualAssetSettings {
    private val auditedVisuals = PcAuditedVisualReuse()
    private val placeholders = PcPlaceholderVisualAssets()
    private val importer = PcResourcePackImporter(cache)
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
        try { return prepareAudited(selection, content) }
        finally { preparationStatus = null }
    }

    private fun prepareAudited(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        val checked = content?.revalidate()
        val resolved = selection.copy(originalDirectory = selection.originalDirectory?.toAbsolutePath()?.normalize()
            ?: if (selection.source == VisualAssetSource.ORIGINAL) applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize() else null,
            temporary = options.hasVisualOverride)
        val roots = resolved.packs.enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId }
            .map { id -> requireNotNull(resolved.packs.catalog.single { it.id == id }.manifest.parent) }
        // Original-directory extraction retains its existing audit path. User packs (including v5)
        // may reuse only this settings instance's independently audited, non-GL description.
        val key = if (resolved.source == VisualAssetSource.PLACEHOLDER)
            listOf(resolved, checked?.unitId, checked?.content?.sources) else null
        preparationStatus = "Checking resource sources..."
        var preparationPath = PcResourcePreparationPath.PROCESS_REUSE
        var producedProof: Publication? = null
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
            val original = if (resolved.source == VisualAssetSource.ORIGINAL) prepareOriginal(resolved) else null
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
                (restored ?: importer.load(expected.manifest, inputs)).also { pack ->
                    require(pack.catalogEntry == expected) { "RESOURCE_PACK_SOURCE_CHANGED: $id / ${expected.manifest}: Pack changed after checking; check it again" }
                }
            }
            preparationStatus = "Checking picture and sound qualifications..."
            val layers = users.associate { pack -> pack.metadata.id.value to
                ResourceImageLayer(pack.metadata, pack.images, pack.identity, pack.sources) { if (key == null) pack.verifySources() else verifyAuditedInputs() }
                    .withSounds(pack.sounds, pack.soundEvents)
                    .withStaticBaseContracts(restoredContracts[pack.metadata.id.value] ?: if (pack.metadata.id.value.startsWith("reforge:pc-original-resources-"))
                        pcResourceExtractor.staticBaseContracts(pack, builtinImages, inputs, reviewedOutputs(pack, inputs))
                        else pcImageExtractor.staticBaseContracts(pack, builtinImages, inputs)) }.toMutableMap()
            original?.let { layers[VisualAssetSource.ORIGINAL.resourcePackId] =
                ResourceImagePreparation.baseLayer(it, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL)) }
            val images = ResourceImageComposer.compose(baseImages.withSounds(baseSounds.sounds, baseSounds.events),
                resolved.packs.enabled.map(layers::getValue))
            preparationStatus = "Checking prepared resources before loading..."
            val prepared = base.withConfiguration(resolved, original?.profileId ?: base.profileId, original?.identity ?: base.identity)
                .withResourceImages(images) { baseSounds.verify(); original?.verify(); users.forEach { it.verify() } }
                .withResourceSounds(ResourceSoundComposer.compose(images))
                .also { it.verifyForPublication() }
            if (key != null && inputs != null && trustedOutputs.identity != null) {
                val records = users.mapNotNull { pack ->
                    if (!pack.metadata.id.value.startsWith("reforge:pc-original-resources-")) return@mapNotNull null
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
        return result
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
    override fun checkImagePack(path: Path): PreparedImagePack = importer.load(path)
    override fun extractOriginalImagePack(path: Path): PreparedImagePack = pcImageExtractor.extract(path)
    override fun extractOriginalResourcePack(path: Path): PreparedImagePack {
        return pcResourceExtractor.extract(path)
    }

    override fun loadSavedSelection(): VisualAssetSelection {
        val saved = store.load()
        return saved.copy(originalDirectory = saved.originalDirectory ?: applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize(),
            temporary = options.hasVisualOverride)
    }

    override fun createUiFont(characters: String) = PcVisualAssetFont.create(characters)
}
