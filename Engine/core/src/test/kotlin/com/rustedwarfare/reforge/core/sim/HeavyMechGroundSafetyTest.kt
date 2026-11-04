package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HeavyMechGroundSafetyTest {
    @Test fun four_leg_land_sliding_routes_around_a_real_friendly_factory_and_keeps_its_full_collision_circle_on_the_map() {
        val base = HeavyMechFactoryScene.scenario()
        val context = HeavyMechTestScene.open(base.copy(
            units = listOf(UnitSpawn("heavy", "p1", "mechHeavyMissile", Position(200.0, 200.0), 90.0),
                UnitSpawn("factory", "p1", "mechFactory", Position(350.0, 200.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L, "factory" to 9L), 10L)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("around-factory", "p1", "heavy", Position(600.0, 200.0), context.world)))
            var detoured = false
            for (q in 0L..1399L) {
                val unit = context.advance(Step(q, 1.0, "sim-tick")).units.single { it.unitId == "heavy" }
                val p = unit.position
                // Literal tile rectangle for T1 footprint[-1,-1,1,1], anchor cell(17,10), tile20.
                assertFalse(p.x >= 320.0 && p.x < 380.0 && p.y >= 180.0 && p.y < 240.0,
                    "q$q: LAND center enters the factory's blocked cells: $p")
                assertTrue(p.x in 22.0..778.0 && p.y in 22.0..578.0)
                assertNull(unit.flightPose)
                assertEquals(4, unit.mechPose!!.legs.size)
                detoured = detoured || p.y < 180.0 || p.y >= 240.0
            }
            assertTrue(detoured)
            assertTrue(context.observe().units.single { it.unitId == "heavy" }.position.x > 550.0)
            assertTrue(context.observe().units.single { it.unitId == "factory" }.alive)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("outside-circle", "p1", "heavy", Position(5.0, 200.0), context.world)))
            for (q in 1400L..2399L) {
                val p = context.advance(Step(q, 1.0, "sim-tick")).units.single { it.unitId == "heavy" }.position
                assertTrue(p.x in 22.0..778.0 && p.y in 22.0..578.0)
            }
        } finally { context.close() }
    }

    @Test fun retarget_preserves_inertia_and_unauthorized_nonfinite_and_stale_actions_cannot_change_the_fresh_pose() {
        val scene = HeavyMechTestScene.scenario(heading = 90.0).copy(
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)))
        val context = HeavyMechTestScene.open(scene)
        try {
            val original = context.observe().units.single()
            val oldWorld = context.world
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "heavy", Position(600.0, 200.0), context.world)))
            for (q in 0L..4L) context.advance(Step(q, 1.0, "sim-tick"))
            val beforeTurn = context.observe().units.single()
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("west", "p1", "heavy", Position(80.0, 200.0), context.world)))
            val firstTurn = context.advance(Step(5, 1.0, "sim-tick")).units.single()
            assertTrue(firstTurn.position.x > beforeTurn.position.x, "Changing the goal must not snap the sliding velocity")
            for (q in 6L..40L) context.advance(Step(q, 1.0, "sim-tick"))
            val turned = context.observe().units.single()
            assertTrue(turned.position.x < beforeTurn.position.x)
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("foreign", "p2", "heavy", Position(600.0, 400.0), context.world)))
            assertEquals("invalid-target", assertIs<ActionResult.Rejected>(context.submit(
                MoveAction("nan", "p1", "heavy", Position(Double.NaN, 200.0), context.world))).error.code)
            assertEquals(turned.position, context.observe().units.single().position)
            assertEquals(turned.mechPose, context.observe().units.single().mechPose)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(scene))
            assertIs<ActionResult.Rejected>(context.submit(MoveAction("stale", "p1", "heavy", Position(600.0, 200.0), oldWorld)))
            val fresh = context.observe().units.single()
            assertEquals(original.position, fresh.position)
            assertEquals(original.mechPose, fresh.mechPose)
            assertEquals(0.0, fresh.speed)
            assertNull(fresh.moveTarget)
        } finally { context.close() }
    }
}
