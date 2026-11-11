package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NautilusFormsAdmissionTest {
    @Test fun each_form_admits_paid_mobile_production_without_allocating_a_new_parent_identity() = WaterTestScene.withMap { map ->
        for (format in listOf("ini", "json", "hjson")) for (type in listOf(
            "nautilusSubmarine", "nautilusSubmarineSurface", "nautilusSubmarineLand")) {
            val scenario = NautilusScene.scenario(map, format).let { s ->
                s.copy(units = s.units.map { it.copy(typeId = type) })
            }
            val c = NautilusScene.open(scenario)
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "b".repeat(32), c.world)))
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p1", "mother", Position(680.0, 180.0), c.world)))
                c.advance(Step(0, 1.0, "sim-tick"))
                val parent = c.observe().units.single()
                assertEquals("mother", parent.unitId)
                assertEquals(4000.0, parent.health)
                assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
                assertEquals(.004f, c.observe().economy!!.queuedProduction.getValue("mother").single().progress)
            } finally { c.close() }
        }
    }
}
