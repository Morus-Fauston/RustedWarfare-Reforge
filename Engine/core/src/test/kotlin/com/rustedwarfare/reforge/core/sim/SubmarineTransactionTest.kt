package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class SubmarineTransactionTest {
    @Test fun late_object_id_overflow_rolls_back_scheduled_intent_depth_movement_shot_and_public_history_before_retry() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val sub = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value.units.getValue("attackSubmarine")
            val shot = ProjectileProfile(1.0, 100.0, 2.0)
            val gun = NamedWeaponDefinition("1", "instrument", shot, TurretMount(), 10000.0, 0.0, 0.0, 0.0,
                null, 1.0, canAttackUnderwaterUnits = true)
            val instrument = UnitDefinition("instrument", "OwnedInstrument", 6.0, 6.0, 1, sub.imagePath, 1.0,
                MovementProfile(MovementType.HOVER, 0.0, 0.0, 0.0, 0.0, 0.0, false, false), emptySet(),
                combat = CombatProfile(1000.0, 200.0, 10000.0, 0.0, 0.0, 0.0, shot, null, null, 1.0,
                    namedWeapons = WeaponCatalog(listOf(gun))))
            val witness = UnitDefinition("witness", "OwnedWitness", 1.0, 1.0, 1, sub.imagePath, 1.0,
                instrument.movement, emptySet(), durability = DurabilityProfile(1000.0, null))
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("sub" to sub, "instrument" to instrument, "witness" to witness),
                players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                units = listOf(UnitSpawn("sub", "p1", "sub", Position(500.0, 200.0), 90.0, -8.0),
                    UnitSpawn("witness", "p1", "witness", Position(700.0, 200.0), 0.0),
                    UnitSpawn("zgun", "p2", "instrument", Position(650.0, 200.0), 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, "witness" to 9L, "zgun" to 10L), Long.MAX_VALUE - 1))
            val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
            try {
                for (context in contexts) {
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("move", "p1", "sub", Position(550.0, 260.0), context.world)))
                    assertEquals(0L, assertIs<ActionResult.Accepted>(context.submit(
                        SubmarineDepthAction("surface", "p1", "sub", true, context.world, 0L))).queuedForStep)
                    assertEquals(false, context.observe().units.single { it.unitId == "sub" }.submarine!!.surfaceIntent)
                }
                val failed = contexts[0]
                val before = failed.observe()
                val owner = failed.observePlayer(failed.world, "p1")
                val enemy = failed.observePlayer(failed.world, "p2")
                assertFailsWith<ArithmeticException> { failed.advance(Step(0L, 1.0, "sim-tick")) }
                assertSame(before, failed.observe())
                assertSame(owner, failed.observePlayer(failed.world, "p1"))
                assertSame(enemy, failed.observePlayer(failed.world, "p2"))
                for (context in contexts) assertIs<ActionResult.Accepted>(context.submit(
                    AttackAction("correct", "p2", "zgun", "witness", context.world)))
                for (q in 0L..5L) {
                    val expected = contexts[1].advance(Step(q, 1.0, "sim-tick"))
                    val actual = failed.advance(Step(q, 1.0, "sim-tick"))
                    assertEquals(expected.units, actual.units)
                    assertEquals(expected.projectiles, actual.projectiles)
                    assertEquals(expected.combat, actual.combat)
                    assertEquals(expected.events, actual.events)
                    assertEquals(expected.actionResults, actual.actionResults)
                    assertEquals(contexts[1].observePlayer(contexts[1].world, "p1").projectiles,
                        failed.observePlayer(failed.world, "p1").projectiles)
                }
                val moved = failed.observe().units.single { it.unitId == "sub" }
                assertTrue(moved.height > -8.0)
                assertEquals(true, moved.submarine!!.surfaceIntent)
                assertTrue(moved.submarine.verticalSpeed!! > 0f)
                val oldWorld = failed.world
                failed.close()
                assertIs<InitResult.Accepted>(failed.reopen(input))
                assertEquals(false, failed.observe().units.single { it.unitId == "sub" }.submarine!!.surfaceIntent)
                assertEquals("stale-world", assertIs<ActionResult.Rejected>(failed.submit(
                    SubmarineDepthAction("stale", "p1", "sub", true, oldWorld))).error.code)
            } finally { contexts.forEach { it.close() } }
        } }
}
