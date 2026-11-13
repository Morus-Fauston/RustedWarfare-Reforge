package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** One owned press/release for the currently available amphibious intent. */
class AmphibiousFlightButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    changed: () -> Unit = {}) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> current.actionMenu.flightAction?.takeIf { it.available }?.let {
        InputActionIdentity(if (it.flying) "amphibious-fly" else "amphibious-dive")
    } }, submit = { current, action, _ -> current.amphibiousFlight(action.key == "amphibious-fly") }, changed = changed)
