package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class AmphibiousJetTurretTest {
    @Test fun native_turret_uses_point_three_eight_for_braking_independently_of_point_three_five_acceleration() =
        withJetTarget(targetPosition = Position(500.0, 120.0)) { context ->
            // Fixed native recurrence for a 90-degree turn: eleven .35 ramps, then fifteen steps at 4.
            for (q in 0L..25L) context.advance(Step(q, 1.0, "sim-tick"))
            val body = assertNotNull(context.observe().combat).weapons.single { it.unitId == "jet" && it.weaponName == "body" }
            assertEquals(6.9, assertNotNull(body.turretHeading), 1e-4)
            assertEquals(-4.0, body.angularVelocity, 1e-6)
            val next = context.advance(Step(26, 1.0, "sim-tick"))
            val braking = assertNotNull(next.combat).weapons.single { it.unitId == "jet" && it.weaponName == "body" }
            assertEquals(-3.62, braking.angularVelocity, 1e-6)
            assertEquals(3.28, assertNotNull(braking.turretHeading), 1e-4)
        }
}
