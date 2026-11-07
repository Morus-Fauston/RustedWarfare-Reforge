package com.rustedwarfare.reforge.client

import java.nio.file.Path

/** Preparation is worker-only; the UI accepts and saves only its current successful request. */
interface VisualAssetSettings {
    val initialSelection: VisualAssetSelection
    val initialError: String?
    /** Worker-published description of current preparation; null when idle. */
    val preparationStatus: String? get() = null
    /** Last completed worker preparation, for runtime evidence rather than stored trust. */
    val preparationDiagnostics: Map<String, Any?> get() = emptyMap()
    val availableResourcePacks: List<ResourcePackMetadata> get() = BuiltInResourcePacks.all
    fun prepare(selection: VisualAssetSelection): PreparedVisualAssets
    /** Worker-only: apply/save must use the same final-content contract as a new world. */
    fun prepareForContent(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        val checked = content?.revalidate()
        val prepared = prepare(selection)
        return (checked?.let { contentPackVisuals(prepared, it) } ?: prepared).also { it.verifyForPublication() }
    }
    fun applyPrepared(prepared: PreparedVisualAssets)
    /** The current launch accepted this exact descriptor; optional platform cache publication. */
    fun onPublished(prepared: PreparedVisualAssets) = Unit
    /** Worker-only local import; checking never enables a pack or writes the selection. */
    fun checkImagePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("Local image-pack import is unavailable")
    /** Passive saved-catalog inspection must not migrate or replace a saved version. */
    fun inspectSavedImagePack(path: Path): PreparedImagePack = checkImagePack(path)
    val supportsOriginalImageExtraction: Boolean get() = false
    /** Worker-only PC source extraction. It never enables the resulting ordinary pack or saves settings. */
    fun extractOriginalImagePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("PC image extraction is unavailable")
    val supportsOriginalResourceExtraction: Boolean get() = false
    /** Worker-only complete PC pictures/sounds. Legacy image-only extraction remains separate. */
    fun extractOriginalResourcePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("PC resource extraction is unavailable")
    /** Finished portable media output; legacy callers retain the original extraction contract. */
    fun extractOriginalPreparedResourcePack(path: Path): PreparedImagePack = extractOriginalResourcePack(path)
    /** Menu operation; a platform may retain an unchanged, still-valid extracted choice. */
    fun extractAndCheckOriginalResources(path: Path): PreparedImagePack = checkImagePack(extractOriginalPreparedResourcePack(path).manifest)
    /** Worker-only read; restoration still validates this choice against the current content. */
    fun loadSavedSelection(): VisualAssetSelection = initialSelection
    /** Optional local-platform font; called and disposed only on the render thread. */
    fun createUiFont(characters: String): com.badlogic.gdx.graphics.g2d.BitmapFont? = null
}
