package com.rustedwarfare.reforge.core.sim

/** Queries current candidate objects in gameplay order; no position or membership survives a call. */
internal object CombatTargetQuery {
    fun isEnemy(actor: MutableUnit, target: MutableUnit, players: List<Player>): Boolean =
        isEnemy(actor.ownerPlayerId, players.single { it.playerId == actor.ownerPlayerId }.teamId, target, players)

    private fun isEnemy(owner: String, team: String?, target: MutableUnit, players: List<Player>) =
        target.alive && target.health != null && target.ownerPlayerId != owner &&
            (team == null || players.single { it.playerId == target.ownerPlayerId }.teamId != team)

    fun nearestEnemy(actor: MutableUnit, candidates: Collection<MutableUnit>, players: List<Player>,
                     coordinates: CoordinatePolicy): MutableUnit? {
        val weapon = actor.definition.combat ?: return null
        val team = players.single { it.playerId == actor.ownerPlayerId }.teamId
        val rangeSquared = weapon.attackRange * weapon.attackRange
        var chosen: MutableUnit? = null
        var chosenDistance = 0.0
        var chosenArmed = false
        for (candidate in candidates) {
            if (!isEnemy(actor.ownerPlayerId, team, candidate, players)) continue
            val distance = coordinates.distanceSquared(actor.position, candidate.position)
            if (!(distance < rangeSquared)) continue
            val armed = candidate.definition.combat != null
            if (chosen == null || (armed && !chosenArmed) ||
                (armed == chosenArmed && distance.compareTo(chosenDistance) < 0)) {
                chosen = candidate
                chosenDistance = distance
                chosenArmed = armed
            }
        }
        return chosen
    }

    fun patrolEnemy(actor: MutableUnit, candidates: Collection<MutableUnit>, players: List<Player>,
                    visible: (String, Position) -> Boolean): MutableUnit? {
        val weapon = actor.definition.combat ?: return null
        val team = players.single { it.playerId == actor.ownerPlayerId }.teamId
        val range = maxOf(weapon.attackRange.toFloat() + 110f, 190f)
        var anyDistance = range * range + 1f
        var armedDistance = anyDistance
        var any: MutableUnit? = null
        var armed: MutableUnit? = null
        for (candidate in candidates) {
            if (!isEnemy(actor.ownerPlayerId, team, candidate, players) ||
                !visible(actor.ownerPlayerId, candidate.position) ||
                !(kotlin.math.abs(actor.position.x.toFloat() - candidate.position.x.toFloat()) <= range) ||
                !(kotlin.math.abs(actor.position.y.toFloat() - candidate.position.y.toFloat()) <= range)) continue
            val distance = patrolDistanceSquared(actor, candidate)
            if (distance < anyDistance) { anyDistance = distance; any = candidate }
            val combat = candidate.definition.combat
            if (combat != null && combat.attackRange > 0.0 &&
                (combat.projectile.directDamage > 0.0 || (combat.projectile.ground?.areaDamage ?: 0f) > 0f) &&
                distance < armedDistance) {
                armedDistance = distance
                armed = candidate
            }
        }
        val attackRange = weapon.attackRange.toFloat()
        return if (armed == null || armedDistance >= attackRange * attackRange) any else armed
    }

    fun patrolDistanceSquared(actor: MutableUnit, target: MutableUnit): Float {
        val x = actor.position.x.toFloat() - target.position.x.toFloat()
        val y = actor.position.y.toFloat() - target.position.y.toFloat()
        return x * x + y * y
    }
}
