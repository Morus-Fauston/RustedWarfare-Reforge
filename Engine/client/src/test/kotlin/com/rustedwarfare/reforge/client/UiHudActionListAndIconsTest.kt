package com.rustedwarfare.reforge.client

import com.rustedwarfare.reforge.core.sim.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class UiHudActionListAndIconsTest {

    @Test
    fun commandIconAtlasManifestContainsAll65SelfOwnedIconsAndValidPng() {
        val jsonUrl = checkNotNull(javaClass.classLoader.getResource(HudCommandIconManifest.ATLAS_JSON_PATH)) {
            "Missing packaged command icon manifest"
        }
        val pngUrl = checkNotNull(javaClass.classLoader.getResource(HudCommandIconManifest.ATLAS_PNG_PATH)) {
            "Missing packaged command icon atlas PNG"
        }
        val manifest = HudCommandIconManifest.parse(jsonUrl.readText(Charsets.UTF_8))
        assertEquals(65, manifest.icons.size)
        assertEquals(72, manifest.iconSizePx)
        assertTrue(manifest.atlasWidth >= 600 && manifest.atlasHeight >= 600)
        assertTrue(manifest.license.contains("Self-owned", ignoreCase = true))

        // Verify all required core command icons exist inside the 65-icon manifest
        val requiredIcons = listOf(
            "rw-stop", "rw-patrol", "rw-guard",
            "rw-rally", "rw-rally-clear", "rw-reclaim", "rw-repair", "rw-upgrade", "rw-cancel",
            "rw-load", "rw-unload", "rw-dive", "rw-surface", "rw-takeoff", "rw-land",
            "rw-afterburn", "rw-deploy", "rw-undeploy", "rw-blink",
            "rw-nuke", "rw-nuke-build", "rw-anti-nuke", "rw-scout",
            "rw-slot-empty", "rw-add-module", "rw-enable", "rw-disable",
            "rw-menu", "rw-pause", "rw-resume", "rw-speed",
            "rw-spawn", "rw-destroy", "rw-health-adjust", "rw-console",
            "rw-info", "rw-camera-follow",
        )
        for (icon in requiredIcons) {
            val rect = manifest.icons[icon]
            assertNotNull(rect, "Missing required icon: $icon")
            assertEquals(72, rect!!.width)
            assertEquals(72, rect.height)
            assertTrue(rect.x >= 0 && rect.x + rect.width <= manifest.atlasWidth)
            assertTrue(rect.y >= 0 && rect.y + rect.height <= manifest.atlasHeight)
        }
        val pngBytes = pngUrl.readBytes()
        assertTrue(pngBytes.size > 10_000, "Atlas PNG appears empty or corrupt")
        assertEquals(0x89.toByte(), pngBytes[0])
        assertEquals(0x50.toByte(), pngBytes[1])
    }

    @Test
    fun actionProjectionShowsCommonCommandsOnlyForMovableOwnUnitsAndMapsGuard() {
        val fixturesDir = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            // Select building (extractor) -> NOT movable -> common command bar hidden
            session.select(Position(190.0, 290.0))
            val bSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertFalse(bSnap.showCommonBar)
            assertTrue(bSnap.commonCommands.isEmpty())
            assertTrue(bSnap.exclusiveKeys.any { it.id.startsWith("upgrade:") })

            // Select builder (movable) -> common bar shows Stop S / Patrol P / Guard G
            session.select(Position(270.0, 270.0))
            val wSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            assertTrue(wSnap.showCommonBar)
            assertEquals(listOf("stop", "choose-patrol", "choose-guard"), wSnap.commonCommands.map { it.id })
            assertEquals(listOf("S", "P", "G"), wSnap.commonCommands.map { it.hotkey })
            // Reclaim comes BEFORE construction keys per Spec §7.1-7.2
            val reclaimIdx = wSnap.exclusiveKeys.indexOfFirst { it.id == "choose-reclaim" }
            val firstBuildIdx = wSnap.exclusiveKeys.indexOfFirst { it.id.startsWith("choose-construction:") }
            assertTrue(reclaimIdx >= 0 && firstBuildIdx > reclaimIdx)

            // Touch controls hide keyboard hotkey labels
            val touchSnap = HudActionProjection.projectActionList(session, null, false, false, false, null, touchControls = true, language = UiLanguage.ZH)
            assertTrue(touchSnap.commonCommands.all { it.hotkey.isEmpty() })
            assertTrue(touchSnap.exclusiveKeys.all { it.hotkeyLabel == null })
        }
    }

    @Test
    fun targetModeCardProjectsConstructionThreeStatesAndPatrolCount() {
        val fixturesDir = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            session.select(Position(270.0, 270.0))
            val buildType = session.actionMenu.construction.first().typeId

            val clearCard = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = buildType,
                constructionPreview = ConstructionPreview(buildType, Position(300.0, 300.0), PlacementState.CLEAR, emptyList()),
                reclaimMode = false, repairMode = false, patrolMode = false, patrolPointCount = 0,
                groundActionName = null, groundActionPreview = null, sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNotNull(clearCard)
            assertFalse(clearCard!!.statusIsWarning)
            assertEquals(UiStrings.get("placeOk", UiLanguage.ZH), clearCard.statusText)

            val blockedCard = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = buildType,
                constructionPreview = ConstructionPreview(buildType, Position(300.0, 300.0), PlacementState.BLOCKED, emptyList()),
                reclaimMode = false, repairMode = false, patrolMode = false, patrolPointCount = 0,
                groundActionName = null, groundActionPreview = null, sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNotNull(blockedCard)
            assertTrue(blockedCard!!.statusIsWarning)
            assertTrue(blockedCard.statusText.startsWith("▲"))

            val unexploredCard = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = buildType,
                constructionPreview = ConstructionPreview(buildType, Position(300.0, 300.0), PlacementState.UNEXPLORED, emptyList()),
                reclaimMode = false, repairMode = false, patrolMode = false, patrolPointCount = 0,
                groundActionName = null, groundActionPreview = null, sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNotNull(unexploredCard)
            assertTrue(unexploredCard!!.statusIsWarning)
            assertTrue(unexploredCard.statusText.contains("未探索"))

            val patrolCard = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = null, constructionPreview = null,
                reclaimMode = false, repairMode = false, patrolMode = true, patrolPointCount = 3,
                groundActionName = null, groundActionPreview = null, sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNotNull(patrolCard)
            assertEquals("已选 3 点", patrolCard!!.rightValue)

            val noneCard = HudActionProjection.projectTargetModeCard(
                session = session,
                constructionTypeId = null, constructionPreview = null,
                reclaimMode = false, repairMode = false, patrolMode = false, patrolPointCount = 0,
                groundActionName = null, groundActionPreview = null, sandboxPick = null, touchControls = false, language = UiLanguage.ZH,
            )
            assertNull(noneCard)
        }
    }

    @Test
    fun shortFundsConstructionKeyIsDisabledAndTriggersFlashState() {
        val fixturesDir = System.getProperty("public.fixtures.dir")?.let(Path::of)
            ?: Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/maintenance"))
        SandboxSession(base).use { session ->
            session.select(Position(270.0, 270.0))
            val snap = HudActionProjection.projectActionList(session, null, false, false, false, null, false, UiLanguage.ZH)
            val shortKeys = snap.exclusiveKeys.filter { it.id.startsWith("choose-construction:") && it.state == HudActionKeyState.SHORT_FUNDS }
            assertTrue(shortKeys.isNotEmpty(), "Expected at least one short-funds construction key")
            assertTrue(shortKeys.all { !it.enabled }, "SHORT_FUNDS construction key must have enabled=false so click flashes instead of entering placement")
            val readyKeys = snap.exclusiveKeys.filter { it.id.startsWith("choose-construction:") && it.state == HudActionKeyState.READY }
            assertTrue(readyKeys.all { it.enabled }, "READY construction keys must remain enabled")
        }
    }
    @Test
    fun nativeActionPanelSourceFileIsCompletelyRemoved() {
        val legacyFile = Path.of("src", "main", "kotlin", "com", "rustedwarfare", "reforge", "client", "NativeActionPanel.kt")
        assertFalse(Files.exists(legacyFile), "NativeActionPanel.kt must be removed in UI-P001-005")
    }
}
