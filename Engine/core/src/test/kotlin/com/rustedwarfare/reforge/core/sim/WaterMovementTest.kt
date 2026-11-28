package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.navigation.PathSearchReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class WaterMovementTest {
    @Test
    fun ordinary_move_stops_a_turn_whose_middle_pose_sweeps_across_the_bank() = WaterTestScene.withMap { shore ->
        val hull = WaterTestScene.unit(MovementType.WATER)
        val ship = UnitDefinition(hull.id, hull.className, hull.radius, hull.displayRadius, hull.totalFrames,
            hull.imagePath, hull.imageScale, hull.movement.copy(maxTurnSpeed = 90.0, turnAcceleration = 0.0), emptySet(), fogOfWarSightRange = 0,
            movementCollision = MovementCollisionShape.Rectangle(20.0, 10.0))
        val blockedColumn = (0 until shore.heightTiles).mapTo(linkedSetOf()) { 0 to it }
        val water = shore.groundGids[shore.widthTiles - 1]
        val map = MapDefinition(shore.widthTiles, shore.heightTiles, shore.tileWidth, shore.tileHeight, shore.tileSets,
            IntArray(shore.widthTiles * shore.heightTiles) { water }, shore.blocking, blockedColumn)

        fun turnFrom(x: Double): UnitObservation {
            val start = Position(x, 150.0)
            val base = WaterTestScene.scenario(map, MovementType.WATER, start)
            val input = base.copy(unitDefinitions = mapOf(ship.id to ship),
                units = listOf(base.units.single().copy(heading = 0.0)),
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val accepted = assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("turn-east", "p1", "w1", Position(x + 100.0, 150.0), context.world)))
                assertTrue(accepted.events.isEmpty())
                val frame = context.advance(Step(0L, 1.0, "sim-tick"))
                assertTrue(Event("movement-started", "w1") in frame.events)
                return frame.units.single()
            } finally {
                context.close()
            }
        }

        // 0 and 90 degrees each fit 10.5 units from the bank. Intermediate corners do not.
        val constrained = turnFrom(30.5)
        assertEquals(43.3440035485, constrained.heading, 1e-6)
        assertEquals(Position(30.5, 150.0), constrained.position)
        assertEquals(90.0, turnFrom(50.0).heading)
    }

    @Test
    fun a_rectangular_ship_uses_a_diagonal_channel_directly_or_after_turning_in_the_basin() = WaterTestScene.withMap { shore ->
        val hull = WaterTestScene.unit(MovementType.WATER)
        val ship = UnitDefinition(hull.id, hull.className, hull.radius, hull.displayRadius, hull.totalFrames,
            hull.imagePath, hull.imageScale, hull.movement, emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(48.0, 16.0))
        val water = shore.groundGids[shore.widthTiles - 1]
        val land = shore.groundGids.first()
        val target = Position(190.0, 190.0)
        for (basin in listOf(false, true)) {
            val gids = IntArray(12 * 12) { index ->
                val row = index / 12
                val column = index % 12
                if (kotlin.math.abs(row - column) <= 1 || basin && column <= 3) water else land
            }
            val map = MapDefinition(12, 12, 20, 20, shore.tileSets, gids, BlockingGrid(12, 12, BooleanArray(gids.size)))
            val start = if (basin) Position(40.0, 130.0) else Position(50.0, 50.0)
            val base = WaterTestScene.scenario(map, MovementType.WATER, start)
            val input = base.copy(unitDefinitions = mapOf(ship.id to ship),
                units = listOf(base.units.single().copy(heading = if (basin) 0.0 else 135.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enableMotionDiagnostics(context.world)
                val move = assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("cross-diagonal", "p1", "w1", target, context.world)))
                assertTrue(move.events.isEmpty())
                val frame = context.advanceToMoveOutcome(0, "w1")
                assertTrue(Event("movement-started", "w1") in frame.events, "basin=$basin: ${frame.events}")
                val route = frame.units.single().pathPoints
                assertEquals(!basin, route.size == 1)
                repeat(699) { context.advance(Step(frame.stepIndex + it + 1L, 1.0, "sim-tick")) }
                val final = context.observePlayer(context.world, "p1").units.single()
                val trace = context.drainMotionDiagnostics(context.world)
                assertTrue(ScreenPlane.distanceSquared(final.position, target) < 64.0,
                    "basin=$basin route=$route final=${final.position}/${final.heading}, " +
                        "first correction=${trace.firstOrNull { it.terrainDisposition != MotionTerrainDisposition.DIRECT }}")
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun a_rectangular_hull_can_move_tangent_to_the_map_edge() = WaterTestScene.withMap { shore ->
        val original = WaterTestScene.unit(MovementType.WATER)
        val rectangle = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, emptySet(),
            movementCollision = MovementCollisionShape.Rectangle(20.0, 10.0))
        val gids = IntArray(shore.widthTiles * shore.heightTiles) { shore.groundGids[shore.widthTiles - 1] }
        val water = MapDefinition(shore.widthTiles, shore.heightTiles, shore.tileWidth, shore.tileHeight,
            shore.tileSets, gids, shore.blocking)
        val start = Position(5.0, 150.0)
        val base = WaterTestScene.scenario(water, MovementType.WATER, start)
        val scenario = base.copy(unitDefinitions = mapOf(rectangle.id to rectangle),
            units = listOf(base.units.single().copy(heading = 0.0)))
        val opened = SimulationContext.initialize(scenario)
        val context = assertIs<InitResult.Accepted>(opened, opened.toString()).context
        try {
            val command = assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("tangent-edge", "p1", "w1", Position(5.0, 60.0), context.world)))
            assertTrue(command.events.isEmpty())
            repeat(220) {
                val frame = context.advance(Step(it.toLong(), 1.0, "sim-tick"))
                if (it == 0) assertTrue(Event("movement-started", "w1") in frame.events)
            }
            val ship = context.observePlayer(context.world, "p1").units.single()
            assertEquals(5.0, ship.position.x)
            assertTrue(ship.position.y < 68.0)
        } finally {
            context.close()
        }
    }

    @Test
    fun an_in_place_sweep_stops_on_the_legal_side_of_the_map_edge() = WaterTestScene.withMap { map ->
        val grid = HullTerrainGrid.authoritative(map, MovementType.AIR)
        val hull = MovementCollisionShape.Rectangle(20.0, 10.0)
        val start = Position(10.5, 150.0)
        val last = RectangularTurn.resolve(grid, hull, start, 0.0, 90.0)
        assertEquals(43.3440035485, last, 1e-6)
        assertTrue(RectangularTerrain(hull, last).contains(grid, start))
    }

    @Test
    fun a_long_hull_slides_along_a_tangent_bank_but_stops_at_a_small_intrusion() = WaterTestScene.withMap { shore ->
        val original = WaterTestScene.unit(MovementType.WATER)
        val ship = UnitDefinition(original.id, original.className, original.radius, original.displayRadius,
            original.totalFrames, original.imagePath, original.imageScale, original.movement, emptySet(),
            fogOfWarSightRange = 0, movementCollision = MovementCollisionShape.Rectangle(102.0, 20.0))
        val gids = IntArray(shore.widthTiles * shore.heightTiles) { shore.groundGids[shore.widthTiles - 1] }
        val map = MapDefinition(shore.widthTiles, shore.heightTiles, shore.tileWidth, shore.tileHeight, shore.tileSets,
            gids, shore.blocking, (4..6).mapTo(linkedSetOf()) { 1 to it })
        for (clearance in listOf(-0.000001, 0.0, 0.000001)) {
            val start = Position(50.0 + clearance, 230.0)
            val base = WaterTestScene.scenario(map, MovementType.WATER, start)
            val input = base.copy(unitDefinitions = mapOf(ship.id to ship),
                units = listOf(base.units.single().copy(heading = 0.0)),
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                val move = assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("follow-bank", "p1", "w1", Position(start.x, 65.0), context.world)))
                assertTrue(move.events.isEmpty())
                repeat(330) {
                    val frame = context.advance(Step(it.toLong(), 1.0, "sim-tick"))
                    if (it == 0) assertTrue(Event("movement-started", "w1") in frame.events)
                }
                val final = context.observe().units.single()
                assertEquals(start.x, final.position.x)
                if (clearance < 0.0) assertEquals(191.0, final.position.y, 1e-8)
                else assertTrue(final.position.y < 73.0)
                println("bank x=40; width=20; length=102; clearance=$clearance; end=${final.position}; heading=${final.heading}")
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun ordinary_water_body_reverses_and_stop_retains_signed_inertia_without_mech_pose() = WaterTestScene.withMap { map ->
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
            unitDefinitions = mapOf("water-test" to WaterTestScene.unit(MovementType.WATER, reverse = 1.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            val initialPose = assertNotNull(context.observe().units.single().waterPose)
            assertEquals(false, initialPose.reversing)
            assertEquals(0f, initialPose.sinkSpeed)
            assertEquals(false, initialPose.settled)
            context.submit(MoveAction("reverse", "p1", "w1", Position(450.0, 200.0), context.world))
            val first = context.advance(Step(0, 1.0, "sim-tick")).units.single()
            assertEquals(90.0, first.heading)
            assertEquals(-.035, first.speed, 1e-12)
            assertEquals(499.965, first.position.x, 1e-9)
            assertNull(first.mechPose)
            assertEquals(true, assertNotNull(first.waterPose).reversing)
            assertEquals(first.waterPose, context.observePlayer(context.world, "p1").units.single().waterPose)
            val second = context.advance(Step(1, 1.0, "sim-tick")).units.single()
            assertEquals(-.07, second.speed, 1e-12)
            context.submit(StopAction("stop", "p1", "w1", context.world))
            val stopping = context.advance(Step(2, 1.0, "sim-tick")).units.single()
            assertEquals(-.056, stopping.speed, 1e-12)
            assertEquals(499.839, stopping.position.x, 1e-9)
            assertNull(stopping.moveTarget)
            repeat(20) { context.advance(Step(it + 3L, 1.0, "sim-tick")) }
            val final = context.observe().units.single()
            assertEquals(0.0, final.speed)
            assertEquals(90.0, final.heading)
            assertNull(final.mechPose)
            assertEquals(499.965, first.position.x, 1e-9)
            assertEquals(false, initialPose.reversing, "Published initial pose remains unchanged")
        } finally { context.close() }
    }

    @Test
    fun hidden_building_blocks_water_truth_collision_without_entering_terrain_memory() = WaterTestScene.withMap(bridge = true) { map ->
        val boat = WaterTestScene.unit(MovementType.WATER, sight = 0)
        val building = UnitDefinition("bridge-building", "owned-test-building", 6.0, 6.0, 1,
            boat.imagePath, 1.0, MovementProfile(MovementType.NONE, 0.0, 0.0, 0.0, 0.0, 0.0, false, false),
            emptySet(), 0, durability = DurabilityProfile(100.0, null),
            building = BuildingProfile(BuildingFootprint(0, 0, 0, 0)))
        val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 210.0)).copy(
            unitDefinitions = mapOf("boat" to boat, "building" to building),
            units = listOf(UnitSpawn("boat", "p1", "boat", Position(500.0, 210.0), 90.0),
                UnitSpawn("building", "p2", "building", Position(610.0, 210.0), 0.0)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
        try {
            assertTrue(context.observePlayer(context.world, "p1").units.none { it.unitId == "building" })
            context.submit(MoveAction("blind", "p1", "boat", Position(700.0, 210.0), context.world))
            var corrections = 0
            repeat(450) { step ->
                val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                if (step == 0) assertEquals(listOf(Position(700.0, 210.0)), sample.units.single { it.unitId == "boat" }.pathPoints)
                val position = sample.units.single { it.unitId == "boat" }.position
                assertTrue(position.x < 600.0, "A hidden building still contributes real WATER collision: $position")
                corrections += sample.events.count { it.kind == "movement-blocked" }
            }
            assertTrue(corrections > 0)
            val view = context.observePlayer(context.world, "p1")
            assertTrue(view.units.none { it.unitId == "building" })
            assertNull(view.knowledge!!.cell(30, 10).lastKnownGroundGid)
        } finally { context.close() }
    }

    @Test
    fun hidden_dry_barrier_does_not_leak_and_revelation_repairs_the_water_route() {
        for (automatic in listOf(false, true)) WaterTestScene.withCorridor(false) { openMap ->
            WaterTestScene.withCorridor(true) { blockedMap ->
                fun input(map: MapDefinition): Scenario = WaterTestScene.scenario(map, MovementType.WATER,
                    Position(10.0, 110.0)).copy(
                    unitDefinitions = mapOf("water-test" to WaterTestScene.unit(MovementType.WATER, sight = 1)),
                    units = listOf(UnitSpawn("w1", "p1", "water-test", Position(10.0, 110.0), 0.0)),
                    automaticVision = if (automatic) KnowledgeRules(TerrainRetention.MEMORY,
                        PathInformationSource.PLAYER_KNOWLEDGE, false) else null)
                val open = assertIs<InitResult.Accepted>(SimulationContext.initialize(input(openMap))).context
                val blocked = assertIs<InitResult.Accepted>(SimulationContext.initialize(input(blockedMap))).context
                try {
                    for (context in listOf(open, blocked)) {
                        if (!automatic) context.enableKnowledge(context.world,
                            KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
                        context.enablePathDiagnostics(context.world)
                        context.submit(MoveAction("north", "p1", "w1", Position(10.0, 30.0), context.world))
                        val first = context.advance(Step(0, 1.0, "sim-tick"))
                        assertTrue(Event("movement-started", "w1") in first.events)
                        assertEquals(listOf(PathSearchReason.USER_MOVE), context.drainPathDiagnostics(context.world).map { it.reason })
                    }
                    assertEquals(open.observe().units.single().pathPoints, blocked.observe().units.single().pathPoints)
                    val old = blocked.knowledgeSnapshot(blocked.world, "p1")
                    assertNull(old.cell(0, 3).lastKnownGroundGid)
                    var step = 1L
                    if (automatic) {
                        while (blocked.knowledgeSnapshot(blocked.world, "p1").cell(0, 3).lastKnownGroundGid == null && step < 200L) {
                            blocked.advance(Step(step++, 1.0, "sim-tick"))
                            blocked.drainPathDiagnostics(blocked.world)
                        }
                        assertEquals(1, blocked.knowledgeSnapshot(blocked.world, "p1").cell(0, 3).lastKnownGroundGid)
                    } else blocked.acceptVisibleTerrain(blocked.world, "p1", listOf(VisibleTerrainCell(0, 3, false, 1)))
                    val sample = blocked.advance(Step(step, 1.0, "sim-tick"))
                    assertNull(sample.units.single().moveTarget)
                    assertTrue(sample.events.any { it.kind == "path-unreachable" })
                    assertEquals(listOf(PathSearchReason.KNOWLEDGE_REPLAN),
                        blocked.drainPathDiagnostics(blocked.world).map { it.reason })
                    assertNull(old.cell(0, 3).lastKnownGroundGid)
                } finally { open.close(); blocked.close() }
            }
        }
    }

    @Test
    fun water_routes_use_authorized_gids_and_keep_land_knowledge_independent() = WaterTestScene.withMap { map ->
        for (retention in TerrainRetention.entries) {
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("wet" to WaterTestScene.unit(MovementType.WATER),
                    "dry" to WaterTestScene.unit(MovementType.LAND)),
                units = listOf(UnitSpawn("wet", "p1", "wet", Position(500.0, 200.0), 90.0),
                    UnitSpawn("dry", "p1", "dry", Position(100.0, 200.0), 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                context.enableKnowledge(context.world, KnowledgeRules(retention, PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.acceptVisibleTerrain(context.world, "p1", List(800) { index ->
                    VisibleTerrainCell(index % 40, index / 40, index % 40 >= 20, if (index % 40 >= 20) 2 else 1)
                })
                val old = context.knowledgeSnapshot(context.world, "p1")
                assertTrue(old.cell(25, 10).lastKnownBlocked == true)
                for (id in listOf("dry", "wet", "dry", "wet")) {
                    val result = assertIs<ActionResult.Accepted>(context.submit(MoveAction("$id-${context.observe().sampleId}",
                        "p1", id, Position(if (id == "wet") 700.0 else 300.0, 200.0), context.world)))
                    assertTrue(result.events.isEmpty())
                }
                repeat(800) {
                    val frame = context.advance(Step(it.toLong(), 1.0, "sim-tick"))
                    if (it == 0) assertEquals(2, frame.events.count { event -> event.kind == "movement-started" })
                }
                assertTrue(context.observe().units.single { it.unitId == "wet" }.position.x > 690.0)
                assertTrue(context.observe().units.single { it.unitId == "dry" }.position.x > 290.0)
                assertTrue(old.cell(25, 10).lastKnownBlocked == true)
            } finally { context.close() }
        }
    }

    @Test
    fun same_player_land_and_water_orders_keep_distinct_routes_and_both_arrive() = WaterTestScene.withMap { map ->
        for (order in listOf(listOf("wet", "dry"), listOf("dry", "wet"))) {
            val input = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 200.0)).copy(
                unitDefinitions = mapOf("wet" to WaterTestScene.unit(MovementType.WATER),
                    "dry" to WaterTestScene.unit(MovementType.LAND)),
                units = listOf(UnitSpawn("wet", "p1", "wet", Position(500.0, 200.0), 90.0),
                    UnitSpawn("dry", "p1", "dry", Position(100.0, 200.0), 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context
            try {
                for (id in order) {
                    val x = if (id == "wet") 700.0 else 300.0
                    val result = assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("move-$id", "p1", id, Position(x, 200.0), context.world)))
                    assertTrue(result.events.isEmpty(), "order=$order id=$id")
                }
                val arrivals = mutableListOf<String>()
                repeat(800) { step ->
                    val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
                    if (step == 0) assertEquals(order, sample.events.filter { it.kind == "movement-started" }.map { it.unitId })
                    arrivals += sample.events.filter { it.kind == "movement-arrived" }.map { it.unitId!! }
                    assertTrue(sample.units.single { it.unitId == "wet" }.position.x >= 400.0)
                    assertTrue(sample.units.single { it.unitId == "dry" }.position.x < 400.0)
                }
                assertEquals(listOf("dry", "wet"), arrivals.sorted())
                val final = context.observe().units
                assertTrue(final.single { it.unitId == "wet" }.position.x > 690.0)
                assertTrue(final.single { it.unitId == "dry" }.position.x > 290.0)
                assertTrue(final.all { it.speed == 0.0 && it.moveTarget == null && it.mechPose == null })
            } finally { context.close() }
        }
    }

    @Test
    fun water_units_initialize_only_on_water_while_land_spawns_stay_blocked() = WaterTestScene.withMap { map ->
        val water = MovementType.valueOf("WATER")
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(
            WaterTestScene.scenario(map, water, Position(500.0, 200.0)))).context
        context.close()
        val dry = assertIs<InitResult.Rejected>(SimulationContext.initialize(
            WaterTestScene.scenario(map, water, Position(100.0, 200.0))))
        assertEquals("invalid-spawn", dry.error.code)
        val wetLand = assertIs<InitResult.Rejected>(SimulationContext.initialize(
            WaterTestScene.scenario(map, MovementType.LAND, Position(500.0, 200.0))))
        assertEquals("invalid-spawn", wetLand.error.code)
        assertIs<InitResult.Accepted>(SimulationContext.initialize(
            WaterTestScene.scenario(map, MovementType.LAND, Position(100.0, 200.0)))).context.close()
    }
}

/** A self-owned finite map: dry columns 0..19, water columns 20..39. */
internal object WaterTestScene {
    fun withCorridor(dryBarrier: Boolean, action: (MapDefinition) -> Unit) {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val map = ReadOnlyTestContentPacks.loadCachedMap("corridor:$dryBarrier:$image") {
            val csv = List(7) { if (dryBarrier && it == 3) "1" else "2" }.joinToString(",")
            var loaded: MapDefinition? = null
            com.rustedwarfare.reforge.core.content.withMap("""<map orientation="orthogonal" renderorder="right-down" width="1" height="7" tilewidth="20" tileheight="20">
                <tileset firstgid="1" name="water-corridor" tilewidth="20" tileheight="20">
                  <image source="$image" width="100" height="160"/>
                  <tile id="1"><properties><property name="water" value=""/></properties></tile>
                </tileset>
                <layer name="Ground" width="1" height="7"><data encoding="csv">$csv</data></layer>
                </map>""") { path -> loaded = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value }
            checkNotNull(loaded)
        }
        action(map)
    }

    fun withMap(bridge: Boolean = false, liquidProperty: String = "water", action: (MapDefinition) -> Unit) {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val map = ReadOnlyTestContentPacks.loadCachedMap("map:$bridge:$liquidProperty:$image") {
            val csv = List(800) { if (bridge && it == 430) "3" else if (it % 40 < 20) "1" else "2" }.joinToString(",")
            var loaded: MapDefinition? = null
            com.rustedwarfare.reforge.core.content.withMap("""<map orientation="orthogonal" renderorder="right-down" width="40" height="20" tilewidth="20" tileheight="20">
                <tileset firstgid="1" name="land-water" tilewidth="20" tileheight="20">
                  <image source="$image" width="100" height="160"/>
                  <tile id="1"><properties><property name="$liquidProperty" value=""/></properties></tile>
                  <tile id="2"><properties><property name="water-bridge" value=""/></properties></tile>
                </tileset>
                <layer name="Ground" width="40" height="20"><data encoding="csv">$csv</data></layer>
                </map>""") { path -> loaded = assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value }
            checkNotNull(loaded)
        }
        action(map)
    }

    fun unit(type: MovementType, sight: Int = 15, reverse: Double = 0.0): UnitDefinition = UnitDefinition("water-test", "CustomUnitMetadata", 6.0, 6.0, 1,
        tilesetImagePath("p001-ground"), 1.0,
        MovementProfile(type, .7, .02, .05, 1.0, .05, false, false, reverse), emptySet(), sight)

    fun scenario(map: MapDefinition, type: MovementType, position: Position): Scenario = Scenario(
        "water-test", map, mapOf("water-test" to unit(type)),
        listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.NONE)),
        listOf(UnitSpawn("w1", "p1", "water-test", position, 90.0)),
        CoordinateModel("water.coordinates", "2d", "x-right", "y-down", "top-left", "world-unit", "double", "ieee754-binary64"),
        TimeModel("water.time", "sim-tick", "ieee754-binary64", "fixed-explicit-delta"), MovementContract(16.0, 4.0))
}
