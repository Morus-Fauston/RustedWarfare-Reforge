package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Typed finite projection on the shared syntax, inheritance and source coordinator. */
internal object BunkerContentReader {
    private val mobile = linkedMapOf(
        "core" to mapOf("name" to "mechBunker", "class" to "CustomUnitMetadata", "price" to "4500", "maxHp" to "800",
            "radius" to "29", "fogOfWarSightRange" to "20", "buildSpeed" to "0.0006"),
        "graphics" to mapOf("total_frames" to "1", "image_wreak" to "NONE", "image_turret" to "NONE",
            "lock_body_rotation_with_main_turret" to "false", "lock_leg_rotation_with_main_turret" to "false"),
        "attack" to mapOf("canAttack" to "true", "canAttackFlyingUnits" to "false", "canAttackLandUnits" to "true",
            "canAttackUnderwaterUnits" to "false", "turretSize" to "20", "turretTurnSpeed" to "2",
            "maxAttackRange" to "200", "shootDelay" to "70", "turretRotateWithBody" to "true"),
        "turret_1" to mapOf("x" to "0", "y" to "0", "idleDir" to "0", "turnSpeed" to "2.9",
            "turnSpeedAcceleration" to "0.07", "canShoot" to "false", "projectile" to "1"),
        "turret_2" to mapOf("x" to "0", "y" to "0", "projectile" to "1", "turnSpeed" to "3.9",
            "turnSpeedAcceleration" to "0.2", "canShoot" to "true", "size" to "25", "shoot_sound" to "large_gun_fire2",
            "shoot_sound_vol" to "0.3", "recoilOffset" to "-3", "recoilOutTime" to "6", "recoilReturnTime" to "7",
            "idleSweepAngle" to "20", "idleSweepSpeed" to "0.2", "idleSweepDelay" to "210",
            "idleSweepAddRandomDelay" to "50", "idleSweepCondition" to "if not self.isMoving()"),
        "projectile_1" to mapOf("life" to "150", "speed" to "4", "targetGround" to "true", "areaDamage" to "110", "areaRadius" to "45"),
        "movement" to mapOf("movementType" to "OVER_CLIFF", "moveSpeed" to "0.4", "moveAccelerationSpeed" to "0.07",
            "moveDecelerationSpeed" to "0.17", "maxTurnSpeed" to "1", "turnAcceleration" to "0.5", "targetHeight" to "3",
            "moveSlidingMode" to "true", "moveIgnoringBody" to "true", "moveSlidingDir" to "181", "reverseSpeedPercentage" to "1"),
        "action_deploy" to mapOf("isVisible" to "true", "convertTo" to "mechBunkerDeployed", "price" to "0",
            "buildSpeed" to "0.006", "whenBuilding_cannotMove" to "true", "whenBuilding_rotateTo" to "0",
            "whenBuilding_rotateTo_orBackwards" to "true", "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"),
    ) + (1..4).associate { index ->
        val x = if (index % 2 == 1) -22 else 22
        val y = if (index <= 2) 15 else -15
        "leg_$index" to mapOf("x" to "$x", "y" to "$y", "attach_x" to "${if (x < 0) -5 else 5}",
            "attach_y" to "${if (y < 0) -10 else 10}", "moveSpeed" to "1.9", "moveWarmUp" to "0.1s",
            "rotateSpeed" to "7", "heightSpeed" to "0.3", "resetAngle" to "25", "dust_effect" to "false",
            "holdDisMin" to "5", "holdDisMax" to "19", "favourOppositeSideNeighbours" to "true", "estimatingPositionMultiplier" to "0.7")
    }
    private val fixed = mobile.mapValues { (section, fields) -> fields + when (section) {
        "core" -> mapOf("name" to "mechBunkerDeployed", "radius" to "23", "maxShield" to "500", "shieldRegen" to "0.15",
            "startShieldAtZero" to "true", "isBuilding" to "true", "footprint" to "-1,-1,1,1", "constructionFootprint" to "-1,-1,1,1")
        "attack" -> mapOf("maxAttackRange" to "240", "shootDelay" to "50")
        "turret_2" -> mapOf("shouldResetTurret" to "false")
        "movement" -> mapOf("movementType" to "NONE", "moveSpeed" to "0", "targetHeight" to "0", "moveSlidingMode" to "false", "moveIgnoringBody" to "false")
        "action_deploy" -> mapOf("isVisible" to "false")
        "leg_1", "leg_2", "leg_3", "leg_4" -> mapOf("x" to if (section in setOf("leg_1", "leg_3")) "-3" else "3",
            "y" to if (section in setOf("leg_1", "leg_2")) "3" else "-3", "moveSpeed" to "1", "heightSpeed" to "0.4", "holdDisMax" to "15")
        else -> emptyMap()
    } } + mapOf("action_undeploy" to mapOf("isVisible" to "true", "convertTo" to "mechBunker", "price" to "0",
        "buildSpeed" to "0.010", "canPlayerCancel" to "false", "allowMultipleInQueue" to "false"))
    private val imageFields = mapOf("graphics" to setOf("image", "image_shadow", "image_shield"), "turret_2" to setOf("image")) +
        (1..4).associate { "leg_$it" to setOf("image_leg", "image_foot") }
    private val textFields = setOf("name", "class", "image", "image_shadow", "image_shield", "image_turret", "image_wreak",
        "image_leg", "image_foot", "projectile", "convertTo", "footprint", "constructionFootprint", "movementType", "shoot_sound", "idleSweepCondition")
    private val entries = fixed.values.flatMap { it.entries }
    private val schema = UnitContentSchema(fixed.mapValues { (s, f) -> f.keys + imageFields[s].orEmpty() },
        numericFields = entries.filter { it.key !in textFields && it.value.toDoubleOrNull() != null }.map { it.key }.toSet(),
        booleanFields = entries.filter { it.value in setOf("true", "false") }.map { it.key }.toSet(),
        textFields = textFields, dynamicWeapons = false, timeFieldLocations = (1..4).map { "leg_$it" to "moveWarmUp" }.toSet())

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent = UnitSourceReader(schema, validatePath).load(path) { loaded ->
        fun bad(s: String, k: String, reason: String): Nothing = reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, s, k, reason = reason)
        val deployed = when (loaded.values["core"]?.get("name")) {
            "mechBunker" -> false
            "mechBunkerDeployed" -> true
            else -> bad("core", "name", "Expected one of the two qualified bunker forms")
        }
        val expected = if (deployed) fixed else mobile
        for ((s, fields) in loaded.values) for (key in fields.keys) {
            if (key !in expected[s].orEmpty() && key !in imageFields[s].orEmpty() ||
                !deployed && s == "graphics" && key == "image_shield") bad(s, key, "Field is not used by this qualified form")
        }
        for ((s, fields) in expected) for ((key, wanted) in fields) {
            val value = loaded.values[s]?.get(key) ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, s, key, reason = "Required bunker field")
            val number = wanted.toDoubleOrNull()
            if (if (number == null || key in textFields) value != wanted else value.toDoubleOrNull()?.let { it.isFinite() && it == number } != true)
                bad(s, key, "Expected the qualified value $wanted")
        }
        fun image(s: String, key: String): Path {
            val value = loaded.values[s]?.get(key) ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, s, key, reason = "Required bunker image")
            return resolveReference(path, value, s, key).also { validatePath(it); readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true) }
        }
        val projectile = ProjectileProfile(0.0, 150.0, 4.0, ground = GroundProjectileProfile(110f, 45f, 0f, 0f, 0f))
        val delay = if (deployed) 50.0 else 70.0
        val weapons = WeaponCatalog(listOf(
            NamedWeaponDefinition("1", "1", projectile, TurretMount(), delay, 20.0, 2.9, .07, null, 1.0, recoil = WeaponRecoil(0.0), canShoot = false),
            NamedWeaponDefinition("2", "1", projectile, TurretMount(), delay, 25.0, 3.9, .2, image("turret_2", "image"), 1.0,
                recoil = WeaponRecoil(-3.0, 6.0, 7.0), fireSound = FireSoundProfile.NAVAL_MAIN,
                idleSweep = IdleSweepProfile(20.0, 210f, .2f, 50, onlyWhenStationary = true), resetTurret = !deployed)))
        val combat = CombatProfile(800.0, if (deployed) 240.0 else 200.0, delay, 20.0, 2.9, .07,
            projectile, null, null, 1.0, recoil = WeaponRecoil(0.0), namedWeapons = weapons, rotateWithBody = true)
        val deploy = CustomActionDefinition("deploy", "mechBunkerDeployed_0", "mechBunkerDeployed", .006f, null,
            visible = !deployed, cannotMove = true, rotateTo = 0.0, rotateToOrBackwards = true)
        UnitDefinition(if (deployed) "mechBunkerDeployed" else "mechBunker", "CustomUnitMetadata",
            if (deployed) 23.0 else 29.0, if (deployed) 23.0 else 29.0, 1, image("graphics", "image"), 1.0,
            MovementProfile(if (deployed) MovementType.NONE else MovementType.OVER_CLIFF, if (deployed) 0.0 else .4,
                .07, .17, 1.0, .5, !deployed, !deployed, 1.0, 181), emptySet(), fogOfWarSightRange = 20, combat = combat,
            building = if (deployed) BuildingProfile(BuildingFootprint(-1,-1,1,1)) else null,
            build = BuildProfile(4500, .0006f), mech = HeavyMechContentFields.read(path, loaded, validatePath),
            shadowImagePath = image("graphics", "image_shadow"), shield = if (deployed) ShieldProfile(500f, .15f, true) else null,
            shieldImagePath = if (deployed) image("graphics", "image_shield") else null,
            customActions = CustomActionsProfile(listOf(deploy) + if (deployed) listOf(
                CustomActionDefinition("undeploy", "mechBunker_1", "mechBunker", .010f, null)) else emptyList()),
            groundHeight = GroundHeightProfile(if (deployed) 0f else 3f))
    }
}
