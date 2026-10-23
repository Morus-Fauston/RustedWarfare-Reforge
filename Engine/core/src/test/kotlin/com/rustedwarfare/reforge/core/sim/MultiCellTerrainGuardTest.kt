package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class MultiCellTerrainGuardTest {
    private fun map(walls: Set<Pair<Int, Int>>) = MapDefinition(30, 20, 20, 20,
        emptyList(), IntArray(600), BlockingGrid(30, 20, BooleanArray(600) { (it % 30 to it / 30) in walls }))

    @Test fun multi_cell_center_guard_includes_exact_corner_contacts_and_tiny_interior_intervals() {
        // Each expectation is independent literal half-open cell geometry, not a rounded t oracle.
        data class Vector(val a: Position, val b: Position, val hit: Boolean)
        val vectors = listOf(
            Vector(Position(13.0,16.0), Position(Math.nextDown(248.0),136.0), true),
            Vector(Position(13.0,16.0), Position(248.0,136.0), false),
            Vector(Position(13.0,16.0), Position(Math.nextUp(248.0),136.0), false),
            Vector(Position(10.0,70.0), Position(70.0,10.0), true), // mixed-sign corner belongs to (2,2)
            Vector(Position(10.0,10.0), Position(70.0,70.0), true),
            Vector(Position(10.0,60.0), Position(90.0,60.0), false), // excluded bottom edge
            Vector(Position(60.0,10.0), Position(60.0,90.0), false), // excluded right edge
            Vector(Position(40.0,10.0), Position(40.0,90.0), true),
            Vector(Position(10.0,40.0), Position(90.0,40.0), true),
            Vector(Position(10.0,10.0), Position(40.0,40.0), true), // included endpoint
            Vector(Position(60.0,60.0), Position(100.0,100.0), false),
            Vector(Position(40.0,40.0), Position(0.0,0.0), true), // included start
        )
        val terrain = map(setOf(2 to 2))
        for ((a,b,hit) in vectors) for ((start,end) in listOf(a to b,b to a)) {
            assertEquals(if(hit) start else end, TerrainMotion.resolve(terrain,start,end), "segment $start -> $end hit=$hit")
        }
    }

    @Test fun zero_radius_outer_edge_still_occupies_the_last_column_or_row() {
        for ((a,b,wall) in listOf(
            Triple(Position(600.0,10.0),Position(600.0,390.0),29 to 10),
            Triple(Position(10.0,400.0),Position(590.0,400.0),15 to 19),
            Triple(Position(-0.0,10.0),Position(0.0,390.0),0 to 10),
        )) for ((start,end) in listOf(a to b,b to a)) {
            assertEquals(start,TerrainMotion.resolve(map(setOf(wall)),start,end))
            assertEquals(end,TerrainMotion.resolve(map(emptySet()),start,end))
        }
    }
}
