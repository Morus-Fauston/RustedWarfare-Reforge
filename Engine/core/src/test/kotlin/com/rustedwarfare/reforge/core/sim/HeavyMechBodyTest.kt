package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class HeavyMechBodyTest {
    @Test fun the_four_leg_body_follows_motion_and_rotates_all_turrets_without_locking_to_the_small_laser() {
        val scenario = HeavyMechTestScene.scenario(unit = HeavyMechTestScene.declared()).copy(
            combatContract = CombatContract(12345, -1, mapOf("heavy" to 8L), 9L))
        val context = HeavyMechTestScene.open(scenario)
        try {
            val initial = context.observePlayer(context.world, "p1")
            assertEquals(0.0, assertNotNull(initial.units.single().mechPose).forwardHeading)
            assertEquals(listOf(270.0, 0.0, 0.0, 0.0), initial.weapons.map { it.turretHeading })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east", "p1", "heavy", Position(600.0, 200.0), context.world)))
            for ((q, bodyHeading) in listOf(.5, 1.5).withIndex()) {
                context.advance(Step(q.toLong(), 1.0, "sim-tick"))
                val view = context.observePlayer(context.world, "p1")
                val body = assertNotNull(view.units.single().mechPose)
                assertEquals(bodyHeading, body.forwardHeading, 1e-10)
                assertEquals(if (q == 0) .5 else 1.0, body.angularVelocity, 1e-10)
                assertEquals(bodyHeading, view.units.single().heading, 1e-10)
                assertEquals(listOf("smallLaser", "1", "2", "3"), view.weapons.map { it.weaponName })
                for ((weapon, expected) in view.weapons.zip(listOf(270.0 + bodyHeading, bodyHeading, bodyHeading, bodyHeading))) {
                    assertEquals(expected, weapon.turretHeading, 1e-10)
                }
                assertNotEquals(view.weapons.first().turretHeading, body.forwardHeading)
            }
            assertEquals(listOf(270.0, 0.0, 0.0, 0.0), initial.weapons.map { it.turretHeading })
            assertEquals(0.0, initial.units.single().mechPose!!.forwardHeading)
        } finally { context.close() }
    }
}
