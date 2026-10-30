package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class OrdinaryUpgradesTest {
    @Test fun trusted_same_semantics_registration_owns_payment_conversion_pruning_and_single_refund() {
        val base = UpgradeTestContent.scenario()
        val tower = UpgradeTestContent.variant(base.unitDefinitions.getValue("c_turret_t1"), id = "practice",
            upgrades = UpgradeProfile(listOf(UpgradeActionProfile("practice_gun", "c_turret_t2_gun", 7L, 1f),
                UpgradeActionProfile("practice_artillery", "c_turret_t1_artillery", 11L, .5f))))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (tower.id to tower),
            units = listOf(UnitSpawn("t", "p1", "practice", Position(230.0, 150.0), 90.0)))
        val relations = linkedMapOf("practice_gun" to OrdinaryUpgradeContent.TurretTarget("c_turret_t2_gun", false),
            "practice_artillery" to OrdinaryUpgradeContent.TurretTarget("c_turret_t1_artillery", true))
        val registration = UpgradeRegistration(extraTurrets = mapOf("practice" to relations))
        assertEquals("unsupported-upgrade", OrdinaryUpgrades.validate(scene)?.code)
        assertNull(OrdinaryUpgrades.validate(scene, registration))
        relations.clear() // The running registration must own its input.
        var state = OrdinaryUpgrades.initial(scene, registration)!!
        var ledger = ProductionLedger.initial(mapOf("p1" to 30L), emptyMap())
        val unit = MutableUnit("t", "p1", "practice", tower, Position(230.0, 150.0), 90.0,
            health = 350.0, objectId = 11L)
        unit.combat!!.cooldown = 99.0
        for (key in listOf("practice_gun", "practice_artillery")) {
            val change = assertIs<OrdinaryUpgradeRequest.Accepted>(state.request(unit, key, false, ledger)).change
            state = change.upgrades; ledger = change.ledger
            assertEquals(listOf(Event("upgrade-queued", "t")), change.events)
        }
        assertEquals(12L, ledger.balances.getValue("p1"))
        assertEquals("duplicate-upgrade", assertIs<OrdinaryUpgradeRequest.Rejected>(
            state.request(unit, "practice_gun", false, ledger)).error.code)
        val queued = state.observe()
        val change = state.advance(unit, ledger)
        assertEquals("c_turret_t2_gun", unit.typeId)
        assertEquals(550.0, unit.health)
        assertEquals(20.0, unit.combat!!.cooldown)
        assertEquals(11L, unit.objectId)
        assertEquals(Position(230.0, 150.0), unit.position)
        assertEquals(23L, change.ledger.balances.getValue("p1"))
        assertEquals(listOf(Event("upgrade-completed", "t"), Event("upgrade-refunded", "t")), change.events)
        assertTrue(change.upgrades.observe().queues.getValue("t").isEmpty())
        assertEquals(2, queued.queues.getValue("t").size)
        val again = change.upgrades.discard(unit, change.ledger)
        assertEquals(23L, again.ledger.balances.getValue("p1"))
        assertTrue(again.events.isEmpty())
        // A cancellation refunds the paid cost and leaves the earlier state untouched.
        val cancelled = assertIs<OrdinaryUpgradeRequest.Accepted>(state.request(unit.copy(typeId = "practice",
            definition = tower), "practice_artillery", true, ledger)).change
        assertEquals(23L, cancelled.ledger.balances.getValue("p1"))
        assertEquals(1, cancelled.upgrades.observe().queues.getValue("t").size)
        assertEquals(2, state.observe().queues.getValue("t").size)
    }
}
