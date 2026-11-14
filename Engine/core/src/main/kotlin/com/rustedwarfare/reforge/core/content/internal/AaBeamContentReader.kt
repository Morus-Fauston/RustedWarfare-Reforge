package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Fixed, qualified custom AA representative; not the unrelated native continuous beam. */
internal object AaBeamContentReader {
    private val actionFields = setOf("buildSpeed", "convertTo", "resourceAmount", "resourceAmount_setValue",
        "addActionCooldownTime", "canPlayerCancel", "allowMultipleInQueue")
    private val schema = UnitContentSchema(mapOf(
        "afterburn" to setOf("normalForm", "boostedForm"),
        "core" to setOf("name", "maxHp", "radius", "price", "buildSpeed", "image", "image_dead", "image_shadow",
            "image_turret", "beam_image", "beam_image_start", "beam_image_end", "energyRegen",
            "energyRegenWhenRecharging", "energyNeedsToRechargeToFull", "afterburn_trail_image"),
        "movement" to setOf("moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration"),
        "turret_1" to setOf("energyUsage", "idleSweepAngle"),
        "action_afterBurn" to actionFields, "action_cancelBurn" to actionFields,
        "hiddenAction_autoSwitchBack" to setOf("autoTrigger", "convertTo", "resourceAmount", "resourceAmount_setValue")),
        numericFields = setOf("maxHp", "radius", "price", "buildSpeed", "energyRegen", "energyRegenWhenRecharging",
            "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration",
            "energyUsage", "idleSweepAngle"),
        booleanFields = setOf("energyNeedsToRechargeToFull", "canPlayerCancel", "allowMultipleInQueue"),
        textFields = setOf("name", "image", "image_dead", "image_shadow", "image_turret", "beam_image",
            "beam_image_start", "beam_image_end", "normalForm", "boostedForm", "convertTo", "resourceAmount", "autoTrigger", "addActionCooldownTime", "afterburn_trail_image"),
        dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath, inputGuard = { file, input, _ ->
            if (!input.structured && "afterburn" in input.sections)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, file, "afterburn",
                    reason = "The afterburn profile section requires structured unit content")
        }).load(path) { loaded ->
            val fields = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(fields[key], path, "core", key, "Required AA beam gunship field")
            val name = required("name")
            val profile = if (!loaded.structured) AfterburnProfile.ORIGINAL else {
                val declaration = loaded.values["afterburn"]
                    ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, "afterburn", reason = "Declare the normal and boosted forms")
                val normal = requiredContentField(declaration["normalForm"], path, "afterburn", "normalForm", "Required normal form id")
                val boosted = requiredContentField(declaration["boostedForm"], path, "afterburn", "boostedForm", "Required boosted form id")
                AfterburnProfile(normal, boosted)
            }
            if (!profile.validIds())
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, "afterburn", "boostedForm",
                    reason = "Expected two distinct unit ids")
            if (name !in setOf(profile.normalForm, profile.boostedForm))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", reason = "Expected the normal or boosted form in the afterburn profile")
            val afterburn = name == profile.boostedForm
            if (!afterburn && fields.containsKey("afterburn_trail_image"))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "afterburn_trail_image", reason = "Only the accelerated form emits trails")
            fun expected(section: String, key: String, value: String, optional: Boolean = false): String {
                val actual = loaded.values[section]?.get(key) ?: if (optional) value else
                    reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, key, reason = "Required AA form field")
                val same = value.toDoubleOrNull()?.let { actual.toDoubleOrNull()?.let { n -> n.isFinite() && n == it } }
                    ?: (actual == value)
                if (!same) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, key, reason = "Expected qualified value $value")
                return actual
            }
            expected("core", "energyRegen", if (afterburn) "-0.02" else "0.01")
            expected("core", "energyRegenWhenRecharging", "0.05")
            expected("core", "energyNeedsToRechargeToFull", "true")
            val movement = if (afterburn) listOf(2.3, .13, .13, 1.5, .1) else listOf(1.0, .015, .015, 1.2, .04)
            for ((key, value) in listOf("moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration").zip(movement))
                expected("movement", key, value.toString(), optional = !afterburn)
            expected("turret_1", "energyUsage", if (afterburn) "99" else "0.5", optional = !afterburn)
            expected("turret_1", "idleSweepAngle", if (afterburn) "0" else "20", optional = !afterburn)
            fun action(section: String, index: Int, target: String, energy: Boolean, cooldown: Boolean,
                       automatic: AutomaticActionCondition? = null): CustomActionDefinition {
                expected(section, "convertTo", target)
                if (automatic == null) {
                    expected(section, "buildSpeed", "0")
                    expected(section, "canPlayerCancel", "false")
                    expected(section, "allowMultipleInQueue", "false")
                } else expected(section, "autoTrigger", "if self.energy(empty=true)")
                expected(section, "resourceAmount", if (energy) "energy" else "IGNORE")
                expected(section, "resourceAmount_setValue", if (energy) "6" else "IGNORE")
                if (cooldown) expected(section, "addActionCooldownTime", "25s")
                else if (loaded.values[section]?.containsKey("addActionCooldownTime") == true)
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, "addActionCooldownTime", reason = "No cooldown on this qualified action")
                return CustomActionDefinition(section.substringAfter('_'), "${target}_$index", target,
                    if (automatic == null) 1000f else 0f, if (energy) 6f else null, if (cooldown) 25000 else 0,
                    automatic = automatic)
            }
            val actions = mutableListOf(action("action_afterBurn", 0, profile.boostedForm, !afterburn, true))
            if (afterburn) {
                actions += action("action_cancelBurn", 1, profile.normalForm, true, false)
                actions += action("hiddenAction_autoSwitchBack", 2, profile.normalForm, true, false, AutomaticActionCondition.ENERGY_EMPTY)
            } else if (loaded.values.keys.any { it in setOf("action_cancelBurn", "hiddenAction_autoSwitchBack") })
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, reason = "Normal AA form does not declare afterburn recovery actions")
            for ((key, expected) in mapOf("maxHp" to 2500.0, "radius" to 22.0, "price" to 6000.0)) {
                val value = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected a finite number")
                if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected qualified value $expected")
            }
            if (required("buildSpeed").toFloatOrNull() != 1f / 2100f)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "buildSpeed", reason = "Expected qualified 35s production rate")
            fun image(key: String, width: Int, height: Int): Path = resolveReference(path, required(key), "graphics", key).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == width && png.height == height) { "Expected ${width}x$height PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid AA beam PNG: ${failure.message}")
                }
            }
            val projectile = ProjectileProfile(22.0, 15.0, 5.0, instant = true,
                damageModifiers = ProjectileDamageModifiers(shieldDamage = .75),
                beam = BeamProfile(image("beam_image", 13, 20), image("beam_image_start", 20, 20), image("beam_image_end", 20, 20)))
            val turret = image("image_turret", 16, 26)
            val weapon = NamedWeaponDefinition("1", "beam", projectile, TurretMount(0.0, 2.0), 5.0, 16.0,
                4.2, .3, turret, 1.0, recoil = WeaponRecoil(0.0), canAttackFlyingUnits = true,
                imageOffsetY = 5.0, energyUsage = if (afterburn) 99f else .5f)
            UnitDefinition(name, "CustomUnitMetadata", 22.0, 22.0, 1, image("image", 33, 52), 1.0,
                MovementProfile(MovementType.AIR, movement[0], movement[1], movement[2], movement[3], movement[4], true, true, .1, 50,
                    FlightProfile(35.0, 1.0, -1.0, 0.0)), emptySet(),
                combat = CombatProfile(2500.0, 280.0, 5.0, 16.0, 4.2, .3, projectile,
                    turret, image("image_dead", 33, 52), 1.0, mount = TurretMount(0.0, 2.0),
                    recoil = WeaponRecoil(0.0), idleSweep = IdleSweepProfile(if (afterburn) 0.0 else 20.0, 100f, .4f, 5),
                    namedWeapons = WeaponCatalog(listOf(weapon))), build = BuildProfile(6000L, 1f / 2100f),
                shadowImagePath = image("image_shadow", 33, 52), energy = EnergyProfile(6f, if (afterburn) -.02f else .01f, .05f, true),
                customActions = CustomActionsProfile(actions), afterburn = profile,
                afterburnTrailImagePath = if (afterburn) image("afterburn_trail_image", 32, 32) else null)
        }
}
