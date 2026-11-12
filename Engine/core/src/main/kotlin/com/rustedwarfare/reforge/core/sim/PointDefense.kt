package com.rustedwarfare.reforge.core.sim

/** Runs in each live unit's slot, before that step's projectile movement. */
internal object PointDefense {
    fun muzzle(unit: MutableUnit): Position = checkNotNull(unit.definition.pointDefense).muzzle(unit.position, unit.heading)

    fun advance(unit: MutableUnit, delta: Double, projectiles: List<MutableProjectile>,
                units: Map<String, MutableUnit>, teams: Map<String, String?>): MutableProjectile? {
        val state = unit.pointDefense ?: return null
        val profile = checkNotNull(unit.definition.pointDefense)
        val module = profile.attachedBarrel != null
        if (!module) recharge(unit, delta)
        val deflected = deflect(unit, projectiles, units, teams, profile.range, profile.cost)
        if (module) recharge(unit, delta)
        return deflected
    }

    private fun recharge(unit: MutableUnit, delta: Double) {
        val state = checkNotNull(unit.pointDefense)
        val profile = checkNotNull(unit.definition.pointDefense)
        val replenished = state.energy + profile.recharge * delta.toFloat()
        require(replenished.isFinite()) { "Defense energy must remain finite" }
        state.energy = minOf(1f, replenished)
        if (state.energy >= 1f) state.exhausted = false
        if (unit.attachment != null) unit.attachmentLaserExhausted = state.exhausted
    }

    private fun deflect(unit: MutableUnit, projectiles: List<MutableProjectile>, units: Map<String, MutableUnit>,
                        teams: Map<String, String?>, range: Double, cost: Float): MutableProjectile? {
        val state = checkNotNull(unit.pointDefense)
        if (state.exhausted || state.energy <= 0f) return null
        val muzzle = muzzle(unit)
        require(muzzle.x.isFinite() && muzzle.y.isFinite()) { "Defense muzzle must remain finite" }
        fun allied(owner: String): Boolean = owner == unit.ownerPlayerId ||
            teams[unit.ownerPlayerId]?.let { it == teams[owner] } == true
        val candidate = projectiles.firstOrNull { shot ->
            !shot.instant && !shot.nonDeflectable && !shot.intercepted &&
                (shot.age > 7f || shot.age > 2f && shot.speed > 8.0) &&
                ScreenPlane.distanceSquared(muzzle, shot.position) < range * range && shot.height >= -1.0 &&
                (units[shot.targetUnitId]?.let { allied(it.ownerPlayerId) } == true ||
                    units[shot.sourceUnitId]?.let { !allied(it.ownerPlayerId) } == true)
        } ?: return null
        candidate.deflectionHealth -= 1.01f
        if (candidate.deflectionHealth <= 0f) candidate.intercepted = true
        state.energy -= cost
        if (state.energy < 0f) { state.energy = 0f; state.exhausted = true }
        return candidate
    }
}
