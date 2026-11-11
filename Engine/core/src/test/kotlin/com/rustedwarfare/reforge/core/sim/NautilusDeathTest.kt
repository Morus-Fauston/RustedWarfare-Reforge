package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class NautilusDeathTest {
    @Test fun a_real_delayed_underwater_hit_cleans_the_shared_manifest_and_refunds_only_unfinished_work_before_passenger_death() = WaterTestScene.withMap { map ->
        val base = NautilusScene.scenario(map)
        val image = base.unitDefinitions.getValue("nautilusSubmarine").imagePath
        val shot = ProjectileProfile(5000.0, 100.0, 7.0)
        val named = NamedWeaponDefinition("1", "instrument", shot, TurretMount(), 10000.0, 0.0, 360.0, 0.0,
            null, 1.0, canAttackUnderwaterUnits = true, warmup = 300.0)
        val gun = UnitDefinition("instrument", "OwnedInstrument", 6.0, 6.0, 1, image, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 360.0, 0.0, shot, null, null, 1.0,
                namedWeapons = WeaponCatalog(listOf(named))))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (gun.id to gun),
            units = base.units + UnitSpawn("z-gun", "p2", gun.id, Position(650.0, 180.0), 270.0),
            combatContract = CombatContract(12345, -1, mapOf("mother" to 8L, "z-gun" to 9L), 10L))
        val c = NautilusScene.open(scene)
        try {
            for (index in 1..2) assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$index", "p1", "mother",
                "hoverTank", index.toString().padStart(32, '0'), c.world)))
            var step = 0L
            repeat(250) { c.advance(Step(step++, 1.0, "sim-tick")) }
            val passengerId = c.observe().units.single { it.unitId == "mother" }.transport!!.passengerIds!!.single()
            assertEquals(4100L, c.observePlayer(c.world, "p1").balance)
            while (c.observe().units.single { it.unitId == "mother" }.alive && step < 400)
                c.advance(Step(step++, 1.0, "sim-tick"))
            val dead = c.observe()
            val mother = dead.units.single { it.unitId == "mother" }
            val passenger = dead.units.single { it.unitId == passengerId }
            assertFalse(mother.alive)
            assertNull(passenger.carriedBy)
            assertEquals(-1.0, passenger.health)
            assertEquals(0, mother.transport!!.passengerCount)
            assertTrue(passenger.position.x < mother.position.x)
            assertEquals(4550L, c.observePlayer(c.world, "p1").balance)
            assertTrue(dead.economy!!.queuedProduction.getValue("mother").isEmpty())
            assertTrue(dead.events.none { it.kind == "unit-unloaded" })
            val following = c.advance(Step(step++, 1.0, "sim-tick"))
            assertFalse(following.units.single { it.unitId == passengerId }.alive)
            assertTrue(following.events.any { it.kind == "unit-died" && it.unitId == passengerId })
            val corpse = following.units.single { it.unitId == "mother" }
            assertNull(corpse.flightPose)
            assertEquals(0.0, corpse.speed)
            assertIs<CloseResult.Closed>(c.close())
            assertIs<InitResult.Accepted>(c.reopen(scene))
            assertEquals(0, c.observe().units.single { it.unitId == "mother" }.transport!!.passengerCount)
            assertEquals(5000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }
}
