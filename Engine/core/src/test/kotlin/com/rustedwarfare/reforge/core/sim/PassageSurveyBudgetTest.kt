package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.test.Test
import com.rustedwarfare.reforge.core.path.PathSearchReason
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PassageSurveyBudgetTest {
    @Test fun switching_knowledge_releases_the_terrain_of_an_unfinished_survey() {
        val survey = DriverPassageSurvey()
        val point = Position(230.0, 110.0)
        fun terrain() = PlanningTerrain(25, 11, 20, 20,
            BooleanArray(25 * 11) { it % 25 in 10..13 && it / 25 != 5 })
        val collected = java.lang.ref.ReferenceQueue<PlanningTerrain>()
        fun leaveOldTerrain(): java.lang.ref.WeakReference<PlanningTerrain> {
            val old = terrain()
            assertNull(survey.at(old, point, 40.0, PathWorkBudget(5)))
            return java.lang.ref.WeakReference(old, collected)
        }
        val old = leaveOldTerrain()
        val current = terrain()
        assertNull(survey.at(current, point, 40.0, PathWorkBudget(1)))
        // Keep both the survey and its new snapshot live while the old key is collected.
        for (attempt in 1..40) {
            System.gc()
            if (collected.remove(50) === old) break
        }
        assertNull(old.get(), "Unfinished survey retained an obsolete terrain snapshot")
        assertNotNull(survey.at(current, point, 40.0, PathWorkBudget(256)))
    }

    @Test fun a_cold_single_file_gate_resumes_on_its_own_terrain_and_replays_a_discarded_slice() {
        val width = 25
        val height = 11
        fun terrain(closed: Boolean) = PlanningTerrain(width, height, 20, 20,
            BooleanArray(width * height) { cell -> cell % width in 10..13 && (closed || cell / width != 5) })
        val open = terrain(false)
        val closed = terrain(true)
        val point = Position(230.0, 110.0)
        val survey = DriverPassageSurvey()
        val active = survey.fork(PathSearchRollback())
        val exhausted = PathWorkBudget(0)
        assertNull(active.at(open, point, 40.0, exhausted))
        val discarded = PathSearchRollback()
        val attempt = survey.fork(discarded)
        val first = PathWorkBudget(1)
        assertNull(attempt.at(open, point, 40.0, first))
        assertEquals(1, first.used)
        discarded.restore()
        val retry = survey.fork(PathSearchRollback())
        var cost = 0
        var gate: NarrowPassage? = null
        while (gate == null && cost < 20000) {
            val slice = PathWorkBudget(1)
            gate = retry.at(open, point, 40.0, slice)
            assertEquals(1, slice.used)
            cost++
        }
        assertNotNull(gate)
        assertEquals(20.0, gate.width)
        val clean = DriverPassageSurvey()
        val full = PathWorkBudget(cost)
        assertEquals(gate, clean.at(open, point, 40.0, full))
        assertEquals(cost, full.used)
        assertEquals(gate, retry.at(open, point, 40.0, PathWorkBudget(0)))
        // An opposing approach samples the same single-file throat at the other mouth.
        kotlin.test.assertSame(gate, retry.at(open, Position(270.0, 110.0), 40.0, PathWorkBudget(0)))
        assertNull(retry.at(closed, point, 40.0, PathWorkBudget(0)))
    }

    @Test fun two_wall_pressed_drivers_share_only_the_work_left_by_the_move_queue() {
        val base = TestContentFactory.openScenario()
        val width = 38
        val blocked = BooleanArray(width * width) { it % width == 8 && it / width in listOf(8, 13) }
        val map = com.rustedwarfare.reforge.core.content.MapDefinition(width, width, 20, 20, emptyList(),
            IntArray(blocked.size), com.rustedwarfare.reforge.core.content.BlockingGrid(width, width, blocked))
        val cars = (0..1).map { i -> UnitSpawn("wall-$i", "p1", "tank", Position(153.0, 166.0 + 100 * i), 90.0) }
        val queued = (0 until 24).map { i -> UnitSpawn("queued-$i", "p1", "tank",
            Position(40.0 + 28 * (i % 8), 350.0 + 28 * (i / 8)), 90.0) }
        val scene = base.copy(map = map, units = cars + queued,
            unitDefinitions = mapOf("tank" to TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), 6.0)))
        val ctx = kotlin.test.assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(680.0, 480.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", queued.map { it.unitId }, click))
            for (spawn in queued) kotlin.test.assertIs<ActionResult.Accepted>(ctx.submitGroupMove(
                MoveAction("go-${spawn.unitId}", "p1", spawn.unitId, click, ctx.world), group))
            ctx.enablePlanningWorkDiagnostics(ctx.world)
            ctx.advance(Step(0, 1.0, "sim-tick"))
            val work = ctx.drainPlanningWorkDiagnostics(ctx.world).single()
            // ENG-P016-009D: 24-unit circular group uses O(1) shared flow-field direct drive (0 per-unit A* work),
            // staying strictly within the 512 step planning budget.
            kotlin.test.assertTrue(work.used in 0..512, "Step 0 planning work must stay within 512 budget: ${work.used}")
            val state = (SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
                .get(ctx) as CandidateSteps).current
            val drivers = cars.map { state.units.getValue(it.unitId) }
            val wallGroup = assertNotNull(ctx.requestGroupMove("p1", cars.map { it.unitId }, click))
            drivers.forEachIndexed { i, unit ->
                unit.updateNavigation {
                    it.withMoveTarget(click, MotionContract.sharedCluster(click))
                        .withPathPoints(listOf(Position(176.0, 191.0 + 100 * i)))
                        .withTerrainHistory(correctionTime = 4.0)
                        .withLandingMember(LandingMember(wallGroup))
                }
            }
            val retry = SimulationContext::class.java.declaredMethods.single { it.name == "recoverStalledMoves" }
                .apply { isAccessible = true }
            val retryStep = requireNotNull(wallGroup.completedStep) + 1 + ROUTE_RETRY_TICKS
            fun recover(budget: PathWorkBudget) = retry.invoke(ctx, map, retryStep, emptySet<String>(), emptySet<String>(), budget)
            state.noFogPlanning.clear()
            recover(PathWorkBudget(0))
            assertEquals(listOf(1, 1), drivers.map { it.pathPoints.size })
            kotlin.test.assertTrue(state.noFogPlanning.isEmpty(), "Exhausted wall service rebuilt the terrain")
            val available = PathWorkBudget(512)
            recover(available)
            assertEquals(listOf(2, 2), drivers.map { it.pathPoints.size })
            kotlin.test.assertTrue(available.used in 1..512)

            // A just-issued order has members before its landing pool is materialized.
            val other = assertNotNull(ctx.requestGroupMove("p1", listOf(cars.last().unitId, queued.last().unitId), click))
            drivers.last().updateNavigation { it.withLandingMember(LandingMember(other)) }
            drivers.forEachIndexed { i, u -> u.updateNavigation { it.withPathPoints(listOf(Position(176.0, 191.0 + 100 * i))) } }
            assertNull(state.landingSites[wallGroup])
            assertNull(state.landingSites[other])
            kotlin.test.assertFalse(state.sharedWallRepairDue(retryStep - 1))
            kotlin.test.assertTrue(state.sharedWallRepairDue(retryStep), "Due drivers without sites still need a reserved slice")
            ctx.advance(Step(retryStep, 1.0, "sim-tick"))
            val shared = ctx.drainPlanningWorkDiagnostics(ctx.world).single()
            val wallWork = shared.slices.filter { it.reason == PathSearchReason.ROUTE_REPAIR && it.unitIds.isEmpty() }.sumOf { it.workUnits }
            kotlin.test.assertTrue(wallWork > 0 && shared.used <= 512, "No wall service or shared budget exceeded: $shared")
        } finally { ctx.close() }
    }
}
