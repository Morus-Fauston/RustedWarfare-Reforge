package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import com.rustedwarfare.reforge.core.path.PathSearchReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class WaterMovementTest {
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
            assertEquals(listOf(Position(700.0, 210.0)), context.observe().units.single { it.unitId == "boat" }.pathPoints)
            var corrections = 0
            repeat(450) { step ->
                val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
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
                        context.drainPathDiagnostics(context.world)
                    }
                    assertEquals(open.observe().units.single().pathPoints, blocked.observe().units.single().pathPoints)
                    val old = blocked.knowledgeSnapshot(blocked.world, "p1")
                    assertNull(old.cell(0, 3).lastKnownGroundGid)
                    var step = 0L
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
                    assertEquals(listOf("movement-started"), result.events.map { it.kind })
                }
                repeat(800) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
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
                    assertEquals(listOf("movement-started"), result.events.map { it.kind }, "order=$order id=$id")
                }
                val arrivals = mutableListOf<String>()
                repeat(800) { step ->
                    val sample = context.advance(Step(step.toLong(), 1.0, "sim-tick"))
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
        val csv = List(7) { if (dryBarrier && it == 3) "1" else "2" }.joinToString(",")
        com.rustedwarfare.reforge.core.content.withMap("""<map orientation="orthogonal" renderorder="right-down" width="1" height="7" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="water-corridor" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="1"><properties><property name="water" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="1" height="7"><data encoding="csv">$csv</data></layer>
            </map>""") { path -> action(assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value) }
    }

    fun withMap(bridge: Boolean = false, action: (MapDefinition) -> Unit) {
        val image = tilesetImagePath("p001-ground").toString().replace("&", "&amp;")
        val csv = List(800) { if (bridge && it == 430) "3" else if (it % 40 < 20) "1" else "2" }.joinToString(",")
        com.rustedwarfare.reforge.core.content.withMap("""<map orientation="orthogonal" renderorder="right-down" width="40" height="20" tilewidth="20" tileheight="20">
            <tileset firstgid="1" name="land-water" tilewidth="20" tileheight="20">
              <image source="$image" width="100" height="160"/>
              <tile id="1"><properties><property name="water" value=""/></properties></tile>
              <tile id="2"><properties><property name="water-bridge" value=""/></properties></tile>
            </tileset>
            <layer name="Ground" width="40" height="20"><data encoding="csv">$csv</data></layer>
            </map>""") { path -> action(assertIs<ContentResult.Success<MapDefinition>>(ContentLoader.loadMap(path)).value) }
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
