package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NaturalPathTest {
    @Test fun np01_open_diagonal_preserves_the_exact_target_without_grid_center_detours() {
        val terrain = PlanningTerrain(5, 5, 20, 20, BooleanArray(25))
        val target = Position(70.0, 70.0)
        assertEquals(listOf(target), PathPlanner.findPath(terrain, Position(30.0, 30.0), target, 1.0))
    }

    @Test fun np02_known_obstacle_preserves_safe_turns_and_removes_redundant_centers() {
        val cells = BooleanArray(25).apply { this[2 * 5 + 2] = true }
        val terrain = PlanningTerrain(5, 5, 20, 20, cells)
        assertEquals(listOf(Position(30.0, 30.0), Position(70.0, 30.0), Position(70.0, 50.0)),
            PathPlanner.findPath(terrain, Position(30.0, 50.0), Position(70.0, 50.0), 1.0))
    }

    @Test fun np03_old_circle_tangency_does_not_reject_a_clear_center_line() {
        val cells = BooleanArray(25).apply { this[1 * 5 + 2] = true }
        val terrain = PlanningTerrain(5, 5, 20, 20, cells)
        // y=50 is exactly ten units below the closed obstacle bottom y=40.
        // The diagonal to (70,70) misses the nearest corner by sqrt(180) > 10.
        assertEquals(listOf(Position(70.0, 50.0)),
            PathPlanner.findPath(terrain, Position(30.0, 50.0), Position(70.0, 50.0), 10.0))
        // (39,41) lies inside the expanded AABB but sqrt(2) away from the actual corner.
        assertEquals(listOf(Position(39.0, 41.0)),
            PathPlanner.findPath(terrain, Position(39.0, 41.0), Position(39.0, 41.0), 1.0))
    }

    @Test fun np04_exact_noncenter_targets_reverse_paths_and_rectangular_tiles() {
        val terrain = PlanningTerrain(5, 5, 20, 30, BooleanArray(25))
        val start = Position(31.0, 29.0)
        val target = Position(72.0, 73.0)
        for (radius in listOf(0.0, 1.0, 19.0)) {
            assertEquals(listOf(target), PathPlanner.findPath(terrain, start, target, radius))
            assertEquals(listOf(start), PathPlanner.findPath(terrain, target, start, radius))
        }
    }

    @Test fun np05_solid_wall_and_blocked_endpoints_remain_unreachable() {
        val cells = BooleanArray(25).apply { for (row in 0..4) this[row * 5 + 2] = true }
        val terrain = PlanningTerrain(5, 5, 20, 20, cells)
        assertNull(PathPlanner.findPath(terrain, Position(30.0, 50.0), Position(70.0, 50.0), 1.0))
        assertNull(PathPlanner.findPath(terrain, Position(50.0, 50.0), Position(70.0, 50.0), 0.0))
        assertNull(PathPlanner.findPath(terrain, Position(30.0, 50.0), Position(50.0, 50.0), 0.0))
        assertNull(PathPlanner.findPath(terrain, Position(-1.0, 50.0), Position(70.0, 50.0), 1.0))
    }
}
