package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Submit only the unload intent owned by the original press and current selection. */
class TransportUnloadButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    changed: () -> Unit = {}) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> current.actionMenu.transportAction?.takeIf { it.available }?.let {
        InputActionIdentity(if (it.unloading) "unload" else "cancel-unload")
    } }, submit = { current, action, _ -> current.unload(action.key == "unload") }, changed = changed)
