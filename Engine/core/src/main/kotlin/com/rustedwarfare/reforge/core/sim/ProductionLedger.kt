package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildProfile
import java.util.Collections

/** Trusted factory registration; command authority remains the simulation's responsibility. */
internal data class FactoryProductionSpec(val ownerPlayerId: String, val typeId: String, val build: BuildProfile)
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
    balances: Map<String, Long>,
    private val factories: Map<String, FactoryProductionSpec>,
    queues: Map<String, List<PendingProduction>> = emptyMap(),
    nonces: Map<String, Set<String>> = emptyMap(),
    inactive: Set<String> = emptySet(),
) {
    val balances: Map<String, Long> = Collections.unmodifiableMap(LinkedHashMap(balances))
    private val queues = Collections.unmodifiableMap(queues.mapValuesTo(linkedMapOf()) { immutable(it.value) })
    private val nonces = Collections.unmodifiableMap(nonces.mapValuesTo(linkedMapOf()) {
        Collections.unmodifiableSet(LinkedHashSet(it.value)) })
    private val inactive = Collections.unmodifiableSet(LinkedHashSet(inactive))

    companion object {
        private val nonceFormat = Regex("[0-9a-f]{32}")
        fun initial(balances: Map<String, Long>, factories: Map<String, FactoryProductionSpec>): ProductionLedger {
            require(balances.values.all { it >= 0L }) { "Initial balances must be nonnegative" }
            require(factories.values.all { it.ownerPlayerId in balances && it.typeId.isNotBlank() &&
                it.build.price >= 0L && it.build.rate.isFinite() && it.build.rate > 0f }) { "Invalid factory production registration" }
            return ProductionLedger(balances, Collections.unmodifiableMap(LinkedHashMap(factories)))
        }
    }

    fun queue(factoryId: String): List<PendingProduction> = queues[factoryId].orEmpty()
    fun observe(): EconomyObservation = EconomyObservation(balances, factories.keys.sorted().associateWith { factoryId ->
        queue(factoryId).map { QueuedProductionObservation(it.typeId, it.unitNonce, it.progress, it.cost, it.rate) }
    })
    fun isActive(factoryId: String): Boolean = factoryId in factories && factoryId !in inactive

    fun validateCandidate() {
        require(balances.values.all { it >= 0L }) { "Production balance must remain nonnegative" }
        for ((factoryId, queue) in queues) {
            val factory = factories.getValue(factoryId)
            require(factoryId !in inactive || queue.isEmpty()) { "Inactive factory must not retain pending work" }
            for (item in queue) require(item.typeId == factory.typeId && item.cost >= 0L &&
                item.progress.isFinite() && item.progress in 0f..<1f && item.rate.isFinite() && item.rate > 0f &&
                item.unitNonce in nonces[factory.ownerPlayerId].orEmpty()) { "Invalid production candidate" }
        }
    }
    fun enqueue(factoryId: String, typeId: String, unitNonce: String): ProductionChange {
        val factory = factories[factoryId] ?: return ProductionChange.Rejected(ProductionError.UNKNOWN_FACTORY)
        if (!isActive(factoryId)) return ProductionChange.Rejected(ProductionError.INACTIVE_FACTORY)
        if (typeId != factory.typeId) return ProductionChange.Rejected(ProductionError.INVALID_TYPE)
        if (!nonceFormat.matches(unitNonce)) return ProductionChange.Rejected(ProductionError.INVALID_NONCE)
        if (unitNonce in nonces[factory.ownerPlayerId].orEmpty()) return ProductionChange.Rejected(ProductionError.DUPLICATE_NONCE)
        val balance = balances.getValue(factory.ownerPlayerId)
        if (balance < factory.build.price) return ProductionChange.Rejected(ProductionError.INSUFFICIENT_FUNDS)
        val next = ProductionLedger(balances + (factory.ownerPlayerId to Math.subtractExact(balance, factory.build.price)), factories,
            queues + (factoryId to (queue(factoryId) + PendingProduction(typeId, unitNonce, 0f, factory.build.price, factory.build.rate))),
            nonces + (factory.ownerPlayerId to (nonces[factory.ownerPlayerId].orEmpty() + unitNonce)), inactive)
        return ProductionChange.Accepted(next)
    }

    fun cancel(factoryId: String, typeId: String): ProductionChange {
        val factory = factories[factoryId] ?: return ProductionChange.Rejected(ProductionError.UNKNOWN_FACTORY)
        if (!isActive(factoryId)) return ProductionChange.Rejected(ProductionError.INACTIVE_FACTORY)
        if (typeId != factory.typeId) return ProductionChange.Rejected(ProductionError.INVALID_TYPE)
        val queue = queue(factoryId)
        val index = queue.indexOfLast { it.typeId == typeId }
        if (index < 0) return ProductionChange.Rejected(ProductionError.EMPTY_QUEUE)
        val kept = queue.filterIndexed { itemIndex, _ -> itemIndex != index }
        return ProductionChange.Accepted(ProductionLedger(
            balances + (factory.ownerPlayerId to Math.addExact(balances.getValue(factory.ownerPlayerId), queue[index].cost)),
            factories, queues + (factoryId to kept), nonces, inactive))
    }

    /** One fixed sim-tick, one head; excess progress is never transferred to another item. */
    fun advance(factoryId: String): ProductionTick {
        if (!isActive(factoryId)) return ProductionTick(this)
        val queue = queue(factoryId)
        val head = queue.firstOrNull() ?: return ProductionTick(this)
        val progress = head.progress + head.rate
        require(progress.isFinite()) { "Production progress must remain finite" }
        if (progress < 1f) {
            return ProductionTick(ProductionLedger(balances, factories,
                queues + (factoryId to (listOf(head.copy(progress = progress)) + queue.drop(1))), nonces, inactive))
        }
        val completed = ProductionCompletion(factoryId, factories.getValue(factoryId).ownerPlayerId, head.typeId, head.unitNonce)
        return ProductionTick(ProductionLedger(balances, factories, queues + (factoryId to queue.drop(1)), nonces, inactive), completed)
    }

    /** Pending work is refunded before the returned factory becomes inactive. */
    fun destroyFactory(factoryId: String): ProductionLedger {
        if (!isActive(factoryId)) return this
        val factory = factories.getValue(factoryId)
        var refunded = balances.getValue(factory.ownerPlayerId)
        for (item in queue(factoryId)) refunded = Math.addExact(refunded, item.cost)
        return ProductionLedger(balances + (factory.ownerPlayerId to refunded), factories,
            queues + (factoryId to emptyList()), nonces, inactive + factoryId)
    }
}
