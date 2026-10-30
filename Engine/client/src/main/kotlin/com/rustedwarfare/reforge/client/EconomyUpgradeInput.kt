package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Economic cards retain left/right and release-time rectangle semantics. */
class EconomyUpgradeInput(session: () -> SandboxSession?, active: () -> Boolean,
    changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {}
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT, Input.Buttons.RIGHT), false,
    identity = { current -> current.selectedUpgrades.singleOrNull()
        ?.takeIf { it.actionKey in setOf("upgrade_t2", "upgrade_t3") }
        ?.let { InputActionIdentity(it.actionKey) } },
    submit = { current, action, button ->
        if (button == Input.Buttons.LEFT) current.upgrade(action.key) else current.cancelUpgrade(action.key)
    }, changed = changed, hovered = hovered)
