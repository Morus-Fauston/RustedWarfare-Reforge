package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSettings
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Path

internal class PcVisualAssetSettings(private val store: VisualAssetSettingsStore, cache: Path,
    private val applicationRoot: Path, private val options: WindowsLaunchOptions,
    private val prepareOriginal: (VisualAssetSelection) -> PreparedVisualAssets = PcVisualAssetService(cache)::prepareVisuals,
) : VisualAssetSettings {
    private val placeholders = PcPlaceholderVisualAssets()
    override val initialSelection: VisualAssetSelection
    override val initialError: String?
    init {
        val loaded = runCatching { store.load() }
        val saved = loaded.getOrDefault(VisualAssetSelection())
        initialError = loaded.exceptionOrNull()?.message
        initialSelection = VisualAssetSelection(options.visualSource ?: saved.source,
            options.originalAssets ?: saved.originalDirectory ?: applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize(),
            temporary = options.hasVisualOverride)
    }

    override fun prepare(selection: VisualAssetSelection): PreparedVisualAssets {
        val resolved = selection.copy(originalDirectory = selection.originalDirectory?.toAbsolutePath()?.normalize()
            ?: if (selection.source == VisualAssetSource.ORIGINAL) applicationRoot.resolve("Rusted Warfare").toAbsolutePath().normalize() else null,
            temporary = options.hasVisualOverride)
        return when (resolved.source) {
            VisualAssetSource.PLACEHOLDER -> placeholders.prepare(resolved)
            VisualAssetSource.ORIGINAL -> prepareOriginal(resolved)
        }
    }

    override fun applyPrepared(prepared: PreparedVisualAssets) {
        prepared.verify()
        store.save(prepared.selection.copy(temporary = options.hasVisualOverride))
    }

    override fun createUiFont(characters: String) = PcVisualAssetFont.create(characters)
}
