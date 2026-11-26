package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.path.*
import kotlin.test.*

/**
 * Targeted regression tests for ENG-P016-011 stabilization fixes:
 * 1. Single-cell sealed circular unit pocket rejection (`canLeaveStart` / `isSingleCellImmobilePocket`).
 * 2. `findPathToRange` honoring `RouteOccupancy` dynamic-unit avoidance.
 * 3. Flow-field `USER_MOVE` diagnostic emission.
 */
class MovementRegression011Test {

    @Test
    fun sealed_single_cell_circular_unit_rejects_move_with_immobile_unit() {
        val w = 7
        val h = 7
        val blocked = BooleanArray(w * h) { true }
        // Single isolated passable cell at (3, 3) surrounded by blocked orthogonal neighbors
        blocked[3 * w + 3] = false
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val terrain = PlanningTerrain.fromNoFog(map, MovementType.LAND)
        val policy = ClearanceAStarPolicy(CenterClearance(PlanningCenterTerrain), TileCenters, PlanningCenterTerrain)
        val hull = HullRoute.Circle(radius = 9.0, boundaryRadius = 10.0, simplify = true)
        val search = policy.beginNearest(terrain, Position(70.0, 70.0), Position(110.0, 70.0), 10.0, hull = hull)
        while (!search.finished) search.advance(PathWorkBudget(1024))
        assertNull(search.path, "Expected sealed 1x1 circular unit with boundaryRadius=10.0 to be immobile (null path)")
    }

    @Test
    fun find_path_to_range_respects_dynamic_route_occupancy() {
        val w = 15
        val h = 9
        val blocked = BooleanArray(w * h) { idx ->
            val c = idx % w
            val r = idx / w
            c == 0 || c == w - 1 || r == 0 || r == h - 1
        }
        val map = MapDefinition(w, h, 20, 20, emptyList(), IntArray(w * h), BlockingGrid(w, h, blocked))
        val start = Position(70.0, 90.0)
        val target = Position(250.0, 90.0)
        val obstaclePos = Position(130.0, 90.0)
        val combinedR = 35.0
        val occupancy = RouteOccupancy { from, to, _, _ ->
            val dx = to.x - from.x
            val dy = to.y - from.y
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 1e-12) 0.0 else (((obstaclePos.x - from.x) * dx + (obstaclePos.y - from.y) * dy) / lenSq).coerceIn(0.0, 1.0)
            val px = from.x + dx * t
            val py = from.y + dy * t
            val dSq = (px - obstaclePos.x) * (px - obstaclePos.x) + (py - obstaclePos.y) * (py - obstaclePos.y)
            dSq >= combinedR * combinedR - 1e-6
        }
        val hull = HullRoute.Circle(radius = 9.0, boundaryRadius = 10.0, simplify = true)
        val terrain = PlanningTerrain.fromNoFog(map, MovementType.LAND)
        val path = assertNotNull(
            ClearanceAStarPolicy(CenterClearance(PlanningCenterTerrain), TileCenters, PlanningCenterTerrain).findPath(
                map = terrain,
                start = start,
                target = target,
                radius = 10.0,
                targetRange = 50.0,
                hull = hull,
                occupancy = occupancy,
            ),
            "Expected detour path around dynamic obstacle in findPathToRange",
        )
        assertTrue(path.any { p -> kotlin.math.abs(p.y - 90.0) > 25.0 }, "Expected path to detour vertically around blocker, got $path")
    }

    @Test
    fun group_flow_field_emits_initial_user_move_diagnostic_sample() {
        val base = TestContentFactory.openScenario()
        val spawns = (0 until 10).map { i ->
            UnitSpawn("u$i", "p1", "tank", Position(60.0 + (i % 5) * 28.0, 60.0 + (i / 5) * 28.0), 90.0)
        }
        val scene = base.copy(units = spawns)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            ctx.enablePathDiagnostics(ctx.world)
            val ids = spawns.map { it.unitId }
            val click = Position(360.0, 200.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, click))
            for (id in ids) {
                assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("gm-$id", "p1", id, click, ctx.world), group))
            }
            for (s in 0L until 10L) ctx.advance(Step(s, 1.0, "sim-tick"))
            val samples = ctx.drainPathDiagnostics(ctx.world)
            assertTrue(
                samples.any { it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.USER_MOVE },
                "Expected USER_MOVE diagnostic sample from shared flow field, got $samples",
            )
        } finally {
            ctx.close()
        }
    }
}
