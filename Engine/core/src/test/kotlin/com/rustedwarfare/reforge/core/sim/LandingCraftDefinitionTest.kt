package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class LandingCraftDefinitionTest {
    @Test fun a_native_transport_marker_cannot_authorize_a_different_body_or_weapon() = WaterTestScene.withMap { map ->
        val original = LandingCraftScene.definitions().getValue("hovercraft")
        val base = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0)))
        for (armed in listOf(false, true)) {
            val invalid = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement, emptySet(),
                durability = DurabilityProfile(if (armed) 450.0 else 451.0, original.durability!!.deadImagePath),
                combat = if (armed) base.unitDefinitions.getValue("c_tank").combat else null,
                build = original.build, transport = TransportProfile.NATIVE_HOVERCRAFT)
            val attempt = SimulationContext.initialize(base.copy(unitDefinitions = base.unitDefinitions + (original.id to invalid)))
            if (attempt is InitResult.Accepted) attempt.context.close()
            assertEquals("unsupported-transport", assertIs<InitResult.Rejected>(attempt).error.code)
        }
    }
}
