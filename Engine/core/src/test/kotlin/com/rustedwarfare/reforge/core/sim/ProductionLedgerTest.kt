package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import java.nio.file.Path
import kotlin.test.*

/** Module tests are bookkeeping evidence, not factory/world/PC production evidence. */
class ProductionLedgerTest {
    private fun spec(owner: String = "p1"): FactoryProductionSpec {
        val root = Path.of(checkNotNull(System.getProperty("p001.inputs.dir"))).parent.parent.parent.resolve(
            ".scratch/开发票据/引擎重写/P005-生产战斗与指挥沙盒/evidence/ENG-P005-002/20261025-e-input-01/input/units")
        val factory = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadFactory(root.resolve("landFactory.properties"))).value
        val tank = assertIs<ContentResult.Success<UnitDefinition>>(ContentLoader.loadCombatUnit(root.resolve("tank.ini"))).value
        assertEquals(30.0, factory.radius)
        return FactoryProductionSpec(owner, assertNotNull(factory.production).unitTypeId, assertNotNull(tank.build))
    }

    private fun ledger(balance: Long = 1750L): ProductionLedger = ProductionLedger.initial(
        mapOf("p1" to balance, "p2" to 0L), mapOf("f" to spec()))

    private fun nonce(index: Int): String = index.toString(16).padStart(32, '0')

    @Test
    fun real_content_price_is_paid_once_then_cancelled_in_full_without_mutating_the_old_state() {
        val initial = ledger()
        val paid = assertIs<ProductionChange.Accepted>(initial.enqueue("f", "c_tank", nonce(0))).ledger
        assertEquals(1400L, paid.balances["p1"])
        assertEquals(1, paid.queue("f").size)
        assertEquals(0f, paid.queue("f").single().progress)
        val refunded = assertIs<ProductionChange.Accepted>(paid.cancel("f", "c_tank")).ledger
        assertEquals(1750L, refunded.balances["p1"])
        assertTrue(refunded.queue("f").isEmpty())
        assertEquals(1750L, initial.balances["p1"])
        assertTrue(initial.queue("f").isEmpty())
        assertEquals(1, paid.queue("f").size)
    }

    @Test
    fun only_the_head_advances_with_binary32_and_completion_does_not_carry_into_the_next_item() {
        val first = assertIs<ProductionChange.Accepted>(ledger().enqueue("f", "c_tank", nonce(0))).ledger
        val two = assertIs<ProductionChange.Accepted>(first.enqueue("f", "c_tank", nonce(1))).ledger
        assertEquals(350L, two.queue("f").first().cost)
        assertEquals(0.002f, two.queue("f").first().rate)
        var current = two
        repeat(500) {
            val tick = current.advance("f")
            assertNull(tick.completed)
            current = tick.ledger
        }
        assertEquals(0.9999942183494568f, current.queue("f").first().progress)
        assertEquals(0f, current.queue("f").last().progress)
        val finished = current.advance("f")
        assertEquals(ProductionCompletion("f", "p1", "c_tank", nonce(0)), finished.completed)
        assertEquals(1, finished.ledger.queue("f").size)
        assertEquals(nonce(1), finished.ledger.queue("f").single().unitNonce)
        assertEquals(0f, finished.ledger.queue("f").single().progress)
        assertEquals(1050L, finished.ledger.balances["p1"])
        assertEquals(0.002f, finished.ledger.advance("f").ledger.queue("f").single().progress)
        assertEquals(0f, two.queue("f").first().progress)
    }

    @Test
    fun cancelling_the_last_matching_item_keeps_head_progress_and_empty_cancel_does_not_refund() {
        var current = assertIs<ProductionChange.Accepted>(ledger().enqueue("f", "c_tank", nonce(0))).ledger
        current = assertIs<ProductionChange.Accepted>(current.enqueue("f", "c_tank", nonce(1))).ledger
        repeat(100) { current = current.advance("f").ledger }
        val progress = current.queue("f").first().progress
        assertTrue(progress > 0f)
        val cancelled = assertIs<ProductionChange.Accepted>(current.cancel("f", "c_tank")).ledger
        assertEquals(1400L, cancelled.balances["p1"])
        assertEquals(nonce(0), cancelled.queue("f").single().unitNonce)
        assertEquals(progress, cancelled.queue("f").single().progress)
        val empty = assertIs<ProductionChange.Accepted>(cancelled.cancel("f", "c_tank")).ledger
        assertEquals(1750L, empty.balances["p1"])
        assertEquals(ProductionError.EMPTY_QUEUE, assertIs<ProductionChange.Rejected>(empty.cancel("f", "c_tank")).error)
        assertEquals(1750L, empty.balances["p1"])
    }

    @Test
    fun death_refunds_only_pending_work_and_is_idempotent_after_prior_completion() {
        var current = assertIs<ProductionChange.Accepted>(ledger().enqueue("f", "c_tank", nonce(0))).ledger
        repeat(501) { current = current.advance("f").ledger }
        assertTrue(current.queue("f").isEmpty())
        current = assertIs<ProductionChange.Accepted>(current.enqueue("f", "c_tank", nonce(1))).ledger
        repeat(100) { current = current.advance("f").ledger }
        val before = current
        val dead = current.destroyFactory("f")
        assertEquals(1400L, dead.balances["p1"], "Completed work is not refunded")
        assertTrue(dead.queue("f").isEmpty())
        assertFalse(dead.isActive("f"))
        assertSame(dead, dead.destroyFactory("f"))
        assertSame(dead, dead.advance("f").ledger)
        assertNull(dead.advance("f").completed)
        assertEquals(ProductionError.INACTIVE_FACTORY,
            assertIs<ProductionChange.Rejected>(dead.enqueue("f", "c_tank", nonce(2))).error)
        assertEquals(1050L, before.balances["p1"])
        assertEquals(1, before.queue("f").size)
    }

    @Test
    fun death_refunds_every_pending_item_and_does_not_release_owner_nonce_reservations() {
        val registration = spec()
        var current = ProductionLedger.initial(mapOf("p1" to 1750L), mapOf("f" to registration, "g" to registration))
        current = assertIs<ProductionChange.Accepted>(current.enqueue("f", "c_tank", nonce(0))).ledger
        current = assertIs<ProductionChange.Accepted>(current.enqueue("f", "c_tank", nonce(1))).ledger
        repeat(100) { current = current.advance("f").ledger }
        val dead = current.destroyFactory("f")
        assertEquals(1750L, dead.balances["p1"])
        assertTrue(dead.queue("f").isEmpty())
        assertEquals(ProductionError.DUPLICATE_NONCE,
            assertIs<ProductionChange.Rejected>(dead.enqueue("g", "c_tank", nonce(0))).error)
        assertEquals(ProductionError.DUPLICATE_NONCE,
            assertIs<ProductionChange.Rejected>(dead.enqueue("g", "c_tank", nonce(1))).error)
        assertEquals(1050L, current.balances["p1"])
        assertEquals(2, current.queue("f").size)
    }

    @Test
    fun reserved_nonce_is_per_owner_and_survives_cancel_and_other_factories() {
        val registered = spec()
        val initial = ProductionLedger.initial(mapOf("p1" to 1750L, "p2" to 350L),
            mapOf("f" to registered, "g" to registered, "h" to registered.copy(ownerPlayerId = "p2")))
        val paid = assertIs<ProductionChange.Accepted>(initial.enqueue("f", "c_tank", nonce(0))).ledger
        assertEquals(ProductionError.DUPLICATE_NONCE,
            assertIs<ProductionChange.Rejected>(paid.enqueue("g", "c_tank", nonce(0))).error)
        val empty = assertIs<ProductionChange.Accepted>(paid.cancel("f", "c_tank")).ledger
        assertEquals(ProductionError.DUPLICATE_NONCE,
            assertIs<ProductionChange.Rejected>(empty.enqueue("f", "c_tank", nonce(0))).error)
        val otherOwner = assertIs<ProductionChange.Accepted>(empty.enqueue("h", "c_tank", nonce(0))).ledger
        assertEquals(0L, otherOwner.balances["p2"])
        assertEquals(1750L, otherOwner.balances["p1"])
    }

    @Test
    fun insufficient_or_invalid_requests_do_not_change_state_and_there_is_no_artificial_99_item_cap() {
        val poor = ledger(349L)
        assertEquals(ProductionError.INSUFFICIENT_FUNDS, assertIs<ProductionChange.Rejected>(poor.enqueue("f", "c_tank", nonce(0))).error)
        assertEquals(349L, poor.balances["p1"])
        assertTrue(poor.queue("f").isEmpty())
        val enough = ledger(350L)
        assertEquals(ProductionError.INVALID_TYPE, assertIs<ProductionChange.Rejected>(enough.enqueue("f", "other", nonce(0))).error)
        assertEquals(ProductionError.INVALID_NONCE, assertIs<ProductionChange.Rejected>(enough.enqueue("f", "c_tank", "0")).error)
        assertEquals(ProductionError.UNKNOWN_FACTORY, assertIs<ProductionChange.Rejected>(enough.enqueue("missing", "c_tank", nonce(0))).error)
        val paid = assertIs<ProductionChange.Accepted>(enough.enqueue("f", "c_tank", nonce(0))).ledger
        assertEquals(0L, paid.balances["p1"])
        assertEquals(ProductionError.INSUFFICIENT_FUNDS,
            assertIs<ProductionChange.Rejected>(paid.enqueue("f", "c_tank", nonce(1))).error)
        val zeroCost = spec().copy(build = BuildProfile(0L, 0.002f))
        var current = ProductionLedger.initial(mapOf("p1" to 0L), mapOf("f" to zeroCost))
        repeat(101) { current = assertIs<ProductionChange.Accepted>(current.enqueue("f", "c_tank", nonce(it))).ledger }
        assertEquals(101, current.queue("f").size)
        assertEquals(0L, current.balances["p1"])
        assertFailsWith<UnsupportedOperationException> { (current.queue("f") as MutableList<PendingProduction>).clear() }
        assertFailsWith<UnsupportedOperationException> { (current.balances as MutableMap<String, Long>)["p1"] = 1L }
    }

    @Test
    fun checked_full_refund_restores_maximum_legal_balance_and_invalid_registration_is_rejected() {
        val initial = ledger(Long.MAX_VALUE)
        val paid = assertIs<ProductionChange.Accepted>(initial.enqueue("f", "c_tank", nonce(0))).ledger
        assertEquals(9223372036854775457L, paid.balances["p1"])
        val refunded = assertIs<ProductionChange.Accepted>(paid.cancel("f", "c_tank")).ledger
        assertEquals(Long.MAX_VALUE, refunded.balances["p1"])
        for (bad in listOf(BuildProfile(-1L, .002f), BuildProfile(350L, 0f), BuildProfile(350L, Float.NaN))) {
            assertFailsWith<IllegalArgumentException> {
                ProductionLedger.initial(mapOf("p1" to 1750L), mapOf("f" to spec().copy(build = bad)))
            }
        }
    }
}
