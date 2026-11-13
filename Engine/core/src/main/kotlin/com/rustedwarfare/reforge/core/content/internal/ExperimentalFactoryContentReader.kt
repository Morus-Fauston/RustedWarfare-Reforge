package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

internal object ExperimentalFactoryContentReader {
    private val expected = mapOf("format" to "rw-reforge.native-experimental-factory.v1", "name" to "experimentalLandFactory",
        "techLevel" to "2", "maxHp" to "3200", "radius" to "55", "footprint" to "-2,-2,2,2",
        "placementFootprint" to "-2,-2,2,4", "movementType" to "NONE", "productionUnits" to "nautilusSubmarineLand",
        "price" to "11000", "buildSpeed" to "0.00035", "fogOfWarSightRange" to "15")
    private val schema = UnitContentSchema.native(expected.keys + setOf("image", "image_back", "image_dead"),
        setOf("rw-reforge.native-experimental-factory.v1")).let {
            it.copy(numericFields = it.numericFields + "techLevel", textFields = it.textFields - "techLevel")
        }

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val fields = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(fields[key], path, "core", key, "Required experimental factory field")
            for ((field, value) in expected) {
                val actual = required(field)
                val matches = value.toDoubleOrNull()?.let { actual.toDoubleOrNull() == it } ?: (actual == value)
                if (!matches) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", field, reason = "Expected native value $value")
            }
            fun image(key: String): Path = resolveReference(path, required(key), "core", key).also {
                validatePath(it)
                try {
                    val png = readPngMetadata(it, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                    require(png.width == 100 && png.height == 115) { "Expected 100x115 experimental factory PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) { reject(ContentErrorCode.BAD_REFERENCE, path, "core", key, reason = "Invalid PNG: ${failure.message}") }
            }
            UnitDefinition("experimentalLandFactory", "NativeExperimentalFactoryFixture", 55.0, 55.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 15,
                durability = DurabilityProfile(3200.0, image("image_dead")),
                building = BuildingProfile(BuildingFootprint(-2, -2, 2, 2), image("image_back"),
                    placementFootprint = BuildingFootprint(-2, -2, 2, 4)),
                production = ProductionProfile(listOf("nautilusSubmarineLand"), FactoryExitProfile.NATIVE_EXPERIMENTAL),
                build = BuildProfile(11000L, .00035f), construction = ConstructionProfile(11000L, .00035f),
                directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING, techLevel = 2)
        }
}
