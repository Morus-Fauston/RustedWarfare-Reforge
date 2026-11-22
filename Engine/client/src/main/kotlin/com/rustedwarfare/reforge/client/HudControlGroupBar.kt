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

/**
 * Pure control-group slot projection & touch 3-stage hold state machine (HUD Spec §10 & DEC-P011-001).
 *
 * Original Android timings: 50 / 100 frame units at 60 units/sec => 0.83s / 1.67s.
 * - Release < 0.83s: short tap (recall if non-empty, save current selection if empty)
 * - Release in [0.83s, 1.67s]: append current selection to group and recall
 * - Release > 1.67s: replace group with current selection
 */
enum class TouchGroupHoldStage {
    NONE,
    SHORT,
    APPEND,
    REPLACE,
}

enum class TouchGroupReleaseOutcome {
    RECALL,
    SAVE_EMPTY,
    APPEND_AND_RECALL,
    REPLACE,
    NONE,
}

data class HudControlGroupSlotModel(
    val groupIndex: Int,
    val memberCount: Int,
    val empty: Boolean,
)

object HudControlGroupLogic {
    /** Canonical display order: 1..9 followed by 0 (matching standard keyboard row and Spec §10). */
    val GROUP_ORDER: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
    val TOUCH_PINNED_GROUPS: Set<Int> = setOf(1, 2, 3)

    /** DEC-P011-001 exact thresholds: 50/60s = 0.8333s (spec 0.83s), 100/60s = 1.6667s (spec 1.67s). */
    const val APPEND_THRESHOLD_SEC: Float = 0.83f
    const val REPLACE_THRESHOLD_SEC: Float = 1.67f

    fun visibleSlots(touchControls: Boolean, countOf: (Int) -> Int): List<HudControlGroupSlotModel> {
        val out = ArrayList<HudControlGroupSlotModel>(10)
        for (idx in GROUP_ORDER) {
            val count = countOf(idx).coerceAtLeast(0)
            if (touchControls) {
                if (idx in TOUCH_PINNED_GROUPS || count > 0) {
                    out += HudControlGroupSlotModel(groupIndex = idx, memberCount = count, empty = count == 0)
                }
            } else {
                if (count > 0) {
                    out += HudControlGroupSlotModel(groupIndex = idx, memberCount = count, empty = false)
                }
            }
        }
        return out
    }

    fun holdStage(heldSeconds: Float): TouchGroupHoldStage = when {
        heldSeconds < 0f -> TouchGroupHoldStage.NONE
        heldSeconds < APPEND_THRESHOLD_SEC -> TouchGroupHoldStage.SHORT
        heldSeconds <= REPLACE_THRESHOLD_SEC -> TouchGroupHoldStage.APPEND
        else -> TouchGroupHoldStage.REPLACE
    }

    fun resolveTouchRelease(
        heldSeconds: Float,
        wasEmpty: Boolean,
        hasSelection: Boolean,
    ): TouchGroupReleaseOutcome = when (holdStage(heldSeconds)) {
        TouchGroupHoldStage.NONE -> TouchGroupReleaseOutcome.NONE
        TouchGroupHoldStage.SHORT -> when {
            !wasEmpty -> TouchGroupReleaseOutcome.RECALL
            hasSelection -> TouchGroupReleaseOutcome.SAVE_EMPTY
            else -> TouchGroupReleaseOutcome.NONE
        }
        TouchGroupHoldStage.APPEND -> if (hasSelection) TouchGroupReleaseOutcome.APPEND_AND_RECALL else if (!wasEmpty) TouchGroupReleaseOutcome.RECALL else TouchGroupReleaseOutcome.NONE
        TouchGroupHoldStage.REPLACE -> if (hasSelection) TouchGroupReleaseOutcome.REPLACE else TouchGroupReleaseOutcome.NONE
    }

    fun hintKeyForStage(stage: TouchGroupHoldStage): String = when (stage) {
        TouchGroupHoldStage.NONE -> "holdGroup"
        TouchGroupHoldStage.SHORT -> "groupHintHold"
        TouchGroupHoldStage.APPEND -> "groupHintAppend"
        TouchGroupHoldStage.REPLACE -> "groupHintReplace"
    }
}

/**
 * Bottom-left dual-platform control-group bar (HUD Spec §10):
 * - PC: non-empty groups from 1..9, 0 (44×40 cells, left=8, bottom=8, gap=4)
 * - Touch: pinned 1..3 (empty dashed & dimmed) + non-empty 4..9, 0 (50×50 cells, positioned right of camera joystick at x=160, bottom=8)
 *   with live top hint pill ("按住保存编组" -> "松手调用/存入" -> "松手追加编组" -> "松手替换整组").
 */
internal class HudControlGroupBar(
    private val skin: Skin,
    private val touchControls: Boolean,
    private val fontsProvider: () -> UiFontSet?,
    private val fallbackFontProvider: () -> BitmapFont,
    private val onPcGroupClick: (Int) -> Unit,
    private val onTouchGroupAction: (Int, TouchGroupReleaseOutcome) -> Unit,
    private val hasSelection: () -> Boolean,
    private val allowTouchHold: () -> Boolean,
) : Group() {

    private val glyphLayout = GlyphLayout()
    private val slotActors = LinkedHashMap<Int, GroupSlotActor>()
    private var slots: List<HudControlGroupSlotModel> = emptyList()
    private var language: UiLanguage = UiLanguage.ZH
    private var holdingGroupIndex: Int? = null
    private var holdingElapsedSec: Float = 0f
    private var barBounds: Rectangle = Rectangle()
    private var visibleBar: Boolean = false

    private inner class GroupSlotActor(var model: HudControlGroupSlotModel) : Actor() {
        var hovered: Boolean = false
            private set
        var pressed: Boolean = false
            private set
        private var pressPointer: Int = -1
        private var pressStageX: Float = 0f
        private var pressStageY: Float = 0f
        private var startedTouchHold: Boolean = false

        fun cancelPress() {
            pressed = false
            pressPointer = -1
            startedTouchHold = false
        }

        init {
            addListener(object : InputListener() {
                override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                    if (pointer != 0 || button != Input.Buttons.LEFT) return false
                    if (touchControls && !allowTouchHold()) return false
                    pressPointer = pointer
                    pressStageX = event.stageX
                    pressStageY = event.stageY
                    pressed = true
                    if (touchControls) {
                        startedTouchHold = true
                        holdingGroupIndex = model.groupIndex
                        holdingElapsedSec = 0f
                    }
                    return true
                }

                override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
                    if (pointer != pressPointer || !pressed) return
                    val dx = event.stageX - pressStageX
                    val dy = event.stageY - pressStageY
                    if (dx * dx + dy * dy > 256f || x < -8f || y < -8f || x > width + 8f || y > height + 8f) {
                        cancelHoldIfOwner()
                        cancelPress()
                    }
                }

                override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
                    if (pointer != pressPointer) return
                    val wasPressed = pressed
                    val hadValidHold = startedTouchHold && holdingGroupIndex == model.groupIndex
                    val elapsed = if (hadValidHold) holdingElapsedSec else -1f
                    cancelHoldIfOwner()
                    cancelPress()
                    if (!wasPressed || event.isTouchFocusCancel) return
                    if (x < 0f || y < 0f || x > width || y > height) return
                    if (touchControls) {
                        if (!hadValidHold || !allowTouchHold()) return
                        val outcome = HudControlGroupLogic.resolveTouchRelease(
                            heldSeconds = elapsed,
                            wasEmpty = model.empty,
                            hasSelection = hasSelection(),
                        )
                        if (outcome != TouchGroupReleaseOutcome.NONE) {
                            onTouchGroupAction(model.groupIndex, outcome)
                        }
                    } else {
                        onPcGroupClick(model.groupIndex)
                    }
                }

                override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
                    if (pointer == -1 && !touchControls) hovered = true
                }

                override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
                    if (pointer == -1) hovered = false
                }
            })
        }

        private fun cancelHoldIfOwner() {
            if (holdingGroupIndex == model.groupIndex) {
                holdingGroupIndex = null
                holdingElapsedSec = 0f
            }
        }

        override fun draw(batch: Batch, parentAlpha: Float) {
            drawSlot(batch, this, parentAlpha)
        }
    }

    override fun act(delta: Float) {
        super.act(delta)
        if (touchControls && holdingGroupIndex != null) {
            if (!allowTouchHold()) {
                cancelHold()
            } else {
                holdingElapsedSec += delta.coerceAtLeast(0f)
            }
        }
    }

    fun cancelHold() {
        holdingGroupIndex = null
        holdingElapsedSec = 0f
        slotActors.values.forEach { it.cancelPress() }
    }

    fun update(
        visible: Boolean,
        joystickVisible: Boolean,
        countOf: (Int) -> Int,
        lang: UiLanguage = UiStrings.currentLanguage,
    ) {
        visibleBar = visible
        language = lang
        if (!visible) {
            isVisible = false
            cancelHold()
            barBounds.set(0f, 0f, 0f, 0f)
            return
        }

        val nextSlots = HudControlGroupLogic.visibleSlots(touchControls, countOf)
        if (nextSlots.isEmpty()) {
            isVisible = false
            slots = emptyList()
            cancelHold()
            barBounds.set(0f, 0f, 0f, 0f)
            return
        }

        isVisible = true
        val prevIds = slots.map { it.groupIndex }
        val nextIds = nextSlots.map { it.groupIndex }
        slots = nextSlots

        if (prevIds != nextIds) {
            val nextIdSet = nextIds.toSet()
            val removed = slotActors.keys.filter { it !in nextIdSet }
            for (id in removed) {
                slotActors.remove(id)?.let { actor ->
                    actor.cancelPress()
                    actor.remove()
                }
            }
            val ordered = LinkedHashMap<Int, GroupSlotActor>(nextSlots.size)
            for (slot in nextSlots) {
                val existing = slotActors[slot.groupIndex]
                val actor = if (existing != null) {
                    existing.model = slot
                    existing
                } else {
                    GroupSlotActor(slot).also { addActor(it) }
                }
                ordered[slot.groupIndex] = actor
            }
            slotActors.clear()
            slotActors.putAll(ordered)
            if (holdingGroupIndex != null && holdingGroupIndex !in slotActors) {
                cancelHold()
            }
        } else {
            for (s in nextSlots) {
                slotActors[s.groupIndex]?.model = s
            }
        }

        val leftX = if (touchControls && joystickVisible) TOUCH_LEFT_WITH_JOYSTICK else UiTokens.SCREEN_MARGIN
        val bottomY = UiTokens.SCREEN_MARGIN
        val cellW = if (touchControls) TOUCH_CELL_SIZE else PC_CELL_W
        val cellH = if (touchControls) TOUCH_CELL_SIZE else PC_CELL_H
        val gap = CELL_GAP

        nextSlots.forEachIndexed { idx, s ->
            val actor = slotActors[s.groupIndex] ?: return@forEachIndexed
            actor.setBounds(leftX + idx * (cellW + gap), bottomY, cellW, cellH)
            actor.touchable = Touchable.enabled
        }
        val totalW = nextSlots.size * cellW + (nextSlots.size - 1).coerceAtLeast(0) * gap
        barBounds.set(leftX, bottomY, totalW, cellH)
    }

    fun containsPoint(stageX: Float, stageY: Float): Boolean =
        isVisible && visibleBar && barBounds.contains(stageX, stageY)

    fun drawnActions(): List<DrawnAction> {
        if (!isVisible || !visibleBar) return emptyList()
        return slots.mapNotNull { s ->
            val actor = slotActors[s.groupIndex] ?: return@mapNotNull null
            DrawnAction("group:${s.groupIndex}", actor, "${s.groupIndex} (${s.memberCount})", enabled = true)
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (!isVisible || !visibleBar || slots.isEmpty()) return
        super.draw(batch, parentAlpha)
        if (touchControls) {
            drawTouchHintPill(batch, parentAlpha * color.a)
        }
    }

    private fun drawTouchHintPill(batch: Batch, alpha: Float) {
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val stage = if (holdingGroupIndex != null) HudControlGroupLogic.holdStage(holdingElapsedSec) else TouchGroupHoldStage.NONE
        val text = UiStrings.get(HudControlGroupLogic.hintKeyForStage(stage), language)
        val font = smallFont()
        glyphLayout.setText(font, text)
        val w = glyphLayout.width + 12f
        val h = HINT_H
        val x = barBounds.x
        val y = barBounds.y + barBounds.height + 4f
        val borderColor = when (stage) {
            TouchGroupHoldStage.APPEND -> UiTokens.VALUE
            TouchGroupHoldStage.REPLACE -> UiTokens.AMBER
            else -> UiTokens.LINE
        }
        val textColor = when (stage) {
            TouchGroupHoldStage.REPLACE -> UiTokens.AMBER
            TouchGroupHoldStage.APPEND -> UiTokens.VALUE
            else -> UiTokens.TEXT
        }
        fillRect(batch, white, x, y, w, h, UiTokens.RAIL, alpha)
        strokeRect(batch, white, x, y, w, h, borderColor, alpha)
        drawTextLeft(batch, font, text, x + 6f, y + h * 0.5f, textColor, alpha)
        batch.packedColor = orig
    }

    private fun drawSlot(batch: Batch, actor: GroupSlotActor, parentAlpha: Float) {
        val m = actor.model
        val white = skin.getRegion("white")
        val orig = batch.packedColor
        val alpha = parentAlpha * color.a
        val x = actor.x
        val y = actor.y
        val w = actor.width
        val h = actor.height

        val isHoldingThis = touchControls && holdingGroupIndex == m.groupIndex
        val holdStage = if (isHoldingThis) HudControlGroupLogic.holdStage(holdingElapsedSec) else TouchGroupHoldStage.NONE

        val bg = when {
            m.empty && !isHoldingThis -> EMPTY_BG
            actor.pressed -> UiTokens.CELL
            actor.hovered -> HOVER_BG
            else -> UiTokens.RAIL
        }
        fillRect(batch, white, x, y, w, h, bg, alpha)

        // Hold progress bar along bottom edge when touch-holding a group slot
        if (isHoldingThis) {
            val ratio = (holdingElapsedSec / HudControlGroupLogic.REPLACE_THRESHOLD_SEC).coerceIn(0f, 1f)
            val fillColor = when (holdStage) {
                TouchGroupHoldStage.REPLACE -> UiTokens.AMBER
                TouchGroupHoldStage.APPEND -> UiTokens.VALUE
                else -> UiTokens.RUST
            }
            if (ratio > 0.01f) {
                fillRect(batch, white, x + 1f, y + 1f, (w - 2f) * ratio, 3f, fillColor, alpha)
            }
        }

        val borderColor = when {
            holdStage == TouchGroupHoldStage.REPLACE -> UiTokens.AMBER
            holdStage == TouchGroupHoldStage.APPEND -> UiTokens.VALUE
            isHoldingThis -> UiTokens.RUST
            else -> UiTokens.LINE
        }
        if (m.empty && !isHoldingThis) {
            drawDashedRect(batch, white, x, y, w, h, UiTokens.LINE, alpha * 0.85f)
        } else {
            strokeRect(batch, white, x, y, w, h, borderColor, alpha)
        }

        // Large group number centered (20pt mono bold)
        val numColor = if (m.empty && !isHoldingThis) UiTokens.DIM else UiTokens.TEXT
        drawTextCenter(batch, numberFont(), m.groupIndex.toString(), x + w * 0.5f, y + h * 0.5f, numColor, alpha)

        // Small unit count top-right when non-empty
        if (!m.empty) {
            drawTextRight(batch, countFont(), m.memberCount.toString(), x + w - 4f, y + h - 8f, UiTokens.DIM, alpha)
        }

        batch.packedColor = orig
    }

    private fun numberFont(): BitmapFont = fontsProvider()?.creditsMono ?: fallbackFontProvider()
    private fun countFont(): BitmapFont = fontsProvider()?.smallMono ?: fallbackFontProvider()
    private fun smallFont(): BitmapFont = fontsProvider()?.small ?: fallbackFontProvider()

    private fun fillRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        if (w <= 0f || h <= 0f) return
        batch.setColor(c.r, c.g, c.b, c.a * alpha)
        batch.draw(white, x, y, w, h)
    }

    private fun strokeRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        fillRect(batch, white, x, y + h - 1f, w, 1f, c, alpha)
        fillRect(batch, white, x, y, w, 1f, c, alpha)
        fillRect(batch, white, x, y, 1f, h, c, alpha)
        fillRect(batch, white, x + w - 1f, y, 1f, h, c, alpha)
    }

    private fun drawDashedRect(batch: Batch, white: TextureRegion, x: Float, y: Float, w: Float, h: Float, c: Color, alpha: Float) {
        val dash = 5f
        val gap = 3f
        var px = x
        while (px < x + w) {
            val seg = minOf(dash, x + w - px)
            fillRect(batch, white, px, y + h - 1f, seg, 1f, c, alpha)
            fillRect(batch, white, px, y, seg, 1f, c, alpha)
            px += dash + gap
        }
        var py = y
        while (py < y + h) {
            val seg = minOf(dash, y + h - py)
            fillRect(batch, white, x, py, 1f, seg, c, alpha)
            fillRect(batch, white, x + w - 1f, py, 1f, seg, c, alpha)
            py += dash + gap
        }
    }

    private fun drawTextLeft(batch: Batch, font: BitmapFont, text: String, x: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, x, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawTextRight(batch: Batch, font: BitmapFont, text: String, rightX: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, rightX - glyphLayout.width, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawTextCenter(batch: Batch, font: BitmapFont, text: String, centerX: Float, centerY: Float, c: Color, alpha: Float) {
        if (text.isEmpty()) return
        glyphLayout.setText(font, text)
        font.setColor(c.r, c.g, c.b, c.a * alpha)
        font.draw(batch, text, centerX - glyphLayout.width * 0.5f, centerY + glyphLayout.height * 0.5f)
        font.setColor(1f, 1f, 1f, 1f)
    }

    companion object {
        val PC_CELL_W: Float = UiTokens.GROUP_CELL_W_PC
        val PC_CELL_H: Float = UiTokens.GROUP_CELL_H_PC
        val TOUCH_CELL_SIZE: Float = UiTokens.GROUP_CELL_W_TOUCH
        const val CELL_GAP: Float = 4f
        const val TOUCH_LEFT_WITH_JOYSTICK: Float = 160f
        const val HINT_H: Float = 20f

        private val EMPTY_BG: Color = UiTokens.hex(0x212528, 0.55f)
        private val HOVER_BG: Color = UiTokens.hex(0x2E3438, 0.96f)
    }
}
