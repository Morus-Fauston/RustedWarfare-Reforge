package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class MotionDiagnosticsTest {
    @Test fun automatic_knowledge_and_complete_host_player_views_are_identical_with_diagnostics() {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val input = base.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
                BlockingGrid(30, 20, BooleanArray(600) { it == 314 })),
            unitDefinitions = mapOf("tank" to UnitDefinition(tank.id, tank.className, tank.radius,
                tank.displayRadius, tank.totalFrames, tank.imagePath, tank.imageScale, tank.movement,
                tank.retainedUnconsumed, 2)),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 200.0), 90.0),
                UnitSpawn("enemy", "p2", "tank", Position(500.0, 200.0), 270.0)),
            automaticVision = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false))
        val measured = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        val plain = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        fun host(a: Observation, b: Observation) {
            assertEquals(listOf(a.sampleId, a.worldId, a.scenarioId, a.stepIndex, a.simulationTime,
                a.units, a.events, a.actionResults), listOf(b.sampleId, b.worldId, b.scenarioId,
                b.stepIndex, b.simulationTime, b.units, b.events, b.actionResults))
        }
        fun player(a: PlayerObservation, b: PlayerObservation) {
            assertEquals(listOf(a.sampleId, a.worldId, a.scenarioId, a.playerId, a.stepIndex,
                a.simulationTime, a.units, a.events, a.actionResults), listOf(b.sampleId, b.worldId,
                b.scenarioId, b.playerId, b.stepIndex, b.simulationTime, b.units, b.events, b.actionResults))
            val av = assertNotNull(a.vision); val bv = assertNotNull(b.vision)
            assertEquals(listOf(av.worldId, av.playerId, av.epoch, av.basicCells, av.effectiveCells),
                listOf(bv.worldId, bv.playerId, bv.epoch, bv.basicCells, bv.effectiveCells))
            val ak = assertNotNull(a.knowledge); val bk = assertNotNull(b.knowledge)
            assertEquals(listOf(ak.worldId, ak.playerId, ak.width, ak.height, ak.revision, ak.cells),
                listOf(bk.worldId, bk.playerId, bk.width, bk.height, bk.revision, bk.cells))
        }
        try {
            measured.enableMotionDiagnostics(measured.world)
            for (context in listOf(measured, plain)) {
                assertNull(context.knowledgeSnapshot(context.world, "p1").cell(14, 10).lastKnownBlocked)
                assertEquals(listOf("u1"), context.observePlayer(context.world, "p1").units.map { it.unitId })
                context.submit(MoveAction("move", "p1", "u1", Position(450.0, 200.0), context.world))
            }
            var discovered = false; var memory = false
            for (step in 0L..900L) {
                host(measured.advance(Step(step, 1.0, "sim-tick")), plain.advance(Step(step, 1.0, "sim-tick")))
                for (id in listOf("p1", "p2")) player(measured.observePlayer(measured.world, id), plain.observePlayer(plain.world, id))
                val knowledge = measured.knowledgeSnapshot(measured.world, "p1")
                discovered = discovered || knowledge.cell(14, 10).lastKnownBlocked == true
                memory = memory || knowledge.cells.any { !it.visible && it.lastKnownBlocked != null }
                assertEquals(2, measured.drainMotionDiagnostics(measured.world).size)
            }
            assertTrue(discovered); assertTrue(memory)
        } finally { measured.close(); plain.close() }
    }

    private fun open(): SimulationContext {
        val source = TestContentFactory.openScenario()
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600))),
            units = listOf(UnitSpawn("u1", "p1", "tank", Position(100.0, 100.0), 90.0)), automaticVision = null))).context
    }

    @Test fun actual_terrain_correction_and_both_boundary_stages_keep_distinct_exact_inputs() {
        data class Example(val start: Position, val heading: Double, val speed: Double, val radius: Double,
                           val wall: Int?, val goal: Position, val step: Long, val expected: MotionTerrainDisposition)
        for (example in listOf(
            Example(Position(279.5, 200.0), 90.0, 1.0, 11.0, 314, Position(500.0, 200.0), 0, MotionTerrainDisposition.CORRECTED),
            Example(Position(580.1, 200.0), 270.0, .2, 19.9, 328, Position(40.0, 200.0), 0, MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP),
            Example(Position(550.0, 200.0), 90.0, 20.0, 11.0, null, Position(589.0, 200.0), 1, MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP),
        )) {
            val source = TestContentFactory.openScenario(); val tank = source.unitDefinitions.getValue("tank")
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600) { it == example.wall })),
                unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(tank, radius = example.radius,
                    movement = tank.movement.copy(moveSpeed = example.speed, moveAccelerationSpeed = 1.0, moveDecelerationSpeed = 1.0))),
                units = listOf(UnitSpawn("u1", "p1", "tank", example.start, example.heading)), automaticVision = null))).context
            try {
                context.enableKnowledge(context.world, com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                    com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                    com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.enableMotionDiagnostics(context.world)
                context.submit(MoveAction("move", "p1", "u1", example.goal, context.world))
                if (example.step == 1L) { context.advance(Step(0, 1.0, "sim-tick")); context.drainMotionDiagnostics(context.world) }
                val before = context.observe().units.single()
                val after = context.advance(Step(example.step, 1.0, "sim-tick")).units.single()
                val trace = context.drainMotionDiagnostics(context.world).single()
                assertEquals(example.expected, trace.terrainDisposition)
                assertEquals(before.position, trace.positionBefore); assertEquals(after.position, trace.finalPosition)
                assertEquals(example.speed, trace.integratedSpeed)
                when (example.expected) {
                    MotionTerrainDisposition.CORRECTED -> {
                        assertEquals(280.5, trace.candidate.x); assertEquals(279.8, assertNotNull(trace.terrainResolved).x, 1e-9)
                        assertEquals(trace.terrainResolved, trace.finalPosition); assertEquals(example.speed, trace.finalSpeed)
                    }
                    MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP -> {
                        assertEquals(579.9, trace.candidate.x, 1e-9); assertEquals(580.2, assertNotNull(trace.terrainResolved).x, 1e-9)
                        assertEquals(before.position, trace.finalPosition); assertEquals(0.0, trace.finalSpeed)
                    }
                    MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP -> {
                        assertEquals(590.0, trace.candidate.x); assertNull(trace.terrainResolved)
                        assertEquals(before.position, trace.finalPosition); assertEquals(0.0, trace.finalSpeed)
                    }
                    else -> error("Unexpected example")
                }
            } finally { context.close() }
        }
    }

    @Test fun enabled_diagnostics_capture_exact_committed_inputs_without_changing_any_observation() {
        val measured = open(); val plain = open()
        try {
            val cached = measured.observe()
            measured.enableMotionDiagnostics(measured.world)
            assertSame(cached, measured.observe())
            for (context in listOf(measured, plain)) context.submit(MoveAction("move", "p1", "u1", Position(250.0, 100.0), context.world))
            var previous = measured.observe().units.single()
            for (step in 0L..180L) {
                val a = measured.advance(Step(step, 1.0, "sim-tick")); val b = plain.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(a.units, b.units); assertEquals(a.events, b.events); assertEquals(a.actionResults, b.actionResults)
                assertEquals(a.sampleId, b.sampleId)
                assertEquals(measured.observePlayer(measured.world, "p1").units, plain.observePlayer(plain.world, "p1").units)
                val samples = measured.drainMotionDiagnostics(measured.world)
                val trace = samples.single(); val after = a.units.single()
                assertEquals(step, trace.stepIndex); assertEquals(step.toDouble(), trace.timeBefore)
                assertEquals(previous.position, trace.positionBefore); assertEquals(after.position, trace.finalPosition)
                assertEquals(after.heading, trace.finalHeading); assertEquals(after.speed, trace.finalSpeed)
                assertEquals(MotionTerrainDisposition.DIRECT, trace.terrainDisposition)
                assertEquals(trace.candidate, trace.terrainResolved); assertEquals(trace.terrainResolved, trace.finalPosition)
                if (a.events.any { it.kind == "movement-arrived" }) {
                    assertTrue(trace.normalCompletion); assertFalse(trace.steeringInvoked)
                    assertEquals("move", trace.originMoveId)
                }
                if (!trace.steeringInvoked) { assertNull(trace.steeringAim); assertNull(trace.finalLocalPoint); assertNull(trace.steeringDifference) }
                assertFailsWith<UnsupportedOperationException> { (samples as MutableList<MotionStepSample>).clear() }
                assertTrue(measured.drainMotionDiagnostics(measured.world).isEmpty())
                assertSame(a, measured.observe())
                previous = after
            }
        } finally { measured.close(); plain.close() }
    }

    @Test fun lifecycle_and_invalid_steps_preserve_successful_undrained_samples() {
        val context = open(); val old = context.world
        try {
            assertEquals("not-enabled", assertFailsWith<MotionDiagnosticsException> { context.drainMotionDiagnostics(old) }.code)
            context.enableMotionDiagnostics(old)
            assertEquals("already-enabled", assertFailsWith<MotionDiagnosticsException> { context.enableMotionDiagnostics(old) }.code)
            context.advance(Step(0, 1.0, "sim-tick"))
            assertFailsWith<IllegalArgumentException> { context.advance(Step(1, .5, "sim-tick")) }
            val sample = context.drainMotionDiagnostics(old).single()
            assertEquals(0L, sample.stepIndex); assertFalse(sample.steeringInvoked)
            context.close()
            assertEquals("closed", assertFailsWith<MotionDiagnosticsException> { context.drainMotionDiagnostics(old) }.code)
            assertIs<InitResult.Accepted>(context.reopen(TestContentFactory.openScenario()))
            assertEquals("stale-world", assertFailsWith<MotionDiagnosticsException> { context.enableMotionDiagnostics(old) }.code)
            assertEquals("not-enabled", assertFailsWith<MotionDiagnosticsException> { context.drainMotionDiagnostics(context.world) }.code)
        } finally { context.close() }
    }
}
