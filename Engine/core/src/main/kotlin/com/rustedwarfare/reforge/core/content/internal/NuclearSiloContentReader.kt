package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Effective custom replacement through the common syntax and source reader. */
internal object NuclearSiloContentReader {
    private val fields = setOf("format", "name", "maxHp", "radius", "footprint", "constructionFootprint",
        "movementType", "price", "buildSpeed", "image", "image_back", "image_dead", "image_arm1", "image_arm2", "ordnanceRole")
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
            val id = required("name")
            val original = NuclearSiloKind.entries.firstOrNull { it.unitId == id }
            val role = values["ordnanceRole"]
            val kind = if (role == null) original else NuclearSiloKind.entries.firstOrNull { it.name.lowercase() == role }
            if (role != null && (!loaded.structured || kind == null || original != null && original != kind))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "ordnanceRole", role,
                    reason = "Expected a structured launcher or interceptor role consistent with the reserved ID")
            if (kind == null || original == null && !id.matches(Regex("[a-z][a-z0-9_.-]*")))
                reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", id,
                    reason = "Expected a qualified native ID or a stable authored ID with a declared ordnance role")
            only("movementType", "NONE")
            only("footprint", "-1,-1,1,1")
            val placement = kind.placementFootprint
            only("constructionFootprint", "${placement.left},${placement.top},${placement.right},${placement.bottom}")
            number("maxHp", kind.maxHp)
            number("radius", 35.0)
            number("price", kind.constructionPrice.toDouble())
            number("buildSpeed", .001)
            fun image(key: String) = resolveReference(path, required(key), "graphics", key).also(validatePath)
            UnitDefinition(id, "CustomUnitMetadata", 35.0, 35.0, 1, image("image"), 1.0,
                MovementProfile(MovementType.NONE, 0.0, .01, .01, 0.0, .1, false, false), emptySet(),
                durability = DurabilityProfile(kind.maxHp, image("image_dead")),
                building = BuildingProfile(BuildingFootprint(-1, -1, 1, 1), image("image_back"),
                    placementFootprint = placement),
                build = BuildProfile(kind.constructionPrice, .001f),
                construction = ConstructionProfile(kind.constructionPrice, .001f),
                nuclearSilo = NuclearSiloProfile(image("image_arm1"), image("image_arm2"), kind))
        }
}
