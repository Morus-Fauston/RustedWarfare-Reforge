package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetMountTest {
    @Test fun actual_muzzle_follows_the_body_turret_basis_when_side_aim_differs() =
        withJetTarget(targetPosition = Position(500.0, 120.0)) { context ->
            for (q in 0L..40L) context.advance(Step(q, 1.0, "sim-tick"))
            val row = context.observe()
            val states = assertNotNull(row.combat).weapons.filter { it.unitId == "jet" }
            val body = assertNotNull(states.single { it.weaponName == "body" }.turretHeading)
            assertEquals(90.0, row.units.single { it.unitId == "jet" }.heading)
            assertTrue(body < 1.0 || body > 359.0)
            for (state in states.filter { it.weaponName != "body" }) {
                val pivot = assertNotNull(state.pivot)
                val muzzle = assertNotNull(state.muzzle)
                assertEquals(5.0 * kotlin.math.sin(Math.toRadians(body)), muzzle.x - pivot.x, 1e-9)
                assertEquals(-5.0 * kotlin.math.cos(Math.toRadians(body)), muzzle.y - pivot.y, 1e-9)
            }
        }

    @Test fun side_mounts_fold_from_twelve_to_seven_and_from_seven_to_two_forward() =
        WaterTestScene.withMap { map ->
            val context = AmphibiousJetScene.open(AmphibiousJetScene.scenario().copy(map = map,
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L), 9L)))
            try {
                fun pivot(row: Observation, name: String) = assertNotNull(row.combat).weapons.single { it.weaponName == name }.pivot
                assertEquals(Position(507.0, 188.0), pivot(context.observe(), "left"))
                assertEquals(Position(507.0, 212.0), pivot(context.observe(), "right"))
                assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world)))
                for (q in 0L..300L) context.advance(Step(q, 1.0, "sim-tick"))
                assertEquals(Position(502.0, 193.0), pivot(context.observe(), "left"))
                assertEquals(Position(502.0, 207.0), pivot(context.observe(), "right"))
                assertEquals(Position(500.0, 200.0), pivot(context.observe(), "body"))
            } finally { context.close() }
        }
}
