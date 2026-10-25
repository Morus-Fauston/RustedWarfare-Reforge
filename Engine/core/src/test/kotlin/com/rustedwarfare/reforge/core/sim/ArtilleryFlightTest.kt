package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ArtilleryFlightTest {
    @Test fun public_shots_match_fixed_float_integration_actual_impacts_and_strict_local_gates() {
        data class Case(val name: String, val distance: Float, val x: Int, val y: Int, val z: Int,
            val cached: Float, val kind: String? = null, val life: Float = 240f, val height: Float = 0f,
            val vertical: Float = 1.2f, val gravity: Float = .18f, val trueGravity: Float = 0f, val w: Float = 1.2f)
        // Fixed flight-oracle-01: independent qualified lookup02 + flight03 arithmetic, not product helpers.
        for (case in listOf(
            Case("far", 100f, 0x43808000, 0x43160065, 0x3f7aacda, 10000f),
            Case("strict-distance", 6f, 0x43af8000, 0x43160065, 0x3eae147a, 36f),
            Case("equal-step", 4f, 0x43b08000, 0x43160065, 0, 16f, "impact"),
            Case("overstep", 3f, 0x43b08000, 0x4316004b, 0, 0f, "impact"),
            Case("actual-impact", 5f, 0x43b00000, 0x43160065, 0x3e50e560, 25f, "impact"),
            Case("height-block", 4f, 0x43b08000, 0x43160065, 0x418828f6, 16f, height = 20f),
            Case("expiry", 100f, 0x43808000, 0x43160065, 0x3f7aacda, 10000f, "expired", life = .5f),
            Case("expiry-hit", 3f, 0x43b08000, 0x4316004b, 0, 0f, "impact", life = .5f),
            Case("negative-height", 100f, 0x43808000, 0x43160065, 0xbf449ba5.toInt(), 10000f, height = -2f),
            Case("true-gravity", 100f, 0x43808000, 0x43160065, 0x3f7aacda, 10000f, trueGravity = .2f, w = 1f),
            Case("strict-height", 3f, 0x43b08000, 0x4316004b, 0x40400000, 0f, height = 7f, vertical = 0f, gravity = 0f, w = 0f),
            Case("below-height", 3f, 0x43b08000, 0x4316004b, 0x403ffffe, 0f, "impact",
                height = Math.nextDown(7f), vertical = 0f, gravity = 0f, w = 0f)
        )) {
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(GroundTestContent.scene(case.distance,
                case.life, case.height, case.vertical, case.gravity, case.trueGravity))).context
            try {
                val result = c.advance(Step(0, 1.0, "sim-tick"))
                val shot = if (case.kind == null) assertNotNull(result.projectiles.singleOrNull(), case.name)
                    else assertNotNull(result.projectileEvents.singleOrNull { it.kind == case.kind }, case.name).projectile
                assertEquals(case.x, shot.position.x.toFloat().toRawBits(), "${case.name}: x")
                assertEquals(case.y, shot.position.y.toFloat().toRawBits(), "${case.name}: y")
                assertEquals(case.z, shot.height.toFloat().toRawBits(), "${case.name}: z")
                val ground = assertNotNull(shot.ground)
                assertEquals(case.cached, ground.distanceSquared, case.name)
                assertEquals(case.w, ground.verticalSpeed, case.name)
                assertEquals(if (case.life < 1f) 0.0 else 239.0, shot.remainingLife, case.name)
                assertEquals(1f, shot.age)
                assertTrue(result.projectileEvents.none { it.kind == "target-ended" }, case.name)
                if (case.kind != null) {
                    assertTrue(result.projectiles.isEmpty(), case.name)
                    assertEquals(1, result.projectileEvents.count { it.kind != "born" }, case.name)
                    assertNull(result.projectileEvents.single { it.kind == case.kind }.damage)
                }
            } finally { c.close() }
        }
    }

    @Test fun ground_shot_keeps_its_fixed_destination_after_the_original_target_really_dies() {
        val base = GroundTestContent.scene()
        val dummy = base.unitDefinitions.getValue("dummy")
        val input = base.copy(unitDefinitions = base.unitDefinitions + (dummy.id to UpgradeTestContent.variant(dummy,
            durability = dummy.durability!!.copy(maxHp = 25.0))),
            units = base.units + UnitSpawn("a", "p1", "c_tank", Position(300.0, 150.0), 90.0),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L, "a" to 13L), 15L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val first = c.advance(Step(0, 1.0, "sim-tick"))
            val born = first.projectileEvents.single { it.kind == "born" && it.projectile.ground != null }.projectile
            val destination = born.ground!!.targetPosition
            var continuedAfterDeath = false
            var impact = false
            for (step in 1L..60L) {
                val result = c.advance(Step(step, 1.0, "sim-tick"))
                assertTrue(result.projectileEvents.none { it.projectile.objectId == born.objectId && it.kind == "target-ended" })
                result.projectiles.singleOrNull { it.objectId == born.objectId }?.let {
                    assertEquals(destination, it.ground!!.targetPosition)
                    assertNull(it.targetUnitId)
                    if (!result.units.first { u -> u.unitId == "z" }.alive) continuedAfterDeath = true
                }
                if (result.projectileEvents.any { it.projectile.objectId == born.objectId && it.kind == "impact" }) {
                    impact = true
                    break
                }
            }
            assertTrue(continuedAfterDeath)
            assertTrue(impact)
        } finally { c.close() }
    }
}
