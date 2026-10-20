package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class AutomaticKnowledgePrefixTest {
    private val walls = (2..7).map { 14 to it }
    private val goal = Position(350.0, 110.0)

    private fun open(diagnostics: Boolean = true, failingUnit: Boolean = false): SimulationContext {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet(), 3)
        val fast = UnitDefinition("fast", unit.className, 1.0, 1.0, 1, unit.imagePath, 1.0,
            unit.movement.copy(moveSpeed = Double.MAX_VALUE, moveAccelerationSpeed = Double.MAX_VALUE,
                moveDecelerationSpeed = Double.MAX_VALUE), emptySet(), 0)
        val input = Scenario("automatic-knowledge-prefix",
            MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
                BlockingGrid(30, 20, BooleanArray(600) { it % 30 == 14 && it / 30 in 2..7 })),
            if (failingUnit) mapOf("tank" to unit, "fast" to fast) else mapOf("tank" to unit),
            listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0)) +
                if (failingUnit) listOf(UnitSpawn("z", "p1", "fast", Position(30.0, 10.0), 90.0)) else emptyList(),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0),
            KnowledgeRules(TerrainRetention.HARDCORE_FORGET, PathInformationSource.PLAYER_KNOWLEDGE, false))
        return assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context.also {
            if (diagnostics) it.enablePathDiagnostics(it.world)
        }
    }

    @Test fun akp01_automatic_new_wall_keeps_the_current_safe_prefix_and_reaches_the_original_goal() {
        val context = open()
        try {
            assertTrue(context.knowledgeSnapshot(context.world, "p1").cells.none { it.lastKnownBlocked == true })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-move", "p1", "u1", goal, context.world)))
            context.drainPathDiagnostics(context.world)
            var preserved = false
            var invalidPrefixReplaced = false
            var arrived = false
            for (step in 0L until 1500L) {
                val before = context.observe().units.single()
                val known = context.knowledgeSnapshot(context.world, "p1").cells.mapIndexedNotNull { index, cell ->
                    if (cell.lastKnownBlocked == true) index % 30 to index / 30 else null
                }
                val prefix = mutableListOf<Position>()
                var from = before.position
                for (point in before.pathPoints) {
                    if (!clear(from, point, known)) break
                    prefix += point
                    from = point
                }
                val observation = context.advance(Step(step, 1.0, "sim-tick"))
                val after = observation.units.single()
                assertTrue(clear(before.position, after.position, walls), "step=$step actual swept movement")
                val searches = context.drainPathDiagnostics(context.world)
                if (searches.any { it.reason == PathSearchReason.KNOWLEDGE_REPLAN } && prefix.isNotEmpty() &&
                    prefix.size < before.pathPoints.size &&
                    hypot(before.position.x - prefix.first().x, before.position.y - prefix.first().y) > 16.0) {
                    println("AKP01,$step,${before.position},$prefix,${before.pathPoints},${after.pathPoints},$searches")
                    assertEquals(prefix, after.pathPoints.take(prefix.size),
                        "step=$step new knowledge must retain the currently safe old prefix")
                    assertEquals(goal, after.moveTarget)
                    assertEquals("one-move", searches.first().actionId)
                    val query = searches.single()
                    assertEquals(prefix.last(), query.searchStart)
                    assertEquals(goal, query.searchTarget)
                    assertEquals(prefix.size, query.preservedPrefixPointCount)
                    assertEquals(after.pathPoints.size - prefix.size, query.pathPointCount)
                    assertEquals(1L, query.actualSearchCalls)
                    preserved = true
                }
                if (searches.any { it.reason == PathSearchReason.KNOWLEDGE_REPLAN } &&
                    before.pathPoints.isNotEmpty() && prefix.isEmpty()) {
                    val query = searches.first()
                    assertEquals(before.position, query.searchStart)
                    assertEquals(0, query.preservedPrefixPointCount)
                    assertNotEquals(before.pathPoints, after.pathPoints)
                    invalidPrefixReplaced = true
                }
                if (after.moveTarget == null) {
                    assertTrue(observation.events.any { it.kind == "movement-arrived" })
                    arrived = true
                    break
                }
            }
            assertTrue(preserved, "Automatic exploration must exercise a new wall cutting a future segment")
            assertTrue(invalidPrefixReplaced, "Later knowledge must invalidate an unsafe old first segment")
            assertTrue(arrived, "The single Move must arrive within the fixed 1500-step budget")
        } finally { context.close() }
    }

    @Test fun akp02_diagnostics_off_preserves_every_automatic_exploration_step() {
        val plain = open(false)
        val measured = open(true)
        try {
            for (context in listOf(plain, measured)) {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("scheduled", "p1", "u1", goal, context.world, 0)))
            }
            var arrived = false
            var prefixSearches = 0
            for (step in 0L until 1500L) {
                val a = plain.advance(Step(step, 1.0, "sim-tick"))
                val b = measured.advance(Step(step, 1.0, "sim-tick"))
                assertEquals(a.units, b.units, "step=$step")
                assertEquals(a.events, b.events, "step=$step")
                assertEquals(a.actionResults, b.actionResults, "step=$step")
                assertEquals(a.simulationTime, b.simulationTime)
                for (sample in measured.drainPathDiagnostics(measured.world)) {
                    assertEquals("scheduled", sample.actionId)
                    assertEquals(0L, sample.scheduledStep)
                    assertEquals(step, sample.executionStep)
                    assertNotNull(sample.searchStart)
                    assertNotNull(sample.searchTarget)
                    if (sample.reason != PathSearchReason.ROUTE_REPAIR) assertEquals(goal, sample.searchTarget)
                    if (sample.preservedPrefixPointCount > 0) prefixSearches++
                }
                if (a.units.single().moveTarget == null) { arrived = true; break }
            }
            assertTrue(prefixSearches > 0)
            assertTrue(arrived)
        } finally { plain.close(); measured.close() }
    }

    @Test fun akp03_invalid_step_preserves_pending_prefix_query_for_a_valid_retry() {
        val context = open(failingUnit = true)
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("normal", "p1", "u1", goal, context.world)))
            for (step in 0L..129L) {
                if (step == 129L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("fast", "p1", "z", Position(70.0, 10.0), context.world)))
                context.advance(Step(step, 1.0, "sim-tick"))
                context.drainPathDiagnostics(context.world)
            }
            val before = context.observe()
            val stopped = before.units.single { it.unitId == "z" }
            assertEquals(Position(30.0, 10.0), stopped.position)
            assertEquals(0.0, stopped.speed)
            assertNull(stopped.moveTarget)
            assertTrue(before.events.any { it.unitId == "z" && it.kind == "movement-boundary-blocked" })
            val knowledge = context.knowledgeSnapshot(context.world, "p1")
            assertFailsWith<IllegalArgumentException> { context.advance(Step(130, 0.5, "sim-tick")) }
            assertSame(before, context.observe())
            assertEquals(knowledge.revision, context.knowledgeSnapshot(context.world, "p1").revision)
            assertEquals(knowledge.cells, context.knowledgeSnapshot(context.world, "p1").cells)
            assertTrue(context.drainPathDiagnostics(context.world).isEmpty())
            val after = context.advance(Step(130, 1.0, "sim-tick"))
            val attempt = context.drainPathDiagnostics(context.world).single { it.unitId == "u1" }
            assertEquals(PathSearchReason.KNOWLEDGE_REPLAN, attempt.reason)
            assertEquals(1, attempt.preservedPrefixPointCount)
            assertEquals(Position(270.0, 50.0), attempt.searchStart)
            assertEquals(goal, attempt.searchTarget)
            assertEquals("normal", attempt.actionId)
            assertEquals(goal, after.units.single { it.unitId == "u1" }.moveTarget)
        } finally { context.close() }
    }

    private fun clear(a: Position, b: Position, blocked: List<Pair<Int, Int>>): Boolean = blocked.all { (column, row) ->
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
        } && minOf(distance(a), distance(b), corners.minOf { Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y) }) > 11.0
    }
}
