package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NarrowPassageTest {
    private data class Order(val ship: String, val owner: String, val target: Position, val tick: Long = 0)

    @Test
    fun an_entered_ship_clears_the_passage_before_the_opposing_move_enters() = withPassage { map ->
        val right = Position(700.0, 245.0)
        val left = Position(445.0, 135.0)
        val scene = scene(map, boat("through", "p1", 550.0), boat("opposing", "p2", 650.0))
        val orders = listOf(Order("through", "p1", right), Order("opposing", "p2", left))
        val first = run(scene, orders, 720)
        val firstClear = first.indexOfFirst { it.ship("through").position.x >= 626.0 }
        val opposingEntry = first.indexOfFirst { it.ship("opposing").position.x < 626.0 }
        println("Already entered: clear=$firstClear opposite entry=$opposingEntry arrivals=${arrivals(first)}")
        assertTrue(firstClear >= 0 && opposingEntry > firstClear)
        // An accepted logical order can precede its budgeted physical route.
        assertTrue(first.subList(0, opposingEntry).all { it.ship("opposing").tasks.firstOrNull()?.target == left })
        assertEquals(setOf("through", "opposing"), arrivals(first).keys)
        assertRepeated(first, run(scene.copy(units = scene.units.reversed()), orders, 720))
    }

    @Test
    fun both_arrival_times_and_same_step_order_come_from_the_entrance() = WaterTestScene.withMap { base ->
        // Both short routes publish in the same step under the real 512 budget,
        // so this remains an entrance tie rather than a planning-queue race.
        val banks = (0 until 9).filter { it != 4 }.map { 6 to it }.toSet()
        val map = MapDefinition(12, 9, 20, 20, base.tileSets, IntArray(108) { 2 },
            BlockingGrid(12, 9, BooleanArray(108)), banks)
        val scene = scene(map, boat("z-east", "p1", 95.0).copy(position = Position(95.0, 90.0)),
            boat("a-west", "p2", 165.0).copy(position = Position(165.0, 90.0)))
        val east = Order("z-east", "p1", Position(170.0, 110.0))
        val west = Order("a-west", "p2", Position(90.0, 70.0))
        val tied = run(scene, listOf(east, west), 870)
        val staggered = run(scene, listOf(east, west.copy(tick = 12)), 870)
        assertTrue(tied.first().events.count { it.kind == "movement-started" } == 2)
        fun entries(frames: List<Observation>) = listOf(frames.indexOfFirst { it.ship("z-east").position.x > 114.0 },
            frames.indexOfFirst { it.ship("a-west").position.x < 146.0 })
        println("Identical entrance steps: ${entries(tied)}; east ready earlier: ${entries(staggered)}")
        assertTrue(entries(tied).let { (eastTick, westTick) -> westTick in 0 until eastTick })
        assertTrue(entries(staggered).let { (eastTick, westTick) -> eastTick in 0 until westTick })
        assertRepeated(tied, run(scene.copy(units = scene.units.reversed()), listOf(west, east), 870))
        assertEquals(setOf("z-east", "a-west"), arrivals(tied).keys)
        assertEquals(setOf("z-east", "a-west"), arrivals(staggered).keys)
    }

    @Test
    fun the_older_opposite_queue_precedes_units_reaching_the_served_entrance_later() = withPassage { map ->
        val scene = scene(map, boat("b-inside", "p1", 550.0), boat("c-admitted", "p1", 495.0),
            boat("z-waiting", "p2", 646.0), boat("a-late", "p1", 455.0))
        val orders = listOf(
            Order("b-inside", "p1", Position(700.0, 240.0)),
            Order("c-admitted", "p1", Position(709.0, 257.0)),
            Order("z-waiting", "p2", Position(432.0, 133.0), 12),
            Order("a-late", "p1", Position(735.0, 151.0), 35))
        val frames = run(scene, orders, 1480)
        println("Queued arrival route: ${listOf(500, 700, 1000, 1480).map { i -> frames[i].ship("c-admitted").let { listOf(i, it.position, it.heading, it.moveTarget, it.pathPoints) } }}")
        val existingClear = frames.indexOfFirst { it.ship("c-admitted").position.x >= 626.0 }
        val olderEntry = frames.indexOfFirst { it.ship("z-waiting").position.x < 626.0 }
        val olderClear = frames.indexOfFirst { it.ship("z-waiting").position.x <= 514.0 }
        val lateEntry = frames.indexOfFirst { it.ship("a-late").position.x > 514.0 }
        println("Group: admitted clear=$existingClear older entry=$olderEntry older clear=$olderClear late entry=$lateEntry arrivals=${arrivals(frames)}")
        assertTrue(existingClear >= 0 && olderEntry > existingClear && olderClear > olderEntry && lateEntry > olderClear)
        assertTrue(frames.subList(100, lateEntry).all { it.ship("a-late").moveTarget == orders.last().target })
        val heldAt = frames[400].ship("a-late").position
        val held = frames.take(lateEntry).filter { it.ship("a-late").position == heldAt }
        assertTrue(held.size > 420 && held.last().stepIndex - held.first().stepIndex == held.size.toLong() - 1)
        println("Late approach held at $heldAt for ${held.size} steps with its original move")
        assertEquals(orders.map { it.ship }.toSet(), arrivals(frames).keys)
        // Submission order is part of the budgeted input. Reverse the initial
        // storage order while replaying the identical command sequence.
        assertRepeated(frames, run(scene.copy(units = scene.units.reversed()), orders, 1480))
    }

    @Test
    fun a_vertical_passage_uses_the_same_handoff() = WaterTestScene.withMap { base ->
        val banks = (7..11).flatMap { row -> (20 until base.widthTiles).filter { it != 29 }.map { it to row } }.toSet()
        val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks)
        val north = boat("north", "p1", 590.0).copy(position = Position(590.0, 165.0), heading = 180.0)
        val south = boat("south", "p2", 590.0).copy(position = Position(590.0, 265.0), heading = 0.0)
        val frames = run(scene(map, north, south), listOf(Order("north", "p1", Position(645.0, 330.0)),
            Order("south", "p2", Position(535.0, 65.0))), 720)
        val northClear = frames.indexOfFirst { it.ship("north").position.y >= 246.0 }
        val southEntry = frames.indexOfFirst { it.ship("south").position.y < 246.0 }
        assertTrue(northClear >= 0 && southEntry > northClear)
        assertEquals(setOf("north", "south"), arrivals(frames).keys)
        println("Vertical: clear=$northClear opposite entry=$southEntry")
    }

    @Test
    fun an_entered_rectangle_keeps_its_berth_until_its_stern_clears() = withPassage { map ->
        val ship = boat("long", "p1", 560.0).copy(typeId = "long")
        val opposing = boat("circle", "p2", 750.0)
        val opening = scene(map, ship, opposing)
        val water = opening.unitDefinitions.getValue("water-test")
        val long = UnitDefinition("long", water.className, water.radius, water.displayRadius, water.totalFrames,
            water.imagePath, water.imageScale, water.movement, emptySet(), water.fogOfWarSightRange,
            movementCollision = MovementCollisionShape.Rectangle(102.0, 20.0))
        val frames = run(opening.copy(unitDefinitions = opening.unitDefinitions + (long.id to long)),
            listOf(Order("long", "p1", Position(745.0, 259.0)), Order("circle", "p2", Position(445.0, 135.0))), 1350)
        val sternClear = frames.indexOfFirst {
            val pose = it.ship("long")
            val axis = ScreenPlane.displacement(pose.heading, 1.0)
            pose.position.x - 51.0 * kotlin.math.abs(axis.x) - 10.0 * kotlin.math.abs(axis.y) >= 620.0
        }
        val opposingEntry = frames.indexOfFirst { it.ship("circle").position.x < 626.0 }
        println("Rectangle: stern clear=$sternClear opposite entry=$opposingEntry arrivals=${arrivals(frames)}")
        if (opposingEntry < 0) for (tick in listOf(0, 180, 240, 310, 420, 650, 900, 1350)) {
            val frame = frames[tick]
            println("  $tick ${frame.units.map { listOf(it.unitId, it.position, it.heading, it.pathPoints) }} ${frame.events}")
        }
        assertTrue(sternClear >= 0 && opposingEntry > sternClear)
        assertEquals(setOf("long", "circle"), arrivals(frames).keys)
    }

    @Test
    fun enough_room_for_both_hulls_keeps_both_moves_running() = withPassage(rows = 9..10) { map ->
        val east = boat("east", "p1", 495.0)
        val west = boat("west", "p2", 645.0).copy(position = Position(645.0, 210.0))
        val frames = run(scene(map, east, west),
            listOf(Order("east", "p1", Position(700.0, 190.0)), Order("west", "p2", Position(440.0, 210.0))), 480)
        val eastEntry = frames.indexOfFirst { it.ship("east").position.x > 514.0 }
        val westEntry = frames.indexOfFirst { it.ship("west").position.x < 626.0 }
        val firstExit = frames.indexOfFirst { it.ship("east").position.x >= 626.0 || it.ship("west").position.x <= 514.0 }
        assertTrue(eastEntry in 0 until firstExit && westEntry in 0 until firstExit)
        assertEquals(setOf("east", "west"), arrivals(frames).keys)
    }

    @Test
    fun an_unseen_opposing_move_does_not_change_the_visible_approach() = withPassage { map ->
        val east = boat("east", "p1", 495.0)
        val west = boat("west", "p2", 645.0)
        val original = scene(map, east, west)
        val lowSight = original.copy(unitDefinitions = mapOf("water-test" to WaterTestScene.unit(MovementType.WATER, sight = 1)),
            automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.ORIGINAL_COMPATIBILITY, false))
        val eastOrder = Order("east", "p1", Position(700.0, 245.0))
        val unseen = run(lowSight, listOf(eastOrder, Order("west", "p2", Position(445.0, 135.0))), 80)
        val alone = run(lowSight.copy(units = listOf(east)), listOf(eastOrder), 80)
        assertEquals(alone.map { it.ship("east").position to it.ship("east").moveTarget },
            unseen.map { it.ship("east").position to it.ship("east").moveTarget })
        assertTrue(unseen.last().ship("east").position.x > 514.0)
    }

    @Test
    fun different_planning_masks_still_share_the_water_unless_the_hover_route_bypasses_it() =
        WaterTestScene.withMap { base ->
            val dryBanks = base.groundGids.apply {
                for (column in 26..30) for (row in 0 until base.heightTiles) {
                    if (row != 9) this[row * base.widthTiles + column] = 1
                }
            }
            val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
                base.tileSets, dryBanks, base.blocking)
            assertTrue(map.waterBlocking.isBlocked(28, 8) && !map.hoverBlocking.isBlocked(28, 8))
            val east = boat("east", "p1", 550.0)
            val west = boat("west", "p2", 646.0).copy(typeId = "hover-test")
            val hover = testHull(WaterTestScene.unit(MovementType.HOVER), "hover-test")
            val opening = scene(map, east, west)
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (hover.id to hover))
            val eastOrder = Order("east", "p1", Position(705.0, 245.0))
            val westOrder = Order("west", "p2", Position(445.0, 190.0))
            val frames = run(scene, listOf(eastOrder, westOrder), 770)
            val eastClear = frames.indexOfFirst { it.ship(east.unitId).position.x >= 626.0 }
            val westEntry = frames.indexOfFirst { it.ship(west.unitId).position.x < 626.0 }
            println("Water and hover: east clear=$eastClear west entry=$westEntry arrivals=${arrivals(frames)}")
            assertTrue(eastClear >= 0 && westEntry > eastClear)
            assertEquals(setOf(east.unitId, west.unitId), arrivals(frames).keys)
            val around = run(scene.copy(units = listOf(east, west.copy(position = Position(646.0, 150.0)))),
                listOf(eastOrder, westOrder.copy(target = Position(445.0, 150.0))), 770)
            val aroundWest = around.indexOfFirst { it.ship(west.unitId).position.x < 626.0 }
            assertTrue(aroundWest in 0 until eastClear)
            assertEquals(setOf(east.unitId, west.unitId), arrivals(around).keys)
        }

    @Test
    fun turning_single_lane_connects_its_two_entrances() = WaterTestScene.withMap { base ->
        val banks = buildSet {
            for (col in 20 until base.widthTiles) for (row in 0 until base.heightTiles) {
                val basin = col in 20..24 || col >= 26 && row >= 12
                val lane = row == 7 && col in 25..28 || col == 28 && row in 7..11
                if (!basin && !lane) add(col to row)
            }
        }
        val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks)
        val inside = boat("inside", "p1", 530.0).copy(position = Position(530.0, 150.0))
        val approaching = boat("approaching", "p2", 570.0).copy(position = Position(570.0, 264.0), heading = 0.0)
        val water = WaterTestScene.unit(MovementType.WATER)
        val turning = testHull(water, "water-test", maxTurn = 30.0)
        val scene = scene(map, inside, approaching).copy(unitDefinitions = mapOf(turning.id to turning))
        val first = Order(inside.unitId, "p1", Position(635.0, 325.0))
        val next = Order(approaching.unitId, "p2", Position(438.0, 101.0))
        assertEquals(setOf(inside.unitId), arrivals(run(scene.copy(units = listOf(inside)), listOf(first), 900)).keys)
        assertEquals(setOf(approaching.unitId), arrivals(run(scene.copy(units = listOf(approaching)), listOf(next), 900)).keys)
        val frames = run(scene, listOf(first, next), 1050)
        val firstClear = frames.indexOfFirst { it.ship(inside.unitId).position.y >= 246.0 }
        val nextEntry = frames.indexOfFirst { it.ship(approaching.unitId).position.y < 246.0 }
        println("Turning lane: first clear=$firstClear next entry=$nextEntry arrivals=${arrivals(frames)}")
        assertTrue(firstClear >= 0 && nextEntry > firstClear)
        assertEquals(setOf(inside.unitId, approaching.unitId), arrivals(frames).keys)
    }

    private fun testHull(water: UnitDefinition, id: String, maxTurn: Double = water.movement.maxTurnSpeed) =
        UnitDefinition(id, water.className, water.radius, water.displayRadius, water.totalFrames, water.imagePath,
            water.imageScale, water.movement.copy(maxTurnSpeed = maxTurn, turnAcceleration = maxTurn), emptySet(),
            water.fogOfWarSightRange)

    private fun boat(id: String, owner: String, x: Double) =
        UnitSpawn(id, owner, "water-test", Position(x, 190.0), if (owner == "p1") 90.0 else 270.0)

    private fun scene(map: MapDefinition, vararg ships: UnitSpawn): Scenario =
        WaterTestScene.scenario(map, MovementType.WATER, ships.first().position).copy(units = ships.toList(),
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)))

    private fun run(scene: Scenario, orders: List<Order>, last: Long): List<Observation> {
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (order in orders) {
                val receipt = context.submit(MoveAction("move-${order.ship}", order.owner, order.ship, order.target,
                    context.world, order.tick))
                assertIs<ActionResult.Accepted>(receipt, receipt.toString())
            }
            return (0L..last).map { tick -> context.advance(Step(tick, 1.0, "sim-tick")) }.also { frames ->
                println("Move publication: ${orders.map { order -> order.ship to frames.indexOfFirst { frame -> frame.events.any { it.kind == "movement-started" && it.unitId == order.ship } } }}")
            }
        } finally {
            context.close()
        }
    }

    private fun Observation.ship(id: String): UnitObservation = units.single { it.unitId == id }

    private fun arrivals(frames: List<Observation>) = frames.flatMap { frame ->
        frame.events.filter { it.kind == "movement-arrived" }.map { checkNotNull(it.unitId) to frame.stepIndex }
    }.toMap()

    private fun assertRepeated(first: List<Observation>, repeat: List<Observation>) {
        assertEquals(first.size, repeat.size)
        for (index in first.indices) assertEquals(first[index].units, repeat[index].units, "first differing step $index")
        assertEquals(arrivals(first), arrivals(repeat))
    }

    private fun withPassage(rows: IntRange = 9..9, action: (MapDefinition) -> Unit) = WaterTestScene.withMap { base ->
        val banks = (26..30).flatMap { column -> (0 until base.heightTiles).filter { it !in rows }.map { column to it } }.toSet()
        action(MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks))
    }
}
