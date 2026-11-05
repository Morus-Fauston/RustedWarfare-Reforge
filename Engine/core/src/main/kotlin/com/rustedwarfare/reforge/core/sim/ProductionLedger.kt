package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildProfile
import java.util.Collections

/** Trusted factory registration; command authority remains the simulation's responsibility. */
internal class FactoryProductionSpec(val ownerPlayerId: String, products: Map<String, BuildProfile>,
    upgradeActions: List<com.rustedwarfare.reforge.core.content.UpgradeActionProfile> = emptyList()) {
    constructor(ownerPlayerId: String, typeId: String, build: BuildProfile) : this(ownerPlayerId, mapOf(typeId to build))
    val products: Map<String, BuildProfile> = Collections.unmodifiableMap(LinkedHashMap(products))
    val upgradeActions = immutable(upgradeActions)
    fun valid(): Boolean = (products.isNotEmpty() || upgradeActions.isNotEmpty()) && products.all { (id, build) ->
        id.isNotBlank() && build.price >= 0L && build.rate.isFinite() && build.rate > 0f } &&
        upgradeActions.all { it.actionKey.isNotBlank() && it.targetTypeId.isNotBlank() &&
            it.price >= 0L && it.rate.isFinite() && it.rate > 0f }
}
internal sealed interface PendingFactoryWork {
    val progress: Float
    val cost: Long
    val rate: Float
}
internal data class PendingProduction(val typeId: String, val unitNonce: String, override val progress: Float,
    override val cost: Long, override val rate: Float) : PendingFactoryWork
internal data class FactoryUpgradeTick(val ledger: ProductionLedger, val completed: PendingUpgrade? = null)
internal data class FactoryUpgradeRemoval(val ledger: ProductionLedger, val removed: List<PendingUpgrade>)
internal data class ProductionCompletion(val factoryId: String, val ownerPlayerId: String, val typeId: String, val unitNonce: String)
internal data class ProductionTick(val ledger: ProductionLedger, val completed: ProductionCompletion? = null)
internal enum class ProductionError { UNKNOWN_FACTORY, INACTIVE_FACTORY, INVALID_TYPE, INVALID_NONCE,
    DUPLICATE_NONCE, INSUFFICIENT_FUNDS, EMPTY_QUEUE }
internal sealed interface ProductionChange {
    data class Accepted(val ledger: ProductionLedger) : ProductionChange
    data class Rejected(val error: ProductionError) : ProductionChange
}

/** Pure candidate bookkeeping. Completion describes work; it never fabricates or publishes a unit. */
internal class ProductionLedger private constructor(
    private val integerBalances: Map<String, Long>?,
    private val factories: Map<String, FactoryProductionSpec>,
    private val queues: Map<String, List<PendingFactoryWork>> = emptyMap(),
    private val nonces: Map<String, Set<String>> = emptyMap(),
    private val inactive: Set<String> = emptySet(),
    private val preciseBalances: Map<String, Double>? = null,
) {
    val balances: Map<String, Long> get() = integerBalances ?: Collections.unmodifiableMap(
        checkNotNull(preciseBalances).mapValuesTo(linkedMapOf()) { it.value.toLong() })
    init { require((integerBalances == null) != (preciseBalances == null)) }

    companion object {
        private val nonceFormat = Regex("[0-9a-f]{32}")
        private fun <K, V> freeze(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
        fun initial(balances: Map<String, Long>, factories: Map<String, FactoryProductionSpec>,
                    incomeEnabled: Boolean = false): ProductionLedger {
            require(balances.values.all { it >= 0L }) { "Initial balances must be nonnegative" }
            require(factories.values.all { it.ownerPlayerId in balances && it.valid() }) { "Invalid factory production registration" }
            return ProductionLedger(balances.takeUnless { incomeEnabled }?.let { freeze(it) },
                freeze(factories),
                queues = freeze(emptyMap()), nonces = freeze(emptyMap()),
                inactive = Collections.unmodifiableSet(LinkedHashSet()),
                preciseBalances = balances.takeIf { incomeEnabled }?.mapValues { it.value.toDouble() }?.let { freeze(it) })
        }
    }

    private fun copy(integerBalances: Map<String, Long>? = this.integerBalances,
                     preciseBalances: Map<String, Double>? = this.preciseBalances,
                     queues: Map<String, List<PendingFactoryWork>> = this.queues,
                     nonces: Map<String, Set<String>> = this.nonces,
                     inactive: Set<String> = this.inactive,
                     factories: Map<String, FactoryProductionSpec> = this.factories) =
        // Collection arguments come only from the private frozen update helpers or initial().
        // Balance changes must not scan queues or historical nonce sets.
        ProductionLedger(
            integerBalances?.let { if (it === this.integerBalances) it else freeze(it) },
            factories, queues, nonces, inactive,
            preciseBalances?.let { if (it === this.preciseBalances) it else freeze(it) })

    private fun changedQueue(factoryId: String, items: List<PendingFactoryWork>) =
        Collections.unmodifiableMap(queues + (factoryId to immutable(items)))

    private fun reservedNonce(owner: String, nonce: String): Map<String, Set<String>> {
        val used = LinkedHashSet(nonces[owner].orEmpty())
        used += nonce
        return Collections.unmodifiableMap(nonces + (owner to Collections.unmodifiableSet(used)))
    }

    fun register(factoryId: String, spec: FactoryProductionSpec): ProductionLedger {
        require(factoryId !in factories && spec.ownerPlayerId in balances && spec.valid()) { "Invalid factory registration" }
        return copy(factories = Collections.unmodifiableMap(factories + (factoryId to spec)))
    }

    private fun work(factoryId: String): List<PendingFactoryWork> = queues[factoryId].orEmpty()
    /** Classification projections never determine advancement order. */
    fun queue(factoryId: String): List<PendingProduction> = immutable(work(factoryId).filterIsInstance<PendingProduction>())
    fun upgradeQueue(factoryId: String): List<PendingUpgrade> = immutable(work(factoryId).filterIsInstance<PendingUpgrade>())
    fun upgradeAtHead(factoryId: String): Boolean = work(factoryId).firstOrNull() is PendingUpgrade
    fun enqueueUpgrade(factoryId: String, action: com.rustedwarfare.reforge.core.content.UpgradeActionProfile): ProductionLedger {
        require(isActive(factoryId) && action in factories.getValue(factoryId).upgradeActions &&
            upgradeQueue(factoryId).none { it.actionKey == action.actionKey })
        return copy(queues = changedQueue(factoryId, work(factoryId) + PendingUpgrade(action.actionKey,
            action.targetTypeId, 0f, action.price, action.rate)))
    }
    fun retainUpgrades(factoryId: String, allowed: Set<String>): FactoryUpgradeRemoval {
        val removed = upgradeQueue(factoryId).filter { it.actionKey !in allowed }
        if (removed.isEmpty()) return FactoryUpgradeRemoval(this, emptyList())
        return FactoryUpgradeRemoval(copy(queues = changedQueue(factoryId,
            work(factoryId).filter { it !is PendingUpgrade || it.actionKey in allowed })), removed)
    }
    fun cancelUpgrade(factoryId: String, actionKey: String): FactoryUpgradeRemoval =
        retainUpgrades(factoryId, upgradeQueue(factoryId).mapTo(mutableSetOf()) { it.actionKey } - actionKey)
    fun advanceUpgrade(factoryId: String): FactoryUpgradeTick {
        if (!isActive(factoryId)) return FactoryUpgradeTick(this)
        val items = work(factoryId)
        val head = items.firstOrNull() as? PendingUpgrade ?: return FactoryUpgradeTick(this)
        val progress = head.progress + head.rate
        require(progress.isFinite()) { "Factory upgrade progress must remain finite" }
        return if (progress >= 1f) FactoryUpgradeTick(copy(queues = changedQueue(factoryId, items.drop(1))), head)
        else FactoryUpgradeTick(copy(queues = changedQueue(factoryId, listOf(head.copy(progress = progress)) + items.drop(1))))
    }
    fun upgradeObservation(): Map<String, List<PendingUpgradeObservation>> = factories.filter { (id, spec) ->
        spec.upgradeActions.isNotEmpty() || upgradeQueue(id).isNotEmpty()
    }.mapValues { (id, _) -> upgradeQueue(id).map {
        PendingUpgradeObservation(it.actionKey, it.targetTypeId, it.progress, it.cost, it.rate) } }
    fun validateUpgrades(units: Map<String, MutableUnit>) {
        for ((id, _) in queues) for (item in upgradeQueue(id)) {
            val unit = units.getValue(id)
            val action = unit.definition.upgrades?.actions?.singleOrNull { it.actionKey == item.actionKey }
            require(unit.alive && action != null && item.targetTypeId == action.targetTypeId && item.cost == action.price && item.rate == action.rate)
        }
    }
    fun replaceFactory(factoryId: String, spec: FactoryProductionSpec): ProductionLedger {
        require(isActive(factoryId) && spec.valid() && factories.getValue(factoryId).ownerPlayerId == spec.ownerPlayerId)
        var refunded = this
        for (item in queue(factoryId)) if (item.typeId !in spec.products) refunded = refunded.refund(spec.ownerPlayerId, item.cost)
        return refunded.copy(factories = Collections.unmodifiableMap(factories + (factoryId to spec)),
            queues = changedQueue(factoryId, work(factoryId).filter { it !is PendingProduction || it.typeId in spec.products }))
    }
    fun observe(): EconomyObservation = EconomyObservation(balances, factories.keys.sorted().associateWith { factoryId ->
        queue(factoryId).map { QueuedProductionObservation(it.typeId, it.unitNonce, it.progress, it.cost, it.rate) }
    }, preciseBalances)
    fun isActive(factoryId: String): Boolean = factoryId in factories && factoryId !in inactive

    fun charge(owner: String, credits: Long): ProductionLedger? {
        require(credits >= 0L)
        preciseBalances?.let {
            val balance = it.getValue(owner)
            if (balance < credits.toDouble()) return null
            return copy(preciseBalances = it + (owner to (balance - credits.toDouble())))
        }
        val balance = checkNotNull(integerBalances).getValue(owner)
        if (balance < credits) return null
        return copy(integerBalances = integerBalances + (owner to Math.subtractExact(balance, credits)))
    }

    fun refund(owner: String, credits: Long): ProductionLedger {
        require(credits >= 0L)
        preciseBalances?.let {
            return copy(preciseBalances = it + (owner to (it.getValue(owner) + credits.toDouble())))
        }
        val amounts = checkNotNull(integerBalances)
        return copy(integerBalances = amounts + (owner to Math.addExact(amounts.getValue(owner), credits)))
    }

    fun creditIncome(owner: String, credits: Float): ProductionLedger {
        require(credits.isFinite() && credits >= 0f)
        val amounts = checkNotNull(preciseBalances)
        return copy(preciseBalances = amounts + (owner to minOf(amounts.getValue(owner) + credits.toDouble(), 999999999.0)))
    }

    fun creditReclaim(owner: String, credits: Double): ProductionLedger {
        require(credits.isFinite() && credits >= 0.0)
        val amounts = checkNotNull(preciseBalances)
        return copy(preciseBalances = amounts + (owner to (amounts.getValue(owner) + credits)))
    }

    fun validateCandidate() {
        require(integerBalances?.values?.all { it >= 0L } != false &&
            preciseBalances?.values?.all { it.isFinite() && it >= 0.0 } != false) { "Production balance must remain finite and nonnegative" }
        for ((factoryId, queue) in queues) {
            val factory = factories.getValue(factoryId)
            require(factoryId !in inactive || queue.isEmpty()) { "Inactive factory must not retain pending work" }
            require(queue.filterIsInstance<PendingUpgrade>().map { it.actionKey }.toSet().size == queue.count { it is PendingUpgrade })
            for (item in queue) require(item.cost >= 0L && item.progress.isFinite() && item.progress in 0f..<1f &&
                item.rate.isFinite() && item.rate > 0f &&
                (item !is PendingProduction || item.typeId in factory.products && item.unitNonce in nonces[factory.ownerPlayerId].orEmpty())) {
                "Invalid factory work candidate"
            }
        }
    }
    fun enqueue(factoryId: String, typeId: String, unitNonce: String): ProductionChange {
        val factory = factories[factoryId] ?: return ProductionChange.Rejected(ProductionError.UNKNOWN_FACTORY)
        if (!isActive(factoryId)) return ProductionChange.Rejected(ProductionError.INACTIVE_FACTORY)
        val build = factory.products[typeId] ?: return ProductionChange.Rejected(ProductionError.INVALID_TYPE)
        if (!nonceFormat.matches(unitNonce)) return ProductionChange.Rejected(ProductionError.INVALID_NONCE)
        if (unitNonce in nonces[factory.ownerPlayerId].orEmpty()) return ProductionChange.Rejected(ProductionError.DUPLICATE_NONCE)
        val paid = charge(factory.ownerPlayerId, build.price)
            ?: return ProductionChange.Rejected(ProductionError.INSUFFICIENT_FUNDS)
        val next = paid.copy(
            queues = paid.changedQueue(factoryId, work(factoryId) + PendingProduction(typeId, unitNonce, 0f, build.price, build.rate)),
            nonces = paid.reservedNonce(factory.ownerPlayerId, unitNonce))
        return ProductionChange.Accepted(next)
    }

    fun cancel(factoryId: String, typeId: String): ProductionChange {
        val factory = factories[factoryId] ?: return ProductionChange.Rejected(ProductionError.UNKNOWN_FACTORY)
        if (!isActive(factoryId)) return ProductionChange.Rejected(ProductionError.INACTIVE_FACTORY)
        if (typeId !in factory.products) return ProductionChange.Rejected(ProductionError.INVALID_TYPE)
        val queue = work(factoryId)
        val index = queue.indexOfLast { it is PendingProduction && it.typeId == typeId }
        if (index < 0) return ProductionChange.Rejected(ProductionError.EMPTY_QUEUE)
        val kept = queue.filterIndexed { itemIndex, _ -> itemIndex != index }
        return ProductionChange.Accepted(refund(factory.ownerPlayerId, queue[index].cost).copy(
            queues = changedQueue(factoryId, kept)))
    }

    /** One fixed sim-tick, one head; excess progress is never transferred to another item. */
    fun advance(factoryId: String): ProductionTick {
        if (!isActive(factoryId)) return ProductionTick(this)
        val queue = work(factoryId)
        val head = queue.firstOrNull() as? PendingProduction ?: return ProductionTick(this)
        val progress = head.progress + head.rate
        require(progress.isFinite()) { "Production progress must remain finite" }
        if (progress < 1f) {
            return ProductionTick(copy(
                queues = changedQueue(factoryId, listOf(head.copy(progress = progress)) + queue.drop(1))))
        }
        val completed = ProductionCompletion(factoryId, factories.getValue(factoryId).ownerPlayerId, head.typeId, head.unitNonce)
        return ProductionTick(copy(queues = changedQueue(factoryId, queue.drop(1))), completed)
    }

    /** Pending work is refunded before the returned factory becomes inactive. */
    fun destroyFactory(factoryId: String): ProductionLedger {
        if (!isActive(factoryId)) return this
        val factory = factories.getValue(factoryId)
        var refunded = this
        for (item in work(factoryId)) refunded = refunded.refund(factory.ownerPlayerId, item.cost)
        return refunded.copy(queues = changedQueue(factoryId, emptyList()),
            inactive = Collections.unmodifiableSet(LinkedHashSet(inactive).also { it += factoryId }))
    }
}
