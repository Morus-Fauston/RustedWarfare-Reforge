package com.rustedwarfare.reforge.client

import com.badlogic.gdx.Input

/** One construction mode, invalidated by leaving its card or changing the map mode. */
class ConstructionButtonInput(session: () -> SandboxSession?, active: () -> Boolean,
    typeId: String, choose: (String) -> Unit, hovered: (Boolean) -> Unit = {},
    modeRevision: () -> Long = { 0L }
) : OwnedActionInput(session, active, setOf(Input.Buttons.LEFT), true,
    identity = { current -> InputActionIdentity(typeId, modeRevision())
        .takeIf { current.constructionActions.any { it.typeId == typeId } } },
    submit = { _, action, _ -> choose(action.key) }, hovered = hovered)
