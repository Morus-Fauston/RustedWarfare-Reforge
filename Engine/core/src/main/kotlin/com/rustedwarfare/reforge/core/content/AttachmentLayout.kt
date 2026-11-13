package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

data class AttachmentSlotDefinition(val number: Int, val x: Double, val y: Double, val idleHeading: Double,
    val initialTypeId: String)

class AttachmentLayout(slots: List<AttachmentSlotDefinition>, val slotImagePath: Path) {
    val slots: List<AttachmentSlotDefinition> = Collections.unmodifiableList(ArrayList(slots))
}

data class AttachmentInstall(val action: String, val targetTypeId: String, val cost: Long)

data class AttachmentUpgrade(val action: String, val targetTypeId: String, val cost: Long, val ticks: Float)

data class AttachmentRemoval(val targetTypeId: String, val refund: Long, val ticks: Float)

sealed interface AttachmentAutomaticAction {
    data class RestoreParentShield(val amount: Float) : AttachmentAutomaticAction
    data class ConvertWhenBuilt(val targetTypeId: String) : AttachmentAutomaticAction
}

class AttachmentModuleProfile(
    val empty: Boolean,
    val selfBuildRate: Float = 0f,
    installs: List<AttachmentInstall> = emptyList(),
    val cancel: AttachmentRemoval? = null,
    val reclaim: AttachmentRemoval? = null,
    upgrades: List<AttachmentUpgrade> = emptyList(),
    val automaticAction: AttachmentAutomaticAction? = null,
) {
    val installs: List<AttachmentInstall> = Collections.unmodifiableList(ArrayList(installs))
    val upgrades: List<AttachmentUpgrade> = Collections.unmodifiableList(ArrayList(upgrades))
}

internal object ModularSpiderRules {
    const val ID = "modularSpider"
    const val EMPTY = "modularSpider_emptySlot"
    const val GUN = "modularSpider_gunturret"
    const val LASER = "modularSpider_smallgunturret"
    const val ANTI_AIR = "modularSpider_antiair"
    const val LIGHTNING = "modularSpider_lightning"
    const val ARTILLERY = "modularSpider_artillery"
    const val FABRICATOR = "modularSpider_fabricator"
    const val FABRICATOR_T2 = "modularSpider_fabricatorT2"
    const val SHIELD = "modularSpider_shieldGen"
    const val SPEED_INCOMPLETE = "modularSpider_speedIncomplete"
    const val SPEED = "modularSpider_speed"
    const val LASER_DEFENSE = "modularSpider_laserdefense"
    const val ANTI_NUKE = "modularSpider_antinuke"
    const val BLINK = "modularSpider_blink"
    val installs = listOf(
        AttachmentInstall("buildGun", GUN, 2000L),
        AttachmentInstall("buildSmallLaser", LASER, 700L),
        AttachmentInstall("buildAntiair", ANTI_AIR, 2000L),
        AttachmentInstall("buildLightning", LIGHTNING, 6000L),
        AttachmentInstall("buildArtillery", ARTILLERY, 5000L),
        AttachmentInstall("buildFabricator", FABRICATOR, 3000L),
        AttachmentInstall("buildShieldGen", SHIELD, 6000L),
        AttachmentInstall("buildSpeed", SPEED_INCOMPLETE, 6000L),
        AttachmentInstall("buildLaserdefense", LASER_DEFENSE, 3000L),
        AttachmentInstall("buildAntinuke", ANTI_NUKE, 7000L),
        AttachmentInstall("buildBlink", BLINK, 15000L),
    )
    val slots = listOf(
        AttachmentSlotDefinition(1, -11.0, 32.0, 0.0, EMPTY),
        AttachmentSlotDefinition(2, 11.0, 32.0, 0.0, EMPTY),
        AttachmentSlotDefinition(3, -11.0, 6.0, -90.0, EMPTY),
        AttachmentSlotDefinition(4, 11.0, 6.0, 90.0, EMPTY),
        AttachmentSlotDefinition(5, -10.0, -20.0, 180.0, EMPTY),
        AttachmentSlotDefinition(6, 11.0, -20.0, 180.0, EMPTY),
    )
    val movement = MovementProfile(MovementType.OVER_CLIFF_WATER, .6, .015, .015, .4, .1, false, false, 1.0)
    val slotMovement = MovementProfile(MovementType.OVER_CLIFF_WATER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false, 0.0)
    val build = BuildProfile(90000L, .0002f)
    val deathExplosion = DeathExplosionProfile(4400f, 200f, 75f, nuclear = true)
    val constructionTypes = listOf("builder", "hovercraft", "c_turret_t1", "c_turret_t1_artillery",
        "extractorT1", "fabricatorT1", "landFactory", "mechFactory", "airFactory", "seaFactory",
        "laserDefence", "repairbay", "bomber")

    fun mobileConstruction(unit: UnitDefinition): ConstructionProfile? {
        val build = unit.build ?: return null
        val qualified = when (unit.id) {
            "builder" -> unit.className == "NativeBuilderFixture" && build == BuildProfile(500L, .002f)
            "hovercraft" -> unit.transport == TransportProfile.NATIVE_HOVERCRAFT && build == BuildProfile(600L, .003f)
            "bomber" -> BomberRules.supports(unit)
            else -> false
        }
        return if (qualified) ConstructionProfile(build.price, build.rate) else null
    }

    fun builder(types: List<String>) = BuilderProfile(types, range = 220f, preparation = 30f,
        decay = 4f, repairRate = .4f, buildSpeedMultiplier = 8f, mainNanoTurret = "nanoTurret")

    fun supportsWork(unit: UnitDefinition): Boolean {
        val work = unit.builder ?: return false
        val expected = builder(work.buildingTypeIds)
        return supportsChassis(unit) && unit.maintenance == MaintenanceProfile.MODULAR_SPIDER &&
            unit.build == build && unit.deathExplosion == deathExplosion && unit.transport == null &&
            work.buildingTypeIds.isNotEmpty() && work.buildingTypeIds.distinct().size == work.buildingTypeIds.size &&
            constructionTypes.containsAll(work.buildingTypeIds) && work.range == expected.range &&
            work.preparation == expected.preparation && work.decay == expected.decay &&
            work.repairRate == expected.repairRate && work.buildSpeedMultiplier == expected.buildSpeedMultiplier &&
            work.mainNanoTurret == expected.mainNanoTurret &&
            unit.combat?.namedWeapons?.byName?.get("nanoTurret")?.let { !it.canShoot && it.warmup == 30.0 } == true
    }

    fun supportsChassis(unit: UnitDefinition): Boolean = unit.id == ID && unit.attachmentLayout?.slots == slots &&
        unit.movement == movement && unit.maximumHealth == 10000.0 && unit.shield == ShieldProfile(5000f, .3f) &&
        unit.mech?.let { it.kind == MechKind.EIGHT_LEGGED && it.isValid() } == true

    fun supportsModule(unit: UnitDefinition): Boolean {
        val module = unit.attachmentModule ?: return false
        if (unit.movement != slotMovement || unit.attachmentLayout != null || unit.transport != null) return false
        if (unit.pointDefense != (if (unit.id == LASER_DEFENSE) ModularSpiderUtilityForms.laserDefense else null)) return false
        if (unit.id == EMPTY) return module.empty && unit.radius == 20.0 && unit.maximumHealth == 3000.0 &&
            unit.combat == null && unit.income == null && module.automaticAction == null &&
            module.installs == installs && module.upgrades.isEmpty() &&
            module.selfBuildRate == 0f && module.cancel == null && module.reclaim == null
        val utility = ModularSpiderUtilityForms.byId[unit.id]
        if (utility != null) return unit.radius == 15.0 && unit.maximumHealth == 3000.0 &&
            unit.combat == null && unit.shield == null && unit.income == utility.income &&
            matches(module, utility.module)
        val form = ModularSpiderWeaponForms.byId[unit.id] ?: return false
        val combat = unit.combat ?: return false
        val extraImages = form.additionalTurretDimensions.keys.associateWith { name ->
            combat.namedWeapons?.byName?.get(name)?.imagePath ?: return false
        }
        return unit.radius == 15.0 && unit.maximumHealth == form.health && unit.income == null &&
            combat.turretImagePath?.let { combat == form.combat(it, extraImages) } == true &&
            matches(module, form.module)
    }

    private fun matches(module: AttachmentModuleProfile, expected: AttachmentModuleProfile): Boolean =
        !module.empty && module.installs.isEmpty() && module.selfBuildRate == expected.selfBuildRate &&
            module.cancel == expected.cancel && module.reclaim == expected.reclaim &&
            module.upgrades == expected.upgrades && module.automaticAction == expected.automaticAction

    fun isModuleId(id: String): Boolean = id == EMPTY || id in ModularSpiderWeaponForms.byId ||
        id in ModularSpiderUtilityForms.byId

    /** Check after all declarations are available, including cross-package replacements. */
    data class Reference(val section: String, val field: String, val target: String)

    fun missingReference(unit: UnitDefinition, units: Map<String, UnitDefinition>): Reference? {
        fun available(id: String, empty: Boolean): Boolean = units[id]?.let {
            supportsModule(it) && it.attachmentModule?.empty == empty
        } == true
        val spawn = unit.attachmentLayout?.slots?.firstOrNull { !available(it.initialTypeId, empty = true) }
        if (spawn != null) return Reference("attachment_slot${spawn.number}", "onCreateSpawnUnitOf", spawn.initialTypeId)
        if (unit.id == ID) unit.builder?.buildingTypeIds?.firstOrNull {
                units[it]?.let { target -> target.construction ?: mobileConstruction(target) } == null
            }?.let {
            return Reference("canBuild_chassis", "constructionTypes", it)
        }
        val module = unit.attachmentModule ?: return null
        val conversions = module.installs.map { Reference("action_${it.action}", "convertTo", it.targetTypeId) } +
            module.upgrades.map { Reference("action_${it.action}", "convertTo", it.targetTypeId) } +
            listOfNotNull((module.automaticAction as? AttachmentAutomaticAction.ConvertWhenBuilt)?.let {
                Reference("hiddenAction_speedFinished", "convertTo", it.targetTypeId)
            })
        return conversions.firstOrNull { !available(it.target, empty = false) }
            ?: listOf("cancel" to module.cancel, "reclaim" to module.reclaim).firstNotNullOfOrNull { (action, removal) ->
                removal?.takeUnless { available(it.targetTypeId, empty = true) }
                    ?.let { Reference("action_$action", "convertTo", it.targetTypeId) }
            }
    }
}
