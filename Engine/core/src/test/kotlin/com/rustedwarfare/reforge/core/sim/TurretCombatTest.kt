package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.TurretMount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurretCombatTest {
    @Test fun building_attack_finds_a_continuous_stance_when_no_reachable_cell_center_is_in_range() =
        continuousStance(420.0)

    @Test fun building_attack_does_not_finish_or_stop_propulsion_before_entering_a_narrow_range_sliver() =
        continuousStance(428.5)

    private fun continuousStance(targetX: Double) {
        val base = TurretTestContent.scenario(Position(targetX, 200.0))
        val tower = TurretTestContent.variant(base.unitDefinitions.getValue("c_turret_t1"), combat = null)
        val map = base.map.let { m -> com.rustedwarfare.reforge.core.content.MapDefinition(
            m.widthTiles, m.heightTiles, m.tileWidth, m.tileHeight, m.tileSets, m.groundGids,
            com.rustedwarfare.reforge.core.content.BlockingGrid(m.widthTiles, m.heightTiles,
                BooleanArray(m.widthTiles * m.heightTiles) { it % m.widthTiles == 15 })) }
        val scene = base.copy(map = map, unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = listOf(UnitSpawn("a", "p1", "c_tank", Position(100.0, 200.0), 90.0),
                UnitSpawn("t", "p2", tower.id, Position(targetX, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 12L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val move = assertIs<ActionResult.Accepted>(c.submit(MoveAction("legal-continuous-goal", "p1", "a", Position(299.0, 200.0), c.world)))
            assertTrue(move.events.none { it.kind == "path-unreachable" })
            assertEquals(Position(299.0, 200.0), c.observe().units.single { it.unitId == "a" }.pathPoints.last())
            assertIs<ActionResult.Accepted>(c.submit(StopAction("reset-order", "p1", "a", c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("far-building", "p1", "a", "t", c.world)))
            var fired = false
            var arrivedOutsideRange = false
            for (step in 0L..799L) {
                val frame = c.advance(Step(step, 1.0, "sim-tick"))
                val actor = frame.units.single { it.unitId == "a" }
                assertTrue(actor.position.x < 300.0, "Never open the wall or the target's building cost")
                fired = fired || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                val dx = actor.position.x - targetX
                val dy = actor.position.y - 200.0
                if (frame.events.any { it.kind == "movement-arrived" && it.unitId == "a" } && dx * dx + dy * dy >= 16900.0)
                    arrivedOutsideRange = true
            }
            val final = c.observe().units.single { it.unitId == "a" }.position
            assertTrue((final.x - targetX) * (final.x - targetX) + (final.y - 200.0) * (final.y - 200.0) < 16900.0,
                "Attack must reach the legal continuous range sliver, including late completion capture")
            assertTrue(fired)
            assertTrue(c.observe().units.single { it.unitId == "t" }.health!! < 700.0)
            assertTrue(!arrivedOutsideRange)
        } finally { c.close() }
    }

    @Test
    fun a_tank_ordered_to_attack_a_far_live_tower_really_approaches_and_damages_it() {
        val base = TurretTestContent.scenario(Position(420.0, 200.0))
        val tower = TurretTestContent.variant(base.unitDefinitions.getValue("c_turret_t1"), combat = null)
        val tank = base.unitDefinitions.getValue("c_tank")
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = listOf(UnitSpawn("a", "p1", tank.id, Position(100.0, 200.0), 90.0),
                UnitSpawn("t", "p2", tower.id, Position(420.0, 200.0), 270.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "t" to 11L), 12L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack-building", "p1", "a", "t", context.world)))
            var born = false
            var damaged = false
            for (step in 0L..699L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                born = born || frame.projectileEvents.any { it.kind == "born" && it.projectile.sourceUnitId == "a" }
                damaged = damaged || frame.units.single { it.unitId == "t" }.health!! < 700.0
            }
            assertTrue(context.observe().units.single { it.unitId == "a" }.position.x > 290.0,
                "The public Attack must approach from outside range, rather than require a preceding Move")
            assertTrue(born); assertTrue(damaged)
        } finally { context.close() }
    }

    @Test
    fun loaded_tower_uses_strict_original_range_and_real_41_damage_without_native_building_radius() {
        for (distance in listOf(165.0, 165.001, 164.999)) {
            val base = TurretTestContent.scenario(Position(200.0, 200.0))
            val tank = base.unitDefinitions.getValue("c_tank")
            val target = TurretTestContent.variant(tank, id = "target", combat = null,
                durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
            val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
                units = base.units + UnitSpawn("z", "p2", target.id, Position(200.0 + distance, 200.0), 0.0),
                combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 13L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val first = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(distance < 165.0, first.projectileEvents.any { it.kind == "born" })
                if (distance < 165.0) {
                    var impact = false
                    for (step in 1L..29L) {
                        val frame = context.advance(Step(step, 1.0, "sim-tick"))
                        impact = impact || frame.projectileEvents.any { it.kind == "impact" && it.damage == 41.0 }
                    }
                    assertTrue(impact); assertEquals(169.0, context.observe().units.single { it.unitId == "z" }.health)
                }
                assertEquals(700.0, context.observe().units.single { it.unitId == "t" }.health)
            } finally { context.close() }
        }
    }

    @Test
    fun real_recoil_uses_six_out_twelve_return_and_the_same_pivot_for_moving_target_aim() {
        val base = TurretTestContent.scenario(Position(300.0, 200.0))
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = TurretTestContent.variant(tank, id = "target", radius = 1.0, combat = null,
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath),
            movement = tank.movement.copy(moveSpeed = 1.0, moveAccelerationSpeed = 1.0,
                moveDecelerationSpeed = 1.0, maxTurnSpeed = 360.0, turnAcceleration = 0.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("z", "p2", target.id, Position(420.0, 200.0), 180.0),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 13L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(305.9166666666667, context.observe().combat!!.weapons.single().pivot!!.x, 1e-10)
            for (step in 1L..17L) {
                val frame = context.advance(Step(step, 1.0, "sim-tick"))
                val expected = mapOf(5L to 305.5, 11L to 305.75, 17L to 306.0)[step]
                if (expected != null) assertEquals(expected, frame.combat!!.weapons.single().pivot!!.x, 1e-10)
            }
            // Wait for a second real shot, then move the target by one world unit per tick.
            for (step in 18L..30L) context.advance(Step(step, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("cross", "p2", "z", Position(420.0, 260.0), context.world)))
            val before = context.advance(Step(31, 1.0, "sim-tick"))
            assertEquals(Position(420.0, 201.0), before.units.single { it.unitId == "z" }.position)
            assertEquals(305.8333333333333, before.combat!!.weapons.single().pivot!!.x, 1e-10)
            val frame = context.advance(Step(32, 1.0, "sim-tick"))
            val expectedAngle = Math.toDegrees(kotlin.math.atan2(114.1666666666667, -1.0))
            assertEquals(expectedAngle, frame.units.single { it.unitId == "t" }.turretHeading, 1e-10,
                "Use the preceding recoil pivot, not the body or static mount center, for actual aim")
        } finally { context.close() }
    }

    @Test
    fun immobile_attack_retains_its_far_target_without_path_or_motion_then_fires_when_target_enters() {
        val base = TurretTestContent.scenario(Position(200.0, 200.0))
        val tank = base.unitDefinitions.getValue("c_tank")
        val target = TurretTestContent.variant(tank, id = "target", combat = null,
            durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (target.id to target),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + UnitSpawn("z", "p2", target.id, Position(450.0, 200.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 13L))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("far", "p1", "t", "z", context.world)))
            for (step in 0L..9L) {
                val view = context.advance(Step(step, 1.0, "sim-tick"))
                val tower = view.units.single { it.unitId == "t" }
                assertEquals("z", tower.attackTargetId)
                assertEquals(Position(200.0, 200.0), tower.position)
                assertEquals(null, tower.moveTarget)
                assertTrue(tower.pathPoints.isEmpty())
                assertTrue(view.projectileEvents.isEmpty())
            }
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty(), "An immobile attack must not search")
            assertEquals("immobile-unit", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("move-tower", "p1", "t", Position(250.0, 200.0), context.world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("enter", "p2", "z", Position(300.0, 200.0), context.world)))
            var fired = false
            var damaged = false
            for (step in 10L..400L) {
                val view = context.advance(Step(step, 1.0, "sim-tick"))
                val z = view.units.single { it.unitId == "z" }
                for (event in view.projectileEvents.filter { it.kind == "born" }) {
                    fired = true
                    assertEquals("t", event.projectile.sourceUnitId)
                    assertTrue(z.position.x < 365.0, "Only a strictly in-range target may be fired upon")
                }
                damaged = damaged || z.health!! < 210.0
                assertEquals(Position(200.0, 200.0), view.units.single { it.unitId == "t" }.position)
            }
            assertTrue(fired); assertTrue(damaged)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "t", context.world)))
            assertEquals(null, context.observe().units.single { it.unitId == "t" }.attackTargetId)
        } finally { context.close() }
    }

    @Test
    fun real_projectile_birth_uses_body_mount_and_turret_muzzle_in_four_directions() {
        val cases = listOf(
            Triple(0.0, Position(300.0, 100.0), Position(300.0, 172.0)),
            Triple(90.0, Position(400.0, 200.0), Position(328.0, 200.0)),
            Triple(180.0, Position(300.0, 300.0), Position(300.0, 228.0)),
            Triple(270.0, Position(200.0, 200.0), Position(272.0, 200.0)))
        for ((heading, targetPosition, expected) in cases) for (side in listOf(0.0, 4.0)) {
            val sideDelta = when (heading) {
                0.0 -> Position(side, 0.0)
                90.0 -> Position(0.0, side)
                180.0 -> Position(-side, 0.0)
                else -> Position(0.0, -side)
            }
            val base = TurretTestContent.scenario(Position(300.0, 200.0))
            val original = base.unitDefinitions.getValue("c_turret_t1")
            val tower = TurretTestContent.variant(original, combat = original.combat!!.copy(mount = TurretMount(side, 6.0)))
            val tank = base.unitDefinitions.getValue("c_tank")
            // Radius one isolates muzzle geometry from the separately tested target spread.
            val target = TurretTestContent.variant(tank, id = "target", radius = 1.0, combat = null,
                durability = DurabilityProfile(210.0, tank.combat!!.deadImagePath))
            val input = base.copy(unitDefinitions = base.unitDefinitions + (tower.id to tower) + (target.id to target),
                units = listOf(UnitSpawn("t", "p1", tower.id, Position(300.0, 200.0), heading),
                    UnitSpawn("z", "p2", target.id,
                        Position(targetPosition.x + sideDelta.x, targetPosition.y + sideDelta.y), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L), 13L))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val born = context.advance(Step(0L, 1.0, "sim-tick")).projectileEvents.single { it.kind == "born" }.projectile
                assertEquals(expected.x + sideDelta.x, born.position.x, 1e-9, "heading=$heading side=$side")
                assertEquals(expected.y + sideDelta.y, born.position.y, 1e-9, "heading=$heading side=$side")
                assertEquals("t", born.sourceUnitId)
                assertEquals(700.0, context.observe().units.single { it.unitId == "t" }.health)
            } finally { context.close() }
        }
    }
}
