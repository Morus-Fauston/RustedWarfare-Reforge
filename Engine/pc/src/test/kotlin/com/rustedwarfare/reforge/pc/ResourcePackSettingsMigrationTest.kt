package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackSettingsMigrationTest {
    @Test
    fun unknown_v2_identity_bad_keys_and_relative_paths_remain_errors_without_rewriting_the_file() {
        val root = Files.createTempDirectory("resource-settings-invalid-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("settings.properties")
            for (text in listOf(
                "schemaVersion=2\nresourcePackId=reforge:missing\noriginalDirectory=\n",
                "schemaVersion=2\nresourcePackId=original\noriginalDirectory=\n",
                "schemaVersion=2\nresourcePackId=reforge:base\nsource=placeholder\noriginalDirectory=\n",
                "schemaVersion=2\nsource=placeholder\noriginalDirectory=\n",
                "schemaVersion=2\nresourcePackId=reforge:base\noriginalDirectory=relative/path\n",
            )) {
                Files.writeString(file, text)
                assertTrue(assertFailsWith<IllegalArgumentException> { VisualAssetSettingsStore(file).load() }
                    .message.orEmpty().contains("VISUAL_SETTINGS_INVALID"))
                assertEquals(text, Files.readString(file))
            }
        } finally { removeOwned(root) }
    }

    @Test
    fun a_failed_or_temporary_migration_save_preserves_the_original_v1_bytes_and_choice() {
        val root = Files.createTempDirectory("resource-settings-preserve-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("settings.properties")
            Files.writeString(file, "schemaVersion=1\nsource=original\noriginalDirectory=\n")
            val before = Files.readAllBytes(file)
            val store = VisualAssetSettingsStore(file) { _, _, _ -> throw IOException("Owned migration publication failure") }
            val selected = store.load()
            store.save(selected.copy(temporary = true))
            assertContentEquals(before, Files.readAllBytes(file))
            assertTrue(assertFailsWith<IllegalArgumentException> { store.save(selected) }.message.orEmpty().contains("VISUAL_SETTINGS_WRITE_FAILED"))
            assertContentEquals(before, Files.readAllBytes(file))
            assertEquals(selected, VisualAssetSettingsStore(file).load())
        } finally { removeOwned(root) }
    }

    @Test
    fun legacy_selection_migrates_without_a_read_side_write_and_successful_save_publishes_the_same_pack_as_v3() {
        val root = Files.createTempDirectory("resource-settings-中文 space-").toAbsolutePath().normalize()
        try {
            val file = root.resolve("settings.properties")
            val remembered = root.resolve("原版 pictures")
            for ((source, packId) in listOf(VisualAssetSource.PLACEHOLDER to "reforge:base",
                VisualAssetSource.ORIGINAL to "reforge:original-android-1.15-176")) {
                val legacy = Properties().apply {
                    setProperty("schemaVersion", "1"); setProperty("source", source.value)
                    setProperty("originalDirectory", remembered.toString())
                }
                Files.newBufferedWriter(file, Charsets.UTF_8).use { legacy.store(it, "Owned v1 migration input") }
                val before = Files.readAllBytes(file)
                val selected = VisualAssetSettingsStore(file).load()
                assertEquals(VisualAssetSelection(source, remembered), selected)
                assertContentEquals(before, Files.readAllBytes(file), "Reading v1 must not publish unvalidated settings")
                VisualAssetSettingsStore(file).save(selected)
                val saved = Properties().also { p -> Files.newBufferedReader(file, Charsets.UTF_8).use(p::load) }
                assertEquals("3", saved.getProperty("schemaVersion"))
                assertEquals("[]", saved.getProperty("catalog"))
                assertEquals(if (source == VisualAssetSource.ORIGINAL) "[\"$packId\"]" else "[]", saved.getProperty("enabled"))
                assertNull(saved.getProperty("resourcePackId"))
                assertNull(saved.getProperty("source"))
                assertEquals(setOf("schemaVersion", "catalog", "enabled", "originalDirectory"), saved.stringPropertyNames())
                assertEquals(selected, VisualAssetSettingsStore(file).load(), "A new process must recover the same pack and directory")
            }
        } finally {
            removeOwned(root)
        }
    }

    private fun removeOwned(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
            require(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
        } }
    }
}
