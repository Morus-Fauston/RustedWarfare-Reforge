package com.rustedwarfare.reforge.client

import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.Input

/** Owns the Scene2D press for one construction type. */
class ConstructionButtonInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
    private val typeId: String, private val choose: (String) -> Unit,
    private val hovered: (Boolean) -> Unit = {}) : InputListener() {
    private var pressedOwner: String? = null
    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        val current = session() ?: return false
        if (!active() || pointer != 0 || button != Input.Buttons.LEFT || pressedOwner != null ||
            current.constructionActions.none { it.typeId == typeId }) return false
        pressedOwner = current.selectedId
        return pressedOwner != null
    }
    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        if (pointer != 0 || button != Input.Buttons.LEFT || pressedOwner == null) return
        event.handle()
        val current = session()
        val actor = event.listenerActor
        if (active() && actor != null && current != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height &&
            current.selectedId == pressedOwner && current.constructionActions.any { it.typeId == typeId }) choose(typeId)
        pressedOwner = null
    }
    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        if (pointer == -1) hovered(true)
    }
    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == -1) hovered(false)
    }
    fun cancel() { pressedOwner = null; hovered(false) }
}
