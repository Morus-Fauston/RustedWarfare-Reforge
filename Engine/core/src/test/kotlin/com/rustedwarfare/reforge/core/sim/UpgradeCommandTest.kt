package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class UpgradeCommandTest {
    private fun open(money: Long = 1750L) = assertIs<InitResult.Accepted>(SimulationContext.initialize(
        UpgradeTestContent.scenario().copy(economyContract = EconomyContract(mapOf("p1" to money, "p2" to 0L))))).context
    private fun upgrade(c: SimulationContext, id: String, branch: String = "upgrade_gun", at: Long? = null) =
        c.submit(UpgradeAction(id, "p1", "t", branch, c.world, at))
    private fun cancel(c: SimulationContext, id: String, branch: String = "upgrade_gun") =
        c.submit(CancelUpgradeAction(id, "p1", "t", branch, c.world))

    @Test fun public_upgrade_pays_once_advances_and_cancel_refunds_without_mutating_old_views() {
        val c = open()
        try {
            val initial = c.observePlayer(c.world, "p1")
            val enemy = c.observePlayer(c.world, "p2")
            assertIs<ActionResult.Accepted>(upgrade(c, "first"))
            val queued = c.observePlayer(c.world, "p1")
            assertEquals(750L, queued.balance)
            assertEquals(listOf(UpgradeQueueItem("upgrade_gun", "c_turret_t2_gun", 0f)), queued.upgradeQueues.single().items)
            assertEquals("t", queued.upgradeQueues.single().unitId)
            assertSame(enemy, c.observePlayer(c.world, "p2"))
            assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(upgrade(c, "first")).error.code)
            assertEquals("duplicate-upgrade", assertIs<ActionResult.Rejected>(upgrade(c, "same")).error.code)
            assertIs<ActionResult.Accepted>(c.submit(StopAction("stop", "p1", "t", c.world)))
            c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(.001f, c.observePlayer(c.world, "p1").upgradeQueues.single().items.single().progress)
            assertIs<ActionResult.Accepted>(cancel(c, "cancel"))
            assertEquals(1750L, c.observePlayer(c.world, "p1").balance)
            assertTrue(c.observePlayer(c.world, "p1").upgradeQueues.single().items.isEmpty())
            assertEquals("empty-upgrade-queue", assertIs<ActionResult.Rejected>(cancel(c, "empty")).error.code)
            assertEquals(1750L, initial.balance); assertTrue(initial.upgradeQueues.single().items.isEmpty())
            assertEquals(0f, queued.upgradeQueues.single().items.single().progress)
            assertFailsWith<UnsupportedOperationException> { (queued.upgradeQueues.single().items as MutableList).clear() }
        } finally { c.close() }
    }

    @Test fun both_branches_can_queue_with_money_and_cancelling_the_tail_leaves_the_head_untouched() {
        val c = open(3000L)
        try {
            assertIs<ActionResult.Accepted>(upgrade(c, "gun"))
            assertIs<ActionResult.Accepted>(upgrade(c, "art", "upgrade_artillery"))
            assertEquals(400L, c.observePlayer(c.world, "p1").balance)
            assertEquals(listOf("upgrade_gun", "upgrade_artillery"), c.observePlayer(c.world, "p1").upgradeQueues.single().items.map { it.actionKey })
            c.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(listOf(.001f, 0f), c.observePlayer(c.world, "p1").upgradeQueues.single().items.map { it.progress })
            assertIs<ActionResult.Accepted>(cancel(c, "cancel-art", "upgrade_artillery"))
            assertEquals(2000L, c.observePlayer(c.world, "p1").balance)
            assertEquals(.001f, c.observePlayer(c.world, "p1").upgradeQueues.single().items.single().progress)
        } finally { c.close() }
    }

    @Test fun authority_capability_funds_and_world_checks_precede_private_queue_errors() {
        val c = open(999L)
        try {
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(c.submit(
                UpgradeAction("foreign", "p2", "t", "wrong", c.world))).error.code)
            assertEquals("unsupported-upgrade", assertIs<ActionResult.Rejected>(upgrade(c, "type", "wrong")).error.code)
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(upgrade(c, "low")).error.code)
            assertEquals(999L, c.observePlayer(c.world, "p1").balance)
            val world = c.world
            c.close(); assertIs<InitResult.Accepted>(c.reopen(UpgradeTestContent.scenario()))
            assertEquals("stale-world", assertIs<ActionResult.Rejected>(c.submit(
                UpgradeAction("fresh", "p1", "t", "upgrade_gun", world))).error.code)
            assertIs<ActionResult.Accepted>(upgrade(c, "fresh"))
            val view = c.observePlayer(c.world, "p2")
            assertTrue(view.upgradeQueues.isEmpty())
            assertTrue(view.events.none { it.kind.startsWith("upgrade-") })
            assertIs<ActionResult.Accepted>(cancel(c, "owner-cancel"))
            assertSame(view, c.observePlayer(c.world, "p2"))
        } finally { c.close() }
        val base = UpgradeTestContent.scenario()
        val disabled = UpgradeTestContent.tower(false)
        val old = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(
            unitDefinitions = base.unitDefinitions + (disabled.id to disabled)))).context
        try { assertEquals("cannot-upgrade", assertIs<ActionResult.Rejected>(upgrade(old, "guess")).error.code) }
        finally { old.close() }
    }

    @Test fun scheduled_requests_check_execution_time_funds_and_increment_only_the_paid_head() {
        val c = open(1000L)
        try {
            assertIs<ActionResult.Accepted>(upgrade(c, "gun", at = 0L))
            assertIs<ActionResult.Accepted>(upgrade(c, "art", "upgrade_artillery", 0L))
            assertEquals(1000L, c.observePlayer(c.world, "p1").balance)
            val step = c.advance(Step(0, 1.0, "sim-tick"))
            assertIs<ActionResult.Accepted>(step.actionResults.first { it.actionId == "gun" })
            assertEquals("insufficient-funds", assertIs<ActionResult.Rejected>(step.actionResults.first { it.actionId == "art" }).error.code)
            assertEquals(0L, c.observePlayer(c.world, "p1").balance)
            assertEquals(.001f, c.observePlayer(c.world, "p1").upgradeQueues.single().items.single().progress)
            assertIs<ActionResult.Accepted>(cancel(c, "refund"))
            assertEquals(1000L, c.observePlayer(c.world, "p1").balance)
        } finally { c.close() }
    }
}
