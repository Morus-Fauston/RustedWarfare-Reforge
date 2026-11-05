package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.PreparedVisualAssets
import com.rustedwarfare.reforge.client.ResourcePackSelection
import com.rustedwarfare.reforge.client.ContentPackChoice
import java.nio.file.Path

/** Worker adapter for local tools. Uses the application importer/resolver and never applies or saves. */
object PcResourceImagePreparation {
    fun prepare(highToLow: List<Path>, cacheRoot: Path, content: ContentPackChoice? = null): PreparedVisualAssets {
        require(highToLow.isNotEmpty()) { "Select at least one local image pack" }
        val root = cacheRoot.toAbsolutePath().normalize()
        val settings = PcVisualAssetSettings(VisualAssetSettingsStore(root.resolve("settings.properties")), root, root,
            WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), root))
        val packs = highToLow.map(settings::checkImagePack)
        return settings.prepareForContent(settings.initialSelection.copy(packs = ResourcePackSelection(packs.map { it.catalogEntry },
            packs.map { it.metadata.id.value })), content)
    }
}
