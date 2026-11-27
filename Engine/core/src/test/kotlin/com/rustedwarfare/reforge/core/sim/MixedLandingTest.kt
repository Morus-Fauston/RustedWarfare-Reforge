package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

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

    private fun drive(scene: Scenario, target: Position = click, checkStep: (Observation, List<MotionStepSample>) -> Boolean) {
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val ids = scene.units.filter { it.unitId == "a" || it.unitId == "b" }.map { it.unitId }
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
                assertTrue(frame.units.all { it.moveArrival != null })
                val aPos = frame.units.single { it.unitId == "a" }.position
                val bPos = frame.units.single { it.unitId == "b" }.position
                assertTrue(ScreenPlane.distanceSquared(aPos, bPos) >= 24.0 * 24.0 - 1e-3,
                    "Mixed radii settled cleanly without overlap: $aPos vs $bPos")
                true
            }
        }
    }

    @Test fun mixed_cars_survey_apron_under_budget_without_preclaiming_open_ground() {
        val scene = scenario({ _, _ -> false }, Position(180.0, 260.0))
        val cars = scene.units.map { spawn ->
            MutableUnit(spawn.unitId, "p1", spawn.typeId, scene.unitDefinitions.getValue(spawn.typeId),
                spawn.position, 90.0)
        }
        val group = GroupMoveRequest(WorldHandle("mixed-apron"), null, "p1", click, setOf("a", "b"), true)
        val pool = SharedLandingSites.forGroup(scene.map, group, cars)
        val terrain = com.rustedwarfare.reforge.core.navigation.PlanningTerrain(38, 38, 20, 20, BooleanArray(38 * 38))
        val survey = assertNotNull(pool.mixedBays)
        assertNull(survey.sites(terrain, PathWorkBudget(1)))
        assertEquals(emptyList(), survey.sites(terrain, PathWorkBudget(256)))
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
            car.updateNavigation {
                it.withFollowsManeuverRoute(true)
                    .withLandingMember(LandingMember(GroupMoveRequest(ctx.world, null, "p1", click, setOf("car", "parked"), true)))
            }
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
                updateNavigation {
                    it.withLandingMember(LandingMember(group))
                        .withMoveTarget(click, MotionContract.sharedCluster(click))
                        .withPathPoints(listOf(click))
                }
            }
            val other = MutableUnit("parked", "p1", def.id, def, site, 90.0)
            var blockers = emptyList<MutableUnit>()
            var ground = com.rustedwarfare.reforge.core.navigation.PlanningTerrain.fromNoFog(scene.map)
            val paths = ClearanceAStarPolicy.DEFAULT
            fun serve(survey: MixedLandingBays, sites: List<Position> = listOf(site)): MixedLandingBays.Decision {
                car.heading += 0.1 // A circular body can keep steering between slices of the same swept route.
                val slice = PathWorkBudget(1)
                return survey.choose(listOf(car), sites, { _, _, _ -> false }, { true }, { ground }, paths, slice,
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
            ground = com.rustedwarfare.reforge.core.navigation.PlanningTerrain.fromNoFog(blocked.map)
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

    @Test fun landing_occupancy_protects_remote_active_claims_with_terminal_heading_and_rolls_back() {
        val base = scenario({ _, _ -> false }, Position(120.0, 260.0))
        val smallDef = base.unitDefinitions.getValue("small")
        val rectDef = UnitDefinition(
            smallDef.id, smallDef.className, smallDef.radius, smallDef.displayRadius, smallDef.totalFrames,
            smallDef.imagePath, smallDef.imageScale, smallDef.movement, emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(60.0, 16.0),
        )
        val groupA = GroupMoveRequest(WorldHandle("w"), null, "p1", Position(420.0, 260.0), setOf("big", "escort"), true)
        val groupB = GroupMoveRequest(WorldHandle("w"), null, "p1", Position(420.0, 260.0), setOf("small"), true)
        val big = MutableUnit("big", "p1", "rect", rectDef, Position(220.0, 260.0), 0.0).apply {
            updateNavigation {
                it.withMoveTarget(Position(420.0, 260.0), MotionContract.sharedCluster(Position(420.0, 260.0)))
                    .withLandingMember(LandingMember(groupA, claimed = Position(420.0, 260.0), claimedHeading = 90.0))
            }
        }
        val small = MutableUnit("small", "p1", "small", smallDef, Position(444.0, 260.0), 90.0).apply {
            updateNavigation {
                it.withMoveTarget(Position(420.0, 260.0), MotionContract.sharedCluster(Position(420.0, 260.0)))
                    .withLandingMember(LandingMember(groupB))
            }
        }
        val units = mapOf("big" to big, "small" to small)
        val occupancy = LandingOccupancy()
        val undo = PathSearchRollback()
        val forked = occupancy.fork(undo)
        forked.update(big)
        assertTrue(forked.overlapsAnyActiveClaim(small, Position(444.0, 260.0), 90.0, units) { _, _ -> true })
        assertFalse(forked.overlapsAnyActiveClaim(small, Position(420.0, 285.0), 90.0, units) { _, _ -> true })
        undo.restore()
        assertFalse(occupancy.overlapsAnyActiveClaim(small, Position(444.0, 260.0), 90.0, units) { _, _ -> true })
    }

    @Test fun releasing_claimed_bay_on_knowledge_loss_replans_without_concurrent_modification_and_settles() {
        // True map has ONLY the wide bay (y in 12..13); the apparent narrow bay (y==16, x in 20..21) is actually walled off.
        // Under PLAYER_KNOWLEDGE fog, scouts reveal the surrounding bay walls (y==15 and y==17) while leaving (20..21, 16)
        // initially unseen so MixedLandingBays assigns small unit `b` to (420, 330). As `b` approaches, automatic vision
        // discovers (20, 16) is blocked, firing ManeuverRepair.KNOWLEDGE inside p2Repairs -> publishMove ->
        // group-destination-released -> re-enqueue to `click` (proving zero ConcurrentModificationException and no freeze).
        val base = scenario({ x, y ->
            val apron = x in 2..19 && y in 5..21
            val wideBay = x in 20..21 && y in 11..12
            val scoutPads = (x == 21 && (y == 9 || y == 14 || y == 18)) || (x == 23 && y in 11..16)
            !(apron || wideBay || scoutPads)
        }, Position(330.0, 240.0))
        val smallBase = base.unitDefinitions.getValue("small")
        val bigBase = base.unitDefinitions.getValue("big")
        val smallFog = UnitDefinition(smallBase.id, smallBase.className, smallBase.radius, smallBase.displayRadius,
            smallBase.totalFrames, smallBase.imagePath, smallBase.imageScale, smallBase.movement, emptySet(), 3,
            movementCollision = smallBase.movementCollision)
        val bigFog = UnitDefinition(bigBase.id, bigBase.className, bigBase.radius, bigBase.displayRadius,
            bigBase.totalFrames, bigBase.imagePath, bigBase.imageScale, bigBase.movement, emptySet(), 3,
            movementCollision = bigBase.movementCollision)
        val scoutDef = UnitDefinition("scout", smallBase.className, 1.0, 1.0, 1, smallBase.imagePath, 1.0,
            com.rustedwarfare.reforge.core.content.MovementProfile(
                com.rustedwarfare.reforge.core.content.MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false,
            ), emptySet(), 1)
        val targetClick = Position(420.0, 240.0)
        val scene = base.copy(
            unitDefinitions = mapOf("small" to smallFog, "big" to bigFog, "scout" to scoutDef),
            units = listOf(
                UnitSpawn("a", "p1", "big", Position(260.0, 240.0), 90.0),
                UnitSpawn("b", "p1", "small", Position(330.0, 240.0), 90.0),
                // Sight=1 scouts frame all walls around wide bay (y in 11..12) and fake narrow bay (y==16)
                // while leaving (20..21, 16) unseen in fog!
                UnitSpawn("s1", "p1", "scout", Position(430.0, 290.0), 90.0),
                UnitSpawn("s2", "p1", "scout", Position(390.0, 310.0), 90.0),
                UnitSpawn("s3", "p1", "scout", Position(430.0, 370.0), 90.0),
                UnitSpawn("s4", "p1", "scout", Position(390.0, 350.0), 90.0),
                UnitSpawn("s5", "p1", "scout", Position(470.0, 330.0), 90.0),
                UnitSpawn("s6", "p1", "scout", Position(430.0, 190.0), 90.0),
                UnitSpawn("s7", "p1", "scout", Position(390.0, 210.0), 90.0),
                UnitSpawn("s8", "p1", "scout", Position(470.0, 230.0), 90.0),
                UnitSpawn("s9", "p1", "scout", Position(470.0, 250.0), 90.0),
            ),
            automaticVision = com.rustedwarfare.reforge.core.knowledge.KnowledgeRules(
                com.rustedwarfare.reforge.core.knowledge.TerrainRetention.MEMORY,
                com.rustedwarfare.reforge.core.knowledge.PathInformationSource.PLAYER_KNOWLEDGE,
                false,
            ),
        )
        val initRes = SimulationContext.initialize(scene)
        val ctx = assertIs<InitResult.Accepted>(initRes, "Init must succeed: $initRes").context
        try {
            val ids = listOf("a", "b")
            val group = assertNotNull(ctx.requestGroupMove("p1", ids, targetClick))
            for (id in ids) assertIs<ActionResult.Accepted>(ctx.submitGroupMove(MoveAction("rel-$id", "p1", id, targetClick, ctx.world), group))
            ctx.enableMotionDiagnostics(ctx.world)
            var claimedBay = false
            var released = false
            var bothSettled = false
            for (tick in 0L until 2000L) {
                val frame = ctx.advance(Step(tick, 1.0, "sim-tick"))
                val motion = ctx.drainMotionDiagnostics(ctx.world)
                if (motion.firstOrNull { it.unitId == "b" }?.landingClaim == Position(420.0, 330.0)) {
                    claimedBay = true
                }
                if (frame.events.any { it.kind == "group-destination-released" && it.unitId == "b" }) {
                    released = true
                }
                val aUnit = frame.units.single { it.unitId == "a" }
                val bUnit = frame.units.single { it.unitId == "b" }
                if (released && aUnit.moveArrival != null && bUnit.moveArrival != null &&
                    aUnit.moveTarget == null && bUnit.moveTarget == null
                ) {
                    bothSettled = true
                    break
                }
            }
            assertTrue(claimedBay, "Small unit must first claim the fog-unseen narrow bay (420, 330)")
            assertTrue(released, "Discovering the bay wall must emit group-destination-released without ConcurrentModificationException")
            assertTrue(bothSettled, "Released unit must re-plan back to click and settle cleanly: " +
                ctx.observe().units.filter { it.unitId == "a" || it.unitId == "b" }.map { "${it.unitId}@${it.position} tgt=${it.moveTarget} arr=${it.moveArrival != null}" })
        } finally { ctx.close() }
    }
}
