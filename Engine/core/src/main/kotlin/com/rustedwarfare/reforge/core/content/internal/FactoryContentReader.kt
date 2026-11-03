package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Finite native land/air factories and the custom mech factory, not a general building loader. */
internal object FactoryContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "productionUnit", "image", "image_back", "image_dead", "fogOfWarSightRange",
        "productionUnits", "placementFootprint", "price", "buildSpeed", "exitX", "exitY", "exitDistance")

    private val schema = UnitContentSchema.native(fields,
        setOf("rw-reforge.native-land-factory.v1", "rw-reforge.native-land-factory.v2", "rw-reforge.mech-factory.v1",
            "rw-reforge.native-air-factory.v1")).let {
        it.copy(numericFields = it.numericFields + setOf("exitX", "exitY", "exitDistance"),
            textFields = it.textFields - setOf("exitX", "exitY", "exitDistance"))
    }

    fun read(path: Path): UnitDefinition = readWithSources(path).definition

    fun readWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent = UnitSourceReader(schema, validatePath).load(path) { loaded ->
        definition(path, loaded.values.getValue("core"), validatePath)
    }

    private fun definition(path: Path, values: Map<String, String>, validatePath: (Path) -> Unit): UnitDefinition {
        fun required(key: String): String = requiredContentField(values[key], path, null, key,
            "Required factory field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only $expected is supported")
        }
        fun positive(key: String): Double = required(key).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite positive number")
        val mech = required("format") == "rw-reforge.mech-factory.v1"
        val air = required("format") == "rw-reforge.native-air-factory.v1"
        val construction = mech || air || required("format") == "rw-reforge.native-land-factory.v2"
        val newFields = setOf("productionUnits", "placementFootprint", "price", "buildSpeed")
        if (construction) {
            only("productionUnits", when { air -> "helicopter"; mech -> "builder,mechGun"; else -> "builder,c_tank" })
            only("placementFootprint", if (air) "-1,-1,1,2" else "-1,-1,1,3")
            only("price", if (mech || air) "1000" else "700")
            only("buildSpeed", "0.001")
            only("maxHp", when { air -> "1000"; mech -> "1800"; else -> "1200" })
            only("radius", if (mech) "35" else "30")
            only("fogOfWarSightRange", "15")
            if ("productionUnit" in values) reject(ContentErrorCode.UNSUPPORTED_FIELD, path,
                field = "productionUnit", reason = "The v2 format declares productionUnits")
        } else {
            only("format", "rw-reforge.native-land-factory.v1")
            only("productionUnit", "c_tank")
            values.keys.firstOrNull { it in newFields }?.let { reject(ContentErrorCode.UNSUPPORTED_FIELD, path,
                field = it, reason = "Construction requires the v2 format") }
        }
        if (mech) {
            only("exitX", "0"); only("exitY", "-1"); only("exitDistance", "120")
        } else values.keys.firstOrNull { it in setOf("exitX", "exitY", "exitDistance") }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = it, reason = "Custom exits require the finite mech factory format")
        }
        if (air && "image_back" in values) reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = "image_back",
            reason = "The finite native air factory has no back image")
        only("name", when { air -> "airFactory"; mech -> "mechFactory"; else -> "landFactory" })
        only("movementType", "NONE")
        only("footprint", "-1,-1,1,1")
        val sight = required("fogOfWarSightRange").toIntOrNull()?.takeIf { it >= 0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = "fogOfWarSightRange", reason = "Expected nonnegative integer")
        val radius = positive("radius")
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            validatePath(result)
            if (construction) try {
                val metadata = readPngMetadata(result, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                val width = if (air) 40 else if (mech) { if (key == "image_dead") 72 else 74 } else 50
                val height = if (air) 61 else if (mech) { if (key == "image_dead") 88 else 106 } else if (key == "image_dead") 69 else 74
                require(metadata.width == width && metadata.height == height) {
                    "Expected ${width}x${height} PNG dimensions for this finite factory profile"
                }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        return UnitDefinition(when { air -> "airFactory"; mech -> "mechFactory"; else -> "landFactory" },
            when { air -> "NativeAirFactoryFixture"; mech -> "FiniteMechFactoryFixture"; else -> "NativeLandFactoryFixture" }, radius, radius, 1,
            image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), sight,
            durability = DurabilityProfile(positive("maxHp"), image("image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1),
                if (air) null else image("image_back"), placementFootprint = if (air) BuildingFootprint(-1, -1, 1, 2)
                    else if (construction) BuildingFootprint(-1, -1, 1, 3)
                    else BuildingFootprint(-1, -1, 1, 1)),
            production = ProductionProfile(if (air) listOf("helicopter") else if (mech) listOf("builder", "mechGun") else if (construction) listOf("builder", "c_tank") else listOf("c_tank"),
                when { air -> FactoryExitProfile.NATIVE_AIR; mech -> FactoryExitProfile.CUSTOM_MECH; else -> FactoryExitProfile.NATIVE_LAND }),
            build = if (construction) BuildProfile(if (mech || air) 1000L else 700L, .001f) else null,
            construction = if (construction) ConstructionProfile(if (mech || air) 1000L else 700L, .001f) else null,
            directHitTargetKind = if (mech) DirectHitTargetKind.ORDINARY else DirectHitTargetKind.NATIVE_BUILDING)
    }
}
