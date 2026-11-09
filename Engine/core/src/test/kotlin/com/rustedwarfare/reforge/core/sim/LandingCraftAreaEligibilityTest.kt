package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class LandingCraftAreaEligibilityTest {
    @Test fun a_real_ground_impact_damages_the_carrier_but_skips_its_hidden_passenger() {
        val base = GroundTestContent.scene(5f)
        val definitions = LandingCraftScene.definitions()
        val spawns = base.units + listOf(
            UnitSpawn("craft", "p2", "hovercraft", Position(340.0, 150.0), 90.0),
            UnitSpawn("worker", "p2", "builder", Position(353.0, 150.0), 90.0))
        val input = base.copy(unitDefinitions = base.unitDefinitions + definitions,
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) }, units = spawns,
            economyContract = EconomyContract(base.players.associate { it.playerId to 10000L }),
            combatContract = CombatContract(12345, -1, spawns.mapIndexed { i, unit -> unit.unitId to 11L + i }.toMap(), 100L))
        val opened = SimulationContext.initialize(input)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("load", "p2", "worker", "craft", LoadDirection.INTO, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("impact", "p1", "t", "z", context.world)))
            val sample = context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(sample.projectileEvents.any { it.kind == "impact" })
            assertTrue(sample.units.single { it.unitId == "craft" }.health!! < 450.0)
            assertEquals(170.0, sample.units.single { it.unitId == "worker" }.health)
            assertEquals("craft", sample.units.single { it.unitId == "worker" }.carriedBy)
        } finally { context.close() }
    }
}
