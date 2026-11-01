package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Small strict user setting, published by one atomic file replacement. */
internal class VisualAssetSettingsStore(private val file: Path,
    private val atomicMove: (Path, Path, Boolean) -> Unit = ::moveVisualAtomically) {
    fun load(): VisualAssetSelection {
        try {
            if (Files.notExists(file)) return VisualAssetSelection()
            val values = Properties().also { properties -> Files.newBufferedReader(file, Charsets.UTF_8).use(properties::load) }
            require(values.stringPropertyNames() == setOf("schemaVersion", "source", "originalDirectory")) { "Unexpected setting keys" }
            require(values.getProperty("schemaVersion") == "1") { "Only schemaVersion=1 is supported" }
            val source = requireNotNull(VisualAssetSource.entries.firstOrNull { it.value == values.getProperty("source") }) { "Unknown source" }
            val directory = values.getProperty("originalDirectory").takeUnless { it.isEmpty() }?.let { value ->
                Path.of(value).also { require(it.isAbsolute) { "Saved originalDirectory must be absolute" } }.normalize()
            }
            return VisualAssetSelection(source, directory)
        } catch (failure: Exception) { throw IllegalArgumentException("VISUAL_SETTINGS_INVALID: $file: ${failure.message}", failure) }
    }

    fun save(selection: VisualAssetSelection) {
        if (selection.temporary) return
        checkVisualCancellation()
        var temporary: Path? = null
        try {
            val target = file.toAbsolutePath().normalize()
            Files.createDirectories(target.parent)
            temporary = Files.createTempFile(target.parent, "settings-", ".tmp")
            val values = Properties().apply {
                setProperty("schemaVersion", "1"); setProperty("source", selection.source.value)
                setProperty("originalDirectory", selection.originalDirectory?.toAbsolutePath()?.normalize()?.toString() ?: "")
            }
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { values.store(it, "RustedWarfare-Reforge visual assets") }
            checkVisualCancellation()
            atomicMove(temporary, target, true)
        } catch (failure: Exception) {
            temporary?.let { path -> try { Files.deleteIfExists(path) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) } }
            if (failure is java.util.concurrent.CancellationException) throw failure
            throw IllegalArgumentException("VISUAL_SETTINGS_WRITE_FAILED: $file: ${failure.message}", failure)
        }
    }
}
