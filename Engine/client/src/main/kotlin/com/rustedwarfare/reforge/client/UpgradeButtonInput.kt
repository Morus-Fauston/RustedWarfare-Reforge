package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener

/** Owns a press on one existing defense upgrade or cancellation endpoint. */
class UpgradeButtonInput(private val session: () -> SandboxSession?, private val active: () -> Boolean,
    private val actionKey: String, private val cancel: Boolean = false,
    private val changed: () -> Unit = {}) : InputListener() {
    private data class Press(val session: SandboxSession, val unitId: String, val typeId: String, val revision: Long)
    private var press: Press? = null

    private fun available(current: SandboxSession): Boolean = current.isOpen &&
        actionKey in setOf("upgrade_gun", "upgrade_artillery") &&
        current.selectedUpgrades.any { it.actionKey == actionKey } &&
        (!cancel || current.selectedUpgradeQueue?.items?.any { it.actionKey == actionKey } == true)

    override fun touchDown(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int): Boolean {
        if (!active() || pointer != 0 || button != Input.Buttons.LEFT || press != null) return false
        val current = session()?.takeIf { available(it) } ?: return false
        val unit = current.observation.units.singleOrNull { it.unitId == current.selectedId } ?: return false
        press = Press(current, unit.unitId, unit.typeId, current.selectionRevision)
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
        if (active() && actor != null && x >= 0f && y >= 0f && x < actor.width && y < actor.height &&
            current === owned.session && available(current) && current.selectionRevision == owned.revision &&
            current.selectedId == owned.unitId &&
            current.observation.units.singleOrNull { it.unitId == owned.unitId }?.typeId == owned.typeId) {
            if (cancel) current.cancelUpgrade(actionKey) else current.upgrade(actionKey)
        }
        changed()
    }

    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        if (pointer != 0 || press == null) return
        val actor = event.listenerActor
        if (actor == null || x < 0f || y < 0f || x >= actor.width || y >= actor.height) cancel()
    }

    override fun exit(event: InputEvent, x: Float, y: Float, pointer: Int, toActor: Actor?) {
        if (pointer == 0) cancel()
    }

    fun cancel() { press = null }
}
