package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.*
import java.awt.geom.Line2D
import kotlin.math.hypot
import kotlin.test.*

class ContinuousStartConnectorTest {
    private val wall = listOf(Position(280.0, 100.0), Position(300.0, 100.0),
        Position(300.0, 120.0), Position(280.0, 120.0))
    private val terrain = PlanningTerrain(30, 20, 20, 20, BooleanArray(600) { it == 5 * 30 + 14 })

    @Test fun cs01_clear_continuous_start_can_leave_a_cell_whose_center_is_too_close_to_the_wall() {
        val start = Position(268.0, 115.0)
        val target = Position(350.0, 110.0)
        assertTrue(clear(start, start, 11.0))
        assertFalse(clear(Position(270.0, 110.0), Position(270.0, 110.0), 11.0))
        val witness = listOf(start, Position(270.0, 150.0), Position(310.0, 150.0), target)
        assertTrue(witness.zipWithNext().all { (a, b) -> clear(a, b, 11.0) })
        val path = assertNotNull(PathPlanner.findPath(terrain, start, target, 11.0),
            "A clear continuous start must not be rejected merely because its snapped center is blocked")
        assertEquals(target, path.last())
        assertTrue((listOf(start) + path).zipWithNext().all { (a, b) -> clear(a, b, 0.0) })
    }

    @Test fun cs02_actual_contact_and_embedded_starts_remain_rejected() {
        assertNotNull(PathPlanner.findPath(terrain, Position(269.0, 115.0), Position(350.0, 110.0), 11.0))
        for (start in listOf(Position(280.0, 115.0), Position(290.0, 110.0))) {
            assertFalse(clear(start, start, 11.0))
            assertNull(PathPlanner.findPath(terrain, start, Position(350.0, 110.0), 11.0))
        }
        assertNotNull(PathPlanner.findPath(terrain, Position(268.0, 115.0), Position(250.0, 115.0), 11.0))
    }

    @Test fun cs03_center_clear_root_edge_does_not_search_a_radius_expanded_graph() {
        val start = Position(268.0, 115.0)
        val target = Position(270.0, 90.0)
        assertTrue(clear(start, start, 11.0))
        assertTrue(clear(target, target, 11.0))
        assertFalse(clear(start, target, 11.0))
        // Restrict the graph to the one candidate to distinguish root-edge rejection from simplify's backstop.
        val policy = ClearanceAStarPolicy(PathCandidatePolicy { _, col, row, _ -> col == 13 && row == 4 },
            TileCenters, PlanningCenterTerrain)
        val counters = PathSearchCounters()
        assertEquals(listOf(target), policy.findPath(terrain, start, target, 11.0, counters))
        assertEquals(0L, counters.enqueued)
        assertEquals(0L, counters.popped)
        assertEquals(0L, counters.expanded)
        assertEquals(0L, counters.neighborsChecked)
    }

    private fun clear(a: Position, b: Position, radius: Double): Boolean {
        fun inside(p: Position) = p.x in 280.0..300.0 && p.y in 100.0..120.0
        if (inside(a) || inside(b)) return false
        for (index in wall.indices) {
            val c = wall[index]
            val d = wall[(index + 1) % 4]
            if (Line2D.linesIntersect(a.x, a.y, b.x, b.y, c.x, c.y, d.x, d.y)) return false
        }
        fun distance(p: Position) = hypot(maxOf(280.0 - p.x, 0.0, p.x - 300.0),
            maxOf(100.0 - p.y, 0.0, p.y - 120.0))
        return minOf(distance(a), distance(b), wall.minOf { Line2D.ptSegDist(a.x, a.y, b.x, b.y, it.x, it.y) }) > radius
    }
}
