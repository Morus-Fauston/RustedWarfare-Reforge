package com.rustedwarfare.reforge.core.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SimulationBoundaryTest {
    private fun initialize(scenario: Scenario = TestContentFactory.openScenario()): SimulationContext =
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context

    @Test
    fun observe_does_not_consume_actions_or_events_and_repeated_reads_are_stable() {
        val context = initialize()
        context.submit(MoveAction("a1", "p1", "u1", Position(170.0, 110.0), context.world))
        val first = context.observe()
        val second = context.observe()
        assertEquals(first.events, second.events)
        assertEquals(first.actionResults, second.actionResults)
        assertSame(first, second)
        val stepped = context.advance(Step(0, 1.0, "sim-tick"))
        assertEquals(listOf("movement-started"), stepped.events.map { it.kind })
        assertEquals(listOf("a1"), stepped.actionResults.map { it.actionId })
        assertSame(stepped, context.observe())
        assertTrue(context.advance(Step(1, 1.0, "sim-tick")).actionResults.isEmpty())
    }

    @Test
    fun step_zero_cannot_be_replayed_and_rejected_steps_do_not_change_the_world() {
        val context = initialize()
        context.advance(Step(0, 1.0, "sim-tick"))
        val before = context.observe()
        assertFailsWith<IllegalArgumentException> { context.advance(Step(0, 1.0, "sim-tick")) }
        assertFailsWith<IllegalArgumentException> { context.advance(Step(1, Double.NaN, "sim-tick")) }
        assertFailsWith<IllegalArgumentException> { context.advance(Step(1, 0.5, "sim-tick")) }
        assertFailsWith<IllegalArgumentException> { context.advance(Step(1, 1.0, "seconds")) }
        assertSame(before, context.observe())
        assertEquals(2.0, context.advance(Step(1, 1.0, "sim-tick")).simulationTime)
    }

    @Test
    fun select_is_owner_independent_and_does_not_change_a_unit() {
        val context = initialize()
        val before = context.observe().units
        val result = context.submit(SelectAction("a1", "p2", "u1", context.world))
        assertTrue(assertIs<ActionResult.Accepted>(result).events.isEmpty())
        assertEquals(before, context.observe().units)
    }

    @Test
    fun caller_collections_are_not_the_live_scenario() {
        val source = TestContentFactory.openScenario()
        val players = source.players.toMutableList()
        val units = source.units.toMutableList()
        val definitions = source.unitDefinitions.toMutableMap()
        val context = initialize(source.copy(players = players, units = units, unitDefinitions = definitions))
        players.clear()
        units.clear()
        definitions.clear()
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(170.0, 110.0), context.world)))
        assertEquals(1, context.advance(Step(0, 1.0, "sim-tick")).units.size)
    }

    @Test
    fun initialization_rejects_non_finite_values_unsupported_models_and_missing_contract() {
        val source = TestContentFactory.openScenario()
        val invalid = listOf(
            source.copy(movementContract = null),
            source.copy(movementContract = MovementContract(Double.NaN, 4.0)),
            source.copy(coordinateModel = source.coordinateModel.copy(xAxis = "left")),
            source.copy(timeModel = source.timeModel.copy(deltaUnit = "seconds")),
            source.copy(coordinateModel = source.coordinateModel.copy(precision = "")),
            source.copy(timeModel = source.timeModel.copy(precision = "")),
            source.copy(timeModel = source.timeModel.copy(stepRule = "wall-clock")),
            source.copy(scenarioId = "invalid id"),
            source.copy(units = listOf(source.units.single().copy(heading = Double.NaN))),
            source.copy(units = listOf(source.units.single().copy(position = Position(Double.POSITIVE_INFINITY, 100.0)))),
        )
        invalid.forEach { assertIs<InitResult.Rejected>(SimulationContext.initialize(it), it.toString()) }
    }

    @Test
    fun observation_and_result_collections_cannot_be_written_or_cast_to_mutate_the_world() {
        val context = initialize()
        val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("a1", "p1", "u1", Position(170.0, 110.0), context.world)))
        val sample = context.observe()
        assertFailsWith<UnsupportedOperationException> { (sample.units as MutableList<UnitObservation>).clear() }
        assertFailsWith<UnsupportedOperationException> { (sample.units.single().pathPoints as MutableList<Position>)[0] = Position(999.0, 999.0) }
        assertFailsWith<UnsupportedOperationException> { (sample.events as MutableList<Event>).clear() }
        assertFailsWith<UnsupportedOperationException> { (sample.actionResults as MutableList<ActionResult>).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.events as MutableList<Event>).clear() }
        assertEquals(sample.units, context.observe().units)
    }
}
