package com.rustedwarfare.reforge.core.sim

sealed interface Action {
    val actionId: String
    val actorPlayerId: String
    val unitId: String
    val world: WorldHandle
    val atStep: Long?
}

data class MoveAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val target: Position,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class StopAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class SelectAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action
