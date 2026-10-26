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
    private var pHeld = false
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
        if (ownerButton != null) return false
        val current = session() ?: return false
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
        evidence.record("pointer", mapOf("screenX" to screenX, "screenY" to screenY,
            "uiX" to ui.x, "uiY" to ui.y, "world" to world.fields(), "button" to button))
        if (patrolMode && button == Input.Buttons.LEFT) {
            patrolPress = PatrolPress(current.selectedIds.toSet(), patrolPointCount > 0 || shift())
            ownerButton = button
            changed(); return true
        }
        if (touchControls) {
            if (button == Input.Buttons.LEFT) current.tap(world) else current.command(world)
            changed(); return true
        }
        gesture = MapGesture(map).also { check(it.begin(ui.x, ui.y, kind, mode(), current.selectedIds)) }
        ownerButton = button
        changed()
        return true
    }
    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (pointer != 0 || ownerButton == null) return false
        if (!active() || (ownerButton == Input.Buttons.MIDDLE && editing())) { cancel(); return true }
        val ui = uiPoint(screenX, screenY) ?: return true
        pointerPosition = ui
        gesture?.drag(ui.x, ui.y)
        if (ownerButton == Input.Buttons.MIDDLE) recordCamera("pan")
        return true
    }
    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer != 0 || ownerButton != button) return false
        val current = session()
        val ui = uiPoint(screenX, screenY)
        if (!active() || current == null || ui == null) { cancel(); return true }
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
            is MapIntent.Command -> current.command(intent.position)
            null -> Unit
        }
        evidence.record("map-release", mapOf("button" to button, "intent" to intent?.javaClass?.simpleName,
            "selectedIds" to current.selectedIds.toList()))
        cancelGesture(); changed(); return true
    }
    override fun mouseMoved(screenX: Int, screenY: Int): Boolean {
        pointerPosition = uiPoint(screenX, screenY)
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
        if (keycode == Input.Keys.S) { endPatrol(); current.stop(); changed(); return true }
        return false
    }
    override fun keyUp(keycode: Int): Boolean {
        if (keycode != Input.Keys.P) return false
        val held = pHeld; pHeld = false; return held
    }
    fun beginPatrol(): Boolean {
        if (!active() || editing() || session()?.canPatrol != true) return false
        cancelGesture(); patrolMode = true; patrolPointCount = 0
        evidence.record("patrol-mode", mapOf("active" to true, "pointCount" to 0))
        changed(); return true
    }
    private fun endPatrol() {
        cancelGesture()
        if (patrolMode) evidence.record("patrol-mode", mapOf("active" to false, "pointCount" to patrolPointCount))
        patrolMode = false; patrolPointCount = 0
    }
    fun group(index: Int) {
        if (!active() || editing()) return
        val current = session() ?: return
        if (control()) current.saveGroup(index) else current.recallGroup(index, shift())?.let {
            projection()?.centerOn(it); recordCamera("group")
        }
        changed()
    }
    private fun recordCamera(reason: String) {
        val map = projection() ?: return
        evidence.record("camera", mapOf("reason" to reason, "center" to map.camera.center.fields(), "zoom" to map.camera.zoom))
    }
    private fun cancelGesture() {
        gesture?.cancel(); gesture = null; patrolPress = null; ownerButton = null; pointerPosition = null
    }
    fun cancel() { endPatrol(); pHeld = false }
}
