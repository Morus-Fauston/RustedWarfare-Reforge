package com.rustedwarfare.reforge.client

import java.nio.file.Path

/** Preparation is worker-only; the UI accepts and saves only its current successful request. */
interface VisualAssetSettings {
    val initialSelection: VisualAssetSelection
    val initialError: String?
    val availableResourcePacks: List<ResourcePackMetadata> get() = BuiltInResourcePacks.all
    fun prepare(selection: VisualAssetSelection): PreparedVisualAssets
    /** Worker-only: apply/save must use the same final-content contract as a new world. */
    fun prepareForContent(selection: VisualAssetSelection, content: ContentPackChoice?): PreparedVisualAssets {
        val checked = content?.revalidate()
        val prepared = prepare(selection)
        return (checked?.let { contentPackVisuals(prepared, it) } ?: prepared).also { it.verifyForPublication() }
    }
    fun applyPrepared(prepared: PreparedVisualAssets)
    /** Worker-only local import; checking never enables a pack or writes the selection. */
    fun checkImagePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("Local image-pack import is unavailable")
    val supportsOriginalImageExtraction: Boolean get() = false
    /** Worker-only PC source extraction. It never enables the resulting ordinary pack or saves settings. */
    fun extractOriginalImagePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("PC image extraction is unavailable")
    val supportsOriginalResourceExtraction: Boolean get() = false
    /** Worker-only complete PC pictures/sounds. Legacy image-only extraction remains separate. */
    fun extractOriginalResourcePack(path: Path): PreparedImagePack = throw UnsupportedOperationException("PC resource extraction is unavailable")
    /** Worker-only read; restoration still validates this choice against the current content. */
    fun loadSavedSelection(): VisualAssetSelection = initialSelection
    /** Optional local-platform font; called and disposed only on the render thread. */
    fun createUiFont(characters: String): com.badlogic.gdx.graphics.g2d.BitmapFont? = null
}
