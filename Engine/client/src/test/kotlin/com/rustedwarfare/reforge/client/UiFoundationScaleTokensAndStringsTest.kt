package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.rustedwarfare.reforge.core.sim.Position
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.*

class UiFoundationScaleTokensAndStringsTest {
    private fun assertColorRgba(expectedRgb: Int, expectedAlpha: Float, actual: Color, label: String) {
        assertEquals(((expectedRgb ushr 16) and 0xFF) / 255f, actual.r, 1e-3f, "$label.r")
        assertEquals(((expectedRgb ushr 8) and 0xFF) / 255f, actual.g, 1e-3f, "$label.g")
        assertEquals((expectedRgb and 0xFF) / 255f, actual.b, 1e-3f, "$label.b")
        assertEquals(expectedAlpha, actual.a, 1e-3f, "$label.a")
    }

    @Test
    fun continuousScaleMatchesSpecResolutionsPortraitAndDensityFallbacks() {
        val m960 = UiScale.metrics(960, 640, touch = false)
        assertEquals(1.0f, m960.scale, 1e-4f)
        assertEquals(960f, m960.logicalWidth, 1e-3f)
        assertEquals(640f, m960.logicalHeight, 1e-3f)
        assertEquals(960f - UiTokens.SIDEBAR_WIDTH, m960.battlefieldLogicalWidth, 1e-3f)

        val m1080 = UiScale.metrics(1920, 1080, touch = false)
        assertEquals(1.6875f, m1080.scale, 1e-4f)
        assertEquals(1920f / 1.6875f, m1080.logicalWidth, 1e-2f)
        assertEquals(640f, m1080.logicalHeight, 1e-3f)

        // 2560x1080 ultrawide: same short edge -> same scale 1.6875, all extra width goes to battlefield!
        val mUltra = UiScale.metrics(2560, 1080, touch = false)
        assertEquals(1.6875f, mUltra.scale, 1e-4f)
        assertEquals(640f, mUltra.logicalHeight, 1e-3f)
        assertTrue(mUltra.battlefieldLogicalWidth > m1080.battlefieldLogicalWidth + 370f,
            "Ultrawide extra width must go entirely to battlefield, keeping sidebar fixed at 232")

        // Portrait orientation uses short edge = min(width, height) = 1080
        val portrait = UiScale.metrics(1080, 1920, touch = false)
        assertEquals(1.6875f, portrait.scale, 1e-4f)
        assertEquals(640f, portrait.logicalWidth, 1e-3f)

        // Touch with unknown/zero density falls back cleanly to base scale
        val touchNoDensity = UiScale.metricsFromDensity(1920, 1080, touch = true, ppcY = 0f, ppiY = 0f)
        assertEquals(1.6875f, touchNoDensity.scale, 1e-4f)
        assertEquals(0f, touchNoDensity.pxPerMm, 1e-4f)
    }

    @Test
    fun touchScalePrefersPpcYAndEnforces8mmMinimumActionKeyHeightFloor() {
        // High-DPI phone: ppcY = 173.23 px/cm (17.323 px/mm, equivalent to 440 ppi)
        val highDpi = UiScale.metricsFromDensity(1920, 1080, touch = true, ppcY = 173.23f, ppiY = 160f)
        val expectedTouch = (8f * 17.323f) / 58f
        assertEquals(expectedTouch, highDpi.scale, 1e-3f)
        assertTrue(highDpi.scale > 1.6875f)
        val physicalKeyMm = (UiTokens.ACTION_KEY_HEIGHT_TOUCH * highDpi.scale) / 17.323f
        assertTrue(physicalKeyMm >= 7.99f, "Touch action key physical height must be >= 8mm, got $physicalKeyMm")

        // Low-DPI large tablet (2560x1600 at 220 ppi): base = 2.5 > sTouch (~1.19) -> keeps base scale
        val tablet = UiScale.metrics(2560, 1600, touch = true, ppi = 220f)
        assertEquals(2.5f, tablet.scale, 1e-4f)
    }

    @Test
    fun screenViewportBattlefieldCoordinateMappingAndResizePreserveUniformProjection() {
        for ((w, h) in listOf(960 to 640, 1920 to 1080, 2560 to 1080)) {
            val metrics = UiScale.metrics(w, h, touch = false)
            val projection = MapProjection(2000.0, 1400.0, MapArea(0.0, 0.0, 960.0, 640.0), fillViewport = true)
            projection.resizeBattlefield(metrics.logicalWidth.toDouble(), metrics.logicalHeight.toDouble())
            assertEquals(metrics.logicalWidth.toDouble(), projection.mapArea.width, 1e-4)
            assertEquals(metrics.logicalHeight.toDouble(), projection.mapArea.height, 1e-4)

            // Center pixel -> center of battlefield logical coords -> round-trip world <-> screen
            val mid = requireNotNull(battlefieldPoint(
                screenX = w / 2, screenY = h / 2, windowHeight = h,
                viewportLeft = 0, viewportBottom = 0, viewportWidth = w, viewportHeight = h,
                battlefieldWidth = metrics.logicalWidth.toDouble(), battlefieldHeight = metrics.logicalHeight.toDouble(),
            ))
            assertEquals(metrics.logicalWidth / 2.0, mid.x, 1.0)
            assertEquals(metrics.logicalHeight / 2.0, mid.y, 1.0)
            val worldPos = requireNotNull(projection.clippedWorld(mid.x, mid.y))
            assertEquals(mid.x.toFloat(), projection.x(worldPos.x), 0.5f)
            assertEquals(mid.y.toFloat(), projection.y(worldPos.y), 0.5f)
        }
    }

    @Test
    fun fontRebuildDebouncerSuppressesTransientResizeUntilDragSettles() {
        val debouncer = FontRebuildDebouncer(delaySeconds = 0.16f)
        debouncer.markApplied(1.0f)

        debouncer.onScaleChanged(1.005f)
        assertFalse(debouncer.hasPendingRebuild)
        assertNull(debouncer.pollReadyScale(0.2f))

        debouncer.onScaleChanged(1.25f)
        assertTrue(debouncer.hasPendingRebuild)
        assertNull(debouncer.pollReadyScale(0.08f))
        debouncer.onScaleChanged(1.50f)
        assertNull(debouncer.pollReadyScale(0.10f))
        val settled = debouncer.pollReadyScale(0.08f)
        assertEquals(1.50f, settled)
        assertFalse(debouncer.hasPendingRebuild)
        assertEquals(1.50f, debouncer.currentAppliedScale)
    }

    @Test
    fun designTokensMatchAll15SpecColorsAndDimensions() {
        assertEquals(232f, UiTokens.SIDEBAR_WIDTH)
        assertEquals(8f, UiTokens.SCREEN_MARGIN)
        assertEquals(10f, UiTokens.SIDEBAR_PAD_H)
        assertEquals(8f, UiTokens.SIDEBAR_PAD_V)
        assertEquals(52f, UiTokens.actionKeyHeight(touch = false))
        assertEquals(58f, UiTokens.actionKeyHeight(touch = true))
        assertEquals(28f, UiTokens.commonKeyHeight(touch = false))
        assertEquals(34f, UiTokens.commonKeyHeight(touch = true))
        assertEquals(5f, UiTokens.KEY_GAP)
        assertEquals(4f, UiTokens.KEY_RADIUS)
        assertEquals(74f, UiTokens.STAT_VALUE_COL_WIDTH)
        assertEquals(248f, UiTokens.TOOLTIP_WIDTH)
        assertEquals(300f, UiTokens.PLACEMENT_CARD_WIDTH)
        assertEquals(11, UiTokens.FONT_CAPTION)
        assertEquals(19, UiTokens.FONT_CREDITS)

        // Verify all 15 color tokens (full RGB + Alpha) against HUD Spec §3.1
        assertColorRgba(0x212528, 0.86f, UiTokens.RAIL, "RAIL")
        assertColorRgba(0x24292C, 1.00f, UiTokens.SOLID, "SOLID")
        assertColorRgba(0x353B3F, 1.00f, UiTokens.CELL, "CELL")
        assertColorRgba(0x1A1E20, 1.00f, UiTokens.WELL, "WELL")
        assertColorRgba(0x596167, 1.00f, UiTokens.LINE, "LINE")
        assertColorRgba(0xFFFFFF, 0.11f, UiTokens.HAIR, "HAIR")
        assertColorRgba(0xDFE3E5, 1.00f, UiTokens.TEXT, "TEXT")
        assertColorRgba(0x9AA3A8, 1.00f, UiTokens.DIM, "DIM")
        assertColorRgba(0xC9D1D5, 1.00f, UiTokens.VALUE, "VALUE")
        assertColorRgba(0xDBD5C6, 1.00f, UiTokens.BONE, "BONE")
        assertColorRgba(0xF2EDE0, 1.00f, UiTokens.BONE_HI, "BONE_HI")
        assertColorRgba(0x1A1A18, 1.00f, UiTokens.INK, "INK")
        assertColorRgba(0xDC5A22, 1.00f, UiTokens.RUST, "RUST")
        assertColorRgba(0xE6AA2D, 1.00f, UiTokens.AMBER, "AMBER")
        assertColorRgba(0xE0564A, 1.00f, UiTokens.FOE, "FOE")
    }

    @Test
    fun bilingualUiStringsHaveFullParityMapCoreKebabCodesAndPreserveContentPackOriginals() {
        assertEquals(UiStrings.keysZh(), UiStrings.keysEn(), "ZH and EN translation tables must have identical keys")
        assertEquals(UiLanguage.ZH, UiLanguage.fromLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(UiLanguage.ZH, UiLanguage.fromLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(UiLanguage.EN, UiLanguage.fromLocale(Locale.US))
        assertEquals(UiLanguage.fromLocale(Locale.getDefault()), UiStrings.currentLanguage)

        // Unmapped key returns verbatim
        assertEquals("unmapped.future.key", UiStrings.get("unmapped.future.key", UiLanguage.ZH))

        // Fixed UI chrome switches with language, while content-pack unit name stays verbatim!
        val contentUnitName = "重型双足机甲 Mark-IV"
        assertEquals("重型双足机甲 Mark-IV 的动作", UiStrings.format("owner", "NAME" to contentUnitName, language = UiLanguage.ZH))
        assertEquals("Actions of 重型双足机甲 Mark-IV", UiStrings.format("owner", "NAME" to contentUnitName, language = UiLanguage.EN))

        val englishPackUnit = "Modular Spider"
        assertEquals("Modular Spider 生产完成", UiStrings.format("done", "NAME" to englishPackUnit, language = UiLanguage.ZH))
        assertEquals("Modular Spider complete", UiStrings.format("done", "NAME" to englishPackUnit, language = UiLanguage.EN))

        // Core kebab-case SimulationError.code AND screaming-snake codes map cleanly
        assertEquals("资金不足", UiStrings.rejectionReason("insufficient-funds", UiLanguage.ZH))
        assertEquals("Not enough credits", UiStrings.rejectionReason("insufficient-funds", UiLanguage.EN))
        assertEquals("目标不可通行", UiStrings.rejectionReason("action-target-impassable", UiLanguage.ZH))
        assertEquals("Target unreachable", UiStrings.rejectionReason("BLOCKED", UiLanguage.EN))
        assertEquals("储备已满", UiStrings.rejectionReason("ammo-queue-limit", UiLanguage.ZH))
        assertEquals("充能中", UiStrings.rejectionReason("unit-action-locked", UiLanguage.ZH))
        assertEquals("暂未支持：守卫", UiStrings.rejectionReason("guard-unsupported", UiLanguage.ZH))
        assertEquals("CUSTOM_FUTURE_REASON", UiStrings.rejectionReason("CUSTOM_FUTURE_REASON", UiLanguage.ZH))

        // Preload characters include CJK UI strings and extra content names
        val preload = UiStrings.preloadCharacters(listOf("核弹发射井", "反核井"))
        assertTrue('资' in preload && '金' in preload && '▲' in preload && '核' in preload && '井' in preload)
    }

    @Test
    fun packagedOflFontsAndLicenseTextsExistAndAreNonEmpty() {
        for (resourcePath in listOf(
            "/" + UiFontSet.REGULAR_FONT_PATH,
            "/" + UiFontSet.BOLD_FONT_PATH,
            "/" + UiFontSet.MONO_FONT_PATH,
            "/licenses/OFL-1.1-NotoSansSC.txt",
            "/licenses/OFL-1.1-JetBrainsMono.txt",
        )) {
            val stream = UiFontSet::class.java.getResourceAsStream(resourcePath)
            assertNotNull(stream, "Missing packaged resource: $resourcePath")
            val bytes = stream.use { it.readBytes() }
            assertTrue(bytes.size > 1000, "Unexpectedly tiny packaged resource: $resourcePath (${bytes.size} bytes)")
        }

        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val androidLic = repo.resolve("Engine/android/src/main/assets/licenses")
        assertTrue(Files.isRegularFile(androidLic.resolve("OFL-1.1-NotoSansSC.txt")))
        assertTrue(Files.isRegularFile(androidLic.resolve("OFL-1.1-JetBrainsMono.txt")))
        val notices = Files.readString(androidLic.resolve("THIRD-PARTY-NOTICES.txt"))
        assertTrue("Noto Sans SC" in notices && "JetBrains Mono" in notices && "gdx-freetype" in notices)
    }
}
