package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import kotlin.test.*

class ArtillerySnapshotTest {
    private fun scene(): Scenario {
        val base = UpgradeTestContent.scenario()
        val artillery = base.unitDefinitions.getValue("c_turret_t1_artillery")
        val tank = base.unitDefinitions.getValue("c_tank")
        val dummy = UpgradeTestContent.variant(tank, id = "dummy", combat = null,
            durability = DurabilityProfile(5000.0, tank.combat!!.deadImagePath))
        return base.copy(unitDefinitions = base.unitDefinitions + (dummy.id to dummy),
            units = listOf(UnitSpawn("t", "p1", artillery.id, Position(230.0, 150.0), 90.0),
                UnitSpawn("z", "p2", dummy.id, Position(405.0, 150.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 15L))
    }

    @Test fun real_artillery_fire_captures_ground_position_height_and_spread_without_a_tracking_target() {
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            val result = c.advance(Step(0, 1.0, "sim-tick"))
            val born = result.projectileEvents.single { it.kind == "born" }.projectile
            assertEquals("t", born.sourceUnitId)
            assertNull(born.targetUnitId)
            val snapshot = assertNotNull(born.ground)
            assertEquals(0f, snapshot.targetHeight)
            assertEquals(1.2f, snapshot.verticalSpeed)
            assertTrue(snapshot.targetPosition.x in 395.0..415.0)
            assertTrue(snapshot.targetPosition.y in 140.0..160.0)
            assertNotEquals(Position(405.0, 150.0), snapshot.targetPosition)
            // Fixed prediction-oracle-01: qualified 02 arithmetic witness plus the qualified RNG order.
            assertEquals(0x43c80a3d, snapshot.targetPosition.x.toFloat().toRawBits())
            assertEquals(0x430d547b, snapshot.targetPosition.y.toFloat().toRawBits())
            assertEquals(240.0, born.remainingLife)
            assertEquals(Position(263.0, 150.0), born.position)
            val visible = c.observePlayer(c.world, "p1")
            assertTrue(visible.projectileEvents.isNotEmpty())
            assertTrue(visible.projectileEvents.all { it.targetUnitId == null && it.damage == null })
        } finally { c.close() }
    }

    @Test fun real_public_shots_use_three_float_iterations_then_life_cap_range_clip_and_spread() {
        data class Case(val speed: Float, val range: Double, val xBits: Int, val yBits: Int)
        // Trusted finite speed/range/acceleration changes isolate the real public shot's numeric gates.
        // Expected bits come from prediction-oracle-01; no product helper calculates the oracle here.
        for (case in listOf(Case(4f, 350.0, 0x43bd737d, 0x430d57b1),
            Case(.1f, 400.0, 0x44188b85, 0x430d6e66),
            Case(Math.nextDown(.1f), 400.0, 0x43ad170a, 0x430d547b),
            Case(2f, 130.0, 0x43c20a3d, 0x430d613e))) {
            val base = scene()
            val artillery = base.unitDefinitions.getValue("c_turret_t1_artillery")
            val gun = UpgradeTestContent.variant(artillery, combat = artillery.combat!!.copy(
                attackRange = case.range, projectile = artillery.combat.projectile.copy(speed = case.speed.toDouble())))
            val oldDummy = base.unitDefinitions.getValue("dummy")
            val dummy = UpgradeTestContent.variant(oldDummy, movement = oldDummy.movement.copy(moveAccelerationSpeed = 1.1))
            val input = base.copy(unitDefinitions = base.unitDefinitions + (gun.id to gun) + (dummy.id to dummy),
                players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                units = listOf(base.units.first(), UnitSpawn("a", "p2", dummy.id, Position(350.0, 150.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "a" to 12L), 15L))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("move", "p2", "a", Position(450.0, 150.0), c.world)))
                val result = c.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(Position(351.1, 150.0), result.units.first { it.unitId == "a" }.position)
                assertEquals(1.1, result.units.first { it.unitId == "a" }.speed)
                val born = result.projectileEvents.single { it.kind == "born" }.projectile
                val target = assertNotNull(born.ground).targetPosition
                assertEquals(case.xBits, target.x.toFloat().toRawBits(), "speed=${case.speed},range=${case.range}: x")
                assertEquals(case.yBits, target.y.toFloat().toRawBits(), "speed=${case.speed},range=${case.range}: y")
            } finally { c.close() }
        }
    }

    @Test fun later_real_target_motion_never_rewrites_the_launched_ground_snapshot_or_old_observation() {
        val base = scene()
        val input = base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units.map { if (it.unitId == "z") it.copy(position = Position(350.0, 150.0), heading = 90.0) else it })
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertIs<ActionResult.Accepted>(c.submit(MoveAction("moving", "p2", "z", Position(450.0, 150.0), c.world)))
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            val oldShot = first.projectiles.single()
            val snapshot = oldShot.ground!!
            var impact = false
            for (step in 1L..50L) {
                val row = c.advance(Step(step, 1.0, "sim-tick"))
                val shot = row.projectiles.firstOrNull { it.objectId == oldShot.objectId }
                    ?: row.projectileEvents.firstOrNull { it.projectile.objectId == oldShot.objectId }?.projectile
                if (shot != null) {
                    assertEquals(snapshot.targetPosition, shot.ground!!.targetPosition)
                    assertEquals(snapshot.targetHeight, shot.ground.targetHeight)
                    assertNull(shot.targetUnitId)
                }
                if (row.projectileEvents.any { it.projectile.objectId == oldShot.objectId && it.kind == "impact" }) { impact = true; break }
            }
            assertTrue(impact)
            assertTrue(c.observe().units.single { it.unitId == "z" }.position.x > first.units.single { it.unitId == "z" }.position.x)
            assertEquals(snapshot, first.projectiles.single().ground)
            assertEquals(oldShot, first.projectiles.single())
        } finally { c.close() }
    }
}
