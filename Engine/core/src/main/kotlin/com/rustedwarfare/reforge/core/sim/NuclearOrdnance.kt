package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

internal enum class OrdnanceAction(val key: String, val cost: Long, val rate: Float, val queueLimit: Int) {
    BUILD_NUKE("buildNuke", 11000L, .0003f, 4),
    BUILD_INTERCEPTOR("buildAntiNuke", 4000L, .0007f, 12),
    LAUNCH("launchNuke", 0L, 1f / 210f, 0),
}
internal data class PendingOrdnance(val action: OrdnanceAction, val progress: Float = 0f, val target: Position? = null)
internal data class OrdnanceState(val ammo: Int = 0, val queue: List<PendingOrdnance> = emptyList(), val turretHeading: Double? = null,
    val doors: Float = 0f, val doorTargetOpen: Boolean = false, val doorBlendBase: Float = 0f, val doorBlendWeight: Float = 0f,
    val interceptorFireAge: Float? = null)
internal data class OrdnanceChange(val ledger: ProductionLedger, val error: SimulationError? = null)
internal data class TemporaryVision(val owner: String, val position: Position, val remaining: Float = 360f)

/** Paid stock and real queue order form one immutable value on the candidate unit. */
internal object NuclearOrdnance {
    private val launcherActions = listOf(OrdnanceAction.LAUNCH, OrdnanceAction.BUILD_NUKE)
    private val interceptorActions = listOf(OrdnanceAction.BUILD_INTERCEPTOR)

    private fun actions(kind: NuclearSiloKind) = when (kind) {
        NuclearSiloKind.LAUNCHER -> launcherActions
        NuclearSiloKind.INTERCEPTOR -> interceptorActions
    }

    fun supports(d: UnitDefinition): Boolean {
        val kind = d.nuclearSilo?.kind ?: return false
        return d.id == kind.unitId &&
        d.className == "CustomUnitMetadata" && d.maximumHealth == kind.maxHp && d.radius == 35.0 && d.displayRadius == 35.0 &&
        d.totalFrames == 1 && d.imageScale == 1.0 && d.movement == MovementProfile(MovementType.NONE, 0.0, .01, .01, 0.0, .1, false, false) &&
        d.build == BuildProfile(kind.constructionPrice, .001f) && d.construction == ConstructionProfile(kind.constructionPrice, .001f) &&
        d.building?.let { it.footprint == BuildingFootprint(-1, -1, 1, 1) && it.placementFootprint == kind.placementFootprint &&
            it.anchor == null && !it.requiresResourcePool && it.foundationMovementType == null } == true &&
        d.combat == null && d.production == null && d.builder == null && d.customActions == null && d.income == null &&
        d.upgrades == null && d.maintenance == null && d.pointDefense == null && d.deathExplosion == null && d.mech == null &&
        d.rotor == null && d.transport == null && d.energy == null && d.submarine == null && d.amphibiousJet == null &&
        d.fixedUnderwaterTarget == null && d.shield == null && d.groundHeight == null && d.selfRegenRate == 0f
    }

    private fun OrdnanceState.hasQueueRoom(action: OrdnanceAction): Boolean =
        ammo.toLong() + queue.count { it.action == action } < action.queueLimit

    fun observe(unit: MutableUnit): UnitActionsObservation? {
        val kind = unit.definition.nuclearSilo?.kind ?: return null
        val state = unit.ordnance
        val ready = unit.alive && unit.carriedBy == null && BuildingWork.isReady(unit)
        val actions = actions(kind).map { action ->
            val index = state.queue.indexOfFirst { it.action == action }
            UnitActionObservation(action.key, index.takeIf { it >= 0 }, state.queue.getOrNull(index)?.progress, 0,
                ready && if (action == OrdnanceAction.LAUNCH) state.ammo > 0 else state.hasQueueRoom(action))
        }
        return UnitActionsObservation(immutable(actions), 0f, state.ammo,
            immutable(state.queue.map { QueuedUnitActionObservation(it.action.key, it.progress, it.target) }))
    }

    fun request(unit: MutableUnit, name: String, cancel: Boolean, ledger: ProductionLedger, target: Position?): OrdnanceChange {
        fun reject(code: String) = OrdnanceChange(ledger, SimulationError(code, "Nuclear action rejected: $code"))
        val kind = checkNotNull(unit.definition.nuclearSilo).kind
        val action = actions(kind).firstOrNull { it.key == name } ?: return reject("unsupported-unit-action")
        val launch = action == OrdnanceAction.LAUNCH
        if (!BuildingWork.isReady(unit)) return reject("unit-incomplete")
        if (target != null && (cancel || !launch)) return reject("unexpected-action-target")
        val state = unit.ordnance
        if (cancel) {
            val index = state.queue.indexOfLast { it.action == action }
            if (index < 0) return reject("unit-action-not-queued")
            val paid = state.queue[index]
            val refunded = ledger.refund(unit.ownerPlayerId, paid.action.cost)
            unit.ordnance = state.copy(ammo = if (launch) Math.addExact(state.ammo, 1) else state.ammo,
                queue = state.queue.filterIndexed { i, _ -> i != index })
            return OrdnanceChange(refunded)
        }
        if (!launch) {
            if (!state.hasQueueRoom(action)) return reject("ammo-queue-limit")
            val paid = ledger.charge(unit.ownerPlayerId, action.cost) ?: return reject("insufficient-funds")
            unit.ordnance = state.copy(queue = state.queue + PendingOrdnance(action))
            return OrdnanceChange(paid)
        }
        if (target == null) return reject("missing-action-target")
        if (!target.x.isFinite() || !target.y.isFinite() || !target.x.toFloat().isFinite() || !target.y.toFloat().isFinite())
            return reject("invalid-action-target")
        val distance = GroundMath.square(unit.position.x.toFloat(), unit.position.y.toFloat(), target.x.toFloat(), target.y.toFloat())
        if (!distance.isFinite() || distance < 260f * 260f || distance > 9999f * 9999f) return reject("action-target-out-of-range")
        if (state.ammo == 0) return reject("insufficient-ammo")
        val queue = state.queue.toMutableList()
        val index = queue.indexOfFirst { it.action != OrdnanceAction.LAUNCH }.takeIf { it >= 0 } ?: queue.size
        queue.add(index, PendingOrdnance(action, target = target))
        unit.ordnance = state.copy(ammo = state.ammo - 1, queue = queue.toList())
        return OrdnanceChange(ledger)
    }

    fun advance(unit: MutableUnit, delta: Double): PendingOrdnance? {
        var state = unit.ordnance
        val head = state.queue.firstOrNull()
        val open = head?.action == OrdnanceAction.LAUNCH
        if (unit.definition.nuclearSilo?.kind == NuclearSiloKind.INTERCEPTOR) {
            val elapsed = state.interceptorFireAge?.plus(delta.toFloat())
            if (elapsed != null) {
                val doors = if (elapsed < 72f) {
                    val target = 1f - .4f * elapsed / 72f
                    val blend = minOf(1f, elapsed / 30f)
                    state.doorBlendBase * (1f - blend) + target * blend
                } else .6f * maxOf(0f, 1f - (elapsed - 72f) / 210f)
                state = state.copy(doors = doors, interceptorFireAge = elapsed.takeIf { it < 282f })
            }
        } else {
            if (open != state.doorTargetOpen)
                state = state.copy(doorTargetOpen = open, doorBlendBase = state.doors, doorBlendWeight = 1f)
            val weight = if (state.doorBlendWeight > 0f) state.doorBlendWeight -
                (if (open) 1f / 120f else 1f / 150f) * delta.toFloat() else state.doorBlendWeight
            val target = if (open) 1f else 0f
            state = state.copy(doors = if (weight > 0f) target * (1f - weight) + state.doorBlendBase * weight else target,
                doorBlendWeight = weight)
        }
        unit.ordnance = state
        if (head == null) return null
        if (open) {
            val heading = state.turretHeading ?: unit.heading
            val difference = ScreenPlane.difference(ScreenPlane.desired(unit.position, checkNotNull(head.target)), heading)
            state = state.copy(turretHeading = ScreenPlane.normalize(heading + difference.coerceIn(-40.0 * delta, 40.0 * delta)))
        }
        val progress = head.progress + head.action.rate * delta.toFloat()
        require(progress.isFinite()) { "Ordnance progress must remain finite" }
        if (progress < 1f) {
            unit.ordnance = state.copy(queue = listOf(head.copy(progress = progress)) + state.queue.drop(1))
            return null
        }
        unit.ordnance = state.copy(ammo = if (open) state.ammo else Math.addExact(state.ammo, 1),
            queue = state.queue.drop(1))
        return head
    }

    fun discard(unit: MutableUnit, ledger: ProductionLedger): ProductionLedger {
        var refunded = ledger
        for (item in unit.ordnance.queue) refunded = refunded.refund(unit.ownerPlayerId, item.action.cost)
        unit.ordnance = OrdnanceState()
        return refunded
    }

    fun validate(unit: MutableUnit) {
        val state = unit.ordnance
        val kind = unit.definition.nuclearSilo?.kind
        require(state.ammo >= 0 && state.turretHeading?.isFinite() != false && (kind != null || state == OrdnanceState()) &&
            state.doors.isFinite() && state.doors in 0f..1f && state.doorBlendBase.isFinite() &&
            state.doorBlendBase in 0f..1f && state.doorBlendWeight.isFinite() && state.doorBlendWeight <= 1f &&
            state.interceptorFireAge?.let { kind == NuclearSiloKind.INTERCEPTOR && it in 0f..<282f } != false &&
            state.queue.all { it.action in actions(checkNotNull(kind)) && it.progress.isFinite() && it.progress in 0f..<1f &&
                if (it.action == OrdnanceAction.LAUNCH) it.target?.let { p -> p.x.isFinite() && p.y.isFinite() } == true
                else it.target == null }) {
            "Invalid ordnance candidate"
        }
    }

    fun projectile(unit: MutableUnit, work: PendingOrdnance, id: Long): MutableProjectile {
        check(work.action == OrdnanceAction.LAUNCH)
        val pivot = ScreenPlane.displacement(unit.heading, -5.0)
        val barrel = ScreenPlane.displacement(unit.ordnance.turretHeading ?: unit.heading, 1.0)
        val muzzle = Position(unit.position.x + pivot.x + barrel.x, unit.position.y + pivot.y + barrel.y)
        return MutableProjectile(id, unit.unitId, null, muzzle, unit.ordnance.turretHeading ?: unit.heading,
            99999.0, Position(0.0, 0.0), 0f, .1, 123.0, height = unit.height, nonDeflectable = true,
            sourceWeaponName = "siloTop", nuclearTarget = checkNotNull(work.target),
            acceleration = LinearProjectileAcceleration(2.7, .02), ballistic = CustomBallisticProfile(80f, 110f),
            appearance = ProjectileAppearance.NUCLEAR, alwaysVisibleInFog = true, nukeTagged = true)
    }
}
