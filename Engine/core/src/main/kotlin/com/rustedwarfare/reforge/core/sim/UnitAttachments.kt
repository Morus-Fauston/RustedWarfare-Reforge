package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.AttachmentAutomaticAction
import com.rustedwarfare.reforge.core.content.AttachmentSlotDefinition
import com.rustedwarfare.reforge.core.content.AttachmentInstall
import com.rustedwarfare.reforge.core.content.AttachmentRemoval
import com.rustedwarfare.reforge.core.content.AttachmentUpgrade
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.ModularSpiderRules
import com.rustedwarfare.reforge.core.content.ModularSpiderUtilityForms.BLINK_DURATION
import com.rustedwarfare.reforge.core.content.ModularSpiderWeaponForms

internal data class AttachmentBinding(val parentId: String, val slot: AttachmentSlotDefinition)

internal sealed interface AttachmentOrder {
    sealed interface Paid : AttachmentOrder {
        val name: String
        val cost: Long
    }
    data class Install(val action: AttachmentInstall) : Paid {
        override val name get() = action.action
        override val cost get() = action.cost
    }
    data class Upgrade(val action: AttachmentUpgrade, val remaining: Float) : Paid {
        override val name get() = action.action
        override val cost get() = action.cost
        val progress get() = (action.ticks - remaining) / action.ticks
    }
    data class Cancel(val action: AttachmentRemoval, val displacedUpgrade: Upgrade?) : AttachmentOrder
    data class Reclaim(val action: AttachmentRemoval, val remaining: Float) : AttachmentOrder
}

/** Initial creation and pose synchronization for real units attached to a chassis. */
internal object UnitAttachments {
    private const val AUTOMATIC_COOLDOWN = 60f
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

    fun observe(state: StepState, parent: MutableUnit, overview: AttachmentOverview, time: Double): UnitActionsObservation {
        val actions = overview.slots.mapTo(mutableListOf()) { slot ->
            UnitActionObservation("selectSlot${slot.number}", null, null, 0, true)
        }
        actions += UnitActionObservation("selectSlotBuild", null, null, 0, overview.selectedSlot != null)
        val child = overview.selectedSlot?.let { state.units[childId(parent, it)] }
        var ammo: Int? = null
        var online: Boolean? = null
        var queue: List<QueuedUnitActionObservation> = emptyList()
        parent.spiderBlink?.let { pending ->
            val progress = AttachmentBlink.progress(pending)
            actions += UnitActionObservation(AttachmentBlink.QUEUED, 0, progress, 0, false)
            queue = listOf(QueuedUnitActionObservation(AttachmentBlink.QUEUED, progress, pending.target))
        }
        if (child != null) {
            val profile = checkNotNull(child.definition.attachmentModule)
            val order = child.attachmentOrder
            val cooldown = kotlin.math.ceil(child.attachmentCooldown * 1000.0 / 60.0).toInt()
            val ready = order == null && cooldown == 0
            val balance = checkNotNull(state.productionLedger).balances.getValue(child.ownerPlayerId)
            val slotOrderIndex = queue.size
            if (child.typeId == ModularSpiderRules.ANTI_NUKE) {
                ammo = child.attachmentInterceptor.ammo
                online = !child.attachmentInterceptor.offline
                val offset = queue.size
                actions += AttachmentInterceptor.actions(child, balance).map { action ->
                    action.copy(queueIndex = action.queueIndex?.plus(offset))
                }
                queue += AttachmentInterceptor.queue(child)
            }
            if (child.typeId == ModularSpiderRules.BLINK) {
                val blink = child.blinkRequest
                val blinkCooldown = maxOf(cooldown, AttachmentBlink.cooldown(time, child))
                actions += UnitActionObservation(AttachmentBlink.REQUEST, queue.size.takeIf { blink != null },
                    0f.takeIf { blink != null }, blinkCooldown, blink == null && blinkCooldown == 0)
                if (blink != null) queue += QueuedUnitActionObservation(AttachmentBlink.REQUEST, 0f, blink)
            }
            for (install in profile.installs) {
                val queued = (order as? AttachmentOrder.Install)?.action == install
                actions += UnitActionObservation(install.action, slotOrderIndex.takeIf { queued }, 0f.takeIf { queued },
                    cooldown, ready && balance >= install.cost)
            }
            for (upgrade in profile.upgrades) {
                val queued = (order as? AttachmentOrder.Upgrade)?.takeIf { it.action == upgrade }
                actions += UnitActionObservation(upgrade.action, slotOrderIndex.takeIf { queued != null }, queued?.progress,
                    cooldown, ready && balance >= upgrade.cost)
            }
            if (!BuildingWork.isReady(child) && profile.cancel != null) {
                val available = cooldown == 0 && (order == null || order is AttachmentOrder.Upgrade)
                actions += UnitActionObservation("cancel", null, child.constructionProgress, cooldown, available)
            }
            if (BuildingWork.isReady(child) && profile.reclaim != null) {
                val queued = order as? AttachmentOrder.Reclaim
                val index = if (online == null) queue.size else queue.indexOfFirst { it.name == "reclaim" }
                actions += UnitActionObservation("reclaim", index.takeIf { queued != null },
                    queued?.let { 1f - it.remaining / it.action.ticks }, cooldown,
                    ready)
            }
            val queuedSlotOrder = when (order) {
                is AttachmentOrder.Install -> QueuedUnitActionObservation(order.name, 0f, null)
                is AttachmentOrder.Upgrade -> QueuedUnitActionObservation(order.name, order.progress, null)
                is AttachmentOrder.Reclaim -> if (online == null)
                    QueuedUnitActionObservation("reclaim", 1f - order.remaining / order.action.ticks, null) else null
                else -> null
            }
            if (queuedSlotOrder != null) queue += queuedSlotOrder
        }
        return UnitActionsObservation(immutable(actions), 0f, ammo, immutable(queue), online)
    }

    fun request(state: StepState, parent: MutableUnit, action: UnitAction, collision: MapDefinition): SimulationError? {
        val layout = checkNotNull(parent.definition.attachmentLayout)
        val name = action.actionName
        if (name == AttachmentBlink.QUEUED)
            return SimulationError("unit-action-not-cancellable", "The chassis Blink is internal and cannot be cancelled")
        if (name != AttachmentBlink.REQUEST && action.target != null)
            return SimulationError("invalid-target", "Slot controls take no ground target")
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
        val profile = checkNotNull(child.definition.attachmentModule)
        val order = child.attachmentOrder
        if (name == AttachmentBlink.REQUEST && child.typeId == ModularSpiderRules.BLINK)
            return AttachmentBlink.request(state.simulationTime, child, action, collision)
        if (child.typeId == ModularSpiderRules.ANTI_NUKE && AttachmentInterceptor.handles(name)) {
            return AttachmentInterceptor.request(state, child, name, action.cancelQueued)
        }
        if (action.cancelQueued) {
            when {
                order is AttachmentOrder.Paid && name == order.name -> refundPayment(state, child)
                order is AttachmentOrder.Reclaim && name == "reclaim" ->
                    child.attachmentInterceptor = child.attachmentInterceptor.copy(roundsAheadOfReclaim = 0)
                else -> return SimulationError("attachment-order-absent", "No matching cancellable slot order")
            }
            child.attachmentOrder = null
            return null
        }
        if (child.attachmentCooldown > 0f)
            return SimulationError("attachment-slot-cooldown", "The slot is cooling down")
        if (name == "cancel" && !BuildingWork.isReady(child) && profile.cancel != null &&
            (order == null || order is AttachmentOrder.Upgrade)) {
            child.attachmentOrder = AttachmentOrder.Cancel(profile.cancel, order)
            return null
        }
        if (order != null) return SimulationError("attachment-slot-busy", "The slot already has an order")
        val install = profile.installs.singleOrNull { it.action == name }
        val upgrade = profile.upgrades.singleOrNull { it.action == name }
        val next = when {
            install != null -> AttachmentOrder.Install(install)
            upgrade != null -> AttachmentOrder.Upgrade(upgrade, upgrade.ticks)
            name == "reclaim" && BuildingWork.isReady(child) && profile.reclaim != null ->
                AttachmentOrder.Reclaim(profile.reclaim, profile.reclaim.ticks)
            else -> return SimulationError("unsupported-attachment-action", "This action is unavailable for the selected slot")
        }
        if (next is AttachmentOrder.Paid) {
            state.productionLedger = checkNotNull(state.productionLedger).charge(child.ownerPlayerId, next.cost)
                ?: return SimulationError("insufficient-funds", "The slot requires ${next.cost} credits")
        }
        if (next is AttachmentOrder.Reclaim && child.typeId == ModularSpiderRules.ANTI_NUKE) {
            child.attachmentInterceptor = child.attachmentInterceptor.copy(
                roundsAheadOfReclaim = child.attachmentInterceptor.rounds.size)
        }
        child.attachmentOrder = next
        return null
    }

    fun advance(state: StepState, input: Scenario, parent: MutableUnit, child: MutableUnit, delta: Float) {
        child.attachmentCooldown = maxOf(0f, child.attachmentCooldown - delta)
        if (!BuildingWork.isReady(child)) {
            val rate = checkNotNull(child.definition.attachmentModule).selfBuildRate
            child.constructionProgress = minOf(1f, child.constructionProgress + rate * delta)
            if (BuildingWork.isReady(child)) state.pendingEvents += Event("attachment-install-completed", child.unitId)
        }
        when (val order = child.attachmentOrder) {
            is AttachmentOrder.Install -> {
                convert(input, child, order.action.targetTypeId, 0f)
                completed(state, parent, child, "attachment-install-started")
            }
            is AttachmentOrder.Upgrade -> {
                if (!BuildingWork.isReady(child)) return
                if (order.remaining <= delta) {
                    convert(input, child, order.action.targetTypeId, child.constructionProgress)
                    completed(state, parent, child, "attachment-upgrade-completed")
                } else child.attachmentOrder = order.copy(remaining = order.remaining - delta)
            }
            is AttachmentOrder.Reclaim -> {
                if (child.blinkRequest != null || child.attachmentInterceptor.switchPending ||
                    child.attachmentInterceptor.roundsAheadOfReclaim != 0) return
                if (order.remaining <= delta) remove(state, input, parent, child, order.action)
                else child.attachmentOrder = order.copy(remaining = order.remaining - delta)
            }
            is AttachmentOrder.Cancel, null -> Unit
        }
    }

    fun advanceAutomatic(state: StepState, input: Scenario, parent: MutableUnit, child: MutableUnit, delta: Float) {
        val action = child.definition.attachmentModule?.automaticAction ?: return
        child.attachmentAutomaticCooldown = maxOf(0f, child.attachmentAutomaticCooldown - delta)
        if (child.attachmentAutomaticCooldown > 0f) return
        child.attachmentAutomaticCooldown = AUTOMATIC_COOLDOWN
        when (action) {
            is AttachmentAutomaticAction.RestoreParentShield -> {
                val shield = checkNotNull(parent.definition.shield)
                parent.shield = minOf(shield.maximum, parent.shield + action.amount)
            }
            is AttachmentAutomaticAction.ConvertWhenBuilt -> {
                val reclaim = child.attachmentOrder as? AttachmentOrder.Reclaim
                convert(input, child, action.targetTypeId, child.constructionProgress)
                if (reclaim != null && child.definition.attachmentModule?.reclaim == reclaim.action) {
                    child.attachmentOrder = reclaim
                }
                completed(state, parent, child, "attachment-module-activated")
            }
        }
    }

    private fun remove(state: StepState, input: Scenario, parent: MutableUnit, child: MutableUnit, action: AttachmentRemoval) {
        if (child.typeId == ModularSpiderRules.ANTI_NUKE) AttachmentInterceptor.discardQueue(state, child)
        state.productionLedger = checkNotNull(state.productionLedger).refund(child.ownerPlayerId, action.refund)
        convert(input, child, action.targetTypeId, 1f)
        completed(state, parent, child, "attachment-slot-emptied")
    }

    private fun refundPayment(state: StepState, child: MutableUnit) {
        val paid = when (val order = child.attachmentOrder) {
            is AttachmentOrder.Paid -> order
            is AttachmentOrder.Cancel -> order.displacedUpgrade
            else -> null
        } ?: return
        state.productionLedger = checkNotNull(state.productionLedger).refund(child.ownerPlayerId, paid.cost)
    }

    private fun convert(input: Scenario, child: MutableUnit, targetTypeId: String, progress: Float) {
        val previousMaximum = checkNotNull(child.definition.maximumHealth)
        val fraction = (checkNotNull(child.health) / previousMaximum).coerceIn(0.0, 1.0)
        val previousWeapons = child.namedWeapons
        val next = input.unitDefinitions.getValue(targetTypeId)
        child.definition = next
        child.typeId = next.id
        child.blinkRequest = null
        child.pointDefense = next.pointDefense?.let { MutablePointDefense(it.initialEnergy, child.attachmentLaserExhausted) }
        child.health = maxOf(1.0, checkNotNull(next.maximumHealth) * fraction)
        child.constructionProgress = progress
        child.combat = next.combat?.let { MutableCombat(child.heading) }
        child.namedWeapons = NamedWeaponRuntime.initialStates(next.combat, child.heading, child.combat)
        val currentWeapons = child.namedWeapons
        if (previousWeapons != null && currentWeapons != null) {
            val assembly = checkNotNull(next.combat).weaponAssembly
            for (slot in assembly.slots) {
                val mount = slot.definition
                val retained = previousWeapons[mount.name] ?: continue
                retained.cooldown = minOf(retained.cooldown, mount.shootDelay)
                retained.warmup = minOf(retained.warmup, mount.warmup)
                currentWeapons[mount.name] = retained
            }
            child.combat = currentWeapons.getValue(assembly.slots.first().definition.name)
        } else child.clearWeaponTargets()
        child.attachmentOrder = null
    }

    private fun completed(state: StepState, parent: MutableUnit, child: MutableUnit, event: String) {
        val layout = checkNotNull(parent.definition.attachmentLayout)
        fun attached(slot: AttachmentSlotDefinition): MutableUnit? = state.units[childId(parent, slot.number)]
        parent.attachmentMoveSpeed = when {
            layout.slots.any { slot -> attached(slot)?.let {
                it.typeId == ModularSpiderRules.SPEED && BuildingWork.isReady(it)
            } == true } -> .7
            layout.slots.any { slot -> attached(slot)?.typeId in ModularSpiderWeaponForms.byId } -> .4
            else -> null
        }
        state.pendingEvents += Event(event, child.unitId)
    }

    fun settleLifecycle(state: StepState, input: Scenario) {
        if (input.unitDefinitions.values.none { it.attachmentLayout != null }) return
        for (child in state.units.values) {
            val binding = child.attachment ?: continue
            val parent = state.units[binding.parentId]
            val cancel = child.attachmentOrder as? AttachmentOrder.Cancel
            if (parent == null || !parent.alive || parent.reclaimed) {
                refundPayment(state, child)
                AttachmentInterceptor.discardQueue(state, child)
                child.reclaimed = true
                state.missileTargets = state.missileTargets?.remove(child.unitId)
                state.pendingEvents += Event("attachment-removed", child.unitId)
            } else if (checkNotNull(child.health) < 1.0 && child.definition.attachmentModule?.empty == false) {
                refundPayment(state, child)
                AttachmentInterceptor.discardQueue(state, child)
                convert(input, child, binding.slot.initialTypeId, 1f)
                completed(state, parent, child, "attachment-slot-destroyed")
            } else if (cancel != null) {
                refundPayment(state, child)
                remove(state, input, parent, child, cancel.action)
                child.attachmentCooldown = cancel.action.ticks
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
            val defense = unit.attachmentInterceptor
            require(defense.ammo >= 0 && defense.ammo + defense.rounds.size <= 12 &&
                defense.rounds.all { it.isFinite() && it in 0f..<1f } &&
                defense.roundsAheadOfReclaim in 0..defense.rounds.size &&
                (defense.roundsAheadOfReclaim == 0 || unit.attachmentOrder is AttachmentOrder.Reclaim) &&
                (unit.typeId == ModularSpiderRules.ANTI_NUKE || defense.rounds.isEmpty() && !defense.switchPending))
            require(unit.blinkCooldownUntil.isFinite() && unit.blinkCooldownUntil >= 0.0 && unit.blinkRequest?.let {
                unit.typeId == ModularSpiderRules.BLINK && it.x.isFinite() && it.y.isFinite()
            } != false)
        } else require(unit.definition.attachmentModule == null)
        unit.spiderBlink?.let { pending ->
            require(unit.definition.attachmentLayout != null && pending.target.x.isFinite() && pending.target.y.isFinite() &&
                pending.remaining in 1..BLINK_DURATION)
        }
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
            if ((definition.attachmentModule != null || ModularSpiderRules.isModuleId(definition.id)) &&
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
