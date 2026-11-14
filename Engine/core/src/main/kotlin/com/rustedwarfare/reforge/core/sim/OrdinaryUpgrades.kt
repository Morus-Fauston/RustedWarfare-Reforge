package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.OrdinaryUpgradeContent
import com.rustedwarfare.reforge.core.content.UnitDefinition
import java.util.Collections

/** Trusted finite registration, never a scenario, protocol or content-plugin option. */
internal class UpgradeRegistration(extraTurrets: Map<String, Map<String, OrdinaryUpgradeContent.TurretTarget>> = emptyMap()) {
    val turrets: Map<String, Map<String, OrdinaryUpgradeContent.TurretTarget>> = Collections.unmodifiableMap(
        (mapOf("c_turret_t1" to OrdinaryUpgradeContent.turret) + extraTurrets).mapValues {
            Collections.unmodifiableMap(LinkedHashMap(it.value)) })
}

internal data class OrdinaryUpgradeChange(val upgrades: OrdinaryUpgrades, val ledger: ProductionLedger,
    val events: List<Event> = emptyList())
internal sealed interface OrdinaryUpgradeRequest {
    data class Accepted(val change: OrdinaryUpgradeChange) : OrdinaryUpgradeRequest
    data class Rejected(val error: SimulationError) : OrdinaryUpgradeRequest
}

/** Complete ordinary-upgrade lifecycle over loaded definitions and immutable candidate queues. */
internal class OrdinaryUpgrades private constructor(private val definitions: Map<String, UnitDefinition>,
    private val queues: UpgradeQueues) {
    companion object {
        fun validate(input: Scenario, registration: UpgradeRegistration = UpgradeRegistration()): SimulationError? =
            UpgradeRules.validate(input, registration)

        fun prepare(input: Scenario, registration: UpgradeRegistration = UpgradeRegistration()): OrdinaryUpgrades? {
            check(validate(input, registration) == null) { "Invalid ordinary upgrade registration" }
            if (input.unitDefinitions.values.none { it.upgrades != null }) return null
            return OrdinaryUpgrades(Collections.unmodifiableMap(LinkedHashMap(input.unitDefinitions)),
                UpgradeQueues.initial(emptySet()))
        }

        fun baseTypeId(typeId: String): String? = OrdinaryUpgradeContent.baseTypeId(typeId)
        fun constructionBaseTypeId(typeId: String): String? = OrdinaryUpgradeContent.constructionBaseTypeId(typeId)
    }

    private fun withQueues(next: UpgradeQueues) = if (next === queues) this else OrdinaryUpgrades(definitions, next)

    fun register(unitId: String, definition: UnitDefinition): OrdinaryUpgrades =
        if (definition.upgrades == null || definition.production != null) this else withQueues(queues.register(unitId))

    fun request(unit: MutableUnit, actionKey: String, cancel: Boolean, ledger: ProductionLedger): OrdinaryUpgradeRequest {
        fun rejected(code: String, message: String) = OrdinaryUpgradeRequest.Rejected(SimulationError(code, message))
        val profile = unit.definition.upgrades ?: return rejected("cannot-upgrade", "Unit cannot upgrade")
        val action = profile.actions.singleOrNull { it.actionKey == actionKey }
            ?: return rejected("unsupported-upgrade", "Unit cannot perform this upgrade")
        if (unit.definition.production != null) {
            val paid = if (cancel) {
                val removal = ledger.cancelUpgrade(unit.unitId, actionKey)
                val removed = removal.removed.singleOrNull()
                    ?: return rejected("empty-upgrade-queue", "No matching upgrade is queued")
                removal.ledger.refund(unit.ownerPlayerId, removed.cost)
            } else {
                if (ledger.upgradeQueue(unit.unitId).any { it.actionKey == actionKey })
                    return rejected("duplicate-upgrade", "This upgrade is already queued")
                val charged = ledger.charge(unit.ownerPlayerId, action.price)
                    ?: return rejected("insufficient-funds", "Insufficient funds")
                charged.enqueueUpgrade(unit.unitId, action)
            }
            return OrdinaryUpgradeRequest.Accepted(OrdinaryUpgradeChange(this, paid,
                listOf(Event(if (cancel) "upgrade-cancelled" else "upgrade-queued", unit.unitId))))
        }
        val next: UpgradeQueues
        val paid: ProductionLedger
        if (cancel) {
            val removal = queues.cancel(unit.unitId, actionKey)
            val removed = removal.removed.singleOrNull()
                ?: return rejected("empty-upgrade-queue", "No matching upgrade is queued")
            next = removal.queues
            paid = ledger.refund(unit.ownerPlayerId, removed.cost)
        } else {
            if (queues.queue(unit.unitId).any { it.actionKey == actionKey })
                return rejected("duplicate-upgrade", "This upgrade is already queued")
            paid = ledger.charge(unit.ownerPlayerId, action.price)
                ?: return rejected("insufficient-funds", "Insufficient funds")
            next = queues.enqueue(unit.unitId, action)
        }
        return OrdinaryUpgradeRequest.Accepted(OrdinaryUpgradeChange(withQueues(next), paid,
            listOf(Event(if (cancel) "upgrade-cancelled" else "upgrade-queued", unit.unitId))))
    }

    fun advance(unit: MutableUnit, ledger: ProductionLedger): OrdinaryUpgradeChange {
        val advanced: OrdinaryUpgrades
        val workingLedger: ProductionLedger
        val completed: PendingUpgrade?
        if (unit.definition.production != null) {
            val tick = ledger.advanceUpgrade(unit.unitId)
            advanced = this
            workingLedger = tick.ledger
            completed = tick.completed
        } else {
            val tick = queues.advance(unit.unitId)
            advanced = withQueues(tick.queues)
            workingLedger = ledger
            completed = tick.completed
        }
        if (completed == null) return OrdinaryUpgradeChange(advanced, workingLedger)
        val definition = definitions.getValue(completed.targetTypeId)
        check(unit.alive)
        val action = checkNotNull(unit.definition.upgrades?.actions?.singleOrNull {
            it.actionKey == completed.actionKey && it.targetTypeId == definition.id })
        val shield = if (unit.definition.className == "AuthoredEconomicBuilding")
            UnitShield.afterConversion(unit, definition.shield) else unit.shield
        val oldMaximum = checkNotNull(unit.definition.maximumHealth).toFloat()
        val nextMaximum = checkNotNull(definition.maximumHealth).toFloat()
        // Native sea/air tier setters change their menu/appearance without writing HP.
        val nativeTier = FactoryExit.supportsSeaFactory(unit.definition) || FactoryExit.supportsAirFactory(unit.definition)
        val health = if (nativeTier) checkNotNull(unit.health)
            else (checkNotNull(unit.health).toFloat() / oldMaximum * nextMaximum).toDouble()
        require(health.isFinite() && health in 0.0..nextMaximum.toDouble()) { "Converted health must remain finite and bounded" }
        unit.typeId = definition.id
        unit.definition = definition
        unit.health = health
        unit.shield = shield
        unit.speed = 0.0
        unit.combat?.let { it.cooldown = minOf(it.cooldown, checkNotNull(definition.combat).shootDelay) }
        val convertedLedger = definition.production?.let { production -> workingLedger.replaceFactory(unit.unitId,
            FactoryProductionSpec(unit.ownerPlayerId, production.unitTypeIds.associateWith { definitions.getValue(it).build!! },
                definition.upgrades?.actions.orEmpty())) } ?: workingLedger
        val credited = if (action.completionCredits == 0L) convertedLedger
            else convertedLedger.refund(unit.ownerPlayerId, action.completionCredits)
        val retained = advanced.retain(unit, credited,
            definition.upgrades?.actions?.mapTo(mutableSetOf()) { it.actionKey }.orEmpty())
        return retained.copy(events = listOf(Event("upgrade-completed", unit.unitId)) + retained.events)
    }

    fun discard(unit: MutableUnit, ledger: ProductionLedger): OrdinaryUpgradeChange = retain(unit, ledger, emptySet())

    private fun retain(unit: MutableUnit, ledger: ProductionLedger, allowed: Set<String>): OrdinaryUpgradeChange {
        if (unit.definition.production != null) {
            val removal = ledger.retainUpgrades(unit.unitId, allowed)
            var refunded = removal.ledger
            for (item in removal.removed) refunded = refunded.refund(unit.ownerPlayerId, item.cost)
            return OrdinaryUpgradeChange(this, refunded,
                if (removal.removed.isEmpty()) emptyList() else listOf(Event("upgrade-refunded", unit.unitId)))
        }
        val removal = queues.retain(unit.unitId) { item ->
            item.actionKey in allowed && unit.definition.upgrades?.actions?.any {
                it.actionKey == item.actionKey && it.targetTypeId == item.targetTypeId &&
                    it.price == item.cost && it.rate == item.rate
            } == true
        }
        var refunded = ledger
        for (item in removal.removed) refunded = refunded.refund(unit.ownerPlayerId, item.cost)
        return OrdinaryUpgradeChange(withQueues(removal.queues), refunded,
            if (removal.removed.isEmpty()) emptyList() else listOf(Event("upgrade-refunded", unit.unitId)))
    }

    fun observe(ledger: ProductionLedger): UpgradeObservation = UpgradeObservation(queues.observe().queues + ledger.upgradeObservation())
    fun validateCandidate(units: Map<String, MutableUnit>) = queues.validateCandidate(units)
}
