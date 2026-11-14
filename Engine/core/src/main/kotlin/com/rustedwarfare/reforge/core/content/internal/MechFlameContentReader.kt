package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Finite effective flame-mech declarations, including both independently clocked slave turrets. */
internal object MechFlameContentReader {
    private val leg = mapOf("y" to "0", "attach_y" to "0", "moveSpeed" to "1.9", "moveWarmUp" to "0.1s",
        "rotateSpeed" to "7", "heightSpeed" to "0.7", "resetAngle" to "25", "holdDisMin" to "22", "holdDisMax" to "42")
    private val nozzle = mapOf("y" to "30", "attachedTo" to "1", "slave" to "true", "invisible" to "true",
        "projectile" to "fireProjectile1", "turnSpeed" to "8", "turnSpeedAcceleration" to "1", "size" to "0",
        "canShoot" to "true", "limitingAngle" to "45", "shoot_flame" to "CUSTOM:smoke")
    private val expected = mapOf(
        "core" to mapOf("name" to MechFlameRules.ID, "class" to "CustomUnitMetadata", "price" to "12000",
            "maxHp" to "4500", "mass" to "6000", "radius" to "25", "buildSpeed" to "0.0005", "techLevel" to "2",
            "transportSlotsNeeded" to "2", "selfRegenRate" to "0.06"),
        "graphics" to mapOf("total_frames" to "1", "lock_body_rotation_with_main_turret" to "true",
            "image_wreak" to "NONE", "image_turret" to "NONE", "shadowOffsetX" to "2", "shadowOffsetY" to "2"),
        "attack" to mapOf("canAttack" to "true", "canAttackLandUnits" to "true", "canAttackFlyingUnits" to "false",
            "canAttackUnderwaterUnits" to "false", "maxAttackRange" to "170", "shootDelay" to "5", "turretRotateWithBody" to "false"),
        "turret_1" to mapOf("x" to "0", "y" to "0", "idleDir" to "0", "turnSpeed" to "2.9",
            "turnSpeedAcceleration" to "0.17", "turnSpeedDeceleration" to "0.35", "canShoot" to "false"),
        "turret_2" to nozzle + ("x" to "15"),
        "turret_3" to nozzle + mapOf("x" to "-15", "warmup" to "1"),
        "projectile_fireProjectile1" to mapOf("life" to "70", "speed" to "7", "targetSpeed" to "2.5",
            "targetSpeedAcceleration" to "0.3", "speedSpread" to "1", "drawSize" to "0.3", "flameWeapon" to "true",
            "hitSound" to "false", "targetGround" to "true", "targetGroundSpread" to "25", "deflectionPower" to "-1",
            "areaDamage" to "12", "areaRadius" to "60", "explodeEffect" to "CUSTOM:hitFlame",
            "effectOnCreate" to "CUSTOM:attachedFlame, CUSTOM:attachedLight"),
        "leg_1" to leg + mapOf("x" to "-20", "attach_x" to "-13"),
        "leg_2" to leg + mapOf("x" to "20", "attach_x" to "13"),
        "movement" to mapOf("movementType" to "OVER_CLIFF", "moveSlidingMode" to "false", "moveIgnoringBody" to "false",
            "reverseSpeedPercentage" to "1", "moveSpeed" to "0.6", "moveAccelerationSpeed" to "0.07",
            "moveDecelerationSpeed" to "0.17", "maxTurnSpeed" to "5", "turnAcceleration" to "0.5"),
    )
    private val images = mapOf("graphics" to setOf("image", "image_shadow"),
        "leg_1" to setOf("image_leg", "image_foot"), "leg_2" to setOf("image_leg", "image_foot"))
    private val fields = expected.mapValues { (section, values) -> values.keys + images[section].orEmpty() }
    private val text = setOf("name", "class", "movementType", "projectile", "attachedTo", "image_wreak", "image_turret",
        "shoot_flame", "explodeEffect", "effectOnCreate") + images.values.flatten()
    private val booleans = expected.values.flatMap { it.entries }.filter { it.value in setOf("true", "false") }.map { it.key }.toSet()
    private val schema = UnitContentSchema(fields, fields.values.flatten().toSet() - text - booleans - "moveWarmUp",
        booleans, text, dynamicWeapons = false, timeFieldLocations = setOf("leg_1" to "moveWarmUp", "leg_2" to "moveWarmUp"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required flame-mech field")
            val identity = value("core", "name")
            val authored = loaded.structured && identity.matches(Regex("[a-z][a-z0-9_.-]*"))
            for ((section, entries) in expected) for ((field, wanted) in entries) {
                val actual = value(section, field)
                val matches = when (field) {
                    "name" -> actual == wanted || authored
                    "moveWarmUp" -> actual.toDoubleOrNull() == 6.0 || actual == "0.1s"
                    in schema.numericFields -> actual.toDoubleOrNull() == wanted.toDouble()
                    else -> actual == wanted
                }
                if (!matches) {
                    val error = if (field in setOf("projectile", "attachedTo")) ContentErrorCode.BAD_REFERENCE
                        else ContentErrorCode.UNSUPPORTED_FEATURE
                    reject(error, path, section, field, actual, "Expected qualified flame-mech value $wanted")
                }
            }
            fun image(section: String, field: String, width: Int, height: Int): Path {
                val source = loaded.origins[section to field]?.path ?: path
                val file = resolveReference(source, value(section, field), section, field)
                validatePath(file)
                try {
                    val png = readPngMetadata(file, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == width && png.height == height) { "Expected ${width}x${height} PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, section, field, reason = "Invalid flame-mech PNG: ${failure.message}")
                }
                return file
            }
            val legs = listOf(-1, 1).mapIndexed { index, side ->
                val section = "leg_${index + 1}"
                MechLegDefinition(side * 20.0, 0.0, side * 13.0, 0.0, 1.9, 6.0, 7.0, .7, 22.0, 42.0, 1.0,
                    image(section, "image_leg", 16, 68), image(section, "image_foot", 17, 36), 25.0)
            }
            val mech = MechDefinition(MechFlameRules.body, legs)
            if (!mech.isValid()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "leg_2", reason = "Expected shared leg and foot images")
            UnitDefinition(identity, "CustomUnitMetadata", 25.0, 25.0, 1, image("graphics", "image", 45, 87), 1.0,
                MechFlameRules.movement, emptySet(), combat = MechFlameRules.combat, build = MechFlameRules.build, mech = mech,
                shadowImagePath = image("graphics", "image_shadow", 45, 87), transportSlots = 2, selfRegenRate = .06f, techLevel = 2)
        }
}
