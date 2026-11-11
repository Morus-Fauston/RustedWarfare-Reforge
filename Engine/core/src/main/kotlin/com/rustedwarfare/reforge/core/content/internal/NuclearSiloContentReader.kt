package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Effective custom replacement through the common syntax and source reader. */
internal object NuclearSiloContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "constructionFootprint",
        "movementType", "price", "buildSpeed", "image", "image_back", "image_dead", "image_arm1", "image_arm2")
    private val schema = UnitContentSchema.native(fields, setOf("rw-reforge.nuclear-silo.v1"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path) { loaded ->
            val values = loaded.values.getValue("core")
            fun required(key: String) = requiredContentField(values[key], path, "core", key, "Required nuclear silo field")
            fun only(key: String, expected: String) {
                if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected effective nuclear silo value $expected")
            }
            fun number(key: String, expected: Double) {
                val actual = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, "core", key, reason = "Expected finite number")
                if (actual != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", key,
                    reason = "Expected effective nuclear silo value $expected")
            }
            only("format", "rw-reforge.nuclear-silo.v1")
            only("name", "nukeLauncherC")
            only("movementType", "NONE")
            only("footprint", "-1,-1,1,1")
            only("constructionFootprint", "-2,-1,2,2")
            number("maxHp", 1500.0)
            number("radius", 35.0)
            number("price", 45000.0)
            number("buildSpeed", .001)
            fun image(key: String) = resolveReference(path, required(key), "graphics", key).also(validatePath)
            UnitDefinition("nukeLauncherC", "CustomUnitMetadata", 35.0, 35.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.NONE, 0.0, .01, .01, 0.0, .1, false, false), emptySet(),
                durability = DurabilityProfile(1500.0, image("image_dead")),
                building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1), image("image_back"),
                    placementFootprint = BuildingFootprint(-2, -1, 2, 2)),
                build = BuildProfile(45000L, .001f), construction = ConstructionProfile(45000L, .001f),
                nuclearSilo = NuclearSiloProfile(image("image_arm1"), image("image_arm2")))
        }
}
