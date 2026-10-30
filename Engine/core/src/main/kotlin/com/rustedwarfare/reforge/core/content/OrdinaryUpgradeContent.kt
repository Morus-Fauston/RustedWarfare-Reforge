package com.rustedwarfare.reforge.core.content

import java.util.Collections

/** Finite supported content facts shared by readers and ordinary-upgrade registration. */
internal object OrdinaryUpgradeContent {
    data class TurretTarget(val targetTypeId: String, val groundProjectile: Boolean)
    data class EconomicAction(val action: UpgradeActionProfile, val sourceSpeed: String)

    val turret: Map<String, TurretTarget> = Collections.unmodifiableMap(linkedMapOf(
        "upgrade_gun" to TurretTarget("c_turret_t2_gun", false),
        "upgrade_artillery" to TurretTarget("c_turret_t1_artillery", true),
    ))
    val economic: Map<String, EconomicAction> = Collections.unmodifiableMap(linkedMapOf(
        "extractorT1" to EconomicAction(UpgradeActionProfile("upgrade_t2", "extractorT2", 1400L, .0006f), "0.0006"),
        "extractorT2" to EconomicAction(UpgradeActionProfile("upgrade_t3", "extractorT3", 4000L, .0003f), "0.0003"),
        "fabricatorT1" to EconomicAction(UpgradeActionProfile("upgrade_t2", "fabricatorT2", 4400L, 1f / (65.6f * 60f)), "65.6s"),
        "fabricatorT2" to EconomicAction(UpgradeActionProfile("upgrade_t3", "fabricatorT3", 7500L, 1f / (93.3f * 60f)), "93.3s"),
    ))
    private val economicBases = mapOf("extractorT1" to "extractorT1", "extractorT2" to "extractorT1",
        "extractorT3" to "extractorT1", "fabricatorT1" to "fabricatorT1", "fabricatorT2" to "fabricatorT1",
        "fabricatorT3" to "fabricatorT1")
    private val bases = economicBases + mapOf("c_turret_t1" to "c_turret_t1",
        "c_turret_t2_gun" to "c_turret_t1", "c_turret_t1_artillery" to "c_turret_t1")
    fun baseTypeId(typeId: String): String? = bases[typeId]
    fun constructionBaseTypeId(typeId: String): String? = economicBases[typeId]
}
