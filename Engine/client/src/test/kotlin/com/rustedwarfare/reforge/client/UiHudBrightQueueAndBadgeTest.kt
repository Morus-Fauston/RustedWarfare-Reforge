package com.rustedwarfare.reforge.client

import com.badlogic.gdx.math.Rectangle
import com.rustedwarfare.reforge.core.content.UpgradeActionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UiHudBrightQueueAndBadgeTest {

    @Test
    fun `composite icon tuning lifts dark metallic grays while preserving black outline and green player saturation`() {
        val tuning = UiTheme.DEFAULT.unitIconTuning
        assertTrue(tuning.gamma < 1f, "Default constructivist theme should gently lift mid/dark tones")
        assertTrue(tuning.brightnessLift > 0f)

        // Pure dark outline pixel (below threshold) must remain crisp dark without turning gray
        val outline = CompositeUnitIconBuilder.applyIconTuning(0.06f, 0.06f, 0.07f, tuning)
        assertEquals(0.06f, outline[0], 1e-4f)
        assertEquals(0.06f, outline[1], 1e-4f)
        assertEquals(0.07f, outline[2], 1e-4f)

        // Dark metallic steel gray (#3C4246 -> ~0.235, 0.259, 0.275) should be noticeably brighter
        val steelR = 0x3C / 255f
        val steelG = 0x42 / 255f
        val steelB = 0x46 / 255f
        val liftedSteel = CompositeUnitIconBuilder.applyIconTuning(steelR, steelG, steelB, tuning)
        assertTrue(liftedSteel[0] > steelR + 0.06f, "Expected steel gray lift, got ${liftedSteel[0]} vs $steelR")
        assertTrue(liftedSteel[1] > steelG + 0.06f)
        assertTrue(liftedSteel[2] > steelB + 0.06f)
        assertTrue(liftedSteel[2] < 0.85f, "Lift should remain moderate and not wash out dark metal")

        // P1 green tinted body pixel (#2ECC40 -> ~0.18, 0.80, 0.25) should stay vividly green
        val gR = 0x2E / 255f
        val gG = 0xCC / 255f
        val gB = 0x40 / 255f
        val liftedGreen = CompositeUnitIconBuilder.applyIconTuning(gR, gG, gB, tuning)
        assertTrue(liftedGreen[1] - liftedGreen[0] > 0.50f, "Green saturation must stay vivid")
        assertTrue(liftedGreen[1] - liftedGreen[2] > 0.45f, "Green dominance over blue must stay vivid")

        // Compose end-to-end with IDENTITY vs default tuning confirms tuning applies only when requested
        val pixels = intArrayOf(
            (0x10 shl 24) or (0x10 shl 16) or (0x10 shl 8) or 0xFF, // black outline
            (0x40 shl 24) or (0x45 shl 16) or (0x4A shl 8) or 0xFF, // dark steel
            (0x10 shl 24) or (0xD0 shl 16) or (0x10 shl 8) or 0xFF, // pure green
            (0xC8 shl 24) or (0xCC shl 16) or (0xD0 shl 8) or 0xFF, // bright highlight
        )
        val buf = CompositeUnitIconBuilder.RgbaBuffer(2, 2, pixels)
        val layer = CompositeUnitIconBuilder.CompositeLayer(buf, 0.0, 0.0, 8.0, 8.0)
        val raw = CompositeUnitIconBuilder.compose(listOf(layer), tuning = UiUnitIconTuning.IDENTITY)!!
        val tuned = CompositeUnitIconBuilder.compose(listOf(layer), tuning = tuning)!!
        assertEquals(raw.width, tuned.width)
        val rawMeanSteel = raw.pixels.filter { (it and 0xFF) == 0xFF }.map { ((it ushr 24) and 0xFF) }.average()
        val tunedMeanSteel = tuned.pixels.filter { (it and 0xFF) == 0xFF }.map { ((it ushr 24) and 0xFF) }.average()
        assertTrue(tunedMeanSteel > rawMeanSteel + 5.0, "Tuned composite icon must be visibly brighter ($tunedMeanSteel vs $rawMeanSteel)")
    }

    @Test
    fun `finished own factories builders and upgradable buildings keep permanent empty queue row while plain units stay null`() {
        val dummyPath = Path.of("dummy.png")
        val factoryMenu = ActionMenuView(
            production = listOf(
                ProductionActionView("c_tank", "Tank", dummyPath, 1, 350L, 0.01f, 210.0, 1.1, 0, null, true),
            ),
        )
        val factoryEmpty = HudSidebarProjection.buildQueueSection(factoryMenu)
        assertNotNull(factoryEmpty)
        assertEquals("queue", factoryEmpty!!.labelKey)
        assertEquals(HudQueueKind.PRODUCTION, factoryEmpty.kind)
        assertEquals(5, factoryEmpty.slots.size)
        assertTrue(factoryEmpty.slots.all { it.typeId == null && it.count == 0 && it.overflowCount == 0 })
        assertNull(factoryEmpty.progressPercent, "Empty permanent queue must not display 0% noise")

        val builderMenu = ActionMenuView(
            construction = listOf(
                ConstructionActionView("landFactory", "Land Factory", dummyPath, 700L, 1200.0, 0.01f),
            ),
        )
        val builderEmpty = HudSidebarProjection.buildQueueSection(builderMenu)
        assertNotNull(builderEmpty)
        assertEquals("build", builderEmpty!!.labelKey)
        assertEquals(HudQueueKind.BUILD, builderEmpty.kind)
        assertEquals(5, builderEmpty.slots.size)
        assertNull(builderEmpty.progressPercent)

        val upgradeMenu = ActionMenuView(
            upgrades = listOf(
                UpgradeActionView(
                    profile = UpgradeActionProfile("upgrade_t2", "extractorT2", 600L, 0.01f),
                    kind = UpgradePresentationKind.ECONOMIC,
                    title = "Extractor T2",
                    cardText = "Upgrade",
                    tooltip = "",
                    progress = null,
                    affordable = true,
                ),
            ),
        )
        val upgradeEmpty = HudSidebarProjection.buildQueueSection(upgradeMenu)
        assertNotNull(upgradeEmpty)
        assertEquals("upgradeQ", upgradeEmpty!!.labelKey)
        assertEquals(HudQueueKind.UPGRADE, upgradeEmpty.kind)
        assertEquals(5, upgradeEmpty.slots.size)
        assertNull(upgradeEmpty.progressPercent)

        // Plain combat unit / non-queue building returns null
        assertNull(HudSidebarProjection.buildQueueSection(ActionMenuView()))
    }

    @Test
    fun `transition from empty factory queue to queued item keeps action list anchor Y identical (zero jump)`() {
        val card = HudSelectionCardModel(
            unitId = "u1",
            typeId = "landFactory",
            displayName = "陆军工厂",
            countText = "1",
            subtitleText = null,
            subtitleIsWarning = false,
            subtitleIsIncome = false,
            isEnemyOrNeutral = false,
            ownerStripeRgb = null,
            statBars = listOf(HudStatBar("hp", "600", "/600", 1f, HudStatStyle.NORMAL)),
        )
        val emptyQueue = HudQueueSection(
            labelKey = "queue",
            slots = List(5) { HudQueueSlot(null, null, 0) },
            progressPercent = null,
            kind = HudQueueKind.PRODUCTION,
        )
        val activeQueue = HudQueueSection(
            labelKey = "queue",
            slots = HudSidebarProjection.buildSlots(listOf("c_tank", "c_tank", "c_tank"), 0.35f),
            progressPercent = 35,
            kind = HudQueueKind.PRODUCTION,
        )
        val snapEmpty = HudSnapshot(
            creditsText = "$2,000",
            typeChips = null,
            selectionCard = card,
            cargoSection = null,
            stockpileSection = null,
            queueSection = emptyQueue,
            showActionsSection = true,
            menuOpen = false,
            simPaused = false,
            activeRate = OfflineRate.NORMAL,
            language = UiLanguage.ZH,
        )
        val snapActive = snapEmpty.copy(queueSection = activeQueue)

        val layoutEmpty = InGameHudOverlay.computeLayout(1138f, 640f, 148f, snapEmpty, 200f)
        val layoutActive = InGameHudOverlay.computeLayout(1138f, 640f, 148f, snapActive, 200f)

        assertNotNull(layoutEmpty.queueRect)
        assertNotNull(layoutActive.queueRect)
        assertEquals(layoutEmpty.queueRect!!.y, layoutActive.queueRect!!.y, 1e-4f)
        assertEquals(layoutEmpty.queueRect.height, layoutActive.queueRect.height, 1e-4f)
        assertEquals(layoutEmpty.actionsTopY, layoutActive.actionsTopY, 1e-4f)
        assertEquals(layoutEmpty.actionsMaxHeight, layoutActive.actionsMaxHeight, 1e-4f)
    }

    @Test
    fun `protruding queue count badge stays within queue row top boundary in both ZH and EN`() {
        val metrics = UiTheme.DEFAULT.metrics
        assertEquals(3f, metrics.queueBadgeOffsetX, 1e-4f)
        assertEquals(3f, metrics.queueBadgeOffsetY, 1e-4f)
        assertEquals(1f, metrics.queueBadgeStrokeWidth, 1e-4f)

        val queueRect = Rectangle(890f, 300f, UiTokens.SIDEBAR_WIDTH, InGameHudOverlay.QUEUE_ROW_HEIGHT)
        val stroke = metrics.queueBadgeStrokeWidth
        for (lang in listOf(UiLanguage.ZH, UiLanguage.EN)) {
            val slots = InGameHudOverlay.computeQueueSlotRects(queueRect, lang, 5)
            for ((idx, sr) in slots.withIndex()) {
                val badge = InGameHudOverlay.computeQueueBadgeRect(sr, textWidth = 6f, textHeight = 7f, metrics = metrics)
                // Protrudes up-right by +3 relative to slot top-right
                assertEquals(sr.x + sr.width + 3f, badge.x + badge.width, 1e-4f)
                assertEquals(sr.y + sr.height + 3f, badge.y + badge.height, 1e-4f)
                // Outer stroke top stays cleanly below queue row top border (safety clearance >= 4 ref units)
                val outerTop = badge.y + badge.height + stroke
                assertTrue(
                    outerTop <= queueRect.y + queueRect.height - 4f,
                    "Badge outer top ($outerTop) too close to queue row top (${queueRect.y + queueRect.height}) for $lang slot $idx",
                )
            }
        }
    }
}
