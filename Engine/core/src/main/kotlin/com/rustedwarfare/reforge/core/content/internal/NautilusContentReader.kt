package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Finite three-form content; unknown fields cannot grant additional capabilities. */
internal object NautilusContentReader {
    private val core = linkedMapOf("name" to "nautilusSubmarine", "maxHp" to "4000", "radius" to "36",
        "price" to "18000", "buildSpeed" to "0.0003", "techLevel" to "2", "fogOfWarSightRange" to "24",
        "selfRegenRate" to "0.1", "nanoFactorySpeed" to "2", "productionUnits" to "hoverTank",
        "energyMax" to "6", "energyRegen" to "0.01", "energyNeedsToRechargeToFull" to "true",
        "maxTransportingUnits" to "3", "transportUnitsRequireMovementType" to "WATER,HOVER,OVER_CLIFF_WATER",
        "transportUnitsCanUnloadUnits" to "false", "transportUnitsRequireTag" to "nonexistent",
        "transportUnitsKeepBuiltUnits" to "true", "exit_x" to "0", "exit_y" to "30",
        "exit_dirOffset" to "0", "exit_moveAwayAmount" to "110", "exit_heightOffset" to "0")
    private val movement = linkedMapOf("movementType" to "WATER", "moveSpeed" to "0.45",
        "moveAccelerationSpeed" to "0.015", "moveDecelerationSpeed" to "0.05", "reverseSpeedPercentage" to "0.6",
        "maxTurnSpeed" to "0.8", "turnAcceleration" to "0.05", "targetHeight" to "-8",
        "targetHeightDrift" to "0.1", "heightChangeRate" to "0.005")
    private val flags = setOf("energyNeedsToRechargeToFull", "transportUnitsKeepBuiltUnits", "slowDeathFall",
        "whenBuilding_cannotMove", "highPriorityQueue", "isVisible", "lockMovement", "canAttack", "canShoot", "invisible",
        "canAttackFlyingUnits", "canAttackLandUnits", "canAttackUnderwaterUnits", "canAttackNotTouchingWaterUnits",
        "ballistic", "autoTargetingOnDeadTarget", "allowMultipleInQueue", "targetGround")
    private val texts = setOf("name", "productionUnits", "transportUnitsRequireMovementType", "transportUnitsRequireTag",
        "movementType", "image", "image_dead", "image_leg", "image_foot", "convertTo", "autoTrigger", "isActive", "projectile",
        "linkDelayWithTurret", "id", "fireTurretXAtGround", "fireTurretXAtGround_onlyOverPassableTileOf",
        "spawnProjectilesOnEndOfLife", "spawnUnit", "addResources", "isLocked", "interceptProjectiles_withTags",
        "resourceUsage", "shoot_sound")
    private val cores = mapOf("nautilusSubmarine" to core,
        "nautilusSubmarineSurface" to (core + mapOf("name" to "nautilusSubmarineSurface",
            "transportUnitsCanUnloadUnits" to "if self.isOverLiquid()", "transportUnitsRequireTag" to "",
            "transportUnitsKeepBuiltUnits" to "false")),
        "nautilusSubmarineLand" to (core + mapOf("name" to "nautilusSubmarineLand")))
    private val movements = mapOf("nautilusSubmarine" to movement,
        "nautilusSubmarineSurface" to (movement + mapOf("movementType" to "HOVER", "moveSpeed" to "0.6", "targetHeight" to "0.1")),
        "nautilusSubmarineLand" to (movement + mapOf("movementType" to "OVER_CLIFF_WATER", "moveSpeed" to "0.35",
            "moveAccelerationSpeed" to "0.05", "maxTurnSpeed" to "0.5", "turnAcceleration" to "0.2",
            "targetHeight" to "2", "targetHeightDrift" to "0", "moveSlidingDir" to "45", "slowDeathFall" to "true")))
    private val movementKeys = movements.values.flatMap { it.keys }.toSet()
    private fun actionFields(id: String): Map<String, Map<String, String>> = linkedMapOf(
        "action_surface" to mapOf("convertTo" to "nautilusSubmarineSurface", "price" to "0", "buildSpeed" to "1",
            "whenBuilding_cannotMove" to "true", "highPriorityQueue" to "true", "isVisible" to (id == "nautilusSubmarine").toString()),
        "action_dive" to mapOf("convertTo" to "nautilusSubmarine", "price" to "0", "buildSpeed" to "4.5s",
            "whenBuilding_cannotMove" to "true", "highPriorityQueue" to "true", "isVisible" to (id == "nautilusSubmarineSurface").toString(),
            "isActive" to "if self.isOverwater"),
        "action_launchScoutBot" to mapOf("id" to "launchScoutBot", "price" to "250", "buildSpeed" to "3.5s",
            "fireTurretXAtGround" to "botTurret", "fireTurretXAtGround_onlyOverPassableTileOf" to "HOVER",
            "addActionCooldownTime" to "30s", "allowMultipleInQueue" to "false",
            "whenBuilding_cannotMove" to "true", "isVisible" to (id != "nautilusSubmarine").toString()),
        "action_buildAntiNuke" to mapOf("id" to "buildAntiNuke", "price" to "6000", "buildSpeed" to "0.00025",
            "isActive" to "if self.ammoIncludingQueued(lessThan=4)", "addResources" to "ammo=1",
            "isLocked" to "if not game.nukesEnabled()"),
        "hiddenAction_trigger_onLand" to mapOf("autoTrigger" to "if not self.overWater()", "convertTo" to "nautilusSubmarineLand")) +
        if (id == "nautilusSubmarineLand") mapOf("action_trigger_onWater" to mapOf("autoTrigger" to "if self.overWater()",
            "convertTo" to "nautilusSubmarineSurface", "buildSpeed" to "0", "isVisible" to "false")) else emptyMap()
    private val actionSchema = actionFields("nautilusSubmarineLand").mapValues { it.value.keys }
    private fun legFields(id: String): Map<String, Map<String, String>> {
        if (id == "nautilusSubmarine") return emptyMap()
        val land = id == "nautilusSubmarineLand"
        return NautilusRules.feet(land).mapIndexed { index, point ->
            val attach = NautilusRules.attachments[index]
            "leg_${index + 1}" to mapOf("x" to point.first.toString(), "y" to point.second.toString(),
                "attach_x" to attach.first.toString(), "attach_y" to attach.second.toString(),
                "moveSpeed" to (if (land) "1.5" else "0.3"), "resetAngle" to "20", "holdDisMin" to "10",
                "holdDisMax" to "20", "image_leg" to "leg.png", "image_foot" to "foot.png", "lockMovement" to (!land).toString())
        }.toMap()
    }
    private val legSchema = legFields("nautilusSubmarineLand").mapValues { it.value.keys }
    private val weaponSchema = NautilusWeapons.fields("nautilusSubmarine").mapValues { it.value.keys }
    private val schema = UnitContentSchema(mapOf("core" to (core.keys + setOf("image", "image_dead")),
        "movement" to movementKeys) + actionSchema + legSchema + weaponSchema,
        numericFields = (core.keys + movementKeys + actionSchema.values.flatten() + legSchema.values.flatten() + weaponSchema.values.flatten()) - flags - texts - "transportUnitsCanUnloadUnits",
        booleanFields = flags, textFields = texts, dynamicWeapons = false,
        timeFieldLocations = setOf("action_dive" to "buildSpeed", "action_launchScoutBot" to "buildSpeed",
            "action_launchScoutBot" to "addActionCooldownTime"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required finite Nautilus field")
            val id = value("core", "name")
            val expectedCore = cores[id] ?: reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected a qualified Nautilus form")
            val expectedMovement = movements.getValue(id)
            for (field in loaded.values["movement"].orEmpty().keys) if (field !in expectedMovement)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "movement", field, reason = "Field is not used by this Nautilus form")
            val expectedSections = mapOf("core" to expectedCore, "movement" to expectedMovement) + actionFields(id) + legFields(id) + NautilusWeapons.fields(id)
            for (section in loaded.values.keys) if (section !in expectedSections)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, reason = "Section is not used by this Nautilus form")
            for ((section, fields) in expectedSections) for ((field, expected) in fields) {
                val actual = if (expected.isEmpty()) loaded.values[section]?.get(field)
                    ?: reject(ContentErrorCode.MISSING_REQUIRED_FIELD, path, section, field, reason = "Explicit empty tag required")
                    else value(section, field)
                val same = expected.toDoubleOrNull()?.let { n -> actual.toDoubleOrNull()?.let { it.isFinite() && it == n } }
                    ?: (actual == expected)
                if (!same) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, field,
                    reason = "Expected qualified Nautilus value $expected")
            }
            fun image(field: String): Path = resolveReference(path, value("core", field), "core", field).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require(png.width == 42 && png.height == 90) { "Expected 42x90 PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "core", field, reason = "Invalid Nautilus PNG: ${failure.message}")
                }
            }
            fun legImage(field: String, width: Int, height: Int): Path =
                resolveReference(path, value("leg_1", field), "leg_1", field).also {
                    validatePath(it)
                    try {
                        val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                        require(png.width == width && png.height == height)
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        reject(ContentErrorCode.BAD_REFERENCE, path, "leg_1", field, reason = "Invalid Nautilus leg PNG: ${failure.message}")
                    }
                }
            UnitDefinition(id, "CustomUnitMetadata", 36.0, 36.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.valueOf(expectedMovement.getValue("movementType")),
                    expectedMovement.getValue("moveSpeed").toDouble(), expectedMovement.getValue("moveAccelerationSpeed").toDouble(),
                    .05, expectedMovement.getValue("maxTurnSpeed").toDouble(), expectedMovement.getValue("turnAcceleration").toDouble(),
                    false, false, .6, expectedMovement["moveSlidingDir"]?.toInt() ?: -1), emptySet(), 24,
                durability = DurabilityProfile(4000.0, image("image_dead")), combat = NautilusWeapons.combat(id, image("image_dead")),
                production = ProductionProfile(listOf("hoverTank"), FactoryExitProfile.CUSTOM_NAUTILUS, 2f),
                build = BuildProfile(18000L, .0003f), transport = TransportProfile.NAUTILUS_SUBMERGED,
                energy = EnergyProfile(6f, .01f, .01f, true), selfRegenRate = .1f, techLevel = 2,
                customActions = NautilusRules.actions(id),
                mech = if (id == "nautilusSubmarine") null else NautilusRules.legs(id,
                    legImage("image_leg", 16, 68), legImage("image_foot", 17, 36)))
        }
}
