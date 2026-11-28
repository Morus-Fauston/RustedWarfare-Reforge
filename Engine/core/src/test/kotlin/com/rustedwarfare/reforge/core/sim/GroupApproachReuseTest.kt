package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.navigation.PathSearchCounters
import com.rustedwarfare.reforge.core.navigation.PlanningSegmentClearance
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupApproachReuseTest {
    @Test
    fun nearby_slots_reuse_a_proven_approach_only_in_the_same_terrain() {
        fun terrain(gap: Int) = PlanningTerrain(18, 12, 20, 20,
            BooleanArray(18 * 12) { it % 18 == 7 && it / 18 != gap })
        val original = terrain(2)
        val start = Position(50.0, 130.0)
        val cache = PathClearanceCache()
        fun search(map: PlanningTerrain, end: Position, cache: PathClearanceCache): Long {
            val work = ClearanceAStarPolicy.DEFAULT.beginExact(map, start, end, 4.0, counters = PathSearchCounters())
            while (!work.finished) work.advance(PathWorkBudget(64), cache)
            val path = assertNotNull(work.path)
            assertEquals(end, path.last())
            for ((a, b) in (listOf(start) + path).zipWithNext())
                assertTrue(PlanningSegmentClearance.allows(map, a, b, 4.0))
            return checkNotNull(work.counters).expanded
        }
        val adjacent = Position(256.0, 130.0)
        assertTrue(search(original, Position(250.0, 130.0), cache) > 0)
        val warm = search(original, adjacent, cache.fork())
        assertTrue(warm < search(original, adjacent, PathClearanceCache()))
        assertTrue(search(terrain(9), adjacent, cache.fork()) > 0)
    }

    @Test
    fun abandoning_a_step_after_it_warmed_the_cache_restores_the_cold_replay() {
        val terrain = PlanningTerrain(18, 12, 20, 20, BooleanArray(216) { it % 18 == 7 && it / 18 != 2 })
        val start = Position(50.0, 130.0)
        val policy = ClearanceAStarPolicy.DEFAULT
        fun replay(retry: Boolean): Pair<List<Int>, List<Position>> {
            var cache = PathClearanceCache()
            val charges = mutableListOf<Int>()
            var last = emptyList<Position>()
            for (target in listOf(Position(250.0, 130.0), Position(256.0, 130.0))) {
                var active = policy.beginExact(terrain, start, target, 4.0)
                while (!active.finished) {
                    if (retry) {
                        val rollback = PathSearchRollback()
                        active.fork(rollback).advance(PathWorkBudget(64), cache.fork())
                        rollback.restore()
                    }
                    val commit = PathSearchRollback()
                    active = active.fork(commit)
                    cache = cache.fork()
                    val budget = PathWorkBudget(64)
                    active.advance(budget, cache)
                    charges += budget.used
                    commit.commit()
                }
                last = assertNotNull(active.path)
            }
            return charges to last
        }
        assertEquals(replay(false), replay(true))
    }

    @Test
    fun a_nearby_target_behind_a_corner_falls_back_to_search() {
        val map = PlanningTerrain(18, 12, 20, 20, BooleanArray(216) {
            it % 18 == 7 && it / 18 != 2 || it % 18 == 12 && it / 18 == 6
        })
        val start = Position(50.0, 130.0)
        val cache = PathClearanceCache()
        val target = Position(264.0, 130.0)
        // The previous endpoint sits just above the extra rock. Its last bend has
        // no clear view of the adjacent target beyond the rock's right edge.
        val prefix = listOf(Position(130.0, 50.0), Position(170.0, 50.0), Position(230.0, 110.0))
        val previous = prefix + Position(258.0, 116.0)
        for ((a, b) in (listOf(start) + previous).zipWithNext())
            assertTrue(PlanningSegmentClearance.allows(map, a, b, 4.0))
        assertTrue(!PlanningSegmentClearance.allows(map, prefix.last(), target, 4.0))
        cache.rememberApproach(map, 4.0, start, previous)
        val active = ClearanceAStarPolicy.DEFAULT.beginExact(map, start, target, 4.0, counters = PathSearchCounters())
        while (!active.finished) active.advance(PathWorkBudget(64), cache)
        val path = assertNotNull(active.path)
        assertEquals(target, path.last())
        assertTrue(checkNotNull(active.counters).expanded > 0)
        for ((a, b) in (listOf(start) + path).zipWithNext())
            assertTrue(PlanningSegmentClearance.allows(map, a, b, 4.0))
    }
}
