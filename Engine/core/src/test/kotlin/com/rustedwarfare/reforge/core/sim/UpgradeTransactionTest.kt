package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class UpgradeTransactionTest {
    private fun pair(scene: Scenario) = List(2) { assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context }
    private fun same(a: Observation, b: Observation) {
        assertEquals(a.units, b.units); assertEquals(a.combat, b.combat)
        assertEquals(a.projectiles, b.projectiles); assertEquals(a.projectileEvents, b.projectileEvents)
        assertEquals(a.events, b.events); assertEquals(a.actionResults, b.actionResults)
        assertEquals(a.economy!!.balances, b.economy!!.balances)
        assertEquals(a.upgrades?.queues, b.upgrades?.queues)
    }

    @Test fun real_later_shot_overflow_restores_completed_conversion_queue_money_rng_and_odd_state_then_public_correction_retries() {
        val base = UpgradeTestContent.scenario()
        val tower = base.unitDefinitions.getValue("c_turret_t1")
        val fast = UpgradeTestContent.variant(tower, upgrades = com.rustedwarfare.reforge.core.content.UpgradeProfile(
            tower.upgrades!!.actions.map { it.copy(rate = 1f) }))
        val tank = base.unitDefinitions.getValue("c_tank")
        val witness = UpgradeTestContent.variant(tank, id = "witness", combat = null,
            durability = com.rustedwarfare.reforge.core.content.DurabilityProfile(210.0, tank.combat!!.deadImagePath))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + mapOf(fast.id to fast, witness.id to witness),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + listOf(UnitSpawn("z", "p2", "c_tank", Position(350.0, 150.0), 270.0),
                UnitSpawn("c", "p1", "witness", Position(350.0, 270.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L, "c" to 13L), Long.MAX_VALUE - 1))
        val contexts = pair(scene)
        try {
            contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(UpgradeAction("upgrade", "p1", "t", "upgrade_gun", it.world))) }
            val failed = contexts[0]
            val before = failed.observe(); val player = failed.observePlayer(failed.world, "p1")
            assertFailsWith<ArithmeticException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertSame(before, failed.observe()); assertSame(player, failed.observePlayer(failed.world, "p1"))
            assertEquals("c_turret_t1", before.units.single { it.unitId == "t" }.typeId)
            assertEquals(0f, before.upgrades!!.queues.getValue("t").single().progress)
            assertFalse(before.combat!!.weapons.first { it.unitId == "t" }.oddShot)
            contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(AttackAction("correct", "p2", "z", "c", it.world))) }
            for (step in 0L..5L) same(contexts[1].advance(Step(step, 1.0, "sim-tick")), failed.advance(Step(step, 1.0, "sim-tick")))
            assertEquals("c_turret_t2_gun", failed.observe().units.single { it.unitId == "t" }.typeId)
            assertEquals(750L, failed.observePlayer(failed.world, "p1").balance)
            assertTrue(failed.observe().upgrades!!.queues.getValue("t").isEmpty())
            assertEquals(0f, before.upgrades.queues.getValue("t").single().progress)
        } finally { contexts.forEach { it.close() } }
    }

    @Test fun real_later_nonfinite_flight_restores_prior_area_damage_ground_cache_and_published_history_before_retry() {
        val base = GroundTestContent.scene(5f)
        val artillery = base.unitDefinitions.getValue("c_turret_t1_artillery")
        val bad = UpgradeTestContent.variant(artillery, id = "high-flight", combat = artillery.combat!!.let { it.copy(
            turretTurnSpeed = 0.0, turretTurnAcceleration = 0.0,
            projectile = it.projectile.copy(ground = it.projectile.ground!!.copy(initialVerticalSpeed = Float.MAX_VALUE))) })
        val dummy = base.unitDefinitions.getValue("dummy")
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (bad.id to bad) +
            (dummy.id to UpgradeTestContent.variant(dummy, radius = 1.0)),
            players = base.players.map { it.copy(control = PlayerControl.LOCAL) },
            units = base.units + listOf(UnitSpawn("zz", "p2", bad.id, Position(430.0, 150.0), 270.0, Float.MAX_VALUE.toDouble()),
                UnitSpawn("c", "p1", "dummy", Position(430.0, 270.0), 0.0)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L, "z" to 12L, "zz" to 13L, "c" to 14L), 15L))
        val contexts = pair(scene)
        try {
            contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(AttackAction("target", "p1", "t", "z", it.world))) }
            val failed = contexts[0]
            val before = failed.observe(); val view = failed.observePlayer(failed.world, "p1")
            val error = assertFailsWith<IllegalArgumentException> { failed.advance(Step(0, 1.0, "sim-tick")) }
            assertTrue(error.message!!.contains("Projectile"))
            assertSame(before, failed.observe()); assertSame(view, failed.observePlayer(failed.world, "p1"))
            assertEquals(10000.0, before.units.single { it.unitId == "z" }.health)
            contexts.forEach { assertIs<ActionResult.Accepted>(it.submit(AttackAction("correct", "p2", "zz", "c", it.world))) }
            for (step in 0L..5L) same(contexts[1].advance(Step(step, 1.0, "sim-tick")), failed.advance(Step(step, 1.0, "sim-tick")))
            assertEquals(9920.0, failed.observe().units.single { it.unitId == "z" }.health)
            assertTrue(before.projectiles.isEmpty())
            assertTrue(before.projectileEvents.isEmpty())
        } finally { contexts.forEach { it.close() } }
    }
}
