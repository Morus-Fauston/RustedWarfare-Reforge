package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener

/** One owned economic upgrade card press. */
class EconomyUpgradeInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
    private val changed: () -> Unit = {}, private val hovered: (Boolean) -> Unit = {}) : InputListener() {
    private data class Press(val session: SandboxSession, val unitId: String, val typeId: String,
        val actionKey: String, val selectionRevision: Long, val button: Int)
    private var press: Press? = null
    val pressed: Boolean get() = press != null

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (!active() || pointer != 0 || press != null || button !in listOf(Input.Buttons.LEFT, Input.Buttons.RIGHT)) return false
        val current = session()?.takeIf { it.isOpen } ?: return false
        val action = current.selectedUpgrades.singleOrNull()?.takeIf { it.actionKey in setOf("upgrade_t2", "upgrade_t3") } ?: return false
        val selected = current.observation.units.singleOrNull { it.unitId == current.selectedId } ?: return false
        press = Press(current, selected.unitId, selected.typeId, action.actionKey, current.selectionRevision, button)
        changed()
        return true
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        val owned = press ?: return
        if (pointer != 0 || button != owned.button) return
        event.handle()
        press = null
        val current = session()
        val actor = event.listenerActor
        val inside = actor != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height
        // A SandboxSession owns exactly one world; reopening the match creates a new session.
        if (active() && inside && current === owned.session && current.isOpen &&
            current.selectionRevision == owned.selectionRevision && current.selectedId == owned.unitId &&
            current.observation.units.singleOrNull { it.unitId == owned.unitId }?.typeId == owned.typeId &&
            current.selectedUpgrades.singleOrNull()?.actionKey == owned.actionKey) {
            if (button == Input.Buttons.LEFT) current.upgrade(owned.actionKey) else current.cancelUpgrade(owned.actionKey)
        }
        changed()
    }

    override fun enter(event: InputEvent, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
        if (pointer == -1) hovered(true)
    }
    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == -1) hovered(false)
    }
    fun cancel() { press = null; hovered(false) }
}
