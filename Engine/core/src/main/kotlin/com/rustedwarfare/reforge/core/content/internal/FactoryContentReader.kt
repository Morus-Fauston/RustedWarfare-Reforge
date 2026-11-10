package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Finite native land/air factories and the custom mech factory, not a general building loader. */
internal object FactoryContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "productionUnit", "image", "image_back", "image_dead", "fogOfWarSightRange",
        "productionUnits", "placementFootprint", "price", "buildSpeed", "exitX", "exitY", "exitDistance",
        "techLevel", "upgradeTo", "upgradePrice", "upgradeBuildSpeed")

    private val schema = UnitContentSchema.native(fields,
        setOf("rw-reforge.native-land-factory.v1", "rw-reforge.native-land-factory.v2", "rw-reforge.mech-factory.v1",
            "rw-reforge.mech-factory.v2", "rw-reforge.native-air-factory.v2", "rw-reforge.native-sea-factory.v1")).let {
        val numbers = setOf("exitX", "exitY", "exitDistance", "techLevel", "upgradePrice", "upgradeBuildSpeed")
        it.copy(numericFields = it.numericFields + numbers, textFields = it.textFields - numbers)
    }

    fun read(path: Path): UnitDefinition = readWithSources(path).definition

    fun readWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent = UnitSourceReader(schema, validatePath).load(path) { loaded ->
        definition(path, loaded.values.getValue("core"), validatePath)
    }

    private fun definition(path: Path, values: Map<String, String>, validatePath: (Path) -> Unit): UnitDefinition {
        if (values["format"] == "rw-reforge.native-sea-factory.v1") return seaDefinition(path, values, validatePath)
        if (values["format"] == "rw-reforge.native-air-factory.v2") return airDefinition(path, values, validatePath)
        fun required(key: String): String = requiredContentField(values[key], path, null, key,
            "Required factory field is absent or empty")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Only $expected is supported")
        }
        fun positive(key: String): Double = required(key).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = "Expected finite positive number")
        val mechV2 = required("format") == "rw-reforge.mech-factory.v2"
        val mech = mechV2 || required("format") == "rw-reforge.mech-factory.v1"
        val t2 = mechV2 && required("techLevel") == "2"
        val upgradeFields = setOf("upgradeTo", "upgradePrice", "upgradeBuildSpeed")
        if (mechV2) {
            only("techLevel", if (t2) "2" else "1")
            if (!t2) {
                only("upgradeTo", "mechFactoryT2"); only("upgradePrice", "4000"); only("upgradeBuildSpeed", "0.0004")
            } else values.keys.firstOrNull { it in upgradeFields }?.let {
                reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = it, reason = "The finite T2 factory has no further upgrade")
            }
        } else values.keys.firstOrNull { it in upgradeFields || it == "techLevel" }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = it, reason = "Factory upgrades require the finite mech v2 format")
        }
        val construction = mech || required("format") == "rw-reforge.native-land-factory.v2"
        val newFields = setOf("productionUnits", "placementFootprint", "price", "buildSpeed")
        if (construction) {
            if (!mech) {
                val products = required("productionUnits").split(',')
                if (products.isEmpty() || products.distinct().size != products.size ||
                    products.any { it !in setOf("builder", "c_tank", "hoverTank") })
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "productionUnits",
                        reason = "Expected a nonempty unique native land factory product list")
            } else {
                val products = required("productionUnits").split(',')
                val baseProducts = if (t2) listOf("builder", "mechGun", "mechHeavyMissile") else listOf("builder", "mechGun")
                val additions = (if ("mechBunker" in products) listOf("mechBunker") else emptyList()) +
                    (if (t2 && "mechEngineer" in products) listOf("mechEngineer") else emptyList())
                if (products != baseProducts + additions)
                    reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "productionUnits",
                        reason = "Expected the qualified tier products, optional bunker and T2 engineer")
            }
            only("placementFootprint", "-1,-1,1,3")
            only("price", when { t2 -> "5000"; mech -> "1000"; else -> "700" })
            only("buildSpeed", if (t2) "0.0005" else "0.001")
            only("maxHp", when { t2 -> "3100"; mech -> "1800"; else -> "1200" })
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
        val id = when { t2 -> "mechFactoryT2"; mech -> "mechFactory"; else -> "landFactory" }
        only("name", id)
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
                val width = if (mech) { if (key == "image_dead") 72 else 74 } else 50
                val height = if (mech) { if (key == "image_dead") 88 else 106 } else if (key == "image_dead") 69 else 74
                require(metadata.width == width && metadata.height == height) {
                    "Expected ${width}x${height} PNG dimensions for this finite factory profile"
                }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        return UnitDefinition(id,
            if (mech) "FiniteMechFactoryFixture" else "NativeLandFactoryFixture", radius, radius, 1,
            image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), sight,
            durability = DurabilityProfile(positive("maxHp"), image("image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1),
                image("image_back"), placementFootprint = if (construction) BuildingFootprint(-1, -1, 1, 3)
                    else BuildingFootprint(-1, -1, 1, 1)),
            production = ProductionProfile(if (construction) required("productionUnits").split(',') else listOf("c_tank"),
                if (mech) FactoryExitProfile.CUSTOM_MECH else FactoryExitProfile.NATIVE_LAND),
            build = if (construction) BuildProfile(positive("price").toLong(), positive("buildSpeed").toFloat()) else null,
            construction = if (construction) ConstructionProfile(positive("price").toLong(), positive("buildSpeed").toFloat()) else null,
            upgrades = if (mechV2 && !t2) UpgradeProfile(listOf(UpgradeActionProfile("upgrade_t2", "mechFactoryT2", 4000L, .0004f))) else null,
            directHitTargetKind = if (mech) DirectHitTargetKind.ORDINARY else DirectHitTargetKind.NATIVE_BUILDING,
            techLevel = if (t2) 2 else 1)
    }

    private fun airDefinition(path: Path, values: Map<String, String>, validatePath: (Path) -> Unit): UnitDefinition {
        fun required(key: String) = requiredContentField(values[key], path, "core", key, "Required native air factory field")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                reason = "Expected $expected for the finite air factory")
        }
        val tier = required("techLevel")
        if (tier !in setOf("1", "2")) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "techLevel",
            reason = "Expected native air factory tier 1 or 2")
        val t2 = tier == "2"
        val id = if (t2) "airFactoryT2" else "airFactory"
        val upgradeFields = setOf("upgradeTo", "upgradePrice", "upgradeBuildSpeed")
        val upgrade = !t2 && values.keys.any { it in upgradeFields }
        val allowed = setOf("format", "name", "techLevel", "maxHp", "radius", "footprint", "placementFootprint",
            "movementType", "productionUnits", "price", "buildSpeed", "fogOfWarSightRange", "image", "image_dead") +
            if (upgrade) upgradeFields else emptySet()
        values.keys.firstOrNull { it !in allowed }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, "core", it, reason = "Field is outside this finite air factory tier")
        }
        for ((key, value) in mapOf("name" to id, "maxHp" to "1000", "radius" to "30", "footprint" to "-1,-1,1,1",
            "placementFootprint" to "-1,-1,1,2", "movementType" to "NONE",
            "price" to if (t2) "2500" else "1000", "buildSpeed" to "0.001", "fogOfWarSightRange" to "15")) only(key, value)
        val products = required("productionUnits").split(',')
        val supported = if (t2) setOf("helicopter", "amphibiousJet", "dropship", "aaBeamGunship") else setOf("helicopter")
        if (products.isEmpty() || products.distinct().size != products.size || products.any { it !in supported })
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "productionUnits",
                reason = "Expected a nonempty unique menu of implemented native air products for tier $tier")
        if (upgrade) {
            only("upgradeTo", "airFactoryT2"); only("upgradePrice", "1500"); only("upgradeBuildSpeed", "0.0004")
        }
        fun image(key: String): Path = resolveReference(path, required(key), "graphics", key).also { image ->
            validatePath(image)
            try {
                val png = readPngMetadata(image, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                val height = if (t2 && key == "image") 63 else 61
                require(png.width == 40 && png.height == height) { "Expected 40x$height finite air factory PNG" }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
        }
        return UnitDefinition(id, "NativeAirFactoryFixture", 30.0, 30.0, 1, image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 15,
            durability = DurabilityProfile(1000.0, image("image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1), placementFootprint = BuildingFootprint(-1, -1, 1, 2)),
            production = ProductionProfile(products, FactoryExitProfile.NATIVE_AIR),
            build = BuildProfile(if (t2) 2500L else 1000L, .001f), construction = ConstructionProfile(if (t2) 2500L else 1000L, .001f),
            techLevel = if (t2) 2 else 1,
            upgrades = if (upgrade) UpgradeProfile(listOf(UpgradeActionProfile("upgrade_t2", "airFactoryT2", 1500L, .0004f))) else null,
            directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING)
    }

    /** Explicit T1/T2 native profile on the common syntax, source and image path. */
    private fun seaDefinition(path: Path, values: Map<String, String>, validatePath: (Path) -> Unit): UnitDefinition {
        fun required(key: String): String = requiredContentField(values[key], path, null, key, "Required sea factory field")
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key,
                reason = "Expected $expected for the finite sea factory")
        }
        val tier = required("techLevel")
        if (tier !in setOf("1", "2")) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "techLevel",
            reason = "Expected native sea factory tier 1 or 2")
        val t2 = tier == "2"
        val id = if (t2) "seaFactoryT2" else "seaFactory"
        val products = required("productionUnits").split(',')
        val available = if (t2) setOf("heavyBattleship", "attackSubmarine", "hovercraft") else setOf("attackSubmarine", "hovercraft")
        if (products.isEmpty() || products.distinct().size != products.size || products.any { it !in available })
            reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = "productionUnits", reason = "Expected unique supported native sea factory products")
        val upgradeFields = setOf("upgradeTo", "upgradePrice", "upgradeBuildSpeed")
        val allowed = setOf("format", "name", "techLevel", "maxHp", "radius", "footprint", "placementFootprint",
            "movementType", "productionUnits", "price", "buildSpeed", "fogOfWarSightRange", "image", "image_dead") +
            if (t2) emptySet() else upgradeFields
        values.keys.firstOrNull { it !in allowed }?.let {
            reject(ContentErrorCode.UNSUPPORTED_FIELD, path, field = it, reason = "Field is outside this finite sea factory tier")
        }
        for ((key, value) in mapOf("name" to id, "maxHp" to "1000", "radius" to "45", "footprint" to "-1,-1,1,2",
            "placementFootprint" to "-2,-1,2,4", "movementType" to "NONE",
            "price" to if (t2) "3000" else "1000", "buildSpeed" to "0.0007", "fogOfWarSightRange" to "15")) only(key, value)
        if (!t2) {
            only("upgradeTo", "seaFactoryT2"); only("upgradePrice", "2000"); only("upgradeBuildSpeed", "0.0004")
        }
        fun image(key: String): Path {
            val result = resolveReference(path, required(key), "graphics", key)
            validatePath(result)
            try {
                val png = readPngMetadata(result, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                require(png.width == 90 && png.height == 99) { "Expected 90x99 sea factory PNG" }
            } catch (failure: Exception) {
                reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
            }
            return result
        }
        return UnitDefinition(id, "NativeSeaFactoryFixture", 45.0, 45.0, 1, image("image"), 1.0,
            MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), 15,
            durability = DurabilityProfile(1000.0, image("image_dead")),
            building = BuildingProfile(BuildingFootprint(-1, -1, 1, 2), placementFootprint = BuildingFootprint(-2, -1, 2, 4)),
            production = ProductionProfile(products, FactoryExitProfile.NATIVE_SEA),
            build = BuildProfile(if (t2) 3000L else 1000L, .0007f), construction = ConstructionProfile(if (t2) 3000L else 1000L, .0007f),
            techLevel = if (t2) 2 else 1,
            upgrades = if (t2) null else UpgradeProfile(listOf(UpgradeActionProfile("upgrade_t2", "seaFactoryT2", 2000L, .0004f))),
            directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING)
    }
}
