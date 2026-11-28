package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UiP004MainMenuAndMapPickerTest {

    @Test
    fun mainMenuNavigationKeyboardAndTouchTwoStepExitModalSyncContract() {
        var exitCalled = 0
        var skirmishClicked = 0
        var sandboxClicked = 0
        var settingsClicked = 0
        val shell = AppShell(
            initialRoute = OutGameRoute.MAIN_MENU,
            onRequestExitApp = { exitCalled++ },
        )
        val menu = MainMenuScreen(
            skin = Skin(),
            appShell = shell,
            themeProvider = { UiTheme.DEFAULT },
            assetResolverProvider = { UiThemeAssetResolver() },
            fontsProvider = { null },
            fallbackFontProvider = { error("Unused in headless test") },
            scaleProvider = { 1f },
            touchControls = true,
            onSelectSkirmish = { skirmishClicked++ },
            onSelectSandbox = { sandboxClicked++ },
            onSelectSettings = { settingsClicked++ },
            onSelectQuit = { shell.handleBackKey(requireMainMenuExitConfirm = true) },
        )
        try {
            menu.isVisible = true
            menu.updateLayout(1280f, 720f)
            assertEquals(0, menu.selectedIndex)
            assertFalse(menu.confirmOkActor.isVisible)
            assertEquals(Touchable.enabled, menu.rowActorForTest(0).touchable)

            // Keyboard wrap-around navigation (`UP` from 0 -> 3 `QUIT`, `DOWN` -> 0 `SKIRMISH`, `DOWN` -> 1 `SANDBOX`)
            assertTrue(menu.handleKeyDown(Input.Keys.UP))
            assertEquals(3, menu.selectedIndex)
            assertTrue(menu.handleKeyDown(Input.Keys.DOWN))
            assertEquals(0, menu.selectedIndex)
            assertTrue(menu.handleKeyDown(Input.Keys.ENTER))
            assertEquals(1, skirmishClicked)

            assertTrue(menu.handleKeyDown(Input.Keys.DOWN))
            assertTrue(menu.handleKeyDown(Input.Keys.ENTER))
            assertEquals(1, sandboxClicked)

            assertTrue(menu.handleKeyDown(Input.Keys.DOWN))
            assertTrue(menu.handleKeyDown(Input.Keys.ENTER))
            assertEquals(1, settingsClicked)

            // Triggering `QUIT` on touch opens two-step exit modal, exposes confirm buttons with valid bounds, & disables background rows
            menu.triggerItem(MainMenuScreen.MenuItemKind.QUIT)
            assertTrue(shell.exitConfirmVisible)
            assertTrue(menu.confirmCancelActor.isVisible)
            assertTrue(menu.confirmOkActor.isVisible)
            assertTrue(menu.confirmOkActor.width >= 80f && menu.confirmOkActor.height >= 30f)
            assertEquals(Touchable.disabled, menu.rowActorForTest(0).touchable)
            val modalActions = menu.drawnActions().map { it.actionId }.toSet()
            assertEquals(setOf("exit-confirm-cancel", "exit-confirm-ok"), modalActions)

            // Dismissing via Back/Cancel restores row touchability and hides modal buttons
            assertEquals(BackKeyAction.CLOSED_MODAL, shell.handleBackKey(requireMainMenuExitConfirm = true))
            menu.act(0.016f)
            assertFalse(menu.confirmOkActor.isVisible)
            assertEquals(Touchable.enabled, menu.rowActorForTest(0).touchable)
            assertEquals(0, exitCalled)

            // Trigger again and confirm exit via Enter
            menu.triggerItem(MainMenuScreen.MenuItemKind.QUIT)
            assertTrue(menu.handleKeyDown(Input.Keys.ENTER))
            assertEquals(1, exitCalled)
        } finally {
            menu.close()
        }

        // BootstrapScreen route & sub-page back-key popping
        val screen = BootstrapScreen(openSetup = false, touchControls = true)
        try {
            assertEquals(OutGameRoute.MAIN_MENU, screen.appShell.currentRoute)
            screen.navigateStartupPageForTest("SETUP")
            assertEquals(OutGameRoute.SKIRMISH_LOBBY, screen.appShell.currentRoute)
            screen.navigateStartupPageForTest("MAPS")
            assertEquals(OutGameRoute.MAP_PICKER, screen.appShell.currentRoute)
            assertEquals(BackKeyAction.POPPED_ROUTE, screen.appShell.handleBackKey(requireMainMenuExitConfirm = true))
            assertEquals(OutGameRoute.SKIRMISH_LOBBY, screen.appShell.currentRoute)
            assertEquals(BackKeyAction.POPPED_ROUTE, screen.appShell.handleBackKey(requireMainMenuExitConfirm = true))
            assertEquals(OutGameRoute.MAIN_MENU, screen.appShell.currentRoute)
        } finally {
            screen.dispose()
        }
    }

    @Test
    fun mapPickerOpenSelectionCancelAndEscapeBackCleanupContract() {
        var openedCount = 0
        var closedCount = 0
        lateinit var picker: MapPickerScreen
        val shell = AppShell(
            initialRoute = OutGameRoute.SKIRMISH_LOBBY,
            onRouteChanged = { route ->
                if (route != OutGameRoute.MAP_PICKER) {
                    picker.clearPendingSelectionCallback()
                }
            },
        )
        picker = MapPickerScreen(
            skin = Skin(),
            themeProvider = { UiTheme.DEFAULT },
            fontsProvider = { null },
            fallbackFontProvider = { error("Unused in headless controller test") },
            onNavigateOpen = {
                openedCount++
                shell.push(OutGameRoute.MAP_PICKER)
            },
            onNavigateClose = {
                closedCount++
                shell.pop()
            },
        )
        try {
            var callerSelectedMap = "river_crossing"
            picker.open(
                currentMapId = callerSelectedMap,
                filter = MapPickerFilter.SKIRMISH,
                onMapSelected = { chosen -> callerSelectedMap = chosen.id },
            )
            assertEquals(1, openedCount)
            assertTrue(picker.hasPendingSelectionCallback)
            assertEquals("river_crossing", picker.selectedMapId)
            assertTrue(picker.visibleEntries().size >= 3, "Expected packaged real playable .tmx fallback map catalog")
            assertTrue(
                picker.visibleEntries().all { val p = it.tmxPath; p != null && Files.isRegularFile(p) },
                "Every MapPicker catalog entry must point to a real playable .tmx file (zero fake procedural maps)",
            )
            val tenPlayerEntry = picker.visibleEntries().firstOrNull { it.id == "ring_atoll" }
            assertNotNull(tenPlayerEntry)
            assertEquals(10, tenPlayerEntry.maxPlayers)

            // Escape / Android Back pops route and clears pending selection callback without mutating caller selection
            assertEquals(BackKeyAction.POPPED_ROUTE, shell.handleBackKey(requireMainMenuExitConfirm = false))
            assertFalse(picker.hasPendingSelectionCallback)
            assertEquals("river_crossing", callerSelectedMap)

            // Re-open, cancel via top-bar `← 返回`, verify preserved selection
            picker.open(
                currentMapId = callerSelectedMap,
                filter = MapPickerFilter.SKIRMISH,
                onMapSelected = { chosen -> callerSelectedMap = chosen.id },
            )
            assertTrue(picker.hasPendingSelectionCallback)
            picker.cancel()
            assertFalse(picker.hasPendingSelectionCallback)
            assertEquals("river_crossing", callerSelectedMap)

            // Re-open and pick `volcanic_archipelago`
            picker.open(
                currentMapId = callerSelectedMap,
                filter = MapPickerFilter.FREE_SANDBOX,
                onMapSelected = { chosen -> callerSelectedMap = chosen.id },
            )
            assertTrue(picker.selectMap("volcanic_archipelago"))
            assertFalse(picker.hasPendingSelectionCallback)
            assertEquals("volcanic_archipelago", callerSelectedMap)
            assertEquals("volcanic_archipelago", picker.selectedMapId)

            // Verify card label style branching: dark uses bottom gradient shadow, white & bone use bordered pill badge
            assertFalse(MapPickerScreen.isPillBadgeStyle(UiTheme.DEFAULT))
            assertTrue(MapPickerScreen.isPillBadgeStyle(UiTheme.WHITE))
            assertTrue(MapPickerScreen.isPillBadgeStyle(UiTheme.BONE))
        } finally {
            picker.close()
        }
    }

    @Test
    fun darkMenuExtrudeTextEnabledOnlyOnDarkThemeAndCompositorProducesDiagonalHatch() {
        assertTrue(DarkMenuExtrudeRenderer.isEnabledForTheme(UiTheme.DEFAULT))
        assertFalse(DarkMenuExtrudeRenderer.isEnabledForTheme(UiTheme.WHITE))
        assertFalse(DarkMenuExtrudeRenderer.isEnabledForTheme(UiTheme.BONE))

        val w = 64
        val h = 48
        val mask = FloatArray(w * h)
        for (y in 10..34) {
            for (x in 14..22) mask[y * w + x] = 1f
        }
        for (y in 18..24) {
            for (x in 14..38) mask[y * w + x] = 1f
        }

        val rgba = DarkMenuExtrudeRenderer.composeExtrudeRgbaBuffer(mask, w, h, scale = 1.0f, theme = UiTheme.DEFAULT)
        var opaqueFacePixels = 0
        var extrudedOrangePixels = 0
        for (pixel in rgba) {
            val r = (pixel ushr 24) and 0xFF
            val g = (pixel ushr 16) and 0xFF
            val b = (pixel ushr 8) and 0xFF
            val a = pixel and 0xFF
            if (a > 200 && r > 220 && g > 210 && b > 190) opaqueFacePixels++
            if (a > 35 && r > 180 && g in 55..145 && b < 80) extrudedOrangePixels++
        }
        assertTrue(opaqueFacePixels > 100, "Expected crisp boneHi front glyph face pixels, got $opaqueFacePixels")
        assertTrue(extrudedOrangePixels > 25, "Expected diagonal hatched orange extrusion pixels, got $extrudedOrangePixels")
    }

    @Test
    fun rwreVectorLogoRasterizesStencilCutsAndAdaptsToAllThreeThemes() {
        val w = 404
        val h = 118
        val darkBuf = RwreLogoVectorSpec.renderToRgbaBuffer(w, h, UiTheme.DEFAULT)
        val whiteBuf = RwreLogoVectorSpec.renderToRgbaBuffer(w, h, UiTheme.WHITE)
        val boneBuf = RwreLogoVectorSpec.renderToRgbaBuffer(w, h, UiTheme.BONE)

        val darkStripe = Color().also { Color.rgba8888ToColor(it, darkBuf[58 * w + 4]) }
        val whiteStripe = Color().also { Color.rgba8888ToColor(it, whiteBuf[58 * w + 4]) }
        val boneStripe = Color().also { Color.rgba8888ToColor(it, boneBuf[58 * w + 4]) }
        assertTrue(abs(darkStripe.r - UiTheme.DEFAULT.outGame.logoAccent.r) < 0.03f)
        assertTrue(abs(whiteStripe.r - UiTheme.WHITE.outGame.logoAccent.r) < 0.03f)
        assertTrue(abs(boneStripe.r - UiTheme.BONE.outGame.logoAccent.r) < 0.03f)

        assertNotEquals(darkBuf[57 * w + 37], whiteBuf[57 * w + 37], "Logo main glyph color must follow UiTheme")

        val wCutAlpha = darkBuf[83 * w + 143] and 0xFF
        assertEquals(0, wCutAlpha, "W stencil bridge cut must remain transparent")
    }

    @Test
    fun dynamicMapBackdropPansOnlyWhenVisibleWithoutRunningSimulation() {
        val backdrop = DynamicMapBackdrop(
            skin = Skin(),
            themeProvider = { UiTheme.BONE },
            activeMapProvider = { null },
            deepScrimProvider = { true },
        )
        try {
            assertEquals(0f, backdrop.panProgress, 1e-5f)
            backdrop.act(50f)
            assertEquals(0.25f, backdrop.panProgress, 1e-4f)

            // Hidden during in-game match (`isVisible = false`) -> pan timer pauses cleanly
            backdrop.isVisible = false
            backdrop.act(100f)
            assertEquals(0.25f, backdrop.panProgress, 1e-4f)

            backdrop.isVisible = true
            backdrop.act(150f)
            assertEquals(1.0f, backdrop.panProgress, 1e-4f)
            backdrop.act(50f)
            assertEquals(0.75f, backdrop.panProgress, 1e-4f)

            val defaultMap = OutGameMapCatalog.defaultEntry().mapDefinition
            val blueprintBuf = OutGameMapPreviewRenderer.renderBackdropBlueprintRgbaBuffer(defaultMap, UiTheme.BONE, 320, 180)
            val tacticalDark = OutGameMapPreviewRenderer.renderTacticalRgbaBuffer(defaultMap, 128, 128, UiTheme.DEFAULT)
            val tacticalWhite = OutGameMapPreviewRenderer.renderTacticalRgbaBuffer(defaultMap, 128, 128, UiTheme.WHITE)
            assertEquals(320 * 180, blueprintBuf.size)
            assertEquals(128 * 128, tacticalDark.size)
            assertEquals(128 * 128, tacticalWhite.size)
            assertTrue(tacticalDark.distinct().size >= 4, "Expected multi-tone terrain + ore rendering")
            assertNotEquals(tacticalDark.contentHashCode(), tacticalWhite.contentHashCode(), "Tactical preview respects active UiTheme")
        } finally {
            backdrop.close()
        }
    }

    @Test
    fun uiP004OutGameNewSourcesContainZeroHardcodedHexColorLiteralsAndZeroStaticDefaultCoupling() {
        val candidates = listOf(
            "OutGameChrome.kt",
            "RwreLogoDrawable.kt",
            "DynamicMapBackdrop.kt",
            "MainMenuScreen.kt",
            "MapPickerScreen.kt",
            "ResourcePackOptions.kt",
            "ResourcePackOptionsModal.kt",
        )
        val srcDir = listOf(
            Paths.get("src/main/kotlin/com/rustedwarfare/reforge/client"),
            Paths.get("Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client"),
            Paths.get("../Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client"),
        ).firstOrNull { Files.isDirectory(it) } ?: error("Cannot locate client Kotlin source directory from ${Paths.get("").toAbsolutePath()}")

        val hexColorRegex = Regex("""(#[0-9A-Fa-f]{6,8}\b|0x[0-9A-Fa-f]{6,8}\b)""")
        for (fileName in candidates) {
            val path = srcDir.resolve(fileName)
            assertTrue(Files.isRegularFile(path), "Expected source file $path")
            val lines = Files.readAllLines(path)
            lines.forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                val cleaned = trimmed.replace("0xFFFFFFFFL", "").replace("0xFFFFFFFF", "")
                assertFalse(
                    hexColorRegex.containsMatchIn(cleaned),
                    "Hardcoded hex color literal found in $fileName:${idx + 1}: $trimmed",
                )
                assertFalse(
                    trimmed.contains("UiTheme.DEFAULT"),
                    "Static UiTheme.DEFAULT coupling found in $fileName:${idx + 1}: $trimmed",
                )
            }
        }
    }
}
