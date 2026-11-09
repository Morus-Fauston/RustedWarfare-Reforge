package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class LandingCraftDeathTest {
    @Test fun carrier_combat_death_releases_negative_health_passengers_then_their_normal_update_processes_death() = WaterTestScene.withMap { map ->
        val base = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a-passenger", "p1", "builder", Position(200.0, 150.0), 90.0),
            UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
            UnitSpawn("shooter", "p2", "c_tank", Position(290.0, 150.0), 270.0)))
        val original = base.unitDefinitions.getValue("c_tank")
        // Fixed host input, before initialization. Damage is delivered through the public combat lifecycle.
        val shooter = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, original.retainedUnconsumed,
            original.fogOfWarSightRange, original.combat!!.let {
                it.copy(shootDelay = 100000.0, projectile = it.projectile.copy(directDamage = 500.0)) })
        val context = LandingCraftScene.open(base.copy(unitDefinitions = base.unitDefinitions + ("c_tank" to shooter)))
        try {
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("load", "p1", "a-passenger", "craft", LoadDirection.INTO, context.world)))
            var step = 0L
            while (context.observe().units.single { it.unitId == "craft" }.alive && step < 300L)
                context.advance(Step(step++, 1.0, "sim-tick"))
            val dead = context.observe()
            assertFalse(dead.units.single { it.unitId == "craft" }.alive)
            val released = dead.units.single { it.unitId == "a-passenger" }
            assertNull(released.carriedBy)
            assertEquals(-1.0, released.health)
            assertEquals(0, dead.units.single { it.unitId == "craft" }.transport!!.passengerCount)
            assertTrue(released.position.x < 190.0)
            val following = context.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(following.units.single { it.unitId == "a-passenger" }.alive)
            assertTrue(following.events.any { it.kind == "unit-died" && it.unitId == "a-passenger" })
            assertTrue(following.units.any { it.unitId == "a-passenger" }, "Native builder corpse remains a world object")
            assertIs<CloseResult.Closed>(context.close())
            assertIs<InitResult.Accepted>(context.reopen(base))
            assertNull(context.observe().units.single { it.unitId == "a-passenger" }.carriedBy)
            assertEquals(0, context.observe().units.single { it.unitId == "craft" }.transport!!.passengerCount)
        } finally { context.close() }
    }
}
