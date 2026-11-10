package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Qualified mobile constructor, with its actual nano turret distinct from its AA weapon. */
internal object MechEngineerContentReader {
    private val fixedCore = linkedMapOf("name" to "mechEngineer", "maxHp" to "2000", "radius" to "19",
        "price" to "5000", "buildSpeed" to "0.0008", "fogOfWarSightRange" to "16", "techLevel" to "2",
        "transportSlotsNeeded" to "3", "selfRegenRate" to "0.08", "canRepairBuildings" to "true",
        "canRepairUnits" to "true", "autoRepair" to "true", "canReclaimResources" to "true",
        "nanoRange" to "140", "nanoRepairSpeed" to "0.3", "nanoBuildSpeed" to "2",
        "generation_credits" to "1", "generation_delay" to "40")
    private val fixedMovement = linkedMapOf("movementType" to "OVER_CLIFF", "moveSpeed" to "0.85",
        "moveAccelerationSpeed" to "0.02", "moveDecelerationSpeed" to "0.2", "maxTurnSpeed" to "1.5",
        "turnAcceleration" to "1", "moveSlidingMode" to "true", "moveIgnoringBody" to "true",
        "moveSlidingDir" to "180", "reverseSpeedPercentage" to "0.2")
    private val images = setOf("image", "image_dead", "image_body", "image_turret", "image_leg", "image_foot")
    private val flags = setOf("canRepairBuildings", "canRepairUnits", "autoRepair", "canReclaimResources",
        "moveSlidingMode", "moveIgnoringBody")
    private val texts = images + setOf("name", "constructionTypes", "movementType")
    private val schema = UnitContentSchema(mapOf("core" to (fixedCore.keys + images + "constructionTypes"),
        "movement" to fixedMovement.keys), numericFields = (fixedCore.keys + fixedMovement.keys) - flags - texts,
        booleanFields = flags, textFields = texts, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required finite engineer field")
            for ((section, values) in listOf("core" to fixedCore, "movement" to fixedMovement)) {
                for ((key, expected) in values) {
                    val actual = value(section, key)
                    val same = expected.toDoubleOrNull()?.let { e -> actual.toDoubleOrNull()?.let { it.isFinite() && it == e } }
                        ?: (actual == expected)
                    if (!same) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key,
                        reason = "Expected qualified engineer value $expected")
                }
            }
            val targets = value("core", "constructionTypes").split(',')
            if (!MechEngineerRules.supportsConstructionTypes(targets))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "constructionTypes",
                    reason = "Expected a nonempty unique subset of the qualified A construction menu")
            fun image(field: String, width: Int, height: Int): Path =
                resolveReference(path, value("core", field), "core", field).also {
                    validatePath(it)
                    try {
                        val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                        require(png.width == width && png.height == height) { "Expected ${width}x$height PNG" }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        reject(ContentErrorCode.BAD_REFERENCE, path, "core", field, reason = "Invalid engineer PNG: ${failure.message}")
                    }
                }
            val body = image("image_body", 40, 44)
            val cannon = image("image_turret", 28, 35)
            val leg = image("image_leg", 19, 82)
            val foot = image("image_foot", 26, 33)
            val projectile = ProjectileProfile(100.0, 240.0, 7.3,
                acceleration = LinearProjectileAcceleration(5.0, .1),
                splash = OrdinarySplashProfile(40f, 35f), retargetOnDeath = true)
            val weapons = WeaponCatalog(listOf(
                NamedWeaponDefinition("body", "missile", projectile, TurretMount(), 90.0, 7.0, 3.0, .2,
                    body, 1.0, recoil = WeaponRecoil(0.0), fireSound = FireSoundProfile.ENGINEER_MISSILE,
                    canAttackLandUnits = false, canAttackFlyingUnits = true, warmup = 2.0,
                    barrelX = -14.0, idleDirectionReversing = 0.0),
                NamedWeaponDefinition("nanoTurret", "missile", projectile, TurretMount(0.0, -5.0), 90.0,
                    4.0, 3.0, .2, cannon, 1.0, recoil = WeaponRecoil(0.0), canShoot = false,
                    canAttackLandUnits = false, canAttackFlyingUnits = true, attachedTo = "body",
                    idleDirectionReversing = 0.0)))
            val legs = listOf(-15.0 to 15.0, 15.0 to 15.0, -15.0 to -15.0, 15.0 to -15.0).map { (x, y) ->
                MechLegDefinition(x, y, if (x < 0) -4.0 else 4.0, if (y < 0) -4.0 else 4.0,
                    2.9, .5, 9.0, .8, 17.0, 50.0, .8, leg, foot, 25.0,
                    hardLimit = 65.0, favourOppositeSideNeighbours = true, maximumMovingLegs = 2,
                    endDirectionOffset = if (y > 0) { if (x < 0) 135.0 else 225.0 }
                        else { if (x < 0) 45.0 else -45.0 })
            }
            UnitDefinition("mechEngineer", "CustomUnitMetadata", 19.0, 19.0, 1, image("image", 1, 1), 1.0,
                MovementProfile(MovementType.OVER_CLIFF, .85, .02, .2, 1.5, 1.0, true, true, .2, 180),
                emptySet(), 16, combat = CombatProfile(2000.0, 220.0, 90.0, 7.0, 3.0, .2, projectile,
                    body, image("image_dead", 38, 44), 1.0, recoil = WeaponRecoil(0.0), barrelX = -14.0,
                    fireSound = FireSoundProfile.ENGINEER_MISSILE, namedWeapons = weapons, rotateWithBody = true),
                build = BuildProfile(5000L, .0008f), builder = BuilderProfile(targets, 140f, 0f, 4f, .3f, 2f, "nanoTurret"),
                income = IncomeProfile(40f, 1f), mech = MechDefinition(
                    MechBodyDefinition(3.0, .2, 0.0, 0.0, 0.0, 0.0, 0.0, 0), legs, MechKind.FOUR_LEGGED),
                transportSlots = 3, maintenance = MaintenanceProfile.MECH_ENGINEER, selfRegenRate = .08f, techLevel = 2)
        }
}
