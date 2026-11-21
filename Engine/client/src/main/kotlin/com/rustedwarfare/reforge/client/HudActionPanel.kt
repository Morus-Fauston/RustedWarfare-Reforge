package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import kotlin.math.ceil

/**
 * Redesigned HUD action list, PC hover tooltip, and battlefield top-center target-mode hint card
 * (HUD Spec §7, §8, §8A).
 *
 * - Pinned half-height common command bar (Stop S / Patrol P / Guard G) above scrollable exclusive area
 * - Optional multi-type owner subtitle ("X 的动作")
 * - 2-column bone-white keycaps with unit artwork (30×30) or command icons (24×24), top-right price/note,
 *   rust queue badge, bottom-right hotkey, bottom 4px rust progress bar, active-mode rust outline,
 *   dark cell for short funds (amber ▲ $price) / disabled
 * - Smooth pixel-based continuous ScrollPane (no row snapping, 40 ref px per wheel notch, touch flick inertia)
 * - PC hover tooltip docked to the left of the sidebar, top-aligned with hovered key, clamped on-screen
 * - Battlefield top-center 300-wide target-mode hint card for construction/rally/reclaim/repair/patrol/nuke/sandbox
 */
/**
 * Pure original Android "first-tap preview, second-tap execute" state machine (HUD Spec §9.3 & DEC-P011-001).
 *
 * - Target-mode / construction actions (`isTargetAction == true`): first tap immediately executes (enters target mode)
 *   and clears any active preview.
 * - Normal queued / instant actions (`isTargetAction == false`):
 *   - First tap caches (unitId, actionId) and shows the preview card (returns PREVIEW).
 *   - Second tap while cache still matches the same (unitId, actionId) executes the action (returns EXECUTE).
 *   - Switching primary unit, tapping outside the card, or tapping another action clears/replaces the preview.
 */
enum class TouchActionTapOutcome {
    PREVIEW,
    EXECUTE,
}

class TouchActionPreviewState {
    var previewUnitId: String? = null
        private set
    var previewActionId: String? = null
        private set

    val active: Boolean get() = previewActionId != null

    fun isPreviewing(unitId: String?, actionId: String): Boolean =
        unitId != null && previewUnitId == unitId && previewActionId == actionId

    /** Reconciles cache against the currently selected representative unit ID and available action IDs. */
    fun reconcile(currentPrimaryUnitId: String?, availableActionIds: Set<String>) {
        if (previewUnitId == null || previewActionId == null) return
        if (currentPrimaryUnitId == null || previewUnitId != currentPrimaryUnitId || previewActionId !in availableActionIds) {
            clear()
        }
    }

    /** Handles a released tap on an exclusive action key in touch mode. */
    fun onActionTap(primaryUnitId: String?, key: HudActionKeyModel): TouchActionTapOutcome {
        if (key.isTargetAction) {
            clear()
            return TouchActionTapOutcome.EXECUTE
        }
        if (primaryUnitId != null && previewUnitId == primaryUnitId && previewActionId == key.id) {
            return TouchActionTapOutcome.EXECUTE
        }
        previewUnitId = primaryUnitId
        previewActionId = key.id
        return TouchActionTapOutcome.PREVIEW
    }

    fun clear() {
        previewUnitId = null
        previewActionId = null
    }
}

internal class HudActionPanel(
    private val skin: Skin,
    private val stage: Stage,
    private val touchControls: Boolean,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val unitIconProvider: (String) -> TextureRegion?,
    private val commandIconProvider: (String) -> TextureRegion?,
    private val onCommonCommand: (String) -> Unit,
    private val onExclusiveAction: (HudActionKeyModel, Boolean) -> Unit,
    private val onDisabledFlash: (HudActionKeyModel) -> Unit,
    private val onSelectSlot: (Int) -> Unit = {},
) {
    private var snapshot = HudActionListSnapshot(false, emptyList(), null, emptyList(), emptyList())
    private var targetCard: HudTargetModeCardModel? = null
    private var anchorLeft = 0f
    private var anchorTopY = 0f
    private var anchorWidth = 212f
    private var anchorMaxHeight = 320f
    private var stageW = 960f
    private var stageH = 640f

    private var hoveredKeyId: String? = null
    private var hoveredKeyTopY: Float = 0f
    private val flashTimers = LinkedHashMap<String, Float>()
    internal val touchPreview = TouchActionPreviewState()
    private var activePrimaryUnitId: String? = null
    private var lastPopupRect: Rectangle? = null
    private var activeLanguage: UiLanguage = UiLanguage.ZH

    private val glyphLayout = GlyphLayout()

    private fun bodyFont(): BitmapFont = fontsProvider()?.body ?: fallbackFontProvider()
    private fun titleFont(): BitmapFont = fontsProvider()?.title ?: fallbackFontProvider()
    private fun tooltipTitleFont(): BitmapFont = fontsProvider()?.tooltipTitle ?: fallbackFontProvider()
    private fun commandFont(): BitmapFont = fontsProvider()?.command ?: fallbackFontProvider()
    private fun keyBoldFont(): BitmapFont = fontsProvider()?.keyBold ?: fallbackFontProvider()
    private fun smallFont(): BitmapFont = fontsProvider()?.small ?: fallbackFontProvider()
    private fun smallMonoFont(): BitmapFont = fontsProvider()?.smallMono ?: fallbackFontProvider()

    // --- 1. Pinned common command bar + owner subtitle ---
    private val commonGroup: Group = object : Group() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            if (!isVisible) return
            val white = skin.getRegion("white")
            val orig = batch.packedColor
            for (btn in commonButtons) {
                if (btn.isVisible) drawCommonKey(batch, white, btn, x, y, parentAlpha)
            }
            snapshot.ownerSubtitle?.let { owner ->
                drawTextLeft(batch, smallFont(), owner, x, y + ownerTextY, UiTokens.DIM, parentAlpha)
            }
            batch.packedColor = orig
        }
    }.apply {
        isTransform = false
        touchable = Touchable.childrenOnly
        isVisible = false
        this@HudActionPanel.stage.addActor(this)
    }

    private var ownerTextY: Float = 0f

    private class CommonButtonActor(val index: Int, private val onClick: (String) -> Unit) : Actor() {
        var model: HudCommonCommandModel? = null
        var hovered: Boolean = false
            private set

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean =
                    pointer == 0 && button == Input.Buttons.LEFT && model != null

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    val m = model ?: return
                    if (pointer == 0 && button == Input.Buttons.LEFT && x in 0f..width && y in 0f..height) {
                        onClick(m.id)
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
    }

    private val commonButtons: List<CommonButtonActor> = List(3) { idx ->
        CommonButtonActor(idx) { id ->
            touchPreview.clear()
            onCommonCommand(id)
        }.also { commonGroup.addActor(it) }
    }

    // --- 2. Scrollable 2-column exclusive keys ---
    private class KeyActor(
        var model: HudActionKeyModel,
        private val panel: HudActionPanel,
    ) : Actor() {
        var hovered: Boolean = false
            private set
        var pressed: Boolean = false
            private set
        private var pressStageX = 0f
        private var pressStageY = 0f
        private var pressButton = -1

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0) return false
                    if (button != Input.Buttons.LEFT && button != Input.Buttons.RIGHT) return false
                    if (!panel.hitsInsideScroll(event.stageX, event.stageY)) return false
                    pressStageX = event.stageX
                    pressStageY = event.stageY
                    pressButton = button
                    pressed = true
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (pointer != 0 || !pressed) return
                    val dx = event.stageX - pressStageX
                    val dy = event.stageY - pressStageY
                    if (dx * dx + dy * dy > 144f) {
                        pressed = false
                    }
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0 || button != pressButton) return
                    val wasPressed = pressed
                    pressButton = -1
                    pressed = false
                    if (!wasPressed || event.isTouchFocusCancel) return
                    val dx = event.stageX - pressStageX
                    val dy = event.stageY - pressStageY
                    if (dx * dx + dy * dy > 144f) return // scrolled past drag threshold
                    if (!panel.hitsInsideScroll(event.stageX, event.stageY)) return
                    if (x < 0f || y < 0f || x > width || y > height) return
                    val cancel = button == Input.Buttons.RIGHT
                    if (cancel) {
                        if (model.cancelEnabled) panel.onExclusiveAction(model, true)
                    } else if (panel.touchControls) {
                        val outcome = panel.touchPreview.onActionTap(panel.activePrimaryUnitId, model)
                        if (outcome == TouchActionTapOutcome.EXECUTE) {
                            if (model.enabled) {
                                panel.onExclusiveAction(model, false)
                            } else {
                                panel.triggerFlash(model.id)
                                panel.onDisabledFlash(model)
                            }
                        } else {
                            // First tap preview: keep card visible anchored to this key, do NOT execute yet
                            panel.setHoveredKey(model.id, this@KeyActor)
                        }
                    } else {
                        if (model.enabled) {
                            panel.onExclusiveAction(model, false)
                        } else {
                            panel.triggerFlash(model.id)
                            panel.onDisabledFlash(model)
                        }
                    }
                }

                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1 && !panel.touchControls) {
                        hovered = true
                        panel.setHoveredKey(model.id, this@KeyActor)
                    }
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) {
                        hovered = false
                        panel.clearHoveredKey(model.id)
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            panel.drawExclusiveKey(batch, this, parentAlpha)
        }
    }

    private class SlotActor(
        var model: HudAttachmentSlotModel,
        private val panel: HudActionPanel,
    ) : Actor() {
        var hovered: Boolean = false
            private set
        private var pressStageX = 0f
        private var pressStageY = 0f
        private var pressActive = false

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || button != Input.Buttons.LEFT) return false
                    if (!panel.hitsInsideScroll(event.stageX, event.stageY)) return false
                    pressStageX = event.stageX
                    pressStageY = event.stageY
                    pressActive = true
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (pointer != 0 || !pressActive) return
                    val dx = event.stageX - pressStageX
                    val dy = event.stageY - pressStageY
                    if (dx * dx + dy * dy > 144f) {
                        pressActive = false
                    }
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != 0 || !pressActive) return
                    pressActive = false
                    if (event.isTouchFocusCancel) return
                    val dx = event.stageX - pressStageX
                    val dy = event.stageY - pressStageY
                    if (dx * dx + dy * dy > 144f) return
                    if (!panel.hitsInsideScroll(event.stageX, event.stageY)) return
                    if (x < 0f || y < 0f || x > width || y > height) return
                    panel.touchPreview.clear()
                    panel.onSelectSlot(model.slotNumber)
                }

                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1 && !panel.touchControls) {
                        hovered = true
                        panel.setHoveredKey("select-slot:${model.slotNumber}", this@SlotActor)
                    }
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) {
                        hovered = false
                        panel.clearHoveredKey("select-slot:${model.slotNumber}")
                    }
                }
            })
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            panel.drawSlotKey(batch, this, parentAlpha)
        }
    }

    private val keyContainer = Group().apply { isTransform = false }
    private val slotActors = LinkedHashMap<Int, SlotActor>()
    private val keyActors = LinkedHashMap<String, KeyActor>()
    private var gridContentW = 212f
    private var gridContentH = 0f

    private val scrollContent = object : Group() {
        init {
            isTransform = false
            addActor(keyContainer)
        }
        override fun getWidth(): Float = gridContentW
        override fun getHeight(): Float = gridContentH
    }

    /** Pixel-smooth continuous ScrollPane: 40 ref units per wheel notch, touch flick inertia, no overscroll. */
    private val scrollPane = object : ScrollPane(
        scrollContent,
        ScrollPaneStyle().apply {
            vScroll = skin.newDrawable("white", Color(0.10f, 0.12f, 0.13f, 0.65f)).apply { minWidth = SCROLLBAR_W }
            vScrollKnob = skin.newDrawable("white", UiTokens.LINE).apply { minWidth = SCROLLBAR_W; minHeight = 24f }
        },
    ) {
        override fun getMouseWheelY(): Float = 40f
    }.apply {
        setScrollingDisabled(true, false)
        setFadeScrollBars(false)
        setOverscroll(false, false)
        setSmoothScrolling(true)
        setFlickScroll(touchControls)
        setCancelTouchFocus(true)
        setVariableSizeKnobs(true)
        isVisible = false
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                this@HudActionPanel.stage.scrollFocus = this@apply
                return true
            }
        })
        this@HudActionPanel.stage.addActor(this)
    }

    // --- 3. PC Hover Tooltip & Battlefield Top-Center Target Mode Hint Card ---
    private val floatingOverlay = object : Actor() {
        init {
            touchable = Touchable.disabled
        }
        override fun draw(batch: Batch, parentAlpha: Float) {
            val white = skin.getRegion("white")
            val orig = batch.packedColor
            targetCard?.let { card ->
                drawTargetModeCard(batch, white, card, parentAlpha)
            }
            lastPopupRect = null
            val activeTipKeyId = if (touchControls) touchPreview.previewActionId else hoveredKeyId
            if (activeTipKeyId != null && scrollPane.isVisible) {
                val slotNum = if (!touchControls && activeTipKeyId.startsWith("select-slot:")) {
                    activeTipKeyId.removePrefix("select-slot:").toIntOrNull()
                } else null
                val actor: Actor? = if (slotNum != null) slotActors[slotNum] else keyActors[activeTipKeyId]
                if (actor != null) {
                    val bottomPt = actor.localToStageCoordinates(Vector2(0f, 0f))
                    val topY = bottomPt.y + actor.height
                    val visibleInScroll = topY > scrollPane.y + 1f && bottomPt.y < scrollPane.y + scrollPane.height - 1f
                    if (visibleInScroll) {
                        hoveredKeyTopY = topY
                        val rawTip = if (slotNum != null) {
                            snapshot.attachmentSlots.firstOrNull { it.slotNumber == slotNum }?.tooltip
                        } else {
                            snapshot.exclusiveKeys.firstOrNull { it.id == activeTipKeyId }?.tooltip
                        }
                        if (rawTip != null) {
                            val tipToDraw = if (touchControls) {
                                val keyModel = snapshot.exclusiveKeys.firstOrNull { it.id == activeTipKeyId }
                                val touchFoot = if (keyModel?.enabled == true) {
                                    UiStrings.get("touchPreviewHint", activeLanguage)
                                } else {
                                    rawTip.footnote
                                }
                                rawTip.copy(footnote = touchFoot)
                            } else {
                                rawTip
                            }
                            lastPopupRect = drawHoverTooltip(batch, white, tipToDraw, parentAlpha)
                        }
                    }
                }
            }
            batch.packedColor = orig
        }
    }.also(stage::addActor)

    val occupiedHeight: Float
        get() {
            if (!commonGroup.isVisible && !scrollPane.isVisible) return 0f
            return totalUsedHeight
        }

    private var totalUsedHeight: Float = 0f

    fun triggerFlash(keyId: String) {
        flashTimers[keyId] = 0.25f
    }

    fun tickEffects(deltaSeconds: Float) {
        if (flashTimers.isEmpty() || deltaSeconds <= 0f) return
        val iter = flashTimers.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val rem = e.value - deltaSeconds
            if (rem <= 0f) iter.remove() else e.setValue(rem)
        }
    }

    fun scrollTarget(hit: Actor?): Actor? =
        scrollPane.takeIf { it.isVisible && hit?.isDescendantOf(it) == true }

    fun toFront() {
        if (commonGroup.isVisible) commonGroup.toFront()
        if (scrollPane.isVisible) scrollPane.toFront()
        floatingOverlay.toFront()
    }

    /** Clears active touch action preview card (e.g. tap outside card, unit switch, menu/pause open). */
    fun clearTouchPreview() {
        touchPreview.clear()
    }

    /** True if a touch preview tooltip card is currently open. */
    val hasTouchPreview: Boolean get() = touchControls && touchPreview.active

    /** True if [stageX],[stageY] falls inside the floating touch preview card. */
    fun isPointInTouchPreviewCard(stageX: Float, stageY: Float): Boolean =
        touchControls && touchPreview.active && lastPopupRect?.contains(stageX, stageY) == true

    /** True if [stageX],[stageY] hits any visible exclusive action key inside the scroll viewport. */
    fun isPointOnExclusiveKey(stageX: Float, stageY: Float): Boolean {
        if (!scrollPane.isVisible || !hitsInsideScroll(stageX, stageY)) return false
        val pt = Vector2()
        for (actor in keyActors.values) {
            actor.stageToLocalCoordinates(pt.set(stageX, stageY))
            if (pt.x in 0f..actor.width && pt.y in 0f..actor.height) return true
        }
        return false
    }

    fun containsPoint(x: Float, y: Float): Boolean {
        if (commonGroup.isVisible && x >= commonGroup.x && x <= commonGroup.x + commonGroup.width &&
            y >= commonGroup.y && y <= commonGroup.y + commonGroup.height
        ) return true
        if (scrollPane.isVisible && x >= scrollPane.x && x <= scrollPane.x + scrollPane.width &&
            y >= scrollPane.y && y <= scrollPane.y + scrollPane.height
        ) return true
        return false
    }

    private fun hitsInsideScroll(stageX: Float, stageY: Float): Boolean {
        if (!scrollPane.isVisible) return false
        val rightLimit = scrollPane.x + scrollPane.width - (if (scrollPane.isScrollY) SCROLLBAR_W else 0f)
        return stageX >= scrollPane.x && stageX <= rightLimit &&
            stageY >= scrollPane.y && stageY <= scrollPane.y + scrollPane.height
    }

    private fun setHoveredKey(keyId: String, actor: Actor) {
        hoveredKeyId = keyId
        val pt = actor.localToStageCoordinates(Vector2(0f, actor.height))
        hoveredKeyTopY = pt.y
    }

    private fun clearHoveredKey(keyId: String) {
        if (hoveredKeyId == keyId) hoveredKeyId = null
    }

    /** Updates layout, models, and visibility inside the adaptive right sidebar. */
    fun update(
        stageWidth: Float,
        stageHeight: Float,
        sidebarLeft: Float,
        actionsTopY: Float,
        sidebarWidth: Float,
        actionsMaxHeight: Float,
        actionSnapshot: HudActionListSnapshot?,
        targetModeCard: HudTargetModeCardModel?,
        primaryUnitId: String? = null,
        language: UiLanguage = UiStrings.currentLanguage,
    ) {
        activePrimaryUnitId = primaryUnitId
        activeLanguage = language
        if (actionSnapshot == null || targetModeCard != null) {
            touchPreview.clear()
        } else {
            touchPreview.reconcile(primaryUnitId, actionSnapshot.exclusiveKeys.map { it.id }.toSet())
        }
        stageW = stageWidth.coerceAtLeast(1f)
        stageH = stageHeight.coerceAtLeast(1f)
        targetCard = targetModeCard
        floatingOverlay.setBounds(0f, 0f, stageW, stageH)

        if (actionSnapshot == null || actionSnapshot.isEmpty) {
            snapshot = HudActionListSnapshot(false, emptyList(), null, emptyList(), emptyList())
            commonGroup.isVisible = false
            scrollPane.isVisible = false
            if (stage.scrollFocus === scrollPane) stage.scrollFocus = null
            hoveredKeyId = null
            totalUsedHeight = 0f
            return
        }

        val prevIds = snapshot.exclusiveKeys.map { it.id }
        val nextIds = actionSnapshot.exclusiveKeys.map { it.id }
        val prevSlotNums = snapshot.attachmentSlots.map { it.slotNumber }
        val nextSlotNums = actionSnapshot.attachmentSlots.map { it.slotNumber }
        val structureChanged = prevIds != nextIds || prevSlotNums != nextSlotNums
        snapshot = actionSnapshot

        anchorLeft = sidebarLeft + UiTokens.SIDEBAR_PAD_H
        anchorTopY = actionsTopY
        anchorWidth = (sidebarWidth - UiTokens.SIDEBAR_PAD_H * 2f).coerceAtLeast(140f)
        anchorMaxHeight = actionsMaxHeight.coerceAtLeast(0f)

        val padV = UiTokens.SIDEBAR_PAD_V
        var cursorY = anchorTopY - padV

        // 1. Pinned common command bar + optional owner subtitle
        val commonH = if (actionSnapshot.showCommonBar) UiTokens.commonKeyHeight(touchControls) else 0f
        val ownerH = if (actionSnapshot.ownerSubtitle != null) OWNER_LINE_H else 0f
        val pinnedH = commonH + (if (commonH > 0f && ownerH > 0f) 4f else 0f) + ownerH

        if (pinnedH > 0f) {
            val groupY = cursorY - pinnedH
            commonGroup.setBounds(anchorLeft, groupY, anchorWidth, pinnedH)
            commonGroup.isVisible = true
            if (actionSnapshot.showCommonBar) {
                val gap = UiTokens.KEY_GAP
                val btnW = ((anchorWidth - gap * 2f) / 3f).coerceAtLeast(36f)
                val btnY = pinnedH - commonH
                for (i in commonButtons.indices) {
                    val m = actionSnapshot.commonCommands.getOrNull(i)
                    val btn = commonButtons[i]
                    btn.model = m
                    btn.isVisible = m != null
                    if (m != null) {
                        btn.setBounds(i * (btnW + gap), btnY, btnW, commonH)
                    }
                }
            } else {
                commonButtons.forEach { it.isVisible = false; it.model = null }
            }
            ownerTextY = ownerH * 0.5f
            cursorY = groupY - (if (actionSnapshot.exclusiveKeys.isNotEmpty() || actionSnapshot.attachmentSlots.isNotEmpty()) 6f else 0f)
        } else {
            commonGroup.isVisible = false
            commonButtons.forEach { it.isVisible = false; it.model = null }
        }

        // 2. Optional 3x2 attachment slot keys + two-column exclusive keys inside smooth pixel ScrollPane
        val slots = actionSnapshot.attachmentSlots
        val keys = actionSnapshot.exclusiveKeys
        if (slots.isNotEmpty() || keys.isNotEmpty()) {
            val slotH = if (touchControls) UiTokens.SLOT_KEY_HEIGHT_TOUCH else UiTokens.SLOT_KEY_HEIGHT_PC
            val keyH = UiTokens.actionKeyHeight(touchControls)
            val gap = UiTokens.KEY_GAP
            val slotRows = if (slots.isNotEmpty()) (slots.size + 2) / 3 else 0
            val slotsBlockH = if (slotRows > 0) slotRows * slotH + (slotRows - 1) * gap else 0f
            val sectionGap = if (slotRows > 0 && keys.isNotEmpty()) gap + 2f else 0f
            val keyRows = if (keys.isNotEmpty()) (keys.size + 1) / 2 else 0
            val keysBlockH = if (keyRows > 0) keyRows * keyH + (keyRows - 1) * gap else 0f
            val rawGridH = slotsBlockH + sectionGap + keysBlockH
            val availForScroll = (cursorY - UiTokens.SCREEN_MARGIN - padV).coerceAtLeast(0f)
            val needsScroll = rawGridH > availForScroll + 0.5f
            val scrollH = minOf(rawGridH, availForScroll)
            val usableW = if (needsScroll) (anchorWidth - SCROLLBAR_W - 3f).coerceAtLeast(120f) else anchorWidth
            val slotW = ((usableW - gap * 2f) / 3f).coerceAtLeast(42f)
            val colW = ((usableW - gap) * 0.5f).coerceAtLeast(56f)

            gridContentW = usableW
            gridContentH = rawGridH
            scrollContent.setSize(gridContentW, gridContentH)
            keyContainer.setSize(gridContentW, gridContentH)

            if (structureChanged) {
                hoveredKeyId = null
                keyContainer.clearChildren()
                slotActors.clear()
                keyActors.clear()
                for (s in slots) {
                    val actor = SlotActor(s, this)
                    slotActors[s.slotNumber] = actor
                    keyContainer.addActor(actor)
                }
                for (k in keys) {
                    val actor = KeyActor(k, this)
                    keyActors[k.id] = actor
                    keyContainer.addActor(actor)
                }
            } else {
                for (s in slots) {
                    slotActors[s.slotNumber]?.model = s
                }
                for (k in keys) {
                    keyActors[k.id]?.model = k
                }
            }

            slots.forEachIndexed { idx, s ->
                val col = idx % 3
                val row = idx / 3
                val sx = col * (slotW + gap)
                val sy = rawGridH - (row + 1) * slotH - row * gap
                slotActors[s.slotNumber]?.setBounds(sx, sy, slotW, slotH)
            }
            val keysTopY = rawGridH - slotsBlockH - sectionGap
            keys.forEachIndexed { idx, k ->
                val col = idx % 2
                val row = idx / 2
                val kx = col * (colW + gap)
                val ky = keysTopY - (row + 1) * keyH - row * gap
                keyActors[k.id]?.setBounds(kx, ky, colW, keyH)
            }

            scrollPane.setBounds(anchorLeft, cursorY - scrollH, anchorWidth, scrollH)
            scrollPane.isVisible = scrollH > 4f
            scrollPane.setFlickScroll(touchControls)
            if (structureChanged) {
                scrollPane.scrollY = 0f
                scrollPane.updateVisualScroll()
            }
            scrollPane.layout()
            cursorY -= scrollH
        } else {
            scrollPane.isVisible = false
            if (stage.scrollFocus === scrollPane) stage.scrollFocus = null
            hoveredKeyId = null
        }

        totalUsedHeight = (anchorTopY - cursorY + padV).coerceAtMost(anchorMaxHeight)
    }

    fun drawnActions(): List<DrawnAction> {
        val result = mutableListOf<DrawnAction>()
        if (commonGroup.isVisible) {
            for (btn in commonButtons) {
                val m = btn.model ?: continue
                if (btn.isVisible) {
                    result += DrawnAction(m.id, btn, m.label, enabled = m.enabled)
                }
            }
        }
        if (scrollPane.isVisible) {
            result += DrawnAction("action-list", scrollPane, "Actions", enabled = true)
            if (scrollPane.isScrollY) {
                result += DrawnAction(
                    "action-list-scrollbar",
                    scrollPane,
                    when {
                        scrollPane.visualScrollPercentY >= 0.99f -> "at-bottom"
                        scrollPane.visualScrollPercentY <= 0.01f -> "at-top"
                        else -> "scrollable"
                    },
                    localBounds = Rectangle(scrollPane.width - SCROLLBAR_W, 0f, SCROLLBAR_W, scrollPane.height),
                )
            }
            val pt = Vector2()
            for (slot in snapshot.attachmentSlots) {
                val actor = slotActors[slot.slotNumber] ?: continue
                actor.localToStageCoordinates(pt.setZero())
                if (pt.y < scrollPane.y - 1f || pt.y + actor.height > scrollPane.y + scrollPane.height + 1f) continue
                result += DrawnAction("select-slot:${slot.slotNumber}", actor, slot.title, enabled = true)
                result += DrawnAction("unit-action:selectSlot${slot.slotNumber}", actor, slot.title, enabled = true)
            }
            for (key in snapshot.exclusiveKeys) {
                val actor = keyActors[key.id] ?: continue
                actor.localToStageCoordinates(pt.setZero())
                // Report only cards fully visible inside scroll viewport
                if (pt.y < scrollPane.y - 1f || pt.y + actor.height > scrollPane.y + scrollPane.height + 1f) continue
                result += DrawnAction(key.id, actor, key.title, enabled = key.enabled)
                key.cancelId?.let { cid ->
                    result += DrawnAction(cid, actor, key.title, button = "right", enabled = key.cancelEnabled)
                }
            }
        }
        return result
    }

    private fun drawCommonKey(batch: Batch, white: TextureRegion, btn: CommonButtonActor, originX: Float, originY: Float, alpha: Float) {
        val m = btn.model ?: return
        val x = originX + btn.x
        val y = originY + btn.y
        val w = btn.width
        val h = btn.height
        val bg = when {
            m.active -> UiTokens.BONE_HI
            btn.hovered -> CELL_HOVER
            else -> UiTokens.CELL
        }
        val fg = if (m.active) UiTokens.INK else UiTokens.TEXT

        fillRect(batch, white, x, y, w, h, bg, alpha)
        // Bottom 2px keycap bevel shadow
        fillRect(batch, white, x, y, w, 2f, KEY_SHADOW, alpha)
        if (m.active) {
            strokeRectInset(batch, white, x, y, w, h, 2f, UiTokens.RUST, alpha)
        } else {
            strokeRect(batch, white, x, y, w, h, UiTokens.LINE, alpha * 0.55f)
        }

        val cy = y + h * 0.5f + 0.5f
        val iconRegion = commandIconProvider(m.iconName)
        val iconSz = 13f
        glyphLayout.setText(commandFont(), m.label)
        val labelW = glyphLayout.width
        val hasHk = m.hotkey.isNotEmpty()
        val hkW = if (hasHk) {
            glyphLayout.setText(smallMonoFont(), m.hotkey)
            glyphLayout.width + 4f
        } else 0f
        val totalContentW = (if (iconRegion != null) iconSz + 4f else 0f) + labelW + hkW
        var cx = x + ((w - totalContentW) * 0.5f).coerceAtLeast(4f)

        if (iconRegion != null) {
            batch.setColor(fg.r, fg.g, fg.b, fg.a * alpha)
            batch.draw(iconRegion, cx, cy - iconSz * 0.5f, iconSz, iconSz)
            cx += iconSz + 4f
        }
        drawTextLeft(batch, commandFont(), m.label, cx, cy, fg, alpha)
        if (hasHk) {
            val hkColor = if (m.active) UiTokens.INK else UiTokens.DIM
            drawTextLeft(batch, smallMonoFont(), m.hotkey, cx + labelW + 4f, cy - 0.5f, hkColor, alpha * 0.85f)
        }
    }

    private fun drawSlotKey(batch: Batch, actor: SlotActor, alpha: Float) {
        val m = actor.model
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val x = actor.x
        val y = actor.y
        val w = actor.width
        val h = actor.height

        val bg = when {
            m.selected -> UiTokens.VALUE
            actor.hovered -> CELL_HOVER
            else -> UiTokens.CELL
        }
        fillRect(batch, white, x, y, w, h, bg, alpha)
        if (!m.selected) {
            fillRect(batch, white, x, y, w, 2f, KEY_SHADOW, alpha)
        }

        // Top-left 12pt slot number (Spec §7.4)
        val numColor = if (m.selected) UiTokens.WELL else UiTokens.DIM
        drawTextLeft(batch, smallMonoFont(), m.slotNumber.toString(), x + 5f, y + h - 8f, numColor, alpha)

        // Center icon: 24x24 unit artwork when occupied, 22x22 rw-slot-empty (0.6 alpha) when empty (Spec §7.4)
        val cx = x + w * 0.5f
        val cy = y + h * 0.5f - 1f
        if (m.empty) {
            val emptyIcon = commandIconProvider("rw-slot-empty")
            if (emptyIcon != null) {
                val sz = 22f
                val ic = if (m.selected) UiTokens.WELL else UiTokens.VALUE
                batch.setColor(ic.r, ic.g, ic.b, ic.a * alpha * 0.6f)
                batch.draw(emptyIcon, cx - sz * 0.5f, cy - sz * 0.5f, sz, sz)
            }
        } else {
            val unitReg = unitIconProvider(m.typeId)
            if (unitReg != null) {
                val maxSz = 24f
                val asp = unitReg.regionWidth.toFloat() / unitReg.regionHeight.coerceAtLeast(1).toFloat()
                val dw = if (asp >= 1f) maxSz else maxSz * asp
                val dh = if (asp >= 1f) maxSz / asp else maxSz
                batch.setColor(1f, 1f, 1f, alpha)
                batch.draw(unitReg, cx - dw * 0.5f, cy - dh * 0.5f, dw, dh)
            }
            if (m.progress < 0.999f) {
                val pw = w * m.progress.coerceIn(0f, 1f)
                if (pw > 0.5f) fillRect(batch, white, x, y, pw, 3f, UiTokens.RUST, alpha)
            }
        }
        batch.packedColor = orig
    }

    private fun drawExclusiveKey(batch: Batch, actor: KeyActor, alpha: Float) {
        val m = actor.model
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val x = actor.x
        val isPressed = actor.pressed && m.enabled
        val y = actor.y - if (isPressed) 1f else 0f
        val w = actor.width
        val h = actor.height

        val flashing = (flashTimers[m.id] ?: 0f) > 0f
        val isBone = m.state == HudActionKeyState.READY || m.state == HudActionKeyState.ACTIVE_MODE
        val bg = when {
            flashing && !isBone -> CELL_FLASH
            m.state == HudActionKeyState.ACTIVE_MODE -> UiTokens.BONE_HI
            m.state == HudActionKeyState.READY && actor.hovered -> UiTokens.BONE_HI
            m.state == HudActionKeyState.READY -> UiTokens.BONE
            else -> UiTokens.CELL
        }
        val fg = if (isBone) UiTokens.INK else DISABLED_TEXT

        fillRect(batch, white, x, y, w, h, bg, alpha)
        // Bottom 3px physical keycap shadow bevel (hidden when pressed, Spec §7.3)
        if (!isPressed) {
            fillRect(batch, white, x, y, w, 3f, KEY_SHADOW, alpha)
        }

        // Active mode or hover inner outline (Spec §7.3)
        val isTouchPreviewed = touchControls && touchPreview.isPreviewing(activePrimaryUnitId, m.id)
        if (m.state == HudActionKeyState.ACTIVE_MODE || isTouchPreviewed) {
            strokeRectInset(batch, white, x + 1f, y + 1f, w - 2f, h - 2f, 2f, UiTokens.RUST, alpha)
        } else if (m.state == HudActionKeyState.READY && actor.hovered) {
            strokeRectInset(batch, white, x + 1f, y + 1f, w - 2f, h - 2f, 2f, UiTokens.INK, alpha)
        }

        // Top-left icon: 28×28 unit artwork OR 22×22 command icon (tinted ink / dim)
        val iconPadL = 7f
        val iconTopY = y + h - 5f
        val unitReg = m.unitIconTypeId?.let(unitIconProvider)
        if (unitReg != null) {
            val maxSz = 26f
            val asp = unitReg.regionWidth.toFloat() / unitReg.regionHeight.coerceAtLeast(1).toFloat()
            val dw = if (asp >= 1f) maxSz else maxSz * asp
            val dh = if (asp >= 1f) maxSz / asp else maxSz
            if (isBone) batch.setColor(1f, 1f, 1f, alpha) else batch.setColor(0.65f, 0.68f, 0.70f, alpha)
            batch.draw(unitReg, x + iconPadL + (maxSz - dw) * 0.5f, iconTopY - maxSz + (maxSz - dh) * 0.5f, dw, dh)
        } else {
            val cmdName = m.commandIconName ?: "rw-info"
            val cmdReg = commandIconProvider(cmdName)
            if (cmdReg != null) {
                val sz = 22f
                val icColor = if (isBone) UiTokens.INK else DISABLED_ICON
                batch.setColor(icColor.r, icColor.g, icColor.b, icColor.a * alpha)
                batch.draw(cmdReg, x + iconPadL, iconTopY - sz - 1f, sz, sz)
            }
        }

        // Top-right price or note (amber ▲ $price when short funds!)
        val rightX = x + w - 7f
        val topTextY = y + h - 12f
        if (m.priceText != null) {
            if (m.state == HudActionKeyState.SHORT_FUNDS) {
                drawTextRight(batch, smallMonoFont(), "▲ ${m.priceText}", rightX, topTextY, UiTokens.AMBER, alpha)
            } else {
                drawTextRight(batch, smallMonoFont(), m.priceText, rightX, topTextY, fg, alpha)
            }
        } else if (m.noteText != null) {
            val noteColor = if (isBone) NOTE_INK else UiTokens.DIM
            drawTextRight(batch, smallFont(), m.noteText, rightX, topTextY, noteColor, alpha)
        }

        // Bottom-right queue badge (rust pill) & hotkey
        var bottomRightLimit = rightX
        if (m.queueBadgeCount > 0) {
            val badgeText = m.queueBadgeCount.toString()
            glyphLayout.setText(smallMonoFont(), badgeText)
            val bw = (glyphLayout.width + 7f).coerceAtLeast(17f)
            val bh = 16f
            val bx = rightX - bw
            val by = y + 7f
            fillRect(batch, white, bx, by, bw, bh, UiTokens.RUST, alpha)
            drawTextCenter(batch, smallMonoFont(), badgeText, bx + bw * 0.5f, by + bh * 0.5f, Color.WHITE, alpha)
            bottomRightLimit = bx - 4f
        }
        if (m.hotkeyLabel != null) {
            val hkColor = if (isBone) UiTokens.INK else UiTokens.DIM
            glyphLayout.setText(smallMonoFont(), m.hotkeyLabel)
            drawTextRight(batch, smallMonoFont(), m.hotkeyLabel, bottomRightLimit, y + 13f, hkColor, alpha * 0.65f)
            bottomRightLimit -= (glyphLayout.width + 4f)
        }

        // Bottom-left bold action name (ellipsized cleanly)
        val availNameW = (bottomRightLimit - (x + 7f)).coerceAtLeast(24f)
        val titleDraw = ellipsize(keyBoldFont(), m.title, availNameW)
        drawTextLeft(batch, keyBoldFont(), titleDraw, x + 7f, y + 13f, fg, alpha)

        // Bottom 4px rust progress bar when in-progress (Spec §7.3)
        m.progress?.let { p ->
            val pw = (w * p.coerceIn(0f, 1f))
            if (pw > 0.5f) {
                fillRect(batch, white, x, y, pw, 4f, UiTokens.RUST, alpha)
            }
        }

        batch.packedColor = orig
    }

    private fun drawHoverTooltip(batch: Batch, white: TextureRegion, tip: HudActionTooltipModel, alpha: Float): Rectangle {
        val tipW = UiTokens.TOOLTIP_WIDTH
        val padH = 12f
        val padV = 10f
        val innerW = tipW - padH * 2f

        // Measure dynamic height
        val titleH = 20f
        val descLines = if (tip.description.isNotBlank()) wrapText(smallFont(), tip.description, innerW, 3) else emptyList()
        val descH = if (descLines.isNotEmpty()) 6f + descLines.size * 16f + 6f else 4f
        val rowsH = if (tip.rows.isNotEmpty()) 8f + tip.rows.size * 17f else 0f
        val footH = if (tip.footnote.isNotBlank()) 10f + 15f else 0f
        val tipH = padV * 2f + titleH + descH + rowsH + footH

        val tipX = (anchorLeft - UiTokens.SIDEBAR_PAD_H - 8f - tipW).coerceAtLeast(8f)
        val desiredBottom = hoveredKeyTopY - tipH
        val tipY = desiredBottom.coerceIn(UiTokens.SCREEN_MARGIN, (stageH - UiTokens.SCREEN_MARGIN - tipH).coerceAtLeast(UiTokens.SCREEN_MARGIN))

        fillRect(batch, white, tipX, tipY, tipW, tipH, UiTokens.SOLID, alpha)
        strokeRect(batch, white, tipX, tipY, tipW, tipH, POPUP_BORDER, alpha)

        var cy = tipY + tipH - padV - 9f
        // Title + right-aligned price
        tip.priceText?.let { p ->
            drawTextRight(batch, smallMonoFont(), p, tipX + tipW - padH, cy, UiTokens.TEXT, alpha)
        }
        val titleMaxW = if (tip.priceText != null) innerW - 58f else innerW
        drawTextLeft(batch, tooltipTitleFont(), ellipsize(tooltipTitleFont(), tip.title, titleMaxW), tipX + padH, cy, UiTokens.TEXT, alpha)
        cy -= 17f

        // Description lines
        if (descLines.isNotEmpty()) {
            cy -= 3f
            for (line in descLines) {
                drawTextLeft(batch, smallFont(), line, tipX + padH, cy, DESC_TEXT, alpha)
                cy -= 16f
            }
            cy -= 3f
        }

        // Key-value rows under hairline
        if (tip.rows.isNotEmpty()) {
            fillRect(batch, white, tipX + padH, cy + 4f, innerW, 1f, UiTokens.HAIR, alpha)
            cy -= 6f
            for (row in tip.rows) {
                drawTextLeft(batch, smallFont(), row.label, tipX + padH, cy, UiTokens.DIM, alpha)
                val vColor = if (row.isWarning) UiTokens.AMBER else UiTokens.TEXT
                val vFont = if (row.value.any { it.code > 127 }) smallFont() else smallMonoFont()
                drawTextRight(batch, vFont, row.value, tipX + tipW - padH, cy, vColor, alpha)
                cy -= 17f
            }
        }

        // Footnote
        if (tip.footnote.isNotBlank()) {
            cy -= 4f
            val isTouchHint = touchControls && tip.footnote == UiStrings.get("touchPreviewHint", activeLanguage)
            val footColor = if (isTouchHint) UiTokens.RUST else UiTokens.DIM
            val footFont = if (isTouchHint) keyBoldFont() else smallFont()
            drawTextLeft(batch, footFont, ellipsize(footFont, tip.footnote, innerW), tipX + padH, cy, footColor, alpha)
        }
        return Rectangle(tipX, tipY, tipW, tipH)
    }

    private fun drawTargetModeCard(batch: Batch, white: TextureRegion, card: HudTargetModeCardModel, alpha: Float) {
        val cardW = UiTokens.PLACEMENT_CARD_WIDTH
        val padH = 12f
        val padV = 9f
        val innerW = cardW - padH * 2f

        val descLines = if (card.description.isNotBlank()) wrapText(smallFont(), card.description, innerW, 2) else emptyList()
        val cardH = padV * 2f + 20f + (if (descLines.isNotEmpty()) 4f + descLines.size * 16f + 6f else 6f) + 22f

        // Centered over battlefield area (left of 232px sidebar) per Spec §8.2
        val battlefieldW = (stageW - UiTokens.SIDEBAR_WIDTH).coerceAtLeast(cardW + 16f)
        val centeredX = (battlefieldW - cardW) * 0.5f
        val maxSafeX = (battlefieldW - cardW - UiTokens.SCREEN_MARGIN).coerceAtLeast(UiTokens.SCREEN_MARGIN)
        val cardX = centeredX.coerceIn(UiTokens.SCREEN_MARGIN, maxSafeX)
        val cardY = stageH - UiTokens.SCREEN_MARGIN - cardH

        fillRect(batch, white, cardX, cardY, cardW, cardH, UiTokens.SOLID, alpha)
        strokeRect(batch, white, cardX, cardY, cardW, cardH, POPUP_BORDER, alpha)

        var cy = cardY + cardH - padV - 9f
        card.rightValue?.let { rv ->
            val rvFont = if (rv.any { it.code > 127 }) smallFont() else smallMonoFont()
            drawTextRight(batch, rvFont, rv, cardX + cardW - padH, cy, UiTokens.TEXT, alpha)
        }
        val titleMaxW = if (card.rightValue != null) innerW - 72f else innerW
        drawTextLeft(batch, tooltipTitleFont(), ellipsize(tooltipTitleFont(), card.title, titleMaxW), cardX + padH, cy, UiTokens.TEXT, alpha)
        cy -= 18f

        for (line in descLines) {
            drawTextLeft(batch, smallFont(), line, cardX + padH, cy, DESC_TEXT, alpha)
            cy -= 16f
        }

        // Bottom hairline + status (left) & cancel hint (right)
        val footerY = cardY + padV + 7f
        fillRect(batch, white, cardX + padH, footerY + 11f, innerW, 1f, UiTokens.HAIR, alpha)
        val statusColor = if (card.statusIsWarning) UiTokens.AMBER else UiTokens.VALUE
        drawTextLeft(batch, smallFont(), card.statusText, cardX + padH, footerY, statusColor, alpha)
        drawTextRight(batch, smallFont(), card.cancelHint, cardX + cardW - padH, footerY, UiTokens.DIM, alpha)
    }

    private fun wrapText(font: BitmapFont, text: String, maxWidth: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>(maxLines)
        var remaining = text.trim()
        while (remaining.isNotEmpty() && out.size < maxLines) {
            glyphLayout.setText(font, remaining)
            if (glyphLayout.width <= maxWidth) {
                out += remaining
                break
            }
            if (out.size == maxLines - 1) {
                out += ellipsize(font, remaining, maxWidth)
                break
            }
            var cut = remaining.length - 1
            while (cut > 1) {
                glyphLayout.setText(font, remaining.substring(0, cut))
                if (glyphLayout.width <= maxWidth) break
                cut--
            }
            out += remaining.substring(0, cut).trimEnd()
            remaining = remaining.substring(cut).trimStart()
        }
        return out
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

    private fun strokeRectInset(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, t: Float, color: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(color.r, color.g, color.b, color.a * alpha)
        batch.draw(white, x, y, w, t)
        batch.draw(white, x, y + h - t, w, t)
        batch.draw(white, x, y, t, h)
        batch.draw(white, x + w - t, y, t, h)
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

    companion object {
        const val SCROLLBAR_W: Float = 6f
        const val OWNER_LINE_H: Float = 16f

        private val POPUP_BORDER: Color = UiTokens.hex(0x727B81, 1f)
        private val KEY_SHADOW: Color = Color(0f, 0f, 0f, 0.35f)
        private val CELL_HOVER: Color = UiTokens.hex(0x41494E, 1f)
        private val CELL_FLASH: Color = UiTokens.hex(0x4D565C, 1f)
        private val DISABLED_TEXT: Color = UiTokens.hex(0xB4BBBF, 1f)
        private val DISABLED_ICON: Color = UiTokens.hex(0x8C959A, 1f)
        private val NOTE_INK: Color = UiTokens.hex(0x5D5A52, 1f)
        private val DESC_TEXT: Color = UiTokens.hex(0xC8CFD3, 1f)
    }
}
