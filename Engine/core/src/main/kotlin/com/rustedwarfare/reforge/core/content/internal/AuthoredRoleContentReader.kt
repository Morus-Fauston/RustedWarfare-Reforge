package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path

internal object AuthoredRoleContentReader {
    private val core = setOf("name", "maxHp", "radius", "displayRadius", "price", "buildSpeed", "fogOfWarSightRange")
    private val graphics = setOf("image", "image_wreak")
    private val motion = setOf("movementType", "moveSpeed", "moveAccelerationSpeed", "moveDecelerationSpeed",
        "maxTurnSpeed", "turnAcceleration", "hoverHeight", "hoverDrift", "hoverStep", "hoverPeriod",
        "targetHeight", "heightChangeRate")
    private val nanoNumbers = setOf("nanoRange", "nanoRepairSpeed", "nanoBuildSpeed", "nanoTurnSpeed")
    private val nanoFlags = setOf("canRepairUnits", "canRepairBuildings", "canReclaim", "autoRepair")
    private val nanoLists = setOf("constructionTypes", "nanoConstructionTypes")
    private val nanoFields = nanoNumbers + nanoFlags + nanoLists
    private val roles = nanoFields + setOf("productionTypes", "productionSpeed")

    fun waterSchema(base: UnitContentSchema): UnitContentSchema = base.copy(
        fields = base.fields + ("roles" to roles), numericFields = base.numericFields + nanoNumbers + "productionSpeed",
        booleanFields = base.booleanFields + nanoFlags, textFields = base.textFields + nanoLists + "productionTypes")
    private val actions = setOf("convertTo", "buildSpeed", "highPriorityQueue", "ignoreFactorySpeed", "cannotMove")
    private val strings = nanoLists + setOf("name", "image", "image_wreak", "movementType", "productionTypes", "convertTo")
    private val flags = nanoFlags + setOf("highPriorityQueue", "ignoreFactorySpeed", "cannotMove")
    private val schema = UnitContentSchema(mapOf("core" to core, "graphics" to graphics, "movement" to motion,
        "roles" to roles, "action_1" to actions), (core + motion + roles + actions) - strings - flags, flags, strings,
        dynamicWeapons = false, dynamicActions = true, structuredAbilities = true, blinkAbility = true,
        transportAbility = true, timeFieldLocations = setOf("action_1" to "buildSpeed"))

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, preRead = validatePath).load(path) { loaded ->
            val values = loaded.values
            fun bad(section: String, key: String, reason: String): Nothing =
                reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = reason)
            fun raw(section: String, key: String): String = requiredContentField(values[section]?.get(key),
                path, section, key, "Required composite role field")
            fun finite(section: String, key: String, minimum: Double, maximum: Double, fallback: Double? = null): Double {
                val source = values[section]?.get(key) ?: return fallback ?: bad(section,key,"Expected a finite number")
                return source.toDoubleOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }
                    ?: bad(section, key, "Expected a bounded finite number")
            }
            fun bool(section: String, key: String): Boolean = when (values[section]?.get(key)) {
                null, "false" -> false
                "true" -> true
                else -> bad(section, key, "Expected boolean")
            }
            fun image(field: String): Path {
                val from = loaded.origins["graphics" to field]?.path ?: path
                val file = resolveReference(from, raw("graphics", field), "graphics", field)
                validatePath(file)
                readPngMetadata(file, allowIndexed = true, allowGrayAlpha = true)
                return file
            }
            val name = raw("core", "name")
            if (!identity.matches(name)) bad("core", "name", "Expected a stable content identity")
            if (!loaded.structured) bad("core", "name", "Expected v2 structured role content")
            val movement = MovementType.entries.singleOrNull { it.name == raw("movement", "movementType") }
                ?: bad("movement", "movementType", "Expected a supported movement domain")
            fun move(field: String, default: Double? = null) = finite("movement",field,0.0,360.0,default)
            val hover = if (movement == MovementType.HOVER) HoverProfile(move("hoverHeight"), move("hoverDrift"),
                move("hoverPeriod"), move("hoverStep")) else null
            val flight = if (movement == MovementType.AIR) FlightProfile(move("targetHeight"), 0.0,
                move("heightChangeRate"), 0.0) else null
            val stopped = movement == MovementType.NONE
            val sliding = movement in setOf(MovementType.HOVER,MovementType.AIR)
            val locomotion = MovementProfile(movement, move("moveSpeed",0.0), move("moveAccelerationSpeed",0.0),
                move("moveDecelerationSpeed",0.0), move("maxTurnSpeed",0.0), move("turnAcceleration",0.0),
                sliding, sliding, flight = flight, hover = hover)
            if (stopped && locomotion != MovementProfile(MovementType.NONE,0.0,0.0,0.0,0.0,0.0,false,false))
                bad("movement", "moveSpeed", "An immobile form requires zero locomotion")
            val nano = readRoles(loaded, ::bad, ::finite, ::bool)
            val transport = TransportContent.read(path, loaded)
            val blink = BlinkContent.read(path, loaded)
            val shield = ShieldContent.read(path, loaded, otherTypes = setOf("transport","blink"))
            val convert = values.keys.filter { it.startsWith("action_") }.map { section ->
                val action = section.removePrefix("action_")
                val destination = raw(section, "convertTo")
                if (!identity.matches(action) || !identity.matches(destination)) bad(section, "convertTo", "Expected stable action and form identities")
                val text = raw(section,"buildSpeed")
                val duration = text.removeSuffix("s").toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
                    ?: bad(section,"buildSpeed","Expected finite conversion duration")
                val rate = if (text.endsWith('s')) 1f / (duration * 60f) else duration
                if (rate !in .000001f..1f) bad(section,"buildSpeed","Expected bounded conversion duration")
                CustomActionDefinition(action, action, destination, rate, null, canPlayerCancel = true,
                    cannotMove = bool(section, "cannotMove"),
                    highPriority = bool(section,"highPriorityQueue"), ignoreFactorySpeed = bool(section,"ignoreFactorySpeed"))
            } + blink?.actions()?.actions.orEmpty()
            val price = raw("core","price").toLongOrNull()?.takeIf { it >= 0L } ?: bad("core","price","Expected integer credits")
            val build = BuildProfile(price,finite("core","buildSpeed",.000001,1.0).toFloat())
            val sight = raw("core","fogOfWarSightRange").toIntOrNull()?.takeIf { it in 15..30 }
                ?: bad("core","fogOfWarSightRange","Expected sight range 15–30")
            UnitDefinition(name,"AuthoredRoleUnit",finite("core","radius",1.0,19.0),finite("core","displayRadius",1.0,60.0),
                1,image("image"),1.0,locomotion,emptySet(),sight,build=build,
                durability=DurabilityProfile(finite("core","maxHp",1.0,1_000_000.0),image("image_wreak")),
                production = nano.production, builder = nano.builder,
                maintenance = nano.maintenance?.let { MaintenanceProfile.AUTHORED },
                authoredMaintenance = nano.maintenance, shield = shield, transport = transport?.let { TransportProfile.AUTHORED },
                transportSettings = transport, blink = blink,
                customActions = convert.takeIf { it.isNotEmpty() }?.let { CustomActionsProfile(it) })
        }

    data class NanoRoles(val builder: BuilderProfile?, val maintenance: AuthoredMaintenance?,
                         val production: ProductionProfile?)

    fun readWaterNano(path: Path, loaded: UnitSourceReader.EffectiveSections): NanoRoles? {
        val roles = loaded.values["roles"] ?: return null
        fun bad(section: String, key: String, reason: String): Nothing =
            reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, key, reason = reason)
        if (!loaded.structured) bad("roles", "constructionTypes", "Water nano roles require structured content")
        fun number(section: String, key: String, minimum: Double, maximum: Double, fallback: Double?): Double =
            roles[key]?.let { value ->
                value.toDoubleOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }
                    ?: bad(section, key, "Expected a bounded finite number")
            } ?: checkNotNull(fallback)
        fun flag(section: String, key: String): Boolean = when (roles[key]) {
            null, "false" -> false
            "true" -> true
            else -> bad(section, key, "Expected boolean")
        }
        return readRoles(loaded, ::bad, ::number, ::flag)
    }

    private fun readRoles(loaded: UnitSourceReader.EffectiveSections, bad: (String, String, String) -> Nothing,
                         finite: (String, String, Double, Double, Double?) -> Double,
                         bool: (String, String) -> Boolean): NanoRoles {
        val maintenance = AuthoredMaintenance(bool("roles", "canRepairUnits"), bool("roles", "canRepairBuildings"),
            bool("roles", "canReclaim"), bool("roles", "autoRepair"))
        if (maintenance.autoRepair && !maintenance.isValid())
            bad("roles", "autoRepair", "Automatic work requires a repair category")
        val types = ids(loaded, "constructionTypes", bad)
        val forced = ids(loaded, "nanoConstructionTypes", bad).toSet()
        if (!types.containsAll(forced))
            bad("roles", "nanoConstructionTypes", "Forced nano actions must also appear in constructionTypes")
        val builder = types.takeIf { it.isNotEmpty() }?.let {
            BuilderProfile(it, finite("roles", "nanoRange", 1.0, 500.0, 85.0).toFloat(),
                repairRate = finite("roles", "nanoRepairSpeed", .01, 10.0, .2).toFloat(),
                buildSpeedMultiplier = finite("roles", "nanoBuildSpeed", .01, 20.0, 1.0).toFloat(),
                nanoConstructionTypes = forced,
                independentNanoTurnSpeed = loaded.values["roles"]?.get("nanoTurnSpeed")?.let {
                    finite("roles", "nanoTurnSpeed", .01, 180.0, null).toFloat()
                })
        }
        if (builder == null && (maintenance.isValid() || nanoNumbers.any { it in loaded.values["roles"].orEmpty() }))
            bad("roles", "constructionTypes", "Maintenance requires the nano work role")
        val products = ids(loaded, "productionTypes", bad)
        if (products.isEmpty() && "productionSpeed" in loaded.values["roles"].orEmpty())
            bad("roles", "productionTypes", "Production speed requires a product list")
        val production = products.takeIf { it.isNotEmpty() }?.let {
            ProductionProfile(it, FactoryExitProfile.AUTHORED_MOBILE,
                finite("roles", "productionSpeed", .01, 20.0, 1.0).toFloat())
        }
        return NanoRoles(builder, maintenance.takeIf { it.isValid() }, production)
    }

    private fun ids(loaded: UnitSourceReader.EffectiveSections, key: String,
                    bad: (String, String, String) -> Nothing): List<String> = loaded.values["roles"]?.get(key)?.split(',')?.map(String::trim)?.also { entries ->
        if (entries.distinct().size != entries.size || entries.any { !identity.matches(it) })
            bad("roles", key, "Expected distinct stable content identities")
    }.orEmpty()

    private val identity = Regex("[a-z][a-z0-9_.-]*")
}
