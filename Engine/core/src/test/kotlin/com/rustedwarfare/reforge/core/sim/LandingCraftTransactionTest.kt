package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class LandingCraftTransactionTest {
    @Test fun a_later_real_shot_failure_rolls_back_the_manifest_relation_and_scheduled_boarding_before_retry() = WaterTestScene.withMap { map ->
        val base = LandingCraftScene.scenario(map)
        val shot = ProjectileProfile(1.0, 100.0, 2.0)
        val weapon = NamedWeaponDefinition("gun", "probe", shot, TurretMount(), 10000.0, 0.0, 0.0, 0.0, null, 1.0)
        val gun = UnitDefinition("gun", "OwnedProbe", 6.0, 6.0, 1, base.unitDefinitions.getValue("builder").imagePath, 1.0,
            MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
            combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 0.0, 0.0, shot, null, null, 1.0,
                namedWeapons = WeaponCatalog(listOf(weapon, weapon.copy(name = "gun2")))))
        val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, gun.imagePath, 1.0, gun.movement,
            emptySet(), durability = DurabilityProfile(1000.0, null))
        val input = base.copy(unitDefinitions = base.unitDefinitions + mapOf("gun" to gun, "witness" to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = listOf(UnitSpawn("a", "p1", "builder", Position(200.0, 150.0), 90.0),
                UnitSpawn("craft", "p1", "hovercraft", Position(190.0, 150.0), 90.0),
                UnitSpawn("gun", "p2", "gun", Position(290.0, 150.0), 270.0),
                UnitSpawn("witness", "p1", "witness", Position(290.0, 250.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("a" to 8L, "craft" to 9L, "gun" to 10L, "witness" to 11L), Long.MAX_VALUE - 1))
        val contexts = List(2) { LandingCraftScene.open(input) }
        try {
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                LoadAction("board", "p1", "a", "craft", LoadDirection.INTO, context.world, 0L)))
            val failed = contexts.first()
            val before = failed.observe()
            val owner = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(owner, failed.observePlayer(failed.world, "p1"))
            assertNull(failed.observe().units.single { it.unitId == "a" }.carriedBy)
            assertEquals(emptyList(), failed.observe().units.single { it.unitId == "craft" }.transport!!.passengerIds)
            for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                AttackAction("correct", "p2", "gun", "witness", context.world)))
            for (step in 0L..5L) {
                val expected = contexts.last().advance(Step(step, 1.0, "sim-tick"))
                val actual = failed.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units)
                assertEquals(expected.events, actual.events)
                assertEquals(expected.actionResults, actual.actionResults)
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.projectiles, actual.projectiles)
            }
            assertEquals("craft", failed.observe().units.single { it.unitId == "a" }.carriedBy)
        } finally { contexts.forEach { it.close() } }
    }
}
