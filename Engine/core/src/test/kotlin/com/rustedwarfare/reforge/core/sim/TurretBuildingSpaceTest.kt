package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class TurretBuildingSpaceTest {
    @Test
    fun public_initialization_uses_the_custom_offset_footprint_instead_of_the_old_factory_anchor() {
        val tower = UnitSpawn("t", "p1", "c_turret_t1", Position(320.0, 220.0), 90.0)
        val insideCustom = TurretTestContent.scenario(tower,
            UnitSpawn("a", "p1", "c_tank", Position(310.0, 210.0), 90.0))
        val rejected = SimulationContext.initialize(insideCustom)
        if (rejected is InitResult.Accepted) rejected.context.close()
        assertIs<InitResult.Rejected>(rejected, "custom occupied cell15,10 must reject the mobile center")
        val oldOnly = TurretTestContent.scenario(tower,
            UnitSpawn("a", "p1", "c_tank", Position(350.0, 250.0), 90.0))
        val accepted = assertIs<InitResult.Accepted>(SimulationContext.initialize(oldOnly))
        accepted.context.close()
    }
}
