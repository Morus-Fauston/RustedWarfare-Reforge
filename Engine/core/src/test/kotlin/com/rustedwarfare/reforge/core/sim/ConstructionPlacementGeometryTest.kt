package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BuildingFootprint
import com.rustedwarfare.reforge.core.content.FootprintAnchor
import kotlin.test.*

class ConstructionPlacementGeometryTest {
    @Test fun factory_placement_uses_its_five_row_shape_and_returns_immutable_cells() {
        val point = ConstructionPlacement.snap(Position(190.0, 170.0), null)
        val geometry = ConstructionPlacement.footprint(30, 20, 20, 20, point,
            BuildingFootprint(-1, -1, 1, 3))
        assertEquals(Position(190.0, 170.0), point)
        assertTrue(geometry.withinMap)
        assertEquals(listOf(8 to 7, 9 to 7, 10 to 7, 8 to 8, 9 to 8, 10 to 8,
            8 to 9, 9 to 9, 10 to 9, 8 to 10, 9 to 10, 10 to 10, 8 to 11, 9 to 11, 10 to 11), geometry.cells)
        assertFailsWith<UnsupportedOperationException> { (geometry.cells as MutableList<Pair<Int, Int>>).clear() }
    }

    @Test fun anchors_keep_binary32_thresholds_and_closed_map_edge_cells() {
        val anchor = FootprintAnchor(20f, 20f)
        val shape = BuildingFootprint(0, 0, 1, 1)
        for ((x, point, cells) in listOf(
            Triple(198.99998474121094, Position(180.0, 160.0), listOf(8 to 7, 9 to 7, 8 to 8, 9 to 8)),
            Triple(198.99999999999997, Position(200.0, 160.0), listOf(9 to 7, 10 to 7, 9 to 8, 10 to 8)))) {
            assertEquals(point, ConstructionPlacement.snap(Position(x, 170.0), anchor))
            assertEquals(cells, ConstructionPlacement.footprint(30, 20, 20, 20, point, shape, anchor).cells)
        }
        val cell = BuildingFootprint(0, 0, 0, 0)
        assertEquals(listOf(29 to 19), ConstructionPlacement.footprint(30, 20, 20, 20, Position(600.0, 400.0), cell).cells)
        assertFalse(ConstructionPlacement.footprint(30, 20, 20, 20, Position(-0.001, 10.0), cell).withinMap)
        assertTrue(ConstructionPlacement.contains(30, 20, 20, 20, Position(570.0, 370.0), 30.0))
        assertFalse(ConstructionPlacement.contains(30, 20, 20, 20, Position(570.000001, 370.0), 30.0))
    }

    @Test fun outside_display_cells_and_clipped_live_cells_keep_distinct_policies_without_integer_wrap() {
        val shape = BuildingFootprint(-1, -1, 1, 3)
        val outside = ConstructionPlacement.footprint(30, 20, 20, 20, Position(110.0, 350.0), shape)
        assertFalse(outside.withinMap)
        assertEquals(listOf(4 to 20, 5 to 20, 6 to 20), outside.cells.takeLast(3))
        val clipped = ConstructionPlacement.footprint(30, 20, 20, 20, Position(110.0, 350.0), shape, clipToMap = true)
        assertTrue(clipped.withinMap)
        assertEquals(listOf(4 to 19, 5 to 19, 6 to 19), clipped.cells.takeLast(3))
        assertEquals(12, clipped.cells.size)
        val huge = ConstructionPlacement.footprint(30, 20, 20, 20, Position(190.0, 170.0),
            BuildingFootprint(Int.MIN_VALUE, -1, Int.MAX_VALUE, 1))
        assertFalse(huge.withinMap)
        assertTrue(huge.cells.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ConstructionPlacement.footprint(30, 20, 20, 20, Position(Double.MAX_VALUE, 170.0), shape,
                FootprintAnchor(20f, 20f))
        }
    }
}
