package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.nio.file.InvalidPathException

/** Author constructor and plain building bodies share syntax, source closure and finite field handling. */
internal object AuthorConstructionContentReader {
    private val constructorFields = setOf("format", "name", "maxHp", "radius", "displayRadius", "movementType",
        "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed", "maxTurnSpeed", "turnAcceleration",
        "price", "buildSpeed", "image", "image_dead", "fogOfWarSightRange", "constructionTypes")
    private val constructorNumbers = constructorFields - setOf("format", "name", "movementType", "image", "image_dead", "constructionTypes")
    private val constructorSchema = UnitContentSchema(mapOf("core" to constructorFields), constructorNumbers,
        setOf("dont_load"), constructorFields - constructorNumbers, nativeFormats = setOf("rw-reforge.constructor.v1"))
    private val buildingFields = setOf("format", "name", "maxHp", "radius", "footprint", "placementFootprint",
        "movementType", "foundationMovementType", "price", "buildSpeed", "image", "image_back", "image_dead", "fogOfWarSightRange")
    private val buildingSchema = UnitContentSchema.native(buildingFields, setOf("rw-reforge.factory.v1"))

    fun readConstructorWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        UnitSourceReader(constructorSchema, validatePath).load(path) { loaded ->
            val fields = Fields(path, loaded, validatePath)
            fields.only("format", "rw-reforge.constructor.v1")
            fields.only("movementType", "LAND")
            val targets = fields.ids("constructionTypes")
            val speed = fields.nonnegative("moveSpeed")
            if (speed > .8) fields.bad("moveSpeed", "Existing constructor movement supports at most 0.8")
            val sight = fields.sight()
            if (sight < 15) fields.bad("fogOfWarSightRange", "Existing construction requires sight range at least 15")
            UnitDefinition(fields.id(), "AuthoredConstructor", fields.positive("radius"), fields.positive("displayRadius"),
                1, fields.image("image"), 1.0,
                MovementProfile(MovementType.LAND, speed, fields.nonnegative("moveAccelerationSpeed"),
                    fields.nonnegative("moveDecelerationSpeed"), fields.nonnegative("maxTurnSpeed"), fields.nonnegative("turnAcceleration"), false, false),
                emptySet(), sight, durability = DurabilityProfile(fields.positive("maxHp"), fields.image("image_dead")),
                build = BuildProfile(fields.price(), fields.rate()), builder = BuilderProfile(targets))
        }

    /** The planned factory format currently admits its ordinary construction body, without production or upgrade abilities. */
    fun readBuildingWithSources(path: Path, validatePath: (Path) -> Unit = {}): LoadedUnitContent =
        UnitSourceReader(buildingSchema, validatePath).load(path) { loaded ->
            val fields = Fields(path, loaded, validatePath)
            fields.only("format", "rw-reforge.factory.v1")
            fields.only("movementType", "NONE")
            val footprint = fields.footprint("footprint")
            val placement = fields.footprint("placementFootprint")
            if (footprint != BuildingFootprint(-1, -1, 1, 1) || placement != BuildingFootprint(-1, -1, 1, 3))
                fields.bad("footprint", "Expected the existing ordinary construction footprint and placement envelope")
            val foundation = when (fields.required("foundationMovementType")) {
                "LAND" -> MovementType.LAND
                "WATER" -> MovementType.WATER
                else -> fields.bad("foundationMovementType", "Expected LAND or WATER building foundation")
            }
            val radius = fields.positive("radius")
            if (radius > 30.0) fields.bad("radius", "Existing ordinary construction supports radius at most 30")
            UnitDefinition(fields.id(), "AuthoredBuilding", radius, radius, 1, fields.image("image"), 1.0,
                MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(), fields.sight(),
                durability = DurabilityProfile(fields.positive("maxHp"), fields.image("image_dead")),
                building = BuildingProfile(footprint, if ("image_back" in loaded.values.getValue("core")) fields.image("image_back") else null,
                    placementFootprint = placement, foundationMovementType = foundation),
                construction = ConstructionProfile(fields.price(), fields.rate()))
        }

    private class Fields(val path: Path, val loaded: UnitSourceReader.EffectiveSections, val validatePath: (Path) -> Unit) {
        private val values = loaded.values.getValue("core")
        fun required(key: String): String = requiredContentField(values[key], path, null, key, "Required author construction field is absent or empty")
        fun bad(key: String, reason: String): Nothing = reject(ContentErrorCode.MALFORMED_SYNTAX, path, field = key, reason = reason)
        fun only(key: String, expected: String) {
            if (required(key) != expected) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, field = key, reason = "Expected $expected")
        }
        fun id(): String = required("name").also { if (!it.matches(Regex("[a-z][a-z0-9_.-]*"))) bad("name", "Expected a stable lowercase content ID") }
        fun ids(key: String): List<String> = required(key).split(',').map(String::trim).also { ids ->
            if (ids.isEmpty() || ids.distinct().size != ids.size || ids.any { !it.matches(Regex("[a-z][a-z0-9_.-]*")) })
                bad(key, "Expected a nonempty unique list of stable lowercase content IDs")
        }
        private fun number(key: String): Double = required(key).toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: bad(key, "Expected a finite number")
        fun positive(key: String): Double = number(key).also { if (it <= 0.0) bad(key, "Expected a positive number") }
        fun nonnegative(key: String): Double = number(key).also { if (it < 0.0) bad(key, "Expected a nonnegative number") }
        fun price(): Long = required("price").toLongOrNull()?.takeIf { it >= 0L } ?: bad("price", "Expected nonnegative integer price")
        fun rate(): Float = required("buildSpeed").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
            ?: bad("buildSpeed", "Expected positive finite binary32 build speed")
        fun sight(): Int = required("fogOfWarSightRange").toIntOrNull()?.takeIf { it >= 0 }
            ?: bad("fogOfWarSightRange", "Expected nonnegative integer sight range")
        fun footprint(key: String): BuildingFootprint {
            val parts = required(key).split(',').map { it.trim().toIntOrNull() ?: bad(key, "Expected four integer footprint edges") }
            if (parts.size != 4 || parts[0] > parts[2] || parts[1] > parts[3]) bad(key, "Expected ordered left,top,right,bottom footprint")
            return BuildingFootprint(parts[0], parts[1], parts[2], parts[3])
        }
        fun image(key: String): Path {
            val origin = loaded.origins["core" to key]?.path ?: path
            val reference = required(key)
            val candidate = try { origin.parent.resolve(reference).toAbsolutePath().normalize() }
            catch (failure: InvalidPathException) {
                reject(ContentErrorCode.BAD_REFERENCE, origin, field = key, reason = "Invalid image path: ${failure.reason}")
            }
            validatePath(candidate)
            val resolved = resolveReference(origin, reference, "graphics", key)
            validatePath(resolved)
            try { readPngMetadata(resolved, allowIndexed = true, allowGrayAlpha = key == "image_dead") }
            catch (failure: Exception) { reject(ContentErrorCode.BAD_REFERENCE, origin, field = key, reason = "Invalid PNG: ${failure.message}") }
            return resolved
        }
    }
}
