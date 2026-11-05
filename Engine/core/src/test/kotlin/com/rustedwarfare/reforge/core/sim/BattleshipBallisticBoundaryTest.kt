package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class BattleshipBallisticBoundaryTest {
    /** Explicit gate instrument: speed1/apex0 and radius1 remove offset randomness; no shot is injected. */
    private fun scene(map: MapDefinition, distance: Double, height: Double): Scenario {
        val base = NavalBallisticScene.scenario(map)
        val source = base.unitDefinitions.getValue("good")
        val target = UnitDefinition(source.id, source.className, 1.0, 1.0, source.totalFrames,
            source.imagePath, source.imageScale, source.movement.copy(flight = FlightProfile(height, 0.0, 0.0, height)),
            source.retainedUnconsumed, 0, durability = source.durability, rotor = source.rotor)
        val ship = NavalBallisticScene.ship(base.unitDefinitions.getValue("ship")) {
            it.copy(speed = 1.0, acceleration = LinearProjectileAcceleration(1.0, .1), ballistic = CustomBallisticProfile(0f, 0f)) }
        return base.copy(unitDefinitions = mapOf("ship" to ship, "good" to target), units = base.units.map {
            if (it.unitId == "good") it.copy(position = Position(481.0 + distance, 200.0)) else it })
    }

    @Test fun the_exact_cached_four_hundred_plane_boundary_does_not_advance_height_until_the_next_step() = WaterTestScene.withMap { map ->
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map, 20.0, 8.0))).context
        try {
            context.submit(AttackAction("gate", "p1", "ship", "good", context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick")).projectiles.single()
            assertEquals(Position(481.0, 200.0), first.position)
            assertEquals(0.0, first.height)
            assertTrue(assertNotNull(first.ballistic).reachedApex)
            val boundary = context.advance(Step(1, 1.0, "sim-tick")).projectiles.single()
            assertEquals(482.0, boundary.position.x)
            assertEquals(0.0, boundary.height, "The cached distance is exactly20 squared; the moved position must not open the gate")
            val inside = context.advance(Step(2, 1.0, "sim-tick")).projectiles.single()
            assertEquals(483.0, inside.position.x)
            assertEquals(1.0, inside.height)
        } finally { context.close() }
    }

    @Test fun contact_keeps_the_previous_height_difference_and_requires_strictly_less_than_three() = WaterTestScene.withMap { map ->
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene(map, 6.0, 4.0))).context
        try {
            context.submit(AttackAction("contact", "p1", "ship", "good", context.world))
            context.advance(Step(0, 1.0, "sim-tick"))
            val planeBoundary = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(1.0, planeBoundary.projectiles.single().height)
            assertTrue(planeBoundary.projectileEvents.none { it.kind == "impact" })
            val heightBoundary = context.advance(Step(2, 1.0, "sim-tick"))
            assertEquals(2.0, heightBoundary.projectiles.single().height)
            assertTrue(heightBoundary.projectileEvents.none { it.kind == "impact" }, "The previous height difference is3 although the updated difference is2")
            val hit = context.advance(Step(3, 1.0, "sim-tick"))
            assertTrue(hit.projectiles.isEmpty())
            assertEquals(3.0, hit.projectileEvents.single { it.kind == "impact" }.projectile.height)
            assertEquals(950.0, hit.units.single { it.unitId == "good" }.health)
        } finally { context.close() }
    }
}
