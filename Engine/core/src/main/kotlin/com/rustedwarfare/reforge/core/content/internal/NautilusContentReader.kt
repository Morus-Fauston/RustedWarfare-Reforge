package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Mobile production slice; later forms and weapons cannot be enabled by unknown fields. */
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
    private val flags = setOf("energyNeedsToRechargeToFull", "transportUnitsCanUnloadUnits", "transportUnitsKeepBuiltUnits")
    private val texts = setOf("name", "productionUnits", "transportUnitsRequireMovementType", "transportUnitsRequireTag",
        "movementType", "image", "image_dead")
    private val schema = UnitContentSchema(mapOf("core" to (core.keys + setOf("image", "image_dead")),
        "movement" to movement.keys), numericFields = (core.keys + movement.keys) - flags - texts,
        booleanFields = flags, textFields = texts, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            fun value(section: String, field: String) = requiredContentField(loaded.values[section]?.get(field),
                path, section, field, "Required finite Nautilus field")
            for ((section, fields) in listOf("core" to core, "movement" to movement)) for ((field, expected) in fields) {
                val actual = value(section, field)
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
            UnitDefinition("nautilusSubmarine", "CustomUnitMetadata", 36.0, 36.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.WATER, .45, .015, .05, .8, .05, false, false, .6), emptySet(), 24,
                durability = DurabilityProfile(4000.0, image("image_dead")),
                production = ProductionProfile(listOf("hoverTank"), FactoryExitProfile.CUSTOM_NAUTILUS, 2f),
                build = BuildProfile(18000L, .0003f), transport = TransportProfile.NAUTILUS_SUBMERGED,
                energy = EnergyProfile(6f, .01f, .01f, true), selfRegenRate = .1f, techLevel = 2)
        }
}
