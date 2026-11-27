package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MapTriggerInfo
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiP004SkirmishLobbyTest {

    @AfterTest
    fun restoreLanguage() {
        UiStrings.currentLanguage = UiLanguage.DEFAULT
    }

    private fun headlessFont(): BitmapFont =
        BitmapFont(
            BitmapFont.BitmapFontData(),
            com.badlogic.gdx.utils.Array.with(com.badlogic.gdx.graphics.g2d.TextureRegion()),
            true,
        )

    private fun dummyMapWithWinCondition(
        triggerWin: String? = null,
        mapPropWin: String? = null,
    ): MapDefinition {
        val base = OutGameMapCatalog.defaultEntry().mapDefinition
        return MapDefinition(
            widthTiles = base.widthTiles,
            heightTiles = base.heightTiles,
            tileWidth = base.tileWidth,
            tileHeight = base.tileHeight,
            tileSets = emptyList(),
            groundGids = base.groundGids,
            blocking = base.blocking,
            triggerInfo = MapTriggerInfo(
                properties = if (triggerWin != null) mapOf("winCondition" to triggerWin) else emptyMap(),
            ),
            mapProperties = if (mapPropWin != null) mapOf("winCondition" to mapPropWin) else emptyMap(),
        )
    }

    @Test
    fun `winCondition maps accurately to SkirmishWinMode with requiredObjectives fallback notice`() {
        assertEquals(
            SkirmishWinMode.MappingResult(SkirmishWinMode.SANDBOX, null),
            SkirmishWinMode.mapFromMapDefinition(dummyMapWithWinCondition(triggerWin = "none")),
        )
        assertEquals(
            SkirmishWinMode.MappingResult(SkirmishWinMode.ELIMINATION, null),
            SkirmishWinMode.mapFromMapDefinition(dummyMapWithWinCondition(triggerWin = "allUnitsAndBuildings")),
        )
        for (standardRaw in listOf(null, "", "allBuildings", "mainBuildings", "mainBuilings", "commandCenter")) {
            assertEquals(
                SkirmishWinMode.MappingResult(SkirmishWinMode.STANDARD, null),
                SkirmishWinMode.mapFromMapDefinition(dummyMapWithWinCondition(triggerWin = standardRaw)),
                "Expected STANDARD for winCondition=$standardRaw",
            )
        }
        val objMapped = SkirmishWinMode.mapFromMapDefinition(dummyMapWithWinCondition(mapPropWin = "requiredObjectives"))
        assertEquals(SkirmishWinMode.STANDARD, objMapped.mode)
        assertEquals("lobby.win.objectivesFallbackNotice", objMapped.fallbackNoticeKey)

        // Manual winMode override clears fallback notice and survives re-selecting the exact same map
        val objEntry = MapPickerEntry(
            id = "custom_obj",
            slug = "custom_obj",
            titleKeyOrRaw = "Objective Map",
            isTranslationKey = false,
            tmxPath = null,
            mapDefinition = dummyMapWithWinCondition(triggerWin = "requiredObjectives"),
            spawnPointsNormalized = listOf(0.2f to 0.2f, 0.8f to 0.8f),
        )
        val objState = SkirmishLobbyState(initialMap = objEntry)
        assertEquals("lobby.win.objectivesFallbackNotice", objState.winConditionFallbackNoticeKey)
        objState.updateWinMode(SkirmishWinMode.ELIMINATION)
        assertEquals(SkirmishWinMode.ELIMINATION, objState.winMode)
        assertNull(objState.winConditionFallbackNoticeKey)
        objState.selectMapEntry(objEntry)
        assertEquals(SkirmishWinMode.ELIMINATION, objState.winMode, "Re-selecting identical map must preserve manual winMode")
    }

    @Test
    fun `seat table supports 1 to 10 seats, protects host, hides add row at 10, and swaps colors and spawns`() {
        val ringEntry = OutGameMapCatalog.findByIdOrPath("ring_atoll")
        assertNotNull(ringEntry)
        val state = SkirmishLobbyState(initialMap = ringEntry)
        assertEquals(4, state.seats.size)
        assertEquals(MatchSeatKind.LOCAL_HUMAN, state.seats.first().kind)
        assertFalse(state.removeOrKickSeat(0), "Local host seat must never be removable")

        // Add 1 remote human seat + AI seats up to 10
        assertNotNull(state.addRemoteHumanSeat())
        while (state.canAddSeat) {
            assertNotNull(state.addAiSeat())
        }
        assertEquals(10, state.seats.size)
        assertFalse(state.canAddSeat)
        assertNull(state.addAiSeat(), "11th seat must be rejected at 10-seat cap")

        // Verify all 10 colors and all 10 spawns are distinct
        assertEquals(10, state.seats.map { it.colorIndex }.distinct().size)
        assertEquals(10, state.seats.map { it.spawnPoint }.distinct().size)

        // Color swap on collision & 10-click full cycle traversal without deadlock
        val visitedColors = mutableSetOf<Int>()
        repeat(10) {
            state.cycleSeatColor(0)
            visitedColors += state.seats[0].colorIndex
            assertEquals(10, state.seats.map { it.colorIndex }.distinct().size)
        }
        assertEquals((0..9).toSet(), visitedColors, "10 consecutive cycleSeatColor clicks must visit all 10 colors")

        val seat0OrigColor = state.seats[0].colorIndex
        val seat9OrigColor = state.seats[9].colorIndex
        state.assignSeatColor(0, seat9OrigColor)
        assertEquals(seat9OrigColor, state.seats[0].colorIndex)
        assertEquals(seat0OrigColor, state.seats[9].colorIndex)
        assertEquals(10, state.seats.map { it.colorIndex }.distinct().size)

        // Spawn swap on collision: seat 0 (`spawnPoint=1`) assigned seat 5's spawn
        val seat0OrigSpawn = state.seats[0].spawnPoint
        val seat5OrigSpawn = state.seats[5].spawnPoint
        state.assignSeatSpawnPoint(0, seat5OrigSpawn)
        assertEquals(seat5OrigSpawn, state.seats[0].spawnPoint)
        assertEquals(seat0OrigSpawn, state.seats[5].spawnPoint)
        assertEquals(10, state.seats.map { it.spawnPoint }.distinct().size)

        // FFA vs 2-Teams quick split
        state.applyFreeForAllTeams()
        assertEquals((0..9).toList(), state.seats.map { it.teamIndex })
        state.applyTwoTeamsSplit()
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 1, 1, 1, 1), state.seats.map { it.teamIndex })

        // Scene2D MatchSlotTableView hides `+ 添加 AI` at 10 seats and distinguishes `踢出` vs `移除`
        val skin = Skin()
        val font = headlessFont()
        try {
            val tableView = MatchSlotTableView(
                skin = skin,
                lobbyState = state,
                themeProvider = { UiTheme.DEFAULT },
                iconsProvider = { null },
                fontsProvider = { null },
                fallbackFontProvider = { font },
                onStateChanged = {},
            )
            tableView.setBounds(0f, 0f, 540f, 440f)
            assertFalse(tableView.addSeatButton.isVisible, "Add-AI button must hide when 10 seats are full")

            val actionsAt10 = tableView.drawnActions("lobby").associateBy { it.actionId }
            assertFalse("lobby-seat-1-remove" in actionsAt10, "Host row must not expose remove/kick action")
            assertEquals("踢出", actionsAt10.getValue("lobby-seat-5-remove").text)
            assertEquals("移除", actionsAt10.getValue("lobby-seat-6-remove").text)

            // Removing one AI seat re-exposes the `+ 添加 AI` button
            assertTrue(state.removeOrKickSeat(5))
            tableView.syncRows(forceRebuild = true)
            assertEquals(9, state.seats.size)
            assertTrue(tableView.addSeatButton.isVisible)

            // 4P map expanded to 6 seats then removing middle seat reconciles spawns within 1..5
            val riverEntry = OutGameMapCatalog.defaultEntry()
            assertEquals(4, riverEntry.maxPlayers)
            val riverState = SkirmishLobbyState(initialMap = riverEntry)
            assertNotNull(riverState.addAiSeat())
            assertNotNull(riverState.addAiSeat())
            assertEquals(6, riverState.availableSpawnCount)
            assertEquals((1..6).toSet(), riverState.seats.map { it.spawnPoint }.toSet())
            assertTrue(riverState.removeOrKickSeat(1))
            assertEquals(5, riverState.availableSpawnCount)
            assertEquals((1..5).toSet(), riverState.seats.map { it.spawnPoint }.toSet())
            val launched5 = riverState.buildLaunchConfig()
            assertEquals(5, launched5.maxSpawns)
            assertTrue(launched5.seats.all { it.spawnPoint in 1..5 })

            // Defensive copy immutability on SkirmishLaunchConfig.seats
            val mutableInputSeats = riverState.seats.toMutableList()
            val configFromMutable = SkirmishLaunchConfig(
                mapId = riverEntry.id,
                mapSlug = riverEntry.slug,
                mapDisplayName = "River",
                tmxPath = riverEntry.tmxPath,
                maxSpawns = 5,
                winMode = SkirmishWinMode.STANDARD,
                fogMode = SkirmishFogMode.MEMORY,
                startingCredits = SkirmishStartingCredits.CREDITS_4000,
                moreRules = MoreMatchRules.DEFAULT,
                seats = mutableInputSeats,
            )
            mutableInputSeats.clear()
            assertEquals(5, configFromMutable.seats.size, "Mutating caller MutableList must not mutate SkirmishLaunchConfig.seats")
        } finally {
            font.dispose()
            skin.dispose()
        }
    }

    @Test
    fun `more settings modal tracks non-default indicator dot, restores defaults, and round-trips via AppShell`() {
        val shell = AppShell(initialRoute = OutGameRoute.SKIRMISH_LOBBY)
        val state = SkirmishLobbyState()
        val skin = Skin()
        val font = headlessFont()
        try {
            val lobbyScreen = SkirmishLobbyScreen(
                skin = skin,
                appShell = shell,
                lobbyState = state,
                themeProvider = { UiTheme.WHITE },
                iconsProvider = { null },
                fontsProvider = { null },
                fallbackFontProvider = { font },
                onNavigateBack = { shell.handleBackKey(requireMainMenuExitConfirm = false) },
                onOpenMapPicker = { _, _ -> },
                onLoadCustomTmxPath = { null },
                onStartMatch = {},
            )
            lobbyScreen.updateLayout(1280f, 720f)
            assertFalse(state.moreRules.hasNonDefault)
            assertEquals(false, lobbyScreen.moreSettingsButton.indicatorDotProvider?.invoke())

            lobbyScreen.moreSettingsModal.open()
            assertTrue(lobbyScreen.moreSettingsModal.isVisible)
            assertEquals(AppShell.MODAL_MORE_SETTINGS, shell.activeModalId)

            lobbyScreen.moreSettingsModal.incomeSegmented.select(SkirmishIncomeMultiplier.X2_0)
            lobbyScreen.moreSettingsModal.noNukesSegmented.select(true)
            assertTrue(state.moreRules.hasNonDefault)
            assertEquals(true, lobbyScreen.moreSettingsButton.indicatorDotProvider?.invoke())

            // Back key closes modal first without leaving SKIRMISH_LOBBY
            assertEquals(BackKeyAction.CLOSED_MODAL, shell.handleBackKey(requireMainMenuExitConfirm = false))
            assertFalse(lobbyScreen.moreSettingsModal.isVisible)
            assertEquals(OutGameRoute.SKIRMISH_LOBBY, shell.currentRoute)

            // Reopen and restore defaults clears indicator dot
            lobbyScreen.moreSettingsModal.open()
            lobbyScreen.moreSettingsModal.restoreDefaultsButton.triggerClick()
            assertFalse(state.moreRules.hasNonDefault)
            assertEquals(MoreMatchRules.DEFAULT, state.moreRules)
            lobbyScreen.moreSettingsModal.confirmButton.triggerClick()
            assertFalse(lobbyScreen.moreSettingsModal.isVisible)
        } finally {
            font.dispose()
            skin.dispose()
        }
    }

    @Test
    fun `BootstrapScreen preserves lobby configuration across MAIN_MENU and match returnToLobby`() {
        val screen = BootstrapScreen(openSetup = false)
        assertEquals(OutGameRoute.MAIN_MENU, screen.appShell.currentRoute)

        screen.navigateStartupPageForTest("SETUP")
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, screen.appShell.currentRoute)

        val ring = requireNotNull(OutGameMapCatalog.findByIdOrPath("ring_atoll"))
        screen.skirmishLobbyState.selectMapEntry(ring)
        screen.skirmishLobbyState.fogMode = SkirmishFogMode.HARDCORE
        screen.skirmishLobbyState.startingCredits = SkirmishStartingCredits.CREDITS_10000
        screen.skirmishLobbyState.moreRules = MoreMatchRules(
            incomeMultiplier = SkirmishIncomeMultiplier.X1_5,
            sharedVision = false,
            spawnAssignment = SkirmishSpawnAssignment.RANDOM,
            startingUnits = SkirmishStartingUnits.STANDARD_BASE,
            noNukes = true,
        )
        screen.skirmishLobbyState.addAiSeat(SkirmishAiDifficulty.HARD)
        val frozenBefore = screen.skirmishLobbyState.buildLaunchConfig()

        // Navigate back to MAIN_MENU and re-enter SKIRMISH_LOBBY: configuration is 100% preserved
        assertEquals(BackKeyAction.POPPED_ROUTE, screen.appShell.handleBackKey(requireMainMenuExitConfirm = false))
        assertEquals(OutGameRoute.MAIN_MENU, screen.appShell.currentRoute)
        screen.navigateStartupPageForTest("SETUP")
        assertEquals(frozenBefore, screen.skirmishLobbyState.buildLaunchConfig())

        // Simulate loading -> match -> returnToLobby(): configuration remains intact
        screen.recordSkirmishLaunchForTest(frozenBefore)
        screen.appShell.beginLoading(OutGameRoute.SKIRMISH_LOBBY)
        screen.appShell.enterMatch()
        screen.cancelOrLeaveMatchForTest()
        assertEquals(OutGameRoute.SKIRMISH_LOBBY, screen.appShell.currentRoute)
        assertEquals(frozenBefore, screen.skirmishLobbyState.buildLaunchConfig())
        assertEquals(frozenBefore, screen.lastSkirmishLaunchConfig)
        assertEquals(SandboxMode.HARDCORE, screen.currentLaunchOptionsForTest.mode)
        assertFalse(screen.currentLaunchOptionsForTest.shareAlliedVision)
        assertFalse(screen.currentLaunchOptionsForTest.nukesEnabled)
    }

    @Test
    fun `single-piece chevron rasterizer has seamless filled interior and symmetric tapering`() {
        val w = OutGameChevronSpec.DEFAULT_WIDTH_PX
        val h = OutGameChevronSpec.DEFAULT_HEIGHT_PX
        val buf = OutGameChevronSpec.buildDownTriangleRgbaBuffer(w, h)
        assertEquals(w * h, buf.size)

        val midX = w / 2
        val centerAlphas = (0 until h).map { py -> buf[py * w + midX] and 0xFF }
        val firstLitRow = centerAlphas.indexOfFirst { it >= 128 }
        val lastLitRow = centerAlphas.indexOfLast { it >= 128 }
        assertTrue(firstLitRow in 2..6, "Expected top edge near top of buffer, got $firstLitRow")
        assertTrue(lastLitRow in (h - 7)..(h - 2), "Expected apex near bottom of buffer, got $lastLitRow")

        // Crucial seam-free invariant: every single center pixel between top base and apex is solid opaque!
        for (py in (firstLitRow + 1) until (lastLitRow - 1)) {
            val leftMidA = buf[py * w + (midX - 1)] and 0xFF
            val rightMidA = buf[py * w + midX] and 0xFF
            assertEquals(255, leftMidA, "Center-left seam crack detected at row $py (alpha=$leftMidA)")
            assertEquals(255, rightMidA, "Center-right seam crack detected at row $py (alpha=$rightMidA)")
            // Exact mirror symmetry across x = w * 0.5f (`px` <-> `w - 1 - px`)
            for (px in 0 until midX) {
                val leftA = buf[py * w + px] and 0xFF
                val rightA = buf[py * w + (w - 1 - px)] and 0xFF
                assertEquals(leftA, rightA, "Asymmetric triangle edge at row=$py px=$px")
            }
        }

        val topSpan = (0 until w).count { px -> (buf[(firstLitRow + 1) * w + px] and 0xFF) >= 128 }
        val bottomSpan = (0 until w).count { px -> (buf[(lastLitRow - 1) * w + px] and 0xFF) >= 128 }
        assertTrue(topSpan > bottomSpan * 2, "Expected downward tapering triangle (topSpan=$topSpan, bottomSpan=$bottomSpan)")
    }

    @Test
    fun `new UI-P004-003 lobby and control sources contain zero hardcoded colors or AWT imports`() {
        val repoRoot = Paths.get(System.getProperty("repo.root", "..")).toAbsolutePath().normalize()
        val clientSrc = sequenceOf(
            repoRoot.resolve("Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client"),
            Paths.get("src/main/kotlin/com/rustedwarfare/reforge/client").toAbsolutePath().normalize(),
        ).firstOrNull { Files.isDirectory(it) }
        assertNotNull(clientSrc, "Could not locate Engine/client Kotlin source directory")

        val filesToCheck = listOf(
            "OutGameControls.kt",
            "SkirmishLaunchConfig.kt",
            "MatchSlotTableView.kt",
            "MoreMatchSettingsModal.kt",
            "SkirmishLobbyScreen.kt",
        )
        val hexRegex = Regex("""#[0-9A-Fa-f]{6,8}""")
        val colorCtorRegex = Regex("""Color\s*\(\s*0x|Color\.valueOf""")
        for (name in filesToCheck) {
            val path = clientSrc.resolve(name)
            assertTrue(Files.isRegularFile(path), "Missing expected source file: $path")
            val content = Files.readString(path)
            val nonCommentLines = content.lineSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .joinToString("\n")
            assertFalse(hexRegex.containsMatchIn(nonCommentLines), "Hardcoded hex literal found in $name")
            assertFalse(colorCtorRegex.containsMatchIn(nonCommentLines), "Hardcoded Color literal found in $name")
            assertFalse(nonCommentLines.contains("import java.awt"), "Forbidden java.awt import found in $name")
            assertFalse(nonCommentLines.contains("UiTheme.DEFAULT"), "Static UiTheme.DEFAULT fallback found in $name")
        }
    }
}
