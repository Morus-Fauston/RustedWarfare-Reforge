package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.Array
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class UiP004SandboxAndLoadingTest {

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

    private fun findPublicScenarioSeed(fileName: String): Path? {
        val candidates = listOf(
            Paths.get("SimulationTest/public/scenarios", fileName),
            Paths.get("../SimulationTest/public/scenarios", fileName),
            Paths.get("../../SimulationTest/public/scenarios", fileName),
        )
        return candidates.map { it.toAbsolutePath().normalize() }.firstOrNull { Files.isRegularFile(it) }
    }

    @Test
    fun `non-development build hides TEST_SCENES toggle and locks Free Sandbox defaults`() {
        val freeState = SkirmishLobbyState(freeSandboxMode = true)
        assertEquals(SkirmishWinMode.SANDBOX, freeState.winMode)
        assertEquals(SkirmishFogMode.NONE, freeState.fogMode)
        assertEquals(SkirmishStartingCredits.INFINITE, freeState.startingCredits)
        assertEquals(2, freeState.seats.size)
        assertEquals("你", freeState.seats[0].displayName(UiLanguage.ZH))
        assertEquals("沙盒靶机", freeState.seats[1].displayName(UiLanguage.ZH))
        assertEquals(SkirmishAiDifficulty.IDLE, freeState.seats[1].aiDifficulty)

        val screen = TestAndSandboxScreen(
            skin = skin,
            freeSandboxState = freeState,
            testScenes = TestScenarioMenu(),
            development = false,
            themeProvider = UiThemeProvider { UiTheme.DEFAULT },
            iconsProvider = { null },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            onNavigateBack = {},
            onOpenMapPicker = { _, _ -> },
            onLoadCustomTmxPath = { null },
            onEnterFreeSandbox = {},
            onLoadTestScene = {},
        )
        screen.isVisible = true
        screen.updateLayout(1920f, 1080f)

        assertFalse(screen.modeSegmented.isVisible, "Mode toggle must be hidden when development == false")
        assertEquals(TestSandboxMode.FREE_SANDBOX, screen.currentMode)

        // Attempting to force TEST_SCENES in non-development mode remains safely locked to FREE_SANDBOX
        screen.selectMode(TestSandboxMode.TEST_SCENES)
        assertEquals(TestSandboxMode.FREE_SANDBOX, screen.currentMode)
        assertTrue(screen.selectMapButton.isVisible)
        assertTrue(screen.slotTableView.isVisible)
        assertFalse(screen.cameraControlSegmented.isVisible)

        val actionIds = screen.drawnActions().map { it.actionId }.toSet()
        assertFalse("test-scenes-tab" in actionIds)
        assertFalse("test-scene-use" in actionIds)
        assertTrue("start-match" in actionIds)
        assertTrue("skirmish-maps" in actionIds)

        screen.close()
    }

    @Test
    fun `development TestAndSandboxScreen switches modes and preserves 100 percent TestScenarioPanel DrawnAction IDs`() {
        val primarySeed = findPublicScenarioSeed("phase-six-a-json.json")
        val spiderSeed = findPublicScenarioSeed("phase-six-a-spider-json.json")
        val cameraSeed = findPublicScenarioSeed("phase-seven-camera.json")
        assertNotNull(primarySeed, "Expected phase-six-a-json.json in SimulationTest/public/scenarios")
        assertNotNull(spiderSeed, "Expected phase-six-a-spider-json.json in SimulationTest/public/scenarios")
        assertNotNull(cameraSeed, "Expected phase-seven-camera.json in SimulationTest/public/scenarios")

        val testScenes = TestScenarioMenu()
        var sceneMutations = 0
        var launchedFollowCamera: Boolean? = null
        var launchedFreeConfig: SkirmishLaunchConfig? = null

        val screenWithSpider = TestAndSandboxScreen(
            skin = skin,
            freeSandboxState = SkirmishLobbyState(freeSandboxMode = true),
            testScenes = testScenes,
            development = true,
            testScenarioSeed = primarySeed,
            alternateStartSeed = spiderSeed,
            cameraStartSeed = cameraSeed,
            themeProvider = UiThemeProvider { UiTheme.builtIn(BuiltInUiThemeId.BONE) },
            iconsProvider = { null },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            onNavigateBack = {},
            onOpenMapPicker = { _, _ -> },
            onLoadCustomTmxPath = { null },
            onTestSceneMutated = { sceneMutations++ },
            onEnterFreeSandbox = { launchedFreeConfig = it },
            onLoadTestScene = { follow -> launchedFollowCamera = follow },
        )
        screenWithSpider.isVisible = true
        screenWithSpider.updateLayout(1920f, 1080f)

        assertTrue(screenWithSpider.modeSegmented.isVisible)
        val freeActions = screenWithSpider.drawnActions().associateBy { it.actionId }
        assertTrue("test-scenes-tab" in freeActions)
        assertTrue("free-sandbox-tab" in freeActions)
        assertEquals("进入自由沙盒", freeActions.getValue("start-match").text)

        // Launch Free Sandbox and verify immutable contract
        screenWithSpider.fastBuildSegmented.select(true)
        screenWithSpider.primaryLaunchButton.triggerClick()
        val freeCfg = checkNotNull(launchedFreeConfig)
        assertTrue(freeCfg.isFreeSandbox)
        assertTrue(freeCfg.fastBuild)
        assertEquals(SkirmishWinMode.SANDBOX, freeCfg.winMode)
        assertEquals(SkirmishFogMode.NONE, freeCfg.fogMode)
        assertEquals(SkirmishStartingCredits.INFINITE, freeCfg.startingCredits)

        // Switch to TEST_SCENES mode
        screenWithSpider.selectMode(TestSandboxMode.TEST_SCENES)
        assertEquals(TestSandboxMode.TEST_SCENES, screenWithSpider.currentMode)
        assertFalse(screenWithSpider.selectMapButton.isVisible, "Select Map button must be hidden in TEST_SCENES mode")
        assertTrue(screenWithSpider.cameraControlSegmented.isVisible)
        assertFalse(screenWithSpider.primaryLaunchButton.enabled, "Load button disabled before selecting a seed")

        val sceneActions = screenWithSpider.drawnActions().associateBy { it.actionId }
        assertTrue("test-scene-use" in sceneActions)
        assertTrue("test-scene-spider" in sceneActions)
        assertTrue("test-scene-camera" in sceneActions)
        assertFalse("skirmish-maps" in sceneActions)
        assertEquals("载入测试场景", sceneActions.getValue("start-match").text)

        // Trigger `test-scene-spider` and verify SHA-256 locked scenario loads cleanly
        assertTrue(screenWithSpider.triggerSceneActionForTest("test-scene-spider"))
        assertEquals(1, sceneMutations)
        assertTrue(testScenes.selected)
        assertNull(testScenes.error)
        assertNotNull(testScenes.choice)
        screenWithSpider.updateLayout(1920f, 1080f)
        assertTrue(screenWithSpider.primaryLaunchButton.enabled)

        // Switch camera mode to FREE and launch test scene
        screenWithSpider.selectCameraMode(SandboxCameraMode.FREE)
        screenWithSpider.primaryLaunchButton.triggerClick()
        assertEquals(false, launchedFollowCamera)
        screenWithSpider.close()

        // Also verify `alternateStartSeed == null` branch exposes `test-scene-custom` exactly like TestScenarioPanel
        val customBranchScenes = TestScenarioMenu()
        val screenCustomBranch = TestAndSandboxScreen(
            skin = skin,
            freeSandboxState = SkirmishLobbyState(freeSandboxMode = true),
            testScenes = customBranchScenes,
            development = true,
            testScenarioSeed = primarySeed,
            alternateStartSeed = null,
            cameraStartSeed = null,
            themeProvider = UiThemeProvider { UiTheme.DEFAULT },
            iconsProvider = { null },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            onNavigateBack = {},
            onOpenMapPicker = { _, _ -> },
            onLoadCustomTmxPath = { null },
            onEnterFreeSandbox = {},
            onLoadTestScene = {},
        )
        screenCustomBranch.isVisible = true
        screenCustomBranch.selectMode(TestSandboxMode.TEST_SCENES)
        screenCustomBranch.updateLayout(1280f, 720f)

        val customBranchActions = screenCustomBranch.drawnActions().associateBy { it.actionId }
        assertTrue("test-scene-use" in customBranchActions)
        assertTrue("test-scene-custom" in customBranchActions)
        assertFalse(customBranchActions.getValue("test-scene-custom").enabled, "test-scene-custom disabled before selecting a seed")

        assertTrue(screenCustomBranch.triggerSceneActionForTest("test-scene-use"))
        assertTrue(customBranchScenes.selected)
        val afterSelectActions = screenCustomBranch.drawnActions().associateBy { it.actionId }
        assertTrue(afterSelectActions.getValue("test-scene-custom").enabled)

        assertTrue(screenCustomBranch.triggerSceneActionForTest("test-scene-custom"))
        assertFalse(customBranchScenes.selected)
        screenCustomBranch.close()
    }

    @Test
    fun `MatchLoadingScreen renders 01-05 rule summary, invariant LOADING anchor X across dots, and safe cancel back to source route`() {
        val lobbyState = SkirmishLobbyState()
        lobbyState.moreRules = lobbyState.moreRules.copy(
            incomeMultiplier = SkirmishIncomeMultiplier.X1_5,
            noNukes = true,
        )
        val cfg = lobbyState.buildLaunchConfig()
        val summary = MatchLoadingSummary.fromLaunchConfig(cfg, lobbyState.selectedMap, tipIndex = 2)

        val cellsZh = summary.ruleCells(UiLanguage.ZH)
        assertEquals(5, cellsZh.size)
        assertEquals(Triple("01", "胜负", "标准"), cellsZh[0])
        assertEquals(Triple("02", "迷雾", "记忆迷雾"), cellsZh[1])
        assertEquals(Triple("03", "资金", "$4,000"), cellsZh[2])
        assertEquals(Triple("04", "经济", "1.5×"), cellsZh[3])
        assertEquals(Triple("05", "禁核", "开"), cellsZh[4])
        assertTrue(summary.localizedTip(UiLanguage.ZH).isNotBlank())

        // Verify loading cancellation via AppShell returns cleanly to source route (both SKIRMISH_LOBBY and TEST_AND_SANDBOX)
        var cancelHookCalls = 0
        val appShell = AppShell(
            initialRoute = OutGameRoute.MAIN_MENU,
            onCancelLoading = { cancelHookCalls++ },
        )
        appShell.push(OutGameRoute.TEST_AND_SANDBOX)
        appShell.beginLoading()
        assertEquals(OutGameRoute.LOADING, appShell.currentRoute)

        val loadingScreen = MatchLoadingScreen(
            skin = skin,
            themeProvider = UiThemeProvider { UiTheme.builtIn(BuiltInUiThemeId.WHITE) },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            onCancelLoading = { appShell.handleBackKey() },
        )
        loadingScreen.isVisible = true
        loadingScreen.beginLoading(summary, initialProgress = 0.25f)
        loadingScreen.updateLayout(1920f, 1080f)

        val baseWordX = loadingScreen.loadingWordAnchorX
        val baseDotsX = loadingScreen.loadingDotsAnchorX
        assertTrue(baseWordX > 0f)
        assertTrue(baseDotsX > baseWordX)
        assertEquals(".", loadingScreen.activeLoadingDots)

        // Advance through `.` -> `..` -> `...` -> `.` and different percentages: `LOADING` X must have 0.0f drift!
        loadingScreen.stepAnimation(MatchLoadingScreen.DOT_STEP_SECONDS)
        loadingScreen.progress = 0.62f
        loadingScreen.updateLayout(1920f, 1080f)
        assertEquals("..", loadingScreen.activeLoadingDots)
        assertEquals(baseWordX, loadingScreen.loadingWordAnchorX, 0.0f)
        assertEquals(baseDotsX, loadingScreen.loadingDotsAnchorX, 0.0f)

        loadingScreen.stepAnimation(MatchLoadingScreen.DOT_STEP_SECONDS)
        loadingScreen.progress = 0.99f
        loadingScreen.updateLayout(1920f, 1080f)
        assertEquals("...", loadingScreen.activeLoadingDots)
        assertEquals(baseWordX, loadingScreen.loadingWordAnchorX, 0.0f)
        assertEquals(baseDotsX, loadingScreen.loadingDotsAnchorX, 0.0f)

        loadingScreen.stepAnimation(MatchLoadingScreen.DOT_STEP_SECONDS)
        assertEquals(".", loadingScreen.activeLoadingDots)
        assertEquals(baseWordX, loadingScreen.loadingWordAnchorX, 0.0f)

        // Cancel via `exit-match` button returns to TEST_AND_SANDBOX
        val actions = loadingScreen.drawnActions().associateBy { it.actionId }
        assertTrue("exit-match" in actions)
        loadingScreen.cancelButton.triggerClick()
        assertEquals(1, cancelHookCalls)
        assertEquals(OutGameRoute.TEST_AND_SANDBOX, appShell.currentRoute)

        loadingScreen.close()
    }

    @Test
    fun `DrawnActionLayout normalizes 1200x800 and 1920x1080 Stage coordinates into 960x640 action_layout contract`() {
        val root = com.badlogic.gdx.scenes.scene2d.Group()
        // Simulate 1200x800 viewport (`uiScale ≈ 1.13055f`, logical Stage `1061.43 x 707.62`)
        val stageW = 1061.43f
        val stageH = 707.62f
        val relayActor = com.badlogic.gdx.scenes.scene2d.Actor().apply {
            setBounds(stageW - 18f, stageH - 18f, 14f, 14f)
        }
        val topBackActor = com.badlogic.gdx.scenes.scene2d.Actor().apply {
            setBounds(24f, stageH - 46f, 88f, 32f)
        }
        val startBtnActor = com.badlogic.gdx.scenes.scene2d.Actor().apply {
            setBounds(656f, 42f, 360f, 42f)
        }
        root.addActor(relayActor)
        root.addActor(topBackActor)
        root.addActor(startBtnActor)

        val frame = ActionLayoutFrame(
            generation = 0L,
            selectionRevision = 0L,
            selectedIds = emptyList(),
            selectedTypes = emptyMap(),
            menuEpoch = 1L,
            viewport = mapOf(
                "width" to 1200,
                "height" to 800,
                "screenX" to 0,
                "screenY" to 0,
                "screenWidth" to 1200,
                "screenHeight" to 800,
            ),
            stageWidth = stageW,
            stageHeight = stageH,
        )
        val entries = listOf(
            DrawnAction("test-scenes-tab", relayActor, "测试场景"),
            DrawnAction("startup-back", topBackActor, "← 返回"),
            DrawnAction("start-match", startBtnActor, "开始对局"),
        )
        val snapshot = checkNotNull(DrawnActionLayout().afterDraw(root, frame, entries, 1234L))
        @Suppress("UNCHECKED_CAST")
        val actions = snapshot.getValue("actions") as List<Map<String, Any?>>
        assertEquals(3, actions.size)

        for (act in actions) {
            assertEquals(true, act["enabled"], "Expected ${act["actionId"]} to be enabled")
            @Suppress("UNCHECKED_CAST")
            val b = act.getValue("bounds") as Map<String, Float>
            val bx = b.getValue("x")
            val by = b.getValue("y")
            val bw = b.getValue("width")
            val bh = b.getValue("height")
            assertTrue(bx >= 0f && bw > 0f && bx + bw <= 960f, "Out-of-range X bounds for ${act["actionId"]}: $b")
            assertTrue(by >= 0f && bh > 0f && by + bh <= 640f, "Out-of-range Y bounds for ${act["actionId"]}: $b")
        }

        // Verify `action_layout.py` pixel reconstruction (`screenX + (bx + bw/2) * screenW / 960`) hits exact actor pixel center!
        @Suppress("UNCHECKED_CAST")
        val startBounds = actions.single { it["actionId"] == "start-match" }.getValue("bounds") as Map<String, Float>
        val normCenterX = startBounds.getValue("x") + startBounds.getValue("width") * 0.5f
        val normCenterY = startBounds.getValue("y") + startBounds.getValue("height") * 0.5f
        val pyClientX = kotlin.math.round(normCenterX * 1200f / 960f).toInt()
        val pyClientY = kotlin.math.round(800f - normCenterY * 800f / 640f).toInt()
        val expectedClientX = kotlin.math.round((startBtnActor.x + startBtnActor.width * 0.5f) * (1200f / stageW)).toInt()
        val expectedClientY = kotlin.math.round(800f - (startBtnActor.y + startBtnActor.height * 0.5f) * (800f / stageH)).toInt()
        assertEquals(expectedClientX, pyClientX)
        assertEquals(expectedClientY, pyClientY)
    }

    @Test
    fun `BootstrapScreen preserves NO_FOG on locked test seeds, resets lobbyExplicitMapChosen, and preserves controlled CLI inputs`() {
        val primarySeed = checkNotNull(findPublicScenarioSeed("phase-six-a-json.json"))
        val spiderSeed = checkNotNull(findPublicScenarioSeed("phase-six-a-spider-json.json"))

        // 1. Development launch (`selectDefaultTestScenario = true`): starting from Skirmish Lobby preserves `NO_FOG` & `deploymentEnabled`
        val devScreen = BootstrapScreen(
            initialRoot = primarySeed.parent.resolve("../inputs/phase-six-a-sandbox/json").normalize(),
            testScenarioSeed = primarySeed,
            selectDefaultTestScenario = true,
            alternateStartSeed = spiderSeed,
            development = true,
            initialContent = true,
            openSetup = true,
        )
        assertTrue(devScreen.prepareSkirmishLobbyLaunchOptionsForTest())
        assertEquals(SandboxMode.NO_FOG, devScreen.currentLaunchOptionsForTest.mode)
        assertFalse(devScreen.currentLaunchOptionsForTest.shareAlliedVision)
        assertTrue(devScreen.currentLaunchOptionsForTest.nukesEnabled)
        assertTrue(devScreen.currentLaunchOptionsForTest.deploymentEnabled)
        assertNull(devScreen.lastSkirmishLaunchConfig)

        // 2. User picks a skirmish map (`HARDCORE`, `noNukes = true`), then switches to `test-scene-spider` and returns to SETUP
        devScreen.markLobbyExplicitMapChosenForTest(OutGameMapCatalog.defaultEntry())
        devScreen.skirmishLobbyState.fogMode = SkirmishFogMode.HARDCORE
        devScreen.skirmishLobbyState.moreRules = devScreen.skirmishLobbyState.moreRules.copy(noNukes = true)
        assertFalse(devScreen.prepareSkirmishLobbyLaunchOptionsForTest())
        assertEquals(SandboxMode.HARDCORE, devScreen.currentLaunchOptionsForTest.mode)
        assertFalse(devScreen.currentLaunchOptionsForTest.nukesEnabled)
        assertTrue(devScreen.currentLaunchOptionsForTest.deploymentEnabled, "development=true skirmish must keep deploymentEnabled=true")

        devScreen.selectTestSceneSeedForTest(spiderSeed)
        assertTrue(devScreen.prepareSkirmishLobbyLaunchOptionsForTest(), "Choosing locked seed must reset lobbyExplicitMapChosen")
        assertEquals(SandboxMode.NO_FOG, devScreen.currentLaunchOptionsForTest.mode)
        assertTrue(devScreen.currentLaunchOptionsForTest.nukesEnabled)
        assertTrue(devScreen.currentLaunchOptionsForTest.deploymentEnabled)
        devScreen.dispose()

        // 3. Controlled CLI `--inputs` launch (`initialContent = true`, `selectDefaultTestScenario = false`, no `--map`)
        val cliInputsScreen = BootstrapScreen(
            initialRoot = primarySeed.parent.resolve("../inputs/phase-six-a-sandbox/json").normalize(),
            development = false,
            initialContent = true,
            openSetup = true,
        )
        assertFalse(cliInputsScreen.prepareSkirmishLobbyLaunchOptionsForTest())
        assertNull(cliInputsScreen.currentSelectedSkirmishMapPathForTest, "Controlled CLI --inputs launch must not hijack skirmishMapState with default river_crossing.tmx")
        assertEquals(SandboxMode.MEMORY, cliInputsScreen.currentLaunchOptionsForTest.mode)
        assertFalse(cliInputsScreen.currentLaunchOptionsForTest.deploymentEnabled)
        cliInputsScreen.dispose()
    }

    @Test
    fun `TestAndSandboxScreen and MatchLoadingScreen contain zero hardcoded colors, Color literals, UiTheme DEFAULT, or AWT imports`() {
        val clientSrc = listOf(
            Paths.get("src/main/kotlin/com/rustedwarfare/reforge/client"),
            Paths.get("Engine/client/src/main/kotlin/com/rustedwarfare/reforge/client"),
        ).first { Files.isDirectory(it) }

        val targetFiles = listOf("TestAndSandboxScreen.kt", "MatchLoadingScreen.kt")
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
