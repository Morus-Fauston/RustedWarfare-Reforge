package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.TransportProfile
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.WeaponCondition
import com.rustedwarfare.reforge.core.navigation.UnitLocomotion

/** Queries current candidate objects in gameplay order; no position or membership survives a call. */
internal object CombatTargetQuery {
    fun isFlying(target: MutableUnit): Boolean = UnitLocomotion.isFlying(target)

    fun isUnderwater(target: MutableUnit, height: Double = target.height): Boolean =
        UnitLocomotion.isUnderwater(target, height)

    fun isUnderwater(definition: UnitDefinition, height: Double): Boolean =
        UnitLocomotion.isUnderwater(definition, height)

    /** Physical flying takes precedence; negative ordinary heights do not grant underwater capability. */
    fun weaponAllows(target: MutableUnit, weapon: NamedWeaponDefinition): Boolean = when {
        isFlying(target) -> weapon.canAttackFlyingUnits
        isUnderwater(target) -> weapon.canAttackUnderwaterUnits
        else -> weapon.canAttackLandUnits
    }

    fun conditionAllows(actor: MutableUnit, target: MutableUnit, weapon: NamedWeaponDefinition, map: MapDefinition): Boolean =
        allows(actor, weapon.condition, map) &&
            (isFlying(target) || isUnderwater(target) || allows(actor, weapon.landCondition, map))

    private fun allows(actor: MutableUnit, condition: WeaponCondition?, map: MapDefinition): Boolean =
        when (condition) {
            null -> true
            WeaponCondition.NOT_FLYING -> !isFlying(actor)
            WeaponCondition.OVER_WATER -> map.isOverWater(
                (actor.position.x.toFloat() / map.tileWidth).toInt(), (actor.position.y.toFloat() / map.tileHeight).toInt())
            WeaponCondition.SOURCE_FLAG_ZERO_CLEAR -> !actor.definition.initialWeaponFlagZero
        }

    /** Common target category envelope for manual intent and gameplay candidate selection. */
    fun canAttackAirCategory(actor: MutableUnit, target: MutableUnit, map: MapDefinition): Boolean {
        if (target.carriedBy != null || target.definition.attachmentModule?.empty == true) return false
        if (actor.definition.attachmentLayout != null) return !isUnderwater(target)
        if (actor.attachment != null) return actor.definition.combat?.namedWeapons?.ordered?.any {
            it.canShoot && weaponAllows(target, it)
        } == true
        if (actor.definition.maintenance == com.rustedwarfare.reforge.core.content.MaintenanceProfile.MECH_ENGINEER)
            return isFlying(target)
        if (actor.definition.afterburn != null && actor.definition.combat?.projectile?.beam != null) return isFlying(target)
        if (actor.definition.transport == com.rustedwarfare.reforge.core.content.TransportProfile.NAUTILUS_SUBMERGED) {
            val sub = actor.typeId == "nautilusSubmarine"
            if (isFlying(target)) return !sub
            if (isUnderwater(target)) return sub
            return !sub || target.height <= 2.0 && map.isOverWater(
                (target.position.x.toFloat() / map.tileWidth).toInt(), (target.position.y.toFloat() / map.tileHeight).toInt())
        }
        if (actor.definition.submarine != null || actor.amphibiousState != null) {
            val submerged = UnitLocomotion.isSubmerged(actor)
            if (isFlying(target)) return !submerged
            if (isUnderwater(target)) return submerged
            return !submerged || target.height <= 2.0 && map.isOverWater(
                (target.position.x / map.tileWidth).toInt(), (target.position.y / map.tileHeight).toInt())
        }
        // Preserve surface intent and non-shooting parent tracking; only the explicit special
        // categories require an actor-wide shooting capability before per-weapon qualification.
        val catalog = actor.definition.combat?.namedWeapons ?: return !isFlying(target) && !isUnderwater(target)
        if (target.definition.movement.type != MovementType.AIR && !isUnderwater(target) &&
            catalog.ordered.none { it.condition != null || it.landCondition != null }) return true
        return catalog.ordered.any { it.canShoot && weaponAllows(target, it) && conditionAllows(actor, target, it, map) }
    }

    fun isEnemy(actor: MutableUnit, target: MutableUnit, players: List<Player>): Boolean =
        isEnemy(actor.ownerPlayerId, target, players)

    fun isEnemy(owner: String, target: MutableUnit, players: List<Player>): Boolean =
        isEnemy(owner, players.single { it.playerId == owner }.teamId, target, players)

    private fun isEnemy(owner: String, team: String?, target: MutableUnit, players: List<Player>) =
        target.alive && target.carriedBy == null && target.health != null && target.ownerPlayerId != owner &&
            (team == null || players.single { it.playerId == target.ownerPlayerId }.teamId != team)

    fun nearestEnemy(
        actor: MutableUnit,
        candidates: Collection<MutableUnit>,
        players: List<Player>,
        map: MapDefinition,
        distanceSquared: (Position, Position) -> Double = ScreenPlane::distanceSquared,
    ): MutableUnit? {
        actor.definition.combat ?: return null
        val team = players.single { it.playerId == actor.ownerPlayerId }.teamId
        val range = UnitCombat.range(actor)
        val rangeSquared = range * range
        var chosen: MutableUnit? = null
        var chosenDistance = 0.0
        var chosenArmed = false
        for (candidate in candidates) {
            if (!isEnemy(actor.ownerPlayerId, team, candidate, players) || !canAttackAirCategory(actor, candidate, map)) continue
            val distance = distanceSquared(actor.position, candidate.position)
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

    /**
     * Zero-allocation exact equivalent of `nearestEnemy(actor, spatialIndex.queryAllLayersCenterBox(...), ...)`
     * breaking exact distance ties by ascending `updateKey` (matching `UNIT_KEY_COMPARATOR` order).
     */
    fun nearestEnemyInSpatialIndex(
        actor: MutableUnit,
        spatialIndex: WorldSpatialIndex,
        players: List<Player>,
        map: MapDefinition,
    ): MutableUnit? {
        actor.definition.combat ?: return null
        val actorOwner = actor.ownerPlayerId
        var actorTeam: String? = null
        for (i in 0 until players.size) {
            val p = players[i]
            if (p.playerId == actorOwner) {
                actorTeam = p.teamId
                break
            }
        }
        val range = UnitCombat.range(actor)
        val rangeSquared = range * range
        var chosen: MutableUnit? = null
        var chosenDistance = 0.0
        var chosenArmed = false
        spatialIndex.forEachInAllLayersCenterBoxUnordered(actor.position, range + 1e-6) { candidate ->
            if (!candidate.alive || candidate.carriedBy != null || candidate.health == null) return@forEachInAllLayersCenterBoxUnordered
            val candOwner = candidate.ownerPlayerId
            if (candOwner == actorOwner) return@forEachInAllLayersCenterBoxUnordered
            if (actorTeam != null) {
                var candTeam: String? = null
                for (i in 0 until players.size) {
                    val p = players[i]
                    if (p.playerId == candOwner) {
                        candTeam = p.teamId
                        break
                    }
                }
                if (candTeam == actorTeam) return@forEachInAllLayersCenterBoxUnordered
            }
            if (!canAttackAirCategory(actor, candidate, map)) return@forEachInAllLayersCenterBoxUnordered
            val distance = ScreenPlane.distanceSquared(actor.position, candidate.position)
            if (!(distance < rangeSquared)) return@forEachInAllLayersCenterBoxUnordered
            val armed = candidate.definition.combat != null
            val curChosen = chosen
            val better = if (curChosen == null) {
                true
            } else if (armed != chosenArmed) {
                armed
            } else {
                val cmp = distance.compareTo(chosenDistance)
                cmp < 0 || (cmp == 0 && candidate.updateKey < curChosen.updateKey)
            }
            if (better) {
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
