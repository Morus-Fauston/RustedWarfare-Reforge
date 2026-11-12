package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal object ScoutBotContentReader {
    private val core = mapOf("radius" to "11", "displayRadius" to "13", "maxHp" to "650", "price" to "700",
        "buildSpeed" to "0.002", "reclaimPrice" to "0", "fogOfWarSightRange" to "22",
        "energyMax" to "1", "energyRegen" to "-0.0005", "energyStartingPercentage" to "1",
        "dieOnZeroEnergy" to "true", "nanoRange" to "110", "nanoRepairSpeed" to "0.4", "nanoBuildSpeed" to "0.4",
        "canRepairBuildings" to "true", "canRepairUnits" to "true", "canReclaimResources" to "true")
    private val movement = mapOf("movementType" to "OVER_CLIFF_WATER", "moveAccelerationSpeed" to "1",
        "moveDecelerationSpeed" to "1", "maxTurnSpeed" to "4", "targetHeight" to "0.1")
    private val attack = mapOf("canAttackLandUnits" to "true", "canAttackFlyingUnits" to "true",
        "canAttackUnderwaterUnits" to "false", "maxAttackRange" to "110", "shootDelay" to "50")
    private val projectile = mapOf("directDamage" to "17", "life" to "70", "speed" to "6")
    private val images = mapOf("image" to (24 to 21), "image_dead" to (24 to 21), "image_turret" to (8 to 15),
        "image_leg" to (24 to 102), "image_foot" to (8 to 8))
    private val flags = setOf("dieOnZeroEnergy", "canRepairBuildings", "canRepairUnits", "canReclaimResources",
        "canAttackLandUnits", "canAttackFlyingUnits", "canAttackUnderwaterUnits")
    private val text = images.keys + setOf("name", "movementType", "autoTrigger", "convertTo")
    private val sections = mapOf("core" to (core.keys + images.keys + "name"),
        "movement" to (movement.keys + setOf("moveSpeed", "turnAcceleration", "moveSlidingDir")),
        "attack" to attack.keys, "projectile_1" to projectile.keys,
        "hiddenAction_overWater" to setOf("autoTrigger", "convertTo"), "hiddenAction_notOverWater" to setOf("autoTrigger", "convertTo"))
    private val schema = UnitContentSchema(sections, numericFields = sections.values.flatten().toSet() - flags - text,
        booleanFields = flags, textFields = text, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required finite scout bot field")
            val id = value("core", "name")
            if (id !in ScoutBotRules.ids) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name",
                reason = "Expected a qualified scout bot form")
            val water = id == ScoutBotRules.WATER
            val action = ScoutBotRules.actions(water).actions.single()
            val expected = mapOf("core" to core, "movement" to (movement + mapOf("moveSpeed" to if (water) "0.6" else "1",
                "turnAcceleration" to if (water) "0.2" else "0.5", "moveSlidingDir" to if (water) "45" else "35")),
                "attack" to attack, "projectile_1" to projectile, "hiddenAction_${action.name}" to mapOf(
                    "autoTrigger" to if (water) "if not self.overWater()" else "if self.overWater()",
                    "convertTo" to checkNotNull(action.targetTypeId)))
            for (section in loaded.values.keys) if (section !in expected)
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, reason = "Section is not used by this scout form")
            for ((section, fields) in expected) for ((field, wanted) in fields) {
                val actual = value(section, field)
                if (actual != wanted && (wanted.toDoubleOrNull() == null || actual.toDoubleOrNull() != wanted.toDoubleOrNull()))
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, section, field, reason = "Expected qualified scout bot value $wanted")
            }
            fun image(field: String): Path = resolveReference(path, value("core", field), "core", field).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = true)
                    require((png.width to png.height) == images.getValue(field))
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "core", field, reason = "Invalid scout bot PNG: ${failure.message}")
                }
            }
            UnitDefinition(id, "CustomUnitMetadata", 11.0, 13.0, 1, image("image"), 1.0,
                ScoutBotRules.movement(water), emptySet(), 22,
                combat = ScoutBotRules.combat(water, image("image_turret"), image("image_dead")),
                build = ScoutBotRules.build, builder = ScoutBotRules.builder, energy = ScoutBotRules.energy,
                customActions = ScoutBotRules.actions(water), groundHeight = ScoutBotRules.height,
                mech = ScoutBotRules.mech(water, image("image_leg"), image("image_foot")),
                maintenance = MaintenanceProfile.SCOUT_BOT, reclaimPrice = 0L)
        }
}
