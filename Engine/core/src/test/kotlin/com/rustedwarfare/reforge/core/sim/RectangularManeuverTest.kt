package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.withSubmarinePackage
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import com.rustedwarfare.reforge.core.path.PathSearchCounters
import com.rustedwarfare.reforge.core.path.PathSearchOutcome
import com.rustedwarfare.reforge.core.path.PathSearchReason
import com.rustedwarfare.reforge.core.path.PathSearchSample
import com.rustedwarfare.reforge.core.path.PlanningTerrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RectangularManeuverTest {
    private val hull = MovementCollisionShape.Rectangle(102.0, 20.0)

    @Test
    fun ship_finds_another_turn_pose_without_a_second_order_or_clearing_the_blocker() = WaterTestScene.withMap { shore ->
        val blocker = BasinBlocker(Position(440.0, 169.0), Position(440.0, 230.0), Long.MAX_VALUE,
            MovementCollisionShape.Rectangle(24.0, 13.0))
        val repairs = mutableListOf<PathSearchSample>()
        val track = move(strait(shore), Position(240.0, 120.0), 90.0, Position(110.0, 120.0), blocker, repairs)
        val found = repairs.firstOrNull { it.reason == PathSearchReason.ROUTE_REPAIR && it.outcome == PathSearchOutcome.FOUND }
        assertNotNull(found, "Sustained rotational contact must produce a new maneuver for the original Move")
        assertEquals("maneuver", found.actionId)
        assertTrue(track.any { it.position.x > 450.0 || kotlin.math.abs(it.position.y - 120.0) > 20.0 },
            "The ship must really sail to a different turning pose")
        println("sustained-turn recovery=${found.executionStep}; arrived=${track.lastIndex}; final=${track.last().position}")
    }

    @Test
    fun a_light_vessel_needs_no_player_clearing_order_and_replays_identically() = WaterTestScene.withMap { shore ->
        val blocker = BasinBlocker(Position(440.0, 169.0), Position(440.0, 230.0), Long.MAX_VALUE,
            MovementCollisionShape.Rectangle(24.0, 13.0), mass = 1_000.0)
        fun replay() = move(strait(shore), Position(240.0, 120.0), 90.0, Position(110.0, 120.0), blocker)
        assertEquals(replay(), replay())
    }

    @Test
    fun ship_passes_the_rear_target_only_after_turning_in_open_water() = WaterTestScene.withMap { shore ->
        val map = strait(shore)
        val start = Position(240.0, 120.0)
        val target = Position(110.0, 120.0)
        val track = move(map, start, 90.0, target)

        assertTrue(track.maxOf { it.position.x } >= 420.0, "The ship must reach the turning basin")
        val firstWest = track.first { kotlin.math.abs(ScreenPlane.difference(it.heading, 270.0)) <= 1.0 }
        assertTrue(firstWest.position.x >= 410.0, "The ship must finish rotating in the turning basin")
    }

    @Test
    fun ship_crosses_an_s_bend_without_cutting_across_the_banks() = WaterTestScene.withMap { shore ->
        val map = waterMap(52, 33, shore) { col, row ->
            (col in 2..20 && row in 4..6) || (col in 15..21 && row in 2..22) ||
                (col in 18..35 && row in 17..19) || (col in 31..37 && row in 2..22) ||
                (col in 34..49 && row in 4..6)
        }
        val track = move(map, Position(130.0, 110.0), 90.0, Position(925.0, 110.0))

        assertTrue(track.any { it.position.x in 430.0..590.0 && it.position.y in 350.0..390.0 },
            "The ship must sail through the lower stretch of the S")
    }

    @Test
    fun ship_aligns_outside_a_narrow_bay_before_entering() = WaterTestScene.withMap { shore ->
        val map = waterMap(32, 23, shore) { col, row ->
            (row in 9..21 && col in 1..30) || (col in 20..21 && row in 1..9)
        }
        val track = move(map, Position(130.0, 290.0), 90.0, Position(420.0, 90.0))
        val crossing = track.indexOfFirst { it.position.y < 180.0 }
        val alignedOutside = track.indexOfFirst { it.position.y < 210.0 }
        assertTrue(alignedOutside in 0 until crossing)
        val approachHeading = track[alignedOutside].heading
        assertTrue(track.drop(alignedOutside).all {
            kotlin.math.abs(ScreenPlane.difference(it.heading, approachHeading)) < 1e-6
        }, "The ship continued turning after entry instead of aligning outside the bay")
    }

    @Test
    fun short_basin_occupancy_can_clear_without_replacing_the_original_turn_route() = WaterTestScene.withMap { shore ->
        val blocker = BasinBlocker(Position(440.0, 175.0), Position(440.0, 230.0), 430L)
        val repairs = mutableListOf<PathSearchSample>()
        val track = move(strait(shore), Position(240.0, 120.0), 90.0, Position(110.0, 120.0), blocker, repairs)
        assertTrue(repairs.none { it.reason == PathSearchReason.ROUTE_REPAIR },
            "Short occupancy must not require an alternate turn route")
        assertTrue(track.take(blocker.releaseStep.toInt()).all { it.moveTarget == Position(110.0, 120.0) })
        assertTrue(track.last().position.x < 117.0)
    }

    @Test
    fun repeated_turn_attempts_do_not_enter_a_rectangular_blocker() = WaterTestScene.withMap { shore ->
        val blocker = BasinBlocker(Position(440.0, 169.0), Position(440.0, 230.0), 1_250L,
            MovementCollisionShape.Rectangle(24.0, 13.0))
        val repairs = mutableListOf<PathSearchSample>()
        val track = move(strait(shore), Position(240.0, 120.0), 90.0, Position(110.0, 120.0), blocker, repairs)
        assertTrue(track.size > blocker.releaseStep)
        val westbound = track.indexOfFirst { it.heading in 225.0..315.0 }
        assertTrue(westbound in 0 until blocker.releaseStep.toInt(), "The ship must turn before any clearing order")
        assertTrue(repairs.any { it.reason == PathSearchReason.ROUTE_REPAIR && assertNotNull(it.executionStep) < blocker.releaseStep })
        assertTrue(repairs.none { it.reason == PathSearchReason.ROUTE_REPAIR && assertNotNull(it.executionStep) >= blocker.releaseStep },
            "Opening the old basin must not restart a legal route that is already progressing")
        assertTrue(track[blocker.releaseStep.toInt() + 20].position.x < track[blocker.releaseStep.toInt()].position.x)
    }

    @Test
    fun a_basin_with_no_legal_turn_does_not_search_forever() = WaterTestScene.withMap { shore ->
        val map = strait(shore)
        val ship = battleship()
        val fixture = WaterTestScene.unit(MovementType.WATER)
        val bar = UnitDefinition("basin-bar", fixture.className, 16.0, 16.0, fixture.totalFrames,
            fixture.imagePath, fixture.imageScale, fixture.movement.copy(moveSpeed = 0.0), emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(24.0, 200.0), mass = 500_000.0)
        val base = WaterTestScene.scenario(map, MovementType.WATER, Position(240.0, 120.0))
        val scene = base.copy(unitDefinitions = mapOf(ship.id to ship, bar.id to bar), units = listOf(
            UnitSpawn("w1", "p1", ship.id, Position(240.0, 120.0), 90.0),
            UnitSpawn("top", "p1", bar.id, Position(500.0, 72.0), 180.0),
            UnitSpawn("bottom", "p1", bar.id, Position(500.0, 168.0), 180.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePathDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("maneuver", "p1", "w1",
                Position(110.0, 120.0), context.world)))
            val terrain = HullTerrainGrid.authoritative(map, MovementType.WATER)
            for (tick in 0L..2_000L) {
                context.advance(Step(tick, 1.0, "sim-tick"))
                val units = context.observePlayer(context.world, "p1").units
                val moving = units.single { it.unitId == "w1" }
                assertTrue(UnitContact.poseFits(hull, moving.position, moving.heading, terrain))
                for (other in units.filter { it.unitId != "w1" }) {
                    assertTrue(!UnitContact.posesOverlap(hull, moving.position, moving.heading,
                        bar.movementCollision, other.position, other.heading))
                }
            }
            val repairs = context.drainPathDiagnostics(context.world).filter { it.reason == PathSearchReason.ROUTE_REPAIR }
            assertTrue(repairs.isNotEmpty(), "A sustained rotational block must try recovery")
            assertTrue(repairs.all { it.outcome == PathSearchOutcome.UNREACHABLE })
            val work = context.drainPlanningWorkDiagnostics(context.world)
            assertTrue(work.all { it.used in 0..512 && it.slices.sumOf { slice -> slice.workUnits } == it.used })
            assertTrue(work.filter { it.executionStep > 1_500 }.all { it.used == 0 },
                "Unchanged impossible occupancy must not consume recovery budget forever: ${repairs.map { it.executionStep }}")
            println("impossible turn repairs=${repairs.map { it.executionStep }}")
        } finally { context.close() }
    }

    @Test
    fun a_long_hull_detours_around_a_sustained_translation_contact() = WaterTestScene.withMap { shore ->
        val map = waterMap(34, 22, shore) { c, r -> c in 1..32 && r in 1..20 }
        val blocker = BasinBlocker(Position(285.0, 214.0), Position(285.0, 280.0), Long.MAX_VALUE)
        val repairs = mutableListOf<PathSearchSample>()
        val track = move(map, Position(140.0, 200.0), 90.0, Position(480.0, 200.0), blocker, repairs)
        val repair = repairs.first { it.reason == PathSearchReason.ROUTE_REPAIR && it.outcome == PathSearchOutcome.FOUND }
        assertEquals("maneuver", repair.actionId)
        assertEquals(Position(480.0, 200.0), repair.searchTarget)
        assertTrue(repair.pathPointCount > 1)
        assertTrue(track.any { kotlin.math.abs(it.position.y - 200.0) >= 20.0 })
    }

    @Test
    fun newly_revealed_shore_reassigns_a_rectangular_group_slot_without_replacing_its_order() =
        WaterTestScene.withMap { shore -> withSubmarinePackage { submarinePackage ->
            val map = waterMap(40, 21, shore) { c, r ->
                c in 1..38 && r in 1..19 && (c != 30 || r != 10) && (c !in 27..34 || r !in 15..16)
            }
            val target = Position(606.0, 210.0)
            val start = Position(480.0, 210.0)
            val ship = battleship(reverse = 1.0)
            val submerged = assertIs<ContentResult.Success<LoadedContentPack>>(
                ContentLoader.loadContentPack(submarinePackage)).value.units.getValue("attackSubmarine")
            val submarineStart = Position(650.0, 130.0)
            val vehicleStart = Position(575.0, 320.0)
            val land = WaterTestScene.unit(MovementType.LAND)
            val vehicle = UnitDefinition("shore-vehicle", land.className, land.radius, land.displayRadius,
                land.totalFrames, land.imagePath, land.imageScale, land.movement, emptySet())
            val base = WaterTestScene.scenario(map, MovementType.WATER, start)
            val scenario = base.copy(unitDefinitions = base.unitDefinitions +
                listOf(ship, submerged, vehicle).associateBy { it.id },
                units = listOf(
                    UnitSpawn("a", "p1", ship.id, start, 90.0),
                    UnitSpawn("z", "p1", ship.id, Position(320.0, 112.0), 90.0),
                    UnitSpawn("scout", "p1", "water-test", Position(250.0, 280.0), 90.0),
                    UnitSpawn("sub", "p1", submerged.id, submarineStart, 180.0, -8.0),
                    UnitSpawn("vehicle", "p1", vehicle.id, vehicleStart, 90.0)),
                combatContract = CombatContract(831, -1,
                    mapOf("a" to 8L, "z" to 9L, "scout" to 10L, "sub" to 11L, "vehicle" to 12L), 13L))
            val normal = ExperimentalPolicies().path
            var forceContinuation = false
            val path = object : PathPolicy by normal {
                override fun beginExact(map: PlanningTerrain, start: Position, target: Position, radius: Double,
                                        hull: HullRoute?, occupancy: RouteOccupancy?, counters: PathSearchCounters?): ExactPathSearch {
                    val actual = normal.beginExact(map, start, target, radius, hull, occupancy, counters)
                    return if (forceContinuation && hull is RectangularDeparture) deferOneSlice(actual) else actual
                }
            }
            val context = assertIs<InitResult.Accepted>(
                SimulationContext(ExperimentalPolicies(path = path)).initialize(scenario)).context
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.enablePathDiagnostics(context.world)
                val request = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), target))
                for (id in listOf("a", "z")) assertIs<ActionResult.Accepted>(
                    context.submitGroupMove(MoveAction("maneuver-$id", "p1", id, target, context.world), request))
                val publication = context.advanceToMoveOutcome(0L, "a")
                val shipA = publication.units.single { it.unitId == "a" }
                val originalSlot = assertNotNull(shipA.moveTarget)
                val shoreCell = VisibleTerrainCell(30, 10, true, 1)
                val oldSlotOwner = publication.units.single { it.moveTarget == target }.unitId
                assertEquals("a", oldSlotOwner)
                context.drainPathDiagnostics(context.world)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("hold-z", "p1", "z", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(PatrolAction("submarine-transit", "p1", "sub",
                    Position(650.0, 285.0), false, context.world)))
                assertIs<ActionResult.Accepted>(context.submit(PatrolAction("coast-patrol", "p1", "vehicle",
                    Position(675.0, 320.0), false, context.world)))
                forceContinuation = true
                val coast = (15..16).flatMap { r -> (27..34).map { c -> VisibleTerrainCell(c, r, false, 1) } }
                context.acceptVisibleTerrain(context.world, "p1", listOf(shoreCell) + coast)
                val queued = context.advance(Step(publication.stepIndex + 1, 1.0, "sim-tick"))
                assertEquals(originalSlot, queued.units.single { it.unitId == "a" }.moveTarget)
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("scout-after-repair", "p1", "scout",
                    Position(300.0, 280.0), context.world)))
                var repairStep: Long? = null
                var followingStep: Long? = null
                for (tick in (queued.stepIndex + 1)..(queued.stepIndex + 2_000)) {
                    val observation = context.advance(Step(tick, 1.0, "sim-tick"))
                    assertTrue(Event("movement-started", "a") !in observation.events)
                    assertTrue(Event("movement-cancelled", "a") !in observation.events, "Lost the original order at $tick")
                    if (Event("group-destination-reassigned", "a") in observation.events) {
                        assertEquals(null, repairStep)
                        repairStep = tick
                        val moved = observation.units.single { it.unitId == "a" }
                        val sample = context.drainPathDiagnostics(context.world).last { it.unitId == "a" }
                        assertEquals("maneuver-a", sample.actionId)
                        assertEquals(PathSearchReason.ROUTE_REPAIR, sample.reason)
                        assertEquals(moved.moveTarget, sample.searchTarget)
                        assertTrue(moved.moveTarget != originalSlot)
                        assertTrue(moved.speed >= 0.0)
                    }
                    if (Event("movement-started", "scout") in observation.events) followingStep = tick
                    val reissuedAt = repairStep
                    if (reissuedAt != null && followingStep != null) {
                        val sub = observation.units.single { it.unitId == "sub" }
                        val shorePatrol = observation.units.single { it.unitId == "vehicle" }
                        assertTrue(reissuedAt - queued.stepIndex >= 2, "The alternate slot must require multiple planning steps")
                        assertTrue(sub.height < -4.0 && sub.position != submarineStart && sub.moveTarget != null)
                        assertTrue(shorePatrol.position != vehicleStart && shorePatrol.moveTarget != null)
                        assertTrue(vehicle.hasContactLayerAt(shorePatrol.height))
                        val slotSearchReach = hull.boundingRadius + 60.0 + vehicle.movementCollision.boundingRadius
                        assertTrue(ScreenPlane.distanceSquared(shorePatrol.position, target) < slotSearchReach * slotSearchReach)
                        println("Alternate slot published at $reissuedAt and the following Move at $followingStep while the submarine and shore vehicle were moving")
                        return@withSubmarinePackage
                    }
                }
                error("Rectangular slot repair at $repairStep, following Move at $followingStep")
            } finally {
                context.close()
            }
        } }

    private fun deferOneSlice(search: ExactPathSearch, initial: Boolean = true): ExactPathSearch =
        object : ExactPathSearch by search {
            private var pending = initial

            override fun fork(rollback: PathSearchRollback): ExactPathSearch = deferOneSlice(search.fork(rollback), pending)

            override fun advance(budget: PathWorkBudget, cache: PathClearanceCache?) {
                if (!pending) search.advance(budget, cache)
                else {
                    pending = false
                    while (budget.take()) { /* Defer otherwise unchanged geometry work to the next step. */ }
                }
            }
        }

    @Test
    fun offset_grid_search_resumes_with_the_same_waypoints_after_a_rolled_back_step() = WaterTestScene.withMap { shore ->
        val map = waterMap(100, 16, shore) { col, row ->
            col in 1..99 && row in 5..6 || col >= 89 && row in 1..12
        }
        val terrain = PlanningTerrain.fromNoFog(map, MovementType.WATER)
        val start = Position(240.0, 120.0)
        val target = Position(110.0, 120.0)
        val shape = RectangularDeparture(hull, 90.0, reverse = false, maneuver = true)
        val policy = ExperimentalPolicies().path
        var work = policy.beginExact(terrain, start, target, 10.0, shape, counters = PathSearchCounters())
        val cache = PathClearanceCache()
        var slices = 0
        while (!work.finished) {
            val current = work
            val before = checkNotNull(current.counters).popped
            if (slices == 2) {
                val abandoned = PathSearchRollback()
                current.fork(abandoned).advance(PathWorkBudget(512), cache.fork())
                abandoned.restore()
                assertEquals(before, current.counters?.popped)
            }
            val journal = PathSearchRollback()
            work = current.fork(journal)
            work.advance(PathWorkBudget(512), cache)
            journal.commit()
            assertTrue(checkNotNull(work.counters).popped - before <= 512)
            assertTrue(++slices < 100, "Offset search should terminate within the probe bound")
        }
        val path = checkNotNull(work.path)
        assertTrue(slices > 3)
        assertTrue(path.any { it.x >= 1_820.0 })
        assertEquals(target, path.last())
        assertEquals(policy.findPath(terrain, start, target, 10.0, hull = shape), path)
    }

    private data class BasinBlocker(val start: Position, val exit: Position, val releaseStep: Long,
                                   val shape: MovementCollisionShape = MovementCollisionShape.Circle(16.0),
                                   val mass: Double = 500_000.0)

    private fun strait(shore: MapDefinition) = waterMap(30, 16, shore) { col, row ->
        col in 1..29 && row in 5..6 || col >= 19 && row in 1..12
    }

    private fun waterMap(width: Int, height: Int, shore: MapDefinition,
                         liquid: (column: Int, row: Int) -> Boolean): MapDefinition {
        val water = shore.groundGids[shore.widthTiles - 1]
        val land = shore.groundGids.first()
        return MapDefinition(width, height, 20, 20, shore.tileSets,
            IntArray(width * height) { if (liquid(it % width, it / width)) water else land },
            BlockingGrid(width, height, BooleanArray(width * height)))
    }

    private fun battleship(reverse: Double = 0.0): UnitDefinition {
        val fixture = WaterTestScene.unit(MovementType.WATER, reverse = reverse)
        return UnitDefinition("heavyBattleship", fixture.className, 40.0, 40.0, fixture.totalFrames, fixture.imagePath,
            fixture.imageScale, fixture.movement, emptySet(), movementCollision = hull, mass = 40_000.0)
    }

    private fun move(map: MapDefinition, start: Position, heading: Double, target: Position,
                     blocker: BasinBlocker? = null, searches: MutableList<PathSearchSample>? = null): List<UnitObservation> {
        val fixture = WaterTestScene.unit(MovementType.WATER)
        val ship = battleship()
        val base = WaterTestScene.scenario(map, MovementType.WATER, start)
        val blockingShip = UnitDefinition("turn-blocker", fixture.className, 16.0, 16.0, fixture.totalFrames,
            fixture.imagePath, fixture.imageScale, fixture.movement, emptySet(),
            movementCollision = blocker?.shape ?: MovementCollisionShape.Circle(16.0), mass = blocker?.mass ?: 500_000.0)
        val definitions = if (blocker == null) mapOf(ship.id to ship)
            else mapOf(ship.id to ship, blockingShip.id to blockingShip)
        val scenario = base.copy(unitDefinitions = definitions,
            units = base.units.map { it.copy(typeId = ship.id, heading = heading) } +
                listOfNotNull(blocker?.let { UnitSpawn("w2", "p1", blockingShip.id, it.start, 180.0) }))
        val opened = SimulationContext.initialize(scenario)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            if (searches != null) context.enablePathDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            val accepted = assertIs<ActionResult.Accepted>(
                context.submit(MoveAction("maneuver", "p1", "w1", target, context.world)))
            assertTrue(accepted.events.isEmpty())
            val terrain = HullTerrainGrid.authoritative(map, MovementType.WATER)
            val track = mutableListOf<UnitObservation>()
            var starts = 0
            for (tick in 0L until 5_000) {
                if (blocker != null && tick == blocker.releaseStep) {
                    assertIs<ActionResult.Accepted>(
                        context.submit(MoveAction("clear-basin", "p1", "w2", blocker.exit, context.world, tick)))
                }
                val sample = context.advance(Step(tick, 1.0, "sim-tick"))
                for (work in context.drainPlanningWorkDiagnostics(context.world)) {
                    assertTrue(work.used in 0..512)
                    assertEquals(work.used, work.slices.sumOf { it.workUnits })
                }
                if (searches != null) searches += context.drainPathDiagnostics(context.world)
                val playersUnits = context.observePlayer(context.world, "p1").units
                val unit = playersUnits.single { it.unitId == "w1" }
                track += unit
                assertTrue(UnitContact.poseFits(hull, unit.position, unit.heading, terrain),
                    "Hull crossed a bank at step $tick [${unit.position}, ${unit.heading}]")
                if (blocker != null) {
                    val other = playersUnits.single { it.unitId == "w2" }
                    assertTrue(!UnitContact.posesOverlap(hull, unit.position, unit.heading,
                        blockingShip.movementCollision, other.position, other.heading), "The vessels overlapped at step $tick")
                }
                assertTrue(unit.speed >= 0.0, "The ship reversed at step $tick")
                assertTrue(sample.events.none { it.unitId == "w1" && it.kind in setOf("path-unreachable", "movement-cancelled") },
                    "The Move was lost at step $tick [${unit.position}, ${unit.heading}] (${sample.events})")
                starts += sample.events.count { it.unitId == "w1" && it.kind == "movement-started" }
                if (Event("movement-arrived", "w1") in sample.events) {
                    assertEquals(1, starts)
                    assertTrue(ScreenPlane.distanceSquared(unit.position, target) < 49.0)
                    println("$start/$heading -> $target completed at step $tick, hull ${unit.position}/${unit.heading}")
                    return track
                }
            }
            val unit = track.last()
            error("The ship never reached $target from $start; final ${unit.position}/${unit.heading}, route ${unit.pathPoints}")
        } finally {
            context.close()
        }
    }
}
