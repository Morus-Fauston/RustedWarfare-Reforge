package com.rustedwarfare.reforge.client

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.utils.Array
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UiP004PackOptionsAndRealMapsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dummyFont: BitmapFont
    private lateinit var skin: Skin

    @BeforeEach
    fun setUp() {
        UiStrings.currentLanguage = UiLanguage.ZH
        OutGameMapCatalog.configureDirectories(null, null)
        dummyFont = BitmapFont(BitmapFont.BitmapFontData(), Array.with(TextureRegion()), true)
        skin = Skin()
    }

    @AfterEach
    fun tearDown() {
        UiStrings.currentLanguage = UiLanguage.ZH
        OutGameMapCatalog.configureDirectories(null, null)
    }

    @Test
    fun `HTML Demo mapSVG blueprint tokens and 20px crisp grid render distinctly across dark, white, and bone themes`() {
        val themes = listOf(UiTheme.DEFAULT, UiTheme.WHITE, UiTheme.BONE)
        val buffers = themes.map { th ->
            OutGameMapPreviewRenderer.renderDemoMapSvgBlueprintRgbaBuffer(theme = th, cols = 80, rows = 45, cellPx = 20)
        }
        assertEquals(1600 * 900, buffers[0].size)
        assertNotEquals(buffers[0].contentHashCode(), buffers[1].contentHashCode())
        assertNotEquals(buffers[1].contentHashCode(), buffers[2].contentHashCode())

        // Verify exact HTML Demo MAP_SVG_PALS hex bindings on dark, white, and bone themes
        assertEquals(Color.valueOf("1A1E20"), UiTheme.DEFAULT.outGame.backdropWater)
        assertEquals(Color.valueOf("DBD5C6"), UiTheme.DEFAULT.outGame.backdropOre)
        assertEquals(Color.valueOf("DCE5EF"), UiTheme.WHITE.outGame.backdropWater)
        assertEquals(Color.valueOf("BDC9D7"), UiTheme.WHITE.outGame.backdropOre)
        assertEquals(Color.valueOf("CBD0D4"), UiTheme.BONE.outGame.backdropWater)
        assertEquals(Color.valueOf("A9B0B5"), UiTheme.BONE.outGame.backdropOre)

        // Verify 20px grid lines create distinct crisp pixel boundaries relative to cell interiors
        val darkBuf = buffers[0]
        var gridDiffCount = 0
        for (gx in 1 until 80) {
            val gridPx = darkBuf[15 * 1600 + gx * 20]
            val innerPx = darkBuf[15 * 1600 + gx * 20 + 5]
            if (gridPx != innerPx) gridDiffCount++
        }
        assertTrue(gridDiffCount >= 70, "Expected crisp 1px vertical blueprint grid lines every 20px, got $gridDiffCount/79")
    }

    @Test
    fun `ResourcePackOption parser validates multi-option packs, rejects scripts and nasa, retains disabled pack options, and falls back on obsolete values`() {
        // 1. Built-in base pack declares `theme` enum with `dark / white / bone` (never `nasa`)
        val baseMeta = BuiltInResourcePacks.forSource(VisualAssetSource.PLACEHOLDER)
        assertEquals(1, baseMeta.options.size)
        val themeOpt = baseMeta.options.single()
        assertEquals("theme", themeOpt.id)
        assertEquals(ResourcePackOptionType.ENUM, themeOpt.type)
        assertEquals(listOf("dark", "white", "bone"), themeOpt.choices.map { it.value })
        assertFalse(themeOpt.affectsMediaAssets)

        // 2. Multi-option 3rd-party pack (`enum` themes + `enum` sprite swap + `boolean` high-contrast overlay)
        val samplePackJson = """
            {
              "formatVersion": 1,
              "requiresResourceFormat": 4,
              "id": "community:industrial-plus",
              "name": "Industrial Plus Pack",
              "displayText_zh": "重工增强表现包",
              "description": "Industrial plus visual & UI style pack",
              "icon": "reforge:units/c_tank/body",
              "options": [
                {
                  "id": "hudPalette",
                  "type": "enum",
                  "name": "HUD Palette",
                  "displayText_zh": "界面配色",
                  "default": "steel",
                  "choices": [
                    { "value": "steel", "name": "Steel", "displayText_zh": "冷钢", "uiTheme": "themes/steel.json" },
                    { "value": "amber", "name": "Amber", "displayText_zh": "琥珀", "uiTheme": "themes/amber.json" }
                  ]
                },
                {
                  "id": "unitStyle",
                  "type": "enum",
                  "name": "Unit Sprites",
                  "displayText_zh": "单位涂装",
                  "default": "standard",
                  "choices": [
                    { "value": "standard", "name": "Standard", "displayText_zh": "标准涂装" },
                    { "value": "winter", "name": "Winter Camo", "displayText_zh": "雪地迷彩", "images": "variants/winter_images.json" }
                  ]
                },
                {
                  "id": "highContrast",
                  "type": "boolean",
                  "name": "High Contrast Borders",
                  "displayText_zh": "高对比描边",
                  "default": false,
                  "whenTrue": { "uiTheme": "themes/hc_overlay.json" }
                }
              ]
            }
        """.trimIndent()
        val parsed = ResourcePackMetadata.read(samplePackJson.toByteArray(Charsets.UTF_8), "pack.json")
        assertEquals(3, parsed.options.size)
        assertFalse(parsed.options[0].affectsMediaAssets, "Pure uiTheme enum must NOT lock in-match")
        assertTrue(parsed.options[1].affectsMediaAssets, "Image-swapping enum MUST lock in-match")
        assertFalse(parsed.options[2].affectsMediaAssets, "Pure uiTheme boolean must NOT lock in-match")

        // Obsolete or unknown saved value silently falls back to `default` (Q7)
        assertEquals("steel", parsed.options[0].resolveValueOrDefault("removed_v1_choice"))
        assertEquals("amber", parsed.options[0].resolveValueOrDefault("amber"))
        assertEquals("false", parsed.options[2].resolveValueOrDefault("not_a_bool"))

        // Reject unsupported option type (`slider` / script) and path traversal (`../`) and reserved `nasa`
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePackMetadata.read(
                """{"formatVersion":1,"requiresResourceFormat":4,"id":"bad:type","name":"Bad","description":"d","icon":"reforge:units/c_tank/body",
                   "options":[{"id":"x","type":"slider","name":"X","default":"1"}]}""".toByteArray(Charsets.UTF_8),
                "pack.json",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePackMetadata.read(
                """{"formatVersion":1,"requiresResourceFormat":4,"id":"bad:path","name":"Bad","description":"d","icon":"reforge:units/c_tank/body",
                   "options":[{"id":"x","type":"boolean","name":"X","default":false,"whenTrue":{"images":"../escape.json"}}]}""".toByteArray(Charsets.UTF_8),
                "pack.json",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResourcePackMetadata.read(
                """{"formatVersion":1,"requiresResourceFormat":4,"id":"bad:nasa","name":"Bad","description":"d","icon":"reforge:units/c_tank/body",
                   "options":[{"id":"theme","type":"enum","name":"Theme","default":"nasa","choices":[{"value":"nasa","name":"NASA"}]}]}""".toByteArray(Charsets.UTF_8),
                "pack.json",
            )
        }

        // 3. Settings persistence retains options when pack is moved to `未启用` and locks media options in-match
        val settingsFile = tempDir.resolve("pack-opts-settings.json")
        val store = UserSettingsStore(settingsFile)
        store.update {
            it.copy(enabledResourcePacks = listOf("community:industrial-plus", UserSettings.BASE_RESOURCE_PACK_ID))
                .withPackOption("community:industrial-plus", "hudPalette", "amber")
                .withPackOption("community:industrial-plus", "unitStyle", "winter")
                .withPackOption("community:industrial-plus", "highContrast", "true")
        }
        // Disable pack -> options MUST remain in `settings.json` for future re-enable (Q7)
        store.update { it.copy(enabledResourcePacks = listOf(UserSettings.BASE_RESOURCE_PACK_ID)) }
        val reloaded = UserSettingsStore(settingsFile).effective
        assertEquals("amber", reloaded.optionsForPack("community:industrial-plus")["hudPalette"])
        assertEquals("winter", reloaded.optionsForPack("community:industrial-plus")["unitStyle"])
        assertEquals("true", reloaded.optionsForPack("community:industrial-plus")["highContrast"])

        // 4. Verify `ResourcePackOptionsModal` in-match locking: pure-UiTheme options stay enabled, media options lock!
        val shell = AppShell(initialRoute = OutGameRoute.SETTINGS)
        val entry = DualColumnPackEntry(
            id = "community:industrial-plus",
            title = "重工增强表现包",
            subtitle = "v1.2.0",
            enabled = true,
            badgeStyle = PackIconBadgeStyle.CUSTOM_RESOURCE,
            options = parsed.options,
        )
        val modal = ResourcePackOptionsModal(
            skin = skin,
            appShell = shell,
            themeProvider = { UiTheme.DEFAULT },
            iconsProvider = { null },
            fontsProvider = { null },
            fallbackFontProvider = { dummyFont },
            optionValuesProvider = { id -> store.effective.optionsForPack(id) },
            onSelectOption = { id, optId, v -> store.update { it.withPackOption(id, optId, v) } },
            onResetDefaults = { id -> store.update { it.resetPackOptions(id) } },
        )
        modal.open(entry, inMatch = true)
        modal.updateModalBounds(1280f, 720f)
        assertTrue(modal.isOptionControlEnabled("hudPalette"))
        assertFalse(modal.isOptionControlEnabled("unitStyle"))
        assertTrue(modal.isOptionControlEnabled("highContrast"))
        assertFalse(modal.selectOptionForTest("unitStyle", "standard"), "Locked media option must reject in-match mutation")
        assertTrue(modal.selectOptionForTest("hudPalette", "steel"), "Pure UiTheme option must switch live in-match")
        assertEquals("steel", store.effective.optionsForPack("community:industrial-plus")["hudPalette"])
        modal.dismiss()
    }

    @Test
    fun `OutGameMapCatalog scans skirmish and user tmx maps, strips tags, sorts 2P to 10P, binds _map_png, and isolates campaign maps`() {
        val fakeOrig = tempDir.resolve("Rusted Warfare")
        val skirmishDir = Files.createDirectories(fakeOrig.resolve("assets/maps/skirmish"))
        val campaignDir = Files.createDirectories(fakeOrig.resolve("assets/maps/campaign"))
        val userMapsDir = Files.createDirectories(tempDir.resolve("user-maps"))

        // Copy real valid `.tmx` bytes from packaged resources into simulated Steam skirmish/campaign/user directories
        val riverBytes = OutGameMapCatalog::class.java.getResourceAsStream("/maps/skirmish/river_crossing.tmx")!!.use { it.readBytes() }
        val ringBytes = OutGameMapCatalog::class.java.getResourceAsStream("/maps/skirmish/ring_atoll.tmx")!!.use { it.readBytes() }

        val map10 = skirmishDir.resolve("[z;p10]Valley Arena (10p) [by_uber].tmx")
        val map2 = skirmishDir.resolve("[p2]Beach landing (2p) [by hxyy].tmx")
        val map4User = userMapsDir.resolve("[p4]Custom Outpost (4p).tmx")
        val campaignScriptMap = campaignDir.resolve("[p2]Campaign Mission 01.tmx")

        Files.write(map10, ringBytes)
        Files.write(map2, riverBytes)
        Files.write(map4User, riverBytes)
        Files.write(campaignScriptMap, riverBytes)

        // Write a sibling `*_map.png` thumbnail for `map2` (using pure Java ImageIO so headless :client:test requires zero JNI natives)
        val thumb2 = skirmishDir.resolve("[p2]Beach landing (2p) [by hxyy]_map.png")
        val bi = java.awt.image.BufferedImage(80, 60, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 60) for (x in 0 until 80) bi.setRGB(x, y, 0xFF2659A6.toInt())
        javax.imageio.ImageIO.write(bi, "png", thumb2.toFile())

        val entries = OutGameMapCatalog.entries(
            filter = MapPickerFilter.SKIRMISH,
            originalDirectory = fakeOrig,
            userMapsDirectory = userMapsDir,
        )

        // Strictly 3 maps (`map2`, `map4User`, `map10`); `campaign` folder is 100% isolated!
        assertEquals(3, entries.size)
        assertEquals(listOf(2, 4, 10), entries.map { it.maxPlayers }, "Maps must sort ascending by player count (2P -> 4P -> 10P)")
        assertEquals("Beach landing", entries[0].localizedTitle(UiLanguage.ZH))
        assertEquals("Custom Outpost", entries[1].localizedTitle(UiLanguage.EN))
        assertEquals("Valley Arena", entries[2].localizedTitle(UiLanguage.ZH))
        assertEquals(thumb2.toAbsolutePath().normalize(), entries[0].thumbnailPath)
        assertFalse(entries.any { it.localizedTitle().contains("Campaign") }, "Campaign script maps must never leak into skirmish catalog")

        // Verify fallback tactical RGBA buffer renders cleanly without requiring OpenGL/JNI context
        val tacticalBuf = OutGameMapPreviewRenderer.renderTacticalRgbaBuffer(entries[0].mapDefinition, 128, 128, UiTheme.DEFAULT)
        assertEquals(128 * 128, tacticalBuf.size)

        // Verify uncustomized SkirmishLobbyState adapts default seat count cleanly between 4P/10P and 2P maps
        val lobbyState = SkirmishLobbyState(initialMap = entries[2]) // 10P -> 4 default seats
        assertEquals(4, lobbyState.seats.size)
        lobbyState.selectMapEntry(entries[0]) // 2P -> 2 default seats (`Beach landing · 2P`)
        assertEquals(2, lobbyState.seats.size)
        assertEquals(2, lobbyState.availableSpawnCount)
        lobbyState.addAiSeat() // Now user customized to 3 seats -> preserved on subsequent map switch
        lobbyState.selectMapEntry(entries[1])
        assertEquals(3, lobbyState.seats.size)
    }

    @Test
    fun `all packaged fallback tmx maps and all local Steam skirmish tmx maps initialize SimulationContext cleanly`() {
        val packCandidates = listOf(
            Path.of("../SimulationTest/public/inputs/phase-seven-sandbox/json/package"),
            Path.of("SimulationTest/public/inputs/phase-seven-sandbox/json/package"),
            Path.of("../../SimulationTest/public/inputs/phase-seven-sandbox/json/package"),
        ).map { it.toAbsolutePath().normalize() }
        val packDir = packCandidates.firstOrNull { Files.isRegularFile(it.resolve("pack.json")) }
        assertNotNull(packDir, "Expected phase-seven-sandbox/json/package in workspace")
        val loadedPack = com.rustedwarfare.reforge.core.content.ContentLoader.loadContentPack(packDir!!)
        assertTrue(loadedPack is com.rustedwarfare.reforge.core.content.ContentResult.Success)
        val unitDefs = (loadedPack as com.rustedwarfare.reforge.core.content.ContentResult.Success).value.units

        // 1. All 3 packaged fallback `.tmx` maps MUST initialize SimulationContext cleanly
        val fallbackEntries = OutGameMapCatalog.entries(MapPickerFilter.SKIRMISH, originalDirectory = null, userMapsDirectory = null)
        assertEquals(3, fallbackEntries.size)
        for (entry in fallbackEntries) {
            val built = TmxSkirmishScenarioBuilder.build(requireNotNull(entry.tmxPath), unitDefs, null, entry.localizedTitle())
            when (val init = com.rustedwarfare.reforge.core.sim.SimulationContext.initialize(built.scenario)) {
                is com.rustedwarfare.reforge.core.sim.InitResult.Accepted -> init.context.close()
                is com.rustedwarfare.reforge.core.sim.InitResult.Rejected ->
                    org.junit.jupiter.api.Assertions.fail<Unit>("Packaged map ${entry.id} rejected: ${init.error.code} - ${init.error.message}")
            }
        }

        // 2. When local Steam Rusted Warfare is present on developer workstation, verify ALL 48 official skirmish `.tmx` maps!
        val steamOrig = sequenceOf(
            Path.of("E:/SteamLibrary/steamapps/common/Rusted Warfare"),
            Path.of("C:/Program Files (x86)/Steam/steamapps/common/Rusted Warfare"),
            Path.of("D:/SteamLibrary/steamapps/common/Rusted Warfare"),
        ).firstOrNull { Files.isDirectory(it.resolve("assets/maps/skirmish")) }
        if (steamOrig != null) {
            val steamEntries = OutGameMapCatalog.entries(MapPickerFilter.SKIRMISH, originalDirectory = steamOrig, userMapsDirectory = null)
            assertTrue(steamEntries.size >= 40, "Expected official Steam skirmish maps, found ${steamEntries.size}")
            val failures = mutableListOf<String>()
            for (entry in steamEntries) {
                val built = runCatching {
                    TmxSkirmishScenarioBuilder.build(requireNotNull(entry.tmxPath), unitDefs, steamOrig, entry.localizedTitle())
                }.getOrElse { err ->
                    failures += "${entry.id}: build failed (${err.message})"
                    continue
                }
                when (val init = com.rustedwarfare.reforge.core.sim.SimulationContext.initialize(built.scenario)) {
                    is com.rustedwarfare.reforge.core.sim.InitResult.Accepted -> init.context.close()
                    is com.rustedwarfare.reforge.core.sim.InitResult.Rejected ->
                        failures += "${entry.id} (${entry.tmxPath?.fileName}): ${init.error.code} - ${init.error.message}"
                }
            }
            assertTrue(failures.isEmpty(), "Expected 0 SimulationContext rejections across ${steamEntries.size} Steam maps, got:\n${failures.joinToString("\n")}")
        }
    }
}
