package com.rustedwarfare.reforge.core.navigation

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PlayerTerrainKnowledge
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.sim.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainClearanceAndPrimitiveKernelTest {
    @Test
    fun chebyshev_clearance_field_matches_exact_distance_and_incremental_updates() {
        val w = 24
        val h = 18
        val blocked = BooleanArray(w * h)
        for (y in 4..12) blocked[y * w + 10] = true
        blocked[6 * w + 18] = true

        val base = PlanningTerrain(w, h, 20, 20, blocked)
        val baseField = base.clearanceField()

        fun bruteChebyshev(grid: BooleanArray, cx: Int, cy: Int): Int {
            if (grid[cy * w + cx]) return 0
            var best = TerrainClearanceField.MAX_CLEARANCE
            for (y in 0 until h) for (x in 0 until w) {
                if (grid[y * w + x]) {
                    val d = maxOf(kotlin.math.abs(x - cx), kotlin.math.abs(y - cy))
                    if (d < best) best = d
                }
            }
            return best
        }

        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(bruteChebyshev(blocked, x, y), baseField.clearanceRings(x, y), "base ($x,$y)")
        }

        // Incremental building addition (lowering clearance locally)
        val addedBuildings = setOf(14 to 8, 15 to 8, 14 to 9, 15 to 9)
        val withBldg = base.withBuildings(addedBuildings)
        val afterAdd = blocked.copyOf().also { arr ->
            for ((x, y) in addedBuildings) arr[y * w + x] = true
        }
        val bldgField = withBldg.clearanceField()
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(bruteChebyshev(afterAdd, x, y), bldgField.clearanceRings(x, y), "afterAdd ($x,$y)")
        }

        // Incremental mixed raise + lower update WITH duplicate indices to verify deduplication safety
        val afterRemove = afterAdd.copyOf()
        val changed = mutableListOf<Int>()
        for (y in 6..10) {
            val idx = y * w + 10
            afterRemove[idx] = false
            changed += idx
            changed += idx // intentional duplicate
        }
        afterRemove[2 * w + 4] = true
        changed += (2 * w + 4)
        changed += (2 * w + 4) // intentional duplicate

        val mixedField = bldgField.withChangedCells(afterRemove, changed.toIntArray())
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(bruteChebyshev(afterRemove, x, y), mixedField.clearanceRings(x, y), "mixed ($x,$y)")
        }

        // Removing the very last obstacle on a map restores MAX_CLEARANCE everywhere
        val singleBlocked = BooleanArray(w * h).also { it[5 * w + 5] = true }
        val singleField = TerrainClearanceField.build(w, h, singleBlocked)
        assertEquals(0, singleField.clearanceRings(5, 5))
        assertEquals(1, singleField.clearanceRings(6, 5))
        val clearedField = singleField.withChangedCells(BooleanArray(w * h), intArrayOf(5 * w + 5, 5 * w + 5))
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(TerrainClearanceField.MAX_CLEARANCE, clearedField.clearanceRings(x, y), "open ($x,$y)")
        }
    }

    @Test
    fun with_updated_knowledge_incrementally_updates_clearance_and_components() {
        val w = 12
        val h = 10
        val world = WorldHandle("know-clearance")
        val store = PlayerTerrainKnowledge.create(
            world,
            "p1",
            w,
            h,
            KnowledgeRules(
                retention = TerrainRetention.MEMORY,
                shareAlliedVision = false,
                pathSource = com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE,
            ),
        )
        val snap0 = store.snapshot(world)
        val t0 = PlanningTerrain.fromKnowledge(snap0, 20, 20, MovementType.LAND)
        assertEquals(TerrainClearanceField.MAX_CLEARANCE, t0.clearanceRings(5, 5))

        // Reveal a vertical wall at col=6 splitting left & right halves
        val wallCells = (0 until h).map { row -> VisibleTerrainCell(6, row, blocked = true, groundGid = 0) }
        store.accept(world, wallCells)
        val snap1 = store.snapshot(world)
        val t1 = t0.withUpdatedKnowledge(snap0, snap1, MovementType.LAND, emptyList())
        assertEquals(0, t1.clearanceRings(6, 5))
        assertEquals(1, t1.clearanceRings(5, 5))
        assertEquals(2, t1.clearanceRings(4, 5))
        assertFalse(t1.isCellConnected(2, 5, 9, 5))
        // Original immutable terrain snapshot must remain unaffected
        assertTrue(t0.isCellConnected(2, 5, 9, 5))
        assertEquals(TerrainClearanceField.MAX_CLEARANCE, t0.clearanceRings(5, 5))
    }

    @Test
    fun large_radius_edge_start_and_subdivided_hull_circle_edge_goal_are_not_falsely_rejected() {
        // Case 1: radius = 15.0 > tileWidth/2 (10.0), start legally placed at x=15.0 inside col=0
        val w = 12
        val h = 10
        val blocked = BooleanArray(w * h)
        // Small pillar forcing A* (no direct line of sight)
        blocked[5 * w + 4] = true
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val policy = ClearanceAStarPolicy.DEFAULT

        val edgeStartPath = assertNotNull(
            policy.findPath(
                terrain,
                start = Position(15.0, 5.5 * 20.0),
                target = Position(9.5 * 20.0, 5.5 * 20.0),
                radius = 15.0,
            ),
            "Legal off-center edge start with radius > tileWidth/2 must still find a path",
        )
        assertEquals(Position(9.5 * 20.0, 5.5 * 20.0), edgeStartPath.last())

        // Case 2: subdivided(4) terrain (tileWidth=5) + HullRoute.Circle(radius=6.0) with goal near edge
        val sub = terrain.subdivided(4)
        val subPath = assertNotNull(
            policy.findPath(
                sub,
                start = Position(8.0, 5.5 * 20.0),
                target = Position(232.0, 5.5 * 20.0),
                radius = 6.0,
                hull = HullRoute.Circle(6.0, simplify = true),
            ),
            "Subdivided terrain with HullRoute.Circle and near-edge start/target must remain reachable",
        )
        assertEquals(Position(232.0, 5.5 * 20.0), subPath.last())
    }

    @Test
    fun connected_components_reject_enclosed_island_in_zero_node_expansions() {
        val w = 60
        val h = 60
        val blocked = BooleanArray(w * h)
        for (x in 44..53) {
            blocked[44 * w + x] = true
            blocked[53 * w + x] = true
        }
        for (y in 44..53) {
            blocked[y * w + 44] = true
            blocked[y * w + 53] = true
        }
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        assertTrue(terrain.isCellConnected(5, 5, 30, 30))
        assertFalse(terrain.isCellConnected(5, 5, 48, 48))
        assertTrue(terrain.isCellConnected(46, 46, 51, 51))

        val counters = PathSearchCounters()
        val policy = ClearanceAStarPolicy.DEFAULT
        val exact = policy.findPath(
            terrain,
            start = Position(5.5 * 20.0, 5.5 * 20.0),
            target = Position(48.5 * 20.0, 48.5 * 20.0),
            radius = 6.0,
            counters = counters,
        )
        assertNull(exact, "Enclosed pocket must be unreachable by exact search")
        assertEquals(0L, counters.expanded, "Connected-component check must reject with 0 node expansions")
        assertEquals(0L, counters.popped, "Connected-component check must reject with 0 heap pops")
        assertEquals(0L, counters.candidatesChecked, "No full-map clearance scan should occur on O(1) rejection")

        val nearest = policy.beginNearest(
            terrain,
            start = Position(5.5 * 20.0, 5.5 * 20.0),
            target = Position(48.5 * 20.0, 48.5 * 20.0),
            radius = 6.0,
        )
        while (!nearest.finished) nearest.advance(PathWorkBudget(Int.MAX_VALUE))
        val route = assertNotNull(nearest.path, "Nearest search must produce a route to outer wall edge")
        assertTrue(route.last().x < 44.0 * 20.0 || route.last().y < 44.0 * 20.0, "Route must stop outside enclosed box: ${route.last()}")
    }

    @Test
    fun primitive_frontier_rollback_and_cache_pooling_are_exact_and_leak_free() {
        val w = 36
        val h = 24
        val blocked = BooleanArray(w * h)
        for (y in 0..18) blocked[y * w + 10] = true
        for (y in 5 until h) blocked[y * w + 20] = true
        for (y in 0..18) blocked[y * w + 28] = true

        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val start = Position(2.5 * 20.0, 3.5 * 20.0)
        val target = Position(33.5 * 20.0, 4.5 * 20.0)
        val policy = ClearanceAStarPolicy.DEFAULT

        val directCounters = PathSearchCounters()
        val directPath = assertNotNull(policy.findPath(terrain, start, target, 6.0, counters = directCounters))
        assertTrue(directCounters.expanded > 10L)
        assertTrue(directCounters.candidatesChecked <= directCounters.neighborsChecked + 1L)

        // Large 120x120 map short detour checks << 14,400 cells
        val bigW = 120
        val bigH = 120
        val bigBlocked = BooleanArray(bigW * bigH)
        bigBlocked[10 * bigW + 12] = true
        val bigTerrain = PlanningTerrain(bigW, bigH, 20, 20, bigBlocked)
        val bigCounters = PathSearchCounters()
        assertNotNull(policy.findPath(bigTerrain, Position(10.5 * 20, 10.5 * 20), Position(14.5 * 20, 10.5 * 20), 6.0, counters = bigCounters))
        assertTrue(bigCounters.candidatesChecked < 200L, "Short detour on 120x120 map must not scan 14,400 cells: ${bigCounters.candidatesChecked}")

        // Sliced run with PathClearanceCache + PathSearchRollback: every slice tests aborted fork then committed fork
        val cache = PathClearanceCache(reuseApproach = false)
        var work = policy.beginExact(terrain, start, target, 6.0, counters = PathSearchCounters())
        var slices = 0
        while (!work.finished) {
            val beforePopped = checkNotNull(work.counters).popped
            val beforeEnqueued = work.counters!!.enqueued
            val discarded = PathSearchRollback()
            work.fork(discarded).advance(PathWorkBudget(32), cache.fork())
            discarded.restore()
            assertEquals(beforePopped, work.counters!!.popped)
            assertEquals(beforeEnqueued, work.counters!!.enqueued)

            val committed = PathSearchRollback()
            work = work.fork(committed)
            work.advance(PathWorkBudget(32), cache)
            committed.commit()
            slices++
            assertTrue(slices < 2000)
        }
        assertEquals(directPath, work.path, "Sliced + rolled-back search must be bit-identical to direct search")
        assertEquals(directCounters.expanded, work.counters!!.expanded)
        assertEquals(directCounters.popped, work.counters!!.popped)
        // After commit of completed search, primitive frontier must be pooled for reuse
        assertEquals(1, cache.pooledFrontierCount, "Completed committed search must return PrimitiveGridFrontier to cache pool")

        // Second search using same cache must acquire the pooled frontier (pool drops to 0 during search, returns to 1 on commit)
        var second = policy.beginExact(terrain, start, target, 6.0)
        val rb1 = PathSearchRollback()
        second = second.fork(rb1)
        second.advance(PathWorkBudget(24), cache)
        assertEquals(0, cache.pooledFrontierCount, "In-flight search should check out the pooled frontier")
        // Aborting this slice must return the checked-out frontier back to the pool
        rb1.restore()
        assertEquals(1, cache.pooledFrontierCount, "Aborted slice must restore checked-out frontier to pool")

        // Run second search to completion and commit -> pool back to 1 and path matches directPath
        var secondRun = policy.beginExact(terrain, start, target, 6.0)
        while (!secondRun.finished) {
            val rb = PathSearchRollback()
            secondRun = secondRun.fork(rb)
            secondRun.advance(PathWorkBudget(64), cache)
            rb.commit()
        }
        assertEquals(directPath, secondRun.path)
        assertEquals(1, cache.pooledFrontierCount, "Second committed search must return reused frontier to pool")
    }

    @Test
    fun primitive_frontier_generation_reset_and_find_path_to_range_work_correctly() {
        val w = 20
        val h = 15
        val blocked = BooleanArray(w * h)
        for (y in 2..12) blocked[y * w + 10] = true
        val terrain = PlanningTerrain(w, h, 20, 20, blocked)
        val policy = ClearanceAStarPolicy.DEFAULT

        val route = assertNotNull(
            policy.findPath(
                terrain,
                start = Position(3.5 * 20.0, 7.5 * 20.0),
                target = Position(15.5 * 20.0, 7.5 * 20.0),
                radius = 6.0,
                targetRange = 50.0,
            ),
        )
        assertTrue(route.isNotEmpty())
        val dist = kotlin.math.hypot(route.last().x - 15.5 * 20.0, route.last().y - 7.5 * 20.0)
        assertTrue(dist < 50.0, "Final stance must be inside targetRange: $dist")

        val pf = PrimitiveGridFrontier(8, 8)
        pf.setRootWithStart(0, 5, 0, 0, null)
        assertTrue(pf.hasCost(0))
        assertEquals(0, pf.costOf(0))
        pf.resetForNextSearch()
        assertFalse(pf.hasCost(0))
        assertEquals(Int.MAX_VALUE, pf.costOf(0))
        assertTrue(pf.isEmpty())
    }
}
