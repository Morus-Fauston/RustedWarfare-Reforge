package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.rustedwarfare.reforge.core.sim.UnitObservation

internal data class BattleActionRow(val id: String, val title: String, val enabled: Boolean,
    val cancelId: String? = null, val cancelEnabled: Boolean = false, val target: Boolean = false,
    val attachment: Pair<String, String>? = null, val hint: String = "", val iconType: String? = null,
    val progress: Float? = null)

/** Scrollable list of actions already projected from the player's authorized view. */
internal class BattleActionList(skin: Skin, stage: Stage, session: () -> SandboxSession?,
    private val unitIcon: (String) -> TextureRegion?,
    dispatch: (SandboxSession, BattleActionRow, Boolean) -> Unit, changed: () -> Unit) {
    private class Card(val table: Table, val label: Label, val icon: Image, val progress: Image)

    private val content = Table(skin).top()
    private val panes = linkedMapOf<String, Card>()
    private var currentRows = emptyList<BattleActionRow>()
    private var selection: String? = null
    private var hoveredId: String? = null
    private val hint = Label("", skin).apply {
        setBounds(482f, 220f, 252f, 196f)
        setAlignment(Align.topLeft)
        setWrap(true)
        style = Label.LabelStyle(style).apply {
            background = skin.newDrawable("white", Color(.94f, .91f, .86f, .89f))
            (background as BaseDrawable).apply { leftWidth = 10f; rightWidth = 10f; topHeight = 8f }
            fontColor = Color(.10f, .11f, .09f, 1f)
        }
        touchable = Touchable.disabled
        isVisible = false
        stage.addActor(this)
    }
    private val title = Label("", skin).apply {
        style = Label.LabelStyle(style).apply { background = skin.newDrawable("white", Color(.12f, .13f, .12f, .68f)) }
        touchable = Touchable.disabled
        setAlignment(Align.center)
        setWrap(true)
        setFontScale(.80f)
        setBounds(752f, 454f, 184f, 46f)
        stage.addActor(this)
    }
    private val scroll = ScrollPane(content, ScrollPane.ScrollPaneStyle().apply {
        background = skin.newDrawable("white", Color(.09f, .10f, .09f, .43f))
        vScroll = skin.newDrawable("white", Color(.10f, .12f, .10f, .81f)).apply { minWidth = 7f }
        vScrollKnob = skin.newDrawable("white", Color(.59f, .75f, .53f, 1f)).apply { minWidth = 7f; minHeight = 22f }
    }).apply {
        setBounds(752f, 116f, 184f, 330f)
        setScrollingDisabled(true, false)
        setFadeScrollBars(false)
        setOverscroll(false, false)
        setSmoothScrolling(false)
        setFlickScroll(false)
        setVariableSizeKnobs(false)
        addListener(object : InputListener() {
            override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
                stage.scrollFocus = this@apply
                return true
            }
        })
        stage.addActor(this)
    }

    fun scrollTarget(hit: Actor?): Actor? = scroll.takeIf { it.isVisible && hit?.isDescendantOf(it) == true }

    private fun hitsCard(event: InputEvent): Boolean {
        val card = event.listenerActor ?: return false
        val stage = card.stage ?: return false
        val point = scroll.stageToLocalCoordinates(Vector2(event.stageX, event.stageY))
        val contentWidth = scroll.width - if (scroll.isScrollY) 7f else 0f
        return point.x >= 0f && point.x < contentWidth && point.y >= 0f && point.y < scroll.height &&
            stage.hit(event.stageX, event.stageY, true)?.isDescendantOf(card) == true
    }

    private val createRow: (String) -> Card = { id ->
        val label = Label("", skin).apply {
            setWrap(true); setFontScale(.68f); setAlignment(Align.center); touchable = Touchable.disabled
        }
        val icon = Image().apply { setScaling(Scaling.fit); touchable = Touchable.disabled }
        val progress = Image(skin.newDrawable("white", Color(.58f, .86f, .38f, .93f))).apply {
            touchable = Touchable.disabled
        }
        val widget = Table(skin).apply {
            touchable = Touchable.enabled
            background = skin.newDrawable("white", Color(.17f, .18f, .16f, .72f))
            add(icon).size(22f).padTop(2f).row()
            add(label).width(75f).growY().pad(1f, 3f, 3f, 3f)
            addActor(progress)
            addListener(OwnedActionInput(session, { scroll.isVisible }, setOf(Input.Buttons.LEFT, Input.Buttons.RIGHT),
                cancelOnExit = true,
                identity = {
                    currentRows.singleOrNull { it.id == id }?.let { row ->
                        InputActionIdentity(row.id, (if (row.enabled) 1 else 0) + (if (row.cancelEnabled) 2L else 0L), row.attachment)
                    }
                },
                submit = { owner, _, button ->
                    val row = currentRows.singleOrNull { it.id == id }
                    if (row != null && (if (button == Input.Buttons.RIGHT) row.cancelEnabled else row.enabled))
                        dispatch(owner, row, button == Input.Buttons.RIGHT)
                },
                changed = changed,
                hovered = { entered ->
                    if (entered) hoveredId = id else if (hoveredId == id) hoveredId = null
                    showHint()
                }, hitAt = ::hitsCard))
        }
        Card(widget, label, icon, progress)
    }

    fun update(owner: String?, caption: String?, value: List<BattleActionRow>) {
        val rebuild = owner != selection || currentRows.map { it.id } != value.map { it.id }
        selection = owner
        currentRows = value
        scroll.isVisible = value.isNotEmpty()
        title.isVisible = !caption.isNullOrBlank()
        title.setText(caption.orEmpty())
        if (!scroll.isVisible && scroll.stage?.scrollFocus === scroll) scroll.stage.scrollFocus = null
        if (rebuild) {
            hoveredId = null
            panes.clear()
            content.clearChildren()
            for ((index, row) in currentRows.withIndex()) {
                val entry = createRow(row.id)
                panes[row.id] = entry
                content.add(entry.table).width(84f).height(62f).pad(2f)
                if (index % 2 == 1) content.row()
            }
        }
        scroll.height = minOf(330f, ((value.size + 1) / 2 * 66f))
        scroll.y = 446f - scroll.height
        if (rebuild) {
            scroll.scrollY = 0f
            scroll.updateVisualScroll()
            scroll.invalidate()
        }
        for (row in currentRows) {
            val card = panes.getValue(row.id)
            card.label.setText(row.title)
            card.label.color = when {
                row.enabled -> Color(.66f, 1f, .58f, 1f)
                row.cancelEnabled -> Color.WHITE
                else -> Color.LIGHT_GRAY
            }
            val region = row.iconType?.let(unitIcon)
            if (card.icon.userObject !== region) {
                card.icon.userObject = region
                card.icon.drawable = region?.let(::TextureRegionDrawable)
            }
            card.icon.color = if (row.enabled || row.cancelEnabled) Color.WHITE else Color.GRAY
            card.progress.isVisible = row.progress != null
            card.progress.setBounds(2f, 2f, 80f * (row.progress ?: 0f).coerceIn(0f, 1f), 2f)
        }
        showHint()
    }

    fun containsPoint(x: Float, y: Float): Boolean = scroll.isVisible &&
        x >= scroll.x && x < scroll.x + scroll.width && y >= scroll.y && y < scroll.y + scroll.height

    private fun showHint() {
        val detail = currentRows.firstOrNull { it.id == hoveredId }?.hint.orEmpty()
        hint.isVisible = scroll.isVisible && detail.isNotEmpty()
        hint.setText(detail)
    }

    fun drawnActions(): List<DrawnAction> {
        if (!scroll.isVisible) return emptyList()
        val result = mutableListOf(DrawnAction("action-list", scroll, "Actions"))
        if (scroll.isScrollY) {
            result += DrawnAction("action-list-scrollbar", scroll,
                when {
                    scroll.visualScrollPercentY >= 1f -> "at-bottom"
                    scroll.visualScrollPercentY <= 0f -> "at-top"
                    else -> "scrollable"
                }, localBounds =
                Rectangle(scroll.width - 7f, 0f, 7f, scroll.height))
        }
        val point = Vector2()
        for (row in currentRows) {
            val actor = panes.getValue(row.id).table
            actor.localToStageCoordinates(point.setZero())
            if (point.y < scroll.y || point.y + actor.height > scroll.y + scroll.height) continue
            result += DrawnAction(row.id, actor, row.title, enabled = row.enabled)
            row.cancelId?.let { result += DrawnAction(it, actor, row.title, "right", row.cancelEnabled) }
        }
        return result
    }
}

internal fun ActionMenuView.battleActions(): List<BattleActionRow> = buildList {
    for (entry in production) add(BattleActionRow("produce:${entry.typeId}",
        "${entry.title}\n$${entry.price}" + if (entry.count > 0) " · ${entry.count}" else "",
        entry.affordable, "cancel-production:${entry.typeId}", entry.count > 0, hint = entry.tooltip,
        iconType = entry.typeId, progress = entry.progress))
    for (entry in upgrades) add(BattleActionRow("upgrade:${entry.actionKey}", entry.cardText.replace('\n', ' '),
        entry.affordable && entry.progress == null, "cancel-upgrade:${entry.actionKey}", entry.progress != null, hint = entry.tooltip,
        iconType = entry.targetTypeId, progress = entry.progress))
    for (entry in unitActions) {
        val status = entry.progress?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        add(BattleActionRow("unit-action:${entry.name}", entry.title + status, entry.available,
            "cancel-unit-action:${entry.name}".takeIf { entry.cancellable }, entry.cancellable,
            target = entry.groundTargeted, attachment = entry.attachmentContext, hint = entry.tooltip,
            iconType = entry.iconTypeId, progress = entry.progress))
    }
    for (entry in construction) add(BattleActionRow("choose-construction:${entry.typeId}",
        "${entry.panelTitle}\n$${entry.price}", true, target = true, hint = entry.tooltip, iconType = entry.typeId))
    depthAction?.let { add(BattleActionRow(if (it.surface) "submarine-surface" else "submarine-dive", it.title, true, hint = it.tooltip)) }
    flightAction?.let { add(BattleActionRow(if (it.flying) "amphibious-fly" else "amphibious-dive", it.title, it.available, hint = it.tooltip)) }
    transportAction?.let { add(BattleActionRow(if (it.unloading) "transport-unload" else "transport-cancel-unload",
        "${it.title}\n${it.occupiedSlots}/${it.capacity} · ${it.passengerCount}", it.available,
        hint = "${it.title}\nCargo ${it.occupiedSlots} / ${it.capacity}\nPassengers ${it.passengerCount}")) }
    if (canMaintain) add(BattleActionRow("choose-reclaim", "Reclaim", true, target = true, hint = reclaimTooltip))
    if (canRepair) add(BattleActionRow("choose-repair", "Repair", true, target = true, hint = repairTooltip))
}

internal fun desktopPrimaryHeading(menu: ActionMenuView, unit: UnitObservation?,
                                   selectedIds: Set<String>): String? = menu.selectedTitle?.let { title ->
    val hp = unit?.health
    val maximum = unit?.maxHealth
    val owner = if (selectedIds.size > 1 && unit != null) {
        val number = selectedIds.sorted().indexOf(unit.unitId) + 1
        "$title · Primary #$number"
    } else title
    val health = if (hp != null && maximum != null) "\n${hp.toInt()} / ${maximum.toInt()} HP" else ""
    val cargo = unit?.transport?.let { "\nCargo ${it.occupiedSlots}/${it.capacity} · ${it.passengerCount}" }.orEmpty()
    owner + health + cargo
}
