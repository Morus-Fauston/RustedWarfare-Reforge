package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusLandDeathTest {
    @Test fun stationary_land_death_keeps_cargo_until_the_update_after_height_crosses_ground() = checkDeath(false)
    @Test fun moving_land_death_retains_inertia_and_uses_slow_fall_before_releasing_cargo() = checkDeath(true)

    private fun checkDeath(moving: Boolean) =
        WaterTestScene.withMap { map ->
            val base = NautilusScene.scenario(map)
            val killer = UnitDefinition("killer", "OwnedTestAttacker", 6.0, 6.0, 1,
                base.unitDefinitions.getValue("hoverTank").imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 2.0, .1, .1, 360.0, 0.0, false, false), emptySet(),
                combat = CombatProfile(100000.0, 300.0, 1000.0, 0.0, 360.0, 0.0,
                    ProjectileProfile(5000.0, 100.0, 1.0, instant = true), null, null, 1.0, recoil = WeaponRecoil(0.0)))
            val c = NautilusScene.open(base.copy(players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                unitDefinitions = base.unitDefinitions + ("killer" to killer), units = listOf(
                UnitSpawn("mother", "p1", "nautilusSubmarineLand", Position(200.0, 180.0), 90.0, 2.0),
                UnitSpawn("killer", "p2", "killer", Position(650.0, 180.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "killer" to 9L), 10L)))
            try {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "mother", "hoverTank", "a".repeat(32), c.world)))
                var step = 0L
                repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
                val childId = c.observe().units.single { it.carriedBy == "mother" }.unitId
                assertEquals("mother", c.observe().units.single { it.unitId == childId }.carriedBy)
                if (moving) assertIs<ActionResult.Accepted>(c.submit(MoveAction("mother-move", "p1", "mother", Position(350.0, 180.0), c.world)))
                assertIs<ActionResult.Accepted>(c.submit(MoveAction("approach", "p2", "killer", Position(490.0, 180.0), c.world)))
                while (c.observe().units.single { it.unitId == "mother" }.alive && step < 600)
                    c.advance(Step(step++, 1.0, "sim-tick"))
                val dead = c.observe().units.single { it.unitId == "mother" }
                assertFalse(dead.alive)
                assertEquals(2.0, dead.height)
                assertEquals(moving, dead.speed > 0.0)
                assertEquals(listOf(childId), dead.transport!!.passengerIds)
                val falling = c.advance(Step(step++, 1.0, "sim-tick")).units.single { it.unitId == "mother" }
                assertEquals((2f - (if (moving) .017f else .06f)).toDouble(), falling.height)
                assertEquals(moving, falling.position != dead.position)
                repeat(if (moving) 14 else 7) { c.advance(Step(step++, 1.0, "sim-tick")) }
                val crossed = c.observe().units.single { it.unitId == "mother" }
                assertTrue(crossed.height <= 0.0)
                assertEquals(listOf(childId), crossed.transport!!.passengerIds)
                c.advance(Step(step, 1.0, "sim-tick"))
                val landed = c.observe()
                assertTrue(landed.units.single { it.unitId == "mother" }.transport!!.passengerIds!!.isEmpty())
                assertNull(landed.units.single { it.unitId == childId }.carriedBy)
                assertFalse(landed.units.single { it.unitId == childId }.alive)
            } finally { c.close() }
        }
}
