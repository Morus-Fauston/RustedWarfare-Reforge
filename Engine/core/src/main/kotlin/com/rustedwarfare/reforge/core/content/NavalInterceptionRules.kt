package com.rustedwarfare.reforge.core.content

internal object NavalInterceptionRules {
    fun ammunition(price: Long, rate: Float, limit: Int) =
        CustomActionDefinition("buildAntiNuke", "cbuildAntiNuke", null, rate, null, canPlayerCancel = true,
            allowMultipleInQueue = true, ammoProduction = AmmoProductionProfile(price, limit))

    fun validMount(weapon: NamedWeaponDefinition): Boolean {
        val round = weapon.projectile
        val acceleration = round.acceleration ?: return false
        val arc = round.ballistic ?: return false
        return !weapon.canShoot && !round.instant && !round.lightning && round.nonDeflectable &&
            round.ground == null && round.missile == null && round.splash == null && round.beam == null &&
            listOf(round.directDamage, round.life, round.speed, acceleration.targetSpeed, acceleration.acceleration)
                .all { it.toFloat().isFinite() && it > 0.0 } &&
            (round.speed.toFloat() * round.speed.toFloat()).isFinite() &&
            arc.delayMoveHeight.isFinite() && arc.delayMoveHeight > 0f && arc.apex.isFinite() && arc.apex >= arc.delayMoveHeight &&
            arc.verticalSpeed == -1f
    }

    fun supports(unit: UnitDefinition): Boolean {
        if (!unit.authoredWaterCombat || unit.movement.type != MovementType.WATER) return false
        val mount = unit.combat?.namedWeapons?.ordered?.filter { it.interception != null }?.singleOrNull() ?: return false
        val action = unit.customActions?.actions?.singleOrNull() ?: return false
        val stock = action.ammoProduction ?: return false
        return mount.interception?.isValid() == true && validMount(mount) &&
            stock.price in 0L..Int.MAX_VALUE.toLong() && stock.queueLimit in 1..100 &&
            action.rate.isFinite() && action.rate in Float.MIN_VALUE..1f &&
            action == ammunition(stock.price, action.rate, stock.queueLimit)
    }
}
