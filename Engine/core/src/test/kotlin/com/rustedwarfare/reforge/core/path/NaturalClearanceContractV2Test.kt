package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.Position
import java.util.Random
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NaturalClearanceContractV2Test {
    @Test
    fun nc01_same_actual_point_checks_all_obstacles_without_a_cell_center_requirement() {
        val random = Random(6814)
        repeat(600) { index ->
            val width = 8
            val height = 7
            val tileWidth = 20
            val tileHeight = 30
            val blocked = BooleanArray(width * height) { random.nextInt(15) == 0 }
            val point = Position(random.nextDouble() * width * tileWidth, random.nextDouble() * height * tileHeight)
            val radius = listOf(0.0, 5.0, 10.0, 30.0, 300.0)[random.nextInt(5)]
            val terrain = PlanningTerrain(width, height, tileWidth, tileHeight, blocked)

            // Exhaust every obstacle using the actual point, independently of planner geometry.
            val pointCollides = blocked.indices.any { cell ->
                if (!blocked[cell]) false else {
                    val left = (cell % width) * tileWidth.toDouble()
                    val top = (cell / width) * tileHeight.toDouble()
                    point.x >= left && point.x < left + tileWidth && point.y >= top && point.y < top + tileHeight
                }
            }
            if (index == 83) {
                assertEquals(Position(110.68357905644558, 72.10815736035677), point)
                assertEquals(10.0, radius)
                assertFalse(pointCollides)
            }
            val path = PathPlanner.findPath(terrain, point, point, radius)
            val outside = minOf(point.x, point.y, width * tileWidth - point.x,
                height * tileHeight - point.y) < radius
            if (pointCollides || outside) assertNull(path, "same-point case $index: illegal actual endpoint")
            else assertEquals(listOf(point), path, "same-point case $index: clear zero-length direct path")
        }
    }

    @Test
    fun nc02_empty_terrain_returns_only_the_exact_horizontal_or_diagonal_target() {
        val terrain = PlanningTerrain(30, 20, 20, 20, BooleanArray(600))
        val start = Position(110.0, 110.0)
        for (target in listOf(Position(250.0, 110.0), Position(250.0, 250.0))) {
            assertEquals(listOf(target), PathPlanner.findPath(terrain, start, target, 11.0))
        }
    }

    @Test
    fun nc03_positive_and_negative_zero_radius_keep_the_exact_open_target() {
        val terrain = PlanningTerrain(30, 20, 20, 20, BooleanArray(600))
        val start = Position(110.0, 110.0)
        val target = Position(170.0, 110.0)
        for (radius in listOf(0.0, -0.0)) {
            assertEquals(listOf(target), PathPlanner.findPath(terrain, start, target, radius))
        }
    }
}
