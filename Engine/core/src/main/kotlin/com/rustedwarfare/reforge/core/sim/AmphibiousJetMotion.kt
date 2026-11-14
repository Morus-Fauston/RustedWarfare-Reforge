package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.math.abs
import kotlin.math.sin

internal data class AmphibiousJetState(val phase: Float = 0f, val verticalSpeed: Float = 0f,
    val flyingIntent: Boolean = true, val wingFold: Float = 0f, val surfacePresentation: Boolean = true,
    val landed: Boolean = false, val landedInWater: Boolean = false,
    val terrainPausedMove: Boolean = false)

/** Closed native capability, separate from rotor flight and submarine intent. */
internal object AmphibiousJetMotion {
    fun advanceCorpse(unit: MutableUnit, map: MapDefinition, delta: Double) {
        val state = unit.amphibiousState ?: return
        if (unit.waterSettled) return
        val elapsed = delta.toFloat()
        if (unit.height > 0.0) {
            unit.sinkSpeed += .06f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else if (!state.landed) {
            val column = MapBounds.cell(unit.position.x, map.tileWidth, map.widthTiles)
            val row = MapBounds.cell(unit.position.y, map.tileHeight, map.heightTiles)
            unit.amphibiousState = state.copy(landed = true, landedInWater = map.isLiquid(column, row))
            unit.sinkSpeed = 0f
        } else if (!state.landedInWater) {
            unit.height = 0.0
            unit.waterSettled = true
        } else if (unit.height > -10.0) {
            unit.sinkSpeed += .0008f * elapsed
            unit.height = (unit.height.toFloat() - unit.sinkSpeed * elapsed).toDouble()
        } else unit.waterSettled = true
        require(unit.sinkSpeed.isFinite() && unit.height.isFinite()) { "Amphibious corpse must remain finite" }
    }

    fun observation(unit: MutableUnit, map: MapDefinition): AmphibiousJetObservation? = unit.amphibiousState?.let { state ->
        val movement = UnitLocomotion.movement(unit)
        AmphibiousJetObservation(state.flyingIntent, state.verticalSpeed, UnitLocomotion.isSubmerged(unit),
            state.surfacePresentation, movement.type, movement.moveSpeed,
            UnitCombat.range(unit), state.wingFold,
            state.phase, unit.sinkSpeed, unit.waterSettled,
            unit.alive && BuildingWork.isReady(unit) && state.flyingIntent && overWater(unit, map))
    }

    fun advance(unit: MutableUnit, map: MapDefinition, delta: Double): Boolean {
        val old = unit.amphibiousState ?: return false
        if (!unit.alive) return false
        val elapsed = delta.toFloat()
        var phase = old.phase + 2f * elapsed
        if (phase > 360f) phase -= 360f
        val target = if (old.flyingIntent) 20f + sin(Math.toRadians(phase.toDouble())).toFloat() * 1.5f else -8f
        val submerged = UnitLocomotion.isSubmerged(unit)
        val wing = towards(old.wingFold, if (old.flyingIntent && !submerged) 0f else 1f, .018f * elapsed)
        val limit = if (submerged) .6f / 6f else .6f
        val speed = if (abs(unit.height.toFloat() - target) > 3f)
            towards(minOf(old.verticalSpeed, limit), limit, .006f * elapsed)
        else towards(old.verticalSpeed, .07f, .006f * elapsed)
        val height = towards(unit.height.toFloat(), target, speed * elapsed)
        require(listOf(phase, speed, height, wing).all { it.isFinite() }) { "Amphibious height must remain finite" }
        var flying = old.flyingIntent
        var surface = old.surfacePresentation
        if (surface && height < UnitLocomotion.SUBMERGED_DEPTH) {
            if (overWater(unit, map)) surface = false else flying = true
        }
        if (!surface && height >= UnitLocomotion.SUBMERGED_DEPTH) surface = true
        unit.height = height.toDouble()
        unit.amphibiousState = old.copy(phase = phase, verticalSpeed = speed, flyingIntent = flying,
            wingFold = wing, surfacePresentation = surface)
        return surface != old.surfacePresentation
    }

    fun overWater(unit: MutableUnit, map: MapDefinition): Boolean = map.isOverWater(
        (unit.position.x / map.tileWidth).toInt(), (unit.position.y / map.tileHeight).toInt())

    private fun towards(value: Float, target: Float, amount: Float): Float = when {
        value > target + amount -> value - amount
        value < target - amount -> value + amount
        else -> target
    }

    fun supports(d: UnitDefinition): Boolean {
        val combat = d.combat ?: return false
        val dead = combat.deadImagePath ?: return false
        val projectile = ProjectileProfile(45.0, 10.0, 4.0, instant = true, lightning = true,
                damageModifiers = ProjectileDamageModifiers(shieldDeflection = .1, building = .5))
        val weapons = listOf("left", "right", "body").mapIndexed { index, name ->
            NamedWeaponDefinition(name, "lightning", projectile,
                if (index == 2) TurretMount() else TurretMount(if (index == 0) -12.0 else 12.0, 7.0),
                110.0, 5.0, 4.0, .35, null, 1.0, recoil = WeaponRecoil(0.0),
                fireSound = FireSoundProfile.AMPHIBIOUS_LIGHTNING, canAttackFlyingUnits = true,
                canAttackUnderwaterUnits = true, warmup = 25.0 + index * 10.0,
                warmupCallDownRate = .2, canShoot = index != 2, invisible = true, turnDeceleration = .38)
        }
        return d.amphibiousJet != null && d.id == "amphibiousJet" && d.className == "NativeAmphibiousJet" &&
            d.radius == 12.0 && d.displayRadius == 13.0 && d.totalFrames == 1 && d.imageScale == 1.0 &&
            d.movement == MovementProfile(MovementType.AIR, 1.4, .03, .1, 3.8, .3, false, false, .6) &&
            d.build == BuildProfile(2000L, .001f) && combat == CombatProfile(530.0, 170.0, 110.0, 5.0, 4.0, .35,
                projectile, null, dead, 1.0, recoil = WeaponRecoil(0.0), namedWeapons = WeaponCatalog(weapons), turretTurnDeceleration = .38) &&
            d.shadowImagePath != null && d.submarine == null && d.rotor == null && d.mech == null &&
            d.durability == null && d.shield == null && d.shieldImagePath == null && d.building == null &&
            d.production == null && d.builder == null && d.construction == null && d.upgrades == null &&
            d.income == null && d.deathExplosion == null && d.pointDefense == null && d.fixedUnderwaterTarget == null &&
            !d.authoredWaterCombat && d.directHitTargetKind == DirectHitTargetKind.ORDINARY
    }
}
