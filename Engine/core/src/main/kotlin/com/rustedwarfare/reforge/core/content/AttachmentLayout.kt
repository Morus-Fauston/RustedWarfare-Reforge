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

class AttachmentModuleProfile(
    val empty: Boolean,
    val selfBuildRate: Float = 0f,
    installs: List<AttachmentInstall> = emptyList(),
    val cancel: AttachmentRemoval? = null,
    val reclaim: AttachmentRemoval? = null,
    upgrades: List<AttachmentUpgrade> = emptyList(),
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
    val installs = listOf(
        AttachmentInstall("buildGun", GUN, 2000L),
        AttachmentInstall("buildSmallLaser", LASER, 700L),
        AttachmentInstall("buildAntiair", ANTI_AIR, 2000L),
        AttachmentInstall("buildLightning", LIGHTNING, 6000L),
        AttachmentInstall("buildArtillery", ARTILLERY, 5000L),
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

    fun supportsChassis(unit: UnitDefinition): Boolean = unit.id == ID && unit.attachmentLayout?.slots == slots &&
        unit.movement == movement && unit.maximumHealth == 10000.0 && unit.shield == ShieldProfile(5000f, .3f) &&
        unit.mech?.let { it.kind == MechKind.EIGHT_LEGGED && it.isValid() } == true

    fun supportsModule(unit: UnitDefinition): Boolean {
        val module = unit.attachmentModule ?: return false
        if (unit.movement != slotMovement || unit.attachmentLayout != null || unit.transport != null) return false
        if (unit.id == EMPTY) return module.empty && unit.radius == 20.0 && unit.maximumHealth == 3000.0 &&
            unit.combat == null && module.installs == installs && module.upgrades.isEmpty() &&
            module.selfBuildRate == 0f && module.cancel == null && module.reclaim == null
        val form = ModularSpiderWeaponForms.byId[unit.id] ?: return false
        val combat = unit.combat ?: return false
        val expected = form.module
        val extraImages = form.additionalTurretDimensions.keys.associateWith { name ->
            combat.namedWeapons?.byName?.get(name)?.imagePath ?: return false
        }
        return !module.empty && unit.radius == 15.0 && unit.maximumHealth == form.health &&
            combat.turretImagePath?.let { combat == form.combat(it, extraImages) } == true &&
            module.installs.isEmpty() && module.selfBuildRate == expected.selfBuildRate &&
            module.cancel == expected.cancel && module.reclaim == expected.reclaim && module.upgrades == expected.upgrades
    }

    fun isModuleId(id: String): Boolean = id == EMPTY || id in ModularSpiderWeaponForms.byId

    /** Check after all declarations are available, including cross-package replacements. */
    data class Reference(val section: String, val field: String, val target: String)

    fun missingReference(unit: UnitDefinition, units: Map<String, UnitDefinition>): Reference? {
        fun available(id: String, empty: Boolean): Boolean = units[id]?.let {
            supportsModule(it) && it.attachmentModule?.empty == empty
        } == true
        val spawn = unit.attachmentLayout?.slots?.firstOrNull { !available(it.initialTypeId, empty = true) }
        if (spawn != null) return Reference("attachment_slot${spawn.number}", "onCreateSpawnUnitOf", spawn.initialTypeId)
        val module = unit.attachmentModule ?: return null
        val conversions = module.installs.map { Reference("action_${it.action}", "convertTo", it.targetTypeId) } +
            module.upgrades.map { Reference("action_${it.action}", "convertTo", it.targetTypeId) }
        return conversions.firstOrNull { !available(it.target, empty = false) }
            ?: listOf("cancel" to module.cancel, "reclaim" to module.reclaim).firstNotNullOfOrNull { (action, removal) ->
                removal?.takeUnless { available(it.targetTypeId, empty = true) }
                    ?.let { Reference("action_$action", "convertTo", it.targetTypeId) }
            }
    }
}
