package com.rustedwarfare.reforge.core.content

import java.nio.file.Path

internal object ModularSpiderWeaponForms {
    private val empty = ModularSpiderRules.EMPTY
    private const val UPGRADE_TICKS = 960f

    private fun fields(vararg entries: Pair<String, Any>): Map<String, String> =
        entries.associate { (key, value) -> key to value.toString() }

    internal class Form(
        val id: String,
        private val price: Long,
        val health: Double,
        val width: Int,
        val height: Int,
        private val attack: Map<String, String>,
        private val projectileName: String,
        private val shot: Map<String, String>,
        private val mounts: Map<String, Map<String, String>>,
        upgrades: List<AttachmentUpgrade> = emptyList(),
        val additionalTurretDimensions: Map<String, Pair<Int, Int>> = emptyMap(),
    ) {
        val module = AttachmentModuleProfile(empty = false, selfBuildRate = .001f,
            cancel = AttachmentRemoval(empty, price, 120f), reclaim = AttachmentRemoval(empty, price / 2, 180f),
            upgrades = upgrades)

        val sections = mapOf(
            "core" to fields("name" to id, "class" to "CustomUnitMetadata", "price" to price,
                "maxHp" to health, "radius" to 15, "displayRadius" to 12, "isUnselectable" to true,
                "canNotBeDirectlyAttacked" to false, "selfBuildRate" to module.selfBuildRate, "selfRegenRate" to .2),
            "graphics" to fields("total_frames" to 1),
            "attack" to attack,
            "projectile_$projectileName" to shot,
            "action_cancel" to fields("convertTo" to empty, "refundCredits" to price, "addAllActionCooldownsTime" to 120),
            "action_reclaim" to fields("convertTo" to empty, "refundCredits" to price / 2, "buildSpeed" to 180),
        ) + mounts.mapKeys { (name, _) -> "turret_$name" } + upgrades.associate { upgrade ->
            "action_${upgrade.action}" to fields("convertTo" to upgrade.targetTypeId, "price" to upgrade.cost,
                "buildSpeed" to upgrade.ticks)
        }

        private val range = attack.getValue("maxAttackRange").toDouble()
        private val delay = attack.getValue("shootDelay").toDouble()
        private val size = attack.getValue("turretSize").toDouble()
        private val turnSpeed = attack.getValue("turretTurnSpeed").toDouble()
        private val projectile = ProjectileProfile(shot.getValue("directDamage").toDouble(),
            shot.getValue("life").toDouble(), shot.getValue("speed").toDouble(),
            instant = shot["instant"] == "true",
            acceleration = shot["targetSpeed"]?.let {
                LinearProjectileAcceleration(it.toDouble(), shot["targetSpeedAcceleration"]?.toDouble() ?: .1)
            },
            splash = shot["areaDamage"]?.let { OrdinarySplashProfile(it.toFloat(), shot.getValue("areaRadius").toFloat()) },
            retargetOnDeath = shot["autoTargetingOnDeadTarget"] == "true",
            lightning = shot["lightingEffect"] == "true",
            damageModifiers = ProjectileDamageModifiers(shot["shieldDamageMultiplier"]?.toDouble() ?: 1.0,
                shot["shieldDefectionMultiplier"]?.toDouble() ?: 1.0, shot["buildingDamageMultiplier"]?.toDouble() ?: 1.0))

        private val weapons = mounts.map { (name, source) ->
            fun number(key: String, default: Double) = source[key]?.toDouble() ?: default
            val sound = when (source["shoot_sound"]) {
                "firing3" -> if (source.getValue("shoot_sound_vol") == "0.15") FireSoundProfile.SPIDER_GUN_T2
                    else FireSoundProfile.SPIDER_GUN
                "plasma_fire" -> FireSoundProfile.SCOUT_PLASMA
                "missile_fire" -> FireSoundProfile.SPIDER_AA_MISSILE
                "large_gun_fire2" -> FireSoundProfile.NAVAL_MAIN
                "lighting_burst" -> FireSoundProfile.SPIDER_LIGHTNING
                null -> null
                else -> error("Unknown modular spider sound")
            }
            val sweep = source["idleSweepAngle"]?.let {
                IdleSweepProfile(it.toDouble(), source.getValue("idleSweepDelay").toFloat(),
                    source.getValue("idleSweepSpeed").toFloat(), randomDelayExclusive = 5)
            }
            NamedWeaponDefinition(name, projectileName, projectile, TurretMount(number("x", 0.0), number("y", 0.0)),
                number("delay", delay), number("size", size), number("turnSpeed", turnSpeed),
                number("turnSpeedAcceleration", if (id == ModularSpiderRules.GUN) 1.0 else 0.0),
                imagePath = null, imageScale = 1.0,
                recoil = WeaponRecoil(number("recoilOffset", 0.0), number("recoilOutTime", 4.0), number("recoilReturnTime", 7.0)),
                fireSound = sound, canAttackLandUnits = attack["canAttackLandUnits"] == "true",
                canAttackFlyingUnits = attack["canAttackFlyingUnits"] == "true",
                warmup = number("warmup", 0.0), canShoot = source["canShoot"] != "false",
                attachedTo = source["attachedTo"], slave = source["slave"] == "true",
                linkDelayWithTurret = source["linkDelayWithTurret"],
                barrelX = number("barrelX", 0.0), oddShotOffset = source["barrelOffsetX_onOddShots"]?.toDouble(),
                invisible = source["invisible"] == "true", imageOffsetY = number("image_drawOffsetY", 0.0),
                idleSweep = sweep)
        }

        fun combat(turret: Path, additionalTurrets: Map<String, Path>): CombatProfile {
            val primary = weapons.first()
            val catalog = WeaponCatalog(weapons.mapIndexed { index, weapon ->
                weapon.copy(imagePath = if (index == 0) turret else additionalTurrets[weapon.name])
            })
            return CombatProfile(health, range, delay, size, turnSpeed, primary.turnAcceleration, projectile, turret,
                null, 1.0, recoil = primary.recoil, idleSweep = primary.idleSweep, namedWeapons = catalog)
        }
    }

    private fun attack(range: Int, delay: Int, size: Int, turnSpeed: Int, land: Boolean, air: Boolean) =
        fields("maxAttackRange" to range, "shootDelay" to delay, "turretSize" to size, "turretTurnSpeed" to turnSpeed,
            "canAttackLandUnits" to land, "canAttackFlyingUnits" to air, "canAttackUnderwaterUnits" to false)

    private fun upgrade(target: String, cost: Long, action: String = "upgradeT2") =
        AttachmentUpgrade(action, target, cost, UPGRADE_TICKS)

    private fun gun(t2: Boolean): Form {
        val id = ModularSpiderRules.GUN
        val mount = fields("x" to 0, "y" to 0, "projectile" to "1", "barrelX" to (if (t2) -4 else -3),
            "barrelOffsetX_onOddShots" to (if (t2) 8 else 6), "shoot_sound" to "firing3",
            "shoot_sound_vol" to (if (t2) .15 else .3), "recoilOffset" to -.5, "recoilOutTime" to 6, "recoilReturnTime" to 12,
            "idleSweepAngle" to 20, "idleSweepDelay" to 120, "idleSweepSpeed" to (if (t2) .3 else .2))
        return Form(if (t2) "${id}T2" else id, if (t2) 18000 else 2000, if (t2) 3000.0 else 2000.0,
            if (t2) 16 else 14, 46, attack(290, if (t2) 13 else 20, if (t2) 23 else 22, if (t2) 6 else 2,
                land = true, air = false), "1",
            fields("directDamage" to (if (t2) 40 else 44), "life" to 60, "speed" to (if (t2) 9 else 6)),
            mapOf("1" to mount), if (t2) emptyList() else listOf(upgrade("${id}T2", 16000)))
    }

    private fun laser(t2: Boolean): Form {
        val id = ModularSpiderRules.LASER
        val mount = fields("x" to 0, "y" to 0, "projectile" to "laserShot", "delay" to (if (t2) 30 else 80),
            "size" to 17, "turnSpeed" to 5, "turnSpeedAcceleration" to .2, "shoot_sound" to "plasma_fire",
            "shoot_sound_vol" to .05, "recoilOffset" to -1, "recoilOutTime" to 3, "recoilReturnTime" to 4) +
            if (t2) fields("barrelX" to -3, "barrelOffsetX_onOddShots" to 6, "image_drawOffsetY" to 5) else emptyMap()
        return Form(if (t2) "${id}T2" else id, if (t2) 3700 else 700, if (t2) 2000.0 else 1000.0,
            if (t2) 14 else 8, if (t2) 24 else 34, attack(290, if (t2) 42 else 85, 30, 2, land = true, air = true),
            "laserShot", fields("directDamage" to 70, "life" to 70, "speed" to 9), mapOf("smallLaser1" to mount),
            if (t2) emptyList() else listOf(upgrade("${id}T2", 3000)))
    }

    private val missileFire = fields("shoot_sound" to "missile_fire", "shoot_sound_vol" to .3)

    private fun aaPivot(delay: Int, speed: Double) = fields("x" to 0, "y" to 0, "projectile" to "1",
        "recoilOffset" to -3, "recoilOutTime" to 6, "recoilReturnTime" to 12,
        "idleSweepAngle" to 45, "idleSweepDelay" to delay, "idleSweepSpeed" to speed)

    private fun aaShot(damage: Int, speed: Double) = fields("directDamage" to damage, "life" to 240, "speed" to speed,
        "autoTargetingOnDeadTarget" to true)

    private fun antiAir(): Form {
        val id = ModularSpiderRules.ANTI_AIR
        return Form(id, 1000, 2000.0, 16, 26, attack(290, 85, 18, 2, land = false, air = true), "1",
            aaShot(180, .3) + fields("targetSpeed" to 6), mapOf("1" to (aaPivot(110, .6) + missileFire)),
            listOf(upgrade("${id}T2", 16000), upgrade("${id}Flak", 10000, "upgradeFlak")))
    }

    private fun advancedAntiAir(flak: Boolean): Form {
        val left = fields("x" to 7, "y" to (if (flak) 14 else 6), "projectile" to "1",
            "attachedTo" to "1", "slave" to true, "invisible" to true, "warmup" to 0) +
            if (flak) fields("size" to 0, "shoot_sound" to "large_gun_fire2", "shoot_sound_vol" to .3) else missileFire
        val right = left + fields("x" to -7, "linkDelayWithTurret" to "2", "warmup" to (if (flak) 55 else 25))
        val pivot = aaPivot(if (flak) 90 else 110, if (flak) .3 else .2) + fields("canShoot" to false)
        val shot = if (flak) aaShot(0, 20.0) + fields("areaDamage" to 100, "areaRadius" to 70)
            else aaShot(200, .6) + fields("targetSpeed" to 8)
        return Form(ModularSpiderRules.ANTI_AIR + if (flak) "Flak" else "T2", if (flak) 12000 else 18000, 3000.0,
            if (flak) 29 else 25, if (flak) 32 else 24,
            attack(if (flak) 200 else 400, if (flak) 160 else 70, if (flak) 27 else 6, 6, land = false, air = true),
            "1", shot, mapOf("1" to pivot, "2" to left, "3" to right))
    }

    private fun lightning(): Form {
        val mount = fields("x" to 0, "y" to 0, "projectile" to "1", "size" to 20, "image_drawOffsetY" to 9,
            "shoot_sound" to "lighting_burst", "shoot_sound_vol" to .3, "canShoot" to true, "warmup" to 30,
            "recoilOffset" to -1.5, "recoilOutTime" to 3, "recoilReturnTime" to 17)
        return Form(ModularSpiderRules.LIGHTNING, 6000, 2000.0, 22, 31,
            attack(290, 120, 30, 2, land = true, air = false), "1",
            fields("directDamage" to 130, "life" to 25, "speed" to 0, "instant" to true, "lightingEffect" to true,
                "shieldDamageMultiplier" to 1, "shieldDefectionMultiplier" to .1, "buildingDamageMultiplier" to .5),
            mapOf("1" to mount))
    }

    private fun artillery(): Form {
        val timing = fields("projectile" to "plasma", "delay" to 110, "warmup" to 20, "turnSpeedAcceleration" to .1,
            "recoilOutTime" to 3, "recoilReturnTime" to 38)
        val cannon = timing + fields("x" to -.5, "y" to 20, "size" to 16, "turnSpeed" to 0,
            "attachedTo" to "mainCannonBase", "slave" to true, "shoot_sound" to "large_gun_fire2",
            "shoot_sound_vol" to .3, "recoilOffset" to -5)
        val base = timing + fields("x" to 0, "y" to 0, "size" to 40, "turnSpeed" to 2, "canShoot" to false,
            "recoilOffset" to -1)
        return Form(ModularSpiderRules.ARTILLERY, 5000, 3000.0, 7, 35,
            attack(350, 85, 30, 2, land = true, air = false), "plasma",
            fields("directDamage" to 190, "life" to 80, "speed" to 16, "targetSpeed" to 16,
                "targetSpeedAcceleration" to .7), mapOf("mainCannon" to cannon, "mainCannonBase" to base),
            additionalTurretDimensions = mapOf("mainCannonBase" to (20 to 19)))
    }

    val byId = listOf(gun(false), gun(true), laser(false), laser(true), antiAir(), advancedAntiAir(false), advancedAntiAir(true),
        lightning(), artillery())
        .associateBy { it.id }
}
