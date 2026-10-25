package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class NativeBuildingHitTest {
    private fun boundary(native: Boolean, shift: Double) {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(312.0 + shift, native))).context
        val limit = if (native) 24.0 else 6.0
        val expectedDistance = limit + shift
        try {
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("boundary", "p1", "a", "f", context.world)))
            for (step in 0L..60L) {
                val before = context.observe()
                val projectile = before.projectiles.singleOrNull { it.sourceUnitId == "a" && it.targetUnitId == "f" }
                val target = before.units.single { it.unitId == "f" }
                val d2 = projectile?.let {
                    val dx = target.position.x + it.offset.x - it.position.x
                    val dy = target.position.y + it.offset.y - it.position.y
                    dx * dx + dy * dy
                }
                val after = context.advance(Step(step, 1.0, "sim-tick"))
                if (projectile != null && d2 == expectedDistance * expectedDistance) {
                    assertEquals(Position(if (native) -8.0 else 4.0, 0.0), projectile.offset)
                    assertTrue(d2 > 25.0, "Boundary probe itself must not use step5 overshoot clearing")
                    val hit = shift < 0.0
                    val hp = after.units.single { it.unitId == "f" }.health
                    println("public-hit-boundary native=$native step=$step prePlaneDistance=$expectedDistance hpBefore=${target.health} hpAfter=$hp hitExpected=$hit")
                    assertEquals(if (hit) checkNotNull(target.health) - 25.0 else target.health, hp)
                    assertEquals(hit, after.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == projectile.objectId })
                    assertEquals(!hit, after.projectiles.any { it.objectId == projectile.objectId })
                    if (!hit) {
                        val next = context.advance(Step(step + 1, 1.0, "sim-tick"))
                        assertEquals(checkNotNull(target.health) - 25.0, next.units.single { it.unitId == "f" }.health)
                        assertTrue(next.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == projectile.objectId })
                    }
                    return
                }
            }
            fail("No actual projectile reached the exact $expectedDistance public boundary fixture")
        } finally { context.close() }
    }

    @Test
    fun native_24_exact_and_two_sides_use_strict_pre_plane_gate_from_real_public_projectiles() {
        for (shift in listOf(-.125, 0.0, .125)) boundary(native = true, shift)
    }

    @Test
    fun ordinary_custom_tank_still_uses_strict_6_exact_and_two_sides() {
        for (shift in listOf(-.125, 0.0, .125)) boundary(native = false, shift)
    }

    private fun scene(factoryX: Double = 312.0, native: Boolean = true): Scenario {
        val base = BuildingTestContent.scenario()
        return base.copy(units = base.units.map { unit -> when (unit.unitId) {
            "a" -> unit.copy(position = Position(190.0, 210.0))
            "f" -> unit.copy(ownerPlayerId = "p2", typeId = if (native) "landFactory" else "c_tank", position = Position(factoryX, 210.0), heading = 270.0)
            else -> unit
        } }, combatContract = base.combatContract!!.copy(worldSeed = 0))
    }

    @Test
    fun actual_factory_loader_and_public_attack_hit_on_first_uncut_pre_plane_distance_inside_native_gate() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context
        try {
            assertEquals(1200.0, context.observe().units.single { it.unitId == "f" }.health)
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("native-hit", "p1", "a", "f", context.world)))
            for (step in 0L..60L) {
                val before = context.observe()
                val projectile = before.projectiles.singleOrNull { it.sourceUnitId == "a" && it.targetUnitId == "f" }
                val target = before.units.single { it.unitId == "f" }
                val d2 = projectile?.let {
                    val dx = target.position.x + it.offset.x - it.position.x
                    val dy = target.position.y + it.offset.y - it.position.y
                    dx * dx + dy * dy
                }
                val after = context.advance(Step(step, 1.0, "sim-tick"))
                if (projectile != null && d2 != null && d2 > 36.0 && d2 < 576.0) {
                    assertEquals(Position(-8.0, 0.0), projectile.offset, "Real deterministic manual aim offset")
                    assertTrue(d2 > 25.0, "The real speed5 projectile did not use plane overshoot clearing")
                    val hp = after.units.single { it.unitId == "f" }.health
                    println("native-first-contact step=$step prePlaneSquared=$d2 offset=${projectile.offset} oldHp=${target.health} newHp=$hp")
                    assertEquals(checkNotNull(target.health) - 25.0, hp)
                    assertTrue(after.projectileEvents.any { it.kind == "impact" && it.projectile.objectId == projectile.objectId && it.damage == 25.0 })
                    assertTrue(after.projectiles.none { it.objectId == projectile.objectId })
                    return
                }
            }
            fail("Expected an actual first projectile to enter the uncut (6,24) native contact window")
        } finally { context.close() }
    }
}
