package com.rustedwarfare.reforge.core.content

/** The qualified construction subset is identical at content and direct-world admission. */
internal object MechEngineerRules {
    private val constructionTypes = setOf("mechGun", "mechBunkerDeployed", "mechFactory", "mechFactoryT2",
        "airFactoryT2", "seaFactoryT2", "repairbay", "c_turret_t1", "laserDefence",
        "extractorT1", "extractorT2", "fabricatorT1")

    fun supportsConstructionTypes(types: List<String>): Boolean = types.isNotEmpty() &&
        types.distinct().size == types.size && types.all { it in constructionTypes }
}
