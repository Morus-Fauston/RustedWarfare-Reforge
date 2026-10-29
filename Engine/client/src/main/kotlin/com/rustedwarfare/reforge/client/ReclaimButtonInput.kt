package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener

/** A pure text card owns its press through release, including selection changes away and back. */
class ReclaimButtonInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
    private val choose: () -> Unit, private val changed: () -> Unit = {},
    private val hovered: (Boolean) -> Unit = {}, private val modeRevision: () -> Long = { 0L }) : InputListener() {
    private data class Press(val session: SandboxSession, val worker: String, val revision: Long, val modeRevision: Long)
    private var press: Press? = null
    val pressed: Boolean get() = press != null

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        val current = session() ?: return false
        if (!active() || pointer != 0 || button != Input.Buttons.LEFT || press != null || !current.canMaintain) return false
        press = Press(current, checkNotNull(current.selectedId), current.selectionRevision, modeRevision())
        changed()
        return true
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        val owned = press ?: return
        if (pointer != 0 || button != Input.Buttons.LEFT) return
        event.handle()
        press = null
        val current = session()
        val actor = event.listenerActor
        if (active() && current === owned.session && current.isOpen && current.canMaintain &&
            current.selectedId == owned.worker && current.selectionRevision == owned.revision && modeRevision() == owned.modeRevision &&
            actor != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height) choose()
        changed()
    }

    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        if (pointer != 0 || press == null) return
        val actor = event.listenerActor
        if (actor == null || x < 0f || y < 0f || x >= actor.width || y >= actor.height) {
            cancel(); changed()
        }
    }

    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        if (pointer == -1) hovered(true)
    }
    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == -1) hovered(false)
        if (pointer == 0 && press != null) { cancel(); changed() }
    }
    fun cancel() { press = null; hovered(false) }
}
