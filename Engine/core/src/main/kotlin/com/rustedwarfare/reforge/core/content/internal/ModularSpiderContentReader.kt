package com.rustedwarfare.reforge.core.content.internal

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Reads the effective opening chassis and qualified modules through the shared source coordinator. */
internal object ModularSpiderContentReader {
    private val empty = ModularSpiderRules.EMPTY
    private val interceptorRanges = setOf("interceptProjectiles_andTargetingGroundUnderDistance",
        "interceptProjectiles_andUnderDistance", "interceptProjectiles_andOverHeight")
    private val legOffsets = listOf(
        listOf(-50, 20, -25, 10), listOf(50, 20, 15, 10), listOf(-35, 60, -15, 25), listOf(35, 60, 15, 25),
        listOf(-50, -30, -15, -25), listOf(50, -30, 15, -25), listOf(-35, -60, -15, -35), listOf(35, -60, 15, -35),
    )
    private val legs = legOffsets.mapIndexed { i, (x, y, attachX, attachY) ->
        "leg_${i + 1}" to mapOf("x" to "$x", "y" to "$y", "attach_x" to "$attachX", "attach_y" to "$attachY",
            "moveSpeed" to "2.6", "heightSpeed" to "0.6", "moveWarmUp" to "4", "resetAngle" to "20",
            "holdDisMin" to "12", "holdDisMax" to "25", "holdDisMin_maxMovingLegs" to "5",
            "favourOppositeSideNeighbours" to "true")
    }.toMap()
    private val slots = ModularSpiderRules.slots.associate { slot ->
        "attachment_slot${slot.number}" to mapOf("x" to slot.x.toString(), "y" to slot.y.toString(),
            "idleDir" to slot.idleHeading.toString(), "idleDirReversing" to slot.idleHeading.toString(),
            "onCreateSpawnUnitOf" to slot.initialTypeId, "lockRotation" to "true",
            "redirectDamageToParent" to "true", "redirectDamageToParent_shieldOnly" to "true")
    }
    private val core = mapOf("class" to "CustomUnitMetadata")
    private val chassis = mapOf(
        "core" to core + mapOf("name" to ModularSpiderRules.ID, "price" to "90000", "buildSpeed" to "0.0002", "maxHp" to "10000",
            "radius" to "49", "fogOfWarSightRange" to "19", "transportSlotsNeeded" to "14", "maxShield" to "5000",
            "shieldRegen" to "0.3", "selfRegenRate" to "0.1", "generation_credits" to "30", "generation_delay" to "40",
            "canRepairBuildings" to "true", "canRepairUnits" to "true", "autoRepair" to "true",
            "nanoRange" to "220", "nanoRepairSpeed" to "0.4", "nanoBuildSpeed" to "8",
            "nukeOnDeath" to "true", "nukeOnDeathRange" to "200", "nukeOnDeathDamage" to "4400",
            "nukeOnDeathDisableWhenNoNuke" to "true",
            "isPickableStartingUnit" to "true", "startFallingWhenStartingUnit" to "true"),
        "graphics" to mapOf("total_frames" to "1"),
        "canBuild_chassis" to mapOf("constructionTypes" to "repairbay", "forceNano" to "true"),
        "turret_nanoTurret" to mapOf("warmup" to "30", "isMainNanoTurret" to "true"),
        "movement" to mapOf("movementType" to "OVER_CLIFF_WATER", "moveSpeed" to "0.6",
            "moveAccelerationSpeed" to "0.015", "moveDecelerationSpeed" to "0.015", "maxTurnSpeed" to "0.4",
            "turnAcceleration" to "0.1", "reverseSpeedPercentage" to "1"),
        "action_blinkQueued" to mapOf("tags" to "blink", "buildSpeed" to ModularSpiderUtilityForms.BLINK_DURATION.toString(),
            "allowMultipleInQueue" to "false", "canPlayerCancel" to "false", "isActive" to "false",
            "highPriorityQueue" to "true", "whenBuilding_cannotMove" to "true"),
        "projectile_blink" to mapOf("teleportSource" to "true", "instant" to "true",
            "directDamage" to "0", "targetGround" to "true", "shouldRevealFog" to "true"),
    ) + slots + legs
    private val emptySlot = mapOf(
        "core" to core + mapOf("name" to empty, "price" to "1000", "maxHp" to "3000", "radius" to "20", "selfRegenRate" to "3000",
            "isUnselectable" to "true", "canNotBeDirectlyAttacked" to "true"),
        "graphics" to mapOf("total_frames" to "1"),
    ) + ModularSpiderRules.installs.associate { install ->
        "action_${install.action}" to mapOf("convertTo" to install.targetTypeId, "price" to install.cost.toString(),
            "buildSpeed" to "0", "setBuilt" to "0")
    }
    private val profiles = listOf(chassis, emptySlot) + ModularSpiderWeaponForms.byId.values.map { it.sections } +
        ModularSpiderUtilityForms.byId.values.map { it.sections }
    private val images = mapOf("graphics" to setOf("image", "image_shadow", "image_wreak", "image_turret", "image_slotUi")) +
        legs.keys.associateWith { setOf("image_leg", "image_foot") } +
        ModularSpiderWeaponForms.byId.values.flatMap { it.additionalTurretDimensions.keys }
            .associate { "turret_$it" to setOf("image") }
    private val fields = profiles.flatMap { it.entries }.groupBy({ it.key }, { it.value.keys })
        .mapValues { (section, declared) -> declared.flatten().toSet() + images[section].orEmpty() }
    private val references = setOf("onCreateSpawnUnitOf", "convertTo", "projectile", "attachedTo", "linkDelayWithTurret")
    private val text = setOf("name", "class", "movementType", "shoot_sound", "autoTrigger", "takeResources",
        "addResources", "interceptProjectiles_withTags", "resourceUsage", "tags", "sendMessageTo",
        "sendMessageWithTags", "sendMessageWithData", "fireTurretXAtGround_onlyOverPassableTileOf",
        "fireTurretXAtGround", "fireTurretXAtGround_withProjectile", "constructionTypes") +
        references + images.values.flatten()
    private val flags = profiles.flatMap { it.values }.flatMap { it.entries }
        .filter { it.key !in text && (it.value == "true" || it.value == "false") }.map { it.key }.toSet()
    private val schema = UnitContentSchema(fields, fields.values.flatten().toSet() - text - flags,
        flags, text, dynamicWeapons = false, timeFieldLocations = setOf("attack" to "shootDelay"), structuredAbilities = true)

    fun readWithSources(path: Path, validatePath: (Path) -> Unit): LoadedUnitContent =
        UnitSourceReader(schema, validatePath).load(path, setOf("onCreateSpawnUnitOf", "convertTo", "constructionTypes")) { content ->
            fun value(section: String, field: String) = requiredContentField(content.values[section]?.get(field),
                path, section, field, "Required modular spider field")
            val name = value("core", "name")
            val form = ModularSpiderWeaponForms.byId[name]
            val authoredInterceptor = content.structured && ModularSpiderRules.authoredModuleId(name) &&
                content.values["turret_silo"]?.get("interceptProjectiles_withTags") == "nuke"
            val utility = ModularSpiderUtilityForms.byId[if (authoredInterceptor) ModularSpiderRules.ANTI_NUKE else name]
            val expected = when (name) {
                ModularSpiderRules.ID -> chassis
                empty -> emptySlot
                else -> form?.sections ?: utility?.sections ?: reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "core", "name", name,
                    "Unknown modular spider form")
            }
            val graphicFields = when {
                name == ModularSpiderRules.ID -> images.getValue("graphics")
                form != null -> setOf("image", "image_turret")
                else -> setOf("image")
            }
            val shield = ShieldContent.read(path, content, required = name == ModularSpiderRules.ID)
            val expectedShield = if (name == ModularSpiderRules.ID) ShieldProfile(5000f, .3f) else null
            ShieldContent.validateQualified(path, content, shield, expectedShield)
            val hasShieldAbility = content.values.keys.any { it.startsWith("ability_") }
            for ((section, values) in content.values) {
                if (section.startsWith("ability_")) continue
                val permitted = expected[section]?.keys.orEmpty() +
                    if (section == "graphics") graphicFields else images[section].orEmpty().takeIf { section in expected }.orEmpty()
                for (field in values.keys) if (field !in permitted)
                    reject(ContentErrorCode.UNSUPPORTED_FIELD, path, section, field, reason = "Field is unavailable for $name")
            }
            for ((section, fields) in expected) for ((field, required) in fields) {
                if (hasShieldAbility && section == "core" && field in setOf("maxShield", "shieldRegen")) continue
                val actual = value(section, field)
                val matches = when {
                    authoredInterceptor && section == "core" && field == "name" -> true
                    authoredInterceptor && section == "turret_silo" && field in interceptorRanges -> true
                    content.structured && name == empty && section == "action_buildAntinuke" && field == "convertTo" ->
                        actual == required || ModularSpiderRules.authoredModuleId(actual)
                    field == "constructionTypes" -> actual.split(',').map(String::trim).let { types ->
                        types.distinct().size == types.size && ModularSpiderRules.constructionTypes.containsAll(types)
                    }
                    (section to field) in schema.timeFieldLocations && actual.endsWith('s') ->
                        actual.dropLast(1).toDoubleOrNull()?.times(60.0) == required.toDouble()
                    field in schema.numericFields -> actual.toDoubleOrNull() == required.toDouble()
                    else -> actual == required
                }
                if (!matches) reject(if (field in references || field in setOf("fireTurretXAtGround",
                    "fireTurretXAtGround_withProjectile")) ContentErrorCode.BAD_REFERENCE
                    else ContentErrorCode.UNSUPPORTED_FEATURE, path, section, field, actual, "Expected qualified value $required")
            }
            fun image(section: String, field: String, width: Int, height: Int): Path {
                val source = content.origins[section to field]?.path ?: path
                val png = resolveReference(source, value(section, field), section, field)
                validatePath(png)
                try {
                    val dimensions = readPngMetadata(png, allowIndexed = true, allowGrayAlpha = true)
                    require(dimensions.width == width && dimensions.height == height) { "Expected ${width}x${height} PNG" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    reject(ContentErrorCode.BAD_REFERENCE, path, section, field, reason = "Invalid modular spider PNG: ${failure.message}")
                }
                return png
            }
            when {
                name == ModularSpiderRules.ID -> {
                    val legs = legOffsets.mapIndexed { index, (x, y, attachX, attachY) ->
                        val section = "leg_${index + 1}"
                        MechLegDefinition(x.toDouble(), y.toDouble(), attachX.toDouble(), attachY.toDouble(),
                            2.6, 4.0, 3.0, .6, 12.0, 25.0, 1.0, image(section, "image_leg", 10, 84),
                            image(section, "image_foot", 17, 22), 20.0, favourOppositeSideNeighbours = true,
                            maximumMovingLegs = 5)
                    }
                    val mech = MechDefinition(MechBodyDefinition(.4, .1, 0.0, 180.0, 0.0, 0.0, 0.0, 0),
                        legs, MechKind.EIGHT_LEGGED)
                    if (!mech.isValid()) reject(ContentErrorCode.UNSUPPORTED_FEATURE, path, "leg_1",
                        reason = "Expected the qualified eight-legged profile with shared leg and foot images")
                    val nano = image("graphics", "image_turret", 11, 19)
                    val inert = ProjectileProfile(0.0, 1.0, 1.0)
                    val mount = NamedWeaponDefinition("nanoTurret", "inert", inert, TurretMount(0.0, 18.0),
                        85.0, 8.0, 4.0, 1.0, nano, 1.0, canAttackLandUnits = false, canShoot = false, warmup = 30.0)
                    UnitDefinition(name, "CustomUnitMetadata", 49.0, 49.0, 1, image("graphics", "image", 81, 113),
                        1.0, ModularSpiderRules.movement, emptySet(), fogOfWarSightRange = 19, mech = mech,
                        combat = CombatProfile(10000.0, 290.0, 85.0, 8.0, 4.0, 1.0, inert, nano, null, 1.0,
                            namedWeapons = WeaponCatalog(listOf(mount))),
                        durability = DurabilityProfile(10000.0, image("graphics", "image_wreak", 70, 90)),
                        shield = shield, selfRegenRate = .1f, income = IncomeProfile(40f, 30f),
                        build = ModularSpiderRules.build,
                        builder = ModularSpiderRules.builder(value("canBuild_chassis", "constructionTypes").split(',').map(String::trim)),
                        maintenance = MaintenanceProfile.MODULAR_SPIDER, deathExplosion = ModularSpiderRules.deathExplosion,
                        transportSlots = 14, shadowImagePath = image("graphics", "image_shadow", 81, 113),
                        attachmentLayout = AttachmentLayout(ModularSpiderRules.slots, image("graphics", "image_slotUi", 82, 114)))
                }
                name == empty -> UnitDefinition(name, "CustomUnitMetadata", 20.0, 20.0, 1, image("graphics", "image", 1, 1),
                    1.0, ModularSpiderRules.slotMovement, emptySet(), fogOfWarSightRange = 5,
                    durability = DurabilityProfile(3000.0, null), selfRegenRate = 3000f,
                    attachmentModule = AttachmentModuleProfile(empty = true, installs = ModularSpiderRules.installs.map {
                        it.copy(targetTypeId = value("action_${it.action}", "convertTo"))
                    }))
                utility != null -> UnitDefinition(name, "CustomUnitMetadata", 15.0, 12.0, 1,
                    image("graphics", "image", utility.width, utility.height), 1.0, ModularSpiderRules.slotMovement,
                    emptySet(), fogOfWarSightRange = 5, durability = DurabilityProfile(3000.0, null),
                    selfRegenRate = .2f, income = utility.income,
                    attachmentModule = if (authoredInterceptor) utility.moduleWithInterception(interception(path, content)) else utility.module,
                    pointDefense = ModularSpiderUtilityForms.laserDefense.takeIf { name == ModularSpiderRules.LASER_DEFENSE })
                else -> {
                    checkNotNull(form)
                    val turret = image("graphics", "image_turret", form.width, form.height)
                    val additionalTurrets = form.additionalTurretDimensions.mapValues { (name, dimensions) ->
                        image("turret_$name", "image", dimensions.first, dimensions.second)
                    }
                    UnitDefinition(name, "CustomUnitMetadata", 15.0, 12.0, 1, image("graphics", "image", 1, 1),
                        1.0, ModularSpiderRules.slotMovement, emptySet(), fogOfWarSightRange = 5,
                        combat = form.combat(turret, additionalTurrets),
                        selfRegenRate = .2f, attachmentModule = form.module)
                }
            }
        }

    private fun interception(path: Path, content: UnitSourceReader.EffectiveSections): ProjectileInterceptionProfile {
        val section = "turret_silo"
        val values = content.values.getValue(section)
        fun number(field: String, allowZero: Boolean = false): Float =
            values[field]?.toFloatOrNull()?.takeIf { it >= 0f && (allowZero || it > 0f) && (it * it).isFinite() }
                ?: reject(ContentErrorCode.MALFORMED_SYNTAX, path, section, field, values[field], "Expected a finite range or height")
        return ProjectileInterceptionProfile(number("interceptProjectiles_andTargetingGroundUnderDistance"),
            number("interceptProjectiles_andUnderDistance"), number("interceptProjectiles_andOverHeight", allowZero = true))
    }
}
