package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Left enters reclaim mode; leaving the card or changing map mode cancels the old press. */
class ReclaimButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    choose: () -> Unit, changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {},
    modeRevision: () -> Long = { 0L }
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> InputActionIdentity("reclaim", modeRevision()).takeIf { current.canMaintain } },
    submit = { _, _, _ -> choose() }, changed = changed, hovered = hovered, notifyDragCancel = true)
