package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.ResourcePackSelection
import com.rustedwarfare.reforge.client.UiTheme
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PcUiThemeResourcePackTest {
    @Test
    fun `managed user resource pack with ui theme and non-ui icon asset survives custody and detects tampering`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val sourcePack = fixture.create("custom:cobalt-hud", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent
            val manifest = sourcePack.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4"))
            val themeDir = Files.createDirectories(sourcePack.resolve("ui"))
            val iconsDir = Files.createDirectories(sourcePack.resolve("icons"))
            Files.write(iconsDir.resolve("custom-atlas.png"), byteArrayOf(10, 20, 30, 40))
            Files.writeString(
                themeDir.resolve("theme.json"),
                """
                {
                  "schemaVersion": 1,
                  "id": "custom:cobalt-hud",
                  "colors": {
                    "rust": "#2E86DEFF",
                    "bone": "#DCE6F0FF"
                  },
                  "metrics": {
                    "keyShadowHeight": 2.0
                  },
                  "assets": {
                    "commandIconsPng": "icons/custom-atlas.png"
                  }
                }
                """.trimIndent(),
            )
            val backend = fixture.backend()
            val accepted = backend.checkImagePack(sourcePack)
            val selection = backend.initialSelection.copy(
                packs = ResourcePackSelection(listOf(accepted.catalogEntry), listOf(accepted.metadata.id.value)),
            )
            // Remove original source folder to prove ADR-008 client-held custody preserves ui/theme.json and icons/custom-atlas.png
            Files.move(sourcePack, sourcePack.resolveSibling("removed-theme-source"))
            val prepared = backend.prepare(selection)
            prepared.verifyForPublication()
            assertEquals("custom:cobalt-hud", prepared.uiTheme.id)
            assertEquals(0x2E / 255f, prepared.uiTheme.colors.rust.r, 1e-3f)
            assertEquals(0x86 / 255f, prepared.uiTheme.colors.rust.g, 1e-3f)
            assertEquals(0xDE / 255f, prepared.uiTheme.colors.rust.b, 1e-3f)
            assertEquals(2.0f, prepared.uiTheme.metrics.keyShadowHeight, 1e-4f)
            assertEquals(UiTheme.DEFAULT.colors.amber, prepared.uiTheme.colors.amber)
            assertTrue(Files.isRegularFile(accepted.manifest.parent.resolve("ui/theme.json")))
            val heldIcon = accepted.manifest.parent.resolve("icons/custom-atlas.png")
            assertTrue(Files.isRegularFile(heldIcon))

            // Tampering with the non-ui/ managed asset MUST fail verifyForPublication!
            Files.write(heldIcon, byteArrayOf(99, 98, 97, 96))
            val ex = assertFailsWith<IllegalArgumentException> { prepared.verifyForPublication() }
            assertTrue(ex.message.orEmpty().contains("RESOURCE_PACK_ACCEPTANCE_INVALID"))
        }
    }

    @Test
    fun `managed pack declaring reserved receipt filename as ui asset is rejected`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val sourcePack = fixture.create("custom:reserved-asset", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent
            val manifest = sourcePack.resolve("pack.json")
            Files.writeString(manifest, Files.readString(manifest).replace("\"requiresResourceFormat\":1", "\"requiresResourceFormat\":4"))
            val themeDir = Files.createDirectories(sourcePack.resolve("ui"))
            Files.writeString(
                themeDir.resolve("theme.json"),
                """
                {
                  "schemaVersion": 1,
                  "assets": {
                    "commandIconsJson": "receipt.json"
                  }
                }
                """.trimIndent(),
            )
            val ex = assertFailsWith<IllegalArgumentException> {
                fixture.backend().checkImagePack(sourcePack)
            }
            assertTrue(ex.message.orEmpty().startsWith("UI_THEME_INVALID"))
        }
    }

    @Test
    fun `unmanaged external pack detects ui theme tampering on publication`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val checked = fixture.create("custom:unmanaged-theme", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val sourcePack = checked.manifest.parent
            val themeDir = Files.createDirectories(sourcePack.resolve("ui"))
            val themeFile = themeDir.resolve("theme.json")
            Files.writeString(
                themeFile,
                """
                {
                  "schemaVersion": 1,
                  "id": "custom:unmanaged-theme",
                  "colors": {
                    "rust": "#336699FF"
                  }
                }
                """.trimIndent(),
            )
            val backend = fixture.backend()
            val selection = backend.initialSelection.copy(
                packs = ResourcePackSelection(listOf(checked.catalogEntry), listOf(checked.metadata.id.value)),
            )
            val prepared = backend.prepare(selection)
            prepared.verifyForPublication()
            assertEquals("custom:unmanaged-theme", prepared.uiTheme.id)
            assertEquals(0x33 / 255f, prepared.uiTheme.colors.rust.r, 1e-3f)

            // Modify ui/theme.json after prepare() — verifyForPublication MUST reject it.
            Files.writeString(
                themeFile,
                """
                {
                  "schemaVersion": 1,
                  "id": "custom:unmanaged-theme",
                  "colors": {
                    "rust": "#FF0000FF"
                  }
                }
                """.trimIndent(),
            )
            val ex = assertFailsWith<IllegalArgumentException> { prepared.verifyForPublication() }
            assertTrue(ex.message.orEmpty().contains("RESOURCE_PACK_SOURCE_CHANGED"))
        }
    }

    @Test
    fun `unmanaged external pack rejects newly appearing ui theme after prepare`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val checked = fixture.create("custom:unmanaged-appear", listOf(OwnPackImage("reforge:units/c_tank/body")))
            val sourcePack = checked.manifest.parent
            val backend = fixture.backend()
            val selection = backend.initialSelection.copy(
                packs = ResourcePackSelection(listOf(checked.catalogEntry), listOf(checked.metadata.id.value)),
            )
            val prepared = backend.prepare(selection)
            prepared.verifyForPublication()

            val themeDir = Files.createDirectories(sourcePack.resolve("ui"))
            Files.writeString(themeDir.resolve("theme.json"), """{"schemaVersion":1,"id":"custom:injected"}""")
            val ex = assertFailsWith<IllegalArgumentException> { prepared.verifyForPublication() }
            assertTrue(ex.message.orEmpty().contains("RESOURCE_PACK_SOURCE_CHANGED"))
        }
    }
}