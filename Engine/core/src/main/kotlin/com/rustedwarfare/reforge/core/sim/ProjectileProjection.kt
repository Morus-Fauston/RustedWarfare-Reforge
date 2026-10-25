package com.rustedwarfare.reforge.core.sim

/** Player-local history; host IDs only track membership, never assign order or public labels. */
internal data class ProjectedProjectileEvent(val objectId: Long, val kind: String, val position: Position,
    val heading: Double, val height: Double, val sourceUnitId: String?, val targetUnitId: String?, val damage: Double?)

internal data class ProjectileProjectionResult(val projectiles: List<VisibleProjectile>, val events: List<VisibleProjectileEvent>)

internal class ProjectileProjection {
    private data class Appearance(val position: Position, val heading: Double, val height: Double)
    private data class PublicEvent(val kind: String, val appearance: Appearance,
        val source: String?, val target: String?, val damage: Double?)
    private data class PublicKey(val appearance: Appearance, val active: Boolean, val events: List<PublicEvent>)
    private data class Input(val objectId: Long, val key: PublicKey)
    private data class Known(val token: String, val parentToken: String?)
    private var nextToken = 0L
    private val memberships = mutableMapOf<Long, Known>()

    private fun canonical(value: Double): Double = if (value == 0.0) 0.0 else value
    private fun appearance(position: Position, heading: Double, height: Double) =
        Appearance(Position(canonical(position.x), canonical(position.y)), canonical(heading), canonical(height))
    private val appearanceOrder = compareBy<Appearance> { it.position.x }.thenBy { it.position.y }
        .thenBy { it.heading }.thenBy { it.height }

    private fun compareEvent(a: PublicEvent, b: PublicEvent): Int {
        var value = compareValues(a.kind, b.kind)
        if (value == 0) value = appearanceOrder.compare(a.appearance, b.appearance)
        if (value == 0) value = compareValues(a.source, b.source)
        if (value == 0) value = compareValues(a.target, b.target)
        if (value == 0) value = compareValues(a.damage, b.damage)
        return value
    }

    private fun compareKey(a: PublicKey, b: PublicKey): Int {
        var value = appearanceOrder.compare(a.appearance, b.appearance)
        if (value == 0) value = compareValues(a.active, b.active)
        if (value != 0) return value
        for (index in 0 until minOf(a.events.size, b.events.size)) {
            value = compareEvent(a.events[index], b.events[index])
            if (value != 0) return value
        }
        return compareValues(a.events.size, b.events.size)
    }

    fun project(visible: List<ProjectileObservation>, visibleEvents: List<ProjectedProjectileEvent>,
                retainedObjectIds: Set<Long>): ProjectileProjectionResult {
        // Only retire a whole impossible-to-reappear class. Removing individual hidden members
        // would let their hidden death change later split/alias allocation for a visible member.
        for (members in memberships.entries.groupBy({ it.value }, { it.key }).values) {
            if (members.none { it in retainedObjectIds }) for (id in members) memberships.remove(id)
        }
        val active = visible.associateBy { it.objectId }
        val eventGroups = visibleEvents.groupBy { it.objectId }
        val inputs = (active.keys + eventGroups.keys).map { id ->
            val events = eventGroups[id].orEmpty().map { event -> PublicEvent(event.kind,
                appearance(event.position, event.heading, event.height), event.sourceUnitId,
                event.targetUnitId, event.damage?.let(::canonical)) }
            val value = active[id]?.let { appearance(it.position, it.heading, it.height) } ?: events.last().appearance
            Input(id, PublicKey(value, id in active, events))
        }
        val result = mutableListOf<VisibleProjectile>()
        val resultEvents = mutableListOf<VisibleProjectileEvent>()
        val priorMembers = memberships.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }
        val groups = inputs.groupBy { it.key }.entries.sortedWith { a, b -> compareKey(a.key, b.key) }
        for ((key, group) in groups) {
            // Partition by already public history. Fresh same-value arrivals cannot impersonate it.
            val partitions = group.groupBy { memberships[it.objectId] }.entries.sortedWith(
                compareBy<Map.Entry<Known?, List<Input>>> { it.key?.token })
            for ((previous, members) in partitions) {
                val ids = members.mapTo(mutableSetOf()) { it.objectId }
                val known = if (previous != null && priorMembers[previous] == ids) previous else
                    Known("shot-${++nextToken}", previous?.token)
                for (id in ids) memberships[id] = known
                if (key.active) result += VisibleProjectile(known.token, ids.size, key.appearance.position,
                    key.appearance.heading, key.appearance.height, known.parentToken)
                // Per-object causal order survives; class order is entirely public-key/history order.
                for (event in key.events) resultEvents += VisibleProjectileEvent(known.token, event.kind, ids.size,
                    event.appearance.position, event.appearance.heading, event.appearance.height,
                    event.source, event.target, event.damage, known.parentToken)
            }
        }
        return ProjectileProjectionResult(immutable(result), immutable(resultEvents))
    }
}
