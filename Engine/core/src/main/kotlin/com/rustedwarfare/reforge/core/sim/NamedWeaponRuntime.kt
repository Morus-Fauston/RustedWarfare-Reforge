package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.NamedWeaponDefinition
import com.rustedwarfare.reforge.core.content.CombatProfile
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.WeaponAssembly
import com.rustedwarfare.reforge.core.content.MapDefinition

/** Stack-scoped world inputs. Candidates are selected by the host; lookup never grants visibility. */
internal class NamedWeaponFrame(val worldSeed: Int, val counter: Int, val delta: Double,
    val players: List<Player>, val coordinates: CoordinatePolicy, val units: Map<String, MutableUnit>,
    val candidates: Collection<MutableUnit>, val primary: MutableUnit?, val manual: MutableUnit?, val map: MapDefinition)

/** Per-weapon geometry reads the real named state without switching the unit's definition. */
internal object NamedWeaponRuntime {
    // cg.ab constructor default in the fixed original turret evidence.
    private const val DEFAULT_LIMITING_RANGE = 99999.0
    fun inRange(unit: MutableUnit, target: MutableUnit, players: List<Player>, coordinates: CoordinatePolicy, map: MapDefinition): Boolean {
        val catalog = checkNotNull(unit.definition.combat?.namedWeapons)
        return catalog.ordered.any { weapon ->
            weapon.canShoot && qualifies(unit, target, weapon, players, coordinates, map) &&
                (weapon.attachedTo?.let { parent ->
                    qualifies(unit, target, catalog.byName.getValue(parent), players, coordinates, map)
                } ?: true)
        }
    }
    fun usesNamedState(combat: CombatProfile): Boolean = WeaponAssembly.requiresNamedState(combat)

    fun initialStates(combat: CombatProfile?, heading: Double, primary: MutableCombat?): MutableMap<String, MutableCombat>? =
        combat?.takeIf(::usesNamedState)?.weaponAssembly?.slots?.mapIndexed { index, slot ->
            val initialHeading = ScreenPlane.normalize(heading + slot.definition.idleDirection)
            slot.definition.name to if (index == 0) checkNotNull(primary).also {
                it.turretHeading = initialHeading
                it.idleBaseHeading = initialHeading
            } else MutableCombat(initialHeading)
        }?.toMap(linkedMapOf())

    /** All independent aim passes finish before slaves and the declaration-ordered fire pass. */
    fun advance(unit: MutableUnit, frame: NamedWeaponFrame,
                fire: (MutableUnit, MutableCombat, CombatProfile, Double, NamedWeaponDefinition) -> Unit) {
        val assembly = checkNotNull(unit.definition.combat).weaponAssembly
        val actorId = checkNotNull(unit.objectId)
        val states = checkNotNull(unit.namedWeapons)
        for (slot in assembly.slots) {
            val definition = slot.definition
            val state = states.getValue(definition.name)
            state.aimReady = false
            val weapon = slot.profile
            OrdinaryWeapon.idle(frame.worldSeed, frame.counter, actorId, unit, frame.delta, state, weapon)
            if (definition.slave) continue
            val target = NamedWeaponRuntime.target(unit, definition, state, frame.primary, frame.candidates, frame.players, frame.coordinates, frame.map)
            if (state.targetId != target?.unitId) {
                state.targetId = target?.unitId
                if (target != null && target === frame.manual) OrdinaryWeapon.refreshOffset(frame.worldSeed, frame.counter, actorId, unit, target, state)
            }
            if (target == null) NamedWeaponRuntime.idle(unit, definition, state, frame.delta)
        }
        for (slot in assembly.slots) {
            val definition = slot.definition
            if (definition.slave) continue
            val state = states.getValue(definition.name)
            val weapon = slot.profile
            val target = frame.units[state.targetId] ?: continue
            if (weapon.idleSweep != null) {
                state.idleHold = maxOf(state.idleHold, 70f)
                state.idleBaseHeading = state.turretHeading
            }
            val residual = OrdinaryWeapon.aim(unit, target, frame.delta, state, weapon) {
                NamedWeaponRuntime.pivot(unit, definition, state)
            }
            state.aimReady = kotlin.math.abs(residual) < definition.aimMaxAngle
        }
        // The aim pass completes for every independent weapon before any slave reads its parent.
        for (slot in assembly.slots) {
            val definition = slot.definition
            if (!definition.slave) continue
            val state = states.getValue(definition.name)
            val parent = states.getValue(checkNotNull(assembly.parent(definition.name)).definition.name)
            val target = frame.units[parent.targetId]?.takeIf {
                NamedWeaponRuntime.qualifies(unit, it, definition, frame.players, frame.coordinates, frame.map)
            }
            state.targetId = target?.unitId
            state.turretHeading = parent.turretHeading
            state.aimReady = target != null && parent.aimReady
        }
        var active = false
        for (slot in assembly.slots) {
            val definition = slot.definition
            val state = states.getValue(definition.name)
            val target = frame.units[state.targetId] ?: continue
            if (!state.aimReady || !NamedWeaponRuntime.qualifies(unit, target, definition, frame.players, frame.coordinates, frame.map)) continue
            val weapon = slot.profile
            if (state.cooldown == 0.0) active = true
            if (definition.warmupNoReset) {
                state.warmup = if (state.warmup < definition.warmup) state.warmup + frame.delta else definition.warmup
            }
            if (state.cooldown != 0.0 || !definition.canShoot) continue
            if (!definition.warmupNoReset && state.warmup < definition.warmup) {
                state.warmup += frame.delta
                continue
            }
            val delay = NamedWeaponRuntime.effectiveDelay(definition, state)
            if (!definition.warmupNoReset) state.warmup = 0.0
            fire(target, state, weapon, delay, definition)
        }
        if (!active) for (slot in assembly.slots) {
            val definition = slot.definition
            val state = checkNotNull(unit.namedWeapons).getValue(definition.name)
            state.warmup = maxOf(0.0, state.warmup - definition.warmupCallDownRate * frame.delta)
        }
    }

    /** Called at the old fire boundary after its own cooldown is set, before projectile allocation. */
    fun fired(unit: MutableUnit, definition: NamedWeaponDefinition) {
        val assembly = checkNotNull(unit.definition.combat).weaponAssembly
        val states = checkNotNull(unit.namedWeapons)
        for (slot in assembly.followers(definition.name)) {
            val other = slot.definition
            if (other.name == definition.name) continue
            val otherState = states.getValue(other.name)
            // Literal 9000 excludes the separate warmup transfer used by effectiveDelay.
            if (otherState.cooldown < 9000.0 - (definition.shootDelay * 0.5 - other.warmup))
                otherState.cooldown = 0.0
        }
        assembly.parent(definition.name)?.definition?.let { parent ->
            if (!parent.canShoot && parent.recoil.offset != 0.0) {
                val parentState = states.getValue(parent.name)
                parentState.cooldown = effectiveDelay(parent, parentState)
            }
        }
    }

    fun qualifies(unit: MutableUnit, target: MutableUnit, weapon: NamedWeaponDefinition,
                  players: List<Player>, coordinates: CoordinatePolicy, map: MapDefinition, checkRange: Boolean = true): Boolean {
        if (!CombatTargetQuery.isEnemy(unit, target, players)) return false
        if ((unit.amphibiousState != null || unit.definition.submarine != null) &&
            !CombatTargetQuery.canAttackAirCategory(unit, target, map)) return false
        val categoryAllowed = CombatTargetQuery.weaponAllows(target, weapon)
        if (!categoryAllowed) return false
        weapon.limitingAngle?.takeIf { it != -1.0 }?.let { limit ->
            val heading = weapon.attachedTo?.let { checkNotNull(unit.namedWeapons).getValue(it).turretHeading }
                ?: unit.heading
            val bearing = ScreenPlane.desired(unit.position, target.position)
            if (kotlin.math.abs(ScreenPlane.difference(bearing, heading + weapon.idleDirection)) > limit) return false
        }
        if (!checkRange) return true
        val globalRange = UnitCombat.range(unit)
        val maximum = minOf(globalRange, weapon.maximumRange ?: DEFAULT_LIMITING_RANGE)
        val distance = coordinates.distanceSquared(unit.position, target.position)
        return distance < globalRange * globalRange && distance <= maximum * maximum &&
            (weapon.minimumRange <= 0.0 || distance >= weapon.minimumRange * weapon.minimumRange)
    }

    fun target(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat,
               primary: MutableUnit?, candidates: Collection<MutableUnit>, players: List<Player>,
               coordinates: CoordinatePolicy, map: MapDefinition): MutableUnit? {
        if (primary != null && qualifies(unit, primary, weapon, players, coordinates, map)) return primary
        if (unit.definition.combat?.multiTargeting != true ||
            (unit.definition.combat?.namedWeapons?.ordered?.size ?: 0) <= 1) return null
        candidates.firstOrNull { it.unitId == state.targetId && qualifies(unit, it, weapon, players, coordinates, map) }
            ?.let { return it }
        val globalRange = UnitCombat.range(unit)
        val maximum = minOf(globalRange, weapon.maximumRange ?: DEFAULT_LIMITING_RANGE)
        var best = maximum * maximum + 1.0
        val minimum = if (weapon.minimumRange > 0.0) weapon.minimumRange * weapon.minimumRange else -1.0
        var chosen: MutableUnit? = null
        for (candidate in candidates) {
            if (!qualifies(unit, candidate, weapon, players, coordinates, map)) continue
            val distance = coordinates.distanceSquared(unit.position, candidate.position)
            if (distance < best && distance > minimum) {
                chosen = candidate
                best = distance
            }
        }
        return chosen
    }

    fun profile(unit: MutableUnit, weapon: NamedWeaponDefinition) = profile(checkNotNull(unit.definition.combat), weapon)

    fun profile(base: CombatProfile, weapon: NamedWeaponDefinition): CombatProfile =
        base.weaponAssembly.byName.getValue(weapon.name).profile

    fun pivot(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat): Position {
        unit.amphibiousState?.let { form ->
            if (weapon.name == "body") return unit.position
            val fold = form.wingFold.toDouble()
            val heading = checkNotNull(unit.namedWeapons).getValue("body").turretHeading
            val forward = ScreenPlane.displacement(heading, 7.0 - 5.0 * (fold * 4.0).coerceIn(0.0, 1.0))
            val lateral = (12.0 - 5.0 * (fold * 2.0 - 1.0).coerceIn(0.0, 1.0)) *
                if (weapon.name == "left") -1.0 else 1.0
            val side = ScreenPlane.displacement(heading + 90.0, lateral)
            return Position(unit.position.x + forward.x + side.x, unit.position.y + forward.y + side.y)
        }
        var origin = unit.position
        var heading = unit.heading
        checkNotNull(unit.definition.combat).weaponAssembly.parent(weapon.name)?.definition?.let { parent ->
            val name = parent.name
            require(parent.name != weapon.name && parent.attachedTo == null) { "Invalid nested weapon mount" }
            val parentState = checkNotNull(unit.namedWeapons).getValue(name)
            origin = pivot(unit, parent, parentState)
            heading = parentState.turretHeading
        }
        return OrdinaryWeapon.pivot(unit, state, profile(unit, weapon), origin, heading, effectiveDelay(weapon, state))
    }

    fun effectiveDelay(weapon: NamedWeaponDefinition, state: MutableCombat): Double = weapon.shootDelay +
        if (weapon.warmup == 0.0 || weapon.warmupShootDelayTransfer == 0.0) 0.0
        else -weapon.warmupShootDelayTransfer * (state.warmup / weapon.warmup)

    /** Finite ordinary idle direction, separate from the legacy mech sweep/body animation. */
    fun idle(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat, delta: Double) {
        val reverseIdle = weapon.idleDirectionReversing ?: return
        if (state.idleHold != 0f) {
            state.idleHold = maxOf(0f, state.idleHold - delta.toFloat())
            return
        }
        val base = weapon.attachedTo?.let { checkNotNull(unit.namedWeapons).getValue(it).turretHeading } ?: unit.heading
        val idle = if (unit.reversing && unit.definition.movement.reverseSpeedPercentage > .95) reverseIdle else weapon.idleDirection
        val heading = ScreenPlane.normalize(base + idle)
        state.idleBaseHeading = heading
        OrdinaryWeapon.turnTo(unit, heading, delta, state, profile(unit, weapon),
            MechMotion.angleDifference(heading, state.turretHeading))
    }

    fun muzzle(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat): Position {
        if (unit.amphibiousState != null && weapon.name != "body") {
            val base = pivot(unit, weapon, state)
            val forward = ScreenPlane.displacement(checkNotNull(unit.namedWeapons).getValue("body").turretHeading, 5.0)
            return Position(base.x + forward.x, base.y + forward.y)
        }
        return OrdinaryWeapon.muzzle(unit, state, profile(unit, weapon), pivot(unit, weapon, state))
    }

    fun observation(unit: MutableUnit, weapon: NamedWeaponDefinition, state: MutableCombat) =
        UnitWeaponObservation(unit.unitId, checkNotNull(unit.objectId), state.randomCounter,
            state.offset, state.targetId, state.angularVelocity, pivot(unit, weapon, state),
            muzzle(unit, weapon, state), state.cooldown, state.oddShot,
            WeaponIdleObservation(state.idleBaseHeading, state.idleTimer, state.idleOffset,
                state.idleDirection, state.idleReached, state.idleHold), weapon.name, state.warmup, state.turretHeading)
}
