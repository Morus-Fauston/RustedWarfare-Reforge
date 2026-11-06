package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Content-defined factory cards retain the common owned left/right release semantics. */
class FactoryUpgradeInput(session: () -> SandboxSession?, active: () -> Boolean,
    actionKey: String, changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {}
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT, Input.Buttons.RIGHT), false,
    identity = { current -> InputActionIdentity(actionKey).takeIf {
        current.actionMenu.upgrades.any { it.actionKey == actionKey && it.kind == UpgradePresentationKind.FACTORY }
    } }, submit = { current, action, button ->
        if (button == Input.Buttons.LEFT) current.upgrade(action.key) else current.cancelUpgrade(action.key)
    }, changed = changed, hovered = hovered)
