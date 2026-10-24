package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ActionWindowTest {
    @Test fun long_session_keeps_current_and_committed_results_isolated_and_deduplicates_old_ids() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        try {
            val world = context.world
            var frozen: PlayerObservation? = null
            for (step in 0L until 1200L) {
                val ownId = "own-$step"
                assertIs<ActionResult.Accepted>(context.submit(SelectAction(ownId, "p1", "u1", world)))
                // A rejected action still belongs exclusively to its actor.
                context.submit(SelectAction("other-$step", "p2", "missing", world))
                val committed = context.advance(Step(step, 1.0, "sim-tick"))
                val player = context.observePlayer(world, "p1")
                val expected = (if (step == 0L) emptyList() else listOf("after-${step - 1}")) + ownId
                assertEquals(expected, player.actionResults.map { it.actionId })
                if (step == 0L) frozen = player
                context.submit(SelectAction("after-$step", "p1", "u1", world))
                val after = context.observePlayer(world, "p1")
                assertEquals(expected + "after-$step", after.actionResults.map { it.actionId })
                assertEquals(listOf(ownId), committed.actionResults.filter { it.actionId.startsWith("own-") }.map { it.actionId })
                assertSame(after, context.observePlayer(world, "p1"))
            }
            assertEquals(listOf("own-0"), checkNotNull(frozen).actionResults.map { it.actionId })
            assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(
                context.submit(SelectAction("own-0", "p1", "u1", world))).error.code)
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(TestContentFactory.openScenario()))
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("own-0", "p1", "u1", context.world)))
            assertTrue(context.observePlayer(context.world, "p2").actionResults.isEmpty())
        } finally { context.close() }
    }
}
