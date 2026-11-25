package com.rustedwarfare.reforge.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiHudSkeletonAndEventsTest {

    @Test
    fun eventStreamEnforcesTtlsCapDedupAndFollowBadge() {
        val stream = HudEventStream()
        assertTrue(stream.visibleNotices().isEmpty())
        assertFalse(stream.cameraFollowing)

        stream.setCameraFollowing(true)
        assertTrue(stream.cameraFollowing)

        stream.pushInfo("Gun Boat 生产完成")
        stream.pushWarning("▲ 目标不可通行")
        assertEquals(2, stream.visibleNotices().size)
        assertEquals("目标不可通行", stream.visibleNotices()[0].text)
        assertEquals(HudNoticeKind.WARNING, stream.visibleNotices()[0].kind)
        assertEquals(3.0f, stream.visibleNotices()[0].durationSeconds, 1e-4f)
        assertEquals(4.0f, stream.visibleNotices()[1].durationSeconds, 1e-4f)

        stream.update(1.5f)
        stream.pushWarning("目标不可通行")
        assertEquals(2, stream.visibleNotices().size)
        assertEquals(3.0f, stream.visibleNotices()[0].remainingSeconds, 1e-4f)

        stream.pushInfo("Event 3")
        stream.pushInfo("Event 4")
        assertEquals(3, stream.visibleNotices().size)
        assertEquals("Event 4", stream.visibleNotices()[0].text)

        stream.update(3.1f)
        assertEquals(2, stream.visibleNotices().size)
        assertTrue(stream.visibleNotices().none { it.kind == HudNoticeKind.WARNING })
        assertTrue(stream.cameraFollowing)

        stream.pushRejectionCode("insufficient-funds", UiLanguage.ZH)
        assertEquals(UiStrings.get("noFunds", UiLanguage.ZH), stream.visibleNotices()[0].text)
        assertEquals(HudNoticeKind.WARNING, stream.visibleNotices()[0].kind)
    }

    @Test
    fun minimapHeightClampsAndCreditsFormatWithCommas() {
        val w = UiTokens.SIDEBAR_WIDTH
        assertEquals(w * 0.5f, HudSidebarProjection.minimapHeightRef(2000.0, 400.0, w), 1e-3f)
        assertEquals(w * 0.625f, HudSidebarProjection.minimapHeightRef(1600.0, 1000.0, w), 1e-3f)
        assertEquals(w, HudSidebarProjection.minimapHeightRef(600.0, 1800.0, w), 1e-3f)

        assertEquals("\$0", HudSidebarProjection.formatCredits(0L))
        assertEquals("\$1,450", HudSidebarProjection.formatCredits(1450L))
        assertEquals("\$125,000", HudSidebarProjection.formatCredits(125000L))
    }

    @Test
    fun foreignOwnerSubtitleDistinguishesNeutralAndNumberedFoe() {
        assertEquals("中立", HudSidebarProjection.formatForeignOwnerSubtitle("neutral", "p1", UiLanguage.ZH))
        assertEquals("Neutral", HudSidebarProjection.formatForeignOwnerSubtitle("neutral", "p1", UiLanguage.EN))
        assertEquals("玩家 2 · 敌方", HudSidebarProjection.formatForeignOwnerSubtitle("p2", "p1", UiLanguage.ZH))
        assertEquals("Player 2 · Enemy", HudSidebarProjection.formatForeignOwnerSubtitle("p2", "p1", UiLanguage.EN))
    }

    @Test
    fun queueSlotsCapAtFiveWithOverflowBadge() {
        // RLE collapses consecutive identical items: [tank, tank, scout] -> [tank x2] [scout x1]
        val three = HudSidebarProjection.buildSlots(listOf("tank", "tank", "scout"), 0.46f)
        assertEquals(5, three.size)
        assertEquals("tank", three[0].typeId)
        assertEquals(2, three[0].count)
        assertEquals(0.46f, three[0].progress ?: -1f, 1e-4f)
        assertEquals("scout", three[1].typeId)
        assertEquals(1, three[1].count)
        assertNull(three[2].typeId)
        assertEquals(0, three[4].overflowCount)

        // All-identical queue collapses into single slot [tank x7]
        val sevenSame = HudSidebarProjection.buildSlots(List(7) { "tank" }, 0.30f)
        assertEquals(5, sevenSame.size)
        assertEquals("tank", sevenSame[0].typeId)
        assertEquals(7, sevenSame[0].count)
        assertNull(sevenSame[1].typeId)
        assertEquals(0, sevenSame[4].overflowCount)

        // 6 distinct runs overflow into slot 4 with sum of actual overflow item counts
        val sixRuns = HudSidebarProjection.buildSlots(listOf("a", "b", "c", "d", "e", "e", "f"), 0.30f)
        assertEquals(5, sixRuns.size)
        assertEquals("d", sixRuns[3].typeId)
        assertNull(sixRuns[4].typeId)
        assertEquals(3, sixRuns[4].overflowCount)
    }

    @Test
    fun adaptiveSidebarShrinksAndPassesClicksBelow() {
        val emptySnap = HudSnapshot(
            creditsText = "\$1,450",
            selectionCard = null,
            queueSection = null,
            showActionsSection = false,
            menuOpen = false,
            simPaused = false,
            activeRate = OfflineRate.NORMAL,
            language = UiLanguage.ZH,
        )
        val layoutNone = InGameHudOverlay.computeLayout(
            stageW = 1138f,
            stageH = 640f,
            minimapWellH = 148f,
            snapshot = emptySnap,
            externalActionsH = 0f,
        )
        assertEquals(1138f - UiTokens.SIDEBAR_WIDTH, layoutNone.sidebarLeft, 1e-3f)
        assertEquals(640f - 148f - InGameHudOverlay.CREDITS_ROW_HEIGHT, layoutNone.sidebarBottom, 1e-3f)
        assertNull(layoutNone.cardRect)
        assertNull(layoutNone.queueRect)
        assertTrue(layoutNone.containsSidebarPoint(1000f, 600f))
        assertTrue(layoutNone.containsSidebarPoint(1000f, layoutNone.sidebarBottom + 4f))
        assertFalse(layoutNone.containsSidebarPoint(1000f, layoutNone.sidebarBottom - 10f))
        assertFalse(layoutNone.containsSidebarPoint(500f, 600f))

        val foeSnap = emptySnap.copy(
            selectionCard = HudSelectionCardModel(
                unitId = "u2",
                typeId = "tank",
                displayName = "Tank",
                countText = "×1",
                subtitleText = "玩家 2 · 敌方",
                subtitleIsWarning = false,
                subtitleIsIncome = false,
                isEnemyOrNeutral = true,
                ownerStripeRgb = 0xE0564A,
                statBars = listOf(HudStatBar("hp", "210", "/300", 0.7f)),
            ),
            queueSection = null,
            showActionsSection = false,
        )
        val layoutFoe = InGameHudOverlay.computeLayout(
            stageW = 1138f,
            stageH = 640f,
            minimapWellH = 148f,
            snapshot = foeSnap,
            externalActionsH = 200f,
        )
        assertNotNull(layoutFoe.cardRect)
        assertNull(layoutFoe.queueRect)
        assertEquals(layoutFoe.cardRect!!.y, layoutFoe.sidebarBottom, 1e-3f)
        assertFalse(layoutFoe.containsSidebarPoint(1000f, layoutFoe.sidebarBottom - 12f))
    }

    @Test
    fun minimapCenteredFitLetterboxesCleanlyInsideWell() {
        val well = MapArea(906.0, 492.0, 232.0, 148.0)
        val fitted = Minimap.fitCentered(well, 1000.0, 1000.0, 1.6875, 1.6875)
        assertEquals(148.0, fitted.width, 0.5)
        assertEquals(148.0, fitted.height, 0.5)
        assertEquals(well.left + (232.0 - 148.0) * 0.5, fitted.left, 0.5)
        assertEquals(well.bottom, fitted.bottom, 0.5)
    }

    @Test
    fun builderConstructionQueueProjectsBuildLabelAndProgress() {
        val site = com.rustedwarfare.reforge.core.sim.UnitObservation(
            unitId = "b1",
            ownerPlayerId = "p1",
            typeId = "landFactory",
            position = com.rustedwarfare.reforge.core.sim.Position(100.0, 100.0),
            heading = 0.0,
            speed = 0.0,
            angularVelocity = 0.0,
            moveTarget = null,
            pathPoints = emptyList(),
            movementState = com.rustedwarfare.reforge.core.sim.MovementState.IDLE,
            health = 200.0,
            maxHealth = 1000.0,
            alive = true,
            constructionProgress = 0.42f,
        )
        val worker = com.rustedwarfare.reforge.core.sim.UnitObservation(
            unitId = "w1",
            ownerPlayerId = "p1",
            typeId = "builder",
            position = com.rustedwarfare.reforge.core.sim.Position(80.0, 100.0),
            heading = 0.0,
            speed = 0.0,
            angularVelocity = 0.0,
            moveTarget = null,
            pathPoints = emptyList(),
            movementState = com.rustedwarfare.reforge.core.sim.MovementState.IDLE,
            health = 170.0,
            maxHealth = 170.0,
            alive = true,
            constructionProgress = 1f,
            constructionTargetId = "b1",
            tasks = listOf(
                com.rustedwarfare.reforge.core.sim.UnitTask(
                    kind = com.rustedwarfare.reforge.core.sim.UnitTaskKind.CONSTRUCT,
                    actionId = "a1",
                    targetUnitId = "b1",
                    buildingTypeId = "landFactory",
                ),
                com.rustedwarfare.reforge.core.sim.UnitTask(
                    kind = com.rustedwarfare.reforge.core.sim.UnitTaskKind.BUILD,
                    actionId = "a2",
                    buildingTypeId = "extractorT1",
                ),
            ),
        )
        val emptyMenu = ActionMenuView(credits = 500.0, selectedTitle = "Builder")
        val q = HudSidebarProjection.buildQueueSection(emptyMenu, worker, listOf(worker, site))
        assertNotNull(q)
        assertEquals("build", q!!.labelKey)
        assertEquals(42, q.progressPercent)
        assertEquals("landFactory", q.slots[0].typeId)
        assertEquals("extractorT1", q.slots[1].typeId)
        assertNull(q.slots[2].typeId)
    }

    @Test
    fun hudSnapshotProjectionFromRealSessionHandlesOwnAndFoeSelection() {
        val fixturesDir = System.getProperty("public.fixtures.dir")?.let(java.nio.file.Path::of)
            ?: java.nio.file.Path.of("..", "..", "SimulationTest", "public", "inputs").toAbsolutePath().normalize()
        val base = ControlledScenario.load(fixturesDir.resolve("platform-basics/upgrade"))
        SandboxSession(base).use { session ->
            val noneSnap = HudSidebarProjection.project(
                session = session,
                menuOpen = false,
                simPaused = false,
                activeRate = OfflineRate.NORMAL,
                language = UiLanguage.ZH,
            )
            assertNull(noneSnap.selectionCard)
            assertNull(noneSnap.queueSection)
            assertFalse(noneSnap.showActionsSection)
            assertTrue(noneSnap.creditsText.startsWith("$"))

            val ownUnit = session.observation.units.first { it.alive && it.ownerPlayerId == session.observation.playerId }
            session.select(ownUnit.position)
            val ownSnap = HudSidebarProjection.project(
                session = session,
                menuOpen = true,
                simPaused = false,
                activeRate = OfflineRate.DOUBLE,
                language = UiLanguage.ZH,
            )
            assertNotNull(ownSnap.selectionCard)
            assertFalse(ownSnap.selectionCard!!.isEnemyOrNeutral)
            assertTrue(ownSnap.menuOpen)
            assertEquals(OfflineRate.DOUBLE, ownSnap.activeRate)

            val foeUnit = session.observation.units.first { it.alive && it.ownerPlayerId != session.observation.playerId }
            session.select(foeUnit.position)
            val foeSnap = HudSidebarProjection.project(
                session = session,
                menuOpen = false,
                simPaused = false,
                activeRate = OfflineRate.NORMAL,
                language = UiLanguage.EN,
            )
            val foeCard = checkNotNull(foeSnap.selectionCard)
            assertTrue(foeCard.isEnemyOrNeutral)
            assertNull(foeSnap.queueSection)
            assertFalse(foeSnap.showActionsSection)
            assertTrue(foeCard.subtitleText!!.contains("Enemy"))
        }
    }
}
