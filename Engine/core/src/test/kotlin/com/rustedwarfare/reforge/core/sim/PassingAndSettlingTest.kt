package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PassingAndSettlingTest {
    @Test
    fun a_sliding_ground_unit_finishes_the_last_four_units_to_its_exact_stop() {
        val base = MechTestScene.scenario(position = Position(80.0, 80.0))
        val mover = base.unitDefinitions.getValue("mech")
        val map = MapDefinition(8, 8, 20, 20, emptyList(), IntArray(64),
            com.rustedwarfare.reforge.core.content.BlockingGrid(8, 8, BooleanArray(64)))
        val scene = base.copy(map = map)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(159.0, 80.0)
            assertIs<ActionResult.Accepted>(ctx.submit(MoveAction("exact", "p1", "u1", click, ctx.world)))
            for (step in 0L..600L) ctx.advance(Step(step, 1.0, "sim-tick"))
            val u = ctx.observe().units.single()
            assertEquals(click, assertNotNull(u.moveArrival).originalTarget)
            assertEquals(160.0 - mover.movementCollision.boundingRadius, u.position.x, 1e-6)
            assertEquals(null, u.moveTarget)
            assertEquals(0.0, u.speed)
        } finally { ctx.close() }
    }

    @Test
    fun patrol_and_move_orders_slide_to_their_own_right_on_first_contact() = WaterTestScene.withMap { map ->
        for ((eastPatrol, westPatrol) in listOf(false to true, true to false, true to true)) {
            val hull = hull()
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(550.0, 190.0)).copy(
                unitDefinitions = mapOf(hull.id to hull), units = listOf(
                    UnitSpawn("east", "p1", hull.id, Position(550.0, 190.0), 90.0),
                    UnitSpawn("west", "p1", hull.id, Position(590.0, 190.0), 270.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                for ((id, patrol, target) in listOf(Triple("east", eastPatrol, Position(710.0, 190.0)),
                    Triple("west", westPatrol, Position(445.0, 190.0)))) {
                    val action = if (patrol) PatrolAction("$id-original", "p1", id, target, false, context.world)
                        else MoveAction("$id-original", "p1", id, target, context.world)
                    assertIs<ActionResult.Accepted>(context.submit(action))
                }
                val frames = (0L..140L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                assertTrue(frames.any { it.ship("east").position.y > 190.5 },
                    "eastbound must steer south (right-hand): eastPatrol=$eastPatrol westPatrol=$westPatrol")
                assertTrue(frames.any { it.ship("west").position.y < 189.5 },
                    "westbound must steer north (right-hand): eastPatrol=$eastPatrol westPatrol=$westPatrol")
                assertTrue(frames.last().ship("east").position.x > frames.last().ship("west").position.x,
                    "oncoming units must pass each other: east=${frames.last().ship("east").position}, west=${frames.last().ship("west").position}")
                for (frame in frames) {
                    assertTrue(ScreenPlane.distanceSquared(frame.ship("east").position, frame.ship("west").position) >= 144.0 - 1e-8)
                    assertTrue(frame.events.none { it.kind == "movement-cancelled" })
                    if (eastPatrol) {
                        assertNotNull(frame.ship("east").moveTarget)
                        assertEquals("east-original", frame.ship("east").tasks.first().actionId)
                    }
                    if (westPatrol) {
                        assertNotNull(frame.ship("west").moveTarget)
                        assertEquals("west-original", frame.ship("west").tasks.first().actionId)
                    }
                }
            } finally { context.close() }
        }
    }

    @Test
    fun a_contacted_circle_can_depart_between_tile_centers() = WaterTestScene.withMap { map ->
        val source = hull()
        fun land(id: String, mass: Double) = UnitDefinition(id, source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement.copy(type = MovementType.LAND),
            emptySet(), fogOfWarSightRange = 15, movementCollision = MovementCollisionShape.Circle(6.0), mass = mass)
        val tank = land("tank", 3000.0)
        val anchor = land("anchor", 100000.0)
        val scene = WaterTestScene.scenario(map, MovementType.LAND, Position(200.0, 200.0)).copy(
            unitDefinitions = mapOf(tank.id to tank, anchor.id to anchor), units = listOf(
                UnitSpawn("tank", "p1", tank.id, Position(200.0, 200.0), 90.0),
                UnitSpawn("front", "p1", anchor.id, Position(212.0, 200.0), 0.0),
                UnitSpawn("north", "p1", anchor.id, Position(200.0, 188.0), 0.0),
                UnitSpawn("south", "p1", anchor.id, Position(200.0, 212.0), 0.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val goal = Position(300.0, 200.0)
            context.enablePathDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("original", "p1", "tank", goal, context.world)))
            var arrived = false
            for (tick in 0L..1800L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                arrived = arrived || frame.events.any { it.unitId == "tank" && it.kind == "movement-arrived" }
                for (other in frame.units.filter { it.unitId != "tank" })
                    assertTrue(ScreenPlane.distanceSquared(frame.ship("tank").position, other.position) >= 144.0 - 1e-8)
            }
            assertTrue(arrived)
            println("Circle departure paths: ${context.drainPathDiagnostics(context.world)}")
            println("Circle departure final: ${context.observe().ship("tank").position}")
            assertTrue(ScreenPlane.distanceSquared(context.observe().ship("tank").position, goal) < 49.0,
                "must use the open western departure, not end at a false nearest fallback")
        } finally { context.close() }
    }

    @Test
    fun a_long_hull_at_the_target_does_not_turn_to_face_the_remaining_offset() = WaterTestScene.withMap { map ->
        val hull = hull(MovementCollisionShape.Rectangle(102.0, 20.0))
        val start = Position(500.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
            unitDefinitions = mapOf(hull.id to hull), units = listOf(UnitSpawn("long", "p1", hull.id, start, 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            // Position is already captured, while facing the remaining offset
            // would require a ninety-degree turn. No final alignment is needed.
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("original", "p1", "long",
                Position(500.0, 196.0), context.world)))
            var arrived = false
            for (tick in 0L..600L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                arrived = arrived || frame.events.any { it.kind == "movement-arrived" && it.unitId == "long" }
                assertEquals(start, frame.ship("long").position)
                assertEquals(90.0, frame.ship("long").heading)
                assertEquals(0.0, frame.ship("long").speed)
                assertEquals(0.0, frame.ship("long").angularVelocity)
            }
            assertTrue(arrived)
            assertEquals(null, context.observe().ship("long").moveTarget)
        } finally { context.close() }
    }

    @Test
    fun a_captured_group_member_stops_even_while_departing_traffic_overlaps_its_buffer() = WaterTestScene.withMap { map ->
        val body = hull()
        val click = Position(600.0, 200.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, click).copy(
            unitDefinitions = mapOf(body.id to body), units = listOf(
                UnitSpawn("a", "p1", body.id, click, 90.0),
                UnitSpawn("b", "p1", body.id, Position(600.0, 212.0), 270.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enableMotionDiagnostics(context.world)
            context.enablePlanningWorkDiagnostics(context.world)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "b"), click))
            for (id in listOf("a", "b")) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("group-$id", "p1", id, click, context.world), group))
            var started = false
            for (tick in 0L..2000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val motion = context.drainMotionDiagnostics(context.world)
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                if (frame.events.any { it.kind == "movement-started" && it.unitId == "a" }) {
                    started = true
                    val a = motion.single { it.unitId == "a" }
                    val b = motion.single { it.unitId == "b" }
                    val distance = ScreenPlane.distanceSquared(a.positionBefore, b.positionBefore)
                    assertTrue(distance >= 144.0 && distance < 256.0)
                    assertTrue(frame.events.any { it.kind == "movement-arrived" && it.unitId == "a" },
                        "The captured member must finish without waiting for transient buffer occupancy")
                    assertEquals(false, a.steeringInvoked)
                    assertEquals(0.0, a.integratedSpeed)
                    assertEquals(90.0, a.finalHeading)
                    assertEquals(null, frame.ship("a").moveTarget)
                    break
                }
            }
            assertTrue(started)
        } finally { context.close() }
    }

    @Test
    fun an_already_positioned_long_group_member_does_not_align_to_the_suggested_arrival_bearing() = WaterTestScene.withMap { map ->
        val long = hull(MovementCollisionShape.Rectangle(102.0, 20.0))
        val source = hull()
        val short = UnitDefinition("short", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement, emptySet(),
            movementCollision = source.movementCollision, mass = 3000.0)
        val start = Position(500.0, 190.0)
        val click = Position(500.0, 196.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
            unitDefinitions = mapOf(long.id to long, short.id to short), units = listOf(
                UnitSpawn("long", "p1", long.id, start, 90.0),
                UnitSpawn("short", "p1", short.id, Position(700.0, 320.0), 270.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            val group = assertNotNull(context.requestGroupMove("p1", listOf("long", "short"), click))
            for (id in listOf("long", "short")) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("group-$id", "p1", id, click, context.world), group))
            var arrived = false
            var after = 0
            for (tick in 0L..4000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                val unit = frame.ship("long")
                assertEquals(start, unit.position, "Already inside positional capture; no final manoeuvre")
                assertEquals(90.0, unit.heading, "The six-unit remaining offset points south, not east")
                assertEquals(0.0, unit.speed)
                assertEquals(0.0, unit.angularVelocity)
                if (frame.events.any { it.kind == "movement-arrived" && it.unitId == "long" }) arrived = true
                if (arrived && ++after >= 600) break
            }
            assertTrue(arrived)
            assertEquals(600, after)
            assertEquals(null, context.observe().ship("long").moveTarget)
        } finally { context.close() }
    }

    @Test
    fun two_opposing_queues_pass_in_a_two_lane_channel() = WaterTestScene.withMap { base ->
        val banks = (0 until base.widthTiles).flatMap { col ->
            (0 until base.heightTiles).filter { it !in 9..10 }.map { col to it }
        }.toSet()
        val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks)
        val hull = hull()
        val spawns = (0..2).flatMap { index -> listOf(
            UnitSpawn("east-$index", "p1", hull.id, Position(550.0 - 25.0 * index, 200.0), 90.0),
            UnitSpawn("west-$index", "p1", hull.id, Position(566.0 + 25.0 * index, 200.0), 270.0)) }
        val scene = WaterTestScene.scenario(map, MovementType.WATER, spawns.first().position).copy(
            unitDefinitions = mapOf(hull.id to hull), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            for (index in 0..2) for ((side, x) in listOf("east" to 740.0 - 20.0 * index, "west" to 440.0 + 20.0 * index)) {
                val id = "$side-$index"
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("original-$id", "p1", id,
                    Position(x, 200.0), context.world)))
            }
            val arrived = mutableSetOf<String>()
            for (tick in 0L..1800L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                arrived += frame.events.filter { it.kind == "movement-arrived" }.mapNotNull { it.unitId }
                for (unit in frame.units) assertTrue(unit.position.y in 186.0..214.0, "must stay inside both banks")
                for (i in frame.units.indices) for (j in i + 1 until frame.units.size)
                    assertTrue(ScreenPlane.distanceSquared(frame.units[i].position, frame.units[j].position) >= 144.0 - 1e-8)
                assertTrue(frame.events.none { it.kind == "movement-cancelled" })
            }
            assertEquals(spawns.map { it.unitId }.toSet(), arrived)
            assertTrue(context.observe().units.all { it.moveTarget == null && it.speed == 0.0 })
        } finally { context.close() }
    }

    @Test
    fun two_tanks_enter_separate_parking_places_without_driving_the_parked_one_ahead() = WaterTestScene.withMap { map ->
        val source = hull()
        val tank = UnitDefinition("group-tank", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement.copy(type = MovementType.LAND),
            emptySet(), fogOfWarSightRange = 15, movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0)
        val click = Position(217.0, 166.0)
        val spawns = listOf(UnitSpawn("a", "p1", tank.id, Position(104.0, 166.0), 90.0),
            UnitSpawn("b", "p1", tank.id, Position(132.0, 166.0), 90.0))
        val scene = WaterTestScene.scenario(map, MovementType.LAND, spawns.first().position).copy(
            unitDefinitions = mapOf(tank.id to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val group = assertNotNull(context.requestGroupMove("p1", spawns.map { it.unitId }, click))
            spawns.forEach { assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("go-${it.unitId}", "p1", it.unitId, click, context.world), group)) }
            val parked = mutableMapOf<String, Position>()
            val firstOwnStep = mutableMapOf<String, Long>()
            context.enableMotionDiagnostics(context.world)
            for (tick in 0L..1000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                for (m in context.drainMotionDiagnostics(context.world)) {
                    if (m.originMoveId == "go-${m.unitId}" && m.commandTargetBefore != null &&
                        (m.finalPosition != m.positionBefore || m.finalHeading != m.headingBefore))
                        firstOwnStep.putIfAbsent(m.unitId, tick)
                }
                for ((id, at) in parked) assertEquals(at, frame.ship(id).position, "parked $id swept from its spot at $tick")
                frame.events.filter { it.kind == "movement-arrived" }.mapNotNull { it.unitId }
                    .forEach { parked[it] = frame.ship(it).position }
                assertTrue(ScreenPlane.distanceSquared(frame.ship("a").position, frame.ship("b").position) >= 144.0 - 1e-7)
                if (parked.size == 2) break
            }
            assertEquals(setOf("a", "b"), parked.keys)
            assertEquals(parked.keys, firstOwnStep.keys)
            assertTrue(firstOwnStep.values.max() <= 2L, "two unobstructed starts: $firstOwnStep")
        } finally { context.close() }
    }
    @Test
    fun fifty_selected_members_all_arrive_then_remain_exactly_still() = WaterTestScene.withMap { map ->
        val source = hull()
        val tank = UnitDefinition("group-tank", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement.copy(type = MovementType.LAND),
            emptySet(), fogOfWarSightRange = 15, movementCollision = MovementCollisionShape.Circle(6.0), mass = 3000.0)
        val click = Position(217.0, 166.0)
        val spawns = List(50) { index ->
            val start = Position(62.0 + index % 10 * 33.0, 72.0 + index / 10 * 53.0)
            UnitSpawn("tank-${index.toString().padStart(2, '0')}", "p1", tank.id, start,
                ScreenPlane.normalize(90.0 + Math.toDegrees(kotlin.math.atan2(click.y - start.y, click.x - start.x))))
        }
        val scene = WaterTestScene.scenario(map, MovementType.LAND, spawns.first().position).copy(
            unitDefinitions = mapOf(tank.id to tank), units = spawns)
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            val group = assertNotNull(context.requestGroupMove("p1", spawns.map { it.unitId }, click))
            for (spawn in spawns) assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("click-${spawn.unitId}", "p1", spawn.unitId, click, context.world), group))
            val arrived = mutableSetOf<String>()
            var stopped: List<Triple<String, Position, Double>>? = null
            var quiet = 0
            for (tick in 0L..6000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val work = context.drainPlanningWorkDiagnostics(context.world)
                assertTrue(work.all { it.used <= it.limit })
                arrived += frame.events.filter { it.kind == "movement-arrived" }.mapNotNull { it.unitId }
                assertTrue(frame.events.none { it.kind == "group-destination-unavailable" || it.kind == "movement-cancelled" })
                for (i in frame.units.indices) for (j in i + 1 until frame.units.size)
                    assertTrue(ScreenPlane.distanceSquared(frame.units[i].position, frame.units[j].position) >= 144.0 - 1e-7)
                if (arrived.size == spawns.size) {
                    assertTrue(frame.units.all { it.moveTarget == null && it.speed == 0.0 && it.angularVelocity == 0.0 })
                    val poses = frame.units.map { Triple(it.unitId, it.position, it.heading) }
                    if (stopped == null) stopped = poses else { assertEquals(stopped, poses); quiet++ }
                    if (quiet == 600) break
                }
            }
            assertEquals(spawns.map { it.unitId }.toSet(), arrived, "all members must finish their commands; active=" +
                context.observe().units.filter { it.moveTarget != null }.map { "${it.unitId}@${it.position} spd=${it.speed} path=${it.pathPoints.size}" })
            assertEquals(600, quiet)
        } finally { context.close() }
    }

    @Test
    fun an_arrived_single_hull_stays_at_the_reported_arrival_pose() = WaterTestScene.withMap { map ->
        for (shape in listOf(MovementCollisionShape.Circle(6.0), MovementCollisionShape.Rectangle(102.0, 20.0))) {
            val hull = hull(shape)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 190.0)).copy(
                unitDefinitions = mapOf(hull.id to hull), units = listOf(UnitSpawn("single", "p1", hull.id, Position(500.0, 190.0), 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("original", "p1", "single", Position(700.0, 190.0), context.world)))
                val frames = (0L..1300L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                val arrival = frames.indexOfFirst { it.events.any { event -> event.kind == "movement-arrived" && event.unitId == "single" } }
                assertTrue(arrival in 0..800, "must arrive before the stability observation")
                val stopped = frames[arrival].ship("single")
                assertEquals(0.0, stopped.speed, "arrival must end propulsion")
                assertEquals(0.0, stopped.angularVelocity)
                for (frame in frames.drop(arrival)) {
                    assertEquals(stopped.position, frame.ship("single").position, "no coasting after arrival at ${frame.stepIndex}")
                    assertEquals(stopped.heading, frame.ship("single").heading)
                }
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun a_body_pushed_beside_the_entrance_can_rejoin_its_own_tile_center() = WaterTestScene.withMap { base ->
        val banks = (26..30).flatMap { col -> (0 until base.heightTiles).filter { it != 9 }.map { col to it } }.toSet()
        val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks)
        val start = Position(624.9787013268153, 196.65148792769665)
        val hull = hull()
        val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
            unitDefinitions = mapOf(hull.id to hull), units = listOf(UnitSpawn("rejoin", "p1", hull.id, start, 276.89828886779037)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("original", "p1", "rejoin", Position(445.0, 135.0), context.world)))
            val frames = (0L..1200L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(frames.any { it.ship("rejoin").position.x < 514.0 }, "must enter the reachable passage")
            assertTrue(ScreenPlane.distanceSquared(frames.last().ship("rejoin").position, Position(445.0, 135.0)) < 49.0,
                "must reach the actual reachable target, not just emit an arrival at a fallback")
            assertTrue(frames.any { it.events.any { event -> event.kind == "movement-arrived" && event.unitId == "rejoin" } })
        } finally {
            context.close()
        }
    }

    @Test
    fun opposing_units_already_inside_a_single_lane_are_pushed_back_then_finish_the_original_orders() = WaterTestScene.withMap { base ->
        val banks = (26..30).flatMap { col -> (0 until base.heightTiles).filter { it != 9 }.map { col to it } }.toSet()
        val map = MapDefinition(base.widthTiles, base.heightTiles, base.tileWidth, base.tileHeight,
            base.tileSets, base.groundGids, base.blocking, banks)
        val hull = hull()
        val eastTarget = Position(710.0, 245.0)
        val westTarget = Position(445.0, 135.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(550.0, 190.0)).copy(
            unitDefinitions = mapOf(hull.id to hull), units = listOf(
                UnitSpawn("z-east", "p1", hull.id, Position(550.0, 190.0), 90.0),
                UnitSpawn("a-west", "p1", hull.id, Position(590.0, 190.0), 270.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("east-original", "p1", "z-east", eastTarget, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("west-original", "p1", "a-west", westTarget, context.world, 12)))
            val frames = (0L..1800L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val pushed = frames.indexOfFirst { it.ship("a-west").position.x > 590.01 }
            val clear = frames.indexOfFirst { it.ship("z-east").position.x >= 626.0 }
            val reentry = frames.indexOfFirst { it.ship("a-west").position.x < 514.0 }
            assertTrue(pushed >= 0, "priority side must push the opposing mover back")
            assertEquals(westTarget, frames[pushed].ship("a-west").moveTarget)
            assertTrue(clear >= 0 && reentry > clear, "priority side clears before the displaced unit returns")
            assertEquals(setOf("z-east", "a-west"), frames.flatMap { f -> f.events.filter { it.kind == "movement-arrived" }.map { it.unitId } }.toSet())
            for (frame in frames) {
                assertTrue(ScreenPlane.distanceSquared(frame.ship("z-east").position, frame.ship("a-west").position) >= 144.0 - 1e-8)
                assertTrue(frame.events.none { it.kind == "movement-cancelled" })
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun equal_mass_oncoming_units_slide_to_their_own_right_without_replanning() = WaterTestScene.withMap { map ->
        val hull = hull()
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(550.0, 190.0)).copy(
            unitDefinitions = mapOf(hull.id to hull), units = listOf(
                UnitSpawn("east", "p1", hull.id, Position(550.0, 190.0), 90.0),
                UnitSpawn("west", "p1", hull.id, Position(566.0, 190.0), 270.0)))
        fun run(): List<Observation> {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("east-order", "p1", "east", Position(710.0, 190.0), context.world)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("west-order", "p1", "west", Position(445.0, 190.0), context.world)))
                return (0L..800L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            } finally {
                context.close()
            }
        }
        val frames = run()
        assertTrue(frames.any { it.ship("east").position.y > 190.5 }, "eastbound unit must steer south (right-hand)")
        assertTrue(frames.any { it.ship("west").position.y < 189.5 }, "westbound unit must steer north (right-hand)")
        for (frame in frames) {
            val first = frame.ship("east")
            val second = frame.ship("west")
            assertTrue(ScreenPlane.distanceSquared(first.position, second.position) >= 144.0 - 1e-8)
            assertTrue(frame.events.none { it.kind == "movement-cancelled" })
        }
        assertEquals(setOf("east", "west"), frames.flatMap { f -> f.events.filter { it.kind == "movement-arrived" }.map { it.unitId } }.toSet())
        assertEquals(frames.map { it.units }, run().map { it.units })
    }

    private fun hull(shape: MovementCollisionShape = MovementCollisionShape.Circle(6.0)): UnitDefinition {
        val source = WaterTestScene.unit(MovementType.WATER)
        return UnitDefinition("passing-hull", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale,
            source.movement.copy(maxTurnSpeed = 90.0, turnAcceleration = 0.0), emptySet(),
            fogOfWarSightRange = 15, movementCollision = shape, mass = 3000.0)
    }

    private fun Observation.ship(id: String) = units.single { it.unitId == id }
}
