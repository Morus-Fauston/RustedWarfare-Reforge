package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition

/** Queries current candidate objects in gameplay order; no position or membership survives a call. */
internal object CombatTargetQuery {
    fun isFlying(target: MutableUnit): Boolean =
        if (target.amphibiousState != null) UnitLocomotion.movement(target).type == MovementType.AIR
        else target.definition.movement.type == MovementType.AIR && target.height >= 4.0

    fun isUnderwater(target: MutableUnit): Boolean =
        SubmarineMotion.submerged(target) || target.amphibiousState != null && target.height < -1.0 ||
            target.definition.fixedUnderwaterTarget != null && target.height <= -1.0

    /** Physical flying takes precedence; negative ordinary heights do not grant underwater capability. */
    fun weaponAllows(target: MutableUnit, weapon: NamedWeaponDefinition): Boolean = when {
        isFlying(target) -> weapon.canAttackFlyingUnits
        isUnderwater(target) -> weapon.canAttackUnderwaterUnits
        else -> weapon.canAttackLandUnits
    }

    /** Common target category envelope for manual intent and gameplay candidate selection. */
    fun canAttackAirCategory(actor: MutableUnit, target: MutableUnit, map: MapDefinition): Boolean {
        if (actor.definition.submarine != null || actor.amphibiousState != null) {
            val submerged = if (actor.amphibiousState != null) actor.height < -1.0 else SubmarineMotion.submerged(actor)
            if (isFlying(target)) return !submerged
            if (isUnderwater(target)) return submerged
            return !submerged || target.height <= 2.0 && map.isOverWater(
                (target.position.x / map.tileWidth).toInt(), (target.position.y / map.tileHeight).toInt())
        }
        // Preserve surface intent and non-shooting parent tracking; only the explicit special
        // categories require an actor-wide shooting capability before per-weapon qualification.
        if (target.definition.movement.type != MovementType.AIR && !isUnderwater(target)) return true
        val catalog = actor.definition.combat?.namedWeapons ?: return !isFlying(target) && !isUnderwater(target)
        return catalog.ordered.any { it.canShoot && weaponAllows(target, it) }
    }

    fun isEnemy(actor: MutableUnit, target: MutableUnit, players: List<Player>): Boolean =
        isEnemy(actor.ownerPlayerId, players.single { it.playerId == actor.ownerPlayerId }.teamId, target, players)

    private fun isEnemy(owner: String, team: String?, target: MutableUnit, players: List<Player>) =
        target.alive && target.health != null && target.ownerPlayerId != owner &&
            (team == null || players.single { it.playerId == target.ownerPlayerId }.teamId != team)

    fun nearestEnemy(actor: MutableUnit, candidates: Collection<MutableUnit>, players: List<Player>,
                     coordinates: CoordinatePolicy, map: MapDefinition): MutableUnit? {
        val weapon = actor.definition.combat ?: return null
        val team = players.single { it.playerId == actor.ownerPlayerId }.teamId
        val range = UnitCombat.range(actor)
        val rangeSquared = range * range
        var chosen: MutableUnit? = null
        var chosenDistance = 0.0
        var chosenArmed = false
        for (candidate in candidates) {
            if (!isEnemy(actor.ownerPlayerId, team, candidate, players) || !canAttackAirCategory(actor, candidate, map)) continue
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
                    visible: (String, Position) -> Boolean, map: MapDefinition): MutableUnit? {
        val weapon = actor.definition.combat ?: return null
        val team = players.single { it.playerId == actor.ownerPlayerId }.teamId
        val range = maxOf(UnitCombat.range(actor).toFloat() + 110f, 190f)
        var anyDistance = range * range + 1f
        var armedDistance = anyDistance
        var any: MutableUnit? = null
        var armed: MutableUnit? = null
        for (candidate in candidates) {
            if (!isEnemy(actor.ownerPlayerId, team, candidate, players) || !canAttackAirCategory(actor, candidate, map) ||
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
        val attackRange = UnitCombat.range(actor).toFloat()
        return if (armed == null || armedDistance >= attackRange * attackRange) any else armed
    }

    fun patrolDistanceSquared(actor: MutableUnit, target: MutableUnit): Float {
        val x = actor.position.x.toFloat() - target.position.x.toFloat()
        val y = actor.position.y.toFloat() - target.position.y.toFloat()
        return x * x + y * y
    }
}
