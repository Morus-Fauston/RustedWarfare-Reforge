package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.PathSearchReason
import kotlin.test.*

class TurretCombatTest {
    @Test
    fun real_tower_fires_from_its_body_mount_and_applies_real_damage_to_a_factory() {
        val scenario = TurretTestContent.scenario(
            UnitSpawn("t", "p1", "c_turret_t1", Position(280.0, 100.0), 90.0),
            UnitSpawn("g", "p2", "landFactory", Position(430.0, 100.0), 0.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            val initial = context.observe()
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            val shot = first.projectileEvents.single { it.kind == "born" && it.projectile.sourceUnitId == "t" }.projectile
            assertEquals(308.0, shot.position.x, 1e-9, "body mount6 + barrel22 must be consumed by the real born projectile")
            assertEquals(100.0, shot.position.y, 1e-9)
            assertEquals(240.0, shot.remainingLife)
            assertEquals(1200.0, first.units.single { it.unitId == "g" }.health)
            var latest = first
            for (step in 1L..29L) latest = context.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1159.0, latest.units.single { it.unitId == "g" }.health)
            assertEquals(700.0, latest.units.single { it.unitId == "t" }.health)
            assertEquals(Position(280.0, 100.0), latest.units.single { it.unitId == "t" }.position)
            assertEquals(1200.0, initial.units.single { it.unitId == "g" }.health, "old frames remain immutable")
        } finally { context.close() }
    }

    @Test
    fun distant_manual_target_is_retained_without_path_search_then_real_moving_target_enters_range() {
        val base = TurretTestContent.scenario(
            UnitSpawn("t", "p1", "c_turret_t1", Position(280.0, 100.0), 90.0),
            UnitSpawn("b", "p2", "c_tank", Position(500.0, 100.0), 270.0))
        // This trusted JVM fixture enables p2 only to issue a real moving-target order; PC p2 remains NONE.
        val scenario = base.copy(players = base.players.map { if (it.playerId == "p2") it.copy(control = PlayerControl.LOCAL) else it })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("tower-far", "p1", "t", "b", context.world)))
            val far = context.advance(Step(0, 1.0, "sim-tick"))
            val tower = far.units.single { it.unitId == "t" }
            assertEquals("b", tower.attackTargetId)
            assertEquals(Position(280.0, 100.0), tower.position)
            assertTrue(tower.pathPoints.isEmpty())
            assertTrue(far.projectileEvents.none { it.projectile.sourceUnitId == "t" })
            assertTrue(context.drainPathDiagnostics(context.world).none { it.reason == PathSearchReason.ATTACK_PURSUIT },
                "NONE must not ask a path planner even if the route would be unreachable")
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter", "p2", "b", Position(430.0, 100.0), context.world)))
            var born = false; var damage = false
            for (step in 1L..150L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                born = born || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "t" }
                damage = damage || frame.units.single { it.unitId == "b" }.health!! < 210.0
                assertEquals(Position(280.0, 100.0), frame.units.single { it.unitId == "t" }.position)
            }
            assertTrue(born && damage, "Only actual target movement into range may lead to a real hit")
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-tower", "p1", "t", context.world)))
            assertNull(context.advance(Step(151, 1.0, "sim-tick")).units.single { it.unitId == "t" }.attackTargetId)
        } finally { context.close() }
    }
}
