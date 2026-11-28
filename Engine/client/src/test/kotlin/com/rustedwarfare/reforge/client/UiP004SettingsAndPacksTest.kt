package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.Array
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class UiP004SettingsAndPacksTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dummyFont: BitmapFont
    private lateinit var skin: Skin

    @BeforeEach
    fun setUp() {
        UiStrings.currentLanguage = UiLanguage.ZH
        dummyFont = BitmapFont(BitmapFont.BitmapFontData(), Array.with(TextureRegion()), true)
        skin = Skin()
    }

    @AfterEach
    fun tearDown() {
        UiStrings.currentLanguage = UiLanguage.ZH
    }

    private fun createManagedPackScreen(
        store: UserSettingsStore,
        appShell: AppShell,
        onPreviewScale: (Float) -> Unit = {},
        onCommitScale: (Float) -> Unit = {},
        onRevertScale: (Float) -> Unit = {},
        onBack: () -> Unit = {},
    ): SettingsScreen {
        val knownResIds = listOf("pack:alpha", "pack:beta", "pack:gamma")
        val knownModIds = listOf("mod:mech", "mod:naval")

        fun buildResEntries(): List<DualColumnPackEntry> {
            val enabledOrder = store.effective.enabledResourcePacks
            val enabledNonBase = enabledOrder.filter { it != UserSettings.BASE_RESOURCE_PACK_ID }
            val out = ArrayList<DualColumnPackEntry>()
            for (id in enabledNonBase) {
                out += DualColumnPackEntry(
                    id = id,
                    title = if (UiStrings.currentLanguage == UiLanguage.ZH) "中文资源包 $id" else "Resource $id",
                    subtitle = "v1.0",
                    enabled = true,
                    lockedBottom = false,
                    badgeStyle = PackIconBadgeStyle.CUSTOM_RESOURCE,
                )
            }
            out += DualColumnPackEntry(
                id = UserSettings.BASE_RESOURCE_PACK_ID,
                title = "内置基础资源包",
                subtitle = "reforge:base",
                enabled = true,
                lockedBottom = true,
                canEnable = false,
                badgeStyle = PackIconBadgeStyle.BASE_RESOURCE,
                options = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER).options,
            )
            for (id in knownResIds) {
                if (id !in enabledNonBase) {
                    out += DualColumnPackEntry(
                        id = id,
                        title = if (UiStrings.currentLanguage == UiLanguage.ZH) "中文资源包 $id" else "Resource $id",
                        subtitle = "v1.0",
                        enabled = false,
                        lockedBottom = false,
                        badgeStyle = PackIconBadgeStyle.CUSTOM_RESOURCE,
                    )
                }
            }
            return out
        }

        fun buildModEntries(): List<DualColumnPackEntry> {
            val enabledOrder = store.effective.enabledContentPacks
            val enabledNonBase = enabledOrder.filter { it != UserSettings.DEFAULT_BASE_CONTENT_PACK_ID }
            val out = ArrayList<DualColumnPackEntry>()
            for (id in enabledNonBase) {
                out += DualColumnPackEntry(
                    id = id,
                    title = LocalizedMetadata.contentPackName(id, "Fallback $id"),
                    subtitle = "v1.0",
                    enabled = true,
                    lockedBottom = false,
                    badgeStyle = PackIconBadgeStyle.CUSTOM_CONTENT,
                )
            }
            out += DualColumnPackEntry(
                id = UserSettings.DEFAULT_BASE_CONTENT_PACK_ID,
                title = LocalizedMetadata.contentPackName(UserSettings.DEFAULT_BASE_CONTENT_PACK_ID, "Phase VII Sandbox Pack"),
                subtitle = LocalizedMetadata.contentPackDescription(UserSettings.DEFAULT_BASE_CONTENT_PACK_ID, ""),
                enabled = true,
                lockedBottom = true,
                canEnable = false,
                badgeStyle = PackIconBadgeStyle.BASE_CONTENT,
            )
            for (id in knownModIds) {
                if (id !in enabledNonBase) {
                    out += DualColumnPackEntry(
                        id = id,
                        title = "扩展模组 $id",
                        subtitle = "v1.0",
                        enabled = false,
                        lockedBottom = false,
                        badgeStyle = PackIconBadgeStyle.CUSTOM_CONTENT,
                    )
                }
            }
            return out
        }

        return SettingsScreen(
            skin = skin,
            appShell = appShell,
            settingsStore = store,
            themeProvider = UiThemeProvider { UiTheme.builtIn(store.effective.themeId) },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            resourcePacksProvider = ::buildResEntries,
            contentPacksProvider = ::buildModEntries,
            onNavigateBack = onBack,
            onPreviewUiScale = onPreviewScale,
            onCommitUiScale = onCommitScale,
            onRevertUiScale = onRevertScale,
            onMoveResourcePack = { id, delta ->
                store.update { cur ->
                    val list = cur.enabledResourcePacks.filter { it != UserSettings.BASE_RESOURCE_PACK_ID }.toMutableList()
                    val idx = list.indexOf(id)
                    val target = idx + delta
                    if (idx >= 0 && target in list.indices) {
                        java.util.Collections.swap(list, idx, target)
                    }
                    cur.copy(enabledResourcePacks = list + UserSettings.BASE_RESOURCE_PACK_ID)
                }
            },
            onSetResourcePackEnabled = { id, enabled ->
                if (id != UserSettings.BASE_RESOURCE_PACK_ID) {
                    store.update { cur ->
                        val baseFiltered = cur.enabledResourcePacks.filter { it != UserSettings.BASE_RESOURCE_PACK_ID && it != id }
                        val next = if (enabled) listOf(id) + baseFiltered else baseFiltered
                        cur.copy(enabledResourcePacks = next + UserSettings.BASE_RESOURCE_PACK_ID)
                    }
                }
            },
            onMoveContentPack = { id, delta ->
                store.update { cur ->
                    val list = cur.enabledContentPacks.filter { it != UserSettings.DEFAULT_BASE_CONTENT_PACK_ID }.toMutableList()
                    val idx = list.indexOf(id)
                    val target = idx + delta
                    if (idx >= 0 && target in list.indices) {
                        java.util.Collections.swap(list, idx, target)
                    }
                    cur.copy(enabledContentPacks = list + UserSettings.DEFAULT_BASE_CONTENT_PACK_ID)
                }
            },
            onSetContentPackEnabled = { id, enabled ->
                if (id != UserSettings.DEFAULT_BASE_CONTENT_PACK_ID) {
                    store.update { cur ->
                        val baseFiltered = cur.enabledContentPacks.filter { it != UserSettings.DEFAULT_BASE_CONTENT_PACK_ID && it != id }
                        val next = if (enabled) listOf(id) + baseFiltered else baseFiltered
                        cur.copy(enabledContentPacks = next + UserSettings.DEFAULT_BASE_CONTENT_PACK_ID)
                    }
                }
            },
        )
    }

    @Test
    fun `7 categories switch cleanly and persist language, audio, theme, and controls immediately to settings_json`() {
        val settingsFile = tempDir.resolve("settings.json")
        val store = UserSettingsStore(settingsFile)
        val appShell = AppShell(initialRoute = OutGameRoute.MAIN_MENU)
        appShell.push(OutGameRoute.SETTINGS)

        val screen = createManagedPackScreen(store, appShell)
        screen.isVisible = true
        screen.updateLayout(1920f, 1080f)

        assertEquals(7, SettingsCategory.entries.size)
        assertEquals(SettingsCategory.GENERAL, screen.currentCategory)

        // 1. General -> switch language to EN and back to ZH (instant persistence)
        screen.languageSegmented.select(UiLanguage.EN)
        assertEquals(UiLanguage.EN, UiStrings.currentLanguage)
        assertEquals(UiLanguage.EN, UserSettingsStore(settingsFile).effective.language)
        screen.languageSegmented.select(UiLanguage.ZH)
        assertEquals(UiLanguage.ZH, UserSettingsStore(settingsFile).effective.language)

        // 2. Audio -> master volume, sfx volume, mute
        screen.selectCategory(SettingsCategory.AUDIO)
        screen.masterVolumeSlider.setValueAndNotify(0.75f)
        screen.sfxVolumeSlider.setValueAndNotify(0.40f)
        screen.muteSegmented.select(true)
        val reloadedAudio = UserSettingsStore(settingsFile).effective
        assertEquals(0.75f, reloadedAudio.masterVolume, 1e-3f)
        assertEquals(0.40f, reloadedAudio.sfxVolume, 1e-3f)
        assertTrue(reloadedAudio.muted)

        // 3. Video -> contains ONLY `UI 缩放` (zero duplicate `settings-theme` row); built-in themes live in `reforge:base` options!
        screen.selectCategory(SettingsCategory.VIDEO)
        assertFalse(screen.drawnActions().any { it.actionId == "settings-theme" }, "Duplicate theme row in VIDEO must be removed")
        screen.selectCategory(SettingsCategory.RESOURCE_PACKS)
        assertTrue(screen.resourcePackManager.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "options"))
        assertTrue(screen.packOptionsModal.isOpen)
        assertEquals(ResourcePackOptionsModal.MODAL_ID, appShell.activeModalId)
        assertTrue(screen.packOptionsModal.selectOptionForTest(UserSettings.BASE_THEME_OPTION_ID, BuiltInUiThemeId.BONE.code))
        assertEquals(BuiltInUiThemeId.BONE, UserSettingsStore(settingsFile).effective.themeId)
        assertEquals("bone", UserSettingsStore(settingsFile).effective.optionsForPack(UserSettings.BASE_RESOURCE_PACK_ID)["theme"])
        screen.packOptionsModal.confirmButton.triggerClick()
        assertFalse(screen.packOptionsModal.isOpen)

        // 4. Controls -> joystick, edge scroll, pan & zoom sensitivities
        screen.selectCategory(SettingsCategory.CONTROLS)
        screen.touchJoystickSegmented.select(false)
        screen.edgeScrollSegmented.select(false)
        screen.panSensitivitySlider.setValueAndNotify(1.50f)
        screen.zoomSensitivitySlider.setValueAndNotify(1.25f)
        val reloadedCtrl = UserSettingsStore(settingsFile).effective
        assertFalse(reloadedCtrl.touchCameraJoystick)
        assertFalse(reloadedCtrl.windowEdgeScroll)
        assertEquals(1.50f, reloadedCtrl.cameraPanSensitivity, 1e-3f)
        assertEquals(1.25f, reloadedCtrl.cameraZoomSensitivity, 1e-3f)

        // 7. About -> license & third-party modal opens and dismisses via Back key before popping route
        screen.selectCategory(SettingsCategory.ABOUT)
        screen.viewLicenseButton.triggerClick()
        assertEquals("settings-about-doc", appShell.activeModalId)
        assertEquals(BackKeyAction.CLOSED_MODAL, appShell.handleBackKey())
        assertEquals(OutGameRoute.SETTINGS, appShell.currentRoute)

        screen.close()
    }

    @Test
    fun `DualColumnPackManagerView enforces enable disable reorder, bottom base lock, displayText_zh, and inMatchMode dual lock`() {
        val settingsFile = tempDir.resolve("packs-settings.json")
        val store = UserSettingsStore(settingsFile)
        val appShell = AppShell(initialRoute = OutGameRoute.SETTINGS)
        val screen = createManagedPackScreen(store, appShell)
        screen.isVisible = true
        screen.updateLayout(1920f, 1080f)

        // Resource Packs: initially only `reforge:base` is enabled and locked at bottom
        screen.selectCategory(SettingsCategory.RESOURCE_PACKS)
        assertEquals(listOf(UserSettings.BASE_RESOURCE_PACK_ID), screen.resourcePackManager.currentEnabledEntries.map { it.id })
        assertFalse(
            screen.resourcePackManager.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "disable"),
            "Base resource pack must never be disabled or removed",
        )
        assertFalse(screen.resourcePackManager.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "up"))

        // Enable `pack:alpha` and `pack:beta`, then reorder priority (`pack:beta` initially on top -> move `pack:alpha` up)
        assertTrue(screen.resourcePackManager.triggerPackActionForTest("pack:alpha", "enable"))
        assertTrue(screen.resourcePackManager.triggerPackActionForTest("pack:beta", "enable"))
        assertEquals(
            listOf("pack:beta", "pack:alpha", UserSettings.BASE_RESOURCE_PACK_ID),
            UserSettingsStore(settingsFile).effective.enabledResourcePacks,
        )
        assertTrue(screen.resourcePackManager.triggerPackActionForTest("pack:alpha", "up"))
        assertEquals(
            listOf("pack:alpha", "pack:beta", UserSettings.BASE_RESOURCE_PACK_ID),
            UserSettingsStore(settingsFile).effective.enabledResourcePacks,
        )
        // Cannot move `pack:beta` down past locked `reforge:base`!
        assertFalse(screen.resourcePackManager.triggerPackActionForTest("pack:beta", "down"))

        // Content Packs: verify `displayText_zh` (`现行综合试玩包`) and bottom lock on `phase-seven.json`
        screen.selectCategory(SettingsCategory.CONTENT_PACKS)
        val baseMod = screen.contentPackManager.currentEnabledEntries.single()
        assertEquals(UserSettings.DEFAULT_BASE_CONTENT_PACK_ID, baseMod.id)
        assertEquals("现行综合试玩包", baseMod.title)
        assertFalse(screen.contentPackManager.triggerPackActionForTest(UserSettings.DEFAULT_BASE_CONTENT_PACK_ID, "disable"))

        assertTrue(screen.contentPackManager.triggerPackActionForTest("mod:mech", "enable"))
        assertEquals(
            listOf("mod:mech", UserSettings.DEFAULT_BASE_CONTENT_PACK_ID),
            UserSettingsStore(settingsFile).effective.enabledContentPacks,
        )

        // Enable `inMatchMode = true`: BOTH resource packs and content packs lock add/remove/reorder,
        // while `reforge:base`'s pure-UiTheme `options` modal AND language still switch live!
        screen.inMatchMode = true
        assertFalse(screen.resourcePackManager.triggerPackActionForTest("pack:alpha", "disable"))
        assertFalse(screen.contentPackManager.triggerPackActionForTest("mod:mech", "disable"))
        assertFalse(screen.openFolderButton.enabled)
        screen.selectCategory(SettingsCategory.RESOURCE_PACKS)
        assertTrue(screen.resourcePackManager.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "options"))
        assertTrue(screen.packOptionsModal.isOpen)
        assertTrue(screen.packOptionsModal.selectOptionForTest(UserSettings.BASE_THEME_OPTION_ID, BuiltInUiThemeId.WHITE.code))
        assertEquals(BuiltInUiThemeId.WHITE, UserSettingsStore(settingsFile).effective.themeId)
        screen.packOptionsModal.dismiss()

        screen.selectCategory(SettingsCategory.GENERAL)
        screen.languageSegmented.select(UiLanguage.EN)
        assertEquals(UiLanguage.EN, UiStrings.currentLanguage)
        assertEquals(UiLanguage.EN, UserSettingsStore(settingsFile).effective.language)

        screen.close()
    }

    @Test
    fun `UiScaleConfirmModal auto-reverts after 10 seconds or Back key without polluting disk, and persists on Keep`() {
        val settingsFile = tempDir.resolve("scale-settings.json")
        val store = UserSettingsStore(settingsFile)
        val appShell = AppShell(initialRoute = OutGameRoute.MAIN_MENU)
        appShell.push(OutGameRoute.SETTINGS)

        var liveAppliedScale = 1.0f
        val screen = createManagedPackScreen(
            store = store,
            appShell = appShell,
            onPreviewScale = { liveAppliedScale = it },
            onCommitScale = { liveAppliedScale = it },
            onRevertScale = { liveAppliedScale = it },
            onBack = { appShell.handleBackKey(requireMainMenuExitConfirm = false) },
        )
        screen.isVisible = true
        screen.selectCategory(SettingsCategory.VIDEO)
        screen.updateLayout(1920f, 1080f)

        // 1. Select `125%` -> live scale previews `1.25f`, modal opens, disk STILL has `1.0f`!
        screen.uiScaleSegmented.select(1.25f)
        assertTrue(screen.uiScaleModal.isOpen)
        assertEquals(1.25f, liveAppliedScale, 1e-4f)
        assertEquals(1.0f, UserSettingsStore(settingsFile).effective.uiScaleMultiplier, 1e-4f)

        // Advance 9.5s -> still open; advance +0.6s (>= 10.0s) -> auto-reverts to `1.0f`!
        screen.uiScaleModal.stepTimer(9.5f)
        assertTrue(screen.uiScaleModal.isOpen)
        screen.uiScaleModal.stepTimer(0.6f)
        assertFalse(screen.uiScaleModal.isOpen)
        assertEquals(1.0f, liveAppliedScale, 1e-4f)
        assertEquals(1.0f, screen.uiScaleSegmented.selected, 1e-4f)
        assertEquals(1.0f, UserSettingsStore(settingsFile).effective.uiScaleMultiplier, 1e-4f)

        // 2. Android / PC Back key priority: first Back dismisses modal & reverts scale; second Back pops SETTINGS -> MAIN_MENU!
        screen.uiScaleSegmented.select(1.10f)
        assertTrue(screen.uiScaleModal.isOpen)
        assertEquals(1.10f, liveAppliedScale, 1e-4f)
        assertEquals(BackKeyAction.CLOSED_MODAL, appShell.handleBackKey())
        assertFalse(screen.uiScaleModal.isOpen)
        assertEquals(1.0f, liveAppliedScale, 1e-4f)
        assertEquals(OutGameRoute.SETTINGS, appShell.currentRoute)

        assertEquals(BackKeyAction.POPPED_ROUTE, appShell.handleBackKey())
        assertEquals(OutGameRoute.MAIN_MENU, appShell.currentRoute)

        // 3. Re-enter SETTINGS, select `125%`, click `保留` (`keep`) -> persists `1.25f` to `settings.json`!
        appShell.push(OutGameRoute.SETTINGS)
        screen.uiScaleSegmented.select(1.25f)
        assertTrue(screen.uiScaleModal.isOpen)
        screen.uiScaleModal.keepButton.triggerClick()
        assertFalse(screen.uiScaleModal.isOpen)
        assertEquals(1.25f, liveAppliedScale, 1e-4f)
        assertEquals(1.25f, UserSettingsStore(settingsFile).effective.uiScaleMultiplier, 1e-4f)

        // 4. In-match mode (`appShell.enterMatch()` -> `inMatch == true`): Back key STILL dismisses active UI scale & About modals first!
        appShell.enterMatch()
        assertTrue(appShell.inMatch)
        screen.inMatchMode = true
        screen.uiScaleSegmented.select(0.85f)
        assertTrue(screen.uiScaleModal.isOpen)
        assertEquals(0.85f, liveAppliedScale, 1e-4f)
        assertEquals(BackKeyAction.CLOSED_MODAL, appShell.handleBackKey())
        assertFalse(screen.uiScaleModal.isOpen)
        assertEquals(1.25f, liveAppliedScale, 1e-4f)

        screen.selectCategory(SettingsCategory.ABOUT)
        screen.viewThirdPartyButton.triggerClick()
        assertEquals("settings-about-doc", appShell.activeModalId)
        assertEquals(BackKeyAction.CLOSED_MODAL, appShell.handleBackKey())
        assertEquals(BackKeyAction.NONE, appShell.handleBackKey())

        screen.close()
    }

    @Test
    fun `ADR-014 layering eliminates virtual ui-theme packs, locks reforge base at bottom with gear options, and syncs BootstrapScreen content root`() {
        assertEquals(UiStrings.keysZh(), UiStrings.keysEn(), "Bilingual string table keys must be 100% identical")

        val store = UserSettingsStore(tempDir.resolve("adr014-settings.json"))
        var moves = 0
        var disables = 0
        var openedOptionsFor: String? = null
        val mgr = DualColumnPackManagerView(
            skin = skin,
            kind = PackManagerKind.RESOURCE_PACKS,
            themeProvider = UiThemeProvider { UiTheme.builtIn(BuiltInUiThemeId.DARK) },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            onMovePriority = { _, _ -> moves++ },
            onSetPackEnabled = { _, _ -> disables++ },
            onOpenPackOptions = { entry -> openedOptionsFor = entry.id },
        )
        mgr.setBounds(0f, 0f, 700f, 180f)
        val baseOptions = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER).options
        mgr.setEntries(
            listOf(
                DualColumnPackEntry("pack:user1", "用户资源包 1", "v1", enabled = true, canReorder = true, canDisable = true, badgeStyle = PackIconBadgeStyle.CUSTOM_RESOURCE),
                DualColumnPackEntry("pack:user2", "用户资源包 2", "v1", enabled = true, canReorder = true, canDisable = true, badgeStyle = PackIconBadgeStyle.CUSTOM_RESOURCE),
                DualColumnPackEntry(UserSettings.BASE_RESOURCE_PACK_ID, "内置基础资源包", "base", enabled = true, lockedBottom = true, canReorder = false, canDisable = false, canEnable = false, badgeStyle = PackIconBadgeStyle.BASE_RESOURCE, options = baseOptions),
            ),
        )
        // `pack:user2` cannot move down past locked `reforge:base`; `reforge:base` cannot reorder or disable, but opens `options`!
        assertFalse(mgr.triggerPackActionForTest("pack:user2", "down"))
        assertFalse(mgr.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "up"))
        assertFalse(mgr.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "disable"))
        assertTrue(mgr.triggerPackActionForTest(UserSettings.BASE_RESOURCE_PACK_ID, "options"))
        assertEquals(UserSettings.BASE_RESOURCE_PACK_ID, openedOptionsFor)
        assertTrue(mgr.triggerPackActionForTest("pack:user2", "up"))
        assertEquals(1, moves)
        assertEquals(0, disables)
        mgr.close()

        // Verify BootstrapScreen startup restores saved non-base content pack root (`phase-six-a.json` or custom root)
        val mockPackDir = tempDir.resolve("custom-mod")
        Files.createDirectories(mockPackDir)
        Files.writeString(mockPackDir.resolve("pack.json"), "{}")
        store.update {
            it.copy(
                knownContentPackPaths = mapOf("custom:mod" to mockPackDir.toString()),
                enabledContentPacks = listOf("custom:mod", UserSettings.DEFAULT_BASE_CONTENT_PACK_ID),
            )
        }
        val bs = BootstrapScreen(
            initialRoot = tempDir.resolve("default-root"),
            initialContent = false,
            userSettingsStore = store,
        )
        // Before `create()`, constructor initializes `launch` with `touchCameraJoystick` from store; verify store wiring
        assertEquals(store.effective.touchCameraJoystick, bs.currentLaunchOptionsForTest.cameraJoystickEnabled)
    }

    @Test
    fun `SettingsScreen, DualColumnPackManagerView, and UiScaleConfirmModal contain zero hardcoded colors, UiTheme DEFAULT, or AWT imports`() {
        val clientSrc = listOf(
            Paths.get("src/main/kotlin/com/rustedwarfare/reforge/client"),
            Paths.get("Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client"),
        ).first { Files.isDirectory(it) }

        val targetFiles = listOf("SettingsScreen.kt", "DualColumnPackManagerView.kt", "UiScaleConfirmModal.kt", "ResourcePackOptionsModal.kt")
        val hexColorRegex = Regex("""Color\.valueOf\(|#[0-9A-Fa-f]{6,8}|0x[0-9A-Fa-f]{6,8}""")
        val colorCtorRegex = Regex("""\bColor\s*\(""")
        val defaultThemeRegex = Regex("""\bUiTheme\.DEFAULT\b""")
        val awtRegex = Regex("""import\s+java\.awt\.|import\s+javax\.swing\.""")

        for (name in targetFiles) {
            val content = Files.readString(clientSrc.resolve(name))
            assertFalse(hexColorRegex.containsMatchIn(content), "Forbidden hardcoded hex color in $name")
            assertFalse(colorCtorRegex.containsMatchIn(content), "Forbidden Color(...) literal in $name")
            assertFalse(defaultThemeRegex.containsMatchIn(content), "Forbidden UiTheme.DEFAULT reference in $name")
            assertFalse(awtRegex.containsMatchIn(content), "Forbidden java.awt/javax.swing import in $name")
        }
    }
}
