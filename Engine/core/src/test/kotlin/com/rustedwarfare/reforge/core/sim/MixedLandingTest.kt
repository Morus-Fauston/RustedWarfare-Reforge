package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.MapDefinition
import kotlin.test.*

class MixedLandingTest {
    private val click = Position(420.0, 260.0)

    private fun scenario(blocked: (Int, Int) -> Boolean, smallStart: Position): Scenario {
        val base = TestContentFactory.openScenario()
        val tank = base.unitDefinitions.getValue("tank")
        val cells = BooleanArray(38 * 38) { blocked(it % 38, it / 38) }
        return base.copy(map = MapDefinition(38, 38, 20, 20, emptyList(), IntArray(cells.size), BlockingGrid(38, 38, cells)),
            unitDefinitions = mapOf("small" to TestContentFactory.unitWith(tank, 6.0), "big" to TestContentFactory.unitWith(tank, 18.0)),
            units = listOf(UnitSpawn("a", "p1", "big", Position(180.0, 260.0), 90.0), UnitSpawn("b", "p1", "small", smallStart, 90.0)))
    }

    private fun drive(scene: Scenario, target: Position = click, moving: Set<String> = setOf("a", "b"),
                      policies: ExperimentalPolicies = ExperimentalPolicies(),
                      checkStep: (Observation, List<MotionStepSample>) -> Boolean) {
        val ctx = assertIs<InitResult.Accepted>(SimulationContext(policies).initialize(scene)).context
        try {
            val ids = scene.units.map { it.unitId }.filter { it in moving }
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, target))
            for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("go-$id", "p1", id, target, ctx.world), group))
            ctx.enableMotionDiagnostics(ctx.world)
            repeat(3000) { tick ->
                val observation = ctx.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                if (checkStep(observation, ctx.drainMotionDiagnostics(ctx.world))) return
            }
            fail("Group did not settle: ${ctx.observe().units.map { listOf(it.unitId, it.position, it.moveTarget, it.moveArrival) }}")
        } finally { ctx.close() }
    }

    @Test fun first_car_through_a_single_entrance_parks_deeper_regardless_of_id() =
        checkSingleEntrance(ExperimentalPolicies())

    @Test fun a_sliced_parking_search_preserves_the_followers_shallow_approach() =
        checkSingleEntrance(ExperimentalPolicies(movePlanningBudgetPerStep = 8))

    private fun checkSingleEntrance(policies: ExperimentalPolicies) {
        val mouth = 360.0
        val end = Position(450.0, 270.0)
        for (frontId in listOf("a", "b")) {
            val room = scenario({ x, y -> x >= 23 || x >= 18 && y != 13 }, Position(200.0, 270.0))
            val cars = room.units.map { spawn ->
                spawn.copy(typeId = "small", position = Position(if (spawn.unitId == frontId) 320.0 else 220.0, end.y))
            }
            var firstIn: String? = null
            val firstRights = mutableMapOf<String, Pair<Long, Position>>()
            var furthestRearX = cars.single { it.unitId != frontId }.position.x
            var quiet = 0
            var stopped: List<Position>? = null
            drive(room.copy(units = cars), end, policies = policies) { observation, motion ->
                val front = observation.units.single { it.unitId == frontId }
                val rear = observation.units.single { it.unitId != frontId }
                for (sample in motion) {
                    val site = sample.landingClaim ?: sample.landingIngress ?: continue
                    firstRights.putIfAbsent(sample.unitId, sample.stepIndex to site)
                }
                furthestRearX = maxOf(furthestRearX, rear.position.x)
                if (firstIn == null) firstIn = observation.units.firstOrNull { it.position.x >= mouth }?.unitId
                assertTrue(ScreenPlane.distanceSquared(front.position, rear.position) >= 12.0 * 12.0 - 1e-6)
                if (observation.units.all { it.moveArrival != null && it.moveTarget == null }) {
                    assertEquals(frontId, firstIn)
                    val (frontStep, deep) = firstRights.getValue(frontId)
                    val (rearStep, shallow) = firstRights.getValue(rear.unitId)
                    assertTrue(deep.x > shallow.x, "First rights: $firstRights")
                    assertTrue(frontStep <= rearStep, "Entry order: $firstRights")
                    assertTrue(furthestRearX <= shallow.x + 6.0,
                        "Follower passed its shallow pad $shallow with the whole hull at x=$furthestRearX")
                    if (quiet == 0) println("front=$frontId rights=$firstRights stopped=${observation.stepIndex} " +
                        "deepPose=${front.position} shallowPose=${rear.position} furthestRearX=$furthestRearX")
                    assertTrue(rear.position.x >= mouth && front.position.x > rear.position.x,
                        "front=$frontId at ${front.position}, follower at ${rear.position}")
                    val poses = listOf(front.position, rear.position)
                    stopped?.let { assertEquals(it, poses) }
                    stopped = poses
                    quiet++
                } else {
                    quiet = 0
                    stopped = null
                }
                quiet >= 60
            }
        }
    }

    @Test fun four_cars_release_independent_approaches_from_the_same_group_order() {
        for (bypass in listOf(false, true)) checkIndependentEntrances(bypass, false, ExperimentalPolicies())
    }

    @Test fun independent_approaches_survive_reversed_ids_and_sliced_work() {
        for (bypass in listOf(false, true)) checkIndependentEntrances(bypass, true, ExperimentalPolicies(movePlanningBudgetPerStep = 8))
    }

    private fun checkIndependentEntrances(bypass: Boolean, swap: Boolean, policies: ExperimentalPolicies) {
        val goal = Position(450.0, 270.0)
        fun wall(x: Int, y: Int): Boolean {
            val main = y == 13 && x in 16..25
            val feeder = x == 21 && y in 13..18
            val right = x in 22..26 && y in 12..14
            val detour = bypass && x in 19..21 && y == 12
            return !(main || feeder || right || detour)
        }
        val room = scenario(::wall, goal)
        val early = if (swap) "late" else "early"
        val late = if (swap) "early" else "late"
        val cars = listOf(
            UnitSpawn(early, "p1", "small", Position(391.0, 270.0), 270.0),
            UnitSpawn(late, "p1", "small", Position(430.0, 351.0), 270.0),
            UnitSpawn("upper", "p1", "small", Position(497.0, 250.0), 270.0),
            UnitSpawn("lower", "p1", "small", Position(497.0, 290.0), 270.0),
        )
        val first = mutableMapOf<String, Pair<Long, Position>>()
        val stopped = mutableMapOf<String, Long>()
        var quiet = 0
        drive(room.copy(units = cars), goal, cars.map { it.unitId }.toSet(), policies) { frame, motion ->
            for (sample in motion) {
                val site = sample.landingClaim ?: sample.landingIngress ?: continue
                if (first.putIfAbsent(sample.unitId, frame.stepIndex to site) == null)
                    println("bypass=$bypass first ${sample.unitId} ${frame.stepIndex} $site")
            }
            for ((i, car) in frame.units.withIndex()) {
                val p = car.position
                for (x in ((p.x - 6) / 20).toInt()..((p.x + 6) / 20).toInt()) {
                    for (y in ((p.y - 6) / 20).toInt()..((p.y + 6) / 20).toInt()) {
                        if (wall(x, y)) {
                            val nearest = Position(p.x.coerceIn(x * 20.0, (x + 1) * 20.0), p.y.coerceIn(y * 20.0, (y + 1) * 20.0))
                            assertTrue(ScreenPlane.distanceSquared(p, nearest) >= 36.0 - 1e-6, "Wall at ($x,$y), car ${car.unitId} at $p")
                        }
                    }
                }
                for (peer in frame.units.drop(i + 1))
                    assertTrue(ScreenPlane.distanceSquared(car.position, peer.position) >= 144.0 - 1e-6)
                if (car.moveArrival != null && stopped.putIfAbsent(car.unitId, frame.stepIndex) == null)
                    println("bypass=$bypass parked ${car.unitId} ${frame.stepIndex} ${car.position}")
            }
            val parked = frame.units.all { it.moveArrival?.originalTarget == goal && it.tasks.isEmpty() && it.speed == 0.0 }
            quiet = if (parked) quiet + 1 else 0
            quiet >= 60
        }
    }

    @Test fun a_pad_overlapped_by_a_stationary_hull_cannot_be_reserved() {
        val target = Position(450.0, 270.0)
        val room = scenario({ x, y -> x >= 23 || x >= 18 && y !in 12..14 }, Position(220.0, 270.0))
        val obstacle = UnitSpawn("parked", "p1", "small", Position(452.75, 270.0), 90.0)
        val cars = listOf(UnitSpawn("a", "p1", "small", Position(320.0, 265.0), 90.0),
            UnitSpawn("b", "p1", "small", Position(240.0, 275.0), 90.0), obstacle)
        var settled = 0
        drive(room.copy(units = cars), target) { frame, samples ->
            val parked = frame.units.single { it.unitId == obstacle.unitId }
            for (sample in samples) {
                val site = sample.landingClaim ?: sample.landingIngress ?: continue
                assertTrue(ScreenPlane.distanceSquared(site, parked.position) >= 144.0 - 1e-6,
                    "Step ${frame.stepIndex}: ${sample.unitId} reserved $site under stationary tank at ${parked.position}")
            }
            if (frame.units.filter { it.unitId != obstacle.unitId }.all { it.moveArrival != null }) settled++ else settled = 0
            settled >= 60
        }
    }

    @Test fun a_click_short_of_the_end_leaves_the_deepest_pad_for_the_leading_car() {
        val room = scenario({ x, y -> x >= 26 || x >= 18 && y != 13 }, Position(200.0, 270.0))
        val cars = listOf("d", "c", "b", "a").mapIndexed { i, id ->
            UnitSpawn(id, "p1", "small", Position(340.0 - 50.0 * i, 270.0), 90.0)
        }
        val target = Position(450.0, 270.0)
        drive(room.copy(units = cars), target, cars.map { it.unitId }.toSet()) { frame, motion ->
            // All four cars share the same single-file entrance. A free pad beyond the click is reachable.
            val leading = motion.single { it.unitId == "d" }
            val site = leading.landingClaim
            if (site != null) assertTrue(site.x > target.x, "First car was assigned $site")
            frame.units.all { it.moveArrival != null } && frame.units.maxBy { it.position.x }.unitId == "d"
        }
    }

    @Test fun parking_preserves_another_drivers_reserved_footprint() {
        val room = scenario({ _, _ -> false }, Position(390.0, 270.0))
        val goal = Position(470.0, 270.0)
        val order = GroupMoveRequest(WorldHandle("landing"), null, "p1", goal, setOf("early", "late"))
        val definition = room.unitDefinitions.getValue("small")
        val early = MutableUnit("early", "p1", "small", definition, Position(370.0, 270.0), 90.0).apply {
            landingMember = LandingMember(order, claimed = goal)
            moveTarget = goal
        }
        val late = MutableUnit("late", "p1", "small", definition, goal, 90.0)
        val units = listOf(early, late).associateBy { it.unitId }
        val occupancy = LandingOccupancy().apply { update(units.values) }
        assertFalse(occupancy.leavesReservedSitesClear(late, units))
        late.position = Position(410.0, 270.0)
        assertTrue(occupancy.leavesReservedSitesClear(late, units))
    }

    @Test fun mixed_radii_move_towards_the_click_before_claiming_separate_sites() {
        val approaching = mutableSetOf<String>()
        drive(scenario({ _, _ -> false }, Position(160.0, 325.0))) { frame, motion ->
            for (unit in frame.units) if (unit.position.x < 240.0 && unit.pathPoints.isNotEmpty()) {
                assertEquals(click, unit.moveTarget, "Personal pad already bound at departure for ${unit.unitId}")
                assertNull(motion.single { it.unitId == unit.unitId }.landingClaim)
                approaching += unit.unitId
            }
            if (motion.count { it.landingParked } != 2) false else {
                assertEquals(setOf("a", "b"), approaching)
                assertEquals(2, motion.mapNotNull { it.landingClaim }.distinct().size)
                assertTrue(frame.units.all { it.moveArrival != null })
                true
            }
        }
    }

    @Test fun mixed_cars_join_a_parking_lane_only_after_surveying_the_current_apron() {
        val scene = scenario({ _, _ -> false }, Position(180.0, 260.0))
        val cars = scene.units.map { spawn ->
            MutableUnit(spawn.unitId, "p1", spawn.typeId, scene.unitDefinitions.getValue(spawn.typeId),
                spawn.position, 90.0)
        }
        val group = GroupMoveRequest(WorldHandle("mixed-apron"), null, "p1", click, setOf("a", "b"))
        val pool = SharedLandingSites.forGroup(scene.map, group, cars)
        fun ground() = com.rustedwarfare.reforge.core.path.PlanningTerrain(38, 38, 20, 20, BooleanArray(38 * 38))
        val terrain = ground()
        val car = cars.last()
        val pad = Position(click.x, click.y + 30.0)
        val survey = assertNotNull(pool.mixedBays)
        assertNull(survey.sites(terrain, PathWorkBudget(1)))
        assertEquals(listOf(pad), pool.approach(car, pad, terrain))
        assertEquals(emptyList(), survey.sites(terrain, PathWorkBudget(256)))
        val path = pool.approach(car, pad, terrain)
        assertEquals(2, path.size)
        assertEquals(pad, path.last())
        assertEquals(pad.y, path.first().y)
        assertTrue(path.first().x in (car.position.x + 1.0)..(pad.x - 1.0))
        assertEquals(listOf(pad), pool.approach(car, pad, ground()))
    }

    @Test fun a_circle_just_past_a_bend_drives_the_clear_prefix_when_a_parked_body_blocks_the_far_end() {
        val base = scenario({ _, _ -> false }, Position(200.577, 299.817))
        val click = Position(550.0, 190.0)
        val spawns = listOf(UnitSpawn("car", "p1", "small", Position(200.577, 299.817), 276.4),
            UnitSpawn("parked", "p1", "small", Position(490.0, 210.0), 90.0))
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(base.copy(units = spawns))).context
        try {
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("original", "p1", "car", click, ctx.world)))
            ctx.advanceToMoveOutcome(0, "car")
            val state = (SimulationContext::class.java.getDeclaredField("steps").apply { isAccessible = true }
                .get(ctx) as CandidateSteps).current
            val car = state.units.getValue("car")
            car.position = spawns.first().position
            car.heading = 276.4
            car.speed = 0.0
            car.adoptRoute(listOf(Position(200.0, 300.0), click), click, car.lastPlannedKnowledgeRevision,
                car.lastPlannedBuildingRevision, listOf("parked"))
            car.followsManeuverRoute = true
            car.landingMember = LandingMember(GroupMoveRequest(ctx.world, null, "p1", click, setOf("car", "parked")))
            val first = state.stepIndex + 1
            for (tick in first until first + 600) {
                val frame = ctx.advance(Step(tick, 1.0, "sim-tick"))
                val moved = frame.units.single { it.unitId == "car" }
                if (moved.position.x > 216.0) return
            }
            fail("Vehicle kept turning at the projection of the waypoint")
        } finally { ctx.close() }
    }

    @Test fun first_arriving_small_car_leaves_the_only_wide_bay() = checkBays(false)
    @Test fun another_reachable_wide_bay_lets_the_small_car_park_nearby() = checkBays(true)

    @Test fun a_wide_bay_is_still_scarce_when_the_large_car_must_detour_to_reach_it() = checkBays(false, true)

    @Test fun click_near_the_end_wall_still_leaves_the_only_wide_bay_for_the_big_car() =
        checkBays(false, target = Position(430.0, 260.0))

    @Test fun click_near_the_end_wall_can_address_the_second_wide_bay() =
        checkBays(true, target = Position(430.0, 260.0))

    @Test fun two_known_parked_cars_in_the_second_leg_keep_the_narrow_bay_unclaimed() {
        val base = scenario({ x, y -> !(x in 2..19 && y in 5..21 || x in 20..21 && (y in 12..13 || y == 16)) },
            Position(380.0, 260.0))
        val scene = base.copy(units = base.units + listOf(UnitSpawn("c", "p1", "small", Position(410.0, 330.0), 90.0),
            UnitSpawn("d", "p1", "small", Position(430.0, 330.0), 90.0)))
        var ticks = 0
        drive(scene) { _, motion ->
            assertNotEquals(Position(420.0, 330.0), motion.single { it.unitId == "b" }.landingClaim)
            ++ticks == 60
        }
    }

    @Test fun one_unit_bay_slices_resume_each_leg_and_recheck_changed_traffic_and_terrain() {
        // The diagonal touches the corner, but both legs of the apron elbow are free.
        val scene = scenario({ x, y -> x == 19 && y == 15 }, Position(380.0, 260.0))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val site = Position(420.0, 330.0)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "b"), click))
            val def = scene.unitDefinitions.getValue("small")
            val car = MutableUnit("b", "p1", def.id, def, Position(370.0, 260.0), 90.0).apply {
                landingMember = LandingMember(group)
                moveTarget = click
                pathPoints = mutableListOf(click)
            }
            val other = MutableUnit("parked", "p1", def.id, def, site, 90.0)
            var blockers = emptyList<MutableUnit>()
            var ground = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(scene.map)
            val paths = ExperimentalPolicies().path
            fun serve(survey: MixedLandingBays, sites: List<Position> = listOf(site)): MixedLandingBays.Decision {
                car.heading += 0.1 // A circular body can keep steering between slices of the same swept route.
                val slice = PathWorkBudget(1)
                return survey.choose(listOf(car), sites, { _, _ -> false }, { true }, { ground }, paths, slice,
                    { _, _ -> fail("Clear elbow should not need an exact search") }, { blockers })
            }
            val bay = MixedLandingBays(click, true, 110.0)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            val undo = PathSearchRollback()
            val fork = bay.fork(undo)
            assertIs<MixedLandingBays.Decision.Pending>(serve(fork))
            assertEquals(listOf(Position(370.0, 330.0), site), assertIs<MixedLandingBays.Decision.Claim>(serve(fork)).path)
            undo.restore()
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            assertIs<MixedLandingBays.Decision.Claim>(serve(bay))
            blockers = listOf(other)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
            other.position = Position(100.0, 100.0)
            car.position = Position(400.0, 330.0)
            assertEquals(listOf(site), assertIs<MixedLandingBays.Decision.Claim>(serve(bay)).path)
            val blocked = scenario({ x, y -> x == 21 && y == 16 }, car.position)
            ground = com.rustedwarfare.reforge.core.path.PlanningTerrain.fromNoFog(blocked.map)
            assertIs<MixedLandingBays.Decision.Pending>(serve(bay))
        } finally { context.close() }
    }

    private fun checkBays(secondWide: Boolean, detour: Boolean = false, target: Position = click) {
        val scene = scenario({ x, y ->
            val apron = x in 2..19 && y in 5..21
            val bay = x in 20..21 && (y in 12..13 || y == 16 || secondWide && y in 8..9)
            !apron && !bay || detour && x == 12 && y in 9..14
        }, Position(380.0, 260.0))
        var stable = 0
        var previous = emptyList<Pair<Position, Double>>()
        drive(scene, target) { frame, _ ->
            val big = frame.units.single { it.unitId == "a" }
            val small = frame.units.single { it.unitId == "b" }
            fun inside(p: Position, radius: Double, top: Int, bottom: Int) =
                p.x in (400 + radius)..(440 - radius) && p.y in (top + radius)..(bottom - radius)
            val parked = big.moveArrival != null && small.moveArrival != null &&
                inside(big.position, 18.0, if (secondWide) 160 else 240, if (secondWide) 200 else 280) &&
                inside(small.position, 6.0, if (secondWide) 240 else 320, if (secondWide) 280 else 340)
            val poses = frame.units.map { it.position to it.heading }
            stable = if (parked && poses == previous) stable + 1 else 0
            previous = poses
            stable >= 60
        }
    }

    @Test fun a_long_hull_uses_the_wide_branch_and_the_small_car_takes_the_tight_bend() {
        val base = scenario({ x, y ->
            val room = x in 26..36 && y in 18..25
            val departure = x in 2..14 && y in 5..24
            val short = x in 15..31 && y == 8 || x == 31 && y in 8..18
            val wide = x in 8..13 && y in 24..35 || x in 8..33 && y in 31..35 || x in 29..33 && y in 24..35
            !(departure || short || wide || room)
        }, Position(125.0, 170.0))
        val tank = base.unitDefinitions.getValue("small")
        val long = UnitDefinition(tank.id, tank.className, tank.radius, tank.displayRadius, tank.totalFrames,
            tank.imagePath, tank.imageScale, tank.movement, emptySet(), movementCollision = MovementCollisionShape.Rectangle(76.0, 16.0))
        val scene = base.copy(unitDefinitions = base.unitDefinitions + ("long" to long),
            units = listOf(UnitSpawn("a", "p1", "long", Position(200.0, 170.0), 90.0), base.units.last()))
        var longCrossed = false
        var smallCrossed = false
        val target = Position(630.0, 450.0)
        drive(scene, target) { frame, motion ->
            val truck = frame.units.single { it.unitId == "a" }.position
            val car = frame.units.single { it.unitId == "b" }.position
            if (truck.y > 500.0 && truck.x < 280.0) longCrossed = true
            if (car.x > 300.0 && car.y in 166.0..174.0) smallCrossed = true
            assertFalse(truck.x > 300.0 && truck.y < 185.0, "Long hull entered a turn it cannot clear")
            if (frame.units.any { it.moveArrival == null }) false else {
                assertTrue(longCrossed && smallCrossed)
                assertTrue(frame.units.all { it.position.x in 520.0..740.0 && it.position.y in 360.0..520.0 })
                assertTrue(motion.all { it.landingParked })
                true
            }
        }
    }
}
