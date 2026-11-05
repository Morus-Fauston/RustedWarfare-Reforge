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

internal class PcVisualAssetSettings(private val store: VisualAssetSettingsStore, cache: Path,
    private val applicationRoot: Path, private val options: WindowsLaunchOptions,
    private val prepareOriginal: (VisualAssetSelection) -> PreparedVisualAssets = PcVisualAssetService(cache)::prepareVisuals,
    private val imageExtractionProfile: PcOriginalImageProfile? = null,
    imageExtractionMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically,
    private val resourceExtractionProfile: PcOriginalResourceProfile? = null,
) : VisualAssetSettings {
    private val auditedVisuals = PcAuditedVisualReuse()
    private val placeholders = PcPlaceholderVisualAssets()
    private val importer = PcResourcePackImporter(cache)
    private val pcImageExtractor = PcOriginalImageExtractor(cache, imageExtractionProfile ?: resourceExtractionProfile?.images, imageExtractionMove)
    private val pcResourceExtractor = PcOriginalResourceExtractor(cache, resourceExtractionProfile, pcImageExtractor::extract, imageExtractionMove)
    private val baseSoundResources = PcBaseSoundResources(cache)
    override val availableResourcePacks = BuiltInResourcePacks.all
    override val supportsOriginalImageExtraction = true
    override val supportsOriginalResourceExtraction = true
    override val initialSelection: VisualAssetSelection
    override val initialError: String?
    @Volatile override var preparationStatus: String? = null
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
        return auditedVisuals.prepare(key, roots) { verifyAuditedInputs, inputs ->
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
            val users = resolved.packs.enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId }.map { id ->
                val expected = resolved.packs.catalog.single { it.id == id }
                importer.load(expected.manifest, inputs).also { pack ->
                    require(pack.catalogEntry == expected) { "RESOURCE_PACK_SOURCE_CHANGED: $id / ${expected.manifest}: Pack changed after checking; check it again" }
                }
            }
            preparationStatus = "Checking picture and sound qualifications..."
            val layers = users.associate { pack -> pack.metadata.id.value to
                ResourceImageLayer(pack.metadata, pack.images, pack.identity, pack.sources) { if (key == null) pack.verifySources() else verifyAuditedInputs() }
                    .withSounds(pack.sounds, pack.soundEvents)
                    .withStaticBaseContracts(if (pack.metadata.id.value.startsWith("reforge:pc-original-resources-"))
                        pcResourceExtractor.staticBaseContracts(pack, builtinImages, inputs)
                        else pcImageExtractor.staticBaseContracts(pack, builtinImages, inputs)) }.toMutableMap()
            original?.let { layers[VisualAssetSource.ORIGINAL.resourcePackId] =
                ResourceImagePreparation.baseLayer(it, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL)) }
            val images = ResourceImageComposer.compose(baseImages.withSounds(baseSounds.sounds, baseSounds.events),
                resolved.packs.enabled.map(layers::getValue))
            preparationStatus = "Checking prepared resources before loading..."
            base.withConfiguration(resolved, original?.profileId ?: base.profileId, original?.identity ?: base.identity)
                .withResourceImages(images) { baseSounds.verify(); original?.verify(); users.forEach { it.verify() } }
                .withResourceSounds(ResourceSoundComposer.compose(images))
                .also { it.verifyForPublication() }
        }
    }

    override fun applyPrepared(prepared: PreparedVisualAssets) {
        prepared.verify()
        store.save(prepared.selection.copy(temporary = options.hasVisualOverride))
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
