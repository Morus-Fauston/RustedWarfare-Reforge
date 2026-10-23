package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class CenterPlanningBoundaryTest {
    private val terrain = PlanningTerrain(30, 20, 20, 20, BooleanArray(600) { it == 6 * 30 + 14 })
    private fun path(a: Position, b: Position) = PathPlanner.findPath(terrain, a, b, 11.0)

    @Test fun half_open_edges_are_direction_independent_and_allow_zero_length_open_targets() {
        for ((a, b) in listOf(
            Position(300.0, 130.0) to Position(310.0, 130.0),
            Position(290.0, 140.0) to Position(290.0, 150.0),
            Position(300.0, 120.0) to Position(300.0, 150.0),
            Position(280.0, 140.0) to Position(310.0, 140.0),
            Position(290.0, 110.0) to Position(310.0, 130.0),
        )) {
            assertEquals(listOf(b), path(a, b), "$a -> $b")
            assertEquals(listOf(a), path(b, a), "$b -> $a")
        }
        val open = Position(300.0, 130.0)
        assertEquals(listOf(open), path(open, open))
        val blocked = Position(290.0, 130.0)
        assertNull(path(blocked, blocked))
    }

    @Test fun left_top_edges_and_included_corner_do_not_become_direct_routes() {
        assertNull(path(Position(280.0, 130.0), Position(270.0, 130.0)))
        assertNull(path(Position(290.0, 120.0), Position(290.0, 110.0)))
        val a = Position(270.0, 130.0); val b = Position(290.0, 110.0)
        val detour = assertNotNull(path(a, b))
        assertNotEquals(listOf(b), detour) // (280,120) belongs to the blocked tile.
        assertEquals(b, detour.last())
    }
}
