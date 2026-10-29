package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementType

/** The supported ordinary building set, separate from the builder's new-construction menu. */
internal object MaintenanceRules {
    private val prices = mapOf("commandCenter" to 3000L, "landFactory" to 700L, "laserDefence" to 1200L,
        "c_turret_t1" to 500L, "c_turret_t2_gun" to 1500L, "c_turret_t1_artillery" to 2100L,
        "extractorT1" to 700L, "extractorT2" to 2100L, "extractorT3" to 6100L,
        "fabricatorT1" to 2200L, "fabricatorT2" to 6600L, "fabricatorT3" to 14100L)

    fun validate(input: Scenario): SimulationError? {
        val contract = input.maintenanceContract ?: return null
        fun invalid() = SimulationError("unsupported-maintenance", "Expected a complete finite ordinary building maintenance registration")
        val buildings = input.unitDefinitions.filterValues { it.building != null }
        if (input.economyContract == null || input.unitDefinitions["builder"]?.builder == null ||
            buildings.isEmpty() || contract.basePrices.keys != buildings.keys) return invalid()
        for ((key, definition) in buildings) {
            val hp = definition.maximumHealth
            if (key != definition.id || prices[key] == null || contract.basePrices[key] != prices[key] ||
                definition.movement.type != MovementType.NONE || hp == null || !hp.isFinite() || hp <= 0.0 ||
                !hp.toFloat().isFinite()) return invalid()
        }
        return null
    }
}
