package com.rustedwarfare.reforge.client

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiP004ShellSettingsThemeTest {

    @AfterTest
    fun restoreDefaultLanguage() {
        UiStrings.currentLanguage = UiLanguage.ZH
    }

    @Test
    fun appShellRouteStackModalsLoadingAndAndroidBackKeyFollowSpec() {
        var cancelledLoads = 0
        var exitRequests = 0
        val visited = mutableListOf<OutGameRoute>()
        val shell = AppShell(
            initialRoute = OutGameRoute.MAIN_MENU,
            onRouteChanged = { visited += it },
            onCancelLoading = { cancelledLoads++ },
            onRequestExitApp = { exitRequests++ },
        )
        assertEquals(OutGameRoute.MAIN_MENU, shell.currentRoute)
        assertEquals(listOf(OutGameRoute.MAIN_MENU), shell.stack)

        // Main Menu -> Skirmish Lobby -> Map Picker -> Pop back to Skirmish Lobby
        shell.push(OutGameRoute.SKIRMISH_LOBBY)
        assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.SKIRMISH_LOBBY), shell.stack)
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, shell.preMatchRoute)

        shell.push(OutGameRoute.MAP_PICKER)
        assertEquals(OutGameRoute.MAP_PICKER, shell.currentRoute)
        assertEquals(BackKeyAction.POPPED_ROUTE, shell.handleBackKey())
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, shell.currentRoute)

        // Modal stack preempts route pop (e.g. More Settings / UI Scale Confirm)
        var dismissedMoreSettings = false
        shell.openModal(AppShell.MODAL_MORE_SETTINGS) { dismissedMoreSettings = true }
        assertTrue(shell.hasActiveModal)
        assertEquals(AppShell.MODAL_MORE_SETTINGS, shell.activeModalId)
        assertEquals(BackKeyAction.CLOSED_MODAL, shell.handleBackKey())
        assertTrue(dismissedMoreSettings)
        assertFalse(shell.hasActiveModal)
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, shell.currentRoute)

        // Loading cancel via Back returns to launching route without losing lobby context
        shell.beginLoading()
        assertEquals(OutGameRoute.LOADING, shell.currentRoute)
        assertEquals(BackKeyAction.CANCELLED_LOADING, shell.handleBackKey())
        assertEquals(1, cancelledLoads)
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, shell.currentRoute)

        // Enter match & return to lobby preserves launching route (Test & Sandbox)
        shell.replace(OutGameRoute.TEST_AND_SANDBOX)
        assertEquals(OutGameRoute.TEST_AND_SANDBOX, shell.preMatchRoute)
        shell.beginLoading()
        shell.enterMatch()
        assertTrue(shell.inMatch)
        assertEquals(BackKeyAction.NONE, shell.handleBackKey())
        shell.returnToLobby()
        assertFalse(shell.inMatch)
        assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.TEST_AND_SANDBOX), shell.stack)

        // Pop to Main Menu -> Android Back triggers two-step exit confirmation rather than immediate exit
        assertEquals(BackKeyAction.POPPED_ROUTE, shell.handleBackKey(requireMainMenuExitConfirm = true))
        assertEquals(OutGameRoute.MAIN_MENU, shell.currentRoute)
        assertEquals(BackKeyAction.PROMPTED_EXIT_CONFIRM, shell.handleBackKey(requireMainMenuExitConfirm = true))
        assertTrue(shell.exitConfirmVisible)
        assertEquals(0, exitRequests)

        // Pressing Back again dismisses the exit confirm modal; explicit confirmExit exits app
        assertEquals(BackKeyAction.CLOSED_MODAL, shell.handleBackKey(requireMainMenuExitConfirm = true))
        assertFalse(shell.exitConfirmVisible)
        assertEquals(BackKeyAction.PROMPTED_EXIT_CONFIRM, shell.handleBackKey(requireMainMenuExitConfirm = true))
        assertEquals(BackKeyAction.EXIT_APP, shell.confirmExit())
        assertEquals(1, exitRequests)
    }

    @Test
    fun bootstrapScreenThinDelegationAlignsInitialRoutePopsAncestorRoutesAndRestoresOriginPageOnCancel() {
        val homeScreen = BootstrapScreen(openSetup = false, development = false)
        try {
            assertEquals(OutGameRoute.MAIN_MENU, homeScreen.appShell.currentRoute)
            assertEquals("HOME", homeScreen.currentStartupPageNameForTest)
            assertEquals(listOf(OutGameRoute.MAIN_MENU), homeScreen.appShell.stack)
        } finally {
            homeScreen.dispose()
        }

        val repoRoot = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val devSeed = repoRoot.resolve("SimulationTest/public/scenarios/phase-six-a-json.json")
        val devScreen = BootstrapScreen(
            testScenarioSeed = devSeed,
            selectDefaultTestScenario = true,
            development = true,
            openSetup = true,
        )
        try {
            assertEquals(OutGameRoute.SKIRMISH_LOBBY, devScreen.appShell.currentRoute)
            assertEquals("SETUP", devScreen.currentStartupPageNameForTest)
            assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.SKIRMISH_LOBBY), devScreen.appShell.stack)

            // SETUP -> MAPS -> RESOURCES (sibling leaf replace) -> Back to SETUP -> Esc to HOME (never loops back to MAPS/SETTINGS)
            devScreen.navigateStartupPageForTest("MAPS")
            assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.SKIRMISH_LOBBY, OutGameRoute.MAP_PICKER), devScreen.appShell.stack)
            assertEquals("MAPS", devScreen.currentStartupPageNameForTest)

            devScreen.navigateStartupPageForTest("CONTENT")
            assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.SKIRMISH_LOBBY, OutGameRoute.SETTINGS), devScreen.appShell.stack)
            assertEquals("CONTENT", devScreen.currentStartupPageNameForTest)

            assertEquals(BackKeyAction.POPPED_ROUTE, devScreen.appShell.handleBackKey(requireMainMenuExitConfirm = false))
            assertEquals(listOf(OutGameRoute.MAIN_MENU, OutGameRoute.SKIRMISH_LOBBY), devScreen.appShell.stack)
            assertEquals("SETUP", devScreen.currentStartupPageNameForTest)

            assertEquals(BackKeyAction.POPPED_ROUTE, devScreen.appShell.handleBackKey(requireMainMenuExitConfirm = false))
            assertEquals(OutGameRoute.MAIN_MENU, devScreen.appShell.currentRoute)
            assertEquals("HOME", devScreen.currentStartupPageNameForTest)

            // SETUP -> DEVELOPMENT -> beginLoading -> leaveMatch/cancel restores DEVELOPMENT on both AppShell and startupPage!
            devScreen.navigateStartupPageForTest("SETUP")
            devScreen.navigateStartupPageForTest("DEVELOPMENT")
            assertEquals(OutGameRoute.TEST_AND_SANDBOX, devScreen.appShell.currentRoute)
            assertEquals("DEVELOPMENT", devScreen.currentStartupPageNameForTest)

            devScreen.appShell.beginLoading()
            assertEquals(OutGameRoute.LOADING, devScreen.appShell.currentRoute)
            devScreen.cancelOrLeaveMatchForTest()
            assertEquals(OutGameRoute.TEST_AND_SANDBOX, devScreen.appShell.currentRoute)
            assertEquals("DEVELOPMENT", devScreen.currentStartupPageNameForTest)
        } finally {
            devScreen.dispose()
        }
    }

    @Test
    fun userSettingsStoreDefaultsToZhPersistsAtomicallyFallsBackOnCorruptionAndHonorsCliWithoutPollution() {
        val dir = Files.createTempDirectory("ui-p004-settings-")
        try {
            val file = dir.resolve("settings.json")
            val store = UserSettingsStore(file)
            assertFalse(Files.exists(file), "Reading missing settings.json must not eagerly write to disk")
            assertEquals(UiLanguage.ZH, store.effective.language)
            assertEquals(BuiltInUiThemeId.DARK, store.effective.themeId)
            assertEquals(listOf("reforge:base"), store.effective.enabledResourcePacks)
            assertEquals(listOf("phase-seven.json"), store.effective.enabledContentPacks)
            assertNull(store.lastLoadWarning)

            // Save custom settings & ensure base packs remain pinned at the bottom
            store.update {
                it.copy(
                    language = UiLanguage.EN,
                    masterVolume = 0.75f,
                    sfxVolume = 0.50f,
                    muted = true,
                    uiScaleMultiplier = 1.25f,
                    touchCameraJoystick = false,
                    cameraPanSensitivity = 1.4f,
                    cameraZoomSensitivity = 0.8f,
                    windowEdgeScroll = false,
                    themeId = BuiltInUiThemeId.BONE,
                    enabledResourcePacks = listOf("reforge:base", "user:hd-terrain", "user:cobalt"),
                    enabledContentPacks = listOf("phase-seven.json", "mod:mech-expansion"),
                )
            }
            assertTrue(Files.isRegularFile(file))
            val reloaded = UserSettingsStore(file)
            assertEquals(UiLanguage.EN, reloaded.effective.language)
            assertEquals(BuiltInUiThemeId.BONE, reloaded.effective.themeId)
            assertEquals(0.75f, reloaded.effective.masterVolume, 1e-4f)
            assertEquals(1.25f, reloaded.effective.uiScaleMultiplier, 1e-4f)
            assertEquals(listOf("user:hd-terrain", "user:cobalt", "reforge:base"), reloaded.effective.enabledResourcePacks)
            assertEquals(listOf("mod:mech-expansion", "phase-seven.json"), reloaded.effective.enabledContentPacks)

            // CLI overrides take precedence in effective view WITHOUT polluting persisted settings on disk!
            val withCli = UserSettingsStore(
                file = file,
                cliOverrides = UserSettingsCliOverrides(
                    language = UiLanguage.ZH,
                    themeId = BuiltInUiThemeId.WHITE,
                    uiScaleMultiplier = 0.85f,
                ),
            )
            assertEquals(UiLanguage.ZH, withCli.effective.language)
            assertEquals(BuiltInUiThemeId.WHITE, withCli.effective.themeId)
            assertEquals(0.85f, withCli.effective.uiScaleMultiplier, 1e-4f)
            withCli.update { it.copy(masterVolume = 0.40f) }

            val diskAfterCli = UserSettingsStore(file)
            assertEquals(0.40f, diskAfterCli.effective.masterVolume, 1e-4f)
            assertEquals(UiLanguage.EN, diskAfterCli.effective.language, "CLI --lang must never overwrite saved language")
            assertEquals(BuiltInUiThemeId.BONE, diskAfterCli.effective.themeId, "CLI --theme must never overwrite saved theme")
            assertEquals(1.25f, diskAfterCli.effective.uiScaleMultiplier, 1e-4f, "CLI scale must never overwrite saved scale")

            // Corrupted JSON & disallowed 'nasa' theme safely fall back to defaults with warning
            for (badPayload in listOf(
                "{corrupted-json",
                """{"schemaVersion": 999}""",
                """{"schemaVersion": 1, "themeId": "nasa"}""",
            )) {
                Files.writeString(file, badPayload, Charsets.UTF_8)
                val recovered = UserSettingsStore(file)
                assertEquals(UiLanguage.ZH, recovered.effective.language)
                assertEquals(BuiltInUiThemeId.DARK, recovered.effective.themeId)
                assertNotNull(recovered.lastLoadWarning)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun builtInThreeThemesLoadCleanlyExcludeNasaAndComposeResourcePackOverridesOverEachBase() {
        assertEquals(listOf("dark", "white", "bone"), BuiltInUiThemeId.entries.map { it.code })
        assertNull(UiTheme::class.java.getResourceAsStream("/ui/themes/nasa.json"), "NASA theme must NOT be shipped")
        assertEquals(BuiltInUiThemeId.DARK, BuiltInUiThemeId.fromCodeOrDefault("nasa"))

        val dark = UiTheme.DEFAULT
        val white = UiTheme.WHITE
        val bone = UiTheme.BONE

        assertEquals("reforge:constructivist-default", dark.id)
        assertEquals("reforge:polar-white", white.id)
        assertEquals("reforge:constructivist-bone", bone.id)

        // Dark-only diagonal hatched extruded selected text feature gate (UI Supplement §3)
        assertTrue(dark.features.mainMenuExtrudeText)
        assertFalse(white.features.mainMenuExtrudeText)
        assertFalse(bone.features.mainMenuExtrudeText)

        // Bone theme uses de-yellowed warm stone white (#F2F1EC / #E5E4DF) rather than yellow parchment
        assertEquals(0xF2 / 255f, bone.colors.solid.r, 1e-3f)
        assertEquals(0xF1 / 255f, bone.colors.solid.g, 1e-3f)
        assertEquals(0xEC / 255f, bone.colors.solid.b, 1e-3f)
        assertEquals(0xE5 / 255f, bone.outGame.railSolid.r, 1e-3f)
        assertEquals(0xE4 / 255f, bone.outGame.railSolid.g, 1e-3f)
        assertEquals(0xDF / 255f, bone.outGame.railSolid.b, 1e-3f)

        // 10-player & 10-team palettes across all 3 themes
        for (theme in listOf(dark, white, bone)) {
            assertEquals(10, theme.palettes.players.size, "10 player colors required in ${theme.id}")
            assertEquals(10, theme.palettes.teams.size, "10 team colors required in ${theme.id}")
        }

        // Resource-pack partial ui/theme.json overrides compose cleanly on top of DARK, WHITE, and BONE
        val patchBytes = """
            {
              "schemaVersion": 1,
              "id": "test:custom-accent",
              "outGame": {
                "primaryBg": "#1F7AE0"
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val patch = UiThemePatch.read(patchBytes, "test-pack/ui/theme.json", requireFull = false)
        for (base in listOf(dark, white, bone)) {
            val composed = UiThemeComposer.compose(base = base, overridesHighToLow = listOf(patch))
            assertEquals(0x1F / 255f, composed.outGame.primaryBg.r, 1e-3f)
            assertEquals(base.outGame.railSolid, composed.outGame.railSolid, "Unpatched outGame tokens must stay from ${base.id}")
            assertEquals(base.features.mainMenuExtrudeText, composed.features.mainMenuExtrudeText)
        }
    }

    @Test
    fun localizedMetadataResolvesDisplayTextZhWithCleanFallbackForResourceAndContentPacks() {
        val basePack = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER)
        assertEquals("内置基础资源包", LocalizedMetadata.resourcePackName(basePack, UiLanguage.ZH))
        assertEquals("Public placeholder pack", LocalizedMetadata.resourcePackName(basePack, UiLanguage.EN))

        val unlocalizedPack = ResourcePackMetadata.read(
            """
            {
              "formatVersion": 1,
              "requiresResourceFormat": 1,
              "id": "user:english-only",
              "name": "Industrial Tank Retexture",
              "description": "Custom steel hulls.",
              "icon": "reforge:units/c_tank/body"
            }
            """.trimIndent().toByteArray(Charsets.UTF_8),
            "pack.json",
        )
        assertEquals("Industrial Tank Retexture", LocalizedMetadata.resourcePackName(unlocalizedPack, UiLanguage.ZH))
        assertEquals("Custom steel hulls.", LocalizedMetadata.resourcePackDescription(unlocalizedPack, UiLanguage.ZH))

        // Mod directory with RW mod-info.txt ([mod] displayText_zh & description_zh)
        val dir = Files.createTempDirectory("ui-p004-mod-i18n-")
        try {
            val iniModDir = Files.createDirectories(dir.resolve("ini-mod"))
            Files.writeString(
                iniModDir.resolve("mod-info.txt"),
                """
                [mod]
                title: Heavy Artillery Expansion
                displayText_zh: 重型火炮扩展包
                description: Adds long-range siege cannons.
                description_zh: 新增远程攻城火炮与弹药补给单位。
                """.trimIndent(),
                Charsets.UTF_8,
            )
            assertEquals("重型火炮扩展包", LocalizedMetadata.contentPackName("mod:artillery", "Heavy Artillery Expansion", iniModDir, UiLanguage.ZH))
            assertEquals("Heavy Artillery Expansion", LocalizedMetadata.contentPackName("mod:artillery", "Heavy Artillery Expansion", iniModDir, UiLanguage.EN))
            assertEquals("新增远程攻城火炮与弹药补给单位。", LocalizedMetadata.contentPackDescription("mod:artillery", "", iniModDir, UiLanguage.ZH))

            // Built-in phase-seven.json localized fallback without modifying Engine/core pack.json schema
            val emptyDir = Files.createDirectories(dir.resolve("phase-seven"))
            assertEquals("现行综合试玩包", LocalizedMetadata.contentPackName("phase-seven.json", "Phase Seven Sandbox", emptyDir, UiLanguage.ZH))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledNotoSansScFontsCoverEveryCharacterInUiStringsAndLocalizedMetadataWithZeroMissingGlyphs() {
        val allStrings = buildList {
            for (key in UiStrings.keysZh()) {
                add(UiStrings.get(key, UiLanguage.ZH))
                add(UiStrings.get(key, UiLanguage.EN))
            }
            add(UiStrings.preloadCharacters())
            BuiltInResourcePacks.all.forEach { meta ->
                add(LocalizedMetadata.resourcePackName(meta, UiLanguage.ZH))
                add(LocalizedMetadata.resourcePackDescription(meta, UiLanguage.ZH))
            }
            for (packId in listOf("phase-seven.json", "phase-six-a.json")) {
                add(LocalizedMetadata.contentPackName(packId, packId, null, UiLanguage.ZH))
                add(LocalizedMetadata.contentPackDescription(packId, "", null, UiLanguage.ZH))
                add(LocalizedMetadata.contentPackName(packId, packId, null, UiLanguage.EN))
                add(LocalizedMetadata.contentPackDescription(packId, "", null, UiLanguage.EN))
            }
        }
        val codePoints = LinkedHashSet<Int>()
        for (text in allStrings) {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (!Character.isISOControl(cp) && !Character.isWhitespace(cp)) {
                    codePoints.add(cp)
                }
                i += Character.charCount(cp)
            }
        }
        assertTrue(codePoints.size > 200, "Expected rich bilingual character set, got ${codePoints.size}")

        for (fontResource in listOf("/" + UiFontSet.REGULAR_FONT_PATH, "/" + UiFontSet.BOLD_FONT_PATH)) {
            val bytes = requireNotNull(UiFontSet::class.java.getResourceAsStream(fontResource)) {
                "Missing font resource: $fontResource"
            }.use { it.readBytes() }
            val covered = parseOpenTypeCmapCoveredCodePoints(bytes)
            val missing = codePoints.filterNot(covered::contains)
            assertEquals(
                emptyList(),
                missing.map { "U+%04X('%s')".format(it, String(Character.toChars(it))) },
                "Font $fontResource has missing glyph(s) (would render tofu boxes)",
            )
        }
    }

    /**
     * Parses OpenType/TrueType `cmap` subtables (Format 4 BMP & Format 12 full Unicode) directly from font bytes
     * to verify real glyph coverage (`glyphId != 0`) in headless JVM unit tests.
     */
    private fun parseOpenTypeCmapCoveredCodePoints(fontBytes: ByteArray): Set<Int> {
        fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, off: Int): Long =
            ((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF)

        val numTables = u16(fontBytes, 4)
        var cmapOffset = -1
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val tag = String(fontBytes, rec, 4, Charsets.US_ASCII)
            if (tag == "cmap") {
                cmapOffset = u32(fontBytes, rec + 8).toInt()
                break
            }
        }
        require(cmapOffset >= 0) { "Missing cmap table in OpenType font" }

        val numSubtables = u16(fontBytes, cmapOffset + 2)
        val covered = HashSet<Int>(32768)
        for (i in 0 until numSubtables) {
            val subRec = cmapOffset + 4 + i * 8
            val subOff = cmapOffset + u32(fontBytes, subRec + 4).toInt()
            when (u16(fontBytes, subOff)) {
                4 -> {
                    val segCount = u16(fontBytes, subOff + 6) / 2
                    val endCodeBase = subOff + 14
                    val startCodeBase = endCodeBase + segCount * 2 + 2
                    val idDeltaBase = startCodeBase + segCount * 2
                    val idRangeOffsetBase = idDeltaBase + segCount * 2
                    for (s in 0 until segCount) {
                        val endCode = u16(fontBytes, endCodeBase + s * 2)
                        val startCode = u16(fontBytes, startCodeBase + s * 2)
                        if (startCode == 0xFFFF && endCode == 0xFFFF) continue
                        val idDelta = fontBytes[idDeltaBase + s * 2].toInt().shl(8).or(fontBytes[idDeltaBase + s * 2 + 1].toInt() and 0xFF).toShort().toInt()
                        val idRangeOffsetPos = idRangeOffsetBase + s * 2
                        val idRangeOffset = u16(fontBytes, idRangeOffsetPos)
                        for (cp in startCode..endCode) {
                            val glyphId = if (idRangeOffset == 0) {
                                (cp + idDelta) and 0xFFFF
                            } else {
                                val glyphIndexAddr = idRangeOffsetPos + idRangeOffset + (cp - startCode) * 2
                                val raw = u16(fontBytes, glyphIndexAddr)
                                if (raw == 0) 0 else (raw + idDelta) and 0xFFFF
                            }
                            if (glyphId != 0) covered.add(cp)
                        }
                    }
                }
                12 -> {
                    val numGroups = u32(fontBytes, subOff + 12).toInt()
                    var groupOff = subOff + 16
                    for (g in 0 until numGroups) {
                        val startChar = u32(fontBytes, groupOff).toInt()
                        val endChar = u32(fontBytes, groupOff + 4).toInt()
                        val startGlyph = u32(fontBytes, groupOff + 8).toInt()
                        for (cp in startChar..endChar) {
                            if (startGlyph + (cp - startChar) != 0) covered.add(cp)
                        }
                        groupOff += 12
                    }
                }
            }
        }
        return covered
    }
}
