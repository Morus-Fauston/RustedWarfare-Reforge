package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class RouteRepairTest {
    // D-083: move the repair geometry into the interior; do not rely on illegal edge candidates.
    private val target = Position(450.0, 210.0)
    private val walls = listOf(2 to 2)

    private fun open(hidden: Boolean = false, truthWalls: Boolean = true, diagnostics: Boolean = true,
                     known: Boolean = false): SimulationContext {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet())
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(Scenario("route-repair",
            MapDefinition(40, 30, 20, 20, emptyList(), IntArray(1200), BlockingGrid(40, 30,
                BooleanArray(1200) { truthWalls && it % 40 == 19 && it / 40 in 7..12 })), mapOf("tank" to unit),
            listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(360.80333715352117, 198.0989131273078), 128.3558160425307)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0)))).context
        if (hidden || known) context.enableKnowledge(context.world,
            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        if (known) context.acceptVisibleTerrain(context.world, "p1", walls.map { (column, row) ->
            VisibleTerrainCell(column, row, true)
        })
        if (diagnostics) context.enablePathDiagnostics(context.world)
        return context
    }

    @Test fun rp02_unknown_truth_never_triggers_repair_or_teaches_terrain() {
        val blocked = open(hidden = true)
        val empty = open(hidden = true, truthWalls = false)
        try {
            for (context in listOf(blocked, empty)) {
                context.submit(MoveAction("hidden-move", "p1", "u1", target, context.world))
                assertEquals(listOf(target), context.observe().units.single().pathPoints)
                assertTrue(context.drainPathDiagnostics(context.world).single().usedPlayerKnowledge)
            }
            var collision = false
            for (step in 0L until 120L) {
                val a = blocked.advance(Step(step, 1.0, "sim-tick")).units.single()
                val b = empty.advance(Step(step, 1.0, "sim-tick")).units.single()
                collision = collision || a.position != b.position
                assertTrue(blocked.drainPathDiagnostics(blocked.world).isEmpty())
                assertTrue(empty.drainPathDiagnostics(empty.world).isEmpty())
                assertEquals(blocked.knowledgeSnapshot(blocked.world, "p1").cells,
                    empty.knowledgeSnapshot(empty.world, "p1").cells)
                if (!collision) assertEquals(a, b)
                assertEquals(target, a.moveTarget)
                assertEquals(listOf(target), a.pathPoints)
            }
            assertTrue(collision, "Hidden truth must still physically stop the moving unit")
        } finally { blocked.close(); empty.close() }
    }

    private fun drift(diagnostics: Boolean = true, known: Boolean = false, truth: Boolean = true): SimulationContext {
        val source = TestContentFactory.openScenario()
        val original = source.unitDefinitions.getValue("tank")
        val unit = TestContentFactory.unitWith(original, radius = 11.0,
            movement = original.movement.copy(moveSpeed = 1.1, moveAccelerationSpeed = .07,
                moveDecelerationSpeed = .17, maxTurnSpeed = 4.1, turnAcceleration = .25))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            scenarioId = "route-repair-drift",
            map = MapDefinition(10, 10, 20, 20, emptyList(), IntArray(100), BlockingGrid(10, 10, BooleanArray(100) { truth && it == 22 })),
            unitDefinitions = mapOf("tank" to unit), units = listOf(UnitSpawn("u1", "p1", "tank", Position(39.615, 70.0), 90.0)),
            automaticVision = null,
        ))).context
        if (known) {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.acceptVisibleTerrain(context.world, "p1", listOf(VisibleTerrainCell(2, 2, true)))
        }
        context.submit(MoveAction("warmup", "p1", "u1", Position(150.0, 70.0), context.world))
        repeat(16) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
        val warm = context.observe().units.single()
        assertEquals(49.9, warm.position.x, 1e-9); assertEquals(70.0, warm.position.y)
        assertEquals(1.1, warm.speed); assertEquals(90.0, warm.heading); assertEquals(0.0, warm.angularVelocity)
        if (diagnostics) context.enablePathDiagnostics(context.world)
        return context
    }

    private val driftGoal = Position(30.0, 30.0)
    private val driftPath = listOf(Position(30.0, 50.0), driftGoal)

    private fun beginDrift(context: SimulationContext, origin: String, scheduled: Boolean = false): Observation {
        context.submit(MoveAction(origin, "p1", "u1", driftGoal, context.world, atStep = if (scheduled) 16L else null))
        val before = context.observe().units.single()
        assertTrue(clear(before.position, Position(30.0, 50.0)))
        val result = context.advance(Step(16, 1.0, "sim-tick"))
        val after = result.units.single()
        assertEquals(driftPath, after.pathPoints)
        assertEquals(49.9 + .913 * kotlin.math.sin(Math.toRadians(89.75)), after.position.x, 1e-9)
        assertEquals(70.0 - .913 * kotlin.math.cos(Math.toRadians(89.75)), after.position.y, 1e-9)
        assertEquals(.913, after.speed, 1e-9); assertEquals(-.25, after.angularVelocity)
        assertFalse(clear(after.position, after.pathPoints.first()))
        assertTrue(clear(after.pathPoints[0], after.pathPoints[1]))
        assertTrue(result.events.none { it.kind == "movement-blocked" })
        return result
    }

    private fun finish(context: SimulationContext, first: Long = 18L) {
        var arrived = false
        var stable = 0
        var previous = context.observe().units.single()
        for (step in first until 1500L) {
            val observation = context.advance(Step(step, 1.0, "sim-tick"))
            val unit = observation.units.single()
            assertFalse(unit.position.x >= 40.0 && unit.position.x < 60.0 && unit.position.y >= 40.0 && unit.position.y < 60.0)
            assertTrue(unit.position.x in 11.0..189.0 && unit.position.y in 11.0..189.0)
            if (observation.events.any { it.kind == "movement-arrived" }) arrived = true
            if (unit.moveTarget == null) assertTrue(arrived, "Cancellation cannot stand in for normal completion")
            stable = if (arrived && unit.movementState == MovementState.IDLE && unit.position == previous.position) stable + 1 else 0
            if (stable >= 18) break
            previous = unit
        }
        assertTrue(arrived, "Original 1500q budget exhausted: ${context.observe().units}")
        assertTrue(stable >= 18)
    }

    @Test fun rp01_curved_motion_repairs_a_now_unsafe_known_connection_without_a_new_discovery() {
        val context = drift()
        try {
            val before = beginDrift(context, "original-move").units.single()
            assertEquals(PathSearchReason.USER_MOVE, context.drainPathDiagnostics(context.world).single().reason)
            val after = context.advance(Step(17, 1.0, "sim-tick")).units.single()
            val sample = context.drainPathDiagnostics(context.world).single()
            assertEquals(PathSearchReason.ROUTE_REPAIR, sample.reason)
            assertEquals("original-move", sample.actionId); assertNull(sample.scheduledStep)
            assertEquals(17L, sample.executionStep); assertEquals(before.position, sample.searchStart)
            assertEquals(Position(30.0, 50.0), sample.searchTarget); assertEquals(1, sample.actualSearchCalls)
            assertFalse(sample.usedPlayerKnowledge)
            assertEquals(.726, after.speed, 1e-9); assertEquals(-.5, after.angularVelocity)
            finish(context)
        } finally { context.close() }
    }

    @Test fun rp03_diagnostics_do_not_change_repaired_motion_and_scheduled_origin_survives() {
        val measured = drift(); val plain = drift(diagnostics = false)
        try {
            assertEquals(beginDrift(plain, "scheduled-origin", true).units, beginDrift(measured, "scheduled-origin", true).units)
            measured.drainPathDiagnostics(measured.world)
            var repairs = 0; var arrived = false; var stable = 0
            var previous = measured.observe().units.single()
            for (step in 17L until 1500L) {
                val a = measured.advance(Step(step, 1.0, "sim-tick")); val b = plain.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(a.sampleId, b.sampleId); assertEquals(a.worldId, b.worldId)
                assertEquals(a.stepIndex, b.stepIndex); assertEquals(a.simulationTime, b.simulationTime)
                assertEquals(a.units, b.units); assertEquals(a.events, b.events); assertEquals(a.actionResults, b.actionResults)
                for (sample in measured.drainPathDiagnostics(measured.world)) {
                    assertEquals(PathSearchReason.ROUTE_REPAIR, sample.reason)
                    assertEquals("scheduled-origin", sample.actionId); assertEquals(16L, sample.scheduledStep)
                    assertEquals(step, sample.executionStep); assertEquals(1, sample.actualSearchCalls); repairs++
                }
                val unit = a.units.single()
                if (a.events.any { it.kind == "movement-arrived" }) arrived = true
                stable = if (arrived && unit.movementState == MovementState.IDLE && unit.position == previous.position) stable + 1 else 0
                if (stable >= 18) break
                previous = unit
            }
            assertTrue(repairs > 0); assertTrue(arrived); assertTrue(stable >= 18)
        } finally { measured.close(); plain.close() }
    }

    @Test fun rp04_scheduled_stop_wins_over_an_invalid_remaining_connection() {
        val context = drift()
        try {
            beginDrift(context, "stopped-origin"); context.drainPathDiagnostics(context.world)
            context.submit(StopAction("scheduled-stop", "p1", "u1", context.world, atStep = 17))
            val after = context.advance(Step(17, 1.0, "sim-tick"))
            assertNull(after.units.single().moveTarget); assertTrue(after.units.single().pathPoints.isEmpty())
            assertEquals(.726, after.units.single().speed, 1e-9)
            assertTrue(after.events.any { it.kind == "movement-cancelled" })
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
            repeat(24) { offset ->
                assertNull(context.advance(Step(18L + offset, 1.0, "sim-tick")).units.single().moveTarget)
                assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
            }
            assertEquals(MovementState.IDLE, context.observe().units.single().movementState)
        } finally { context.close() }
    }

    @Test fun rp05_owner_repair_preserves_braking_and_does_not_consult_unobserved_truth() {
        val blocked = drift(known = true); val empty = drift(known = true, truth = false)
        try {
            assertEquals(beginDrift(blocked, "known-origin").units, beginDrift(empty, "known-origin").units)
            blocked.drainPathDiagnostics(blocked.world); empty.drainPathDiagnostics(empty.world)
            val a = blocked.advance(Step(17, 1.0, "sim-tick")); val b = empty.advance(Step(17, 1.0, "sim-tick"))
            assertEquals(a.units, b.units); assertEquals(a.events, b.events)
            val sa = blocked.drainPathDiagnostics(blocked.world).single(); val sb = empty.drainPathDiagnostics(empty.world).single()
            assertTrue(sa.usedPlayerKnowledge && sb.usedPlayerKnowledge)
            assertEquals(sa.searchStart, sb.searchStart); assertEquals(sa.searchTarget, sb.searchTarget)
            assertEquals(sa.actualSearchCalls, sb.actualSearchCalls)
            assertEquals(.726, a.units.single().speed, 1e-9); assertEquals(-.5, a.units.single().angularVelocity)
            finish(blocked)
        } finally { blocked.close(); empty.close() }
    }

    @Test fun rp06_reconnect_keeps_the_safe_old_anchor_and_suffix_and_arrives_at_original_goal() {
        val context = drift(known = true)
        try {
            val before = beginDrift(context, "suffix-origin").units.single()
            context.drainPathDiagnostics(context.world)
            assertTrue(hypot(before.position.x - 30.0, before.position.y - 50.0) > 16.0)
            val after = context.advance(Step(17, 1.0, "sim-tick")).units.single()
            val sample = context.drainPathDiagnostics(context.world).single()
            assertEquals(driftPath, after.pathPoints.takeLast(2))
            assertEquals(listOf(Position(30.0, 70.0)) + driftPath, after.pathPoints)
            assertEquals(driftGoal, after.moveTarget)
            assertEquals(PathSearchReason.ROUTE_REPAIR, sample.reason); assertEquals("suffix-origin", sample.actionId)
            assertEquals(17L, sample.executionStep); assertEquals(before.position, sample.searchStart)
            assertEquals(driftPath.first(), sample.searchTarget); assertEquals(1, sample.actualSearchCalls)
            finish(context)
        } finally { context.close() }
    }

    private fun clear(a: Position, b: Position): Boolean = walls.all { (column, row) ->
        val left = column * 20.0
        val top = row * 20.0
        val right = left + 20.0
        val bottom = top + 20.0
        fun distance(p: Position) = hypot(maxOf(left - p.x, 0.0, p.x - right), maxOf(top - p.y, 0.0, p.y - bottom))
        val corners = listOf(Position(left, top), Position(right, top), Position(right, bottom), Position(left, bottom))
        corners.indices.none { index ->
            val c = corners[index]
            val d = corners[(index + 1) % 4]
            Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)
        } && minOf(distance(a), distance(b), corners.minOf { Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y) }) > 0.0
    }
}
