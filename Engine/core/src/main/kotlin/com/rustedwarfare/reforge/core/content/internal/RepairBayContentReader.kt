package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Finite native repair bay on the common syntax and source-closure reader. */
internal object RepairBayContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "movementType",
        "price", "buildSpeed", "image", "image_dead")
    private val schema = UnitContentSchema.native(fields, setOf("rw-reforge.native-repairbay.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(values[key], path, "core", key,
                "Required native repair bay field")
            fun only(key: String, expected: String) {
                if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected $expected for the native repair bay")
            }
            fun number(key: String, expected: Double) {
                val value = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected finite number")
                if (value != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected native value $expected")
            }
            only("format", "rw-reforge.native-repairbay.v1")
            only("name", "repairbay")
            only("movementType", "NONE")
            only("footprint", "-1,-1,1,1")
            number("maxHp", 1000.0)
            number("radius", 30.0)
            number("price", 1500.0)
            number("buildSpeed", .001)
            fun image(key: String): Path = resolveReference(path, required(key), "graphics", key).also { image ->
                validatePath(image)
                try {
                    val png = readPngMetadata(image, allowIndexed = true, allowGrayAlpha = key == "image_dead")
                    val width = if (key == "image") 62 else 60
                    require(png.width == width && png.height == 76) { "Expected ${width}x76 native repair bay PNG" }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, "graphics", key, reason = "Invalid PNG: ${failure.message}")
                }
            }
            UnitDefinition("repairbay", "NativeRepairBayFixture", 30.0, 30.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                durability = DurabilityProfile(1000.0, image("image_dead")),
                building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1)),
                build = BuildProfile(1500L, .001f), construction = ConstructionProfile(1500L, .001f),
                directHitTargetKind = DirectHitTargetKind.NATIVE_BUILDING,
                maintenance = MaintenanceProfile.NATIVE_REPAIR_BAY)
        }
}
