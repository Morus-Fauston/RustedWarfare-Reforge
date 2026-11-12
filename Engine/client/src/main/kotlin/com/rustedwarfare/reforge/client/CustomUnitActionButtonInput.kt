package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Uses the normal owned press/release path and the published authority availability. */
class CustomUnitActionButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    name: String, changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {}, beginTargeting: (String) -> Boolean = { false }) : OwnedActionInput(
    session, active, setOf(Input.Buttons.LEFT, Input.Buttons.RIGHT), true,
    identity = { current -> current.actionMenu.unitActions.singleOrNull { it.name == name && (it.available || it.cancellable) }
        ?.let { InputActionIdentity(it.name, if (it.queued) 1L else 0L) } }, submit = { current, action, button ->
        val view = current.actionMenu.unitActions.singleOrNull { it.name == action.key }
        if (button == Input.Buttons.RIGHT && view?.cancellable == true) current.unitAction(action.key, cancelQueued = true)
        else if (button == Input.Buttons.LEFT && view?.available == true) {
            if (view.groundTargeted) beginTargeting(action.key) else current.unitAction(action.key)
        }
    },
    changed = changed, hovered = hovered)
