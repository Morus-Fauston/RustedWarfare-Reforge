package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UpgradeActionProfile
import java.util.Collections

internal data class PendingUpgrade(val actionKey: String, val targetTypeId: String, val progress: Float,
    val cost: Long, val rate: Float)
internal data class UpgradeTick(val queues: UpgradeQueues, val completed: PendingUpgrade? = null)
internal data class UpgradeRemoval(val queues: UpgradeQueues, val removed: List<PendingUpgrade>)

/** Pure work queues. All credits remain exclusively in ProductionLedger. */
internal class UpgradeQueues private constructor(queues: Map<String, List<PendingUpgrade>>) {
    private val queues = Collections.unmodifiableMap(queues.mapValuesTo(linkedMapOf()) { immutable(it.value) })
    companion object {
        fun initial(unitIds: Set<String>) = UpgradeQueues(unitIds.sorted().associateWith { emptyList() })
    }
    fun queue(unitId: String): List<PendingUpgrade> = queues[unitId].orEmpty()
    fun enqueue(unitId: String, action: UpgradeActionProfile): UpgradeQueues {
        require(unitId in queues && queue(unitId).none { it.actionKey == action.actionKey })
        return UpgradeQueues(queues + (unitId to (queue(unitId) + PendingUpgrade(action.actionKey,
            action.targetTypeId, 0f, action.price, action.rate))))
    }
    fun cancel(unitId: String, actionKey: String): UpgradeRemoval {
        val items = queue(unitId)
        val index = items.indexOfLast { it.actionKey == actionKey }
        if (index < 0) return UpgradeRemoval(this, emptyList())
        return UpgradeRemoval(UpgradeQueues(queues + (unitId to items.filterIndexed { i, _ -> i != index })), listOf(items[index]))
    }
    fun retain(unitId: String, allowed: Set<String>): UpgradeRemoval {
        val items = queue(unitId)
        val removed = items.filter { it.actionKey !in allowed }
        if (removed.isEmpty()) return UpgradeRemoval(this, emptyList())
        return UpgradeRemoval(UpgradeQueues(queues + (unitId to items.filter { it.actionKey in allowed })), removed)
    }
    fun advance(unitId: String): UpgradeTick {
        val items = queue(unitId)
        val head = items.firstOrNull() ?: return UpgradeTick(this)
        val progress = head.progress + head.rate
        require(progress.isFinite()) { "Upgrade progress must remain finite" }
        return if (progress >= 1f) UpgradeTick(UpgradeQueues(queues + (unitId to items.drop(1))), head)
        else UpgradeTick(UpgradeQueues(queues + (unitId to (listOf(head.copy(progress = progress)) + items.drop(1)))))
    }
    fun observe() = UpgradeObservation(queues.mapValues { (_, items) -> items.map {
        PendingUpgradeObservation(it.actionKey, it.targetTypeId, it.progress, it.cost, it.rate) } })

    fun validateCandidate(units: Map<String, MutableUnit>) {
        for ((id, items) in queues) {
            val unit = units.getValue(id)
            require(items.map { it.actionKey }.toSet().size == items.size) { "Duplicate upgrade work" }
            for (item in items) {
                val action = unit.definition.upgrades?.actions?.singleOrNull { it.actionKey == item.actionKey }
                require(unit.alive && action != null && item.targetTypeId == action.targetTypeId && item.cost == action.price &&
                    item.rate == action.rate && item.progress.isFinite() && item.progress in 0f..<1f) { "Invalid upgrade candidate" }
            }
        }
    }
}
