package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.*

class TurretIdleTest {
    private fun heading(context: SimulationContext) = context.observePlayer(context.world, "p1").units.single { it.unitId == "t" }.turretHeading

    @Test
    fun idle_wait_then_real_public_heading_moves_to_an_endpoint_and_reverses_deterministically() {
        val scenario = TurretTestContent.scenario(UnitSpawn("t", "p1", "c_turret_t1", Position(280.0, 100.0), 90.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        val trace = mutableListOf<Double>()
        try {
            val initial = context.observePlayer(context.world, "p1")
            for (step in 0L..209L) {
                context.advance(Step(step, 1.0, "sim-tick"))
                trace += heading(context)
                assertEquals(90.0, trace.last())
            }
            context.advance(Step(210, 1.0, "sim-tick")); trace += heading(context)
            assertEquals(90.0, trace.last(), "211st callback chooses a new endpoint without turning again")
            context.advance(Step(211, 1.0, "sim-tick")); trace += heading(context)
            assertEquals(91.2, trace.last(), 1e-6, "speed factor .2 enters the real constant6 turn function")
            for (step in 212L..699L) {
                context.advance(Step(step, 1.0, "sim-tick")); trace += heading(context)
            }
            assertTrue(trace.any { it >= 109.999999 })
            assertTrue(trace.any { it < 89.0 }, "a later actual callback must reverse")
            assertTrue(trace.all { it in 69.999999..110.000001 })
            assertEquals(90.0, initial.units.single().turretHeading, "old authorized frames cannot be mutated")
        } finally { context.close() }
        val replay = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val again = (0L..699L).map { replay.advance(Step(it, 1.0, "sim-tick")); heading(replay) }
            assertEquals(trace, again)
        } finally { replay.close() }
    }

    @Test
    fun an_idled_tower_really_aims_fires_then_holds_and_resumes_after_stop_and_target_leaves() {
        val base = TurretTestContent.scenario(
            UnitSpawn("t", "p1", "c_turret_t1", Position(280.0, 100.0), 90.0),
            UnitSpawn("b", "p2", "c_tank", Position(500.0, 100.0), 270.0))
        val input = base.copy(players = base.players.map { if (it.playerId == "p2") it.copy(control = PlayerControl.LOCAL) else it })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            var step = 0L
            while (step < 300L) context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(heading(context) > 100.0, "the incoming-target check must begin from an actual idle deflection")
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("wait-far", "p1", "t", "b", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter-idled", "p2", "b", Position(440.0, 100.0), context.world)))
            var born = false
            while (step < 550L && context.observe().units.single { it.unitId == "b" }.health == 210.0) {
                val frame = context.advance(Step(step++, 1.0, "sim-tick"))
                born = born || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "t" }
            }
            assertTrue(born)
            assertTrue(context.observe().units.single { it.unitId == "b" }.health!! < 210.0)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("leave-idled", "p2", "b", Position(500.0, 100.0), context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-idled", "p1", "t", context.world)))
            while (step < 850L && context.observe().units.single { it.unitId == "b" }.position.x < 445.0)
                context.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single { it.unitId == "b" }.alive)
            assertTrue(context.observe().units.single { it.unitId == "b" }.position.x >= 445.0)
            val held = heading(context)
            repeat(50) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(held, heading(context), "recent aim must keep the tower from immediately snapping to idle")
                assertNull(context.observe().units.single { it.unitId == "t" }.attackTargetId)
            }
            var resumed = false
            repeat(600) {
                context.advance(Step(step++, 1.0, "sim-tick"))
                resumed = resumed || kotlin.math.abs(heading(context) - held) > 1.0
            }
            assertTrue(resumed, "Stop and a departed target must not permanently freeze or reset idle state")
        } finally { context.close() }
    }

    @Test
    fun an_active_target_suppresses_idle_and_legacy_tanks_keep_their_null_profile_behavior() {
        val active = TurretTestContent.scenario(
            UnitSpawn("t", "p1", "c_turret_t1", Position(280.0, 100.0), 90.0),
            UnitSpawn("g", "p2", "landFactory", Position(430.0, 100.0), 0.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(active)).context
        val tower = active.unitDefinitions.getValue("c_turret_t1")
        val noIdle = UnitDefinition(tower.id, tower.className, tower.radius, tower.displayRadius, tower.totalFrames,
            tower.imagePath, tower.imageScale, tower.movement, tower.retainedUnconsumed, tower.fogOfWarSightRange,
            tower.combat!!.copy(idleSweep = null), tower.durability, tower.building, tower.production, tower.build, tower.directHitTargetKind)
        val control = assertIs<InitResult.Accepted>(SimulationContext.initialize(active.copy(
            unitDefinitions = active.unitDefinitions + (tower.id to noIdle)))).context
        try {
            for (step in 0L..400L) {
                val actual = context.advance(Step(step, 1.0, "sim-tick"))
                val expected = control.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units, "real active aiming includes shot scatter, so compare the null-idle control")
                assertEquals(expected.projectiles, actual.projectiles)
                assertEquals(expected.projectileEvents, actual.projectileEvents)
            }
            assertTrue(context.observe().units.single { it.unitId == "g" }.health!! < 1200.0)
        } finally { control.close(); context.close() }
        val legacy = TurretTestContent.scenario(UnitSpawn("a", "p1", "c_tank", Position(110.0, 210.0), 90.0))
        val old = assertIs<InitResult.Accepted>(SimulationContext.initialize(legacy)).context
        try {
            for (step in 0L..699L) old.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(90.0, old.observePlayer(old.world, "p1").units.single().turretHeading)
        } finally { old.close() }
    }
}
