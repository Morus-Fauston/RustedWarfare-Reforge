package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** One owned press/release for the currently available depth intent. */
class SubmarineDepthButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    changed: () -> Unit = {}) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> current.actionMenu.depthAction?.let {
        InputActionIdentity(if (it.surface) "surface" else "dive")
    } }, submit = { current, action, _ -> current.submarineDepth(action.key == "surface") }, changed = changed)
