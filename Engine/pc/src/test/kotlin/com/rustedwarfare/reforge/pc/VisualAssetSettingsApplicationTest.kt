package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.VisualAssetSelection
import com.rustedwarfare.reforge.client.VisualAssetSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import java.nio.file.Path

class VisualAssetSettingsApplicationTest {
    @Test fun application_and_user_paths_do_not_depend_on_a_shortcuts_working_directory() {
        val root = Files.createTempDirectory("visual-paths-")
        try {
            val app = root.resolve("installed app")
            assertEquals(app, PcVisualAssetPaths.applicationRoot(app.resolve("lib/pc.jar"), root.resolve("unrelated cwd")))
            assertEquals(root, PcVisualAssetPaths.applicationRoot(root.resolve("Engine/pc/build/classes/kotlin/main"), root))
            assertEquals(root.resolve("local/RustedWarfare-Reforge/visual-assets/v1"),
                PcVisualAssetPaths.userBase(root.resolve("local").toString(), root.toString()))
            assertEquals(root.resolve("AppData/Local/RustedWarfare-Reforge/visual-assets/v1"),
                PcVisualAssetPaths.userBase(null, root.toString()))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun saved_selection_is_used_unless_a_temporary_cli_override_is_explicit() {
        VisualAssetTestFixture().use { fixture ->
            val store = VisualAssetSettingsStore(fixture.settings)
            val plain = WindowsLaunchOptions.parse(arrayOf("--mode", "demo"), fixture.root)
            val first = PcVisualAssetSettings(store, fixture.cache, fixture.root, plain,
                prepareOriginal = fixture.service()::prepareVisuals)
            assertNull(first.initialError)
            assertEquals(VisualAssetSource.PLACEHOLDER, first.initialSelection.source)
            val original = VisualAssetSelection(VisualAssetSource.ORIGINAL, fixture.root)
            first.applyPrepared(first.prepare(original))
            val saved = Files.readAllBytes(fixture.settings)
            assertEquals(original, PcVisualAssetSettings(store, fixture.cache, fixture.root, plain).initialSelection)
            val overridden = PcVisualAssetSettings(store, fixture.cache, fixture.root,
                WindowsLaunchOptions.parse(arrayOf("--mode", "demo", "--visuals", "placeholder"), fixture.root))
            assertEquals(original.copy(source = VisualAssetSource.PLACEHOLDER, temporary = true), overridden.initialSelection)
            overridden.applyPrepared(overridden.prepare(overridden.initialSelection))
            assertContentEquals(saved, Files.readAllBytes(fixture.settings))
        }
    }
}
