package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.rustedwarfare.reforge.core.sim.Position

/** Screen adapter; Scene2D is the first processor in the actual multiplexer. */
class SandboxInput(
    private val session: () -> SandboxSession?,
    private val projection: () -> MapProjection?,
    private val uiPoint: (Int, Int) -> Position?,
    private val active: () -> Boolean,
    private val editing: () -> Boolean,
    private val pressed: (Int) -> Boolean,
    private val changed: () -> Unit = {},
    private val evidence: ClientEvidence = ClientEvidence.NONE,
    private val touchControls: Boolean = false,
    private val insideViewport: (Int, Int) -> Boolean = { _, _ -> true },
) : InputAdapter() {
    private var gesture: MapGesture? = null
    private var ownerButton: Int? = null
    private var pointerPosition: Position? = null
    private data class PatrolPress(val members: Set<String>, val append: Boolean)
    private var patrolPress: PatrolPress? = null
    private data class CommandPress(val session: SandboxSession, val revision: Long, val members: Set<String>,
        val modeRevision: Long, val targetId: String?, val append: Boolean)
    private var commandPress: CommandPress? = null
    private var pHeld = false
    var modeRevision: Long = 0L
        private set
    var constructionTypeId: String? = null
        private set
    private data class ConstructionOwner(val session: SandboxSession, val revision: Long, val members: Set<String>)
    private data class ConstructionPress(val owner: ConstructionOwner, val append: Boolean)
    private var constructionOwner: ConstructionOwner? = null
    private var constructionPress: ConstructionPress? = null
    private fun ownsConstruction(current: SandboxSession?): Boolean {
        val owner = constructionOwner ?: return false
        return current === owner.session && current.isOpen && current.selectionRevision == owner.revision &&
            current.selectedIds == owner.members
    }
    private data class ReclaimOwner(val session: SandboxSession, val worker: String, val revision: Long)
    private data class ReclaimPress(val owner: ReclaimOwner, val targetId: String?, val append: Boolean)
    private var reclaimOwner: ReclaimOwner? = null
    private var reclaimPress: ReclaimPress? = null
    val reclaimMode: Boolean
        get() {
            val owner = reclaimOwner ?: return false
            val current = session()
            if (!active() || current !== owner.session || !current.canMaintain ||
                current.selectedId != owner.worker || current.selectionRevision != owner.revision) endReclaim()
            return reclaimOwner != null
        }
    val constructionPreview: ConstructionPreview?
        get() {
            val type = constructionTypeId ?: return null
            val current = session()
            if (!active() || !ownsConstruction(current)) { endConstruction(); return null }
            val ui = pointerPosition ?: return null
            val world = projection()?.toWorld(ui.x, ui.y) ?: return null
            return checkNotNull(current).previewConstruction(type, world)
        }
    var patrolMode = false
        private set
    var patrolPointCount = 0
        private set
    val box: Pair<Position, Position>? get() = gesture?.box
    private fun control() = pressed(Input.Keys.CONTROL_LEFT) || pressed(Input.Keys.CONTROL_RIGHT)
    private fun shift() = pressed(Input.Keys.SHIFT_LEFT) || pressed(Input.Keys.SHIFT_RIGHT)
    private fun mode() = when { control() -> SelectionMode.TOGGLE; shift() -> SelectionMode.ADD; else -> SelectionMode.REPLACE }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (!active() || pointer != 0 || !insideViewport(screenX, screenY)) return false
        val reclaimActive = reclaimMode
        if (ownerButton != null) return false
        val current = session() ?: return false
        if (reclaimActive && button == Input.Buttons.RIGHT) { endReclaim(); changed(); return true }
        if (constructionTypeId != null && button == Input.Buttons.RIGHT) { endConstruction(); changed(); return true }
        if (patrolMode && button == Input.Buttons.RIGHT) { endPatrol(); changed(); return true }
        val map = projection() ?: return false
        val ui = uiPoint(screenX, screenY) ?: return false
        val world = map.toWorld(ui.x, ui.y) ?: return false
        val kind = when (button) {
            Input.Buttons.LEFT -> MapButton.SELECT
            Input.Buttons.RIGHT -> MapButton.COMMAND
            Input.Buttons.MIDDLE -> if (!editing() && !touchControls) MapButton.PAN else return false
            else -> return false
        }
        pointerPosition = ui
        val append = shift()
        evidence.recordWhenEnabled("pointer") { mapOf("screenX" to screenX, "screenY" to screenY,
            "uiX" to ui.x, "uiY" to ui.y, "world" to world.fields(), "button" to button,
            "selectionRevision" to current.selectionRevision, "selectionAtPress" to current.selectedIds.sorted(),
            "append" to append, "mode" to when {
                reclaimActive -> "reclaim"
                constructionTypeId != null -> "construction"
                patrolMode -> "patrol"
                kind == MapButton.COMMAND -> "command"
                kind == MapButton.PAN -> "pan"
                else -> "select"
            }, "typeId" to constructionTypeId, "targetUnitId" to current.commandTargetAt(world)) }
        if (reclaimActive && button == Input.Buttons.LEFT) {
            reclaimPress = ReclaimPress(checkNotNull(reclaimOwner), current.reclaimTargetAt(world), append)
            ownerButton = button
            changed(); return true
        }
        if (constructionTypeId != null && button == Input.Buttons.LEFT) {
            if (!ownsConstruction(current)) { endConstruction(); changed(); return true }
            constructionPress = ConstructionPress(checkNotNull(constructionOwner), append)
            ownerButton = button
            changed(); return true
        }
        if (patrolMode && button == Input.Buttons.LEFT) {
            patrolPress = PatrolPress(current.selectedIds.toSet(), patrolPointCount > 0 || append)
            ownerButton = button
            changed(); return true
        }
        if (touchControls) {
            if (button == Input.Buttons.LEFT) current.tap(world) else current.command(world)
            changed(); return true
        }
        if (kind == MapButton.COMMAND) commandPress = CommandPress(current, current.selectionRevision,
            current.selectedIds.toSet(), modeRevision, current.commandTargetAt(world), append)
        gesture = MapGesture(map).also { check(it.begin(ui.x, ui.y, kind, mode(), current.selectedIds)) }
        ownerButton = button
        changed()
        return true
    }
    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (pointer != 0 || ownerButton == null) return false
        if (!active() || (ownerButton == Input.Buttons.MIDDLE && editing())) { cancel(); return true }
        val ownsPoint = constructionPress != null || reclaimPress != null || commandPress != null
        if (ownsPoint && !insideViewport(screenX, screenY)) { cancelGesture(); changed(); return true }
        val ui = uiPoint(screenX, screenY) ?: run {
            if (ownsPoint) cancelGesture()
            return true
        }
        if (ownsPoint && projection()?.toWorld(ui.x, ui.y) == null) {
            cancelGesture(); changed(); return true
        }
        pointerPosition = ui
        gesture?.drag(ui.x, ui.y)
        if (ownerButton == Input.Buttons.MIDDLE) recordCamera("pan")
        return true
    }
    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (reclaimOwner != null && !reclaimMode) return false
        if (pointer != 0 || ownerButton != button) return false
        val current = session()
        val ui = uiPoint(screenX, screenY)
        if (!active() || current == null || ui == null) { cancel(); return true }
        reclaimPress?.let { press ->
            val world = if (insideViewport(screenX, screenY)) projection()?.toWorld(ui.x, ui.y) else null
            if (world != null && reclaimOwner == press.owner && current === press.owner.session && current.canMaintain &&
                current.selectedId == press.owner.worker && current.selectionRevision == press.owner.revision) {
                if (press.targetId == null) current.reclaim("", press.append)
                else if (current.reclaimTargetAt(world) == press.targetId) {
                    current.reclaim(press.targetId, press.append)
                    if ((current.lastCommandBatch?.added ?: 0) > 0) endReclaim()
                } else if (current.observation.units.none { it.unitId == press.targetId }) current.reclaim(press.targetId, press.append)
            }
            cancelGesture(); changed(); return true
        }
        constructionPress?.let { press ->
            val world = if (insideViewport(screenX, screenY)) projection()?.toWorld(ui.x, ui.y) else null
            val type = constructionTypeId
            pointerPosition = ui
            if (!ownsConstruction(current) || constructionOwner != press.owner) {
                endConstruction(); changed(); return true
            }
            if (world != null && type != null) {
                val members = press.owner.members
                current.build(type, world, press.append)
                val batch = current.lastCommandBatch
                val accepted = batch?.accepted ?: 0
                val added = batch?.added ?: 0
                evidence.recordWhenEnabled("construction-point") { mapOf("typeId" to type, "point" to world.fields(),
                    "append" to press.append, "members" to members.sorted(),
                    "selectionRevision" to press.owner.revision, "selectionAtPress" to members.sorted(), "accepted" to accepted,
                    "added" to added, "queueFull" to (batch?.queueFull ?: 0)) }
                if (added > 0 && !press.append) endConstruction()
                else { constructionPress = null; ownerButton = null }
            } else {
                constructionPress = null; ownerButton = null
            }
            changed(); return true
        }
        patrolPress?.let { press ->
            val point = if (insideViewport(screenX, screenY)) projection()?.toWorld(ui.x, ui.y) else null
            if (point != null && current.patrol(point, press.append, press.members) != null) {
                patrolPointCount++
                evidence.record("patrol-point", mapOf("point" to point.fields(), "append" to press.append,
                    "members" to press.members.sorted(), "pointCount" to patrolPointCount))
            }
            cancelGesture(); changed(); return true
        }
        val intent = gesture?.release(ui.x, ui.y)
        when (intent) {
            is MapIntent.Click -> current.select(intent.position, intent.mode)
            is MapIntent.Box -> current.selectBox(intent.start, intent.end, intent.mode, intent.selectionAtStart)
            is MapIntent.Command -> commandPress?.let { press ->
                if (insideViewport(screenX, screenY) && current === press.session && current.isOpen &&
                    current.selectionRevision == press.revision && current.selectedIds == press.members &&
                    modeRevision == press.modeRevision && current.commandTargetAt(intent.position) == press.targetId)
                    current.command(intent.position, press.append)
            }
            null -> Unit
        }
        evidence.record("map-release", mapOf("button" to button, "intent" to intent?.javaClass?.simpleName,
            "selectedIds" to current.selectedIds.toList()))
        cancelGesture(); changed(); return true
    }
    override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
        pointerPosition = if (insideViewport(screenX, screenY)) uiPoint(screenX, screenY) else null
        return false
    }
    override fun scrolled(amountX: Float, amountY: Float): Boolean {
        if (!active() || editing() || touchControls || ownerButton != null) return false
        val map = projection() ?: return false
        val ui = pointerPosition ?: return false
        if (map.toWorld(ui.x, ui.y) == null) return false
        map.zoomAt(ui.x, ui.y, amountY.toDouble())
        recordCamera("zoom"); return true
    }
    override fun keyDown(keycode: Int): Boolean {
        if (!active() || editing() || touchControls) return false
        val current = session() ?: return false
        if (keycode == Input.Keys.ESCAPE && reclaimMode) { endReclaim(); changed(); return true }
        if (keycode == Input.Keys.ESCAPE && constructionTypeId != null) { endConstruction(); changed(); return true }
        if (keycode == Input.Keys.P) {
            if (pHeld) return true
            pHeld = true
            return beginPatrol()
        }
        if (keycode == Input.Keys.ESCAPE && patrolMode) { endPatrol(); changed(); return true }
        val group = when (keycode) {
            Input.Keys.NUM_0 -> 0; Input.Keys.NUM_1 -> 1; Input.Keys.NUM_2 -> 2; Input.Keys.NUM_3 -> 3
            Input.Keys.NUM_4 -> 4; Input.Keys.NUM_5 -> 5; Input.Keys.NUM_6 -> 6; Input.Keys.NUM_7 -> 7
            Input.Keys.NUM_8 -> 8; Input.Keys.NUM_9 -> 9; else -> null
        }
        if (group != null) { group(group); return true }
        if (keycode == Input.Keys.S) { cancel(); current.stop(); changed(); return true }
        return false
    }
    override fun keyUp(keycode: Int): Boolean {
        if (keycode != Input.Keys.P) return false
        val held = pHeld; pHeld = false; return held
    }
    fun beginPatrol(): Boolean {
        if (!active() || editing() || session()?.canPatrol != true) return false
        endReclaim()
        endConstruction()
        cancelGesture(); patrolMode = true; patrolPointCount = 0
        modeRevision++
        evidence.record("patrol-mode", mapOf("active" to true, "pointCount" to 0))
        changed(); return true
    }
    fun beginConstruction(typeId: String): Boolean {
        val current = session() ?: return false
        if (!active() || editing() || touchControls || current.constructionActions.none { it.typeId == typeId }) return false
        endReclaim()
        endPatrol()
        constructionTypeId = typeId
        modeRevision++
        constructionOwner = ConstructionOwner(current, current.selectionRevision, current.selectedIds.toSet())
        evidence.recordWhenEnabled("construction-mode") { mapOf("active" to true, "typeId" to typeId,
            "members" to current.selectedIds.sorted(), "selectionRevision" to current.selectionRevision,
            "selectionAtPress" to current.selectedIds.sorted()) }
        changed(); return true
    }
    fun beginReclaim(): Boolean {
        val current = session() ?: return false
        if (!active() || editing() || touchControls || !current.canMaintain) return false
        endConstruction(); endPatrol(); cancelGesture()
        reclaimOwner = ReclaimOwner(current, checkNotNull(current.selectedId), current.selectionRevision)
        modeRevision++
        evidence.record("reclaim-mode", mapOf("active" to true))
        changed(); return true
    }
    private fun endReclaim() {
        if (reclaimOwner != null) modeRevision++
        if (reclaimOwner != null) evidence.record("reclaim-mode", mapOf("active" to false))
        reclaimOwner = null
        cancelGesture()
    }
    private fun endConstruction() {
        if (constructionTypeId != null) modeRevision++
        if (constructionTypeId != null) evidence.recordWhenEnabled("construction-mode") { mapOf("active" to false) }
        cancelGesture()
        constructionTypeId = null; constructionOwner = null
    }
    private fun endPatrol() {
        if (patrolMode) modeRevision++
        cancelGesture()
        if (patrolMode) evidence.record("patrol-mode", mapOf("active" to false, "pointCount" to patrolPointCount))
        patrolMode = false; patrolPointCount = 0
    }
    fun group(index: Int) {
        if (!active() || editing()) return
        val current = session() ?: return
        val previousSelection = current.selectedIds.toSet()
        if (control()) current.saveGroup(index) else current.recallGroup(index, shift())?.let {
            projection()?.centerOn(it); recordCamera("group")
        }
        if (current.selectedIds != previousSelection) { endConstruction(); endReclaim() }
        changed()
    }
    private fun recordCamera(reason: String) {
        val map = projection() ?: return
        evidence.record("camera", mapOf("reason" to reason, "center" to map.camera.center.fields(), "zoom" to map.camera.zoom))
    }
    private fun cancelGesture() {
        gesture?.cancel(); gesture = null; patrolPress = null; commandPress = null; constructionPress = null; reclaimPress = null; ownerButton = null; pointerPosition = null
    }
    fun cancel() { endConstruction(); endPatrol(); endReclaim(); pHeld = false }
}
