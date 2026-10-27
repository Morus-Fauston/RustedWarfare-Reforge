package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildProfile
import java.util.Collections

/** Trusted factory registration; command authority remains the simulation's responsibility. */
internal class FactoryProductionSpec(val ownerPlayerId: String, products: Map<String, BuildProfile>) {
    constructor(ownerPlayerId: String, typeId: String, build: BuildProfile) : this(ownerPlayerId, mapOf(typeId to build))
    val products: Map<String, BuildProfile> = Collections.unmodifiableMap(LinkedHashMap(products))
    fun valid(): Boolean = products.isNotEmpty() && products.all { (id, build) ->
        id.isNotBlank() && build.price >= 0L && build.rate.isFinite() && build.rate > 0f }
}
internal data class PendingProduction(val typeId: String, val unitNonce: String, val progress: Float,
    val cost: Long, val rate: Float)
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
    integerBalances: Map<String, Long>?,
    private val factories: Map<String, FactoryProductionSpec>,
    queues: Map<String, List<PendingProduction>> = emptyMap(),
    nonces: Map<String, Set<String>> = emptyMap(),
    inactive: Set<String> = emptySet(),
    preciseBalances: Map<String, Double>? = null,
) {
    private val integerBalances = integerBalances?.let { Collections.unmodifiableMap(LinkedHashMap(it)) }
    private val preciseBalances = preciseBalances?.let { Collections.unmodifiableMap(LinkedHashMap(it)) }
    val balances: Map<String, Long> get() = integerBalances ?: Collections.unmodifiableMap(
        checkNotNull(preciseBalances).mapValuesTo(linkedMapOf()) { it.value.toLong() })
    init { require((integerBalances == null) != (preciseBalances == null)) }
    private val queues = Collections.unmodifiableMap(queues.mapValuesTo(linkedMapOf()) { immutable(it.value) })
    private val nonces = Collections.unmodifiableMap(nonces.mapValuesTo(linkedMapOf()) {
        Collections.unmodifiableSet(LinkedHashSet(it.value)) })
    private val inactive = Collections.unmodifiableSet(LinkedHashSet(inactive))

    companion object {
        private val nonceFormat = Regex("[0-9a-f]{32}")
        fun initial(balances: Map<String, Long>, factories: Map<String, FactoryProductionSpec>,
                    incomeEnabled: Boolean = false): ProductionLedger {
            require(balances.values.all { it >= 0L }) { "Initial balances must be nonnegative" }
            require(factories.values.all { it.ownerPlayerId in balances && it.valid() }) { "Invalid factory production registration" }
            return ProductionLedger(balances.takeUnless { incomeEnabled },
                Collections.unmodifiableMap(LinkedHashMap(factories)),
                preciseBalances = balances.takeIf { incomeEnabled }?.mapValues { it.value.toDouble() })
        }
    }

    private fun copy(integerBalances: Map<String, Long>? = this.integerBalances,
                     preciseBalances: Map<String, Double>? = this.preciseBalances,
                     queues: Map<String, List<PendingProduction>> = this.queues,
                     nonces: Map<String, Set<String>> = this.nonces,
                     inactive: Set<String> = this.inactive,
                     factories: Map<String, FactoryProductionSpec> = this.factories) =
        ProductionLedger(integerBalances, factories, queues, nonces, inactive, preciseBalances)

    fun register(factoryId: String, spec: FactoryProductionSpec): ProductionLedger {
        require(factoryId !in factories && spec.ownerPlayerId in balances && spec.valid()) { "Invalid factory registration" }
        return copy(factories = Collections.unmodifiableMap(factories + (factoryId to spec)))
    }

    fun queue(factoryId: String): List<PendingProduction> = queues[factoryId].orEmpty()
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

    fun validateCandidate() {
        require(integerBalances?.values?.all { it >= 0L } != false &&
            preciseBalances?.values?.all { it.isFinite() && it >= 0.0 } != false) { "Production balance must remain finite and nonnegative" }
        for ((factoryId, queue) in queues) {
            val factory = factories.getValue(factoryId)
            require(factoryId !in inactive || queue.isEmpty()) { "Inactive factory must not retain pending work" }
            for (item in queue) require(item.typeId in factory.products && item.cost >= 0L &&
                item.progress.isFinite() && item.progress in 0f..<1f && item.rate.isFinite() && item.rate > 0f &&
                item.unitNonce in nonces[factory.ownerPlayerId].orEmpty()) { "Invalid production candidate" }
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
            queues = queues + (factoryId to (queue(factoryId) + PendingProduction(typeId, unitNonce, 0f, build.price, build.rate))),
            nonces = nonces + (factory.ownerPlayerId to (nonces[factory.ownerPlayerId].orEmpty() + unitNonce)))
        return ProductionChange.Accepted(next)
    }

    fun cancel(factoryId: String, typeId: String): ProductionChange {
        val factory = factories[factoryId] ?: return ProductionChange.Rejected(ProductionError.UNKNOWN_FACTORY)
        if (!isActive(factoryId)) return ProductionChange.Rejected(ProductionError.INACTIVE_FACTORY)
        if (typeId !in factory.products) return ProductionChange.Rejected(ProductionError.INVALID_TYPE)
        val queue = queue(factoryId)
        val index = queue.indexOfLast { it.typeId == typeId }
        if (index < 0) return ProductionChange.Rejected(ProductionError.EMPTY_QUEUE)
        val kept = queue.filterIndexed { itemIndex, _ -> itemIndex != index }
        return ProductionChange.Accepted(refund(factory.ownerPlayerId, queue[index].cost).copy(
            queues = queues + (factoryId to kept)))
    }

    /** One fixed sim-tick, one head; excess progress is never transferred to another item. */
    fun advance(factoryId: String): ProductionTick {
        if (!isActive(factoryId)) return ProductionTick(this)
        val queue = queue(factoryId)
        val head = queue.firstOrNull() ?: return ProductionTick(this)
        val progress = head.progress + head.rate
        require(progress.isFinite()) { "Production progress must remain finite" }
        if (progress < 1f) {
            return ProductionTick(copy(
                queues = queues + (factoryId to (listOf(head.copy(progress = progress)) + queue.drop(1)))))
        }
        val completed = ProductionCompletion(factoryId, factories.getValue(factoryId).ownerPlayerId, head.typeId, head.unitNonce)
        return ProductionTick(copy(queues = queues + (factoryId to queue.drop(1))), completed)
    }

    /** Pending work is refunded before the returned factory becomes inactive. */
    fun destroyFactory(factoryId: String): ProductionLedger {
        if (!isActive(factoryId)) return this
        val factory = factories.getValue(factoryId)
        var refunded = this
        for (item in queue(factoryId)) refunded = refunded.refund(factory.ownerPlayerId, item.cost)
        return refunded.copy(queues = queues + (factoryId to emptyList()), inactive = inactive + factoryId)
    }
}
