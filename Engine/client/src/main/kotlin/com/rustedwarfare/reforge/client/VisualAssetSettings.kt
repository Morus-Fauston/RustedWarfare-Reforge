package com.rustedwarfare.reforge.client

/** Preparation is worker-only; the UI accepts and saves only its current successful request. */
interface VisualAssetSettings {
    val initialSelection: VisualAssetSelection
    val initialError: String?
    fun prepare(selection: VisualAssetSelection): PreparedVisualAssets
    fun applyPrepared(prepared: PreparedVisualAssets)
    /** Optional local-platform font; called and disposed only on the render thread. */
    fun createUiFont(characters: String): com.badlogic.gdx.graphics.g2d.BitmapFont? = null
}
