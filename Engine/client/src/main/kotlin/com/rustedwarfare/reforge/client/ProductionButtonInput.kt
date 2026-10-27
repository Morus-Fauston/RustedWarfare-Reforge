package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener

/** One owned press chain for both production buttons; Scene2D consumes releases before the map. */
class ProductionButtonInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
                            private val changed: () -> Unit = {}, private val hovered: (Boolean) -> Unit = {},
                            private val typeId: () -> String? = { session()?.productionAction?.typeId }) : InputListener() {
    private var button: Int? = null
    private var actorId: String? = null
    private var pressedType: String? = null
    val pressed: Boolean get() = button != null

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (!active() || pointer != 0 || this.button != null ||
            button !in listOf(Input.Buttons.LEFT, Input.Buttons.RIGHT)) return false
        val current = session() ?: return false
        val type = typeId() ?: return false
        if (current.productionActions.none { it.typeId == type }) return false
        this.button = button
        actorId = current.selectedId
        pressedType = type
        changed()
        return true
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        if (pointer != 0 || this.button != button) return
        event.handle()
        val current = session()
        val actor = event.listenerActor
        val inside = actor != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height
        if (active() && inside && current != null && current.selectedId == actorId && typeId() == pressedType) {
            if (button == Input.Buttons.LEFT) current.produce(pressedType) else current.cancelProduction(pressedType)
        }
        this.button = null; actorId = null; pressedType = null
        changed()
    }

    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
        if (pointer == -1) hovered(true)
    }
    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: com.badlogic.gdx.scenes.scene2d.Actor?) {
        if (pointer == -1) hovered(false)
    }
    fun cancel() { button = null; actorId = null; pressedType = null; hovered(false) }
}
