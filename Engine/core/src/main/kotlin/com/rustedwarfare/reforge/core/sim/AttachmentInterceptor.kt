package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.ANTI_NUKE_AMMO_COST
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.ANTI_NUKE_AMMO_LIMIT
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.ANTI_NUKE_AMMO_RATE

internal data class AttachmentInterceptorState(
    val ammo: Int = 0,
    val offline: Boolean = false,
    val rounds: List<Float> = emptyList(),
    val switchPending: Boolean = false,
    val roundsAheadOfReclaim: Int = 0,
) {
    val switchAction: String get() = if (offline) "isDisabled" else "isEnabled"
    val canQueue: Boolean get() = ammo + rounds.size < ANTI_NUKE_AMMO_LIMIT
}

/** The module's paid rounds and high-priority, noncancellable switch belong to its stable slot. */
internal object AttachmentInterceptor {
    const val BUILD = "buildAntiNuke"

    fun handles(action: String) = action == BUILD || action == "isEnabled" || action == "isDisabled"

    fun actions(child: MutableUnit, balance: Long): List<UnitActionObservation> {
        val state = child.attachmentInterceptor
        val queued = state.rounds.firstOrNull()
        return listOf(
            UnitActionObservation(BUILD, queued?.let { queue(child).indexOfFirst { it.name == BUILD } }, queued, 0,
                state.canQueue && balance >= ANTI_NUKE_AMMO_COST),
            UnitActionObservation(state.switchAction, 0.takeIf { state.switchPending }, 0f.takeIf { state.switchPending },
                0, !state.switchPending),
        )
    }

    fun queue(child: MutableUnit): List<QueuedUnitActionObservation> {
        val state = child.attachmentInterceptor
        val rounds = state.rounds.map { QueuedUnitActionObservation(BUILD, it, null) }
        val reclaim = child.attachmentOrder as? AttachmentOrder.Reclaim
        return buildList {
            if (state.switchPending) add(QueuedUnitActionObservation(state.switchAction, 0f, null))
            addAll(rounds.take(state.roundsAheadOfReclaim))
            if (reclaim != null) add(QueuedUnitActionObservation("reclaim", 1f - reclaim.remaining / reclaim.action.ticks, null))
            addAll(rounds.drop(state.roundsAheadOfReclaim))
        }
    }

    fun request(state: StepState, child: MutableUnit, name: String, cancel: Boolean): SimulationError? {
        val orders = child.attachmentInterceptor
        if (name != BUILD) {
            if (cancel) return SimulationError("unit-action-not-cancellable", "The switch cannot be cancelled")
            if (name != orders.switchAction || orders.switchPending)
                return SimulationError("unsupported-attachment-action", "The switch is unavailable")
            child.attachmentInterceptor = orders.copy(switchPending = true)
            return null
        }
        val ledger = checkNotNull(state.productionLedger)
        if (cancel) {
            if (orders.rounds.isEmpty()) return SimulationError("attachment-order-absent", "No ammunition order to cancel")
            state.productionLedger = ledger.refund(child.ownerPlayerId, ANTI_NUKE_AMMO_COST)
            child.attachmentInterceptor = orders.copy(rounds = orders.rounds.dropLast(1),
                roundsAheadOfReclaim = minOf(orders.roundsAheadOfReclaim, orders.rounds.size - 1))
            return null
        }
        if (!orders.canQueue) return SimulationError("ammo-queue-limit", "The ammunition queue is full")
        state.productionLedger = ledger.charge(child.ownerPlayerId, ANTI_NUKE_AMMO_COST)
            ?: return SimulationError("insufficient-funds", "The round requires 3000 credits")
        child.attachmentInterceptor = orders.copy(rounds = orders.rounds + 0f)
        return null
    }

    /** The original module checks interception before the ordinary ammunition queue advances. */
    fun advance(state: StepState, child: MutableUnit, delta: Float) {
        val orders = child.attachmentInterceptor
        if (orders.switchPending) {
            child.attachmentInterceptor = orders.copy(offline = !orders.offline, switchPending = false)
        } else if (child.attachmentOrder is AttachmentOrder.Reclaim && orders.roundsAheadOfReclaim == 0) {
            return
        } else {
            val progress = (orders.rounds.firstOrNull() ?: return) + ANTI_NUKE_AMMO_RATE * delta
            require(progress.isFinite()) { "Module ammunition progress must remain finite" }
            if (progress < 1f) {
                child.attachmentInterceptor = orders.copy(rounds = listOf(progress) + orders.rounds.drop(1))
                return
            }
            child.attachmentInterceptor = orders.copy(ammo = Math.addExact(orders.ammo, 1), rounds = orders.rounds.drop(1),
                roundsAheadOfReclaim = maxOf(0, orders.roundsAheadOfReclaim - 1))
        }
        state.pendingEvents += Event("unit-action-completed", child.unitId)
    }

    fun discardQueue(state: StepState, child: MutableUnit) {
        val orders = child.attachmentInterceptor
        if (orders.rounds.isNotEmpty()) {
            state.productionLedger = checkNotNull(state.productionLedger)
                .refund(child.ownerPlayerId, orders.rounds.size * ANTI_NUKE_AMMO_COST)
        }
        child.attachmentInterceptor = orders.copy(rounds = emptyList(), switchPending = false, roundsAheadOfReclaim = 0)
    }
}
