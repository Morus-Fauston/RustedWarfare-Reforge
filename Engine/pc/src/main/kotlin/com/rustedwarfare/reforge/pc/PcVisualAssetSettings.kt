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
    init {
        val loaded = runCatching { store.load() }
        val saved = loaded.getOrDefault(VisualAssetSelection())
        initialError = loaded.exceptionOrNull()?.message
        initialSelection = saved.copy(source = options.visualSource ?: saved.source,
            originalDirectory = options.originalAssets ?: saved.originalDirectory ?: applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize(),
            temporary = options.hasVisualOverride)
    }

    override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets = prepareForContent(selection, null)

    override fun prepareForContent(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        val checked = content?.revalidate()
        val resolved = selection.copy(originalDirectory = selection.originalDirectory?.toAbsolutePath()?.normalize()
            ?: if (selection.source == VisualAssetSource.ORIGINAL) applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize() else null,
            temporary = options.hasVisualOverride)
        val placeholderBase = placeholders.prepare(resolved.copy(source = VisualAssetSource.PLACEHOLDER))
        val base = checked?.let { contentPackVisuals(placeholderBase, it) } ?: placeholderBase
        val baseImages = ResourceImagePreparation.baseLayer(base, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
        val builtinImages = if (checked == null) baseImages else
            ResourceImagePreparation.baseLayer(placeholderBase, BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER))
        val original = if (resolved.source == VisualAssetSource.ORIGINAL) prepareOriginal(resolved) else null
        val baseSounds = baseSoundResources.prepare()
        val users = resolved.packs.enabled.filterNot { it == VisualAssetSource.ORIGINAL.resourcePackId }.map { id ->
            val expected = resolved.packs.catalog.single { it.id == id }
            importer.load(expected.manifest).also { pack ->
                require(pack.catalogEntry == expected) { "RESOURCE_PACK_SOURCE_CHANGED: $id / ${expected.manifest}: Pack changed after checking; check it again" }
            }
        }
        val layers = users.associate { pack -> pack.metadata.id.value to
            ResourceImageLayer(pack.metadata, pack.images, pack.identity, pack.sources, pack::verifySources)
                .withSounds(pack.sounds, pack.soundEvents)
                .withStaticBaseContracts(if (pack.metadata.id.value.startsWith("reforge:pc-original-resources-"))
                    pcResourceExtractor.staticBaseContracts(pack, builtinImages)
                    else pcImageExtractor.staticBaseContracts(pack, builtinImages)) }.toMutableMap()
        original?.let { layers[VisualAssetSource.ORIGINAL.resourcePackId] =
            ResourceImagePreparation.baseLayer(it, BuiltInResourcePacks.forSource(VisualAssetSource.ORIGINAL)) }
        val images = ResourceImageComposer.compose(baseImages.withSounds(baseSounds.sounds, baseSounds.events),
            resolved.packs.enabled.map(layers::getValue))
        return base.withConfiguration(resolved, original?.profileId ?: base.profileId, original?.identity ?: base.identity)
            .withResourceImages(images) { baseSounds.verify(); original?.verify(); users.forEach { it.verify() } }
            .withResourceSounds(ResourceSoundComposer.compose(images))
            .also { it.verifyForPublication() }
    }

    override fun applyPrepared(prepared: PreparedVisualAssets) {
        prepared.verify()
        store.save(prepared.selection.copy(temporary = options.hasVisualOverride))
    }

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
