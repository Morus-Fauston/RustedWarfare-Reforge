package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*

internal data class PendingOrdnance(val name: String, val progress: Float = 0f, val target: Position? = null) {
    val cost: Long get() = if (name == "buildNuke") 11000L else 0L
    val rate: Float get() = if (name == "buildNuke") .0003f else 1f / 210f
}
internal data class OrdnanceState(val ammo: Int = 0, val queue: List<PendingOrdnance> = emptyList(), val turretHeading: Double? = null,
    val doors: Float = 0f, val doorTargetOpen: Boolean = false, val doorBlendBase: Float = 0f, val doorBlendWeight: Float = 0f)
internal data class OrdnanceChange(val ledger: ProductionLedger, val error: SimulationError? = null)
internal data class TemporaryVision(val owner: String, val position: Position, val remaining: Float = 360f)

/** Paid stock and real queue order form one immutable value on the candidate unit. */
internal object NuclearOrdnance {
    fun supports(d: UnitDefinition): Boolean = d.nuclearSilo != null && d.id == "nukeLauncherC" &&
        d.className == "CustomUnitMetadata" && d.maximumHealth == 1500.0 && d.radius == 35.0 && d.displayRadius == 35.0 &&
        d.totalFrames == 1 && d.imageScale == 1.0 && d.movement == MovementProfile(MovementType.NONE, 0.0, .01, .01, 0.0, .1, false, false) &&
        d.build == BuildProfile(45000L, .001f) && d.construction == ConstructionProfile(45000L, .001f) &&
        d.building?.let { it.footprint == BuildingFootprint(-1, -1, 1, 1) && it.placementFootprint == BuildingFootprint(-2, -1, 2, 2) &&
            it.anchor == null && !it.requiresResourcePool && it.foundationMovementType == null } == true &&
        d.combat == null && d.production == null && d.builder == null && d.customActions == null && d.income == null &&
        d.upgrades == null && d.maintenance == null && d.pointDefense == null && d.deathExplosion == null && d.mech == null &&
        d.rotor == null && d.transport == null && d.energy == null && d.submarine == null && d.amphibiousJet == null &&
        d.fixedUnderwaterTarget == null && d.shield == null && d.groundHeight == null && d.selfRegenRate == 0f

    fun observe(unit: MutableUnit): UnitActionsObservation? {
        if (unit.definition.nuclearSilo == null) return null
        val state = unit.ordnance
        val ready = unit.alive && unit.carriedBy == null && BuildingWork.isReady(unit)
        val actions = listOf("launchNuke", "buildNuke").map { name ->
            val index = state.queue.indexOfFirst { it.name == name }
            UnitActionObservation(name, index.takeIf { it >= 0 }, state.queue.getOrNull(index)?.progress, 0,
                ready && if (name == "launchNuke") state.ammo > 0 else state.ammo + state.queue.count { it.name == "buildNuke" } < 4)
        }
        return UnitActionsObservation(immutable(actions), 0f, state.ammo,
            immutable(state.queue.map { QueuedUnitActionObservation(it.name, it.progress, it.target) }))
    }

    fun request(unit: MutableUnit, name: String, cancel: Boolean, ledger: ProductionLedger, target: Position?): OrdnanceChange {
        fun reject(code: String) = OrdnanceChange(ledger, SimulationError(code, "Nuclear action rejected: $code"))
        if (name !in setOf("buildNuke", "launchNuke")) return reject("unsupported-unit-action")
        if (!BuildingWork.isReady(unit)) return reject("unit-incomplete")
        if (target != null && (cancel || name != "launchNuke")) return reject("unexpected-action-target")
        val state = unit.ordnance
        if (cancel) {
            val index = state.queue.indexOfLast { it.name == name }
            if (index < 0) return reject("unit-action-not-queued")
            val paid = state.queue[index]
            val refunded = ledger.refund(unit.ownerPlayerId, paid.cost)
            unit.ordnance = state.copy(ammo = if (name == "launchNuke") Math.addExact(state.ammo, 1) else state.ammo,
                queue = state.queue.filterIndexed { i, _ -> i != index })
            return OrdnanceChange(refunded)
        }
        if (name == "buildNuke") {
            if (state.ammo.toLong() + state.queue.count { it.name == "buildNuke" } >= 4) return reject("ammo-queue-limit")
            val paid = ledger.charge(unit.ownerPlayerId, 11000L) ?: return reject("insufficient-funds")
            unit.ordnance = state.copy(queue = state.queue + PendingOrdnance(name))
            return OrdnanceChange(paid)
        }
        if (target == null) return reject("missing-action-target")
        if (!target.x.isFinite() || !target.y.isFinite() || !target.x.toFloat().isFinite() || !target.y.toFloat().isFinite())
            return reject("invalid-action-target")
        val distance = GroundMath.square(unit.position.x.toFloat(), unit.position.y.toFloat(), target.x.toFloat(), target.y.toFloat())
        if (!distance.isFinite() || distance < 260f * 260f || distance > 9999f * 9999f) return reject("action-target-out-of-range")
        if (state.ammo == 0) return reject("insufficient-ammo")
        val queue = state.queue.toMutableList()
        val index = queue.indexOfFirst { it.name != "launchNuke" }.takeIf { it >= 0 } ?: queue.size
        queue.add(index, PendingOrdnance(name, target = target))
        unit.ordnance = state.copy(ammo = state.ammo - 1, queue = queue.toList())
        return OrdnanceChange(ledger)
    }

    fun advance(unit: MutableUnit, delta: Double): PendingOrdnance? {
        var state = unit.ordnance
        val head = state.queue.firstOrNull()
        val open = head?.name == "launchNuke"
        if (open != state.doorTargetOpen)
            state = state.copy(doorTargetOpen = open, doorBlendBase = state.doors, doorBlendWeight = 1f)
        val weight = if (state.doorBlendWeight > 0f) state.doorBlendWeight -
            (if (open) 1f / 120f else 1f / 150f) * delta.toFloat() else state.doorBlendWeight
        val target = if (open) 1f else 0f
        state = state.copy(doors = if (weight > 0f) target * (1f - weight) + state.doorBlendBase * weight else target,
            doorBlendWeight = weight)
        unit.ordnance = state
        if (head == null) return null
        if (head.name == "launchNuke") {
            val heading = state.turretHeading ?: unit.heading
            val difference = ScreenPlane.difference(ScreenPlane.desired(unit.position, checkNotNull(head.target)), heading)
            state = state.copy(turretHeading = ScreenPlane.normalize(heading + difference.coerceIn(-40.0 * delta, 40.0 * delta)))
        }
        val progress = head.progress + head.rate * delta.toFloat()
        require(progress.isFinite()) { "Ordnance progress must remain finite" }
        if (progress < 1f) {
            unit.ordnance = state.copy(queue = listOf(head.copy(progress = progress)) + state.queue.drop(1))
            return null
        }
        unit.ordnance = state.copy(ammo = if (head.name == "buildNuke") Math.addExact(state.ammo, 1) else state.ammo,
            queue = state.queue.drop(1))
        return head
    }

    fun discard(unit: MutableUnit, ledger: ProductionLedger): ProductionLedger {
        var refunded = ledger
        for (item in unit.ordnance.queue) refunded = refunded.refund(unit.ownerPlayerId, item.cost)
        unit.ordnance = OrdnanceState()
        return refunded
    }

    fun validate(unit: MutableUnit) {
        val state = unit.ordnance
        require(state.ammo >= 0 && state.turretHeading?.isFinite() != false && (unit.definition.nuclearSilo != null || state == OrdnanceState()) &&
            state.doors.isFinite() && state.doors in 0f..1f && state.doorBlendBase.isFinite() &&
            state.doorBlendBase in 0f..1f && state.doorBlendWeight.isFinite() && state.doorBlendWeight <= 1f &&
            state.queue.all { it.name in setOf("buildNuke", "launchNuke") && it.progress.isFinite() && it.progress in 0f..<1f &&
                (it.name == "buildNuke" && it.target == null || it.name == "launchNuke" && it.target?.let { p -> p.x.isFinite() && p.y.isFinite() } == true) }) {
            "Invalid ordnance candidate"
        }
    }

    fun projectile(unit: MutableUnit, work: PendingOrdnance, id: Long): MutableProjectile {
        check(work.name == "launchNuke")
        val pivot = ScreenPlane.displacement(unit.heading, -5.0)
        val barrel = ScreenPlane.displacement(unit.ordnance.turretHeading ?: unit.heading, 1.0)
        val muzzle = Position(unit.position.x + pivot.x + barrel.x, unit.position.y + pivot.y + barrel.y)
        return MutableProjectile(id, unit.unitId, null, muzzle, unit.ordnance.turretHeading ?: unit.heading,
            99999.0, Position(0.0, 0.0), 0f, .1, 123.0, height = unit.height, nonDeflectable = true,
            sourceWeaponName = "siloTop", nuclearTarget = checkNotNull(work.target),
            acceleration = LinearProjectileAcceleration(2.7, .02), ballistic = CustomBallisticProfile(80f, 110f),
            appearance = ProjectileAppearance.NUCLEAR, alwaysVisibleInFog = true)
    }
}
