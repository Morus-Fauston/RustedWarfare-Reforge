package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class PathDiagnosticsTest {
    private fun scenario(vararg blocked: Pair<Int, Int>): Scenario {
        val base = TestContentFactory.openScenario()
        val cells = BooleanArray(25)
        blocked.forEach { (col, row) -> cells[row * 5 + col] = true }
        val map = MapDefinition(5, 5, 20, 20, emptyList(), IntArray(25), BlockingGrid(5, 5, cells))
        return base.copy(
            scenarioId = "pd-test",
            map = map,
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 1.0)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(30.0, 50.0), 0.0)),
        )
    }

    private fun open(scenario: Scenario = scenario()): SimulationContext =
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context

    @Test
    fun pd02_real_move_collects_one_literal_path_and_immutable_drained_sample() {
        val context = open()
        try {
            val world = context.world
            context.enablePathDiagnostics(world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("pd-move", "p1", "u1", Position(70.0, 50.0), world)))
            assertEquals(listOf(Position(50.0, 50.0), Position(70.0, 50.0)), context.observe().units.single().pathPoints)
            val drained = context.drainPathDiagnostics(world)
            val sample = drained.single()
            assertEquals("pd-move", sample.actionId)
            assertEquals("u1", sample.unitId)
            assertNull(sample.scheduledStep)
            assertFalse(sample.usedPlayerKnowledge)
            assertEquals(PathSearchOutcome.FOUND, sample.outcome)
            assertEquals(1L, sample.actualSearchCalls)
            assertEquals(25L, sample.candidatesChecked)
            assertEquals(2, sample.pathPointCount)
            assertEquals(sample.enqueued, sample.heuristicCalls)
            assertTrue(sample.popped > 0)
            assertTrue(sample.expanded <= sample.popped)
            assertTrue(sample.neighborsChecked >= sample.expanded)
            assertTrue(sample.inputPreparationNanos >= 0)
            assertTrue(sample.searchNanos >= 0)
            assertTrue(context.drainPathDiagnostics(world).isEmpty())
            assertFailsWith<UnsupportedOperationException> { (drained as MutableList<PathSearchSample>).clear() }
            context.submit(StopAction("pd-stop", "p1", "u1", world))
            assertEquals(sample, drained.single())
        } finally {
            context.close()
        }
    }

    @Test
    fun pd03_blocked_goal_and_outside_target_report_real_early_attempts() {
        for ((map, target) in listOf(scenario(3 to 2) to Position(70.0, 50.0), scenario() to Position(110.0, 50.0))) {
            val context = open(map)
            try {
                context.enablePathDiagnostics(context.world)
                val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("pd-early", "p1", "u1", target, context.world)))
                assertEquals(listOf(Event("movement-cancelled", "u1"), Event("path-unreachable", "u1")), result.events)
                val sample = context.drainPathDiagnostics(context.world).single()
                assertEquals(PathSearchOutcome.UNREACHABLE, sample.outcome)
                assertEquals(1L, sample.actualSearchCalls)
                assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L), listOf(sample.candidatesChecked, sample.popped, sample.expanded, sample.enqueued, sample.neighborsChecked, sample.heuristicCalls))
                assertEquals(0, sample.pathPointCount)
                assertTrue(context.observe().units.single().pathPoints.isEmpty())
            } finally { context.close() }
        }
    }

    @Test
    fun pd04_full_wall_is_unreachable_after_full_candidate_preparation() {
        val context = open(scenario(2 to 0, 2 to 1, 2 to 2, 2 to 3, 2 to 4))
        try {
            context.enablePathDiagnostics(context.world)
            val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("pd-wall", "p1", "u1", Position(70.0, 50.0), context.world)))
            assertEquals(listOf(Event("movement-cancelled", "u1"), Event("path-unreachable", "u1")), result.events)
            val sample = context.drainPathDiagnostics(context.world).single()
            assertEquals(PathSearchOutcome.UNREACHABLE, sample.outcome)
            assertEquals(1L, sample.actualSearchCalls)
            assertEquals(25L, sample.candidatesChecked)
            assertTrue(sample.expanded >= 1L)
            assertEquals(sample.enqueued, sample.heuristicCalls)
            assertEquals(0, sample.pathPointCount)
            assertTrue(context.observe().units.single().pathPoints.isEmpty())
        } finally { context.close() }
    }

    @Test
    fun pd05_only_executed_legal_move_attempts_are_sampled() {
        val context = open()
        try {
            val world = context.world
            context.enablePathDiagnostics(world)
            assertEquals("not-owner", assertIs<ActionResult.Rejected>(context.submit(MoveAction("foreign", "p2", "u1", Position(70.0, 50.0), world))).error.code)
            assertEquals("invalid-target", assertIs<ActionResult.Rejected>(context.submit(MoveAction("bad", "p1", "u1", Position(Double.NaN, 50.0), world))).error.code)
            assertIs<ActionResult.Accepted>(context.submit(SelectAction("select", "p1", "u1", world)))
            assertEquals("duplicate-action", assertIs<ActionResult.Rejected>(context.submit(MoveAction("select", "p1", "u1", Position(70.0, 50.0), world))).error.code)
            context.submit(StopAction("stop", "p1", "u1", world))
            assertEquals("unknown-unit", assertIs<ActionResult.Rejected>(context.submit(MoveAction("unknown", "p1", "missing", Position(70.0, 50.0), world))).error.code)
            assertTrue(context.drainPathDiagnostics(world).isEmpty())
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("scheduled", "p1", "u1", Position(70.0, 50.0), world, atStep = 2)))
            assertTrue(context.drainPathDiagnostics(world).isEmpty())
            context.advance(Step(0, 1.0, "sim-tick"))
            assertTrue(context.drainPathDiagnostics(world).isEmpty())
            context.advance(Step(2, 1.0, "sim-tick"))
            val sample = context.drainPathDiagnostics(world).single()
            assertEquals("scheduled", sample.actionId)
            assertEquals(2L, sample.scheduledStep)
            assertEquals(1L, sample.actualSearchCalls)
        } finally { context.close() }
    }

    @Test
    fun pd06_owner_knowledge_route_counts_do_not_read_hidden_truth_and_follow_retention() {
        for (retention in TerrainRetention.entries) {
            val context = open(scenario(2 to 2))
            try {
                val world = context.world
                context.enableKnowledge(world, KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.enablePathDiagnostics(world)
                val beforeKnowledge = context.knowledgeSnapshot(world, "p1")
                context.submit(MoveAction("empty", "p1", "u1", Position(70.0, 50.0), world))
                assertEquals(listOf(Position(50.0, 50.0), Position(70.0, 50.0)), context.observe().units.single().pathPoints)
                val emptySample = context.drainPathDiagnostics(world).single()
                assertTrue(emptySample.usedPlayerKnowledge)
                assertEquals(25L, emptySample.candidatesChecked)
                assertEquals(PathSearchOutcome.FOUND, emptySample.outcome)
                assertEquals(beforeKnowledge.cells, context.knowledgeSnapshot(world, "p1").cells)
                context.acceptVisibleTerrain(world, "p2", listOf(VisibleTerrainCell(3, 2, true)))
                context.submit(MoveAction("other-player", "p1", "u1", Position(70.0, 50.0), world))
                assertEquals(25L, context.drainPathDiagnostics(world).single().candidatesChecked)
                context.acceptVisibleTerrain(world, "p1", listOf(VisibleTerrainCell(3, 2, true)))
                context.submit(MoveAction("known", "p1", "u1", Position(70.0, 50.0), world))
                val known = context.drainPathDiagnostics(world).single()
                assertEquals(PathSearchOutcome.UNREACHABLE, known.outcome)
                assertEquals(1L, known.actualSearchCalls)
                assertEquals(0L, known.candidatesChecked)
                context.acceptVisibleTerrain(world, "p1", emptyList())
                context.submit(MoveAction("after-empty", "p1", "u1", Position(70.0, 50.0), world))
                val afterEmpty = context.drainPathDiagnostics(world).single()
                assertEquals(if (retention == TerrainRetention.MEMORY) PathSearchOutcome.UNREACHABLE else PathSearchOutcome.FOUND, afterEmpty.outcome)
                assertEquals(if (retention == TerrainRetention.MEMORY) 0L else 25L, afterEmpty.candidatesChecked)
                assertEquals(1L, emptySample.actualSearchCalls)
            } finally { context.close() }
        }
    }

    private fun assertSameObservationValues(expected: Observation, actual: Observation) {
        assertEquals(expected.sampleId, actual.sampleId)
        assertEquals(expected.worldId, actual.worldId)
        assertEquals(expected.scenarioId, actual.scenarioId)
        assertEquals(expected.stepIndex, actual.stepIndex)
        assertEquals(expected.simulationTime, actual.simulationTime)
        assertEquals(expected.units, actual.units)
        assertEquals(expected.events, actual.events)
        assertEquals(expected.actionResults, actual.actionResults)
    }

    @Test
    fun pd07_enabled_diagnostics_preserve_all_observation_values_and_close_clears_queue() {
        val plain = open()
        val measured = open()
        try {
            measured.enablePathDiagnostics(measured.world)
            assertSameObservationValues(plain.observe(), measured.observe())
            for (context in listOf(plain, measured)) {
                context.submit(MoveAction("same", "p1", "u1", Position(70.0, 50.0), context.world))
                context.submit(MoveAction("queued", "p1", "u1", Position(70.0, 30.0), context.world, atStep = 2))
            }
            assertSameObservationValues(plain.observe(), measured.observe())
            val originalPath = measured.observe().units.single().pathPoints.toList()
            val history = measured.drainPathDiagnostics(measured.world)
            assertEquals(1, history.size)
            assertEquals(listOf(Position(50.0, 50.0), Position(70.0, 50.0)), originalPath)
            for (index in 0L..2L) {
                assertSameObservationValues(plain.advance(Step(index, 1.0, "sim-tick")), measured.advance(Step(index, 1.0, "sim-tick")))
            }
            assertEquals("queued", measured.drainPathDiagnostics(measured.world).single().actionId)
            measured.submit(MoveAction("left-buffer", "p1", "u1", Position(70.0, 50.0), measured.world))
            measured.close()
            assertIs<InitResult.Accepted>(measured.reopen(scenario()))
            measured.enablePathDiagnostics(measured.world)
            assertTrue(measured.drainPathDiagnostics(measured.world).isEmpty())
            assertEquals("same", history.single().actionId)
            assertEquals(2, history.single().pathPointCount)
            assertEquals(listOf(Position(50.0, 50.0), Position(70.0, 50.0)), originalPath)
        } finally {
            plain.close()
            measured.close()
        }
    }

    @Test
    fun pd08_actual_search_attempt_survives_later_step_rollback() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(tank, movement = tank.movement.copy(
            moveSpeed = Double.MAX_VALUE, moveAccelerationSpeed = Double.MAX_VALUE, moveDecelerationSpeed = Double.MAX_VALUE,
        ))
        val map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600)))
        val context = open(base.copy(map = map, unitDefinitions = mapOf("tank" to tank, "fast" to fast), units = listOf(
            UnitSpawn("a", "p1", "tank", Position(110.0, 150.0), 90.0),
            UnitSpawn("u1", "p1", "fast", Position(110.0, 110.0), 90.0),
        )))
        try {
            context.enablePathDiagnostics(context.world)
            context.submit(MoveAction("a-first", "p1", "a", Position(250.0, 150.0), context.world))
            context.submit(MoveAction("u-first", "p1", "u1", Position(250.0, 110.0), context.world))
            val before = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(2, context.drainPathDiagnostics(context.world).size)
            context.submit(MoveAction("attempt-before-rollback", "p1", "a", Position(270.0, 150.0), context.world, atStep = 1))
            assertFailsWith<ArithmeticException> { context.advance(Step(1, 1.0, "sim-tick")) }
            assertSame(before, context.observe())
            val attempt = context.drainPathDiagnostics(context.world).single()
            assertEquals("attempt-before-rollback", attempt.actionId)
            assertEquals(1L, attempt.scheduledStep)
            assertEquals(1L, attempt.actualSearchCalls)
            assertEquals(PathSearchOutcome.FOUND, attempt.outcome)
        } finally { context.close() }
    }

    @Test
    fun pd01_public_lifecycle_errors_have_fixed_priority_and_do_not_change_observation() {
        val context = open()
        val other = open()
        try {
            val world = context.world
            val before = context.observe()
            assertEquals("not-enabled", assertFailsWith<PathDiagnosticsException> { context.drainPathDiagnostics(world) }.code)
            assertEquals("stale-world", assertFailsWith<PathDiagnosticsException> { context.enablePathDiagnostics(other.world) }.code)
            context.enablePathDiagnostics(world)
            assertSame(before, context.observe())
            assertEquals("already-enabled", assertFailsWith<PathDiagnosticsException> { context.enablePathDiagnostics(world) }.code)
            assertTrue(context.drainPathDiagnostics(world).isEmpty())
            assertSame(before, context.observe())
            context.close()
            assertEquals("closed", assertFailsWith<PathDiagnosticsException> { context.drainPathDiagnostics(other.world) }.code)
            assertEquals("closed", assertFailsWith<PathDiagnosticsException> { context.enablePathDiagnostics(world) }.code)
            assertIs<InitResult.Accepted>(context.reopen(scenario()))
            assertEquals("stale-world", assertFailsWith<PathDiagnosticsException> { context.drainPathDiagnostics(world) }.code)
            assertEquals("not-enabled", assertFailsWith<PathDiagnosticsException> { context.drainPathDiagnostics(context.world) }.code)
        } finally {
            context.close()
            other.close()
        }
    }
}
