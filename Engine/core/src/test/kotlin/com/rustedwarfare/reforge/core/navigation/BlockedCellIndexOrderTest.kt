package com.rustedwarfare.reforge.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockedCellIndexOrderTest {
    @Test fun partial_word_rows_visit_only_the_requested_cells_in_order() {
        for (width in listOf(1, 17, 63, 64, 65, 131)) {
            val height = 21
            val cells = BooleanArray(width * height) { it % 7 in 0..2 || it % 64 == 63 }
            val index = BlockedCellIndex(width, cells)
            for (left in 0 until width step 7) for (right in left until width step 9) {
                val expected = (2..19).flatMap { y -> (left..right).filter { x -> cells[y * width + x] }.map { y * width + it } }
                val seen = mutableListOf<Int>()
                index.any(left, right, 2, 19) { x, y -> seen.add(y * width + x); false }
                assertEquals(expected, seen)
            }
        }
    }

    @Test fun centre_line_bounds_match_an_exhaustive_grid_at_cell_edges_and_corners() {
        val size = 6
        val cells = BooleanArray(size * size) { it % 7 == 0 || it == 13 }
        val map = PlanningTerrain(size, size, 20, 20, cells)
        val coordinates = listOf(0.0, 10.0, Math.nextDown(40.0), 40.0, Math.nextUp(40.0), 61.0, 120.0)
        val points = coordinates.flatMap { x -> coordinates.map { y -> com.rustedwarfare.reforge.core.sim.Position(x, y) } }
        for (from in points) for (to in points) {
            val clear = cells.indices.none { cell -> cells[cell] && PlanningSegmentClearance.entersCell(from, to, cell % size, cell / size, 20, 20) }
            assertEquals(clear, PlanningSegmentClearance.allows(map, from, to, 0.0), "$from -> $to")
        }
    }
}
