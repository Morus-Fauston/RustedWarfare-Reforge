package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ProjectileProfile

/** Finite ground snapshot entry; ordinary weapons retain their existing Double mathematics. */
internal object GroundWeapon {
    fun initialSpeed(projectile: ProjectileProfile, unit: MutableUnit, seed: Int, frame: Int, objectId: Long,
                     randomState: MutableCombat): Double {
        val spread = projectile.ground?.speedSpread ?: 0f
        if (spread == 0f) return projectile.speed
        val bound = (spread * 100f).toInt()
        val offset = OrdinaryWeapon.sample(seed, frame, objectId, unit, 1, -bound, bound, randomState).toFloat() / 100f
        return (projectile.speed.toFloat() + offset).toDouble()
    }

    /** Ordered binary32 flight of the finite, non-ballistic ground profile. */
    fun move(projectile: MutableProjectile, delta: Double): Boolean {
        val shot = checkNotNull(projectile.ground)
        val profile = shot.profile
        val dt = delta.toFloat()
        val life = projectile.remainingLife.toFloat()
        projectile.remainingLife = (if (life > dt) life - dt else if (life < -dt) life + dt else 0f).toDouble()
        var x = projectile.position.x.toFloat()
        var y = projectile.position.y.toFloat()
        var z = projectile.height.toFloat()
        val angle = GroundMath.angle(x, y, shot.x, shot.y)
        var distance = GroundMath.square(x, y, shot.x, shot.y)
        var dz = shot.height - z
        var w = projectile.verticalSpeed
        if (w != 0f) { z = w * dt + z; dz = shot.height - z }
        if (z > 0f) {
            if (profile.gravity != 0f) { z -= profile.gravity * dt; dz = shot.height - z }
            if (profile.trueGravity != 0f) w -= profile.trueGravity * dt
        }
        val speed = projectile.speed.toFloat()
        var step = speed * dt
        if (distance < step * step) { step = GroundMath.sqrt(distance); distance = 0f }
        x += GroundMath.cos(angle) * step
        y = step * GroundMath.sin(angle) + y
        fun limit(value: Float, bound: Float): Float = if (value > bound) bound else if (value < -bound) -bound else value
        var amount = speed * dt
        if (dz != 0f) {
            if (distance.toDouble() > .1) amount = limit(
                (kotlin.math.abs(dz) / GroundMath.sqrt(distance)) * speed * dt, speed * dt)
            z = limit(dz, amount) + z
            dz = shot.height - z
        }
        projectile.position = Position(x.toDouble(), y.toDouble())
        projectile.height = z.toDouble()
        projectile.heading = ScreenPlane.normalize(angle.toDouble() + 90.0)
        projectile.verticalSpeed = w
        projectile.groundDistanceSquared = distance
        projectile.acceleration?.let { acceleration ->
            projectile.speed = approach(speed.toDouble(), acceleration.targetSpeed.toFloat().toDouble(),
                (acceleration.acceleration.toFloat() * dt).toDouble()).toFloat().toDouble()
        }
        var gate = 3f
        if (w != 0f || profile.gravity != 0f)
            gate = 3f + (kotlin.math.abs(w * dt) + kotlin.math.abs(profile.gravity * dt))
        return distance < 36f && kotlin.math.abs(dz) < gate
    }

    fun snapshot(unit: MutableUnit, target: MutableUnit, muzzle: Position, seed: Int, frame: Int, objectId: Long,
                 state: MutableCombat = checkNotNull(unit.combat),
                 weapon: com.rustedwarfare.reforge.core.content.CombatProfile = checkNotNull(unit.definition.combat)): GroundShot {
        val profile = checkNotNull(weapon.projectile.ground)
        val predicted = GroundMath.predict(muzzle.x.toFloat(), muzzle.y.toFloat(), target,
            (weapon.projectile.acceleration?.targetSpeed ?: weapon.projectile.speed).toFloat(),
            weapon.projectile.life.toFloat(), weapon.attackRange.toFloat(),
            useSlidingVelocity = weapon.projectile.acceleration != null)
        val bound = (profile.spread * 100f).toInt()
        require(bound in 0..Int.MAX_VALUE / 2) { "Ground spread must fit the finite integer sampler" }
        fun scatter(salt: Int) = if (bound == 0) 0f
            else OrdinaryWeapon.sample(seed, frame, objectId, unit, salt, -bound, bound, state).toFloat() / 100f
        val x = predicted.x + scatter(2)
        val y = predicted.y + scatter(7)
        require(x.isFinite() && y.isFinite()) { "Ground snapshot must remain finite" }
        return GroundShot(x, y, 0f, profile)
    }
}
