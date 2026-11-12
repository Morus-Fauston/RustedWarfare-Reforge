package com.rustedwarfare.reforge.core.content

import java.nio.file.Path
import java.util.Collections

data class AttachmentSlotDefinition(val number: Int, val x: Double, val y: Double, val idleHeading: Double,
    val initialTypeId: String)

class AttachmentLayout(slots: List<AttachmentSlotDefinition>, val slotImagePath: Path) {
    val slots: List<AttachmentSlotDefinition> = Collections.unmodifiableList(ArrayList(slots))
}

data class AttachmentInstall(val action: String, val targetTypeId: String, val cost: Long)

data class AttachmentRemoval(val targetTypeId: String, val refund: Long, val ticks: Float)

class AttachmentModuleProfile(
    val empty: Boolean,
    val selfBuildRate: Float = 0f,
    installs: List<AttachmentInstall> = emptyList(),
    val cancel: AttachmentRemoval? = null,
    val reclaim: AttachmentRemoval? = null,
) {
    val installs: List<AttachmentInstall> = Collections.unmodifiableList(ArrayList(installs))
}

internal object ModularSpiderRules {
    const val ID = "modularSpider"
    const val EMPTY = "modularSpider_emptySlot"
    const val GUN = "modularSpider_gunturret"
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
        return when (unit.id) {
            EMPTY -> module.empty && unit.radius == 20.0 && unit.maximumHealth == 3000.0 &&
                unit.combat == null && module.installs == listOf(AttachmentInstall("buildGun", GUN, 2000L)) &&
                module.selfBuildRate == 0f && module.cancel == null && module.reclaim == null
            GUN -> !module.empty && unit.radius == 15.0 && unit.maximumHealth == 2000.0 &&
                unit.combat?.let { it.attackRange == 290.0 && it.projectile == ProjectileProfile(44.0, 60.0, 6.0) } == true &&
                module.installs.isEmpty() && module.selfBuildRate == .001f &&
                module.cancel == AttachmentRemoval(EMPTY, 2000L, 120f) &&
                module.reclaim == AttachmentRemoval(EMPTY, 1000L, 180f)
            else -> false
        }
    }

    /** Check after all declarations are available, including cross-package replacements. */
    fun missingReference(unit: UnitDefinition, units: Map<String, UnitDefinition>): Pair<String, String>? {
        fun available(id: String, empty: Boolean): Boolean = units[id]?.let {
            supportsModule(it) && it.attachmentModule?.empty == empty
        } == true
        val spawn = unit.attachmentLayout?.slots?.firstOrNull { !available(it.initialTypeId, empty = true) }
        if (spawn != null) return "onCreateSpawnUnitOf" to spawn.initialTypeId
        val module = unit.attachmentModule ?: return null
        val install = module.installs.firstOrNull { !available(it.targetTypeId, empty = false) }
        if (install != null) return "convertTo" to install.targetTypeId
        val removal = listOfNotNull(module.cancel, module.reclaim).firstOrNull { !available(it.targetTypeId, empty = true) }
        return removal?.let { "convertTo" to it.targetTypeId }
    }
}
