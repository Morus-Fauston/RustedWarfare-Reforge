package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.AttachmentSlotDefinition
import com.rustedwarfare.reforge.core.content.AttachmentInstall
import com.rustedwarfare.reforge.core.content.AttachmentRemoval
import com.rustedwarfare.reforge.core.content.ModularSpiderRules

internal data class AttachmentBinding(val parentId: String, val slot: AttachmentSlotDefinition)

internal sealed interface AttachmentOrder {
    data class Install(val action: AttachmentInstall) : AttachmentOrder
    data class Cancel(val action: AttachmentRemoval) : AttachmentOrder
    data class Reclaim(val action: AttachmentRemoval, val remaining: Float) : AttachmentOrder
}

/** Initial creation and pose synchronization for real units attached to a chassis. */
internal object UnitAttachments {
    const val UNIT_PREFIX = "attached:"
    const val OPENING_HEIGHT = 170.0
    private const val OPENING_FALL_SPEED = 1.5f
    private const val FALL_ACCELERATION = .03f

    fun initialize(state: StepState, input: Scenario, parent: MutableUnit) {
        val layout = parent.definition.attachmentLayout ?: return
        parent.openingFallSpeed = OPENING_FALL_SPEED
        parent.attachmentIds = layout.slots.asReversed().associate { slot ->
            slot.number to UnitRegistration.attached(state, input, parent, slot).unitId
        }
    }

    fun advanceOpeningFall(parent: MutableUnit, delta: Float) {
        if (parent.openingFallSpeed == 0f) return
        parent.openingFallSpeed += FALL_ACCELERATION * delta
        val height = parent.height.toFloat() - parent.openingFallSpeed * delta
        parent.height = height.coerceAtLeast(0f).toDouble()
        if (height <= 1f) parent.openingFallSpeed = 0f
    }

    fun position(parent: MutableUnit, slot: AttachmentSlotDefinition): Position {
        val forward = ScreenPlane.displacement(parent.heading, slot.y)
        val right = ScreenPlane.displacement(parent.heading + 90.0, slot.x)
        return Position(parent.position.x + forward.x + right.x, parent.position.y + forward.y + right.y)
    }

    fun heading(parent: MutableUnit, slot: AttachmentSlotDefinition): Double =
        ScreenPlane.normalize(parent.heading + slot.idleHeading)

    fun synchronize(parent: MutableUnit, child: MutableUnit, slot: AttachmentSlotDefinition) {
        val heading = heading(parent, slot)
        val turn = ScreenPlane.difference(heading, child.heading)
        child.position = position(parent, slot)
        child.heading = heading
        if (turn != 0.0) {
            for (state in child.namedWeapons?.values ?: listOfNotNull(child.combat)) {
                state.turretHeading = ScreenPlane.normalize(state.turretHeading + turn)
            }
        }
        child.height = parent.height
    }

    private fun childId(parent: MutableUnit, number: Int): String = parent.attachmentIds.getValue(number)

    fun overview(state: StepState, parent: MutableUnit): AttachmentOverview? {
        val layout = parent.definition.attachmentLayout?.takeIf { parent.alive && !parent.reclaimed } ?: return null
        val slots = layout.slots.mapNotNull { slot ->
            val child = state.units[childId(parent, slot.number)] ?: return@mapNotNull null
            AttachmentSlotObservation(slot.number, child.unitId, child.typeId, child.constructionProgress)
        }
        return AttachmentOverview(parent.selectedAttachmentSlot, immutable(slots))
    }

    fun observe(state: StepState, parent: MutableUnit, overview: AttachmentOverview): UnitActionsObservation {
        val actions = overview.slots.mapTo(mutableListOf()) { slot ->
            UnitActionObservation("selectSlot${slot.number}", null, null, 0, true)
        }
        actions += UnitActionObservation("selectSlotBuild", null, null, 0, overview.selectedSlot != null)
        val child = overview.selectedSlot?.let { state.units[childId(parent, it)] }
        if (child != null) {
            val profile = checkNotNull(child.definition.attachmentModule)
            val order = child.attachmentOrder
            val cooldown = kotlin.math.ceil(child.attachmentCooldown * 1000.0 / 60.0).toInt()
            val ready = order == null && cooldown == 0
            for (install in profile.installs) {
                val queued = (order as? AttachmentOrder.Install)?.action == install
                val balance = checkNotNull(state.productionLedger).balances.getValue(child.ownerPlayerId)
                actions += UnitActionObservation(install.action, 0.takeIf { queued }, 0f.takeIf { queued },
                    cooldown, ready && balance >= install.cost)
            }
            if (!BuildingWork.isReady(child) && profile.cancel != null) {
                actions += UnitActionObservation("cancel", null, child.constructionProgress, cooldown, ready)
            }
            if (BuildingWork.isReady(child) && profile.reclaim != null) {
                val queued = order as? AttachmentOrder.Reclaim
                actions += UnitActionObservation("reclaim", 0.takeIf { queued != null },
                    queued?.let { 1f - it.remaining / it.action.ticks }, cooldown, ready)
            }
        }
        return UnitActionsObservation(immutable(actions), 0f)
    }

    fun request(state: StepState, parent: MutableUnit, action: UnitAction): SimulationError? {
        val layout = checkNotNull(parent.definition.attachmentLayout)
        if (action.target != null) return SimulationError("invalid-target", "Slot controls take no ground target")
        val name = action.actionName
        if (name.startsWith("selectSlot")) {
            if (action.cancelQueued) return SimulationError("invalid-slot-action", "Selection cannot be cancelled")
            if (name == "selectSlotBuild") {
                parent.selectedAttachmentSlot = null
                return null
            }
            val selected = layout.slots.singleOrNull { name == "selectSlot${it.number}" }
                ?: return SimulationError("unknown-attachment-slot", "The chassis has no such slot")
            parent.selectedAttachmentSlot = selected.number
            return null
        }
        val selected = parent.selectedAttachmentSlot
            ?: return SimulationError("attachment-slot-required", "Select an attachment slot first")
        val child = state.units[childId(parent, selected)]?.takeIf { it.alive }
            ?: return SimulationError("attachment-slot-unavailable", "The selected slot is unavailable")
        val ledger = checkNotNull(state.productionLedger)
        val profile = checkNotNull(child.definition.attachmentModule)
        val order = child.attachmentOrder
        if (action.cancelQueued) {
            when {
                order is AttachmentOrder.Install && name == order.action.action ->
                    state.productionLedger = ledger.refund(child.ownerPlayerId, order.action.cost)
                order is AttachmentOrder.Reclaim && name == "reclaim" -> Unit
                else -> return SimulationError("attachment-order-absent", "No matching cancellable slot order")
            }
            child.attachmentOrder = null
            return null
        }
        if (order != null) return SimulationError("attachment-slot-busy", "The slot already has an order")
        if (child.attachmentCooldown > 0f)
            return SimulationError("attachment-slot-cooldown", "The slot is cooling down")
        val install = profile.installs.singleOrNull { it.action == name }
        child.attachmentOrder = when {
            install != null -> {
                state.productionLedger = ledger.charge(child.ownerPlayerId, install.cost)
                    ?: return SimulationError("insufficient-funds", "The slot requires ${install.cost} credits")
                AttachmentOrder.Install(install)
            }
            name == "cancel" && !BuildingWork.isReady(child) && profile.cancel != null ->
                AttachmentOrder.Cancel(profile.cancel)
            name == "reclaim" && BuildingWork.isReady(child) && profile.reclaim != null ->
                AttachmentOrder.Reclaim(profile.reclaim, profile.reclaim.ticks)
            else -> return SimulationError("unsupported-attachment-action", "This action is unavailable for the selected slot")
        }
        return null
    }

    fun advance(state: StepState, input: Scenario, parent: MutableUnit, child: MutableUnit, delta: Float) {
        child.attachmentCooldown = maxOf(0f, child.attachmentCooldown - delta)
        when (val order = child.attachmentOrder) {
            is AttachmentOrder.Install -> {
                convert(input, child, order.action.targetTypeId, 0f)
                completed(state, parent, child, "attachment-install-started")
            }
            is AttachmentOrder.Cancel -> {
                remove(state, input, parent, child, order.action)
                child.attachmentCooldown = order.action.ticks
            }
            is AttachmentOrder.Reclaim -> {
                if (order.remaining <= delta) remove(state, input, parent, child, order.action)
                else child.attachmentOrder = order.copy(remaining = order.remaining - delta)
            }
            null -> {
                if (BuildingWork.isReady(child)) return
                val rate = checkNotNull(child.definition.attachmentModule).selfBuildRate
                child.constructionProgress = minOf(1f, child.constructionProgress + rate * delta)
                if (BuildingWork.isReady(child)) state.pendingEvents += Event("attachment-install-completed", child.unitId)
            }
        }
    }

    private fun remove(state: StepState, input: Scenario, parent: MutableUnit, child: MutableUnit, action: AttachmentRemoval) {
        state.productionLedger = checkNotNull(state.productionLedger).refund(child.ownerPlayerId, action.refund)
        convert(input, child, action.targetTypeId, 1f)
        completed(state, parent, child, "attachment-slot-emptied")
    }

    private fun convert(input: Scenario, child: MutableUnit, targetTypeId: String, progress: Float) {
        val previousMaximum = checkNotNull(child.definition.maximumHealth)
        val fraction = (checkNotNull(child.health) / previousMaximum).coerceIn(0.0, 1.0)
        val next = input.unitDefinitions.getValue(targetTypeId)
        child.definition = next
        child.typeId = next.id
        child.health = maxOf(1.0, checkNotNull(next.maximumHealth) * fraction)
        child.constructionProgress = progress
        child.combat = next.combat?.let { MutableCombat(child.heading) }
        child.namedWeapons = NamedWeaponRuntime.initialStates(next.combat, child.heading, child.combat)
        child.clearWeaponTargets()
        child.attachmentOrder = null
    }

    private fun completed(state: StepState, parent: MutableUnit, child: MutableUnit, event: String) {
        val layout = checkNotNull(parent.definition.attachmentLayout)
        parent.attachmentMoveSpeed = .4.takeIf {
            layout.slots.any { slot -> state.units[childId(parent, slot.number)]?.definition?.combat != null }
        }
        state.pendingEvents += Event(event, child.unitId)
    }

    fun settleLifecycle(state: StepState, input: Scenario) {
        if (input.unitDefinitions.values.none { it.attachmentLayout != null }) return
        for (child in state.units.values) {
            val binding = child.attachment ?: continue
            val parent = state.units[binding.parentId]
            if (parent == null || !parent.alive || parent.reclaimed) {
                val install = child.attachmentOrder as? AttachmentOrder.Install
                if (install != null) state.productionLedger =
                    checkNotNull(state.productionLedger).refund(child.ownerPlayerId, install.action.cost)
                child.reclaimed = true
                state.pendingEvents += Event("attachment-removed", child.unitId)
            } else if (checkNotNull(child.health) < 1.0 && child.definition.attachmentModule?.empty == false) {
                convert(input, child, binding.slot.initialTypeId, 1f)
                completed(state, parent, child, "attachment-slot-destroyed")
            }
        }
        state.units.entries.removeIf { (_, unit) -> unit.attachment != null && unit.reclaimed }
    }

    fun validateCandidateUnit(state: StepState, unit: MutableUnit) {
        val binding = unit.attachment
        if (binding != null) {
            val parent = state.units[binding.parentId]
            require(parent != null && parent.alive && !parent.reclaimed && parent.ownerPlayerId == unit.ownerPlayerId &&
                binding.slot in checkNotNull(parent.definition.attachmentLayout).slots && unit.carriedBy == null &&
                unit.definition.attachmentModule != null && unit.unitId == childId(parent, binding.slot.number))
        } else require(unit.definition.attachmentModule == null)
        if (unit.alive && !unit.reclaimed) unit.definition.attachmentLayout?.let { layout ->
            require(layout.slots.all { slot -> state.units[childId(unit, slot.number)]?.attachment == AttachmentBinding(unit.unitId, slot) })
        }
    }

    fun validateDefinitions(input: Scenario): SimulationError? {
        if (input.unitDefinitions.values.any { definition ->
                (definition.attachmentLayout != null || definition.id == ModularSpiderRules.ID) &&
                    !ModularSpiderRules.supportsChassis(definition)
            }) return SimulationError("unsupported-attachment-layout", "Expected the qualified Modular Spider chassis")
        for (definition in input.unitDefinitions.values) {
            if ((definition.attachmentModule != null || definition.id in setOf(ModularSpiderRules.EMPTY, ModularSpiderRules.GUN)) &&
                !ModularSpiderRules.supportsModule(definition)) {
                return SimulationError("unsupported-attachment-module", "Expected a qualified attachment module")
            }
            if (ModularSpiderRules.missingReference(definition, input.unitDefinitions) != null) {
                return SimulationError("bad-reference", "The attachment graph requires its qualified slot definitions")
            }
        }
        if (input.units.any { input.unitDefinitions.getValue(it.typeId).attachmentModule != null }) {
            return SimulationError("invalid-spawn", "Attached modules are created only by their chassis")
        }
        val births = input.units.sumOf { input.unitDefinitions.getValue(it.typeId).attachmentLayout?.slots?.size?.toLong() ?: 0L }
        val nextObjectId = input.combatContract?.nextObjectId ?: 1L
        if (births > 0 && nextObjectId > Long.MAX_VALUE - births - 1)
            return SimulationError("invalid-combat-contract", "Not enough object identities for the initial attachments")
        return null
    }
}
