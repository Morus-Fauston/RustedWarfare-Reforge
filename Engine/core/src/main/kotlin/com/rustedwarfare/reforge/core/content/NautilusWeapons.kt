package com.rustedwarfare.reforge.core.content

import java.nio.file.Path

internal enum class ScoutPayloadStage(val projectileName: String, val life: Double, val speed: Double, val turnSpeed: Float) {
    MOTHER("scoutBotProjectile", 0.0, 5.0, -1f),
    SPLIT("scoutBotProjectileSplit", 90.0, .3, 4f),
    FAST_TURN("scoutBotProjectileSplitFastTurn", 150.0, 2.0, 9f);

    val guided: Boolean get() = this != MOTHER
}

/** Qualified weapons and the action-only scout launcher. */
internal object NautilusWeapons {
    val mounts = listOf(10.0 to 22.0, -10.0 to 22.0, -10.0 to 14.0, 10.0 to 14.0, 10.0 to 4.0, -10.0 to 4.0)

    fun fields(id: String): Map<String, Map<String, String>> {
        val sub = id == "nautilusSubmarine"
        val result = linkedMapOf("attack" to mapOf("canAttack" to "true", "canAttackFlyingUnits" to (!sub).toString(),
            "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to sub.toString(),
            "canAttackNotTouchingWaterUnits" to (!sub).toString(), "turretSize" to "20", "turretTurnSpeed" to "1.8",
            "maxAttackRange" to "240", "shootDelay" to "300"),
            "turret_1" to mapOf("x" to "0", "y" to "27", "idleDir" to "0", "invisible" to "true", "canShoot" to "false"))
        for ((index, mount) in mounts.withIndex()) {
            result["turret_${index + 2}"] = mapOf("x" to mount.first.toString(), "y" to mount.second.toString(),
                "idleDir" to "0", "invisible" to "true", "canAttackFlyingUnits" to (!sub).toString(),
                "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to sub.toString(),
                "canAttackNotTouchingWaterUnits" to (!sub).toString(), "projectile" to (if (sub) "2" else "3"),
                "size" to "1", "turnSpeed" to "40", "energyUsage" to "1", "warmup" to (index * 20).toString()) +
                if (index > 0) mapOf("linkDelayWithTurret" to "2") else emptyMap()
        }
        result["turret_botTurret"] = mapOf("x" to "10", "y" to "22", "idleDir" to "0", "invisible" to "true",
            "canShoot" to "false", "projectile" to "scoutBotProjectile", "size" to "1", "turnSpeed" to "40", "limitingRange" to "500")
        result["projectile_2"] = mapOf("directDamage" to "50", "life" to "250", "speed" to "0.15", "targetSpeed" to "1.2",
            "ballistic" to "true", "ballistic_delaymove_height" to "40", "ballistic_height" to "-2", "autoTargetingOnDeadTarget" to "true")
        result["projectile_3"] = mapOf("directDamage" to "35", "life" to "240", "speed" to "0.3", "targetSpeed" to "6",
            "ballistic" to "true", "ballistic_delaymove_height" to "40", "ballistic_height" to "50", "autoTargetingOnDeadTarget" to "true",
            "areaDamage" to "180", "areaRadius" to "55")
        val mother = ScoutPayloadStage.MOTHER
        val split = ScoutPayloadStage.SPLIT
        val fast = ScoutPayloadStage.FAST_TURN
        result["projectile_${mother.projectileName}"] = mapOf("life" to "0", "directDamage" to "0",
            "spawnProjectilesOnEndOfLife" to "${split.projectileName}(offsetDir=15), ${split.projectileName}(offsetDir=-180)")
        for (stage in listOf(split, fast)) {
            result["projectile_${stage.projectileName}"] = mapOf("life" to stage.life.toString(), "speed" to stage.speed.toString(),
                "turnSpeed" to stage.turnSpeed.toString(), "directDamage" to "123", "spawnUnit" to ScoutBotRules.LAND,
                "targetSpeed" to "6", "targetSpeedAcceleration" to "0.05", "ballistic" to "true",
                "ballistic_delaymove_height" to "20", "ballistic_height" to "50", "targetGround" to "true",
                "deflectionPower" to "-1", "wobbleAmplitude" to "1", "wobbleFrequency" to "60", "targetGroundSpread" to "5",
                "spawnProjectilesOnEndOfLife" to if (stage == split) fast.projectileName else "NONE")
        }
        return result
    }

    fun combat(id: String, dead: Path?): CombatProfile {
        val sub = id == "nautilusSubmarine"
        val projectile = ProjectileProfile(if (sub) 50.0 else 35.0, if (sub) 250.0 else 240.0, if (sub) .15 else .3,
            acceleration = LinearProjectileAcceleration(if (sub) 1.2 else 6.0, .1),
            ballistic = CustomBallisticProfile(40f, if (sub) -2f else 50f),
            splash = if (sub) null else OrdinarySplashProfile(180f, 55f), retargetOnDeath = true)
        // Default V=-1 uses constant limited turning; zero is the compiled no-acceleration branch.
        val disabled = ProjectileProfile(0.0, 0.0, 5.0)
        val weapons = listOf(NamedWeaponDefinition("1", "scoutBotProjectile", disabled, TurretMount(0.0, 27.0),
            300.0, 20.0, 1.8, 0.0, null, 1.0, recoil = WeaponRecoil(0.0), canShoot = false, invisible = true)) +
            mounts.mapIndexed { index, mount ->
                NamedWeaponDefinition((index + 2).toString(), if (sub) "2" else "3", projectile,
                    TurretMount(mount.first, mount.second), 300.0, 1.0, 40.0, 0.0, null, 1.0,
                    recoil = WeaponRecoil(0.0), fireSound = if (sub) null else FireSoundProfile.NAVAL_MISSILE,
                    canAttackFlyingUnits = !sub, canAttackUnderwaterUnits = sub, warmup = (index * 20).toDouble(),
                    linkDelayWithTurret = if (index == 0) null else "2", invisible = true, energyUsage = 1f)
            } + NamedWeaponDefinition("botTurret", "scoutBotProjectile", disabled, TurretMount(10.0, 22.0),
                300.0, 1.0, 40.0, 0.0, null, 1.0, recoil = WeaponRecoil(0.0), canShoot = false, invisible = true)
        return CombatProfile(4000.0, 240.0, 300.0, 20.0, 1.8, 0.0, projectile, null, dead, 1.0,
            recoil = WeaponRecoil(0.0), namedWeapons = WeaponCatalog(weapons))
    }

    fun supports(d: UnitDefinition): Boolean = d.combat == combat(d.id, d.durability?.deadImagePath)
}
