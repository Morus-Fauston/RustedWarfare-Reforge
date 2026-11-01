package com.rustedwarfare.reforge.core.sim

import kotlin.test.*

class ActionWindowTest {
    @Test fun equal_rejections_keep_actor_identity_and_hidden_results_preserve_the_other_players_sample() {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        try {
            val world = context.world
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("shared", "p1", "u1", world)))
            val own = assertIs<ActionResult.Rejected>(context.submit(SelectAction("shared", "p1", "u1", world)))
            val other = assertIs<ActionResult.Rejected>(context.submit(SelectAction("shared", "p2", "u1", world)))
            assertEquals(own, other)
            assertNotSame(own, other)
            val first = context.observePlayer(world, "p1")
            assertEquals(2, first.actionResults.size)
            assertSame(own, first.actionResults[1])
            assertEquals(listOf(other), context.observePlayer(world, "p2").actionResults)
            context.submit(SelectAction("hidden-rejection", "p2", "missing", world))
            assertSame(first, context.observePlayer(world, "p1"))
            context.advance(Step(0, 1.0, "sim-tick"))
            val committed = context.observePlayer(world, "p1")
            assertEquals(2, committed.actionResults.size)
            context.submit(SelectAction("later-hidden", "p2", "missing", world))
            assertSame(committed, context.observePlayer(world, "p1"))
            assertEquals(listOf("shared", "shared"), first.actionResults.map { it.actionId })
            context.close()
            assertIs<InitResult.Accepted>(context.reopen(TestContentFactory.openScenario()))
            val reopened = context.observePlayer(context.world, "p1")
            assertTrue(reopened.actionResults.isEmpty())
            assertNotSame(first, reopened)
            assertEquals(2, committed.actionResults.size)
        } finally { context.close() }
    }

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
