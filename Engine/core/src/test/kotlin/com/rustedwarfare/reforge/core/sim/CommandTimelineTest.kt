package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommandTimelineTest {
    private fun context(): SimulationContext = assertIs<InitResult.Accepted>(
        SimulationContext.initialize(TestContentFactory.openScenario().copy(
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)),
        )),
    ).context

    @Test
    fun scheduled_commands_apply_at_the_declared_step_in_submission_order() {
        val context = context()
        val stop = StopAction("a2", "p1", "u1", context.world, atStep = 2)
        val move = MoveAction("a1", "p1", "u1", Position(250.0, 110.0), context.world, atStep = 0)
        assertEquals(2L, assertIs<ActionResult.Accepted>(context.submit(stop)).queuedForStep)
        context.submit(move)
        assertEquals(null, context.observe().units.single().moveTarget)
        val first = context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals(listOf("a1"), first.actionResults.map { it.actionId })
        assertEquals(0.077, first.units.single().speed, 1e-9)
        val beforeSkip = context.observe()
        assertFailsWith<IllegalArgumentException> { context.advance(Step(3, 1.0, "sim-tick")) }
        assertSame(beforeSkip, context.observe())
        context.advance(Step(1, 1.0, "sim-tick"))
        val stopped = context.advance(Step(2, 1.0, "sim-tick"))
        assertEquals(listOf("a2"), stopped.actionResults.map { it.actionId })
        assertEquals(listOf("movement-cancelled"), stopped.events.map { it.kind })
        assertEquals(null, stopped.units.single().moveTarget)
        assertEquals(0.0, stopped.units.single().speed)
    }

    @Test
    fun same_step_move_then_stop_preserves_file_order() {
        val context = context()
        context.submit(MoveAction("a1", "p1", "u1", Position(250.0, 110.0), context.world, atStep = 0))
        context.submit(StopAction("a2", "p1", "u1", context.world, atStep = 0))
        val sample = context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals(listOf("a1", "a2"), sample.actionResults.map { it.actionId })
        assertEquals(listOf("movement-started", "movement-cancelled"), sample.events.map { it.kind })
        assertEquals(null, sample.units.single().moveTarget)
        assertEquals(0.0, sample.units.single().speed)
    }

    @Test
    fun old_world_commands_and_queued_work_do_not_enter_a_reopened_world() {
        val context = context()
        val oldWorld = context.world
        val oldMove = MoveAction("a1", "p1", "u1", Position(250.0, 110.0), oldWorld, atStep = 0)
        context.submit(oldMove)
        assertEquals(CloseResult.Closed, context.close())
        assertEquals(CloseResult.AlreadyClosed, context.close())
        assertEquals("closed", assertIs<ActionResult.Rejected>(context.submit(oldMove)).error.code)
        assertFailsWith<IllegalStateException> { context.observe() }
        assertFailsWith<IllegalStateException> { context.advance(Step(0, 1.0, "sim-tick")) }
        assertIs<InitResult.Accepted>(context.reopen(TestContentFactory.openScenario()))
        assertNotEquals(oldWorld, context.world)
        val initial = context.observe()
        assertTrue(initial.events.isEmpty() && initial.actionResults.isEmpty())
        assertEquals("stale-world", assertIs<ActionResult.Rejected>(context.submit(oldMove)).error.code)
        val sample = context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals(initial.units, sample.units)
        assertTrue(sample.events.isEmpty())
        println("M07 old=${oldWorld.id} new=${context.world.id} initial=${initial.units} result=${sample.actionResults} units=${sample.units}")
    }

    @Test
    fun a_handle_from_another_context_is_rejected_even_with_the_same_display_id() {
        val first = context()
        val second = context()
        assertEquals(first.world.id, second.world.id)
        val action = MoveAction("a1", "p1", "u1", Position(250.0, 110.0), first.world)
        assertEquals("stale-world", assertIs<ActionResult.Rejected>(second.submit(action)).error.code)
        assertEquals(null, second.observe().units.single().moveTarget)
    }

    @Test
    fun duplicate_ids_past_steps_and_uncontrolled_players_are_rejected() {
        val context = context()
        val action = MoveAction("a1", "p1", "u1", Position(250.0, 110.0), context.world)
        context.submit(action)
        assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(context.submit(action)).error.code)
        context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals("past-step", assertIs<ActionResult.Rejected>(context.submit(
            StopAction("a2", "p1", "u1", context.world, atStep = 0),
        )).error.code)
        val scenario = TestContentFactory.openScenario().copy(
            units = listOf(UnitSpawn("u2", "p2", "tank", Position(110.0, 110.0), 90.0)),
        )
        val other = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        assertEquals("actor-not-controlled", assertIs<ActionResult.Rejected>(other.submit(
            StopAction("a1", "p2", "u2", other.world),
        )).error.code)
    }
}
