package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusAdmissionTest {
    @Test fun direct_host_registration_cannot_replace_the_qualified_product_price_and_rate() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val h = base.unitDefinitions.getValue("hoverTank")
        val forged = UnitDefinition(h.id, h.className, h.radius, h.displayRadius, h.totalFrames, h.imagePath,
            h.imageScale, h.movement, h.retainedUnconsumed, h.fogOfWarSightRange, h.combat,
            build = BuildProfile(1L, 1f), shadowImagePath = h.shadowImagePath)
        val result = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (h.id to forged)))
        assertEquals("unsupported-production", assertIs<InitResult.Rejected>(result).error.code)
    }
}
