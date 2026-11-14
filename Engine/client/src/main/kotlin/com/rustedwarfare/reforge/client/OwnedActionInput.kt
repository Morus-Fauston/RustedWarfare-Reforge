package com.rustedwarfare.reforge.client

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener

/** The adapter supplies action eligibility; this identity never grants simulation authority. */
data class InputActionIdentity(val key: String, val modeRevision: Long = 0L,
    val attachmentContext: Pair<String, String>? = null)

/** Scene2D owns event delivery. This listener owns one project action across that delivery. */
open class OwnedActionInput(
    private val session: () -> SandboxSession?,
    private val active: () -> Boolean,
    private val buttons: Set<Int>,
    private val cancelOnExit: Boolean,
    private val identity: (SandboxSession) -> InputActionIdentity?,
    private val submit: (SandboxSession, InputActionIdentity, Int) -> Unit,
    private val changed: () -> Unit = {},
    private val hovered: (Boolean) -> Unit = {},
    private val notifyDragCancel: Boolean = false,
    private val hitAt: (InputEvent) -> Boolean = { true }
) : InputListener() {
    private data class Press(val session: SandboxSession, val revision: Long, val primary: String?,
        val types: Map<String, String>, val action: InputActionIdentity, val button: Int)
    private var press: Press? = null
    val pressed: Boolean get() = press != null

    private fun selectedTypes(current: SandboxSession): Map<String, String>? {
        val selected = current.selectedIds
        if (selected.isEmpty()) return null
        val types = current.observation.units.filter { it.unitId in selected }.associate { it.unitId to it.typeId }
        return types.takeIf { it.keys == selected }
    }

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (!active() || pointer != 0 || button !in buttons || press != null || !hitAt(event)) return false
        val current = session()?.takeIf { it.isOpen } ?: return false
        val action = identity(current) ?: return false
        val types = selectedTypes(current) ?: return false
        press = Press(current, current.selectionRevision, current.selectedId, types, action, button)
        changed()
        return true
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        val owned = press ?: return
        if (pointer != 0 || button != owned.button) return
        event.handle()
        press = null
        val current = session()
        if (active() && inside(event.listenerActor, x, y) && hitAt(event) && current === owned.session && current.isOpen &&
            current.selectionRevision == owned.revision && current.selectedId == owned.primary &&
            selectedTypes(current) == owned.types && identity(current) == owned.action) {
            submit(current, owned.action, button)
        }
        changed()
    }

    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        if (cancelOnExit && pointer == 0 && press != null && (!inside(event.listenerActor, x, y) || !hitAt(event))) cancelDrag()
    }

    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        if (pointer == -1) hovered(true)
    }

    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == -1) hovered(false)
        if (cancelOnExit && pointer == 0 && press != null) cancelDrag()
    }

    private fun cancelDrag() {
        cancel()
        if (notifyDragCancel) changed()
    }

    fun cancel() { press = null; hovered(false) }

    private fun inside(actor: Actor?, x: Float, y: Float): Boolean =
        actor != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height
}
