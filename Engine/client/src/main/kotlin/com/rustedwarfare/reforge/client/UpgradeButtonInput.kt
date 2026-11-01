package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** One left-only defense endpoint. Leaving its rectangle cancels the press permanently. */
class UpgradeButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    actionKey: String, cancel: Boolean = false, changed: () -> Unit = {}
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current ->
        InputActionIdentity(actionKey).takeIf {
            current.actionMenu.upgrades.any { it.actionKey == actionKey && it.kind == UpgradePresentationKind.DEFENSE } &&
                (!cancel || current.selectedUpgradeQueue?.items?.any { it.actionKey == actionKey } == true)
        }
    }, submit = { current, action, _ ->
        if (cancel) current.cancelUpgrade(action.key) else current.upgrade(action.key)
    }, changed = changed)
