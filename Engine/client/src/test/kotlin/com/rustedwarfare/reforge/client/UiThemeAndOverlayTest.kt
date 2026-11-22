package com.rustedwarfare.reforge.client

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiThemeAndOverlayTest {

    @Test
    fun `built-in default constructivist theme loads completely and matches UiTokens`() {
        val theme = UiTheme.loadBuiltInDefault()
        assertEquals("reforge:constructivist-default", theme.id)
        assertEquals(listOf("reforge:constructivist-default"), theme.appliedLayers)
        assertEquals(UiTokens.RAIL, theme.colors.rail)
        assertEquals(UiTokens.BONE, theme.colors.bone)
        assertEquals(UiTokens.RUST, theme.colors.rust)
        assertEquals(UiTokens.AMBER, theme.colors.amber)
        assertEquals(UiTokens.FOE, theme.colors.foe)
        assertEquals(HudCommandIconManifest.ATLAS_JSON_PATH, theme.assets.commandIconsJson)
        assertEquals(HudCommandIconManifest.ATLAS_PNG_PATH, theme.assets.commandIconsPng)
        assertEquals(UiFontSet.REGULAR_FONT_PATH, theme.assets.fontRegular)
        assertEquals(UiFontSet.BOLD_FONT_PATH, theme.assets.fontBold)
        assertEquals(UiFontSet.MONO_FONT_PATH, theme.assets.fontMono)
        assertNull(theme.assets.panelNinePatch)
        assertNull(theme.assets.keyNinePatch)
    }

    @Test
    fun `layered partial theme composition merges high-to-low with fallback to default`() {
        val low = UiThemePatch.read(
            """
            {
              "schemaVersion": 1,
              "id": "pack:low",
              "colors": {
                "rust": "#112233FF",
                "amber": "#445566"
              },
              "metrics": {
                "keyRadius": 6.0
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "low/ui/theme.json",
        )
        val high = UiThemePatch.read(
            """
            {
              "schemaVersion": 1,
              "id": "pack:high",
              "colors": {
                "rust": "#AABBCCDD"
              }
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "high/ui/theme.json",
        )

        val composed = UiThemeComposer.compose(overridesHighToLow = listOf(high, low))
        assertEquals("pack:high", composed.id)
        assertEquals(listOf("pack:high", "pack:low", "reforge:constructivist-default"), composed.appliedLayers)
        assertEquals(0xAA / 255f, composed.colors.rust.r, 1e-3f)
        assertEquals(0xBB / 255f, composed.colors.rust.g, 1e-3f)
        assertEquals(0xCC / 255f, composed.colors.rust.b, 1e-3f)
        assertEquals(0xDD / 255f, composed.colors.rust.a, 1e-3f)
        assertEquals(0x44 / 255f, composed.colors.amber.r, 1e-3f)
        assertEquals(1f, composed.colors.amber.a, 1e-3f)
        assertEquals(6.0f, composed.metrics.keyRadius, 1e-4f)
        assertEquals(UiTheme.DEFAULT.colors.bone, composed.colors.bone)
        assertEquals(UiTheme.DEFAULT.assets.fontRegular, composed.assets.fontRegular)

        // Anonymous override shares default id but differs by value so hot-swap checks detect it
        val anon = UiThemePatch.read(
            """{"schemaVersion": 1, "colors": {"rust": "#00AAFF"}}""".toByteArray(Charsets.UTF_8),
            "anon.json",
        )
        val composedAnon = UiThemeComposer.compose(overridesHighToLow = listOf(anon))
        assertEquals(UiTheme.DEFAULT.id, composedAnon.id)
        assertNotEquals(UiTheme.DEFAULT, composedAnon)
    }

    @Test
    fun `invalid theme schema unknown keys bad hex out-of-range alpha values and unsafe paths fail with UI_THEME_INVALID`() {
        val badPayloads = listOf(
            """{"schemaVersion": 2}""",
            """{"schemaVersion": 1, "unknownSection": {}}""",
            """{"schemaVersion": 1, "colors": {"notAToken": "#FFFFFF"}}""",
            """{"schemaVersion": 1, "colors": {"rust": "red"}}""",
            """{"schemaVersion": 1, "colors": {"rust": "#12345"}}""",
            """{"schemaVersion": 1, "colors": {"rust": "#112233@1.5"}}""",
            """{"schemaVersion": 1, "colors": {"rust": "#112233@-0.1"}}""",
            """{"schemaVersion": 1, "colors": {"rust": "#112233@NaN"}}""",
            """{"schemaVersion": 1, "metrics": {"keyRadius": 999.0}}""",
            """{"schemaVersion": 1, "unitIconTuning": {"gamma": 0.1}}""",
            """{"schemaVersion": 1, "assets": {"fontRegular": "../escape.ttf"}}""",
            """{"schemaVersion": 1, "assets": {"fontRegular": "/abs/font.ttf"}}""",
            """{"schemaVersion": 1, "assets": {"commandIconsPng": null}}""",
        )
        for (payload in badPayloads) {
            val ex = assertFailsWith<IllegalArgumentException>("Expected failure for: $payload") {
                UiThemePatch.read(payload.toByteArray(Charsets.UTF_8), "bad.json")
            }
            assertTrue(ex.message.orEmpty().startsWith("UI_THEME_INVALID"), "Unexpected error: ${ex.message}")
        }
    }

    @Test
    fun `oversized theme file above 512 KiB is rejected with UI_THEME_INVALID`() {
        val tmpDir = Files.createTempDirectory("ui-theme-size-test")
        try {
            val uiDir = Files.createDirectories(tmpDir.resolve("ui"))
            val oversized = ByteArray(UiThemeComposer.MAX_THEME_BYTES + 16) { ' '.code.toByte() }
            Files.write(uiDir.resolve("theme.json"), oversized)
            val ex = assertFailsWith<IllegalArgumentException> {
                UiThemeComposer.composeFromPackDirectories(listOf(tmpDir))
            }
            assertTrue(ex.message.orEmpty().startsWith("UI_THEME_INVALID"))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sample cobalt-steel pack directory overrides HUD colors and resolves pack-local assets before classpath`() {
        val sampleStream = requireNotNull(
            javaClass.getResourceAsStream("/ui-theme-samples/cobalt-steel/ui/theme.json")
        )
        val tmpDir = Files.createTempDirectory("ui-theme-sample-test")
        try {
            val packHigh = Files.createDirectories(tmpDir.resolve("pack-high"))
            val packLow = Files.createDirectories(tmpDir.resolve("pack-low"))
            Files.createDirectories(packHigh.resolve("ui/icons"))
            Files.createDirectories(packLow.resolve("ui"))
            val sampleBytes = sampleStream.use { it.readBytes() }
            Files.write(packHigh.resolve("ui/theme.json"), sampleBytes)
            Files.write(packHigh.resolve("ui/icons/custom.png"), byteArrayOf(1, 2, 3, 4))
            val composed = UiThemeComposer.composeFromPackDirectories(listOf(packHigh, packLow))
            assertEquals("sample:cobalt-steel", composed.id)
            assertEquals(0x2E / 255f, composed.colors.rust.r, 1e-3f)
            assertEquals(0x86 / 255f, composed.colors.rust.g, 1e-3f)
            assertEquals(0xDE / 255f, composed.colors.rust.b, 1e-3f)
            assertEquals(2.0f, composed.metrics.keyShadowHeight, 1e-4f)
            assertEquals(UiTheme.DEFAULT.colors.ink, composed.colors.ink)
            assertEquals(UiTheme.DEFAULT.colors.foe, composed.colors.foe)

            val resolver = UiThemeAssetResolver(listOf(packHigh, packLow))
            val customHit = resolver.resolveOverridePath("ui/icons/custom.png")
            assertNotNull(customHit)
            assertEquals(packHigh.resolve("ui/icons/custom.png").toRealPath(), customHit)
            assertNull(resolver.resolveOverridePath(composed.assets.commandIconsPng))
            // Structural equality on UiThemeAssetResolver
            assertEquals(resolver, UiThemeAssetResolver(listOf(packHigh, packLow)))
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }
}
