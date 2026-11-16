package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UnitYieldTest {
    @Test
    fun tank_contact_moves_an_idle_unit_gradually_for_every_player_relationship() =
        WaterTestScene.withMap { map ->
            val moverStart = Position(180.0, 190.0)
            val occupantStart = Position(192.0, 190.0)
            for ((owner, otherTeam) in listOf("p1" to "enemy", "p2" to "own", "p2" to "enemy")) {
                val tank = movable("tank", MovementType.LAND)
                val target = Position(320.0, 190.0)
                val scene = WaterTestScene.scenario(map, MovementType.LAND, moverStart).copy(
                    unitDefinitions = mapOf(tank.id to tank),
                    players = listOf(Player("p1", PlayerControl.LOCAL, "own"), Player("p2", PlayerControl.NONE, otherTeam)),
                    units = listOf(UnitSpawn("mover", "p1", tank.id, moverStart, 90.0),
                        UnitSpawn("blocker", owner, tank.id, occupantStart, 270.0)),
                )
                withContext(scene) { context ->
                    move(context, "mover", target)
                    var previous = occupantStart.x
                    repeat(40) { tick ->
                        val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                        val pushed = frame.find("blocker")
                        assertEquals(previous + .35, pushed.position.x, 1e-8)
                        assertEquals(12.0, pushed.position.x - frame.find("mover").position.x, 1e-8)
                        assertEquals(190.0, pushed.position.y, 1e-9)
                        assertEquals(270.0, pushed.heading)
                        assertTrue(pushed.tasks.isEmpty())
                        assertEquals(0.0, pushed.speed)
                        previous = pushed.position.x
                    }
                    assertIs<ActionResult.Accepted>(context.submit(StopAction("stop", "p1", "mover", context.world)))
                    repeat(300) { tick ->
                        val pushed = context.advance(Step(tick + 40L, 1.0, "sim-tick")).find("blocker")
                        assertEquals(previous, pushed.position.x)
                    }
                    println("tank-contact owner=$owner otherTeam=$otherTeam pushed=${previous - occupantStart.x} over 40 steps")
                }
            }
        }

    @Test
    fun touching_idle_units_hold_their_initial_positions() = WaterTestScene.withCorridor(dryBarrier = false) { map ->
        val mover = movable("mover-hull", MovementType.WATER)
        val blocker = movable("blocker-hull", MovementType.WATER)
        val start = Position(10.0, 82.0)
        val occupied = Position(10.0, 70.0)
        withContext(pair(map, mover, blocker, start, occupied, 0.0)) { context ->
            repeat(500) { tick ->
                val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                assertEquals(start, frame.find("mover").position)
                assertEquals(occupied, frame.find("blocker").position)
            }
        }
    }

    @Test
    fun both_active_units_keep_their_commands_and_the_pushed_unit_respects_its_step_limit() =
        WaterTestScene.withCorridor(dryBarrier = false) { map ->
            val mover = movable("fast", MovementType.WATER)
            val blocker = movable("slow", MovementType.WATER, speed = .2)
            val start = Position(10.0, 100.0)
            val occupied = Position(10.0, 88.0)
            val destination = Position(10.0, 30.0)
            withContext(pair(map, mover, blocker, start, occupied, 0.0)) { context ->
                move(context, "mover", destination)
                move(context, "blocker", Position(10.0, 40.0))
                var priorFront = start.y
                var priorBack = occupied.y
                repeat(30) { tick ->
                    val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                    val front = frame.find("mover")
                    val back = frame.find("blocker")
                    val frontTravel = priorFront - front.position.y
                    val backTravel = priorBack - back.position.y
                    assertTrue(frontTravel in 0.0..(.7 + 1e-9))
                    assertTrue(backTravel in 0.0..(maxOf(.2, frontTravel) + 1e-9))
                    assertTrue(front.position.y - back.position.y >= 12.0 - 1e-8)
                    assertEquals("move-mover", front.tasks.single().actionId)
                    assertEquals("move-blocker", back.tasks.single().actionId)
                    if (tick == 0) assertTrue(backTravel > .2, "The slower mover must be displaced at contact")
                    priorFront = front.position.y
                    priorBack = back.position.y
                }
            }
        }

    @Test
    fun shore_and_map_edge_clip_both_units_at_the_last_legal_point() = WaterTestScene.withMap { map ->
        val mover = movable("mover-hull", MovementType.WATER)
        val blocker = movable("blocker-hull", MovementType.WATER)
        for ((limitingX, direction) in listOf(406.0 to -1.0, 794.0 to 1.0)) {
            val occupied = Position(limitingX - direction * .25, 190.0)
            val start = Position(occupied.x - direction * 12.0, occupied.y)
            withContext(pair(map, mover, blocker, start, occupied, if (direction > 0) 90.0 else 270.0)) { context ->
                move(context, "mover", Position(limitingX, 190.0))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals(limitingX, frame.find("blocker").position.x, 1e-8)
                assertEquals(limitingX - direction * 12.0, frame.find("mover").position.x, 1e-8)
                assertTrue(Event("movement-blocked", "mover") in frame.events)
                println("shore or edge limit=$limitingX actual=${frame.find("blocker").position.x}")
            }
        }
    }

    @Test
    fun a_third_body_clips_the_pair_without_starting_a_chain() = WaterTestScene.withMap { map ->
        val mover = movable("mover-hull", MovementType.WATER)
        val blocker = movable("blocker-hull", MovementType.WATER)
        val start = Position(500.0, 190.0)
        val occupied = Position(512.0, 190.0)
        val third = Position(524.25, 190.0)
        val scene = pair(map, mover, blocker, start, occupied, 90.0)
        withContext(scene.copy(units = scene.units + UnitSpawn("third", "p1", blocker.id, third, 90.0))) { context ->
            move(context, "mover", Position(650.0, 190.0))
            repeat(20) { tick ->
                val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                assertEquals(third, frame.find("third").position)
                assertEquals(512.25, frame.find("blocker").position.x, 1e-8)
                assertEquals(500.25, frame.find("mover").position.x, 1e-8)
                assertTrue(Event("movement-blocked", "mover") in frame.events)
            }
        }
    }

    @Test
    fun a_building_footprint_clips_the_pair() {
        val scene = HelicopterFactoryScene.scenario()
        val map = scene.map
        val mover = movable("mover-hull", MovementType.LAND)
        val blocker = movable("blocker-hull", MovementType.LAND)
        val building = scene.unitDefinitions.getValue("airFactory")
        val wall = Position(270.0, 150.0)
        val profile = checkNotNull(building.building)
        val cells = BuildingTerrain.footprint(map, wall, profile.footprint, profile.anchor)
        val left = cells.minOf { it.first }.toDouble() * map.tileWidth
        val limit = left - 6.0
        val start = Position(limit - 12.25, wall.y)
        val occupied = Position(limit - .25, wall.y)
        withContext(scene.copy(unitDefinitions = scene.unitDefinitions + listOf(mover, blocker).associateBy { it.id },
            units = listOf(UnitSpawn("mover", "p1", mover.id, start, 90.0),
                UnitSpawn("blocker", "p1", blocker.id, occupied, 90.0), UnitSpawn("wall", "p1", building.id, wall, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("mover" to 8L, "blocker" to 9L, "wall" to 10L), 11L),
        )) { context ->
            move(context, "mover", Position(limit, wall.y))
            val frame = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(wall, frame.find("wall").position)
            assertEquals(limit, frame.find("blocker").position.x, 1e-8)
            assertEquals(limit - 12.0, frame.find("mover").position.x, 1e-8)
            assertTrue(Event("movement-blocked", "mover") in frame.events)
        }
    }

    @Test
    fun an_idle_long_hull_is_pushed_at_the_bow_without_turning() = WaterTestScene.withMap { map ->
        val tug = movable("tug", MovementType.WATER, mass = 12000.0)
        val ship = movable("ship", MovementType.WATER, MovementCollisionShape.Rectangle(102.0, 20.0), mass = 40000.0)
        val start = Position(500.0, 190.0)
        val occupied = Position(557.0, 190.0)
        withContext(pair(map, tug, ship, start, occupied, 90.0, occupiedHeading = 270.0)) { context ->
            move(context, "mover", Position(650.0, 190.0))
            val frame = context.advance(Step(0, 1.0, "sim-tick"))
            val pushed = frame.find("blocker")
            assertEquals(occupied.x + .7 * 12.0 / 52.0, pushed.position.x, 1e-8)
            assertEquals(occupied.y, pushed.position.y, 1e-8)
            assertEquals(270.0, pushed.heading)
        }
    }

    @Test
    fun a_carrier_stays_anchored_while_a_passenger_boards_at_hull_contact() = WaterTestScene.withMap { map ->
        val carrier = LandingCraftScene.definitions().getValue("hovercraft")
        val carrierRadius = assertIs<MovementCollisionShape.Circle>(carrier.movementCollision).radius
        val fixture = movable("tank", MovementType.LAND)
        val cargoRadius = maxOf(6.0, 22.0 - carrierRadius)
        val cargo = movable("cargo", MovementType.LAND, MovementCollisionShape.Circle(cargoRadius))
        val occupied = Position(220.0, 190.0)
        val moverStart = Position(occupied.x - carrierRadius - 6.0, occupied.y)
        val cargoStart = Position(occupied.x, occupied.y - carrierRadius - cargoRadius)
        val input = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a-mover", "p1", fixture.id, moverStart, 90.0),
            UnitSpawn("carrier", "p1", carrier.id, occupied, 90.0),
            UnitSpawn("z-cargo", "p1", cargo.id, cargoStart, 180.0),
        )).let { it.copy(unitDefinitions = it.unitDefinitions + listOf(fixture, cargo).associateBy { definition -> definition.id }) }
        withContext(input) { context ->
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "z-cargo", "carrier",
                LoadDirection.INTO, context.world)))
            assertEquals(emptyList<String>(), context.observe().find("carrier").transport!!.passengerIds)
            move(context, "a-mover", Position(325.0, 190.0))
            val boarding = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals(occupied, boarding.find("carrier").position)
            assertEquals(listOf("z-cargo"), boarding.find("carrier").transport!!.passengerIds)
            assertTrue(Event("movement-blocked", "a-mover") in boarding.events)
            assertIs<ActionResult.Accepted>(context.submit(UnloadAction("unload", "p1", "carrier", true, context.world)))
            val unloading = context.advance(Step(1L, 1.0, "sim-tick"))
            assertTrue(unloading.find("carrier").transport!!.unloading)
            assertEquals(occupied, unloading.find("carrier").position)
        }
    }

    @Test
    fun the_loading_pair_does_not_push_its_cargo_at_the_first_hull_contact() = WaterTestScene.withMap { map ->
        val fixture = movable("cargo", MovementType.LAND)
        val start = Position(220.0, 190.0)
        val occupied = Position(241.3, 190.0)
        val scene = LandingCraftScene.scenario(map, spawns = listOf(
            UnitSpawn("a-carrier", "p1", "hovercraft", start, 90.0),
            UnitSpawn("cargo", "p1", fixture.id, occupied, 270.0),
        ))
        withContext(scene.copy(unitDefinitions = scene.unitDefinitions + (fixture.id to fixture))) { context ->
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("collect", "p1", "a-carrier", "cargo",
                LoadDirection.UP, context.world)))
            var loaded = false
            var tick = 0L
            while (!loaded && tick < 100) {
                val frame = context.advance(Step(tick++, 1.0, "sim-tick"))
                loaded = frame.find("a-carrier").transport!!.passengerIds == listOf("cargo")
                if (!loaded) assertEquals(occupied, frame.find("cargo").position, "Before loading at step ${tick - 1}")
            }
            assertTrue(loaded)
        }
    }

    @Test
    fun a_hovercraft_uses_its_land_speed_budget_for_the_whole_shoreline_step() = WaterTestScene.withMap { map ->
        val pusher = movable("pusher", MovementType.HOVER)
        for ((carrierId, pusherId) in listOf("a-carrier" to "z-pusher", "z-carrier" to "a-pusher")) {
            val start = Position(360.0, 190.0)
            val pushingStart = Position(399.0, 169.0)
            val scene = LandingCraftScene.scenario(map, spawns = listOf(
                UnitSpawn(carrierId, "p1", "hovercraft", start, 90.0),
                UnitSpawn(pusherId, "p1", pusher.id, pushingStart, 180.0),
            ))
            withContext(scene.copy(unitDefinitions = scene.unitDefinitions + (pusher.id to pusher))) { context ->
                move(context, carrierId, Position(550.0, 190.0))
                var previous = context.observe()
                var tick = 0L
                while (previous.find(carrierId).position.x + .9 < 400.0 && tick < 120L)
                    previous = context.advance(Step(tick++, 1.0, "sim-tick"))
                val before = previous.find(carrierId)
                assertEquals(.9, before.speed, 1e-9)
                assertTrue(before.position.x < 400.0 && before.position.x + .9 >= 400.0)
                move(context, pusherId, Position(pushingStart.x, 270.0))
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                val after = frame.find(carrierId)
                val travel = kotlin.math.sqrt(ScreenPlane.distanceSquared(before.position, after.position))
                println("shoreline order=$carrierId/$pusherId before=${before.position} after=${after.position} travel=$travel")
                assertTrue(Event("movement-blocked", pusherId) in frame.events)
                assertTrue(after.position.x >= 400.0, "$carrierId must cross into water in the measured step")
                assertTrue(travel <= .9 + 1e-9, "$carrierId used more than its starting land speed budget: $travel")
            }
        }
    }

    @Test
    fun a_worker_is_anchored_during_initial_construction_and_after_resuming_the_site() {
        val base = HelicopterFactoryScene.scenario()
        val worker = base.units.first()
        val workerRadius = assertIs<MovementCollisionShape.Circle>(
            base.unitDefinitions.getValue(worker.typeId).movementCollision).radius
        val mover = movable("tank", MovementType.LAND)
        val start = Position(worker.position.x - workerRadius - 6.0, worker.position.y)
        val scene = base.copy(unitDefinitions = base.unitDefinitions + (mover.id to mover),
            units = listOf(worker, UnitSpawn("a-mover", "p1", mover.id, start, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf(worker.unitId to 8L, "a-mover" to 9L), 10L))
        withContext(scene) { context ->
            val site = Position(270.0, 150.0)
            val nonce = "a".repeat(32)
            val siteId = "constructed:p1:$nonce"
            var tick = 0L
            fun advance() = context.advance(Step(tick++, 1.0, "sim-tick"))
            fun awaitWork(): Observation {
                var sample = advance()
                val deadline = tick + 100
                while (sample.construction?.workers?.singleOrNull { it.unitId == worker.unitId }?.working != true &&
                    tick < deadline) sample = advance()
                assertTrue(sample.construction!!.workers.single { it.unitId == worker.unitId }.working)
                return sample
            }
            assertIs<ActionResult.Accepted>(context.submit(BuildAction("build", "p1", worker.unitId, "airFactory",
                site, nonce, context.world)))
            assertEquals(worker.position, awaitWork().find(worker.unitId).position)
            move(context, "a-mover", Position(224.0, 150.0))
            val constructing = advance()
            assertEquals(worker.position, constructing.find(worker.unitId).position)
            assertTrue(Event("movement-blocked", "a-mover") in constructing.events)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("hold", "p1", "a-mover", context.world)))
            assertIs<ActionResult.Accepted>(context.submit(StopAction("stop-build", "p1", worker.unitId, context.world)))
            assertIs<ActionResult.Accepted>(context.submit(
                ResumeConstructionAction("resume", "p1", worker.unitId, siteId, context.world)))
            assertEquals(worker.position, awaitWork().find(worker.unitId).position)
            assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("push-after-resume", "p1", "a-mover", Position(224.0, 150.0), context.world)))
            val resumed = advance()
            assertEquals(worker.position, resumed.find(worker.unitId).position)
            assertEquals("resume", resumed.find(worker.unitId).tasks.single().actionId)
            assertTrue(Event("movement-blocked", "a-mover") in resumed.events)
        }
    }

    @Test
    fun deployment_anchors_a_mobile_bunker_before_it_becomes_a_footprint() = WaterTestScene.withMap { map ->
        val position = Position(200.0, 200.0)
        val bunker = UnitSpawn("bunker", "p1", "mechBunker", position, 90.0)
        val base = BunkerScene.scenario(map, listOf(bunker))
        val shape = assertIs<MovementCollisionShape.Circle>(base.unitDefinitions.getValue(bunker.typeId).movementCollision)
        val mover = movable("tank", MovementType.LAND)
        val spawns = listOf(UnitSpawn("a-mover", "p1", mover.id, Position(position.x - shape.radius - 6.0, position.y), 90.0), bunker)
        withContext(base.copy(unitDefinitions = base.unitDefinitions + (mover.id to mover), units = spawns,
            combatContract = CombatContract(12345, -1, mapOf("a-mover" to 8L, "bunker" to 9L), 10L))) { context ->
            assertIs<ActionResult.Accepted>(context.submit(UnitAction("deploy", "p1", "bunker", "deploy", context.world)))
            context.advance(Step(0, 1.0, "sim-tick"))
            move(context, "a-mover", Position(320.0, 200.0))
            val contact = context.advance(Step(1, 1.0, "sim-tick"))
            assertEquals(position, contact.find("bunker").position)
            assertEquals("mechBunker", contact.find("bunker").typeId)
            assertTrue(Event("movement-blocked", "a-mover") in contact.events)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("hold", "p1", "a-mover", context.world)))
            for (tick in 2L..165L) context.advance(Step(tick, 1.0, "sim-tick"))
            val deployed = context.advance(Step(166, 1.0, "sim-tick")).find("bunker")
            assertEquals(position, deployed.position)
            assertEquals("mechBunkerDeployed", deployed.typeId)
        }
    }

    private fun pair(map: MapDefinition, mover: UnitDefinition, blocker: UnitDefinition, start: Position,
                     occupied: Position, heading: Double, occupiedHeading: Double = heading): Scenario =
        WaterTestScene.scenario(map, mover.movement.type, start).copy(
            unitDefinitions = listOf(mover, blocker).associateBy { it.id },
            units = listOf(UnitSpawn("mover", "p1", mover.id, start, heading),
                UnitSpawn("blocker", "p1", blocker.id, occupied, occupiedHeading)),
        )

    private fun movable(id: String, type: MovementType, shape: MovementCollisionShape = MovementCollisionShape.Circle(6.0),
                        speed: Double = .7, mass: Double = 3000.0): UnitDefinition {
        val source = WaterTestScene.unit(type)
        return UnitDefinition(id, source.className, source.radius, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement.copy(moveSpeed = speed, moveAccelerationSpeed = 1.0,
                moveDecelerationSpeed = 1.0), source.retainedUnconsumed, movementCollision = shape, mass = mass)
    }

    private fun move(context: SimulationContext, id: String, target: Position) {
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-$id", "p1", id, target, context.world)))
    }

    private inline fun withContext(scene: Scenario, action: (SimulationContext) -> Unit) {
        val initialized = SimulationContext.initialize(scene)
        val context = assertIs<InitResult.Accepted>(initialized, initialized.toString()).context
        try {
            action(context)
        } finally {
            context.close()
        }
    }

    private fun Observation.find(id: String) = assertNotNull(units.firstOrNull { it.unitId == id })
}
