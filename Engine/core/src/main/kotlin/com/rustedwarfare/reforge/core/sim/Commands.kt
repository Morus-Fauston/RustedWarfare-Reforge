package com.rustedwarfare.reforge.core.sim

sealed interface Action {
    val actionId: String
    val actorPlayerId: String
    val unitId: String
    val world: WorldHandle
    val atStep: Long?
}

enum class LoadDirection { INTO, UP }

/** A named content action is queued independently of the unit's movement orders. */
data class UnitAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val actionName: String,
    override val world: WorldHandle,
    val cancelQueued: Boolean = false,
    override val atStep: Long? = null,
    val target: Position? = null,
) : Action

data class UnloadAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val unloading: Boolean,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

/** INTO orders the passenger; UP orders the carrier. Both use ordinary owner permissions. */
data class LoadAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val targetUnitId: String,
    val direction: LoadDirection,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

/** Native Fly/Dive intent; physical altitude and passage category change independently. */
data class AmphibiousFlightAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val flying: Boolean,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

/** Native Surface/Dive intent, independent from actual depth and the current movement order. */
data class SubmarineDepthAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val surface: Boolean,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class BuildAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val buildingTypeId: String,
    val target: Position,
    val buildingNonce: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
    val append: Boolean = false,
) : Action

data class RepairUnitAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val targetUnitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
    val append: Boolean = false,
) : Action

data class ReclaimUnitAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val targetUnitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
    val append: Boolean = false,
) : Action

data class ResumeConstructionAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val targetUnitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
    val append: Boolean = false,
) : Action

data class UpgradeAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val upgradeId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class CancelUpgradeAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val upgradeId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class ProduceAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val productionTypeId: String,
    val unitNonce: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class CancelProductionAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val productionTypeId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

data class AttackAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val targetUnitId: String,
    override val world: WorldHandle,
    override val atStep: Long? = null,
) : Action

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

data class PatrolAction(
    override val actionId: String,
    override val actorPlayerId: String,
    override val unitId: String,
    val target: Position,
    val append: Boolean,
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
