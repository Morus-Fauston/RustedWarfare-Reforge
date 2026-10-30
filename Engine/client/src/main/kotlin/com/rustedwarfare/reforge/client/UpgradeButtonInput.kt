package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** One left-only defense endpoint. Leaving its rectangle cancels the press permanently. */
class UpgradeButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    actionKey: String, cancel: Boolean = false, changed: () -> Unit = {}
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current ->
        InputActionIdentity(actionKey).takeIf {
            actionKey in setOf("upgrade_gun", "upgrade_artillery") &&
                current.selectedUpgrades.any { it.actionKey == actionKey } &&
                (!cancel || current.selectedUpgradeQueue?.items?.any { it.actionKey == actionKey } == true)
        }
    }, submit = { current, action, _ ->
        if (cancel) current.cancelUpgrade(action.key) else current.upgrade(action.key)
    }, changed = changed)
