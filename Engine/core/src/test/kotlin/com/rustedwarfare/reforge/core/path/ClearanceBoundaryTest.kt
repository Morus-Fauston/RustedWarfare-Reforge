package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

class ClearanceBoundaryTest {
    @Test fun same_cell_planning_matches_independent_full_map_collision_oracle() {
        val random = java.util.Random(6814)
        repeat(600) {
            val width = 8; val height = 7; val tw = 20; val th = 30
            val blocked = BooleanArray(width * height) { random.nextInt(15) == 0 }
            val terrain = PlanningTerrain(width, height, tw, th, blocked)
            val point = Position(random.nextDouble() * width * tw, random.nextDouble() * height * th)
            val radius = listOf(0.0, 5.0, 10.0, 30.0, 300.0)[random.nextInt(5)]
            // Exhaustive reference deliberately has no local-cell bounds.
            fun collides(p: Position): Boolean = blocked.indices.any { index ->
                if (!blocked[index]) false else {
                    val left = (index % width) * tw.toDouble()
                    val top = (index / width) * th.toDouble()
                    p.x >= left && p.x < left + tw && p.y >= top && p.y < top + th
                }
            }
            // D-076 uses the actual point; D-083 requires the entire circle inside the map.
            val edgeDistance = minOf(point.x, point.y, width * tw - point.x, height * th - point.y)
            val expected = if (collides(point) || edgeDistance < radius) null else listOf(point)
            assertEquals(expected, PathPlanner.findPath(terrain, point, point, radius), "case $it")
        }
    }

    @Test fun radius_contact_outside_a_terrain_cell_does_not_block_rectangular_tile_planning() {
        // Cell (2,2) spans x=40..60, y=60..90. A radius-5 circle
        // touches its four sides; 3/4/5 gives an exact corner contact.
        val cells = BooleanArray(64).also { it[2 * 8 + 2] = true }
        val terrain = PlanningTerrain(8, 8, 20, 30, cells)
        val contacts = listOf(
            Position(35.0, 75.0) to Position(34.99, 75.0),
            Position(65.0, 75.0) to Position(65.01, 75.0),
            Position(50.0, 55.0) to Position(50.0, 54.99),
            Position(50.0, 95.0) to Position(50.0, 95.01),
            Position(37.0, 56.0) to Position(36.99, 56.0),
        )
        for ((touching, separated) in contacts) {
            assertEquals(listOf(touching), PathPlanner.findPath(terrain, touching, touching, 5.0), "$touching")
            assertEquals(listOf(separated), PathPlanner.findPath(terrain, separated, separated, 5.0), "$separated")
        }
    }

    @Test fun zero_radius_grid_line_contact_and_map_spanning_radius_preserve_collision() {
        val cells = BooleanArray(64).also { it[2 * 8 + 2] = true }
        val terrain = PlanningTerrain(8, 8, 20, 30, cells)
        val edge = Position(60.0, 75.0)
        assertEquals(listOf(edge), PathPlanner.findPath(terrain, edge, edge, 0.0))
        assertNull(PathPlanner.findPath(terrain, Position(40.0, 75.0), Position(40.0, 75.0), 0.0))
        val beyond = Position(60.01, 75.0)
        assertEquals(listOf(beyond), PathPlanner.findPath(terrain, beyond, beyond, 0.0))
        val origin = Position(10.0, 15.0)
        assertNull(PathPlanner.findPath(terrain, origin, origin, Double.MAX_VALUE))
        val empty = PlanningTerrain(8, 8, 20, 30, BooleanArray(64))
        assertNull(PathPlanner.findPath(empty, origin, origin, Double.MAX_VALUE))
    }
}
