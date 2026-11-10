package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

class MechEngineerTransactionTest {
    private fun workValues(o: Observation) = o.construction?.let {
        listOf(it.clock, it.buildings, it.workers, it.nonces)
    }

    private fun scene(): Scenario {
        val root = Path.of(checkNotNull(System.getProperty("public.fixtures.dir")))
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            root.resolve("mech-engineer-production/ini/production-package"))).value
        val tank = CombatTestContent.scenario().unitDefinitions.getValue("c_tank")
        return CombatTestContent.scenario().copy(unitDefinitions = pack.units + (tank.id to tank),
            players = listOf(Player("p1", PlayerControl.LOCAL, "one"), Player("p2", PlayerControl.LOCAL, "two")),
            units = listOf(UnitSpawn("engineer", "p1", "mechEngineer", Position(110.0, 210.0), 90.0),
                UnitSpawn("z-enemy", "p2", tank.id, Position(210.0, 210.0), 270.0),
                UnitSpawn("decoy", "p1", "builder", Position(210.0, 310.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "z-enemy" to 9L, "decoy" to 10L), Long.MAX_VALUE - 1),
            economyContract = EconomyContract(mapOf("p1" to 16000L, "p2" to 0L)))
    }

    @Test fun a_later_real_shot_overflow_rolls_back_engineer_self_healing_income_and_work_timers_before_retry() {
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene())).context }
        try {
            var step = 0L
            val failed = contexts[0]
            while (true) {
                val before = failed.observe()
                val player = failed.observePlayer(failed.world, "p1")
                try { failed.advance(Step(step, 1.0, "sim-tick")) }
                catch (error: ArithmeticException) {
                    assertTrue(step in 1..200)
                    assertSame(before, failed.observe())
                    assertSame(player, failed.observePlayer(failed.world, "p1"))
                    assertTrue(before.units.single { it.unitId == "engineer" }.health!! < 2000.0)
                    assertFailsWith<ArithmeticException> { failed.advance(Step(step, 1.0, "sim-tick")) }
                    assertSame(before, failed.observe())
                    break
                }
                contexts[1].advance(Step(step++, 1.0, "sim-tick"))
                assertTrue(step < 201, "The real second shot must exercise object allocation overflow")
            }
            val injured = failed.observe().units.single { it.unitId == "engineer" }.health!!
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(AttackAction("turn-away", "p2", "z-enemy", "decoy", c.world)))
            }
            repeat(10) {
                val expected = contexts[1].advance(Step(step, 1.0, "sim-tick"))
                val actual = failed.advance(Step(step++, 1.0, "sim-tick"))
                assertEquals(expected.units, actual.units)
                assertEquals(expected.economy?.preciseBalances, actual.economy?.preciseBalances)
                assertEquals(workValues(expected), workValues(actual))
                assertEquals(expected.combat, actual.combat)
                assertEquals(expected.projectiles, actual.projectiles)
                assertEquals(expected.events, actual.events)
            }
            assertTrue(failed.observe().units.single { it.unitId == "engineer" }.health!! > injured)
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun mobile_creation_rolls_back_payment_nonce_and_object_registration_if_a_later_enemy_shot_overflows() {
        val base = scene()
        val tank = base.unitDefinitions.getValue("c_tank")
        val instantAim = UpgradeTestContent.variant(tank, combat = tank.combat!!.copy(turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0))
        val input = base.copy(unitDefinitions = base.unitDefinitions + (tank.id to instantAim),
            units = base.units.filter { it.unitId != "decoy" }.map { if (it.unitId == "z-enemy") it.copy(position = Position(310.0, 210.0)) else it },
            combatContract = CombatContract(12345, -1, mapOf("engineer" to 8L, "z-enemy" to 9L), Long.MAX_VALUE - 1))
        val contexts = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context }
        try {
            val nonce = "f".repeat(32)
            for (c in contexts) assertIs<ActionResult.Accepted>(c.submit(BuildAction("build", "p1", "engineer", "mechGun",
                Position(210.0, 210.0), nonce, c.world)))
            val failed = contexts[0]
            val before = failed.observe()
            val player = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe())
            assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertTrue(failed.observe().construction!!.nonces.isEmpty())
            assertEquals(16000L, failed.observePlayer(failed.world, "p1").balance)
            val creations = mutableListOf<Int>()
            for (c in contexts) {
                assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "engineer", c.world)))
                assertIs<ActionResult.Accepted>(c.submit(BuildAction("retry", "p1", "engineer", "mechGun",
                    Position(170.0, 150.0), nonce, c.world)))
                var created = 0
                repeat(30) { step -> created += c.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    .events.count { it.kind == "construction-created" } }
                creations += created
            }
            assertEquals(contexts[1].observe().units, failed.observe().units)
            assertEquals(contexts[1].observe().economy?.preciseBalances, failed.observe().economy?.preciseBalances)
            assertEquals(workValues(contexts[1].observe()), workValues(failed.observe()))
            assertEquals(contexts[1].observe().combat, failed.observe().combat)
            assertEquals(15400L, failed.observePlayer(failed.world, "p1").balance)
            assertEquals(listOf(1, 1), creations)
        } finally { contexts.forEach { it.close() } }
    }
}
