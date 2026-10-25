package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class UpgradeCompletionTest {
    @Test fun real_first_completion_converts_the_same_tower_then_prunes_and_refunds_the_competing_branch_once() {
        for (first in listOf("upgrade_gun", "upgrade_artillery")) {
            val scene = UpgradeTestContent.scenario().copy(economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)))
            val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val old = c.observe()
                val other = if (first == "upgrade_gun") "upgrade_artillery" else "upgrade_gun"
                for ((index, branch) in listOf(first, other).withIndex()) assertIs<ActionResult.Accepted>(c.submit(
                    UpgradeAction("queue-$index", "p1", "t", branch, c.world)))
                val queued = c.observe()
                var step = 0L
                while (c.observe().units.single().typeId == "c_turret_t1" && step < 2600L)
                    c.advance(Step(step++, 1.0, "sim-tick"))
                val result = c.observe()
                val targetType = if (first == "upgrade_gun") "c_turret_t2_gun" else "c_turret_t1_artillery"
                assertEquals(targetType, result.units.single().typeId)
                assertEquals(if (first == "upgrade_gun") 1100.0 else 1000.0, result.units.single().health)
                assertEquals(old.units.single().let { listOf(it.unitId, it.ownerPlayerId, it.position, it.heading, it.height) },
                    result.units.single().let { listOf(it.unitId, it.ownerPlayerId, it.position, it.heading, it.height) })
                assertEquals(old.combat!!.weapons.single().objectId, result.combat!!.weapons.single().objectId)
                assertEquals(old.combat.nextObjectId, result.combat.nextObjectId)
                assertEquals(if (first == "upgrade_gun") 2000L else 1400L, result.economy!!.balances["p1"])
                assertTrue(result.upgrades!!.queues.getValue("t").isEmpty())
                assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.isEmpty())
                assertEquals(1, result.events.count { it.kind == "upgrade-completed" })
                assertEquals(1, result.events.count { it.kind == "upgrade-refunded" })
                assertTrue(result.production!!.units.isEmpty())
                assertEquals(2, queued.upgrades!!.queues.getValue("t").size)
                assertEquals("c_turret_t1", old.units.single().typeId)
                val balance = result.economy.balances
                repeat(20) { c.advance(Step(step++, 1.0, "sim-tick")) }
                assertEquals(balance, c.observe().economy!!.balances)
                assertTrue(c.observe().events.none { it.kind.startsWith("upgrade-") })
                assertEquals("cannot-upgrade", assertIs<ActionResult.Rejected>(c.submit(
                    CancelUpgradeAction("late-cancel", "p1", "t", first, c.world))).error.code)
                c.close(); assertIs<InitResult.Accepted>(c.reopen(scene))
                assertEquals("c_turret_t1", c.observe().units.single().typeId)
                assertEquals(3000L, c.observePlayer(c.world, "p1").balance)
                assertTrue(c.observe().upgrades!!.queues.getValue("t").isEmpty())
            } finally { c.close() }
        }
    }

    @Test fun ordinary_damage_during_upgrade_preserves_completion_time_float_health_ratio() {
        val base = UpgradeTestContent.scenario()
        val scene = base.copy(units = base.units + UnitSpawn("z", "p2", "c_tank", Position(350.0, 150.0), 270.0),
            combatContract = base.combatContract!!.copy(unitObjectIds = mapOf("t" to 11L, "z" to 12L)))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            var step = 0L
            while (c.observe().units.first { it.unitId == "t" }.health == 700.0 && step < 100L)
                c.advance(Step(step++, 1.0, "sim-tick"))
            assertTrue(c.observe().units.first { it.unitId == "t" }.health!! < 700.0)
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("injured", "p1", "t", "upgrade_gun", c.world)))
            var before = c.observe()
            while (c.observe().units.first { it.unitId == "t" }.typeId == "c_turret_t1" && step < 1200L) {
                before = c.observe()
                c.advance(Step(step++, 1.0, "sim-tick"))
            }
            val result = c.observe().units.first { it.unitId == "t" }
            assertEquals("c_turret_t2_gun", result.typeId)
            val prior = before.units.first { it.unitId == "t" }
            assertTrue(prior.health!! < 700.0)
            assertEquals((prior.health.toFloat() / 700f * 1100f).toDouble(), result.health)
            assertEquals(750L, c.observePlayer(c.world, "p1").balance)
            assertEquals(11L, c.observe().combat!!.weapons.first { it.unitId == "t" }.objectId)
        } finally { c.close() }
    }

    @Test fun real_death_refunds_all_pending_upgrades_once_and_dead_tower_cannot_finish_or_accept_scheduled_work() {
        val base = UpgradeTestContent.scenario()
        val enemies = listOf(70, 94, 118, 142, 166, 190, 214).mapIndexed { i, y ->
            UnitSpawn("z$i", "p2", "c_tank", Position(330.0, y.toDouble()), 270.0) }
        val scene = base.copy(units = base.units + enemies,
            economyContract = EconomyContract(mapOf("p1" to 3000L, "p2" to 0L)),
            combatContract = CombatContract(12345, -1, mapOf("t" to 11L) + enemies.mapIndexed { i, u -> u.unitId to 12L + i }, 30L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for ((index, branch) in listOf("upgrade_gun", "upgrade_artillery").withIndex()) assertIs<ActionResult.Accepted>(c.submit(
                UpgradeAction("queue-$index", "p1", "t", branch, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(UpgradeAction("later", "p1", "t", "upgrade_gun", c.world, 1000L)))
            var step = 0L
            while (c.observe().units.first { it.unitId == "t" }.alive && step < 900L)
                c.advance(Step(step++, 1.0, "sim-tick"))
            val death = c.observe()
            assertFalse(death.units.first { it.unitId == "t" }.alive)
            assertEquals("c_turret_t1", death.units.first { it.unitId == "t" }.typeId)
            assertEquals(3000L, death.economy!!.balances["p1"])
            assertTrue(death.upgrades!!.queues.getValue("t").isEmpty())
            assertEquals(1, death.events.count { it.kind == "upgrade-refunded" && it.unitId == "t" })
            while (step <= 1020L) c.advance(Step(step++, 1.0, "sim-tick"))
            assertEquals(3000L, c.observePlayer(c.world, "p1").balance)
            assertEquals("c_turret_t1", c.observe().units.first { it.unitId == "t" }.typeId)
            assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.isEmpty())
        } finally { c.close() }
    }
}
