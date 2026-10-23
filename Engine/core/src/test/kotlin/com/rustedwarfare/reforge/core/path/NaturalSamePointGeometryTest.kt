package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Natural-direct point geometry with the approved closed map boundary. */
class NaturalSamePointGeometryTest {
    private fun intersectsAnyClosedRectangle(
        point: Position,
        radius: Double,
        blocked: BooleanArray,
        width: Int,
        tileWidth: Int,
        tileHeight: Int,
    ): Boolean = blocked.indices.any { index ->
        if (!blocked[index]) false else {
            val left = (index % width) * tileWidth.toDouble()
            val top = (index / width) * tileHeight.toDouble()
            val closestX = point.x.coerceIn(left, left + tileWidth)
            val closestY = point.y.coerceIn(top, top + tileHeight)
            hypot(point.x - closestX, point.y - closestY) <= radius
        }
    }

    @Test
    fun same_actual_point_matches_full_map_geometry_for_the_original_600_inputs() {
        val random = java.util.Random(6814)
        val centerOnlyCases = mutableListOf<Int>()
        repeat(600) { case ->
            val width = 8
            val height = 7
            val tileWidth = 20
            val tileHeight = 30
            val blocked = BooleanArray(width * height) { random.nextInt(15) == 0 }
            val point = Position(random.nextDouble() * width * tileWidth, random.nextDouble() * height * tileHeight)
            val radius = listOf(0.0, 5.0, 10.0, 30.0, 300.0)[random.nextInt(5)]
            val collides = intersectsAnyClosedRectangle(point, radius, blocked, width, tileWidth, tileHeight)
            val center = Position((floor(point.x / tileWidth) + .5) * tileWidth,
                (floor(point.y / tileHeight) + .5) * tileHeight)
            if (!collides && intersectsAnyClosedRectangle(center, radius, blocked, width, tileWidth, tileHeight)) {
                centerOnlyCases += case
            }
            val outside = minOf(point.x, point.y, width * tileWidth - point.x,
                height * tileHeight - point.y) < radius
            // Retain the old six circle/center witnesses above as historical
            // geometry distinctions. The current terrain contract uses center cells.
            val blockedCenter = blocked.indices.any { cell ->
                val left = cell % width * tileWidth.toDouble(); val top = cell / width * tileHeight.toDouble()
                blocked[cell] && point.x >= left && point.x < left + tileWidth && point.y >= top && point.y < top + tileHeight
            }
            val expected = if (blockedCenter || outside) null else listOf(point)
            assertEquals(expected, PathPlanner.findPath(
                PlanningTerrain(width, height, tileWidth, tileHeight, blocked), point, point, radius,
            ), "natural same-point case $case")
        }
        assertEquals(listOf(83, 103, 186, 395, 472, 481), centerOnlyCases)
    }

    @Test
    fun case83_old_circle_contacts_do_not_change_open_center_cell_planning() {
        val blocked = BooleanArray(56).also { cells ->
            listOf(4, 13, 20, 23, 36, 39, 50, 52).forEach { cells[it] = true }
        }
        val terrain = PlanningTerrain(8, 7, 20, 30, blocked)
        val point = Position(110.68357905644558, 72.10815736035677)
        val center = Position(110.0, 75.0)
        assertFalse(intersectsAnyClosedRectangle(point, 10.0, blocked, 8, 20, 30))
        assertTrue(intersectsAnyClosedRectangle(center, 10.0, blocked, 8, 20, 30))
        assertTrue(intersectsAnyClosedRectangle(point, 11.0, blocked, 8, 20, 30))
        assertEquals(listOf(point), PathPlanner.findPath(terrain, point, point, 10.0))
        assertEquals(listOf(center), PathPlanner.findPath(terrain, center, center, 10.0))
        assertEquals(listOf(point), PathPlanner.findPath(terrain, point, point, 11.0))
    }
}
