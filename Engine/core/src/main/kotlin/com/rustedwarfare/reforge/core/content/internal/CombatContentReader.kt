package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.InvalidPathException
import java.io.IOException

/** Explicit ordinary c_tank subset. This does not register global replacements or load game code. */
internal object CombatContentReader {
    private val fields = mapOf(
        "core" to setOf("name", "overrideAndReplace", "class", "price", "maxHp", "mass", "techLevel",
            "buildSpeed", "radius", "displayRadius", "isBio", "softCollisionOnAll", "displayLocaleKey", "fogOfWarSightRange"),
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
            "limitingAngle", "idleDir",
            "barrelY", "size", "barrelX", "barrelOffsetX_onOddShots", "recoilOutTime", "recoilReturnTime",
            "turnSpeedAcceleration", "shoot_sound", "shoot_sound_vol",
            "shoot_flame", "shoot_light", "canShoot", "recoilOffset"),
        "projectile_1" to setOf("directDamage", "life", "speed", "frame", "drawSize"),
        "movement" to setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
            "maxTurnSpeed", "turnAcceleration", "moveSlidingMode", "moveIgnoringBody"),
    )
    private val retained = mapOf(
        "core" to setOf("mass", "techLevel", "isBio", "softCollisionOnAll", "displayLocaleKey"),
        "graphics" to setOf("image_shadow", "shadowOffsetX", "shadowOffsetY", "animation_moving_start",
            "animation_moving_end", "animation_moving_speed", "dustEffect"),
        "turret_1" to setOf("shoot_sound", "shoot_sound_vol", "shoot_flame", "shoot_light"),
        "projectile_1" to setOf("frame", "drawSize"),
    )
    private val numericFields = setOf("price", "buildSpeed", "maxHp", "radius", "displayRadius",
        "softCollisionOnAll", "fogOfWarSightRange", "total_frames", "imageScale", "turretImageScale",
        "turretSize", "turretTurnSpeed", "maxAttackRange", "shootDelay", "x", "y", "turnSpeedAcceleration",
        "directDamage", "life", "speed", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
        "maxTurnSpeed", "turnAcceleration", "delay", "limitingRange", "limitingMinRange",
        "warmup", "warmupCallDownRate", "warmupShootDelayTransfer", "canAttackMaxAngle", "turnSpeed",
        "limitingAngle", "idleDir", "barrelY", "size", "barrelX", "barrelOffsetX_onOddShots",
        "recoilOffset", "recoilOutTime", "recoilReturnTime")
    private val booleanFields = setOf("isBio", "canAttack", "canAttackFlyingUnits", "canAttackLandUnits",
        "canAttackUnderwaterUnits", "canShoot", "moveSlidingMode", "moveIgnoringBody", "dont_load", "turretMultiTargeting",
        "warmupNoReset", "slave")
    private val textFields = setOf("name", "overrideAndReplace", "class", "displayLocaleKey",
        "image", "image_wreak", "image_turret", "movementType", "projectile", "attachedTo", "linkDelayWithTurret")

    private fun sectionKind(section: String): String = when {
        section.startsWith("turret_") -> "turret_1"
        section.startsWith("projectile_") -> "projectile_1"
        else -> section
    }

    private val schema = UnitContentSchema(fields, numericFields, booleanFields, textFields)

    fun read(path: Path): UnitDefinition = readWithSources(path).definition

    fun readWithSources(path: Path): LoadedUnitContent = readWithSources(path, false)

    fun readDeclaredWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, true, validatePath)

    fun readMechWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, true, validatePath, mech = true)

    fun readHelicopterWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, true, validatePath, helicopter = true)

    fun readHeavyMechWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        readWithSources(path, true, validatePath, heavy = true)

    private fun readWithSources(path: Path, declared: Boolean, validatePath: (Path) -> Unit = {}, mech: Boolean = false,
                                helicopter: Boolean = false, heavy: Boolean = false): LoadedUnitContent =
        UnitSourceReader(when { heavy -> HeavyMechContentFields.schema(schema); mech -> MechContentFields.schema(schema)
            helicopter -> HelicopterContentFields.schema(schema); else -> schema }, preRead = validatePath).load(path) { loaded ->
        val sections = loaded.values
        fun invalid(code: ContentErrorCode, s: String, key: String, ref: String? = null, reason: String): Nothing {
            val origin = loaded.origins[s to key] ?: UnitSourceReader.Origin(path, s, key)
            val context = if (origin.chain.size > 1 || origin.section != s)
                "; inheritance=${origin.chain.joinToString(" -> ")}; consumer=[$s].$key" else ""
            reject(code, origin.path, origin.section, origin.field, ref, reason + context)
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
        val buildRate = required("core", "buildSpeed").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "buildSpeed", reason = "Expected finite positive binary32 build rate")
        val identity = required("core", "name")
        if (declared) {
            if (when { mech -> identity != "mechGun"; heavy -> identity != "mechHeavyMissile"
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
        only("attack", "canAttackFlyingUnits", if (helicopter || heavy) "true" else "false")
        only("attack", "canAttackLandUnits", "true")
        only("attack", "canAttackUnderwaterUnits", "false")
        for (weapon in weapons) {
            if ("canShoot" in sections.getValue(weapon) && "canAttack" in sections.getValue(weapon))
                invalid(ContentErrorCode.MALFORMED_SYNTAX, weapon, "canShoot", reason = "canShoot and canAttack are aliases and cannot both be specified")
            if ("barrelY" in sections.getValue(weapon) && "size" in sections.getValue(weapon))
                invalid(ContentErrorCode.MALFORMED_SYNTAX, weapon, "barrelY", reason = "barrelY and size are aliases and cannot both be specified")
        }
        only("movement", "movementType", if (helicopter) "AIR" else "LAND")
        only("movement", "moveSlidingMode", (helicopter || heavy).toString())
        only("movement", "moveIgnoringBody", (helicopter || heavy).toString())
        val sight = sections["core"]?.get("fogOfWarSightRange")?.let {
            it.toIntOrNull() ?: invalid(ContentErrorCode.MALFORMED_SYNTAX, "core", "fogOfWarSightRange", reason = "Expected integer sight range")
        } ?: 15
        val imageScale = if ("imageScale" in sections["graphics"].orEmpty()) number("graphics", "imageScale", true) else 1.0
        val turretScale = if ("turretImageScale" in sections["graphics"].orEmpty()) number("graphics", "turretImageScale", true) else 1.0
        val gait = when { heavy -> HeavyMechContentFields.read(path, loaded, validatePath)
            mech -> MechContentFields.read(path, loaded, validatePath); else -> null }
        val flight = if (helicopter) HelicopterContentFields.read(path, loaded, validatePath) else null
        val reverse = if (mech || helicopter) number("movement", "reverseSpeedPercentage") else 0.0
        if (reverse !in (if (helicopter) setOf(0.1) else setOf(0.0, 1.0))) invalid(ContentErrorCode.UNSUPPORTED_FEATURE, "movement", "reverseSpeedPercentage",
            reason = "The finite mech supports disabled or full-speed reverse")
        val projectileProfiles = projectiles.associateWith { projectile ->
            ProjectileProfile(integer(projectile, "directDamage").toDouble(),
                number(projectile, "life", true), number(projectile, "speed", !mech),
                acceleration = if (mech || heavy && "targetSpeed" in sections.getValue(projectile))
                    LinearProjectileAcceleration(number(projectile, "targetSpeed", true),
                        if (heavy && "targetSpeedAcceleration" !in sections.getValue(projectile)) .1
                        else number(projectile, "targetSpeedAcceleration", true)) else null,
                splash = if (heavy && "areaDamage" in sections.getValue(projectile))
                    OrdinarySplashProfile(number(projectile, "areaDamage").toFloat(), number(projectile, "areaRadius", true).toFloat()) else null,
                retargetOnDeath = heavy && optionalBoolean(projectile, "autoTargetingOnDeadTarget", false))
        }
        val turretImage = if ((mech || helicopter) && required("graphics", "image_turret") == "NONE") null else image("image_turret")
        val deadImage = if ((mech || heavy) && required("graphics", "image_wreak") == "NONE") null else image("image_wreak")
        val catalog = WeaponCatalog(weapons.map { weapon ->
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
                if (heavy && "turnSpeedAcceleration" !in sections.getValue(weapon)) 0.0
                    else if (heavy && signedNumber(weapon, "turnSpeedAcceleration") == -1.0) 0.0
                    else number(weapon, "turnSpeedAcceleration"),
                if (heavy && "image" in sections.getValue(weapon)) {
                    resolveReference(path, required(weapon, "image"), weapon, "image").also(validatePath)
                } else turretImage, turretScale,
                // Keep the existing ordinary adapter's omitted-field default; explicit values are per weapon.
                recoil = WeaponRecoil(if ("recoilOffset" in sections.getValue(weapon)) signedNumber(weapon, "recoilOffset") else if (heavy) 0.0 else -2.0,
                    if ("recoilOutTime" in sections.getValue(weapon)) number(weapon, "recoilOutTime", !helicopter) else 4.0,
                    if ("recoilReturnTime" in sections.getValue(weapon)) number(weapon, "recoilReturnTime", !helicopter) else 7.0),
                fireSound = if (mech || helicopter || heavy) null else FireSoundProfile.TANK,
                minimumRange = if ("limitingMinRange" in sections.getValue(weapon)) number(weapon, "limitingMinRange") else 0.0,
                maximumRange = if ("limitingRange" in sections.getValue(weapon)) number(weapon, "limitingRange") else null,
                canAttackLandUnits = optionalBoolean(weapon, "canAttackLandUnits", true),
                canAttackFlyingUnits = optionalBoolean(weapon, "canAttackFlyingUnits", helicopter || heavy),
                canAttackUnderwaterUnits = optionalBoolean(weapon, "canAttackUnderwaterUnits", false),
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
                invisible = heavy && optionalBoolean(weapon, "invisible", false),
                imageOffsetX = if (heavy && "image_drawOffsetX" in sections.getValue(weapon)) signedNumber(weapon, "image_drawOffsetX") else 0.0,
                imageOffsetY = if (heavy && "image_drawOffsetY" in sections.getValue(weapon)) signedNumber(weapon, "image_drawOffsetY") else 0.0)
        })
        val primary = catalog.ordered.first()
        val profile = CombatProfile(
            integer("core", "maxHp", true).toDouble(), number("attack", "maxAttackRange", true),
            primary.shootDelay, primary.turretSize, primary.turnSpeed, primary.turnAcceleration,
            primary.projectile, turretImage, deadImage, turretScale,
            mount = primary.mount,
            recoil = primary.recoil, barrelX = primary.barrelX ?: 0.0, oddShotOffset = primary.oddShotOffset ?: 0.0,
            fireSound = if (mech || helicopter || heavy) null else FireSoundProfile.TANK,
            idleSweep = if (heavy) null else gait?.body?.let { IdleSweepProfile(it.sweepAngle, it.sweepDelay.toFloat(), it.sweepTimeScale.toFloat(), it.sweepRandomDelay) },
            namedWeapons = catalog,
            multiTargeting = optionalBoolean("attack", "turretMultiTargeting", false),
            rotateWithBody = heavy && optionalBoolean("attack", "turretRotateWithBody", true),
        )
        val definition = UnitDefinition(identity, "CustomUnitMetadata", number("core", "radius", true),
            number("core", "displayRadius", true), integer("graphics", "total_frames", true),
            image("image"), imageScale,
            MovementProfile(if (helicopter) MovementType.AIR else MovementType.LAND, number("movement", "moveSpeed"), number("movement", "moveAccelerationSpeed"),
                number("movement", "moveDecelerationSpeed"), number("movement", "maxTurnSpeed"),
                number("movement", "turnAcceleration"), helicopter || heavy, helicopter || heavy, reverse,
                if (helicopter || heavy) integer("movement", "moveSlidingDir") else -1, flight?.first),
            sections.flatMap { (s, values) -> values.keys.filter { it in retained[sectionKind(s)].orEmpty() } }.toSet(), sight, profile,
            build = BuildProfile(price, buildRate), mech = gait, rotor = flight?.second)
        definition
    }
}
