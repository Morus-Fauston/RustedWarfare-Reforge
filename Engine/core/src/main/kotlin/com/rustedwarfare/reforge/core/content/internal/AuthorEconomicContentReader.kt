package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

/** Resource-pool buildings with authored ordinary conversion actions. */
internal object AuthorEconomicContentReader {
    private val core = setOf("name", "maxHp", "radius", "displayRadius", "price", "buildSpeed",
        "footprint", "constructionFootprint", "placeOnlyOnResPool", "incomePeriod", "incomeCredits",
        "fogOfWarSightRange", "maxShield", "shieldRegen", "startShieldAtZero")
    private val graphics = setOf("image", "image_back", "image_wreak")
    private val action = setOf("convertTo", "price", "buildSpeed", "addCredits")
    private val numbers = setOf("maxHp", "radius", "displayRadius", "price", "buildSpeed", "incomePeriod",
        "incomeCredits", "addCredits", "fogOfWarSightRange", "maxShield", "shieldRegen")
    private val booleans = setOf("placeOnlyOnResPool", "startShieldAtZero")
    private val schema = UnitContentSchema(mapOf("core" to core, "graphics" to graphics,
        "movement" to setOf("movementType"), "action_1" to action), numbers, booleans,
        (core + graphics + action + "movementType") - numbers - booleans,
        timeFieldLocations = setOf("action_1" to "buildSpeed"), structuredAbilities = true,
        dynamicActions = true, dynamicWeapons = false)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, preRead = validatePath).load(path, retainedFields = action) { loaded ->
            val sections = loaded.values
            fun bad(section: String, key: String, reason: String): Nothing =
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = reason)
            fun read(section: String, key: String): String = requiredContentField(
                sections[section]?.get(key), path, section, key, "Missing economic form field")
            fun number(key: String, lower: Double, upper: Double): Double =
                read("core", key).toDoubleOrNull()?.takeIf { it.isFinite() && it in lower..upper }
                    ?: bad("core", key, "Expected a finite value in range")
            fun credits(section: String, key: String, fallback: Long? = null): Long {
                val raw = sections[section]?.get(key) ?: return fallback ?: bad(section, key, "Expected credits")
                return raw.toLongOrNull()?.takeIf { it in 0..999_999_999L }
                    ?: bad(section, key, "Expected bounded nonnegative integer credits")
            }
            fun id(section: String, key: String) = read(section, key).also { value ->
                if (!value.matches(Regex("[a-z][a-z0-9_.-]*"))) bad(section, key, "Expected a stable content identity")
            }
            fun image(key: String): Path {
                val origin = loaded.origins["graphics" to key]?.path ?: path
                val value = resolveReference(origin, read("graphics", key), "graphics", key)
                validatePath(value)
                try { readPngMetadata(value, allowIndexed = true, allowGrayAlpha = key == "image_wreak") }
                catch (failure: IllegalArgumentException) { bad("graphics", key, "Invalid PNG: ${failure.message}") }
                return value
            }

            if (read("movement", "movementType") != "NONE") bad("movement", "movementType", "Expected a stationary resource building")
            for (field in listOf("footprint", "constructionFootprint")) {
                if (read("core", field).split(',').map(String::trim) != listOf("0", "0", "0", "0"))
                    bad("core", field, "Expected a single resource-pool tile")
            }
            if (read("core", "placeOnlyOnResPool") != "true") bad("core", "placeOnlyOnResPool", "Resource pool is required")
            val actions = sections.keys.filter { it.startsWith("action_") }.map { section ->
                val key = section.removePrefix("action_")
                if (!key.matches(Regex("[a-z][a-z0-9_.-]*"))) bad(section, "name", "Expected a stable action identity")
                val value = read(section, "buildSpeed")
                val seconds = value.endsWith('s')
                val raw = value.removeSuffix("s").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
                    ?: bad(section, "buildSpeed", "Expected a finite positive work rate")
                val rate = if (seconds) 1f / (raw * 60f) else raw
                if (rate !in .000001f..1f) bad(section, "buildSpeed", "Expected a bounded finite work duration")
                UpgradeActionProfile(key, id(section, "convertTo"), credits(section, "price"), rate,
                    credits(section, "addCredits", 0L))
            }
            if (actions.isEmpty()) bad("actions", "actions", "Expected economic conversion actions")
            val body = BuildingFootprint(0, 0, 0, 0)
            val radius = number("radius", 1.0, 30.0)
            val sight = sections.getValue("core")["fogOfWarSightRange"]?.let {
                it.toIntOrNull()?.takeIf { value -> value in 0..30 }
                    ?: bad("core", "fogOfWarSightRange", "Expected a sight range from zero to 30")
            } ?: 15
            UnitDefinition(id("core", "name"), "AuthoredEconomicBuilding", radius, number("displayRadius", 1.0, 60.0),
                1, image("image"), 1.0, MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false),
                emptySet(), sight, durability = DurabilityProfile(number("maxHp", 1.0, 1_000_000.0), image("image_wreak")),
                building = BuildingProfile(body, sections["graphics"]?.get("image_back")?.let { image("image_back") },
                    FootprintAnchor(10f, 10f), requiresResourcePool = true),
                construction = ConstructionProfile(credits("core", "price"), number("buildSpeed", .000001, 1.0).toFloat()),
                upgrades = UpgradeProfile(actions), income = IncomeProfile(
                    number("incomePeriod", 1.0, IncomeProfile.MAX_PERIOD.toDouble()).toFloat(),
                    number("incomeCredits", 0.0, 1_000_000.0).toFloat()),
                shield = ShieldContent.read(path, loaded))
        }
}
