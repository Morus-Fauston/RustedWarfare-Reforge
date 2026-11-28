package com.rustedwarfare.reforge.pc

import com.rustedwarfare.reforge.client.BootstrapScreen
import com.rustedwarfare.reforge.client.BuiltInUiThemeId
import com.rustedwarfare.reforge.client.OutGameMapCatalog
import com.rustedwarfare.reforge.client.ResourceId
import com.rustedwarfare.reforge.client.ResourcePackSelection
import com.rustedwarfare.reforge.client.SandboxLoader
import com.rustedwarfare.reforge.client.SandboxSession
import com.rustedwarfare.reforge.client.UiTheme
import com.rustedwarfare.reforge.client.UserSettingsStore
import com.rustedwarfare.reforge.client.VisualAssetSettingsSession
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcPackVariantOptionsTest {

    @Test
    fun `multi-option 3rd-party resource pack survives client-held HMAC custody, composes options top-down, swaps sprites and same-ID sounds, survives restart catalog check, and preserves live in-match UiTheme across base theme switches`() {
        ResourceImagePackApplicationFixture().use { fixture ->
            val sourcePack = fixture.create("community:multi-variant", listOf(OwnPackImage("reforge:units/c_tank/body"))).manifest.parent

            // Write base `sounds.json` + WAV AND variant `heavy_sounds.json` overriding the EXACT SAME sound ID (`reforge:sounds/cannon-fire`) + event!
            val stdCannonWav = OutGameMapCatalog::class.java.getResourceAsStream("/sounds/cannon-fire.wav")!!.use { it.readBytes() }
            val heavyCannonWav = OutGameMapCatalog::class.java.getResourceAsStream("/sounds/gun-fire.wav")!!.use { it.readBytes() }
            Files.write(sourcePack.resolve("std_cannon.wav"), stdCannonWav)
            Files.writeString(
                sourcePack.resolve("sounds.json"),
                """
                {
                  "formatVersion": 1,
                  "sounds": { "reforge:sounds/cannon-fire": { "file": "std_cannon.wav" } },
                  "events": { "reforge:events/cannon-fire": { "sound": "reforge:sounds/cannon-fire" } }
                }
                """.trimIndent(),
            )

            // Write distinct winter sprite sheet (`variants/winter_tank.png` + `variants/winter_images.json`) and heavy sound variant (`variants/heavy_sounds.json`)
            val varDir = Files.createDirectories(sourcePack.resolve("variants"))
            val winterPng = BufferedImage(120, 40, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until winterPng.width) for (y in 0 until winterPng.height) winterPng.setRGB(x, y, 0xffd0e8ff.toInt())
            ImageIO.write(winterPng, "png", varDir.resolve("winter_tank.png").toFile())
            Files.writeString(
                varDir.resolve("winter_images.json"),
                """{"formatVersion":1,"images":{"reforge:units/c_tank/body":{"file":"winter_tank.png","frameWidth":20,"frameHeight":20,"totalFrames":3}}}""",
            )
            Files.write(varDir.resolve("heavy_cannon.wav"), heavyCannonWav)
            Files.writeString(
                varDir.resolve("heavy_sounds.json"),
                """
                {
                  "formatVersion": 1,
                  "sounds": { "reforge:sounds/cannon-fire": { "file": "heavy_cannon.wav" } },
                  "events": { "reforge:events/cannon-fire": { "sound": "reforge:sounds/cannon-fire" } }
                }
                """.trimIndent(),
            )

            // Write 2 enum UI theme overlays + 1 boolean high-contrast overlay (demonstrating declaration-order override!)
            val themesDir = Files.createDirectories(sourcePack.resolve("themes"))
            Files.writeString(
                themesDir.resolve("steel.json"),
                """{"schemaVersion":1,"id":"community:steel","colors":{"rust":"#2266AAFF","line":"#445566FF"}}""",
            )
            Files.writeString(
                themesDir.resolve("amber.json"),
                """{"schemaVersion":1,"id":"community:amber","colors":{"rust":"#DD8822FF","line":"#665533FF"}}""",
            )
            Files.writeString(
                themesDir.resolve("hc_overlay.json"),
                """{"schemaVersion":1,"colors":{"line":"#FFFFFFFF"}}""",
            )

            Files.writeString(
                sourcePack.resolve("pack.json"),
                """
                {
                  "formatVersion": 1,
                  "requiresResourceFormat": 4,
                  "id": "community:multi-variant",
                  "name": "Multi Variant Pack",
                  "displayText_zh": "多变体测试资源包",
                  "description": "Multi-option declarative resource pack fixture",
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
                        { "value": "standard", "name": "Standard" },
                        { "value": "winter", "name": "Winter Camo", "images": "variants/winter_images.json" }
                      ]
                    },
                    {
                      "id": "sfxStyle",
                      "type": "enum",
                      "name": "Weapon SFX",
                      "displayText_zh": "武器音效",
                      "default": "standard",
                      "choices": [
                        { "value": "standard", "name": "Standard" },
                        { "value": "heavy", "name": "Heavy Cannon", "sounds": "variants/heavy_sounds.json" }
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
                """.trimIndent(),
            )

            val backend = fixture.backend()
            val accepted = backend.checkImagePack(sourcePack)
            assertEquals(4, accepted.metadata.options.size)

            // Delete original source directory to prove client-held `PcAcceptedResourceStore` copied & hashed all variants!
            val deletedSource = sourcePack.resolveSibling("deleted-multi-variant-source")
            Files.move(sourcePack, deletedSource)

            val baseSelection = backend.initialSelection.copy(
                packs = ResourcePackSelection(listOf(accepted.catalogEntry), listOf(accepted.metadata.id.value)),
            )

            // 1. Default options (`hudPalette=steel`, `unitStyle=standard`, `sfxStyle=standard`, `highContrast=false`)
            val prepDefault = backend.prepare(baseSelection)
            prepDefault.verifyForPublication()
            assertEquals("community:steel", prepDefault.uiTheme.id)
            assertEquals(0x22 / 255f, prepDefault.uiTheme.colors.rust.r, 1e-3f)
            assertEquals(0x44 / 255f, prepDefault.uiTheme.colors.line.r, 1e-3f)
            val stdTankPath = prepDefault.resourcePack.resources.getValue(ResourceId.parse("reforge:units/c_tank/body"))
            val stdSoundPath = requireNotNull(prepDefault.resolvedSounds).sounds.getValue(ResourceId.parse("reforge:sounds/cannon-fire")).path

            // 2. Switch to `hudPalette=amber`, `unitStyle=winter`, `sfxStyle=heavy`, `highContrast=true` ->
            //    `hc_overlay.json` (4th option) overrides `amber.json`'s `line` (`#665533` -> `#FFFFFF`) while keeping `rust` (`#DD8822`),
            //    AND swaps `c_tank/body` to `winter_tank.png` AND overrides same-ID `reforge:sounds/cannon-fire` to `heavy_cannon.wav`!
            val selVariant = baseSelection
                .withPackOption("community:multi-variant", "hudPalette", "amber")
                .withPackOption("community:multi-variant", "unitStyle", "winter")
                .withPackOption("community:multi-variant", "sfxStyle", "heavy")
                .withPackOption("community:multi-variant", "highContrast", "true")
            val prepVariant = backend.prepare(selVariant)
            prepVariant.verifyForPublication()
            backend.applyPrepared(prepVariant)
            val composedOverBone = prepVariant.composeUiTheme(UiTheme.builtIn(BuiltInUiThemeId.BONE))
            assertEquals(0xDD / 255f, composedOverBone.colors.rust.r, 1e-3f)
            assertEquals(1.0f, composedOverBone.colors.line.r, 1e-3f)
            assertEquals(UiTheme.BONE.colors.solid, composedOverBone.colors.solid, "Unpatched tokens inherit reforge:base's selected BONE theme")
            val winterTankPath = prepVariant.resourcePack.resources.getValue(ResourceId.parse("reforge:units/c_tank/body"))
            val heavySoundPath = requireNotNull(prepVariant.resolvedSounds).sounds.getValue(ResourceId.parse("reforge:sounds/cannon-fire")).path
            assertNotEquals(stdTankPath, winterTankPath, "Selecting winter sprite variant must bind winter_tank.png")
            assertNotEquals(stdSoundPath, heavySoundPath, "Selecting heavy sound variant must override same-ID cannon-fire.wav")

            // 3. Restart simulation (`[P1-3]` regression):
            //    `backendRestart` loads saved `settings.properties` (which has non-empty `catalog` and empty `packOptions`),
            //    so `BootstrapScreen` immediately kicks off async `Operation.CATALOG` (`busy == true`).
            //    Verify `UserSettingsStore` `resourcePackOptions` survive `Operation.CATALOG` and `prepareForLoad`,
            //    AND in-match live `UiTheme` option switch (`amber -> steel`) survives subsequent `setBuiltInTheme(BONE)`!
            val userStore = UserSettingsStore(deletedSource.resolveSibling("restart-user-settings.json"))
            userStore.update {
                it.copy(enabledResourcePacks = listOf("community:multi-variant", "reforge:base"))
                    .withPackOption("community:multi-variant", "hudPalette", "amber")
                    .withPackOption("community:multi-variant", "unitStyle", "winter")
                    .withPackOption("community:multi-variant", "sfxStyle", "heavy")
                    .withPackOption("community:multi-variant", "highContrast", "true")
            }
            val backendRestart = fixture.backend()
            val screen = BootstrapScreen(visualSettings = backendRestart, userSettingsStore = userStore)
            try {
                // Poll until initial async `Operation.CATALOG` finishes
                val pollDeadline = System.nanoTime() + 10_000_000_000L
                while (System.nanoTime() < pollDeadline) {
                    if (screen.pollVisualStateForTest()) break
                    Thread.sleep(15L)
                }
                val restartSel = assertNotNull(screen.currentVisualSelectionForTest)
                assertEquals("amber", restartSel.optionsFor("community:multi-variant")["hudPalette"])
                assertEquals("winter", restartSel.optionsFor("community:multi-variant")["unitStyle"])
                assertEquals("heavy", restartSel.optionsFor("community:multi-variant")["sfxStyle"])

                // Verify `VisualAssetSettingsSession.prepareForLoad()` after restart produces exact saved variants
                VisualAssetSettingsSession(backendRestart, userStore.effective.allEffectivePackOptions()).use { restartedSession ->
                    val restartPrepared = restartedSession.prepareForLoad().invoke()
                    assertEquals(winterTankPath, restartPrepared.resourcePack.resources.getValue(ResourceId.parse("reforge:units/c_tank/body")))
                    assertEquals(heavySoundPath, requireNotNull(restartPrepared.resolvedSounds).sounds.getValue(ResourceId.parse("reforge:sounds/cannon-fire")).path)

                    // Simulate active match with `restartPrepared` (`hudPalette=amber`), switch `hudPalette` live in-match to `steel`,
                    // then switch `reforge:base` built-in theme to `BONE`: `steel` (`#2266AA`) MUST NOT be reverted back to `amber`!
                    screen.applyResourcePackOptionForTest("community:multi-variant", "hudPalette", "steel", inMatch = true, matchVisuals = restartPrepared)
                    assertEquals(0x22 / 255f, screen.currentUiTheme.colors.rust.r, 1e-3f)
                    screen.setBuiltInTheme(BuiltInUiThemeId.BONE, persist = true)
                    assertEquals(0x22 / 255f, screen.currentUiTheme.colors.rust.r, 1e-3f, "Switching base built-in theme in-match must preserve 3rd-party live UiTheme option")
                    assertEquals(UiTheme.BONE.colors.solid, screen.currentUiTheme.colors.solid)
                }
            } finally {
                screen.dispose()
            }

            // 4. Tampering with a canonical staged variant overlay (`ui/variants/theme-0.json`) inside managed storage MUST fail HMAC custody check!
            val stagedVarTheme = accepted.manifest.parent.resolve("ui/variants/theme-0.json")
            assertTrue(Files.isRegularFile(stagedVarTheme))
            Files.writeString(
                stagedVarTheme,
                """{"schemaVersion":1,"id":"community:tampered"}""",
            )
            val ex = assertFailsWith<IllegalArgumentException> { prepVariant.verifyForPublication() }
            assertTrue(ex.message.orEmpty().contains("RESOURCE_PACK_ACCEPTANCE_INVALID"))
        }
    }

    @Test
    fun `ordinary non-development launch defaults to 65-unit phase-seven-sandbox package and loads real tmx skirmish map into SandboxSession`() {
        val repository = System.getProperty("public.fixtures.dir")?.let { Path.of(it).parent.parent.parent }
            ?: sequenceOf(Path.of("../.."), Path.of(".."), Path.of("."))
                .map { it.toAbsolutePath().normalize() }
                .first { Files.isDirectory(it.resolve("SimulationTest/public/inputs/phase-seven-sandbox/json/package")) }
        val options = WindowsLaunchOptions.parse(emptyArray(), repository)
        val expectedSevenRoot = repository.resolve("SimulationTest/public/inputs/phase-seven-sandbox/json/package").toAbsolutePath().normalize()
        assertEquals(expectedSevenRoot, options.inputs, "Ordinary Main Menu launch must default to 65-unit phase-seven-sandbox package")

        // Load a real `.tmx` skirmish map (`ring_atoll.tmx`) with ordinary launch inputs and open a live SandboxSession!
        val ringEntry = requireNotNull(OutGameMapCatalog.findByIdOrPath("ring_atoll", originalDirectory = null, userMapsDirectory = null))
        val ringTmx = requireNotNull(ringEntry.tmxPath)
        SandboxLoader().use { loader ->
            loader.requestSkirmishMap(
                ringTmx,
                checkNotNull(options.inputs),
                null,
                null,
                "Ring Atoll",
            )
            var prepared = loader.pollPrepared()
            val deadline = System.nanoTime() + 15_000_000_000L
            while (prepared == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                prepared = loader.pollPrepared()
            }
            val loaded = checkNotNull(prepared) { "Timed out loading real skirmish .tmx map" }.getOrThrow()
            assertTrue(loaded.scenario.unitDefinitions.size >= 60, "Expected 65-unit Phase VII roster in ordinary skirmish match, got ${loaded.scenario.unitDefinitions.size}")
            SandboxSession(loaded.scenario).use { session ->
                assertTrue(session.isOpen)
                session.tick()
                session.tick()
                assertEquals(1L, session.observation.stepIndex)
                assertTrue(session.observation.units.any { it.ownerPlayerId == "p1" && it.typeId == "commandCenter" })
            }
        }
    }
}
