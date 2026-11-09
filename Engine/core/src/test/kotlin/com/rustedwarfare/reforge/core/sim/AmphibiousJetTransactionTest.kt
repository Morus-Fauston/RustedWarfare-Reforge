package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AmphibiousJetTransactionTest {
    @Test fun late_shot_allocation_failure_rolls_back_scheduled_intent_altitude_and_movement_before_a_normal_retry() =
        WaterTestScene.withMap { map ->
            val base = AmphibiousJetScene.scenario()
            val jet = base.unitDefinitions.getValue("amphibiousJet")
            val shot = ProjectileProfile(1.0, 100.0, 2.0)
            val weapon = NamedWeaponDefinition("gun", "probe", shot, TurretMount(), 10000.0, 0.0, 0.0, 0.0,
                null, 1.0, canAttackFlyingUnits = true, canAttackUnderwaterUnits = true)
            val gun = UnitDefinition("gun", "OwnedProbe", 6.0, 6.0, 1, jet.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 0.0, 0.0, shot, null, null, 1.0,
                    namedWeapons = WeaponCatalog(listOf(weapon, weapon.copy(name = "gun2")))))
            val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, jet.imagePath, 1.0,
                gun.movement, emptySet(), durability = DurabilityProfile(1000.0, null))
            val input = base.copy(map = map, unitDefinitions = base.unitDefinitions + mapOf("gun" to gun, "witness" to witness),
                players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
                units = listOf(UnitSpawn("jet", "p1", "amphibiousJet", Position(500.0, 200.0), 90.0),
                    UnitSpawn("gun", "p2", "gun", Position(650.0, 200.0), 270.0),
                    UnitSpawn("witness", "p1", "witness", Position(650.0, 300.0), 0.0)),
                combatContract = CombatContract(12345, -1, mapOf("jet" to 8L, "gun" to 9L, "witness" to 10L), Long.MAX_VALUE - 1))
            val contexts = List(2) { AmphibiousJetScene.open(input) }
            try {
                for (context in contexts) {
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "jet", Position(550.0, 200.0), context.world)))
                    assertIs<ActionResult.Accepted>(context.submit(AmphibiousFlightAction("dive", "p1", "jet", false, context.world, 0L)))
                }
                val failed = contexts[0]
                val before = failed.observe()
                val player = failed.observePlayer(failed.world, "p1")
                val enemy = failed.observePlayer(failed.world, "p2")
                assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
                assertSame(before, failed.observe())
                assertSame(player, failed.observePlayer(failed.world, "p1"))
                assertSame(enemy, failed.observePlayer(failed.world, "p2"))
                for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                    AttackAction("correct", "p2", "gun", "witness", context.world)))
                for (q in 0L..5L) {
                    val expected = contexts[1].advance(Step(q, 1.0, "sim-tick"))
                    val actual = failed.advance(Step(q, 1.0, "sim-tick"))
                    assertEquals(expected.units, actual.units)
                    assertEquals(expected.combat, actual.combat)
                    assertEquals(expected.events, actual.events)
                    assertEquals(expected.actionResults, actual.actionResults)
                    assertEquals(expected.projectiles, actual.projectiles)
                }
                val moved = failed.observe().units.single { it.unitId == "jet" }
                assertTrue(moved.height < 0.0)
                assertEquals(false, moved.amphibiousJet?.flyingIntent)
                assertTrue(moved.position.x > 500.0)
                val oldWorld = failed.world
                failed.close()
                assertIs<InitResult.Accepted>(failed.reopen(input))
                assertEquals(true, failed.observe().units.single { it.unitId == "jet" }.amphibiousJet?.flyingIntent)
                assertEquals("stale-world", assertIs<ActionResult.Rejected>(failed.submit(
                    AmphibiousFlightAction("stale", "p1", "jet", false, oldWorld))).error.code)
            } finally { contexts.forEach { it.close() } }
        }
}
