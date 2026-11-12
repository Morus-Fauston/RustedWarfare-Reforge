package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Reads the effective opening chassis and its first module through the shared source coordinator. */
internal object ModularSpiderContentReader {
    private val gun = ModularSpiderRules.GUN
    private val empty = ModularSpiderRules.EMPTY
    private val legOffsets = listOf(
        listOf(-50, 20, -25, 10), listOf(50, 20, 15, 10), listOf(-35, 60, -15, 25), listOf(35, 60, 15, 25),
        listOf(-50, -30, -15, -25), listOf(50, -30, 15, -25), listOf(-35, -60, -15, -35), listOf(35, -60, 15, -35),
    )
    private val legs = legOffsets.mapIndexed { i, (x, y, attachX, attachY) ->
        "leg_${i + 1}" to mapOf("x" to "$x", "y" to "$y", "attach_x" to "$attachX", "attach_y" to "$attachY",
            "moveSpeed" to "2.6", "heightSpeed" to "0.6", "moveWarmUp" to "4", "resetAngle" to "20",
            "holdDisMin" to "12", "holdDisMax" to "25", "holdDisMin_maxMovingLegs" to "5",
            "favourOppositeSideNeighbours" to "true")
    }.toMap()
    private val slots = ModularSpiderRules.slots.associate { slot ->
        "attachment_slot${slot.number}" to mapOf("x" to slot.x.toString(), "y" to slot.y.toString(),
            "idleDir" to slot.idleHeading.toString(), "idleDirReversing" to slot.idleHeading.toString(),
            "onCreateSpawnUnitOf" to slot.initialTypeId, "lockRotation" to "true",
            "redirectDamageToParent" to "true", "redirectDamageToParent_shieldOnly" to "true")
    }
    private val core = mapOf("class" to "CustomUnitMetadata")
    private val chassis = mapOf(
        "core" to core + mapOf("name" to ModularSpiderRules.ID, "price" to "90000", "maxHp" to "10000",
            "radius" to "49", "fogOfWarSightRange" to "19", "transportSlotsNeeded" to "14", "maxShield" to "5000",
            "shieldRegen" to "0.3", "selfRegenRate" to "0.1", "generation_credits" to "30", "generation_delay" to "40",
            "isPickableStartingUnit" to "true", "startFallingWhenStartingUnit" to "true"),
        "graphics" to mapOf("total_frames" to "1"),
        "movement" to mapOf("movementType" to "OVER_CLIFF_WATER", "moveSpeed" to "0.6",
            "moveAccelerationSpeed" to "0.015", "moveDecelerationSpeed" to "0.015", "maxTurnSpeed" to "0.4",
            "turnAcceleration" to "0.1", "reverseSpeedPercentage" to "1"),
    ) + slots + legs
    private val emptySlot = mapOf(
        "core" to core + mapOf("name" to empty, "price" to "1000", "maxHp" to "3000", "radius" to "20", "selfRegenRate" to "3000",
            "isUnselectable" to "true", "canNotBeDirectlyAttacked" to "true"),
        "graphics" to mapOf("total_frames" to "1"),
        "action_buildGun" to mapOf("convertTo" to gun, "price" to "2000", "buildSpeed" to "0", "setBuilt" to "0"),
    )
    private val weapon = mapOf(
        "core" to core + mapOf("name" to gun, "price" to "2000", "maxHp" to "2000", "radius" to "15",
            "displayRadius" to "12", "isUnselectable" to "true", "canNotBeDirectlyAttacked" to "false",
            "selfBuildRate" to "0.001", "selfRegenRate" to "0.2"),
        "graphics" to mapOf("total_frames" to "1"),
        "attack" to mapOf("canAttackFlyingUnits" to "false", "canAttackLandUnits" to "true", "canAttackUnderwaterUnits" to "false",
            "maxAttackRange" to "290", "turretSize" to "22", "turretTurnSpeed" to "2", "shootDelay" to "20"),
        "turret_1" to mapOf("x" to "0", "y" to "0", "barrelX" to "-3", "barrelOffsetX_onOddShots" to "6",
            "projectile" to "1", "shoot_sound" to "firing3", "shoot_sound_vol" to "0.3", "recoilOffset" to "-0.5",
            "recoilOutTime" to "6", "recoilReturnTime" to "12", "idleSweepAngle" to "20", "idleSweepDelay" to "120",
            "idleSweepSpeed" to "0.2"),
        "projectile_1" to mapOf("directDamage" to "44", "life" to "60", "speed" to "6"),
        "action_cancel" to mapOf("convertTo" to empty, "refundCredits" to "2000", "addAllActionCooldownsTime" to "120"),
        "action_reclaim" to mapOf("convertTo" to empty, "refundCredits" to "1000", "buildSpeed" to "180"),
    )
    private val images = mapOf("graphics" to setOf("image", "image_shadow", "image_wreak", "image_turret", "image_slotUi")) +
        legs.keys.associateWith { setOf("image_leg", "image_foot") }
    private val fields = listOf(chassis, emptySlot, weapon).flatMap { it.entries }.groupBy({ it.key }, { it.value.keys })
        .mapValues { (section, declared) -> declared.flatten().toSet() + images[section].orEmpty() }
    private val text = setOf("name", "class", "movementType", "onCreateSpawnUnitOf", "convertTo", "projectile", "shoot_sound") +
        images.values.flatten()
    private val flags = listOf(chassis, emptySlot, weapon).flatMap { it.values }.flatMap { it.entries }
        .filter { it.value == "true" || it.value == "false" }.map { it.key }.toSet()
    private val schema = UnitContentSchema(fields, fields.values.flatten().toSet() - text - flags,
        flags, text, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { content ->
            fun value(section: String, field: String) = requiredContentField(content.values[section]?.get(field),
                path, section, field, "Required modular spider field")
            val name = value("core", "name")
            val expected = when (name) {
                ModularSpiderRules.ID -> chassis
                empty -> emptySlot
                gun -> weapon
                else -> reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", name, "Unknown modular spider form")
            }
            val graphicFields = when (name) {
                ModularSpiderRules.ID -> images.getValue("graphics")
                gun -> setOf("image", "image_turret")
                else -> setOf("image")
            }
            for ((section, values) in content.values) {
                val permitted = expected[section]?.keys.orEmpty() +
                    if (section == "graphics") graphicFields else images[section].orEmpty().takeIf { section in expected }.orEmpty()
                for (field in values.keys) if (field !in permitted)
                    reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, field, reason = "Field is unavailable for $name")
            }
            for ((section, fields) in expected) for ((field, required) in fields) {
                val actual = value(section, field)
                val matches = if (field in schema.numericFields) actual.toDoubleOrNull() == required.toDouble()
                    else actual == required
                if (!matches) reject(if (field in setOf("onCreateSpawnUnitOf", "convertTo", "projectile")) ContentErrorCode.BAD_REFERENCE
                    else ContentErrorCode.UNSUPPORTED_FEATURE, path, section, field, actual, "Expected qualified value $required")
            }
            fun image(section: String, field: String, width: Int, height: Int): Path {
                val source = content.origins[section to field]?.path ?: path
                val png = resolveReference(source, value(section, field), section, field)
                validatePath(png)
                try {
                    val dimensions = readPngMetadata(png, allowIndexed = true, allowGrayAlpha = true)
                    require(dimensions.width == width && dimensions.height == height) { "Expected ${width}x${height} PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, section, field, reason = "Invalid modular spider PNG: ${failure.message}")
                }
                return png
            }
            when (name) {
                ModularSpiderRules.ID -> {
                    val legs = legOffsets.mapIndexed { index, (x, y, attachX, attachY) ->
                        val section = "leg_${index + 1}"
                        MechLegDefinition(x.toDouble(), y.toDouble(), attachX.toDouble(), attachY.toDouble(),
                            2.6, 4.0, 3.0, .6, 12.0, 25.0, 1.0, image(section, "image_leg", 10, 84),
                            image(section, "image_foot", 17, 22), 20.0, favourOppositeSideNeighbours = true,
                            maximumMovingLegs = 5)
                    }
                    val mech = MechDefinition(MechBodyDefinition(.4, .1, 0.0, 180.0, 0.0, 0.0, 0.0, 0),
                        legs, MechKind.EIGHT_LEGGED)
                    if (!mech.isValid()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "leg_1",
                        reason = "Expected the qualified eight-legged profile with shared leg and foot images")
                    val nano = image("graphics", "image_turret", 11, 19)
                    val inert = ProjectileProfile(0.0, 1.0, 1.0)
                    val mount = NamedWeaponDefinition("nanoTurret", "inert", inert, TurretMount(0.0, 18.0),
                        85.0, 8.0, 4.0, 1.0, nano, 1.0, canAttackLandUnits = false, canShoot = false)
                    UnitDefinition(name, "CustomUnitMetadata", 49.0, 49.0, 1, image("graphics", "image", 81, 113),
                        1.0, ModularSpiderRules.movement, emptySet(), fogOfWarSightRange = 19, mech = mech,
                        combat = CombatProfile(10000.0, 290.0, 85.0, 8.0, 4.0, 1.0, inert, nano, null, 1.0,
                            namedWeapons = WeaponCatalog(listOf(mount))),
                        durability = DurabilityProfile(10000.0, image("graphics", "image_wreak", 70, 90)),
                        shield = ShieldProfile(5000f, .3f), selfRegenRate = .1f, income = IncomeProfile(40f, 30f),
                        transportSlots = 14, shadowImagePath = image("graphics", "image_shadow", 81, 113),
                        attachmentLayout = AttachmentLayout(ModularSpiderRules.slots, image("graphics", "image_slotUi", 82, 114)))
                }
                empty -> UnitDefinition(name, "CustomUnitMetadata", 20.0, 20.0, 1, image("graphics", "image", 1, 1),
                    1.0, ModularSpiderRules.slotMovement, emptySet(), fogOfWarSightRange = 5,
                    durability = DurabilityProfile(3000.0, null), selfRegenRate = 3000f,
                    attachmentModule = AttachmentModuleProfile(empty = true,
                        installs = listOf(AttachmentInstall("buildGun", gun, 2000L))))
                else -> {
                    val turret = image("graphics", "image_turret", 14, 46)
                    val projectile = ProjectileProfile(44.0, 60.0, 6.0)
                    val sweep = IdleSweepProfile(20.0, 120f, .2f, randomDelayExclusive = 5)
                    val recoil = WeaponRecoil(-.5, 6.0, 12.0)
                    val mount = NamedWeaponDefinition("1", "1", projectile, TurretMount(),
                        20.0, 22.0, 2.0, 1.0, turret, 1.0, recoil, FireSoundProfile.SPIDER_GUN,
                        barrelX = -3.0, oddShotOffset = 6.0, idleSweep = sweep)
                    val combat = CombatProfile(2000.0, 290.0, 20.0, 22.0, 2.0, 1.0, projectile, turret, null, 1.0,
                        recoil = recoil, idleSweep = sweep, namedWeapons = WeaponCatalog(listOf(mount)))
                    UnitDefinition(name, "CustomUnitMetadata", 15.0, 12.0, 1, image("graphics", "image", 1, 1),
                        1.0, ModularSpiderRules.slotMovement, emptySet(), fogOfWarSightRange = 5, combat = combat,
                        selfRegenRate = .2f, attachmentModule = AttachmentModuleProfile(empty = false, selfBuildRate = .001f,
                            cancel = AttachmentRemoval(empty, 2000L, 120f), reclaim = AttachmentRemoval(empty, 1000L, 180f)))
                }
            }
        }
}
