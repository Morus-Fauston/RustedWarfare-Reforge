package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.siteConstruction
import kotlin.test.*

class CancelConstructionActionTest {

    @Test
    fun cancels_own_under_construction_building_refunds_price_and_releases_worker() {
        val input = AuthorConstructionScene.load()
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val startBalance = ctx.observePlayer(ctx.world, "p1").balance!!
            val target = Position(190.0, 210.0)
            val nonce = "0123456789abcdef0123456789abcdef"
            assertIs<ActionResult.Accepted>(
                ctx.submit(BuildAction("build-1", "p1", "a", "workshop", target, nonce, ctx.world))
            )
            // Advance until the construction site is spawned and partially built (< 1f)
            var siteId: String? = null
            for (step in 0L until 120L) {
                ctx.advance(Step(step, 1.0, "sim-tick"))
                val obs = ctx.observePlayer(ctx.world, "p1")
                val site = obs.units.firstOrNull { it.typeId == "workshop" && it.constructionProgress < 1f }
                if (site != null && site.constructionProgress > 0.05f) {
                    siteId = site.unitId
                    break
                }
            }
            val id = assertNotNull(siteId, "Expected under-construction workshop site")
            val price = input.unitDefinitions.getValue("workshop").siteConstruction!!.price
            val balanceAfterCharge = ctx.observePlayer(ctx.world, "p1").balance!!
            assertEquals(startBalance - price, balanceAfterCharge)

            // Reject foreign player cancel attempt
            val foreign = ctx.submit(CancelConstructionAction("cancel-foe", "p2", id, ctx.world))
            assertIs<ActionResult.Rejected>(foreign)

            // Owner cancels construction
            val result = assertIs<ActionResult.Accepted>(
                ctx.submit(CancelConstructionAction("cancel-own", "p1", id, ctx.world))
            )
            assertTrue(result.events.any { it.kind == "construction-cancelled" && it.unitId == id })

            val after = ctx.observePlayer(ctx.world, "p1")
            assertTrue(after.units.none { it.unitId == id }, "Cancelled site must be removed from observation")
            assertEquals(startBalance, after.balance, "Owner must receive full construction refund")
            val worker = after.units.single { it.unitId == "a" }
            assertTrue(worker.tasks.none { it.targetUnitId == id }, "Worker task targeting cancelled site must be released")

            // Double cancel must be rejected
            val again = ctx.submit(CancelConstructionAction("cancel-again", "p1", id, ctx.world))
            assertIs<ActionResult.Rejected>(again)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun rejects_cancel_on_completed_building_and_mobile_units() {
        val base = AuthorConstructionScene.load()
        val input = base.copy(
            units = base.units + UnitSpawn("done-shop", "p1", "workshop", Position(270.0, 210.0), 0.0),
            combatContract = base.combatContract!!.copy(
                unitObjectIds = base.combatContract.unitObjectIds + ("done-shop" to 10L),
                nextObjectId = 11L,
            ),
        )
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val doneReject = assertIs<ActionResult.Rejected>(
                ctx.submit(CancelConstructionAction("cancel-done", "p1", "done-shop", ctx.world))
            )
            assertEquals("construction-already-complete", doneReject.error.code)

            val mobileReject = assertIs<ActionResult.Rejected>(
                ctx.submit(CancelConstructionAction("cancel-mobile", "p1", "a", ctx.world))
            )
            assertEquals("cannot-cancel-construction", mobileReject.error.code)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun sandbox_credits_reserves_room_for_under_construction_refund_and_never_overflows() {
        val input = AuthorConstructionScene.load()
        OfflineSandboxHost(input, deploymentEnabled = true, generation = 7L).use { host ->
            val ctx = host.context
            val target = Position(190.0, 210.0)
            val nonce = "fedcba9876543210fedcba9876543210"
            assertIs<ActionResult.Accepted>(
                ctx.submit(BuildAction("build-overflow", "p1", "a", "workshop", target, nonce, ctx.world))
            )
            var siteId: String? = null
            var stepIdx = 0L
            for (step in 0L until 120L) {
                host.advance(Step(step, 1.0, "sim-tick"))
                stepIdx = step + 1L
                val site = ctx.observePlayer(ctx.world, "p1").units
                    .firstOrNull { it.typeId == "workshop" && it.constructionProgress < 1f }
                if (site != null) {
                    siteId = site.unitId
                    break
                }
            }
            val id = assertNotNull(siteId)
            val price = input.unitDefinitions.getValue("workshop").siteConstruction!!.price
            val bal = ctx.observePlayer(ctx.world, "p1").balance!!
            // Pushing balance past Long.MAX_VALUE - price must be rejected because sandboxRefunds reserves the site refund
            host.submit(SandboxCommandRequest(1L, ctx.world, 7L, "/credits p1 +${Long.MAX_VALUE - bal - price + 1L}"))
            host.advance(Step(stepIdx++, 1.0, "sim-tick"))
            val overflow = checkNotNull(host.receipt(1L))
            assertEquals(SandboxCommandStatus.REJECTED, overflow.status)
            assertEquals("amount-out-of-range", overflow.code)

            // Pushing right up to Long.MAX_VALUE - price is accepted, and then cancelling refunds safely without overflow
            host.submit(SandboxCommandRequest(2L, ctx.world, 7L, "/credits p1 +${Long.MAX_VALUE - bal - price}"))
            host.advance(Step(stepIdx++, 1.0, "sim-tick"))
            assertEquals(SandboxCommandStatus.APPLIED, host.receipt(2L)?.status)
            assertIs<ActionResult.Accepted>(ctx.submit(CancelConstructionAction("cancel-ok", "p1", id, ctx.world)))
            assertEquals(Long.MAX_VALUE, ctx.observePlayer(ctx.world, "p1").balance)
        }
    }
}
