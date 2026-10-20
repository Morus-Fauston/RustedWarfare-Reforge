package com.rustedwarfare.reforge.core.knowledge

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.*

class KnowledgePrefixFallbackTest {
    @Test fun kpf01_unreachable_anchor_falls_back_to_the_actual_position_without_cancelling_the_move() {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet())
        val start = Position(50.0, 50.0)
        val goal = Position(50.0, 150.0)
        val input = Scenario("knowledge-prefix-fallback",
            MapDefinition(10, 10, 20, 20, emptyList(), IntArray(100), BlockingGrid(10, 10, BooleanArray(100))),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", start, 90.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.HARDCORE_FORGET,
                PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.enablePathDiagnostics(context.world)
            context.acceptVisibleTerrain(context.world, "p1", (0..5).map { VisibleTerrainCell(it, 4, true) })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-move", "p1", "u1", goal, context.world)))
            val oldPath = context.observe().units.single().pathPoints
            assertTrue(oldPath.size > 1)
            assertTrue(oldPath.first().x > 120.0, "The original wall requires a right-side prefix")
            context.drainPathDiagnostics(context.world)
            // The remembered horizontal wall is forgotten; a narrow opening in the new vertical
            // wall admits the existing continuous prefix but has no radius-11 grid-centre crossing.
            val newWalls = (0..9).filter { it !in 2..3 }.map { 4 to it }
            context.acceptVisibleTerrain(context.world, "p1", newWalls.map { (col, row) -> VisibleTerrainCell(col, row, true) })
            val prefix = mutableListOf<Position>()
            var from = start
            for (point in oldPath) {
                if (!clear(from, point, newWalls)) break
                prefix += point
                from = point
            }
            assertEquals(2, prefix.size, "Independent closed-rectangle geometry must establish a nonempty safe prefix")
            val after = context.advance(Step(0, 1.0, "sim-tick"))
            val samples = context.drainPathDiagnostics(context.world)
            println("KPF01,$oldPath,$samples,${after.units.single()}")
            assertEquals(2, samples.size)
            assertEquals(listOf(PathSearchOutcome.UNREACHABLE, PathSearchOutcome.FOUND), samples.map { it.outcome })
            assertEquals(listOf(prefix.last(), start), samples.map { it.searchStart })
            assertTrue(samples.all { it.searchTarget == goal && it.reason == PathSearchReason.KNOWLEDGE_REPLAN &&
                it.actionId == "one-move" && it.actualSearchCalls == 1L && it.preservedPrefixPointCount == 0 })
            assertEquals(listOf(0, 1), samples.map { it.pathPointCount })
            assertTrue(samples.all { it.inputPreparationNanos >= 0 && it.searchNanos >= 0 })
            assertEquals(listOf(goal), after.units.single().pathPoints)
            assertEquals(goal, after.units.single().moveTarget)
            assertTrue(after.events.none { it.kind in setOf("movement-cancelled", "path-unreachable") })
        } finally { context.close() }
    }

    @Test fun kpf02_both_real_searches_unreachable_cancel_once_and_clear_the_original_goal() {
        val unit = UnitDefinition("tank", "CustomUnitMetadata", 11.0, 11.0, 1, Path.of("unused.png"), 1.0,
            MovementProfile(MovementType.LAND, 1.1, 0.07, 0.17, 4.1, 0.25, false, false), emptySet())
        val start = Position(50.0, 50.0)
        val goal = Position(50.0, 150.0)
        val input = Scenario("knowledge-prefix-both-unreachable",
            MapDefinition(10, 10, 20, 20, emptyList(), IntArray(100), BlockingGrid(10, 10, BooleanArray(100))),
            mapOf("tank" to unit), listOf(Player("p1", PlayerControl.LOCAL)),
            listOf(UnitSpawn("u1", "p1", "tank", start, 90.0)),
            CoordinateModel("p001.coordinate.v1", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
            TimeModel("p001.time.v1", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.HARDCORE_FORGET,
                PathInformationSource.PLAYER_KNOWLEDGE, false))
            context.enablePathDiagnostics(context.world)
            context.acceptVisibleTerrain(context.world, "p1", (0..5).map { VisibleTerrainCell(it, 4, true) })
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("one-move", "p1", "u1", goal, context.world)))
            val oldPath = context.observe().units.single().pathPoints
            context.drainPathDiagnostics(context.world)
            val newWalls = (0..9).filter { it !in 2..3 }.map { 4 to it } + (0..3).map { it to 4 }
            val prefix = mutableListOf<Position>()
            var from = start
            for (point in oldPath) {
                if (!clear(from, point, newWalls)) break
                prefix += point
                from = point
            }
            assertEquals(2, prefix.size)
            context.acceptVisibleTerrain(context.world, "p1", newWalls.map { (col, row) -> VisibleTerrainCell(col, row, true) })
            val after = context.advance(Step(0, 1.0, "sim-tick"))
            val samples = context.drainPathDiagnostics(context.world)
            println("KPF02,$oldPath,$samples,${after.events}")
            assertEquals(listOf(prefix.last(), start), samples.map { it.searchStart })
            assertEquals(listOf(PathSearchOutcome.UNREACHABLE, PathSearchOutcome.UNREACHABLE), samples.map { it.outcome })
            assertTrue(samples.all { it.searchTarget == goal && it.reason == PathSearchReason.KNOWLEDGE_REPLAN &&
                it.actionId == "one-move" && it.actualSearchCalls == 1L && it.preservedPrefixPointCount == 0 && it.pathPointCount == 0 })
            assertNull(after.units.single().moveTarget)
            assertTrue(after.units.single().pathPoints.isEmpty())
            assertEquals(start, after.units.single().position)
            assertEquals(listOf("movement-started", "movement-cancelled", "path-unreachable"), after.events.map { it.kind })
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
