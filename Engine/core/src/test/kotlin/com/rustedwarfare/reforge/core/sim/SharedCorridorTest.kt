package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.test.*

class SharedCorridorTest {
    @Test fun a_group_retries_at_the_same_command_age_after_an_idle_start() {
        val ids = setOf("lead", "rear")
        val initial = GroupMoveRequest(WorldHandle("initial"), null, "p1", Position(40.0, 40.0), ids, true)
        val later = GroupMoveRequest(WorldHandle("later"), 78, "p1", Position(40.0, 40.0), ids, true)
        for (age in 0L..181L) {
            assertEquals(age % 60L == 0L, initial.repairDue(age))
            assertEquals(initial.repairDue(age), later.repairDue(79L + age))
        }
    }

    private class ForkRoad(extraStone: Int = -1) {
        private val width = 42
        private val height = 30
        val cells = BooleanArray(width * height) { it == extraStone || (it % width in 18..21 && it / width != 10 && it / width !in 20..23) }
        val terrain = PlanningTerrain(width, height, 20, 20, cells)
        private val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(cells.size), BlockingGrid(width, height, cells))
        private val def = TestContentFactory.unitWith(TestContentFactory.openScenario().unitDefinitions.getValue("tank"), radius = 6.0)
        private val world = WorldHandle("corridor")
        private val target = Position(640.0, 210.0)
        fun order() = GroupMoveRequest(world, null, "p1", target, setOf("front", "rear"), sharedLanding = true)
        val group = order()
        private val planner = MovePlanning()
        private val policy = ExperimentalPolicies().path

        fun replan(start: Position, old: PlannedMove, passage: BranchPassage?, moving: Boolean, shortBlocked: Boolean): PlannedMove {
            val body = MutableUnit("rear", "p1", "tank", def, start, 90.0, pathPoints = old.slot!!.path.toMutableList(),
                landingMember = LandingMember(group, branch = passage))
            if (moving) body.lastRoadProgress = RoadProgress(group, 200L, 0.4, 0.1)
            val move = MoveAction("repair", "p1", body.unitId, target, world)
            val member = MovePlanningMember(move, body, MovePlanningPose(start, 90.0, UnitContact.layer(body), def.movementCollision),
                start, 6.0, terrain, HullRoute.forUnit(body, maneuver = true), false, 0)
            val blocker = MutableUnit("other", "p1", "tank", def, Position(390.0, 210.0), 90.0)
            val occupancy = MovePlanningOccupant(blocker, MovePlanningPose(blocker.position, blocker.heading, UnitContact.layer(blocker), def.movementCollision),
                null, idle = true, obstructsRoute = true, fixed = true)
            val input = MovePlanningInput(map, listOf(member), if (shortBlocked) listOf(occupancy) else emptyList(), true, repair = ManeuverRepair.CONTACT)
            planner.repair(move, ManeuverRepair.CONTACT)
            var planned: PlannedMove? = null
            repeat(2000) {
                planner.advance(policy, PathWorkBudget(64), { _, _, _ -> input }, { _, _, _, _, result -> planned = result }, executionStep = 200L)
                planned?.let { return it }
            }
            error("contact repair did not finish")
        }

        fun repairWithTraffic(discard: Boolean): List<Any> {
            val ids = (0 until 100).map { "car-" + it.toString().padStart(2, '0') }
            val command = GroupMoveRequest(world, null, "p1", target, ids.toSet(), true)
            val start = Position(300.0, 280.0)
            val branch = BranchPassage(terrain, true, Position(390.0, 410.0), 1)
            val driver = MutableUnit(ids.first(), "p1", "tank", def, start, 90.0,
                pathPoints = mutableListOf(Position(350.0, 410.0), Position(450.0, 410.0), target),
                landingMember = LandingMember(command, branch = branch))
            val leader = MutableUnit(ids.last(), "p1", "tank", def, Position(378.0, 412.0), 90.0,
                landingMember = LandingMember(command))
            val move = MoveAction("repair", "p1", driver.unitId, target, world)
            val member = MovePlanningMember(move, driver, MovePlanningPose(start, 90.0, UnitContact.layer(driver), def.movementCollision),
                start, 6.0, terrain, HullRoute.forUnit(driver, maneuver = true), false, 0)
            val input = MovePlanningInput(map, listOf(member), emptyList(), true, repair = ManeuverRepair.CONTACT)
            var active = MovePlanning().also { it.repair(move, ManeuverRepair.CONTACT) }
            val record = mutableListOf<Any>()
            var totalReads = 0
            for (tick in 0L..1000L) {
                leader.lastRoadProgress = RoadProgress(command, tick, 0.5, 0.0)
                fun advance(state: MovePlanning): Boolean? {
                    val budget = PathWorkBudget(16)
                    val reads = mutableListOf<String>()
                    var preserve: Boolean? = null
                    state.advance(policy, budget, { _, _, _ -> input }, { _, _, _, _, plan -> preserve = plan.preserveCurrentRoute },
                        executionStep = tick, lookupTraffic = { id -> reads.add(id); leader.takeIf { it.unitId == id } })
                    assertTrue(reads.size <= budget.used && budget.used <= 16)
                    record.add(listOf(budget.used, reads.toList(), preserve))
                    totalReads += reads.size
                    return preserve
                }
                if (discard) {
                    val rollback = PathSearchRollback()
                    val savedReads = totalReads
                    advance(active.fork(rollback))
                    rollback.restore()
                    totalReads = savedReads
                    record.removeAt(record.lastIndex)
                }
                val rollback = PathSearchRollback()
                active = active.fork(rollback)
                val verdict = advance(active)
                rollback.commit()
                if (verdict != null) {
                    assertTrue(verdict, "The occupied wide approach is advancing")
                    assertEquals(100, totalReads)
                    return record
                }
            }
            error("repair did not publish")
        }

        fun drive(id: String, start: Position, queue: List<Position> = emptyList(), command: GroupMoveRequest = group,
                  knowledge: PlanningTerrain = terrain): PlannedMove {
            val unit = MutableUnit(id, "p1", "tank", def, start, 90.0, landingMember = LandingMember(command))
            val move = MoveAction("drive-$id", "p1", id, target, world)
            val member = MovePlanningMember(move, unit, MovePlanningPose(start, 90.0, UnitContact.layer(unit), def.movementCollision),
                start, 6.0, knowledge, HullRoute.forUnit(unit, maneuver = true), false, 0)
            val input = MovePlanningInput(map, listOf(member), emptyList(), true, queuePeers = queue)
            planner.enqueue(move, null)
            var result: PlannedMove? = null
            repeat(2000) {
                planner.advance(policy, PathWorkBudget(128), { _, _, _ -> input }, { _, _, _, _, plan -> result = plan })
                result?.let { return it }
            }
            error("route was never published")
        }
    }

    @Test fun rear_driver_returns_to_the_short_gate_after_the_queue_clears() {
        val road = ForkRoad()
        val queue = List(25) { i -> Position(370.0 - 4.0 * i, 210.0) }
        val long = road.drive("front", Position(260.0, 230.0), queue)
        assertTrue(long.slot!!.path.any { it.y >= 400 }, "front should divert: ${long.slot.path}")
        val clear = road.drive("rear", Position(235.0, 230.0))
        assertTrue(clear.slot!!.path.none { it.y >= 400 }, "rear inherited expired queue detour: ${clear.slot.path} ${clear.counters}")
    }

    @Test fun changed_order_or_updated_knowledge_requires_a_fresh_corridor_search() {
        for (change in listOf("none", "order", "terrain")) {
            val road = ForkRoad()
            val start = Position(260.0, 300.0)
            val cold = road.drive("front", start)
            assertTrue(cold.slot!!.path.size >= 2 && cold.counters!!.expanded > 0)
            val warm = road.drive("rear", Position(235.0, 305.0),
                command = if (change == "order") road.order() else road.group,
                knowledge = if (change == "terrain") PlanningTerrain(42, 30, 20, 20, road.cells) else road.terrain)
            assertEquals(change == "none", warm.counters!!.actualSearchCalls == 0L, change)
        }
    }

    @Test fun stone_between_follower_and_corridor_entrance_requires_a_fresh_search() {
        val road = ForkRoad(extraStone = 11 * 42 + 12)
        val lead = road.drive("front", Position(269.0, 230.0)).slot!!
        assertTrue(lead.path.size >= 2)
        val start = Position(212.0, 233.0)
        val joined = road.drive("rear", start)
        assertTrue(joined.counters!!.actualSearchCalls > 0 && joined.slot != null, "${lead.path} -> $joined")
    }

    private class RepairFork {
        val road = ForkRoad()
        val def = TestContentFactory.unitWith(TestContentFactory.openScenario().unitDefinitions.getValue("tank"), radius = 6.0)
        val target = Position(640.0, 210.0)
        fun car(id: String, x: Double, y: Double) = MutableUnit(id, "p1", "tank", def, Position(x, y), 90.0,
            speed = 1.0, landingMember = LandingMember(road.group))
    }

    @Test fun stationary_queue_with_positive_drivetrain_speed_releases_the_blocked_lane() {
        val f = RepairFork()
        val driver = f.car("rear", 335.0, 210.0)
        val leader = f.car("front", 372.0, 210.0)
        driver.pathPoints.add(f.target)
        val detour = listOf(Position(350.0, 410.0), Position(450.0, 410.0), f.target)
        val branch = BranchPassage(f.road.terrain, true, Position(390.0, 210.0), 1)
        val proof = BranchRepair(branch, f.road.group, driver.position, detour)
        assertEquals(false, proof.preserve(driver, 100, PathWorkBudget(16), mapOf("front" to leader, "rear" to driver)::get))
    }

    @Test fun a_driver_advancing_on_the_wide_branch_keeps_it_when_a_shorter_repair_cuts_back() {
        val f = RepairFork()
        val driver = f.car("rear", 335.0, 315.0)
        driver.pathPoints += listOf(Position(350.0, 410.0), Position(450.0, 410.0), f.target)
        val shorter = listOf(Position(350.0, 210.0), f.target)
        driver.lastRoadProgress = RoadProgress(f.road.group, 99, 0.5, 0.8)
        val branch = BranchPassage(f.road.terrain, true, Position(390.0, 410.0), 1)
        val proof = BranchRepair(branch, f.road.group, driver.position, shorter)
        assertEquals(true, proof.preserve(driver, 100, PathWorkBudget(16), mapOf("rear" to driver)::get))
    }

    @Test fun empty_fork_records_the_actual_passage_for_subsequent_repairs() {
        val road = ForkRoad()
        val plan = road.drive("front", Position(260.0, 300.0))
        val passage = assertNotNull(plan.branch, "An empty gate can acquire traffic after publication")
        assertTrue(passage.alongX)
        assertTrue(passage.crossing.x in 360.0..440.0 && passage.crossing.y in 200.0..220.0)
    }

    @Test fun large_queue_repair_charges_each_peer_and_retries_discarded_slices_exactly() {
        val f = RepairFork()
        val ids = (0 until 100).map { "car-" + it.toString().padStart(2, '0') }
        val command = GroupMoveRequest(f.road.group.world, null, "p1", f.target, ids.toSet(), true)
        val driver = f.car(ids.first(), 335.0, 210.0).apply { landingMember = LandingMember(command) }
        val finalPeer = f.car(ids.last(), 372.0, 210.0).apply {
            landingMember = LandingMember(command)
            lastRoadProgress = RoadProgress(command, 200L, 0.5, 0.0)
        }
        val path = listOf(Position(350.0, 410.0), Position(450.0, 410.0), f.target)
        val branch = BranchPassage(f.road.terrain, true, Position(390.0, 210.0), 1)
        val state = BranchRepair(branch, command, driver.position, path)
        val reads = mutableListOf<String>()
        fun slice(at: BranchRepair): Pair<Boolean?, Int> {
            val budget = PathWorkBudget(3)
            val keep = at.preserve(driver, 200L, budget) { id ->
                reads.add(id)
                if (id == finalPeer.unitId) finalPeer else null
            }
            return keep to budget.used
        }
        var complete = false
        repeat(36) {
            if (complete) return@repeat
            reads.clear()
            val discarded = slice(state.fork())
            val before = reads.toList()
            reads.clear()
            val result = slice(state)
            assertEquals(discarded, result)
            assertEquals(before, reads)
            assertTrue(reads.size <= result.second && result.second in 1..3)
            if (result.first != null) { assertEquals(true, result.first); complete = true }
        }
        assertTrue(complete)
    }

    @Test fun contact_publication_slices_a_large_advancing_queue_and_survives_discard() {
        assertEquals(ForkRoad().repairWithTraffic(false), ForkRoad().repairWithTraffic(true))
    }

    @Test fun an_advancing_driver_in_a_straight_empty_throat_keeps_its_short_route() {
        val road = ForkRoad()
        val start = Position(260.0, 210.0)
        val direct = road.drive("rear", start)
        assertEquals(1, direct.slot!!.path.size)
        val cut = road.replan(Position(320.0,210.0), direct, direct.branch, moving = true, shortBlocked = true)
        assertTrue(cut.slot!!.path.any { it.y >= 400 })
        assertTrue(cut.preserveCurrentRoute)
    }

    @Test fun legitimate_switch_to_the_wide_route_protects_that_route_after_motion_resumes() {
        val road = ForkRoad()
        val initial = road.drive("rear", Position(260.0, 300.0))
        val passed = road.replan(Position(310.0,245.0), initial, assertNotNull(initial.branch), moving = false, shortBlocked = true)
        assertFalse(passed.preserveCurrentRoute)
        assertTrue(passed.slot!!.path.any { it.y >= 400 })
        val recut = road.replan(Position(325.0,310.0), passed, passed.branch, moving = true, shortBlocked = false)
        assertTrue(recut.slot!!.path.none { it.y >= 400 })
        assertTrue(recut.preserveCurrentRoute)
    }

    @Test fun following_ground_vehicles_reuse_the_route_beyond_the_same_rock_gate() {
        val cold = replay(false)
        assertEquals(cold, replay(true), "Discarded candidate work must not warm another driver's corridor")
    }

    private fun replay(failSteps: Boolean): List<Any> {
        val initial = TestContentFactory.openScenario()
        val width = 42
        val height = 26
        val terrain = BooleanArray(width * height) { it % width in 18..19 && it / width !in 6..9 }
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(terrain.size), BlockingGrid(width, height, terrain))
        val units = List(6) { i -> UnitSpawn("tank-$i", "p1", "tank", Position(215.0 - 25.0 * i, 300.0), 90.0) }
        val scene = initial.copy(map = map, units = units, unitDefinitions = mapOf("tank" to
            TestContentFactory.unitWith(initial.unitDefinitions.getValue("tank"), radius = 6.0)))
        var shouldFail = false
        val policies = ExperimentalPolicies(routePreparationBudget = {
            if (shouldFail) error("discard prepared corridor")
            8
        })
        val ctx = assertIs<InitResult.Accepted>(SimulationContext(policies).initialize(scene)).context
        try {
            ctx.enablePathDiagnostics(ctx.world)
            ctx.enablePlanningWorkDiagnostics(ctx.world)
            val click = Position(630.0, 300.0)
            val group = assertNotNull(ctx.requestGroupMove("p1", units.map { it.unitId }, click))
            for (u in units) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(
                MoveAction("go-${u.unitId}", "p1", u.unitId, click, ctx.world), group))
            val recorded = mutableListOf<Any>()
            val searches = mutableMapOf<String, Long>()
            for (tick in 0L..1000L) {
                val step = Step(tick, 1.0, "sim-tick")
                if (failSteps) {
                    shouldFail = true
                    assertEquals("discard prepared corridor", assertFailsWith<IllegalStateException> { ctx.advance(step) }.message)
                    shouldFail = false
                }
                recorded += ctx.advance(step).units.map { it.position to it.pathPoints }.toString()
                val work = ctx.drainPlanningWorkDiagnostics(ctx.world).single()
                recorded.add(work.used)
                assertTrue(work.used <= work.limit)
                for (route in ctx.drainPathDiagnostics(ctx.world).filter { it.reason == PathSearchReason.USER_MOVE }) {
                    assertTrue(route.pathPointCount >= 2, "wall requires a detour")
                    searches[route.unitId] = route.expanded
                }
                if (searches.size == units.size) break
            }
            assertEquals(units.size, searches.size)
            assertTrue(searches.values.first() > 0)
            assertTrue(searches.values.drop(1).any { it == 0L }, "Expected reuse of a proved corridor: $searches")
            recorded.add(searches)
            return recorded
        } finally { ctx.close() }
    }
}
