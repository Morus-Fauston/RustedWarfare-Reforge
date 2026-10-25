package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import com.rustedwarfare.reforge.core.content.UnitDefinition

class OrdinaryCombatTest {
    @Test
    fun automatic_shot_keeps_the_cached_distance_when_the_plane_step_is_exactly_equal() {
        for ((speed, targetX, firstHit) in listOf(Triple(6.0, 136.0, false), Triple(6.01, 136.0, true),
            Triple(5.99, 136.0, false), Triple(5.0, 135.0, true), Triple(5.0, 136.0, false),
            Triple(6.2, 136.1, true), Triple(6.1, 136.2, false))) {
            val base = CombatTestContent.scenario()
            val original = base.unitDefinitions.getValue("c_tank")
            val tank = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
                original.totalFrames, original.imagePath, original.imageScale, original.movement,
                original.retainedUnconsumed, original.fogOfWarSightRange,
                checkNotNull(original.combat).let { it.copy(projectile = it.projectile.copy(speed = speed)) })
            val input = base.copy(unitDefinitions = mapOf("c_tank" to tank), units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(targetX, 210.0), heading = 90.0) else it
            })
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val first = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (firstHit) 185.0 else 210.0, first.units.single { it.unitId == "b" }.health,
                    "speed=$speed targetX=$targetX: only strict overshoot may clear the pre-plane cache")
                if (!firstHit) {
                    assertTrue(first.projectiles.any { it.sourceUnitId == "a" })
                    val second = context.advance(Step(1, 1.0, "sim-tick"))
                    assertEquals(185.0, second.units.single { it.unitId == "b" }.health)
                    assertTrue(second.projectiles.none { it.sourceUnitId == "a" })
                }
            } finally { context.close() }
        }
    }

    @Test
    fun ordinary_height_moves_before_testing_the_strict_remaining_height_boundary() {
        for ((height, hit) in listOf(3.0 to true, 8.0 to false, 7.99 to true, -3.0 to true, -8.0 to false, -7.99 to true, 0.0 to true)) {
            val base = CombatTestContent.scenario()
            val input = base.copy(units = base.units.map {
                if (it.unitId == "b") it.copy(position = Position(135.5, 210.0), height = height) else it
            })
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val observation = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(if (hit) 185.0 else 210.0, observation.units.single { it.unitId == "b" }.health,
                    "height=$height: compare the updated signed height, not the initial difference")
                if (!hit) assertTrue(observation.projectiles.any { it.sourceUnitId == "a" })
            } finally { context.close() }
        }
    }

    @Test
    fun distant_manual_order_keeps_a_still_qualified_nearby_automatic_weapon_target() {
        val base = CombatTestContent.scenario()
        val input = base.copy(units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        } + UnitSpawn("c", "p2", "c_tank", Position(510.0, 330.0), 270.0),
            combatContract = checkNotNull(base.combatContract).copy(unitObjectIds = mapOf("a" to 8L, "b" to 9L, "c" to 10L)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("far-order", "p1", "a", "c", context.world)))
            var observation = context.observe()
            for (step in 1L..75L) observation = context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(observation.projectiles.any { it.sourceUnitId == "a" && it.targetUnitId == "b" },
                "An out-of-range manual target must not silently impose hold fire on a qualified automatic target")
            assertTrue(observation.units.single { it.unitId == "a" }.attackTargetId == "c")
        } finally { context.close() }
    }

    @Test
    fun real_ordinary_tanks_fire_fly_damage_and_die_through_the_public_context() {
        val base = CombatTestContent.scenario()
        val input = base.copy(units = base.units.map {
            if (it.unitId == "b") it.copy(position = Position(220.0, 210.0)) else it
        })
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val initial = context.observe()
            val first = context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(first.events.any { it.kind == "projectile-fired" }, "Real c_tank must fire while in range and aligned")
            assertTrue(first.units.all { it.health == 210.0 }, "A non-instant shot must fly before damage")
            var damaged = false
            var died = false
            for (step in 1L..1000L) {
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                damaged = damaged || observation.units.any { checkNotNull(it.health) < 210.0 }
                died = died || observation.units.any { !it.alive }
                if (died) break
            }
            assertTrue(damaged, "Flying projectiles must apply direct damage")
            assertTrue(died, "Repeated ordinary hits must kill a tank")
            assertTrue(initial.units.all { it.alive && it.health == 210.0 }, "Previously published frames must remain immutable")
        } finally {
            context.close()
        }
    }
}
