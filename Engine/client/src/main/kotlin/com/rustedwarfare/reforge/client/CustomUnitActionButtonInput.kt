package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Uses the normal owned press/release path and the published authority availability. */
class CustomUnitActionButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    name: String, changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {}) : OwnedActionInput(
    session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> current.actionMenu.unitActions.singleOrNull { it.name == name && it.available }
        ?.let { InputActionIdentity(it.name) } }, submit = { current, action, _ -> current.unitAction(action.key) },
    changed = changed, hovered = hovered)
