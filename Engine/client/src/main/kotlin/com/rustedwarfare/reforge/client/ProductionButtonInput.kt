package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** Left buys and right cancels; dragging back inside remains a valid production release. */
class ProductionButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    changed: () -> Unit = {}, hovered: (Boolean) -> Unit = {},
    typeId: () -> String? = { session()?.productionAction?.typeId }
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT, Input.Buttons.RIGHT), false,
    identity = { current -> typeId()?.takeIf { type -> current.productionActions.any { it.typeId == type } }
        ?.let { InputActionIdentity(it) } },
    submit = { current, action, button ->
        if (button == Input.Buttons.LEFT) current.produce(action.key) else current.cancelProduction(action.key)
    }, changed = changed, hovered = hovered)
