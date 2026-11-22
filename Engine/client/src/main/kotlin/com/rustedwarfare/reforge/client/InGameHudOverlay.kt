package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import kotlin.math.ceil

/**
 * Pure geometry output for the redesigned in-game HUD chrome (UI-P001-004).
 *
 * Uses Stage coordinates (origin bottom-left, units = reference units at short edge 640).
 */
data class HudOverlayLayout(
    val sidebarLeft: Float,
    val sidebarWidth: Float,
    val sidebarTop: Float,
    val sidebarBottom: Float,
    val minimapRect: Rectangle,
    val creditsRect: Rectangle,
    val typeChipsRect: Rectangle?,
    val cardRect: Rectangle?,
    val cargoRect: Rectangle?,
    val stockpileRect: Rectangle?,
    val queueRect: Rectangle?,
    val actionsTopY: Float,
    val actionsMaxHeight: Float,
    val systemBarRect: Rectangle,
) {
    /** True if [x],[y] falls inside the adaptive right sidebar (including minimap & active sections). */
    fun containsSidebarPoint(x: Float, y: Float): Boolean =
        x >= sidebarLeft && x <= sidebarLeft + sidebarWidth && y >= sidebarBottom && y <= sidebarTop
}

/** Callbacks triggered by [InGameHudOverlay] interactive controls. */
interface InGameHudCallbacks {
    fun onToggleMenu()
    fun onTogglePause()
    fun onSelectRate(rate: OfflineRate)
    fun onMenuResume()
    fun onMenuRestart()
    fun onMenuBackToStart()
    fun onMenuSettings()
    fun onSelectPrimaryType(typeId: String) {}
    fun onToggleDebug() {}
    fun onCancelQueueSlot(kind: HudQueueKind, slot: HudQueueSlot, slotIndex: Int) {}
}

/**
 * Redesigned in-game HUD skeleton overlay (HUD Spec §4, §5, §6, §9.1, §12.1–12.2):
 * - Top-left system bar (`≡ 菜单` | `暂停`/`继续` | `1×` | `2×` | `4×`)
 * - Top-left event notice stream (max 3 timed notices + optional persistent follow badge)
 * - Right adaptive-height sidebar (opaque black minimap well, credits row, selection card with
 *   segmented stat bars / enemy left stripe, production/upgrade queue row with 5 slots & +N)
 * - `≡` menu dropdown panel (4 rows + 0.45 scrim)
 * - Centered pause modal card (`已暂停` + bone `▶ 继续` button + 0.45 scrim)
 */
internal class InGameHudOverlay(
    private val skin: Skin,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val unitIconProvider: (String) -> TextureRegion?,
    private val commandIconProvider: (String) -> TextureRegion? = { null },
    private val callbacks: InGameHudCallbacks,
) : Group() {

    private var snapshot: HudSnapshot? = null
    private var notices: List<HudNoticeItem> = emptyList()
    private var cameraFollowing: Boolean = false
    private var stageW: Float = 960f
    private var stageH: Float = 640f
    private var minimapWellH: Float = 148f
    private var externalActionsH: Float = 0f
    private var showDebugButton: Boolean = false
    private var debugActive: Boolean = false
    private var noticeTopYOverride: Float? = null

    var layout: HudOverlayLayout = computeLayout(
        stageW = 960f,
        stageH = 640f,
        minimapWellH = 148f,
        snapshot = null,
        externalActionsH = 0f,
    )
        private set

    private val scrimActor = Actor().apply {
        touchable = Touchable.disabled
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean = true
            override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                if (pointer == 0 && button == Input.Buttons.LEFT && snapshot?.menuOpen == true) {
                    callbacks.onMenuResume()
                }
            }
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float): Boolean = true
        })
    }

    private val btnMenu = HitActor(onClick = { callbacks.onToggleMenu() })
    private val btnPause = HitActor(onClick = { callbacks.onTogglePause() })
    private val btnRate1 = HitActor(onClick = { callbacks.onSelectRate(OfflineRate.NORMAL) })
    private val btnRate2 = HitActor(onClick = { callbacks.onSelectRate(OfflineRate.DOUBLE) })
    private val btnRate4 = HitActor(onClick = { callbacks.onSelectRate(OfflineRate.QUADRUPLE) })
    private val btnDebug = HitActor(onClick = { callbacks.onToggleDebug() })

    private val btnMenuResume = HitActor(onClick = { callbacks.onMenuResume() })
    private val btnMenuRestart = HitActor(onClick = { callbacks.onMenuRestart() })
    private val btnMenuBack = HitActor(onClick = { callbacks.onMenuBackToStart() })
    private val btnMenuSettings = HitActor(onClick = { callbacks.onMenuSettings() })

    private val btnPauseResume = HitActor(onClick = { callbacks.onTogglePause() })
    private val typeChipActors = List(HudSidebarProjection.MAX_TYPE_CHIPS) { idx ->
        HitActor(onClick = {
            snapshot?.typeChips?.chips?.getOrNull(idx)?.let { chip -> callbacks.onSelectPrimaryType(chip.typeId) }
        })
    }
    private val queueSlotActors = List(5) { idx ->
        HitActor(
            onClick = {
                val queue = snapshot?.queueSection ?: return@HitActor
                val slot = queue.slots.getOrNull(idx) ?: return@HitActor
                if (slot.typeId != null) {
                    callbacks.onCancelQueueSlot(queue.kind, slot, idx)
                }
            },
            acceptRightClick = true,
        )
    }

    private val sidebarBlocker = Actor().apply {
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) = true
            override fun scrolled(event: InputEvent, x: Float, y: Float, amountX: Float, amountY: Float) = true
        })
    }

    private val glyphLayout = GlyphLayout()

    init {
        isTransform = false
        touchable = Touchable.childrenOnly
        isVisible = false
        addActor(sidebarBlocker)
        addActor(scrimActor)
        addActor(btnMenu)
        addActor(btnPause)
        addActor(btnRate1)
        addActor(btnRate2)
        addActor(btnRate4)
        addActor(btnDebug)
        addActor(btnMenuResume)
        addActor(btnMenuRestart)
        addActor(btnMenuBack)
        addActor(btnMenuSettings)
        addActor(btnPauseResume)
        typeChipActors.forEach(::addActor)
        queueSlotActors.forEach(::addActor)
    }

    private fun bodyFont(): BitmapFont = fontsProvider()?.body ?: fallbackFontProvider()
    private fun titleFont(): BitmapFont = fontsProvider()?.title ?: fallbackFontProvider()
    private fun commandFont(): BitmapFont = fontsProvider()?.command ?: fallbackFontProvider()
    private fun smallFont(): BitmapFont = fontsProvider()?.small ?: fallbackFontProvider()
    private fun creditsMonoFont(): BitmapFont = fontsProvider()?.creditsMono ?: fallbackFontProvider()
    private fun countMonoFont(): BitmapFont = fontsProvider()?.countMono ?: fallbackFontProvider()
    private fun smallMonoFont(): BitmapFont = fontsProvider()?.smallMono ?: fallbackFontProvider()
    private fun captionMonoFont(): BitmapFont = fontsProvider()?.captionMono ?: fallbackFontProvider()

    /** Lightweight per-frame sync for fading notice alphas & camera-follow badge without full relayout. */
    fun syncEventStream(eventStream: HudEventStream, noticesStartBelowY: Float? = noticeTopYOverride) {
        notices = eventStream.visibleNotices()
        cameraFollowing = eventStream.cameraFollowing
        noticeTopYOverride = noticesStartBelowY
    }

    fun update(
        stageWidth: Float,
        stageHeight: Float,
        worldWidth: Double,
        worldHeight: Double,
        hudSnapshot: HudSnapshot?,
        eventStream: HudEventStream,
        externalActionsHeight: Float,
        showTouchDebugButton: Boolean = false,
        debugLayerEnabled: Boolean = false,
        noticesStartBelowY: Float? = null,
    ) {
        stageW = stageWidth.coerceAtLeast(1f)
        stageH = stageHeight.coerceAtLeast(1f)
        snapshot = hudSnapshot
        notices = eventStream.visibleNotices()
        cameraFollowing = eventStream.cameraFollowing
        minimapWellH = HudSidebarProjection.minimapHeightRef(worldWidth, worldHeight, UiTokens.SIDEBAR_WIDTH)
        externalActionsH = externalActionsHeight.coerceAtLeast(0f)
        showDebugButton = showTouchDebugButton
        debugActive = debugLayerEnabled
        noticeTopYOverride = noticesStartBelowY
        setBounds(0f, 0f, stageW, stageH)
        isVisible = hudSnapshot != null

        layout = computeLayout(
            stageW = stageW,
            stageH = stageH,
            minimapWellH = minimapWellH,
            snapshot = hudSnapshot,
            externalActionsH = externalActionsH,
        )

        if (hudSnapshot == null) {
            setAllHitActorsDisabled()
            return
        }

        val sysTop = stageH - UiTokens.SCREEN_MARGIN
        val sysY = sysTop - SYSTEM_BAR_HEIGHT
        val lang = hudSnapshot.language
        val wMenu = measureSystemCellWidth(UiStrings.get("menu", lang), bodyFont())
        val pauseLabel = UiStrings.get(if (hudSnapshot.simPaused) "resume" else "pause", lang)
        val wPause = measureSystemCellWidth(pauseLabel, bodyFont())
        val wRate1 = measureSystemCellWidth("1×", countMonoFont())
        val wRate2 = measureSystemCellWidth("2×", countMonoFont())
        val wRate4 = measureSystemCellWidth("4×", countMonoFont())

        var cx = UiTokens.SCREEN_MARGIN
        btnMenu.setActiveBounds(cx, sysY, wMenu, SYSTEM_BAR_HEIGHT); cx += wMenu
        btnPause.setActiveBounds(cx, sysY, wPause, SYSTEM_BAR_HEIGHT); cx += wPause
        btnRate1.setActiveBounds(cx, sysY, wRate1, SYSTEM_BAR_HEIGHT); cx += wRate1
        btnRate2.setActiveBounds(cx, sysY, wRate2, SYSTEM_BAR_HEIGHT); cx += wRate2
        btnRate4.setActiveBounds(cx, sysY, wRate4, SYSTEM_BAR_HEIGHT); cx += wRate4
        if (showDebugButton) {
            val wDebug = measureSystemCellWidth(UiStrings.get("debugBtn", lang), bodyFont())
            btnDebug.setActiveBounds(cx, sysY, wDebug, SYSTEM_BAR_HEIGHT); cx += wDebug
        } else {
            btnDebug.disable()
        }
        layout = layout.copy(
            systemBarRect = Rectangle(UiTokens.SCREEN_MARGIN, sysY, cx - UiTokens.SCREEN_MARGIN, SYSTEM_BAR_HEIGHT),
        )

        val bodyH = (layout.minimapRect.y - layout.sidebarBottom).coerceAtLeast(0f)
        if (bodyH > 0f) {
            sidebarBlocker.setBounds(layout.sidebarLeft, layout.sidebarBottom, layout.sidebarWidth, bodyH)
            sidebarBlocker.touchable = Touchable.enabled
        } else {
            sidebarBlocker.touchable = Touchable.disabled
        }

        // Position multi-type selection chip hit actors (Spec §5.2)
        val chipsSection = hudSnapshot.typeChips
        val chipsRect = layout.typeChipsRect
        if (chipsSection != null && chipsRect != null && !hudSnapshot.menuOpen && !hudSnapshot.simPaused) {
            val chipRects = computeTypeChipRects(chipsRect, chipsSection)
            typeChipActors.forEachIndexed { idx, actor ->
                val r = chipRects.getOrNull(idx)
                if (r != null) actor.setActiveBounds(r.x, r.y, r.width, r.height) else actor.disable()
            }
        } else {
            typeChipActors.forEach { it.disable() }
        }

        // Position queue slot hit actors (UI-P002 §5.2: click to cancel 1 item in production/upgrade queue)
        val queueSection = hudSnapshot.queueSection
        val queueRect = layout.queueRect
        if (queueSection != null && queueRect != null && !hudSnapshot.menuOpen && !hudSnapshot.simPaused) {
            val slotRects = computeQueueSlotRects(queueRect, lang)
            queueSlotActors.forEachIndexed { idx, actor ->
                val slot = queueSection.slots.getOrNull(idx)
                val r = slotRects.getOrNull(idx)
                if (slot != null && slot.typeId != null && r != null) {
                    actor.setActiveBounds(r.x, r.y, r.width, r.height)
                } else {
                    actor.disable()
                }
            }
        } else {
            queueSlotActors.forEach { it.disable() }
        }

        val modalOpen = hudSnapshot.menuOpen || hudSnapshot.simPaused
        scrimActor.setBounds(0f, 0f, stageW, stageH)
        scrimActor.touchable = if (modalOpen) Touchable.enabled else Touchable.disabled

        if (hudSnapshot.menuOpen) {
            val menuTop = layout.systemBarRect.y - 6f
            val rowW = UiTokens.MENU_PANEL_WIDTH - MENU_PAD * 2f
            val rowX = UiTokens.SCREEN_MARGIN + MENU_PAD
            var ry = menuTop - MENU_PAD - MENU_ROW_H
            btnMenuResume.setActiveBounds(rowX, ry, rowW, MENU_ROW_H); ry -= (MENU_ROW_H + MENU_ROW_GAP)
            btnMenuRestart.setActiveBounds(rowX, ry, rowW, MENU_ROW_H); ry -= (MENU_ROW_H + MENU_ROW_GAP)
            btnMenuBack.setActiveBounds(rowX, ry, rowW, MENU_ROW_H); ry -= (MENU_ROW_H + MENU_ROW_GAP)
            btnMenuSettings.setActiveBounds(rowX, ry, rowW, MENU_ROW_H)
        } else {
            btnMenuResume.disable()
            btnMenuRestart.disable()
            btnMenuBack.disable()
            btnMenuSettings.disable()
        }

        if (hudSnapshot.simPaused && !hudSnapshot.menuOpen) {
            val cardW = PAUSE_CARD_W
            val cardH = PAUSE_CARD_H
            val cardX = (stageW - cardW) * 0.5f
            val cardY = (stageH - cardH) * 0.5f
            val btnW = 116f
            val btnH = 34f
            btnPauseResume.setActiveBounds(cardX + (cardW - btnW) * 0.5f, cardY + 18f, btnW, btnH)
        } else {
            btnPauseResume.disable()
        }
    }

    private fun setAllHitActorsDisabled() {
        sidebarBlocker.touchable = Touchable.disabled
        scrimActor.touchable = Touchable.disabled
        btnMenu.disable(); btnPause.disable()
        btnRate1.disable(); btnRate2.disable(); btnRate4.disable(); btnDebug.disable()
        btnMenuResume.disable(); btnMenuRestart.disable(); btnMenuBack.disable(); btnMenuSettings.disable()
        btnPauseResume.disable()
        typeChipActors.forEach { it.disable() }
        queueSlotActors.forEach { it.disable() }
    }

    /** True if [stageX],[stageY] should be consumed by HUD chrome instead of passing to battlefield. */
    fun containsHudPoint(stageX: Float, stageY: Float): Boolean {
        val snap = snapshot ?: return false
        if (snap.menuOpen || snap.simPaused) return true
        if (layout.containsSidebarPoint(stageX, stageY)) return true
        if (layout.systemBarRect.contains(stageX, stageY)) return true
        return false
    }

    fun drawnActions(): List<DrawnAction> {
        val snap = snapshot ?: return emptyList()
        val lang = snap.language
        val list = mutableListOf<DrawnAction>()
        list += DrawnAction("open-menu", btnMenu, UiStrings.get("menu", lang), enabled = true)
        list += DrawnAction(
            "pause-simulation",
            btnPause,
            UiStrings.get(if (snap.simPaused) "resume" else "pause", lang),
            enabled = true,
        )
        list += DrawnAction("set-speed:1", btnRate1, "1×", enabled = true)
        list += DrawnAction("set-speed:2", btnRate2, "2×", enabled = true)
        list += DrawnAction("set-speed:4", btnRate4, "4×", enabled = true)
        if (showDebugButton) {
            val dbgLabel = UiStrings.get("debugBtn", lang)
            list += DrawnAction("debug-layer-toggle", btnDebug, dbgLabel, enabled = true)
            list += DrawnAction("collision-box-view", btnDebug, dbgLabel, enabled = true)
        }
        if (snap.menuOpen) {
            list += DrawnAction("menu-resume", btnMenuResume, UiStrings.get("menu.resume", lang), enabled = true)
            list += DrawnAction("menu-restart", btnMenuRestart, UiStrings.get("menu.restart", lang), enabled = true)
            list += DrawnAction("start-match", btnMenuRestart, UiStrings.get("menu.restart", lang), enabled = true)
            list += DrawnAction("menu-exit", btnMenuBack, UiStrings.get("menu.backToStart", lang), enabled = true)
            list += DrawnAction("exit-match", btnMenuBack, UiStrings.get("menu.backToStart", lang), enabled = true)
            list += DrawnAction("menu-settings", btnMenuSettings, UiStrings.get("menu.settings", lang), enabled = true)
        }
        if (snap.simPaused && !snap.menuOpen) {
            list += DrawnAction("pause-resume", btnPauseResume, UiStrings.get("resume", lang), enabled = true)
        }
        if (!snap.menuOpen && !snap.simPaused) {
            snap.typeChips?.chips?.forEachIndexed { idx, chip ->
                val actor = typeChipActors.getOrNull(idx) ?: return@forEachIndexed
                if (actor.touchable == Touchable.enabled) {
                    list += DrawnAction("primary-chip:${chip.typeId}", actor, "${chip.displayName} ${chip.count}", enabled = true)
                }
            }
            snap.queueSection?.slots?.forEachIndexed { idx, slot ->
                val actor = queueSlotActors.getOrNull(idx) ?: return@forEachIndexed
                val typeId = slot.typeId
                if (typeId != null && actor.touchable == Touchable.enabled) {
                    val label = if (slot.count >= 2) "$typeId ×${slot.count}" else typeId
                    list += DrawnAction("queue-slot:$idx", actor, label, enabled = true)
                }
            }
        }
        return list
    }

    /** Draws only the sidebar and event notices (sits underneath Stage widgets such as BattleActionList). */
    override fun draw(batch: Batch, parentAlpha: Float) {
        val snap = snapshot ?: return
        val white = skin.getRegion("white")
        val origColor = batch.packedColor

        drawSidebar(batch, white, snap, parentAlpha)
        drawNotices(batch, white, snap, parentAlpha)
        if (!snap.menuOpen && !snap.simPaused) {
            drawSystemBar(batch, white, snap, parentAlpha)
        }

        batch.packedColor = origColor
    }

    /**
     * Draws modal scrim, crisp system bar and menu/pause modal ABOVE all other Stage actors
     * (including legacy action list during UI-P001-004).
     */
    fun drawModalLayer(batch: Batch, parentAlpha: Float) {
        val snap = snapshot ?: return
        if (!snap.menuOpen && !snap.simPaused) return
        val white = skin.getRegion("white")
        val origColor = batch.packedColor

        fillRect(batch, white, 0f, 0f, stageW, stageH, UiTokens.SCRIM, parentAlpha)
        drawSystemBar(batch, white, snap, parentAlpha)
        if (snap.menuOpen) {
            drawMenuPanel(batch, white, snap, parentAlpha)
        } else if (snap.simPaused) {
            drawPauseCard(batch, white, snap, parentAlpha)
        }

        batch.packedColor = origColor
    }

    private fun drawSidebar(batch: Batch, white: TextureRegion, snap: HudSnapshot, alpha: Float) {
        val l = layout
        val railH = (l.sidebarTop - l.sidebarBottom).coerceAtLeast(0f)
        if (railH <= 0f) return

        val bodyH = (l.minimapRect.y - l.sidebarBottom).coerceAtLeast(0f)
        if (bodyH > 0f) {
            fillRect(batch, white, l.sidebarLeft, l.sidebarBottom, l.sidebarWidth, bodyH, UiTokens.RAIL, alpha)
        }
        fillRect(batch, white, l.sidebarLeft, l.sidebarBottom, 1f, railH, UiTokens.LINE, alpha)
        fillRect(batch, white, l.sidebarLeft, l.sidebarBottom, l.sidebarWidth, 1f, UiTokens.LINE, alpha)
        fillRect(batch, white, l.sidebarLeft, l.minimapRect.y, l.sidebarWidth, 1f, UiTokens.LINE, alpha)

        val cr = l.creditsRect
        val padH = UiTokens.SIDEBAR_PAD_H
        drawTextLeft(
            batch, smallFont(), UiStrings.get("funds", snap.language),
            cr.x + padH, cr.y + cr.height * 0.5f, UiTokens.DIM, alpha,
        )
        drawTextRight(
            batch, creditsMonoFont(), snap.creditsText,
            cr.x + cr.width - padH, cr.y + cr.height * 0.5f + 1f, UiTokens.TEXT, alpha,
        )

        val chips = snap.typeChips
        val chipsRect = l.typeChipsRect
        if (chips != null && chipsRect != null) {
            fillRect(batch, white, l.sidebarLeft + 1f, chipsRect.y + chipsRect.height, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
            drawTypeChips(batch, white, chips, chipsRect, alpha)
        }

        val card = snap.selectionCard
        val cardRect = l.cardRect
        if (card != null && cardRect != null) {
            fillRect(batch, white, l.sidebarLeft + 1f, cardRect.y + cardRect.height, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
            drawSelectionCard(batch, white, card, cardRect, snap.language, alpha)
        }

        val cargo = snap.cargoSection
        val cargoRect = l.cargoRect
        if (cargo != null && cargoRect != null) {
            fillRect(batch, white, l.sidebarLeft + 1f, cargoRect.y + cargoRect.height, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
            drawCargoSection(batch, white, cargo, cargoRect, snap.language, alpha)
        }

        val stockpile = snap.stockpileSection
        val stockpileRect = l.stockpileRect
        if (stockpile != null && stockpileRect != null) {
            fillRect(batch, white, l.sidebarLeft + 1f, stockpileRect.y + stockpileRect.height, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
            drawStockpileSection(batch, white, stockpile, stockpileRect, snap.language, alpha)
        }

        val queue = snap.queueSection
        val qRect = l.queueRect
        if (queue != null && qRect != null) {
            fillRect(batch, white, l.sidebarLeft + 1f, qRect.y + qRect.height, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
            drawQueueSection(batch, white, queue, qRect, snap.language, alpha)
        }

        if (externalActionsH > 0f && snap.showActionsSection) {
            fillRect(batch, white, l.sidebarLeft + 1f, l.actionsTopY, l.sidebarWidth - 1f, 1f, UiTokens.HAIR, alpha)
        }
    }

    private fun drawTypeChips(
        batch: Batch,
        white: TextureRegion,
        section: HudTypeChipsSection,
        rect: Rectangle,
        alpha: Float,
    ) {
        val chipRects = computeTypeChipRects(rect, section)
        section.chips.forEachIndexed { idx, chip ->
            val r = chipRects.getOrNull(idx) ?: return@forEachIndexed
            val actor = typeChipActors.getOrNull(idx)
            val bg = when {
                chip.isPrimary -> UiTokens.VALUE
                actor?.hovered == true -> MENU_ROW_HOVER
                else -> UiTokens.WELL
            }
            val border = if (chip.isPrimary) UiTokens.VALUE else UiTokens.LINE
            val fg = if (chip.isPrimary) UiTokens.WELL else UiTokens.TEXT
            fillRect(batch, white, r.x, r.y, r.width, r.height, bg, alpha)
            strokeRect(batch, white, r.x, r.y, r.width, r.height, border, alpha)
            val icon = unitIconProvider(chip.typeId)
            val iconSz = 16f
            val cy = r.y + r.height * 0.5f
            if (icon != null) {
                val asp = icon.regionWidth.toFloat() / icon.regionHeight.coerceAtLeast(1).toFloat()
                val dw = if (asp >= 1f) iconSz else iconSz * asp
                val dh = if (asp >= 1f) iconSz / asp else iconSz
                batch.setColor(1f, 1f, 1f, alpha)
                batch.draw(icon, r.x + 6f + (iconSz - dw) * 0.5f, cy - dh * 0.5f, dw, dh)
            }
            drawTextRight(batch, countMonoFont(), chip.count.toString(), r.x + r.width - 6f, cy, fg, alpha)
        }
        if (section.overflowCount > 0) {
            val overflowRect = chipRects.getOrNull(section.chips.size)
            if (overflowRect != null) {
                fillRect(batch, white, overflowRect.x, overflowRect.y, overflowRect.width, overflowRect.height, UiTokens.WELL, alpha)
                strokeRect(batch, white, overflowRect.x, overflowRect.y, overflowRect.width, overflowRect.height, UiTokens.LINE, alpha)
                drawTextCenter(batch, countMonoFont(), "+${section.overflowCount}", overflowRect.x + overflowRect.width * 0.5f, overflowRect.y + overflowRect.height * 0.5f, UiTokens.DIM, alpha)
            }
        }
    }

    private fun drawCargoSection(
        batch: Batch,
        white: TextureRegion,
        cargo: HudCargoSection,
        rect: Rectangle,
        lang: UiLanguage,
        alpha: Float,
    ) {
        val padH = UiTokens.SIDEBAR_PAD_H
        val cy = rect.y + rect.height * 0.5f
        drawTextLeft(batch, smallFont(), UiStrings.get("cargo", lang), rect.x + padH, cy, UiTokens.DIM, alpha)

        // Right-aligned occupied/capacity (occupied 13pt bold text, /capacity 11pt dim per Spec §7.2)
        val rightEdge = rect.x + rect.width - padH
        val capText = "/${cargo.capacity}"
        val occText = cargo.occupiedSlots.toString()
        glyphLayout.setText(captionMonoFont(), capText)
        val capW = glyphLayout.width
        glyphLayout.setText(countMonoFont(), occText)
        val occW = glyphLayout.width
        drawTextRight(batch, captionMonoFont(), capText, rightEdge, cy - 0.5f, UiTokens.DIM, alpha)
        drawTextRight(batch, countMonoFont(), occText, rightEdge - capW - 1f, cy + 0.5f, UiTokens.TEXT, alpha)
        val capacityLeft = rightEdge - capW - occW - 8f

        // Merged passenger chips (unit icon 16x16 + count)
        val labelColW = if (lang == UiLanguage.EN) 38f else 32f
        var cx = rect.x + padH + labelColW + 4f
        val chipH = 24f
        val chipY = rect.y + (rect.height - chipH) * 0.5f
        if (cargo.items.isEmpty()) {
            drawTextLeft(batch, smallFont(), UiStrings.get("empty", lang), cx, cy, UiTokens.DIM, alpha * 0.85f)
        } else {
            var rendered = 0
            for (item in cargo.items) {
                val cntText = item.count.toString()
                glyphLayout.setText(smallMonoFont(), cntText)
                val chipW = (5f + 16f + 4f + glyphLayout.width + 5f).coerceAtLeast(36f)
                if (cx + chipW > capacityLeft && rendered > 0) {
                    val remain = cargo.items.size - rendered
                    if (cx + 26f <= capacityLeft) {
                        drawTextLeft(batch, smallMonoFont(), "+$remain", cx + 2f, cy, UiTokens.DIM, alpha)
                    }
                    break
                }
                fillRect(batch, white, cx, chipY, chipW, chipH, UiTokens.WELL, alpha)
                strokeRect(batch, white, cx, chipY, chipW, chipH, UiTokens.LINE, alpha)
                val icon = unitIconProvider(item.typeId)
                if (icon != null) {
                    val iconSz = 16f
                    val asp = icon.regionWidth.toFloat() / icon.regionHeight.coerceAtLeast(1).toFloat()
                    val dw = if (asp >= 1f) iconSz else iconSz * asp
                    val dh = if (asp >= 1f) iconSz / asp else iconSz
                    batch.setColor(1f, 1f, 1f, alpha)
                    batch.draw(icon, cx + 5f + (iconSz - dw) * 0.5f, cy - dh * 0.5f, dw, dh)
                }
                drawTextRight(batch, smallMonoFont(), cntText, cx + chipW - 5f, cy, UiTokens.TEXT, alpha)
                cx += chipW + 4f
                rendered++
            }
        }
    }

    private fun drawStockpileSection(
        batch: Batch,
        white: TextureRegion,
        stock: HudStockpileSection,
        rect: Rectangle,
        lang: UiLanguage,
        alpha: Float,
    ) {
        val padH = UiTokens.SIDEBAR_PAD_H
        val cy = rect.y + rect.height * 0.5f
        drawTextLeft(batch, smallFont(), UiStrings.get("stock", lang), rect.x + padH, cy, UiTokens.DIM, alpha)

        // Right-aligned current/capacity (current 13pt bold text, /capacity 11pt dim per Spec §5.4)
        val rightEdge = rect.x + rect.width - padH
        val capText = "/${stock.capacity}"
        val curText = stock.current.toString()
        glyphLayout.setText(captionMonoFont(), capText)
        val capW = glyphLayout.width
        glyphLayout.setText(countMonoFont(), curText)
        val curW = glyphLayout.width
        drawTextRight(batch, captionMonoFont(), capText, rightEdge, cy - 0.5f, UiTokens.DIM, alpha)
        drawTextRight(batch, countMonoFont(), curText, rightEdge - capW - 1f, cy + 0.5f, UiTokens.TEXT, alpha)
        val maxPipRight = rightEdge - capW - curW - 8f

        // 14x14 pips (solid VALUE fill for stocked, dashed LINE outline for empty slots per Spec §5.4)
        // If capacity > available single-row width (e.g. 12 interceptors), shrink pip size slightly so every pip fits cleanly.
        val labelColW = if (lang == UiLanguage.EN) 36f else 30f
        val startX = rect.x + padH + labelColW + 4f
        val availW = (maxPipRight - startX).coerceAtLeast(14f)
        val cap = stock.capacity.coerceAtLeast(1)
        val gap = if (cap <= 6) 4f else 2f
        val pipSize = minOf(STOCKPILE_PIP_SIZE, ((availW - (cap - 1) * gap) / cap).coerceAtLeast(4f))
        val pipY = rect.y + (rect.height - pipSize) * 0.5f
        var px = startX
        for (i in 0 until cap) {
            if (i < stock.current) {
                fillRect(batch, white, px, pipY, pipSize, pipSize, UiTokens.VALUE, alpha)
                strokeRect(batch, white, px, pipY, pipSize, pipSize, UiTokens.LINE, alpha)
            } else {
                drawDashedRect(batch, white, px, pipY, pipSize, pipSize, UiTokens.LINE, alpha * 0.78f)
            }
            px += pipSize + gap
        }
    }

    private fun drawSelectionCard(
        batch: Batch,
        white: TextureRegion,
        card: HudSelectionCardModel,
        rect: Rectangle,
        lang: UiLanguage,
        alpha: Float,
    ) {
        val leftPad = if (card.isEnemyOrNeutral) 14f else UiTokens.SIDEBAR_PAD_H
        if (card.isEnemyOrNeutral) {
            val stripeColor = card.ownerStripeRgb?.let { MatchPlayerColors.rgbToColor(it) } ?: UiTokens.FOE
            fillRect(batch, white, rect.x + 1f, rect.y, 4f, rect.height, stripeColor, alpha)
        }

        val topY = rect.y + rect.height - UiTokens.SIDEBAR_PAD_V
        val iconSize = UiTokens.ICON_BOX_SIZE
        val iconX = rect.x + leftPad
        val iconY = topY - iconSize

        fillRect(batch, white, iconX, iconY, iconSize, iconSize, UiTokens.WELL, alpha)
        strokeRect(batch, white, iconX, iconY, iconSize, iconSize, UiTokens.LINE, alpha)

        val iconRegion = unitIconProvider(card.typeId)
        if (iconRegion != null) {
            val maxIcon = 28f
            val aspect = iconRegion.regionWidth.toFloat() / iconRegion.regionHeight.coerceAtLeast(1).toFloat()
            val drawW = if (aspect >= 1f) maxIcon else maxIcon * aspect
            val drawH = if (aspect >= 1f) maxIcon / aspect else maxIcon
            batch.setColor(1f, 1f, 1f, alpha)
            batch.draw(iconRegion, iconX + (iconSize - drawW) * 0.5f, iconY + (iconSize - drawH) * 0.5f, drawW, drawH)
        }

        val rightEdge = rect.x + rect.width - UiTokens.SIDEBAR_PAD_H
        val countFont = if (card.countText.startsWith("×")) countMonoFont() else smallFont()
        glyphLayout.setText(countFont, card.countText)
        val countW = glyphLayout.width
        drawTextRight(batch, countFont, card.countText, rightEdge, topY - 8f, UiTokens.DIM, alpha)

        val textX = iconX + iconSize + 9f
        val availNameW = (rightEdge - countW - 6f - textX).coerceAtLeast(40f)
        val nameToDraw = ellipsize(titleFont(), card.displayName, availNameW)
        val nameCenterY = if (card.subtitleText != null) iconY + iconSize * 0.68f else iconY + iconSize * 0.5f
        drawTextLeft(batch, titleFont(), nameToDraw, textX, nameCenterY, UiTokens.TEXT, alpha)

        card.subtitleText?.let { sub ->
            val subColor = when {
                card.subtitleIsWarning -> UiTokens.AMBER
                card.subtitleIsIncome -> UiTokens.VALUE
                else -> UiTokens.DIM
            }
            val availSubW = (rightEdge - textX).coerceAtLeast(40f)
            val subDraw = ellipsize(smallFont(), sub, availSubW)
            drawTextLeft(batch, smallFont(), subDraw, textX, iconY + iconSize * 0.25f, subColor, alpha)
        }

        if (card.statBars.isNotEmpty()) {
            val rowLeft = rect.x + leftPad
            val rowRight = rightEdge
            val labelColW = if (lang == UiLanguage.EN) 40f else 30f
            val colGap = if (lang == UiLanguage.EN) 6f else 8f
            val measuredValW = card.statBars.maxOfOrNull { b ->
                glyphLayout.setText(smallMonoFont(), b.currentText)
                val cw = glyphLayout.width
                val mw = if (b.maxText.isNotEmpty()) { glyphLayout.setText(captionMonoFont(), b.maxText); glyphLayout.width + 2f } else 0f
                cw + mw
            } ?: 0f
            val valColW = maxOf(UiTokens.STAT_VALUE_COL_WIDTH, measuredValW)
            val barX = rowLeft + labelColW + colGap
            val barW = (rowRight - valColW - colGap - barX).coerceAtLeast(36f)
            var rowCenterY = iconY - 8f - STAT_ROW_H * 0.5f

            for (bar in card.statBars) {
                val labelColor = if (bar.style == HudStatStyle.WARNING_AMBER) UiTokens.AMBER else UiTokens.DIM
                val valColor = if (bar.style == HudStatStyle.WARNING_AMBER) UiTokens.AMBER else UiTokens.TEXT
                val fillColor = when (bar.style) {
                    HudStatStyle.PROGRESS_RUST -> UiTokens.RUST
                    HudStatStyle.WARNING_AMBER -> UiTokens.AMBER
                    HudStatStyle.NORMAL -> UiTokens.VALUE
                }

                drawTextLeft(batch, smallFont(), UiStrings.get(bar.labelKey, lang), rowLeft, rowCenterY, labelColor, alpha)

                val barY = rowCenterY - UiTokens.STAT_BAR_HEIGHT * 0.5f
                fillRect(batch, white, barX, barY, barW, UiTokens.STAT_BAR_HEIGHT, UiTokens.WELL, alpha)
                val innerW = (barW - 2f).coerceAtLeast(0f)
                val fillW = (innerW * bar.ratio.coerceIn(0f, 1f))
                if (fillW > 0.5f) {
                    fillRect(batch, white, barX + 1f, barY + 1f, fillW, UiTokens.STAT_BAR_HEIGHT - 2f, fillColor, alpha)
                }
                for (q in 1..3) {
                    val tickX = barX + barW * (q * 0.25f)
                    fillRect(batch, white, tickX, barY + 1f, 1f, UiTokens.STAT_BAR_HEIGHT - 2f, UiTokens.WELL, alpha)
                }
                strokeRect(batch, white, barX, barY, barW, UiTokens.STAT_BAR_HEIGHT, UiTokens.LINE, alpha)

                if (bar.maxText.isNotEmpty()) {
                    val capFont = captionMonoFont()
                    glyphLayout.setText(capFont, bar.maxText)
                    val maxW = glyphLayout.width
                    drawTextRight(batch, capFont, bar.maxText, rowRight, rowCenterY - 0.5f, UiTokens.DIM, alpha)
                    drawTextRight(batch, smallMonoFont(), bar.currentText, rowRight - maxW - 2f, rowCenterY, valColor, alpha)
                } else {
                    drawTextRight(batch, smallMonoFont(), bar.currentText, rowRight, rowCenterY, valColor, alpha)
                }

                rowCenterY -= (STAT_ROW_H + STAT_ROW_GAP)
            }
        }
    }

    private fun drawQueueSection(
        batch: Batch,
        white: TextureRegion,
        queue: HudQueueSection,
        rect: Rectangle,
        lang: UiLanguage,
        alpha: Float,
    ) {
        val padH = UiTokens.SIDEBAR_PAD_H
        val centerY = rect.y + rect.height * 0.5f
        drawTextLeft(batch, smallFont(), UiStrings.get(queue.labelKey, lang), rect.x + padH, centerY, UiTokens.DIM, alpha)

        val slotRects = computeQueueSlotRects(rect, lang)
        queue.slots.forEachIndexed { idx, slot ->
            val sr = slotRects.getOrNull(idx) ?: return@forEachIndexed
            val sx = sr.x
            val slotY = sr.y
            val slotSize = sr.width
            val occupied = slot.typeId != null || slot.overflowCount > 0
            if (occupied) {
                val hovered = slot.typeId != null && queueSlotActors.getOrNull(idx)?.hovered == true
                fillRect(batch, white, sx, slotY, slotSize, slotSize, if (hovered) MENU_ROW_HOVER else UiTokens.WELL, alpha)
                slot.progress?.let { p ->
                    val fillH = ((slotSize - 2f) * p.coerceIn(0f, 1f))
                    if (fillH > 0.5f) {
                        fillRect(batch, white, sx + 1f, slotY + 1f, slotSize - 2f, fillH, QUEUE_PROGRESS_RUST, alpha)
                    }
                }
                val borderColor = when {
                    hovered -> UiTokens.FOE
                    slot.progress != null -> UiTokens.RUST
                    else -> UiTokens.LINE
                }
                strokeRect(batch, white, sx, slotY, slotSize, slotSize, borderColor, alpha)
                if (slot.overflowCount > 0) {
                    drawTextCenter(
                        batch, captionMonoFont(), "+${slot.overflowCount}",
                        sx + slotSize * 0.5f, slotY + slotSize * 0.5f, UiTokens.DIM, alpha,
                    )
                } else if (slot.typeId != null) {
                    val region = unitIconProvider(slot.typeId)
                    if (region != null) {
                        val maxI = slotSize - 4f
                        val asp = region.regionWidth.toFloat() / region.regionHeight.coerceAtLeast(1).toFloat()
                        val dw = if (asp >= 1f) maxI else maxI * asp
                        val dh = if (asp >= 1f) maxI / asp else maxI
                        batch.setColor(1f, 1f, 1f, alpha)
                        batch.draw(region, sx + (slotSize - dw) * 0.5f, slotY + (slotSize - dh) * 0.5f, dw, dh)
                    }
                    // Top-right red-background white-text count badge when count >= 2 (UI-P002 §5.2)
                    if (slot.count >= 2) {
                        val badgeText = slot.count.toString()
                        val badgeFont = captionMonoFont()
                        glyphLayout.setText(badgeFont, badgeText)
                        val padX = 2f
                        val badgeW = (glyphLayout.width + padX * 2f).coerceAtLeast(9f).coerceAtMost(slotSize - 2f)
                        val badgeH = (glyphLayout.height + 2.5f).coerceAtLeast(9.5f).coerceAtMost(11.5f)
                        val bx = sx + slotSize - badgeW
                        val by = slotY + slotSize - badgeH
                        fillRect(batch, white, bx, by, badgeW, badgeH, QUEUE_COUNT_BADGE_RED, alpha)
                        drawTextCenter(batch, badgeFont, badgeText, bx + badgeW * 0.5f, by + badgeH * 0.5f + 0.5f, Color.WHITE, alpha)
                    }
                }
            } else {
                drawDashedRect(batch, white, sx, slotY, slotSize, slotSize, UiTokens.LINE, alpha * 0.65f)
            }
        }

        queue.progressPercent?.let { pct ->
            drawTextRight(batch, countMonoFont(), "$pct%", rect.x + rect.width - padH, centerY, UiTokens.TEXT, alpha)
        }
    }

    private fun drawSystemBar(batch: Batch, white: TextureRegion, snap: HudSnapshot, alpha: Float) {
        val bar = layout.systemBarRect
        fillRect(batch, white, bar.x, bar.y, bar.width, bar.height, UiTokens.RAIL, alpha)

        val lang = snap.language
        drawSystemCell(batch, white, btnMenu, UiStrings.get("menu", lang), bodyFont(), active = snap.menuOpen, drawRightSep = true, alpha = alpha)
        drawSystemCell(
            batch, white, btnPause,
            UiStrings.get(if (snap.simPaused) "resume" else "pause", lang),
            bodyFont(), active = snap.simPaused, drawRightSep = true, alpha = alpha,
        )
        drawSystemCell(batch, white, btnRate1, "1×", countMonoFont(), active = snap.activeRate == OfflineRate.NORMAL, drawRightSep = true, alpha = alpha)
        drawSystemCell(batch, white, btnRate2, "2×", countMonoFont(), active = snap.activeRate == OfflineRate.DOUBLE, drawRightSep = true, alpha = alpha)
        drawSystemCell(batch, white, btnRate4, "4×", countMonoFont(), active = snap.activeRate == OfflineRate.QUADRUPLE, drawRightSep = showDebugButton, alpha = alpha)
        if (showDebugButton) {
            drawSystemCell(batch, white, btnDebug, UiStrings.get("debugBtn", lang), bodyFont(), active = debugActive, drawRightSep = false, alpha = alpha)
        }

        strokeRect(batch, white, bar.x, bar.y, bar.width, bar.height, UiTokens.LINE, alpha)
    }

    private fun drawSystemCell(
        batch: Batch,
        white: TextureRegion,
        actor: Actor,
        text: String,
        font: BitmapFont,
        active: Boolean,
        drawRightSep: Boolean,
        alpha: Float,
    ) {
        if (active) {
            fillRect(batch, white, actor.x, actor.y, actor.width, actor.height, UiTokens.VALUE, alpha)
        }
        val textColor = if (active) UiTokens.WELL else UiTokens.TEXT
        drawTextCenter(batch, font, text, actor.x + actor.width * 0.5f, actor.y + actor.height * 0.5f, textColor, alpha)
        if (drawRightSep) {
            fillRect(batch, white, actor.x + actor.width - 1f, actor.y, 1f, actor.height, UiTokens.LINE, alpha)
        }
    }

    private fun drawNotices(batch: Batch, white: TextureRegion, snap: HudSnapshot, alpha: Float) {
        var ny = (noticeTopYOverride ?: layout.systemBarRect.y) - 8f
        val x = UiTokens.SCREEN_MARGIN
        val font = bodyFont()

        if (cameraFollowing) {
            val text = UiStrings.get("following", snap.language)
            val followIcon = commandIconProvider("rw-camera-follow")
            val iconSz = 14f
            val iconLead = if (followIcon != null) iconSz + 7f else 0f
            glyphLayout.setText(font, text)
            val w = glyphLayout.width + 20f + iconLead
            val h = NOTICE_H
            ny -= h
            fillRect(batch, white, x, ny, w, h, UiTokens.RAIL, alpha)
            strokeRect(batch, white, x, ny, w, h, UiTokens.VALUE, alpha)
            if (followIcon != null) {
                batch.setColor(UiTokens.VALUE.r, UiTokens.VALUE.g, UiTokens.VALUE.b, UiTokens.VALUE.a * alpha)
                batch.draw(followIcon, x + 9f, ny + (h - iconSz) * 0.5f, iconSz, iconSz)
            }
            drawTextLeft(batch, font, text, x + 10f + iconLead, ny + h * 0.5f, UiTokens.TEXT, alpha)
            ny -= NOTICE_GAP
        }

        notices.forEachIndexed { idx, item ->
            val stackDim = if (idx == 0) 1f else 0.55f
            val itemAlpha = alpha * item.alpha * stackDim
            if (itemAlpha <= 0.02f) return@forEachIndexed

            val displayText = if (item.kind == HudNoticeKind.WARNING) "▲ ${item.text}" else item.text
            glyphLayout.setText(font, displayText)
            val w = (glyphLayout.width + 10f + 7f + 8f + 10f).coerceAtMost(stageW - UiTokens.SIDEBAR_WIDTH - 24f)
            val h = NOTICE_H
            ny -= h

            fillRect(batch, white, x, ny, w, h, UiTokens.RAIL, itemAlpha)
            strokeRect(batch, white, x, ny, w, h, UiTokens.LINE, itemAlpha)

            val pipColor = if (item.kind == HudNoticeKind.WARNING) UiTokens.AMBER else UiTokens.RUST
            fillRect(batch, white, x + 10f, ny + (h - 7f) * 0.5f, 7f, 7f, pipColor, itemAlpha)

            val textColor = if (item.kind == HudNoticeKind.WARNING) UiTokens.AMBER else UiTokens.TEXT
            val textMaxW = (w - 33f).coerceAtLeast(40f)
            drawTextLeft(batch, font, ellipsize(font, displayText, textMaxW), x + 25f, ny + h * 0.5f, textColor, itemAlpha)
            ny -= NOTICE_GAP
        }
    }

    private fun drawMenuPanel(batch: Batch, white: TextureRegion, snap: HudSnapshot, alpha: Float) {
        val panelW = UiTokens.MENU_PANEL_WIDTH
        val panelH = MENU_PAD * 2f + MENU_ROW_H * 4f + MENU_ROW_GAP * 3f
        val panelX = UiTokens.SCREEN_MARGIN
        val panelY = layout.systemBarRect.y - 6f - panelH

        fillRect(batch, white, panelX, panelY, panelW, panelH, UiTokens.SOLID, alpha)
        strokeRect(batch, white, panelX, panelY, panelW, panelH, MENU_BORDER, alpha)

        val lang = snap.language
        drawMenuRow(batch, white, btnMenuResume, "▶", UiStrings.get("menu.resume", lang), "Esc", dimmed = false, alpha = alpha)
        drawMenuRow(batch, white, btnMenuRestart, "↺", UiStrings.get("menu.restart", lang), null, dimmed = false, alpha = alpha)
        drawMenuRow(batch, white, btnMenuBack, "←", UiStrings.get("menu.backToStart", lang), null, dimmed = false, alpha = alpha)
        drawMenuRow(batch, white, btnMenuSettings, "⚙", UiStrings.get("menu.settings", lang), null, dimmed = true, alpha = alpha)
    }

    private fun drawMenuRow(
        batch: Batch,
        white: TextureRegion,
        actor: HitActor,
        glyph: String,
        label: String,
        shortcut: String?,
        dimmed: Boolean,
        alpha: Float,
    ) {
        val bg = if (actor.hovered && !dimmed) MENU_ROW_HOVER else UiTokens.CELL
        fillRect(batch, white, actor.x, actor.y, actor.width, actor.height, bg, alpha)
        val fg = if (dimmed) UiTokens.DIM else UiTokens.TEXT
        val cy = actor.y + actor.height * 0.5f
        drawMenuIcon(batch, white, glyph, actor.x + 12f, cy, fg, alpha)
        drawTextLeft(batch, bodyFont(), label, actor.x + 30f, cy, fg, alpha)
        if (shortcut != null) {
            drawTextRight(batch, smallFont(), shortcut, actor.x + actor.width - 10f, cy, UiTokens.DIM, alpha)
        }
    }

    private fun drawMenuIcon(batch: Batch, white: TextureRegion, kind: String, cx: Float, cy: Float, color: Color, alpha: Float) {
        when (kind) {
            "▶" -> {
                fillRect(batch, white, cx - 3f, cy - 4f, 2f, 8f, color, alpha)
                fillRect(batch, white, cx - 1f, cy - 3f, 2f, 6f, color, alpha)
                fillRect(batch, white, cx + 1f, cy - 2f, 2f, 4f, color, alpha)
                fillRect(batch, white, cx + 3f, cy - 1f, 1.5f, 2f, color, alpha)
            }
            "←", "↺" -> {
                fillRect(batch, white, cx - 4f, cy - 0.75f, 9f, 1.5f, color, alpha)
                fillRect(batch, white, cx - 4f, cy - 2f, 2f, 4f, color, alpha)
                fillRect(batch, white, cx - 2.5f, cy - 3.5f, 1.5f, 2f, color, alpha)
                fillRect(batch, white, cx - 2.5f, cy + 1.5f, 1.5f, 2f, color, alpha)
            }
            else -> {
                strokeRect(batch, white, cx - 3.5f, cy - 3.5f, 7f, 7f, color, alpha)
                fillRect(batch, white, cx - 1f, cy + 3.5f, 2f, 1.5f, color, alpha)
                fillRect(batch, white, cx - 1f, cy - 5f, 2f, 1.5f, color, alpha)
                fillRect(batch, white, cx - 5f, cy - 1f, 1.5f, 2f, color, alpha)
                fillRect(batch, white, cx + 3.5f, cy - 1f, 1.5f, 2f, color, alpha)
                fillRect(batch, white, cx - 1.2f, cy - 1.2f, 2.4f, 2.4f, color, alpha)
            }
        }
    }

    private fun drawPauseCard(batch: Batch, white: TextureRegion, snap: HudSnapshot, alpha: Float) {
        val cardW = PAUSE_CARD_W
        val cardH = PAUSE_CARD_H
        val cardX = (stageW - cardW) * 0.5f
        val cardY = (stageH - cardH) * 0.5f

        fillRect(batch, white, cardX, cardY, cardW, cardH, UiTokens.SOLID, alpha)
        strokeRect(batch, white, cardX, cardY, cardW, cardH, MENU_BORDER, alpha)

        drawTextCenter(
            batch, titleFont(), UiStrings.get("paused", snap.language),
            cardX + cardW * 0.5f, cardY + cardH - 30f, UiTokens.TEXT, alpha,
        )

        val btn = btnPauseResume
        val btnBg = if (btn.hovered) UiTokens.BONE_HI else UiTokens.BONE
        fillRect(batch, white, btn.x, btn.y, btn.width, btn.height, btnBg, alpha)
        val btnCy = btn.y + btn.height * 0.5f
        val resumeLabel = UiStrings.get("resume", snap.language)
        glyphLayout.setText(commandFont(), resumeLabel)
        val contentW = 10f + 6f + glyphLayout.width
        val startX = btn.x + (btn.width - contentW) * 0.5f
        drawMenuIcon(batch, white, "▶", startX + 4f, btnCy, UiTokens.INK, alpha)
        drawTextLeft(batch, commandFont(), resumeLabel, startX + 14f, btnCy, UiTokens.INK, alpha)
    }

    private fun measureSystemCellWidth(text: String, font: BitmapFont): Float {
        glyphLayout.setText(font, text)
        return ceil(glyphLayout.width + 20f).coerceAtLeast(36f)
    }

    private fun ellipsize(font: BitmapFont, text: String, maxWidth: Float): String {
        glyphLayout.setText(font, text)
        if (glyphLayout.width <= maxWidth || text.length <= 2) return text
        var end = text.length - 1
        while (end > 1) {
            val candidate = text.substring(0, end) + "…"
            glyphLayout.setText(font, candidate)
            if (glyphLayout.width <= maxWidth) return candidate
            end--
        }
        return text.take(1) + "…"
    }

    private fun fillRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(white, x, y, w, h)
    }

    private fun strokeRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(white, x, y, w, 1f)
        batch.draw(white, x, y + h - 1f, w, 1f)
        batch.draw(white, x, y, 1f, h)
        batch.draw(white, x + w - 1f, y, 1f, h)
    }

    private fun drawDashedRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float) {
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        val dash = 4f
        val step = 7f
        var dx = 0f
        while (dx < w) {
            val len = minOf(dash, w - dx)
            batch.draw(white, x + dx, y, len, 1f)
            batch.draw(white, x + dx, y + h - 1f, len, 1f)
            dx += step
        }
        var dy = 0f
        while (dy < h) {
            val len = minOf(dash, h - dy)
            batch.draw(white, x, y + dy, 1f, len)
            batch.draw(white, x + w - 1f, y + dy, 1f, len)
            dy += step
        }
    }

    private fun drawTextLeft(batch: Batch, font: BitmapFont, text: String, x: Float, centerY: Float, color: Color, alpha: Float) {
        glyphLayout.setText(font, text)
        font.setColor(color.r, color.g, color.b, color.a * alpha)
        font.draw(batch, text, x, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawTextRight(batch: Batch, font: BitmapFont, text: String, rightX: Float, centerY: Float, color: Color, alpha: Float) {
        glyphLayout.setText(font, text)
        font.setColor(color.r, color.g, color.b, color.a * alpha)
        font.draw(batch, text, rightX - glyphLayout.width, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawTextCenter(batch: Batch, font: BitmapFont, text: String, centerX: Float, centerY: Float, color: Color, alpha: Float) {
        glyphLayout.setText(font, text)
        font.setColor(color.r, color.g, color.b, color.a * alpha)
        font.draw(batch, text, centerX - glyphLayout.width * 0.5f, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private class HitActor(
        private val onClick: () -> Unit,
        private val acceptRightClick: Boolean = false,
    ) : Actor() {
        var hovered: Boolean = false
            private set

        init {
            touchable = Touchable.disabled
            addListener(object : InputListener() {
                private fun acceptedButton(button: Int): Boolean =
                    button == Input.Buttons.LEFT || (acceptRightClick && button == Input.Buttons.RIGHT)

                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || !acceptedButton(button)) return false
                    return true
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer == 0 && acceptedButton(button) && x >= 0f && y >= 0f && x <= width && y <= height) {
                        onClick()
                    }
                }

                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }
            })
        }

        fun setActiveBounds(x: Float, y: Float, w: Float, h: Float) {
            isVisible = true
            setBounds(x, y, w, h)
            touchable = Touchable.enabled
        }

        fun disable() {
            hovered = false
            isVisible = false
            touchable = Touchable.disabled
            setBounds(0f, 0f, 0f, 0f)
        }
    }

    companion object {
        const val SYSTEM_BAR_HEIGHT: Float = 28f
        const val CREDITS_ROW_HEIGHT: Float = 38f
        const val STAT_ROW_H: Float = 14f
        const val STAT_ROW_GAP: Float = 4f
        const val TYPE_CHIPS_PAD_V: Float = 7f
        const val TYPE_CHIP_H: Float = 26f
        const val TYPE_CHIP_W: Float = 46f
        const val TYPE_CHIP_GAP: Float = 4f
        const val CARGO_ROW_HEIGHT: Float = 40f
        const val STOCKPILE_ROW_HEIGHT: Float = 36f
        const val STOCKPILE_PIP_SIZE: Float = 14f
        const val QUEUE_ROW_HEIGHT: Float = 42f
        const val QUEUE_SLOT_SIZE: Float = 24f
        const val NOTICE_H: Float = 26f
        const val NOTICE_GAP: Float = 4f
        const val MENU_PAD: Float = 6f
        const val MENU_ROW_H: Float = 34f
        const val MENU_ROW_GAP: Float = 4f
        const val PAUSE_CARD_W: Float = 196f
        const val PAUSE_CARD_H: Float = 112f

        private val MENU_BORDER: Color = UiTokens.hex(0x727B81, 1f)
        private val MENU_ROW_HOVER: Color = UiTokens.hex(0x41494E, 1f)
        private val QUEUE_PROGRESS_RUST: Color = Color(UiTokens.RUST.r, UiTokens.RUST.g, UiTokens.RUST.b, 0.55f)
        private val QUEUE_COUNT_BADGE_RED: Color = UiTokens.hex(0xC92A2A, 0.96f)

        /** Deterministic slot bounds for queue rendering and click-to-cancel hit testing (UI-P002 §5.2). */
        fun computeQueueSlotRects(rect: Rectangle, lang: UiLanguage, slotCount: Int = 5): List<Rectangle> {
            val padH = UiTokens.SIDEBAR_PAD_H
            val centerY = rect.y + rect.height * 0.5f
            val labelColW = if (lang == UiLanguage.EN) 44f else 30f
            val slotSize = if (lang == UiLanguage.EN) 22f else QUEUE_SLOT_SIZE
            val slotGap = if (lang == UiLanguage.EN) 3f else 4f
            val slotY = centerY - slotSize * 0.5f
            var sx = rect.x + padH + labelColW + slotGap
            return List(slotCount) {
                val r = Rectangle(sx, slotY, slotSize, slotSize)
                sx += slotSize + slotGap
                r
            }
        }

        /** Pure deterministic layout calculation for unit tests and runtime rendering. */
        fun computeLayout(
            stageW: Float,
            stageH: Float,
            minimapWellH: Float,
            snapshot: HudSnapshot?,
            externalActionsH: Float,
        ): HudOverlayLayout {
            val sideW = UiTokens.SIDEBAR_WIDTH.coerceAtMost((stageW - UiTokens.SCREEN_MARGIN * 2f).coerceAtLeast(160f))
            val sideLeft = (stageW - sideW).coerceAtLeast(0f)
            val top = stageH

            val mmH = minimapWellH.coerceIn(sideW * 0.5f, sideW).coerceAtMost((stageH * 0.45f).coerceAtLeast(80f))
            val minimapRect = Rectangle(sideLeft, top - mmH, sideW, mmH)

            var cursorY = minimapRect.y
            val creditsRect = Rectangle(sideLeft, cursorY - CREDITS_ROW_HEIGHT, sideW, CREDITS_ROW_HEIGHT)
            cursorY = creditsRect.y

            var typeChipsRect: Rectangle? = null
            var cardRect: Rectangle? = null
            var cargoRect: Rectangle? = null
            var stockpileRect: Rectangle? = null
            var queueRect: Rectangle? = null

            val chips = snapshot?.typeChips
            if (chips != null && chips.chips.isNotEmpty()) {
                val totalBadges = chips.chips.size + if (chips.overflowCount > 0) 1 else 0
                val rows = typeChipRowCount(sideW, totalBadges)
                val chipsH = TYPE_CHIPS_PAD_V * 2f + rows * TYPE_CHIP_H + (rows - 1).coerceAtLeast(0) * TYPE_CHIP_GAP
                typeChipsRect = Rectangle(sideLeft, cursorY - chipsH, sideW, chipsH)
                cursorY = typeChipsRect.y
            }

            val card = snapshot?.selectionCard
            if (card != null) {
                val statsCount = card.statBars.size
                val statsH = if (statsCount > 0) 8f + statsCount * STAT_ROW_H + (statsCount - 1) * STAT_ROW_GAP else 0f
                val cardH = UiTokens.SIDEBAR_PAD_V * 2f + UiTokens.ICON_BOX_SIZE + statsH
                cardRect = Rectangle(sideLeft, cursorY - cardH, sideW, cardH)
                cursorY = cardRect.y
            }

            val cargo = snapshot?.cargoSection
            if (cargo != null) {
                cargoRect = Rectangle(sideLeft, cursorY - CARGO_ROW_HEIGHT, sideW, CARGO_ROW_HEIGHT)
                cursorY = cargoRect.y
            }

            val stockpile = snapshot?.stockpileSection
            if (stockpile != null) {
                stockpileRect = Rectangle(sideLeft, cursorY - STOCKPILE_ROW_HEIGHT, sideW, STOCKPILE_ROW_HEIGHT)
                cursorY = stockpileRect.y
            }

            val queue = snapshot?.queueSection
            if (queue != null) {
                queueRect = Rectangle(sideLeft, cursorY - QUEUE_ROW_HEIGHT, sideW, QUEUE_ROW_HEIGHT)
                cursorY = queueRect.y
            }

            val actionsTopY = cursorY
            val maxActionsH = (actionsTopY - UiTokens.SCREEN_MARGIN).coerceAtLeast(0f)
            val usedActionsH = if (snapshot?.showActionsSection == true) externalActionsH.coerceIn(0f, maxActionsH) else 0f
            val sidebarBottom = (actionsTopY - usedActionsH).coerceAtLeast(UiTokens.SCREEN_MARGIN)

            return HudOverlayLayout(
                sidebarLeft = sideLeft,
                sidebarWidth = sideW,
                sidebarTop = top,
                sidebarBottom = sidebarBottom,
                minimapRect = minimapRect,
                creditsRect = creditsRect,
                typeChipsRect = typeChipsRect,
                cardRect = cardRect,
                cargoRect = cargoRect,
                stockpileRect = stockpileRect,
                queueRect = queueRect,
                actionsTopY = actionsTopY,
                actionsMaxHeight = maxActionsH,
                systemBarRect = Rectangle(UiTokens.SCREEN_MARGIN, stageH - UiTokens.SCREEN_MARGIN - SYSTEM_BAR_HEIGHT, 240f, SYSTEM_BAR_HEIGHT),
            )
        }

        internal fun typeChipRowCount(sidebarWidth: Float, badgeCount: Int): Int {
            if (badgeCount <= 0) return 0
            val innerW = (sidebarWidth - UiTokens.SIDEBAR_PAD_H * 2f).coerceAtLeast(TYPE_CHIP_W)
            val perRow = ((innerW + TYPE_CHIP_GAP) / (TYPE_CHIP_W + TYPE_CHIP_GAP)).toInt().coerceAtLeast(1)
            return (badgeCount + perRow - 1) / perRow
        }

        internal fun computeTypeChipRects(sectionRect: Rectangle, section: HudTypeChipsSection): List<Rectangle> {
            val total = section.chips.size + if (section.overflowCount > 0) 1 else 0
            val innerLeft = sectionRect.x + UiTokens.SIDEBAR_PAD_H
            val innerRight = sectionRect.x + sectionRect.width - UiTokens.SIDEBAR_PAD_H
            val result = ArrayList<Rectangle>(total)
            var cx = innerLeft
            var cy = sectionRect.y + sectionRect.height - TYPE_CHIPS_PAD_V - TYPE_CHIP_H
            for (i in 0 until total) {
                val w = if (i < section.chips.size) TYPE_CHIP_W else 34f
                if (cx > innerLeft && cx + w > innerRight + 0.5f) {
                    cx = innerLeft
                    cy -= (TYPE_CHIP_H + TYPE_CHIP_GAP)
                }
                result += Rectangle(cx, cy, w, TYPE_CHIP_H)
                cx += w + TYPE_CHIP_GAP
            }
            return result
        }
    }
}
