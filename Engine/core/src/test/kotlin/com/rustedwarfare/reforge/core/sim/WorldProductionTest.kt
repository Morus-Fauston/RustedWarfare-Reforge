package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class WorldProductionTest {
    @Test fun a_real_product_can_be_selected_stopped_and_attack_with_its_dynamic_weapon_identity() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = listOf(base.units.single { it.unitId == "f" },
            UnitSpawn("z", "p2", "c_tank", Position(439.96, 215.0), 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L, "z" to 9L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val nonce = "0".repeat(32)
        val id = "produced:p1:$nonce"
        try {
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("produce", "p1", "f", "c_tank", nonce, c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertTrue(c.observe().projectiles.none { it.sourceUnitId == id })
            assertEquals(11L, c.observe().production!!.units.single().objectId)
            assertIs<ActionResult.Accepted>(c.submit(SelectAction("select", "p1", id, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", id, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(AttackAction("attack", "p1", id, "z", c.world)))
            var shot: ProjectileObservation? = null
            for (step in 501L..570L) {
                val view = c.advance(Step(step, 1.0, "sim-tick"))
                shot = shot ?: view.projectileEvents.firstOrNull { it.kind == "born" && it.projectile.sourceUnitId == id }?.projectile
            }
            val fired = assertNotNull(shot)
            assertEquals("z", fired.targetUnitId)
            assertTrue(fired.objectId > 11L)
            assertTrue(c.observe().units.single { it.unitId == "z" }.health!! < 210.0)
            assertEquals(11L, c.observe().combat!!.weapons.single { it.unitId == id }.objectId)
            assertTrue(c.observe().combat!!.weapons.single { it.unitId == id }.randomCounter != 0)
            val oldWorld = c.world
            c.close()
            assertIs<InitResult.Accepted>(c.reopen(scene))
            assertTrue(c.observe().production!!.units.isEmpty())
            assertEquals(0L, c.observe().production!!.lastBirthSerial)
            assertEquals(1750L, c.observePlayer(c.world, "p1").balance)
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(c.submit(StopAction("stale", "p1", id, oldWorld))).error.code)
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("fresh", "p1", "f", "c_tank", nonce, c.world)))
        } finally { c.close() }
    }

    @Test fun stopped_first_product_does_not_block_second_birth_and_reverse_nonces_do_not_reorder_updates() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        val first = "produced:p1:${"f".repeat(32)}"
        val second = "produced:p1:${"0".repeat(32)}"
        try {
            for ((index, nonce) in listOf("f".repeat(32), "0".repeat(32)).withIndex()) {
                assertIs<ActionResult.Accepted>(c.submit(ProduceAction("queue-$index", "p1", "f", "c_tank", nonce, c.world)))
            }
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertIs<ActionResult.Accepted>(c.submit(StopAction("hold", "p1", first, c.world)))
            for (step in 501L..1001L) c.advance(Step(step, 1.0, "sim-tick"))
            val view = c.observe()
            assertEquals(listOf("f", second, first), view.units.map { it.unitId })
            assertEquals(listOf(first, second), view.production!!.units.map { it.unitId })
            assertEquals(listOf(11L, 12L), view.production.units.map { it.objectId })
            assertEquals(listOf("~produced/00000000000000000001", "~produced/00000000000000000002"),
                view.production.units.map { it.updateKey })
            assertEquals(listOf(0f, 30f), view.production.units.map { it.birthRemaining })
            assertEquals(2L, view.production.lastBirthSerial)
            assertEquals(listOf(Position(310.0, 215.0), Position(310.0, 215.0)),
                view.units.filter { it.unitId != "f" }.map { it.position })
            assertEquals(1050L, view.economy!!.balances["p1"])
            assertTrue(view.economy.queuedProduction.getValue("f").isEmpty())
        } finally { c.close() }
    }

    @Test fun born_tank_starts_its_exit_on_the_next_step_and_continues_after_the_birth_window() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val nonce = "0".repeat(32)
            val id = "produced:p1:$nonce"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("one", "p1", "f", "c_tank", nonce, c.world)))
            repeat(501) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val newborn = c.observe().units.single { it.unitId == id }
            assertEquals(Position(310.0, 215.0), newborn.position)
            val birth = assertNotNull(c.observe().production)
            assertEquals(1L, birth.lastBirthSerial)
            assertEquals(30f, birth.units.single().birthRemaining)
            assertEquals(11L, birth.units.single().objectId)
            assertEquals("~produced/00000000000000000001", birth.units.single().updateKey)
            assertEquals("f", birth.units.single().producerId)
            val firstOwnUpdate = c.advance(Step(501, 1.0, "sim-tick")).units.single { it.unitId == id }
            assertEquals(29f, c.observe().production!!.units.single().birthRemaining)
            assertEquals(Position(309.0, 300.0), firstOwnUpdate.moveTarget)
            assertTrue(firstOwnUpdate.speed > 0.0)
            assertTrue(firstOwnUpdate.position.y > 215.0)
            for (step in 502L..529L) c.advance(Step(step, 1.0, "sim-tick"))
            assertEquals(1f, c.observe().production!!.units.single().birthRemaining)
            c.advance(Step(530, 1.0, "sim-tick"))
            assertEquals(0f, c.observe().production!!.units.single().birthRemaining)
            assertEquals(30f, birth.units.single().birthRemaining)
            assertEquals(Position(309.0, 300.0), c.observe().units.single { it.unitId == id }.moveTarget)
            for (step in 531L..899L) c.advance(Step(step, 1.0, "sim-tick"))
            val exited = c.observe().units.single { it.unitId == id }
            assertNull(exited.moveTarget)
            assertTrue(exited.position.y > 280.0)
            assertEquals(1400L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observe().economy!!.queuedProduction.getValue("f").isEmpty())
        } finally { c.close() }
    }

    @Test fun real_head_completion_creates_one_loaded_tank_without_advancing_the_tail() {
        val base = BuildingTestContent.scenario()
        val scene = base.copy(units = base.units.filter { it.unitId == "f" },
            combatContract = CombatContract(12345, -1, mapOf("f" to 10L), 11L))
        val c = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val firstNonce = "0".repeat(31) + "1"
            val secondNonce = "0".repeat(31) + "2"
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("one", "p1", "f", "c_tank", firstNonce, c.world)))
            assertIs<ActionResult.Accepted>(c.submit(ProduceAction("two", "p1", "f", "c_tank", secondNonce, c.world)))
            val paid = c.observe()
            assertEquals(1050L, paid.economy!!.balances["p1"])
            repeat(500) { c.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            val almost = c.observe()
            assertEquals(listOf("f"), almost.units.map { it.unitId })
            assertEquals(0.9999942183494568f, almost.economy!!.queuedProduction.getValue("f").first().progress)
            assertEquals(0f, almost.economy.queuedProduction.getValue("f").last().progress)
            val completed = c.advance(Step(500, 1.0, "sim-tick"))
            val born = completed.units.single { it.unitId == "produced:p1:$firstNonce" }
            assertEquals("p1", born.ownerPlayerId)
            assertEquals("c_tank", born.typeId)
            assertEquals(Position(310.0, 215.0), born.position)
            assertEquals(Position(309.0, 300.0), born.moveTarget)
            assertEquals(180.0, born.heading)
            assertEquals(0.0, born.speed)
            assertEquals(210.0, born.health)
            assertTrue(born.alive)
            assertEquals(1050L, completed.economy!!.balances["p1"])
            assertEquals(listOf(secondNonce), completed.economy.queuedProduction.getValue("f").map { it.unitNonce })
            assertEquals(0f, completed.economy.queuedProduction.getValue("f").single().progress)
            assertEquals(0f, paid.economy.queuedProduction.getValue("f").first().progress)
            assertEquals(0.9999942183494568f, almost.economy.queuedProduction.getValue("f").first().progress)
            assertEquals(listOf("f"), almost.units.map { it.unitId })
            assertEquals(listOf("f", "produced:p1:$firstNonce"), c.observePlayer(c.world, "p1").units.map { it.unitId })
        } finally { c.close() }
    }
}
