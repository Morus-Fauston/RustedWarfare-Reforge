package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class TurretIdleTest {
    @Test
    fun a_deflected_tower_really_aims_then_stop_and_departure_preserve_the_seventy_tick_hold() {
        val base = TurretTestContent.scenario(Position(300.0, 200.0))
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = TurretTestContent.variant(tank, id = "target", radius = 1.0, combat = null,
            durability = com.rustedwarfare.reforge.core.content.DurabilityProfile(100000.0, tank.combat!!.deadImagePath),
            movement = tank.movement.copy(moveSpeed = 100.0, moveAccelerationSpeed = 1.0,
                moveDecelerationSpeed = 1.0, maxTurnSpeed = 360.0, turnAcceleration = 0.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("z", "p2", target.id, Position(500.0, 200.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 13L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var next = 0L
            repeat(221) { c.advance(Step(next++, 1.0, "sim-tick")) }
            assertTrue(c.observe().units.single { it.unitId == "t" }.turretHeading!! > 100.0)
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("enter", "p2", "z", Position(400.0, 200.0), c.world)))
            c.advance(Step(next++, 1.0, "sim-tick"))
            assertTrue(c.observe().units.single { it.unitId == "z" }.position.x < 465.0)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("park-near", "p2", "z", c.world)))
            var fired = false
            repeat(20) {
                val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                fired = fired || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "t" }
            }
            assertTrue(fired)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop-tower", "p1", "t", c.world)))
            c.advance(Step(next++, 1.0, "sim-tick"))
            assertEquals(90.0, c.observe().units.single { it.unitId == "t" }.turretHeading!!,
                .01, "The shared aim gate permits its existing .01-degree residual")
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("leave", "p2", "z", Position(580.0, 200.0), c.world)))
            var departureSteps = 0
            while (c.observe().units.single { it.unitId == "z" }.position.x <= 465.0 && departureSteps++ < 10)
                c.advance(Step(next++, 1.0, "sim-tick"))
            assertTrue(c.observe().units.single { it.unitId == "z" }.position.x > 465.0,
                c.observe().units.single { it.unitId == "z" }.toString())
            val aimedHeading = c.observe().units.single { it.unitId == "t" }.turretHeading!!
            assertIs<ActionResult.Accepted>(c.submit(StopAction("park-far", "p2", "z", c.world)))
            repeat(70) {
                val frame = c.advance(Step(next++, 1.0, "sim-tick"))
                assertEquals(aimedHeading, frame.units.single { it.unitId == "t" }.turretHeading,
                    "No idle callback occurs on the update that reduces real aim hold to zero")
                assertTrue(frame.projectileEvents.none { it.kind == "born" && it.projectile.sourceUnitId == "t" })
            }
            assertEquals(aimedHeading + 1.2000000178814,
                c.advance(Step(next, 1.0, "sim-tick")).units.single { it.unitId == "t" }.turretHeading!!, 1e-10)
        } finally { c.close() }
    }

    @Test
    fun real_idle_waits_through_210_switches_at_211_then_turns_from_212() {
        val scene = TurretTestContent.scenario(Position(300.0, 200.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val initial = context.observe()
            for (step in 0L..209L) assertEquals(90.0,
                context.advance(Step(step, 1.0, "sim-tick")).units.single().turretHeading)
            assertEquals(90.0, context.advance(Step(210L, 1.0, "sim-tick")).units.single().turretHeading)
            val turned = context.advance(Step(211L, 1.0, "sim-tick")).units.single().turretHeading
            assertEquals(91.2000000178814, turned, 1e-10)
            val headings = (212L..700L).map {
                context.advance(Step(it, 1.0, "sim-tick")).units.single().turretHeading
            }
            assertTrue(headings.any { it == 110.0 }, "The real turret reaches its positive endpoint")
            assertTrue(headings.any { it == 70.0 }, "The real turret reverses and reaches its negative endpoint")
            assertTrue(context.observe().projectiles.isEmpty())
            assertEquals(0, context.observe().combat!!.weapons.single().randomCounter)
            assertEquals(90.0, initial.units.single().turretHeading)
        } finally { context.close() }
    }
}
