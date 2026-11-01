package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.IOException

class VisualAssetSettingsStoreTest {
    @Test fun corrupt_or_unknown_settings_stay_an_error_until_an_explicit_successful_save() {
        val root = Files.createTempDirectory("visual-settings-invalid-")
        try {
            val file = root.resolve("settings.properties")
            val store = VisualAssetSettingsStore(file)
            for (text in listOf("schemaVersion=99\nsource=placeholder\noriginalDirectory=\n",
                "schemaVersion=1\nsource=unknown\noriginalDirectory=\n",
                "schemaVersion=1\nsource=placeholder\noriginalDirectory=relative/path\n",
                "broken=\\uINVALID\n")) {
                Files.writeString(file, text)
                assertTrue(assertFailsWith<IllegalArgumentException> { store.load() }.message.orEmpty().contains("VISUAL_SETTINGS_INVALID"))
                assertEquals(text, Files.readString(file))
            }
            store.save(VisualAssetSelection())
            assertEquals(VisualAssetSelection(), store.load())
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun failed_atomic_save_preserves_the_previous_good_configuration() {
        val root = Files.createTempDirectory("visual-settings-write-")
        try {
            val file = root.resolve("settings.properties")
            VisualAssetSettingsStore(file).save(VisualAssetSelection())
            val before = Files.readAllBytes(file)
            val store = VisualAssetSettingsStore(file) { _, _, _ -> throw IOException("owned atomic write failure") }
            assertTrue(assertFailsWith<IllegalArgumentException> {
                store.save(VisualAssetSelection(VisualAssetSource.ORIGINAL, root.resolve("original")))
            }.message.orEmpty().contains("VISUAL_SETTINGS_WRITE_FAILED"))
            assertContentEquals(before, Files.readAllBytes(file))
            assertEquals(VisualAssetSelection(), VisualAssetSettingsStore(file).load())
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun defaults_to_placeholder_then_remembers_source_and_directory_without_temporary_override_writes() {
        val root = Files.createTempDirectory("visual-settings-中文 space-")
        try {
            val file = root.resolve("settings.properties")
            val store = VisualAssetSettingsStore(file)
            assertEquals(VisualAssetSelection(), store.load())
            val original = VisualAssetSelection(VisualAssetSource.ORIGINAL, root.resolve("原版 assets"))
            store.save(original)
            assertEquals(original, VisualAssetSettingsStore(file).load())
            val placeholder = original.copy(source = VisualAssetSource.PLACEHOLDER)
            store.save(placeholder)
            assertEquals(placeholder, VisualAssetSettingsStore(file).load())
            val saved = Files.readAllBytes(file)
            store.save(original.copy(originalDirectory = root.resolve("temporary other directory"), temporary = true))
            assertContentEquals(saved, Files.readAllBytes(file))
            assertEquals(placeholder, store.load())
        } finally { root.toFile().deleteRecursively() }
    }
}
