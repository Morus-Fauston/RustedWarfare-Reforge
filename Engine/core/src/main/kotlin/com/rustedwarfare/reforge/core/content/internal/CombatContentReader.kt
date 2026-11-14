package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.io.IOException

/** Explicit ordinary c_tank subset. This does not register global replacements or load game code. */
internal object CombatContentReader {
    private val fields = mapOf(
        "core" to setOf("name", "overrideAndReplace", "class", "price", "reclaimPrice", "maxHp", "mass", "techLevel",
            "buildSpeed", "radius", "displayRadius", "isBio", "softCollisionOnAll", "displayLocaleKey", "fogOfWarSightRange", "transportSlotsNeeded",
            "initialWeaponFlagZero"),
        "graphics" to setOf("total_frames", "image", "image_wreak", "image_turret", "imageScale", "turretImageScale",
            "image_shadow", "shadowOffsetX", "shadowOffsetY", "animation_moving_start", "animation_moving_end",
            "animation_moving_speed", "dustEffect"),
        "attack" to setOf("canAttack", "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits",
            "turretSize", "turretTurnSpeed", "maxAttackRange", "shootDelay", "turretMultiTargeting"),
        "turret_1" to setOf("x", "y", "projectile", "delay", "limitingRange", "limitingMinRange",
            "canAttackLandUnits", "canAttackFlyingUnits", "canAttackUnderwaterUnits",
            "warmup", "warmupCallDownRate", "warmupNoReset", "warmupShootDelayTransfer",
            "canAttack", "canAttackMaxAngle", "turnSpeed",
            "attachedTo", "slave", "linkDelayWithTurret",
            "limitingAngle", "idleDir", "canAttackCondition",
            "barrelY", "size", "barrelX", "barrelOffsetX_onOddShots", "recoilOutTime", "recoilReturnTime",
            "turnSpeedAcceleration", "shoot_sound", "shoot_sound_vol",
            "shoot_flame", "shoot_light", "canShoot", "recoilOffset"),
        "projectile_1" to setOf("directDamage", "life", "speed", "frame", "drawSize", "ballistic",
            "ballistic_delaymove_height", "ballistic_height", "autoTargetingOnDeadTarget") + InstantWeaponContent.fields,
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
            "maxTurnSpeed", "turnAcceleration", "moveSlidingMode", "moveIgnoringBody"),
    )
    private val retained = mapOf(
        "core" to setOf("mass", "isBio", "softCollisionOnAll", "displayLocaleKey"),
        "graphics" to setOf("image_shadow", "shadowOffsetX", "shadowOffsetY", "animation_moving_start",
            "animation_moving_end", "animation_moving_speed", "dustEffect"),
        "turret_1" to setOf("shoot_sound", "shoot_sound_vol", "shoot_flame", "shoot_light"),
        "projectile_1" to setOf("frame", "drawSize"),
    )
    private val numericFields = setOf("transportSlotsNeeded", "price", "reclaimPrice", "buildSpeed", "maxHp", "radius", "displayRadius", "techLevel",
        "softCollisionOnAll", "fogOfWarSightRange", "total_frames", "imageScale", "turretImageScale",
        "turretSize", "turretTurnSpeed", "maxAttackRange", "shootDelay", "x", "y", "turnSpeedAcceleration",
        "directDamage", "life", "speed", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
        "maxTurnSpeed", "turnAcceleration", "delay", "limitingRange", "limitingMinRange",
        "warmup", "warmupCallDownRate", "warmupShootDelayTransfer", "canAttackMaxAngle", "turnSpeed",
        "limitingAngle", "idleDir", "barrelY", "size", "barrelX", "barrelOffsetX_onOddShots",
        "recoilOffset", "recoilOutTime", "recoilReturnTime", "ballistic_delaymove_height", "ballistic_height",
        "shieldDamage", "shieldDefection", "buildingDamage")
    private val booleanFields = setOf("isBio", "canAttack", "canAttackFlyingUnits", "canAttackLandUnits",
        "canAttackUnderwaterUnits", "canShoot", "moveSlidingMode", "moveIgnoringBody", "dont_load", "turretMultiTargeting",
        "warmupNoReset", "slave", "ballistic", "autoTargetingOnDeadTarget", "initialWeaponFlagZero") + InstantWeaponContent.booleans
    private val textFields = setOf("name", "overrideAndReplace", "class", "displayLocaleKey",
        "image", "image_wreak", "image_turret", "movementType", "projectile", "attachedTo", "linkDelayWithTurret", "canAttackCondition",
        "beamImage", "beamStartImage", "beamEndImage")

    private fun sectionKind(section: String): String = when {
        section.startsWith("turret_") -> "turret_1"
        section.startsWith("projectile_") -> "projectile_1"
        else -> section
    }

    private val schema = UnitContentSchema(fields, numericFields, booleanFields, textFields,
        requiredInlineProjectileFields = setOf("directDamage", "life", "speed"), landAttackCondition = true)

    private val tankTemplate = mapOf("movementType" to "LAND", "moveSpeed" to "1.1",
        "moveAccelerationSpeed" to "0.07", "moveDecelerationSpeed" to "0.17", "maxTurnSpeed" to "4.1",
        "turnAcceleration" to "0.25", "moveSlidingMode" to "false", "moveIgnoringBody" to "false")
    private val hoverTemplate = mapOf("movementType" to "HOVER", "moveSpeed" to "1.0",
        "moveAccelerationSpeed" to "0.04", "moveDecelerationSpeed" to "0.09", "maxTurnSpeed" to "180",
        "turnAcceleration" to "0.5", "moveSlidingMode" to "true", "moveIgnoringBody" to "true")

    fun read(path: Path): UnitDefinition = readWithSources(path).definition

    private enum class Kind { NATIVE_TANK, DECLARED_TANK, MECH, HELICOPTER, HEAVY_MECH, BATTLESHIP, AUTHOR_WATER, HOVER_TANK, AUTHOR_AIR, AUTHOR_STATIC }

    fun readWithSources(path: Path): LoadedUnitContent = readWithSources(path, Kind.NATIVE_TANK)
    fun readDeclaredWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.DECLARED_TANK, validatePath)
    fun readMechWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.MECH, validatePath)
    fun readHelicopterWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.HELICOPTER, validatePath)
    fun readAirWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.AUTHOR_AIR, validatePath)
    fun readStaticWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.AUTHOR_STATIC, validatePath)
    fun readHeavyMechWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.HEAVY_MECH, validatePath)
    fun readBattleshipWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.BATTLESHIP, validatePath)
    fun readAuthorWaterWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.AUTHOR_WATER, validatePath)
    fun readHoverTankWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, Kind.HOVER_TANK, validatePath)

    private fun readWithSources(path: Path, kind: Kind, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        UnitSourceReader(when (kind) {
            Kind.HOVER_TANK -> schema.copy(
                fields = schema.fields + ("projectile_1" to (schema.fields.getValue("projectile_1") + setOf("targetSpeed", "targetSpeedAcceleration"))),
                numericFields = schema.numericFields + setOf("targetSpeed", "targetSpeedAcceleration"),
                movementTemplates = mapOf("hoverTank" to hoverTemplate), structuredAbilities = true,
                blinkAbility = true, transportAbility = true, pointDefenseAbility = true, deathExplosionAbility = true)
            Kind.DECLARED_TANK, Kind.NATIVE_TANK -> schema.copy(movementTemplates = mapOf("tank" to tankTemplate),
                structuredAbilities = true)
            Kind.BATTLESHIP -> NavalContentFields.schema(schema)
            Kind.AUTHOR_WATER -> NavalContentFields.schema(schema).copy(structuredAbilities = true, navalInterceptionAbility = true)
            Kind.HEAVY_MECH -> HeavyMechContentFields.schema(schema).copy(structuredAbilities = true)
            Kind.MECH -> MechContentFields.schema(schema)
            Kind.HELICOPTER -> HelicopterContentFields.schema(schema)
            Kind.AUTHOR_AIR -> HelicopterContentFields.schema(FlameWeaponContent.schema(schema), rotor = false)
            Kind.AUTHOR_STATIC -> FlameWeaponContent.schema(schema)
        }, preRead = validatePath).load(path) { loaded ->
        val declared = kind != Kind.NATIVE_TANK
        val mech = kind == Kind.MECH
        val helicopter = kind == Kind.HELICOPTER
        val authoredAir = kind == Kind.AUTHOR_AIR
        val stationary = kind == Kind.AUTHOR_STATIC
        val air = helicopter || authoredAir
        val heavy = kind == Kind.HEAVY_MECH
        val naval = kind == Kind.BATTLESHIP
        val authorWater = kind == Kind.AUTHOR_WATER
        val hover = kind == Kind.HOVER_TANK
        val water = naval || authorWater
        val sections = loaded.values
        val blink = if (hover) BlinkContent.read(path, loaded) else null
        val transport = if (hover) TransportContent.read(path, loaded) else null
        val defense = if (hover) PointDefenseContent.read(path, loaded) else null
        val death = if (hover) DeathExplosionContent.read(path, loaded) else null
        val deployedBlink = blink?.locked == true && sections["movement"]?.get("movementType") == "NONE"
        fun invalid(code: ContentErrorCode, s: String, key: String, ref: String? = null, reason: String): Nothing {
            val origin = loaded.origins[s to key] ?: UnitSourceReader.Origin(path, s, key)
            val context = if (origin.chain.size > 1 || origin.section != s)
                "; inheritance=${origin.chain.joinToString(" -> ")}; consumer=[$s].$key" else ""
            reject(code, origin.path, origin.section, origin.field, ref, reason + context)
        }
        if ((authoredAir || stationary) && !loaded.structured)
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "core", "name", reason = "This combat capability requires structured unit content")
        if (death != null && deployedBlink) {
            val section = sections.entries.single { it.key.startsWith("ability_") && it.value["type"] == "deathExplosion" }.key
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, section, "type",
                reason = "Death explosions require the mobile HOVER form")
        }
        fun required(s: String, key: String): String = sections[s]?.get(key)?.takeIf { it.isNotBlank() }
            ?: invalid(ContentErrorCode.MISSING_REQUIRED_FIELD, s, key, reason = "Required combat field is absent or empty")
        fun only(s: String, key: String, expected: String) {
            val value = required(s, key)
            if (value != expected) invalid(ContentErrorCode.UNSUPPORTED_FEATURE, s, key, value, "Only $expected is supported")
        }
        fun number(s: String, key: String, positive: Boolean = false): Double {
            val value = required(s, key).toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0.0 || (positive && value == 0.0)) {
                invalid(ContentErrorCode.MALFORMED_SYNTAX, s, key, reason = "Expected finite ${if (positive) "positive" else "nonnegative"} number")
            }
            return value
        }
        fun signedNumber(s: String, key: String): Double {
            val value = required(s, key).toDoubleOrNull()
            return value?.takeIf { it.isFinite() }
                ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, s, key, reason = "Expected finite signed number")
        }
        fun optionalBoolean(s: String, key: String, default: Boolean): Boolean = when (val value = sections[s]?.get(key)) {
            null -> default
            "true" -> true
            "false" -> false
            else -> invalid(ContentErrorCode.MALFORMED_SYNTAX, s, key, value, "Expected a boolean")
        }
        fun integer(s: String, key: String, positive: Boolean = false): Int {
            val value = required(s, key).toIntOrNull()
            if (value == null || value < 0 || (positive && value == 0)) {
                invalid(ContentErrorCode.MALFORMED_SYNTAX, s, key, reason = "Expected ${if (positive) "positive" else "nonnegative"} integer")
            }
            return value
        }
        fun image(key: String): Path {
            val reference = required("graphics", key)
            return try {
                validatePath(path.parent.resolve(reference).toAbsolutePath().normalize())
                resolveReference(path, reference, "graphics", key)
            } catch (failure: ContentReadException) {
                invalid(failure.error.code, "graphics", key, failure.error.objectRef, failure.error.reason)
            } catch (failure: InvalidPathException) {
                invalid(ContentErrorCode.BAD_REFERENCE, "graphics", key, reference, "Invalid image reference path")
            } catch (failure: IOException) {
                invalid(ContentErrorCode.BAD_REFERENCE, "graphics", key, reference, failure.message ?: "Cannot resolve image reference")
            }
        }
        val weapons = sections.keys.filter { it.startsWith("turret_") }
        val projectiles = sections.keys.filter { it.startsWith("projectile_") }
        if (weapons.isEmpty()) invalid(ContentErrorCode.MISSING_REQUIRED_FIELD, "turret_1", "canShoot", reason = "A combat weapon is required")
        for (weapon in weapons) {
            val name = required(weapon, "projectile")
            if ("projectile_$name" !in projectiles)
                invalid(ContentErrorCode.BAD_REFERENCE, weapon, "projectile", name, "Unknown named projectile")
            val parent = sections.getValue(weapon)["attachedTo"]
            if (parent != null) {
                val parentSection = "turret_$parent"
                if (parentSection !in weapons || parentSection == weapon)
                    invalid(ContentErrorCode.BAD_REFERENCE, weapon, "attachedTo", parent, "Expected a distinct existing parent weapon")
                if (sections.getValue(parentSection)["attachedTo"] != null)
                    invalid(ContentErrorCode.UNSUPPORTED_FEATURE, weapon, "attachedTo", parent, "A parent weapon must be attached directly to the body")
            }
            if (optionalBoolean(weapon, "slave", false) && parent == null)
                invalid(ContentErrorCode.UNSUPPORTED_FEATURE, weapon, "slave", reason = "A slave weapon requires attachedTo")
            sections.getValue(weapon)["linkDelayWithTurret"]?.let { link ->
                if ("turret_$link" !in weapons || "turret_$link" == weapon)
                    invalid(ContentErrorCode.BAD_REFERENCE, weapon, "linkDelayWithTurret", link, "Expected a distinct existing linked weapon")
            }
        }
        for (weapon in weapons) {
            val visited = mutableSetOf(weapon)
            var next = sections.getValue(weapon)["linkDelayWithTurret"]
            while (next != null) {
                val section = "turret_$next"
                if (!visited.add(section))
                    invalid(ContentErrorCode.UNSUPPORTED_FEATURE, weapon, "linkDelayWithTurret",
                        sections.getValue(weapon)["linkDelayWithTurret"], "Cyclic links are outside this finite weapon contract")
                next = sections.getValue(section)["linkDelayWithTurret"]
            }
        }
        val price = required("core", "price").toLongOrNull()?.takeIf { it >= 0L }
            ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "price", reason = "Expected nonnegative integer cost")
        val reclaimPrice = sections.getValue("core")["reclaimPrice"]?.let { text ->
            text.toLongOrNull()?.takeIf { it >= 0L } ?: invalid(ContentErrorCode.MALFORMED_SYNTAX,
                "core", "reclaimPrice", reason = "Expected nonnegative integer reclaim cost")
        }
        val buildRate = required("core", "buildSpeed").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "buildSpeed", reason = "Expected finite positive binary32 build rate")
        val identity = required("core", "name")
        val techLevel = sections["core"]?.get("techLevel")?.let { text ->
            text.toIntOrNull()?.takeIf { it in 1..3 } ?: invalid(ContentErrorCode.MALFORMED_SYNTAX,
                "core", "techLevel", reason = "Expected a tech level from one through three")
        } ?: 1
        if (declared) {
            if (when { hover -> identity != "hoverTank" &&
                (if (blink != null) !identity.matches(Regex("[A-Za-z][A-Za-z0-9_.-]*"))
                else !loaded.structured || !identity.matches(Regex("[a-z][a-z0-9_.-]*")))
                naval -> identity != "heavyBattleship"; mech -> identity != "mechGun"; heavy -> identity != "mechHeavyMissile"
                    else -> !identity.matches(Regex("[a-z][a-z0-9_.-]*")) })
                invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "name", identity, "Expected a stable lowercase content ID")
            if ("overrideAndReplace" in sections["core"].orEmpty())
                invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "core", "overrideAndReplace",
                    reason = "Content-pack replacement requires an explicit package target; not supported by a single pack")
        } else {
            only("core", "name", "c_tank")
            only("core", "overrideAndReplace", "tank")
        }
        only("core", "class", "CustomUnitMetadata")
        only("core", "isBio", "false")
        if (number("core", "softCollisionOnAll") != 0.0) {
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "core", "softCollisionOnAll",
                reason = "Soft unit collision is not supported by the ordinary combat subset")
        }
        only("attack", "canAttack", "true")
        val landRule = sections["attack"]?.get("canAttackLandUnits")
        val onlyWhenLanded = landRule in setOf("notFlying", "if not self.flying")
        if (onlyWhenLanded && !air)
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "attack", "canAttackLandUnits", landRule,
                "Conditional ground fire requires a compatible AIR definition")
        if (!authorWater) {
            if (!authoredAir) only("attack", "canAttackFlyingUnits", if (helicopter || heavy || naval) "true" else "false")
            if (!onlyWhenLanded) only("attack", "canAttackLandUnits", "true")
            only("attack", "canAttackUnderwaterUnits", naval.toString())
        }
        val landDefault = if (authorWater) optionalBoolean("attack", "canAttackLandUnits", true) else true
        val airDefault = if (authorWater || authoredAir) optionalBoolean("attack", "canAttackFlyingUnits", false)
            else helicopter || heavy || naval
        val underwaterDefault = authorWater && optionalBoolean("attack", "canAttackUnderwaterUnits", false)
        for (weapon in weapons) {
            if ("canShoot" in sections.getValue(weapon) && "canAttack" in sections.getValue(weapon))
                invalid(ContentErrorCode.MALFORMED_SYNTAX, weapon, "canShoot", reason = "canShoot and canAttack are aliases and cannot both be specified")
            if ("barrelY" in sections.getValue(weapon) && "size" in sections.getValue(weapon))
                invalid(ContentErrorCode.MALFORMED_SYNTAX, weapon, "barrelY", reason = "barrelY and size are aliases and cannot both be specified")
        }
        val movement = when {
            heavy -> MovementType.OVER_CLIFF
            stationary || deployedBlink -> MovementType.NONE
            hover -> MovementType.HOVER
            water -> MovementType.WATER
            air -> MovementType.AIR
            else -> MovementType.LAND
        }
        only("movement", "movementType", movement.name)
        val sliding = air || heavy || hover && !deployedBlink
        if (!deployedBlink) {
            only("movement", "moveSlidingMode", sliding.toString())
            only("movement", "moveIgnoringBody", sliding.toString())
        }
        val sight = sections["core"]?.get("fogOfWarSightRange")?.let {
            it.toIntOrNull() ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "fogOfWarSightRange", reason = "Expected integer sight range")
        } ?: 15
        val imageScale = if ("imageScale" in sections["graphics"].orEmpty()) number("graphics", "imageScale", true) else 1.0
        val turretScale = if ("turretImageScale" in sections["graphics"].orEmpty()) number("graphics", "turretImageScale", true) else 1.0
        val gait = when { heavy -> HeavyMechContentFields.read(path, loaded, validatePath)
            mech -> MechContentFields.read(path, loaded, validatePath); else -> null }
        val flight = if (air) HelicopterContentFields.flight(path, loaded) else null
        val rotor = if (helicopter) HelicopterContentFields.rotor(path, loaded, validatePath) else null
        val reverse = if ((authorWater || authoredAir) && "reverseSpeedPercentage" !in sections.getValue("movement")) 0.0
            else if (mech || air || water) number("movement", "reverseSpeedPercentage") else 0.0
        val validReverse = when {
            authoredAir -> reverse in 0.0..1.0
            helicopter -> reverse == .1
            else -> reverse in setOf(0.0, 1.0)
        }
        if (!validReverse) invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "movement", "reverseSpeedPercentage",
            reason = "Unsupported reverse speed for this movement profile")
        if (stationary) for (field in listOf("moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration")) {
            if (number("movement", field) != 0.0)
                invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "movement", field, reason = "Stationary combat requires zero body movement")
        }
        val projectileProfiles = projectiles.associateWith { projectile ->
            val flame = if (authoredAir || stationary) FlameWeaponContent.read(path, loaded, projectile) else null
            if (flame != null) return@associateWith flame
            val ballistic = if (!water && optionalBoolean(projectile, "ballistic", false)) {
                val delay = number(projectile, "ballistic_delaymove_height").toFloat()
                val apex = number(projectile, "ballistic_height").toFloat()
                if (!delay.isFinite() || !apex.isFinite() || apex < delay)
                    invalid(ContentErrorCode.MALFORMED_SYNTAX, projectile, "ballistic_height", reason = "Expected finite apex at or above the movement gate")
                CustomBallisticProfile(delay, apex)
            } else null
            if (!water && ballistic == null && sections.getValue(projectile).keys.any { it in setOf("ballistic_delaymove_height", "ballistic_height") })
                invalid(ContentErrorCode.MALFORMED_SYNTAX, projectile, "ballistic", reason = "Height fields require an enabled arc")
            val base = if (water) NavalContentFields.projectile(path, loaded, projectile) else ProjectileProfile(integer(projectile, "directDamage").toDouble(),
                number(projectile, "life", true), number(projectile, "speed", !mech),
                acceleration = if ((hover || mech || heavy) && ("targetSpeed" in sections.getValue(projectile) ||
                    (hover || mech) && sections.getValue(projectile)["instant"] != "true"))
                    LinearProjectileAcceleration(number(projectile, "targetSpeed", true),
                        if (heavy && "targetSpeedAcceleration" !in sections.getValue(projectile)) .1
                        else number(projectile, "targetSpeedAcceleration", true)) else null,
                splash = if (heavy && "areaDamage" in sections.getValue(projectile))
                    OrdinarySplashProfile(number(projectile, "areaDamage").toFloat(), number(projectile, "areaRadius", true).toFloat()) else null,
                retargetOnDeath = optionalBoolean(projectile, "autoTargetingOnDeadTarget", false), ballistic = ballistic)
            InstantWeaponContent.apply(path, loaded, projectile, base, validatePath)
        }
        val turretImage = if ((mech || air || stationary || authorWater || hover) && required("graphics", "image_turret") == "NONE") null else image("image_turret")
        val deadImage = if ((mech || heavy) && required("graphics", "image_wreak") == "NONE") null else image("image_wreak")
        val mounts = weapons.map { weapon ->
            val projectileName = required(weapon, "projectile")
            NamedWeaponDefinition(weapon.removePrefix("turret_"), projectileName,
                projectileProfiles.getValue("projectile_$projectileName"),
                TurretMount(signedNumber(weapon, "x"), signedNumber(weapon, "y")),
                if ("delay" in sections.getValue(weapon)) number(weapon, "delay", true)
                    else if ("linkDelayWithTurret" in sections.getValue(weapon)) 9000.0 else number("attack", "shootDelay", true),
                when {
                    "barrelY" in sections.getValue(weapon) -> number(weapon, "barrelY")
                    "size" in sections.getValue(weapon) -> number(weapon, "size")
                    else -> number("attack", "turretSize")
                },
                if ("turnSpeed" in sections.getValue(weapon)) number(weapon, "turnSpeed") else number("attack", "turretTurnSpeed"),
                if ((heavy || water) && "turnSpeedAcceleration" !in sections.getValue(weapon)) 0.0
                    else if ((heavy || water) && signedNumber(weapon, "turnSpeedAcceleration") == -1.0) 0.0
                    else number(weapon, "turnSpeedAcceleration"),
                if ((heavy || water) && "image" in sections.getValue(weapon)) {
                    resolveReference(path, required(weapon, "image"), weapon, "image").also(validatePath)
                } else turretImage, turretScale,
                // Keep the existing ordinary adapter's omitted-field default; explicit values are per weapon.
                recoil = WeaponRecoil(if ("recoilOffset" in sections.getValue(weapon)) signedNumber(weapon, "recoilOffset") else if (heavy || water) 0.0 else -2.0,
                    if ("recoilOutTime" in sections.getValue(weapon)) number(weapon, "recoilOutTime", !air) else 4.0,
                    if ("recoilReturnTime" in sections.getValue(weapon)) number(weapon, "recoilReturnTime", !air) else 7.0),
                fireSound = if (hover) FireSoundProfile.HOVER else if (water) NavalContentFields.sound(path, sections.getValue(weapon), weapon) else if (mech || air || stationary || heavy) null else FireSoundProfile.TANK,
                minimumRange = if ("limitingMinRange" in sections.getValue(weapon)) number(weapon, "limitingMinRange") else 0.0,
                maximumRange = if ("limitingRange" in sections.getValue(weapon)) number(weapon, "limitingRange") else null,
                canAttackLandUnits = optionalBoolean(weapon, "canAttackLandUnits", landDefault),
                canAttackFlyingUnits = optionalBoolean(weapon, "canAttackFlyingUnits", airDefault),
                canAttackUnderwaterUnits = optionalBoolean(weapon, "canAttackUnderwaterUnits", underwaterDefault),
                warmup = if ("warmup" in sections.getValue(weapon)) number(weapon, "warmup") else 0.0,
                warmupCallDownRate = if ("warmupCallDownRate" in sections.getValue(weapon)) number(weapon, "warmupCallDownRate") else 4.0,
                warmupNoReset = optionalBoolean(weapon, "warmupNoReset", false),
                warmupShootDelayTransfer = if ("warmupShootDelayTransfer" in sections.getValue(weapon)) number(weapon, "warmupShootDelayTransfer") else 0.0,
                canShoot = optionalBoolean(weapon, "canShoot", optionalBoolean(weapon, "canAttack", true)),
                aimMaxAngle = if ("canAttackMaxAngle" in sections.getValue(weapon)) number(weapon, "canAttackMaxAngle") else 5.0,
                attachedTo = sections.getValue(weapon)["attachedTo"], slave = optionalBoolean(weapon, "slave", false),
                linkDelayWithTurret = sections.getValue(weapon)["linkDelayWithTurret"],
                limitingAngle = sections.getValue(weapon)["limitingAngle"]?.let {
                    val value = signedNumber(weapon, "limitingAngle")
                    when {
                        value == -1.0 -> null
                        value < 0.0 -> invalid(ContentErrorCode.MALFORMED_SYNTAX, weapon, "limitingAngle",
                            reason = "Expected a nonnegative local arc or -1 to disable it")
                        else -> value
                    }
                },
                idleDirection = if ("idleDir" in sections.getValue(weapon)) signedNumber(weapon, "idleDir") else 0.0,
                barrelX = if ("barrelX" in sections.getValue(weapon)) signedNumber(weapon, "barrelX") else null,
                oddShotOffset = if ("barrelOffsetX_onOddShots" in sections.getValue(weapon)) signedNumber(weapon, "barrelOffsetX_onOddShots") else null,
                invisible = (heavy || water) && optionalBoolean(weapon, "invisible", false),
                imageOffsetX = if (heavy && "image_drawOffsetX" in sections.getValue(weapon)) signedNumber(weapon, "image_drawOffsetX") else 0.0,
                imageOffsetY = if (heavy && "image_drawOffsetY" in sections.getValue(weapon)) signedNumber(weapon, "image_drawOffsetY") else 0.0,
                idleDirectionReversing = if (water) {
                    if ("idleDirReversing" in sections.getValue(weapon)) signedNumber(weapon, "idleDirReversing")
                    else if (sections.getValue(weapon)["attachedTo"] != null) 0.0
                    else (if ("idleDir" in sections.getValue(weapon)) signedNumber(weapon, "idleDir") else 0.0) + 180.0
                } else null,
                condition = when (val condition = sections.getValue(weapon)["canAttackCondition"]) {
                    null -> null
                    "notFlying", "if not self.flying" -> WeaponCondition.NOT_FLYING
                    "overWater", "if self.overWater()", "if self.overwater" -> WeaponCondition.OVER_WATER
                    "flagZeroClear", "if not self.hasFlag(id=0)" -> WeaponCondition.SOURCE_FLAG_ZERO_CLEAR
                    else -> invalid(ContentErrorCode.UNSUPPORTED_FEATURE, weapon, "canAttackCondition", condition,
                        "Expected a supported source condition")
                },
                landCondition = WeaponCondition.NOT_FLYING.takeIf { onlyWhenLanded })
        }
        val interception = if (authorWater) NavalInterceptionContent.read(path, loaded, mounts) else null
        val catalog = WeaponCatalog(mounts.map {
            if (it.name == interception?.weapon) it.copy(interception = interception.ranges) else it
        })
        val primary = catalog.ordered.first()
        if ("initialWeaponFlagZero" in sections.getValue("core") &&
            (!loaded.structured || catalog.ordered.none { it.condition == WeaponCondition.SOURCE_FLAG_ZERO_CLEAR }))
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "core", "initialWeaponFlagZero",
                reason = "An initial flag requires a structured weapon with that guard")
        if (catalog.ordered.any { it.projectile.beam != null && it.projectile.beam != primary.projectile.beam })
            invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "turret_${primary.name}", "projectile",
                reason = "Continuous weapon beams share the primary mount's beam images")
        val shield = ShieldContent.read(path, loaded, required = heavy, otherTypes = when {
            hover -> setOf("blink", "transport", "pointDefense", "deathExplosion")
            authorWater -> setOf("navalInterception")
            else -> emptySet()
        })
        val profile = CombatProfile(
            integer("core", "maxHp", true).toDouble(), number("attack", "maxAttackRange", true),
            primary.shootDelay, primary.turretSize, primary.turnSpeed, primary.turnAcceleration,
            primary.projectile, turretImage, deadImage, turretScale,
            mount = primary.mount,
            recoil = primary.recoil, barrelX = primary.barrelX ?: 0.0, oddShotOffset = primary.oddShotOffset ?: 0.0,
            fireSound = if (hover) FireSoundProfile.HOVER else if (water) primary.fireSound else if (mech || air || stationary || heavy) null else FireSoundProfile.TANK,
            idleSweep = if (heavy) null else gait?.body?.let { IdleSweepProfile(it.sweepAngle, it.sweepDelay.toFloat(), it.sweepTimeScale.toFloat(), it.sweepRandomDelay) },
            namedWeapons = catalog,
            multiTargeting = optionalBoolean("attack", "turretMultiTargeting", false),
            rotateWithBody = heavy && optionalBoolean("attack", "turretRotateWithBody", true),
        )
        val definition = UnitDefinition(identity, "CustomUnitMetadata", number("core", "radius", true),
            number("core", "displayRadius", true), integer("graphics", "total_frames", true),
            image("image"), imageScale,
            MovementProfile(movement, number("movement", "moveSpeed"), number("movement", "moveAccelerationSpeed"),
                number("movement", "moveDecelerationSpeed"), number("movement", "maxTurnSpeed"),
                number("movement", "turnAcceleration"), sliding, sliding, reverse,
                if (air || heavy) integer("movement", "moveSlidingDir") else -1, flight,
                if (hover && !deployedBlink) HoverProfile() else null),
            sections.flatMap { (s, values) -> values.keys.filter { it in retained[sectionKind(s)].orEmpty() } }.toSet(), sight, profile,
            build = BuildProfile(price, buildRate), mech = gait, rotor = rotor, reclaimPrice = reclaimPrice,
            building = if (stationary) BuildingProfile(BuildingFootprint(0, 0, 0, 0)) else null,
            authoredAirCombat = authoredAir,
            construction = if (mech) ConstructionProfile(price, buildRate) else null, techLevel = techLevel,
            authoredWaterCombat = authorWater, shadowImagePath = if (hover) image("image_shadow") else null,
            pointDefense = defense, deathExplosion = death,
            initialWeaponFlagZero = optionalBoolean("core", "initialWeaponFlagZero", false),
            shield = shield, shieldImagePath = if (heavy) image("image_shield") else null,
            blink = blink, customActions = blink?.actions() ?: interception?.actions,
            transport = transport?.let { TransportProfile.AUTHORED }, transportSettings = transport,
            transportSlots = if ("transportSlotsNeeded" in sections.getValue("core")) integer("core", "transportSlotsNeeded") else 1)
        definition
    }
}
