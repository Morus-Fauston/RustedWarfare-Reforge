package com.rustedwarfare.reforge.client

import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.Input

/** Owns the Scene2D press for one construction type. */
class ConstructionButtonInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
    private val typeId: String, private val choose: (String) -> Unit,
    private val hovered: (Boolean) -> Unit = {}, private val modeRevision: () -> Long = { 0L }) : InputListener() {
    private data class Press(val session: SandboxSession, val revision: Long, val members: Set<String>, val modeRevision: Long)
    private var press: Press? = null
    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        val current = session() ?: return false
        if (!active() || pointer != 0 || button != Input.Buttons.LEFT || press != null ||
            current.constructionActions.none { it.typeId == typeId }) return false
        press = Press(current, current.selectionRevision, current.selectedIds.toSet(), modeRevision())
        return true
    }
    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        val owned = press ?: return
        if (pointer != 0 || button != Input.Buttons.LEFT) return
        event.handle()
        press = null
        val current = session()
        val actor = event.listenerActor
        if (active() && actor != null && current === owned.session && current.isOpen &&
            x >= 0f && y >= 0f && x < actor.width && y < actor.height &&
            current.selectionRevision == owned.revision && current.selectedIds == owned.members && modeRevision() == owned.modeRevision &&
            current.constructionActions.any { it.typeId == typeId }) choose(typeId)
    }
    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        if (pointer != 0 || press == null) return
        val actor = event.listenerActor
        if (actor == null || x < 0f || y < 0f || x >= actor.width || y >= actor.height) cancel()
    }
    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        if (pointer == -1) hovered(true)
    }
    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == -1) hovered(false)
        if (pointer == 0 && press != null) cancel()
    }
    fun cancel() { press = null; hovered(false) }
}
