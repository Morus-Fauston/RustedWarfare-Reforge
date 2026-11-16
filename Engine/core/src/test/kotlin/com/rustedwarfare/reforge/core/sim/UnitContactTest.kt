package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.ContentLoader
import com.rustedwarfare.reforge.core.content.ContentResult
import com.rustedwarfare.reforge.core.content.DurabilityProfile
import com.rustedwarfare.reforge.core.content.LoadedContentPack
import com.rustedwarfare.reforge.core.content.LoadedUnitContent
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.content.withSubmarinePackage
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UnitContactTest {
    @Test
    fun measured_gunboat_and_battleship_push_at_the_bow() = WaterTestScene.withMap { map ->
        val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(
            fixtures.resolve("naval-production/json/production-package"))).value
        val gunboat = gunboat()
        val battleship = pack.units.getValue("heavyBattleship")
        assertEquals(1500.0, gunboat.mass)
        assertEquals(40000.0, battleship.mass)
        for ((mover, occupant) in listOf(gunboat to battleship, battleship to gunboat, gunboat to gunboat)) {
            val moverStart = Position(500.0, 190.0)
            val occupantStart = Position(moverStart.x + foreReach(mover) + foreReach(occupant), moverStart.y)
            val input = WaterTestScene.scenario(map, MovementType.WATER, moverStart).copy(
                unitDefinitions = listOf(mover, occupant).associateBy { it.id },
                units = listOf(UnitSpawn("mover", "p1", mover.id, moverStart, 90.0),
                    UnitSpawn("occupant", "p1", occupant.id, occupantStart, 270.0)),
                combatContract = CombatContract(12345, -1, mapOf("mover" to 8L, "occupant" to 9L), 10L),
            )
            val initialized = SimulationContext.initialize(input)
            assertIs<InitResult.Accepted>(initialized, initialized.toString()).context.useContext { context ->
                context.enableMotionDiagnostics(context.world)
                move(context, "mover", Position(730.0, 190.0))
                val frame = context.advance(Step(0, 1.0, "sim-tick")).units.associateBy { it.unitId }
                val resolved = checkNotNull(context.drainMotionDiagnostics(context.world).single { it.unitId == "mover" }.terrainResolved)
                val requested = resolved.x - moverStart.x
                val travel = frame.getValue("mover").position.x - moverStart.x
                val transferred = frame.getValue("occupant").position.x - occupantStart.x
                val moverMass = checkNotNull(mover.mass)
                val occupantMass = checkNotNull(occupant.mass)
                println("${mover.id} ($moverMass) -> ${occupant.id} ($occupantMass): requested=$requested travel=$travel transferred=$transferred")
                assertTrue(requested > 0.0)
                assertEquals(travel, transferred, 1e-9)
                val expected = if (mover.id == "gunboat" && occupant.id == "heavyBattleship") 0.0
                    else requested * moverMass / (moverMass + occupantMass)
                assertEquals(expected, transferred, 1e-9)
                assertEquals(270.0, frame.getValue("occupant").heading, 1e-9)
            }
        }
    }

    @Test
    fun a_stationary_firing_unit_keeps_the_attack_and_returns_to_range_after_a_push() = WaterTestScene.withMap { map ->
        val gunboat = gunboat()
        val gunboatStart = Position(560.0, 190.0)
        val moverStart = Position(gunboatStart.x - foreReach(gunboat) - 6.0, gunboatStart.y)
        val attackRange = checkNotNull(gunboat.combat).attackRange
        val enemyStart = Position(gunboatStart.x - attackRange + .02, gunboatStart.y)
        val mover = contactUnit("tug", MovementType.WATER, mass = 12000.0)
        val dummy = UnitDefinition("target", mover.className, mover.radius, mover.displayRadius, mover.totalFrames,
            mover.imagePath, mover.imageScale, mover.movement.copy(moveSpeed = 0.0), emptySet(),
            durability = DurabilityProfile(1000.0, null))
        val scene = WaterTestScene.scenario(map, MovementType.WATER, moverStart).copy(
            unitDefinitions = listOf(mover, gunboat, dummy).associateBy { it.id },
            units = listOf(UnitSpawn("a-mover", "p1", mover.id, moverStart, 90.0),
                UnitSpawn("gunboat", "p1", gunboat.id, gunboatStart, 270.0), UnitSpawn("target", "p2", dummy.id, enemyStart, 90.0)),
            combatContract = CombatContract(12345, -1, mapOf("a-mover" to 8L, "gunboat" to 9L, "target" to 10L), 11L),
        )
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
            assertIs<ActionResult.Accepted>(context.submit(AttackAction("attack", "p1", "gunboat", "target", context.world)))
            var tick = 0L
            fun advance() = context.advance(Step(tick++, 1.0, "sim-tick"))
            var fired = false
            while (!fired && tick < 90) {
                val frame = advance()
                assertEquals(gunboatStart, frame.units.single { it.unitId == "gunboat" }.position)
                fired = frame.projectiles.any { it.sourceUnitId == "gunboat" }
            }
            assertTrue(fired)
            move(context, "a-mover", Position(690.0, 190.0))
            var displaced = advance().units.single { it.unitId == "gunboat" }
            while (displaced.position.x - enemyStart.x < attackRange && tick < 100)
                displaced = advance().units.single { it.unitId == "gunboat" }
            assertTrue(displaced.position.x - enemyStart.x >= attackRange)
            assertEquals("attack", displaced.tasks.single().actionId)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("clear-west", "p1", "a-mover", Position(470.0, 160.0), context.world)))
            var returned = displaced
            while (returned.position.x - enemyStart.x >= attackRange && tick < 300)
                returned = advance().units.single { it.unitId == "gunboat" }
            assertTrue(returned.position.x - enemyStart.x < attackRange)
            assertEquals("attack", returned.tasks.single().actionId)
        }
    }

    private val fixtures get() =
        System.getProperty("public.fixtures.dir")?.let(Path::of) ?: Path.of("../../SimulationTest/public/inputs")

    private fun gunboat() = assertIs<ContentResult.Success<LoadedUnitContent>>(ContentLoader.loadAuthorWaterUnitWithSources(
        fixtures.resolve("phase-seven-gunboat/units/gunboat.json"))).value.definition

    private fun foreReach(unit: UnitDefinition) = when (val shape = unit.movementCollision) {
        is MovementCollisionShape.Circle -> shape.radius
        is MovementCollisionShape.Rectangle -> shape.length / 2.0
    }

    @Test
    fun touching_mobile_pairs_share_one_forward_step_by_mass() = WaterTestScene.withMap { map ->
        val start = Position(500.0, 190.0)
        val occupantStart = Position(512.0, 190.0)
        val cases = listOf(
            Triple("light-pushes-heavy", 3000.0, 12000.0),
            Triple("heavy-pushes-light", 12000.0, 3000.0),
            Triple("equal-mass", 3000.0, 3000.0),
            Triple("at-provisional-cutoff", 3000.0, 24000.0),
            Triple("above-provisional-cutoff", 3000.0, 24001.0),
        )
        data class PushSample(val case: String, val requested: Double, val moverTravel: Double, val occupantTravel: Double,
                              val expected: Double, val occupantHeading: Double)
        val measurements = mutableListOf<PushSample>()
        for ((name, moverMass, occupantMass) in cases) {
            val mover = contactUnit("mover-hull", MovementType.WATER, mass = moverMass)
            val occupant = contactUnit("occupant-hull", MovementType.WATER, mass = occupantMass)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, start).copy(
                unitDefinitions = listOf(mover, occupant).associateBy { it.id },
                units = listOf(UnitSpawn("mover", "p1", mover.id, start, 90.0),
                    UnitSpawn("occupant", "p2", occupant.id, occupantStart, 180.0)),
            )
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
                context.enableMotionDiagnostics(context.world)
                move(context, "mover", Position(570.0, 190.0))
                val updated = context.advance(Step(0, 1.0, "sim-tick")).units.associateBy { it.unitId }
                val motion = context.drainMotionDiagnostics(context.world).single { it.unitId == "mover" }
                val requested = checkNotNull(motion.terrainResolved).x - start.x
                val front = updated.getValue("mover")
                val back = updated.getValue("occupant")
                assertTrue(requested > 0.0, name)
                assertEquals(190.0, front.position.y, 1e-9, name)
                assertEquals(190.0, back.position.y, 1e-9, name)
                val expected = if (name == "above-provisional-cutoff") 0.0 else
                    requested * (moverMass / (moverMass + occupantMass))
                measurements += PushSample(name, requested, front.position.x - start.x, back.position.x - occupantStart.x,
                    expected, back.heading)
            }
        }
        measurements.forEach(::println)
        for (sample in measurements) {
            assertEquals(sample.expected, sample.occupantTravel, 1e-9, sample.case)
            assertEquals(sample.expected, sample.moverTravel, 1e-9, sample.case)
            assertEquals(180.0, sample.occupantHeading, 1e-9, sample.case)
        }
    }

    @Test
    fun a_blocked_turn_with_no_translation_still_requests_route_repair() = WaterTestScene.withMap { map ->
        val center = Position(500.0, 190.0)
        val ship = contactUnit("ship", MovementType.WATER, MovementCollisionShape.Rectangle(24.0, 6.0))
        val obstructing = contactUnit("circle", MovementType.WATER, MovementCollisionShape.Circle(2.0))
        val input = WaterTestScene.scenario(map, MovementType.WATER, center).copy(
            unitDefinitions = listOf(ship, obstructing).associateBy { it.id },
            units = listOf(UnitSpawn("ship", "p1", ship.id, center, 0.0),
                UnitSpawn("occupant", "p1", obstructing.id, Position(508.0, 182.0), 0.0)))
        assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context.useContext { context ->
            context.enableMotionDiagnostics(context.world)
            context.enablePathDiagnostics(context.world)
            move(context, "ship", Position(575.0, 190.0))
            for (tick in 0L..170L) context.advance(Step(tick, 1.0, "sim-tick"))
            val blockedTurn = context.drainMotionDiagnostics(context.world).filter { sample ->
                sample.unitId == "ship" && sample.unitContactLimited && sample.candidate == sample.positionBefore &&
                    sample.finalHeading == sample.headingBefore
            }
            assertTrue(blockedTurn.isNotEmpty(), "The sample needs a physically blocked turn with zero attempted translation")
            val repair = context.drainPathDiagnostics(context.world).firstOrNull {
                it.unitId == "ship" && it.actionId == "move-ship" &&
                    it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR
            }
            assertTrue(repair != null, "A blocked turn must trigger repair without waiting for translation")
            assertTrue(checkNotNull(repair.executionStep) > blockedTurn.first().stepIndex)
            println("Turn only blocked at ${blockedTurn.first().stepIndex}; same-command route repair at ${repair.executionStep}")
        }
    }

    @Test
    fun a_rectangular_ship_stops_at_first_contact_with_a_stationary_circle() = WaterTestScene.withMap { map ->
        val circle = WaterTestScene.unit(MovementType.WATER)
        val rectangle = contactUnit("long-ship", MovementType.WATER, MovementCollisionShape.Rectangle(24.0, 8.0))
        val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 190.0))
        val scene = opening.copy(
            unitDefinitions = mapOf(rectangle.id to rectangle, circle.id to circle),
            units = listOf(UnitSpawn("mover", "p1", rectangle.id, Position(500.0, 190.0), 90.0),
                UnitSpawn("stationary", "p2", circle.id, Position(560.0, 190.0), 90.0)),
        )
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val target = Position(650.0, 190.0)
            val move = assertIs<ActionResult.Accepted>(context.submit(
                MoveAction("cross-water", "p1", "mover", target, context.world)))
            assertTrue(move.events.isEmpty())
            var started = false
            var blocked = false
            repeat(320) { tick ->
                val visible = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                started = started || Event("movement-started", "mover") in visible.events
                blocked = blocked || visible.events.any { it.kind == "movement-blocked" && it.unitId == "mover" }
                val center = visible.units.single { it.unitId == "mover" }.position
                assertTrue(center.x <= 542.0 + 1e-9,
                    "The bow penetrated the stationary circle at tick $tick: $center")
            }
            assertTrue(started)
            val units = context.observePlayer(context.world, "p1").units.associateBy { it.unitId }
            assertEquals(542.0, units.getValue("mover").position.x, 1e-9)
            assertEquals(Position(560.0, 190.0), units.getValue("stationary").position)
            assertTrue(blocked)
        } finally {
            context.close()
        }
    }

    @Test
    fun the_turn_stops_before_its_middle_pose_overlaps_a_circle_or_rectangle() = WaterTestScene.withMap { map ->
        val ship = contactUnit("turning-ship", MovementType.WATER, MovementCollisionShape.Rectangle(24.0, 6.0))
        val center = Position(500.0, 190.0)
        val opening = WaterTestScene.scenario(map, MovementType.WATER, center)
        val firstContact = listOf(MovementCollisionShape.Circle(2.0) to 18.772162551913475,
            MovementCollisionShape.Rectangle(4.0, 4.0) to 16.0569331388297)
        for ((shape, heading) in firstContact) {
            val obstruction = contactUnit("obstruction", MovementType.WATER, shape)
            val scene = opening.copy(unitDefinitions = listOf(ship, obstruction).associateBy { it.id },
                units = listOf(UnitSpawn("ship", "p1", ship.id, center, 0.0),
                    UnitSpawn("obstruction", "p2", obstruction.id, Position(508.0, 182.0), 0.0)))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
                move(context, "ship", Position(650.0, 190.0))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                val pose = frame.units.single { it.unitId == "ship" }
                assertEquals(heading, pose.heading, 1e-8, shape.toString())
                assertTrue(frame.events.any { it == Event("movement-blocked", "ship") })
            }
        }
    }

    @Test
    fun turning_out_of_an_existing_overlap_does_not_allow_reentry_in_the_same_step() = WaterTestScene.withMap { map ->
        val ship = contactUnit("turning-ship", MovementType.WATER, MovementCollisionShape.Rectangle(24.0, 6.0), 180.0)
        val center = Position(500.0, 190.0)
        val opening = WaterTestScene.scenario(map, MovementType.WATER, center)
        for (shape in listOf(MovementCollisionShape.Circle(2.0), MovementCollisionShape.Rectangle(4.0, 4.0))) {
            val obstruction = contactUnit("obstruction", MovementType.WATER, shape)
            val scene = opening.copy(unitDefinitions = listOf(ship, obstruction).associateBy { it.id },
                units = listOf(UnitSpawn("ship", "p1", ship.id, center, 0.0),
                    UnitSpawn("obstruction", "p2", obstruction.id, Position(500.0, 182.0), 0.0)))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
                move(context, "ship", Position(500.0, 295.0))
                val frame = context.advance(Step(0, 1.0, "sim-tick"))
                val pose = frame.units.single { it.unitId == "ship" }
                assertTrue(pose.heading in 210.0..250.0, "$shape reentered the original overlap at ${pose.heading}°")
                assertTrue(Event("movement-blocked", "ship") in frame.events)
            }
        }
    }

    @Test
    fun existing_overlap_can_reduce_until_clear_and_two_sided_overlap_stops_motion() = WaterTestScene.withMap { map ->
        val body = WaterTestScene.unit(MovementType.WATER)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(500.0, 190.0))
        val mover = scene.units.single().copy(unitId = "mover", heading = 270.0)
        val east = UnitSpawn("east", "p2", body.id, Position(508.0, 190.0), 90.0)
        val west = UnitSpawn("west", "p2", body.id, Position(492.0, 190.0), 90.0)
        for (trapped in listOf(false, true)) {
            val units = if (trapped) listOf(mover, east, west) else listOf(mover, east)
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene.copy(units = units))).context.useContext { context ->
                move(context, "mover", Position(450.0, 190.0))
                var blocked = false
                var lastX = 500.0
                repeat(160) { tick ->
                    val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                    val position = frame.units.single { it.unitId == "mover" }.position
                    assertTrue(position.x <= lastX)
                    lastX = position.x
                    blocked = blocked || frame.events.any { it == Event("movement-blocked", "mover") }
                }
                val final = context.observe().units.single { it.unitId == "mover" }.position
                if (trapped) assertEquals(mover.position, final) else assertTrue(final.x < 480.0)
                assertEquals(trapped, blocked)
            }
        }
    }

    @Test
    fun four_overlapping_neighbours_leave_no_safe_separation_direction() = WaterTestScene.withMap { map ->
        val origin = Position(500.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, origin)
        val mover = scene.units.single().copy(unitId = "mover")
        val obstacles = (0..270 step 90).mapIndexed { index, heading ->
            val offset = ScreenPlane.displacement(heading.toDouble(), 8.0)
            UnitSpawn("obstacle-$index", "p2", mover.typeId, Position(origin.x + offset.x, origin.y + offset.y), 90.0)
        }
        for (heading in 0..315 step 45) {
            val input = scene.copy(units = listOf(mover.copy(heading = heading.toDouble())) + obstacles)
            assertIs<InitResult.Accepted>(SimulationContext.initialize(input)).context.useContext { context ->
                context.enableMotionDiagnostics(context.world)
                val offset = ScreenPlane.displacement(heading.toDouble(), 70.0)
                move(context, "mover", Position(origin.x + offset.x, origin.y + offset.y))
                repeat(10) { tick ->
                    val frame = context.advance(Step(tick.toLong(), 1.0, "sim-tick"))
                    assertEquals(origin, frame.units.single { it.unitId == "mover" }.position)
                    assertTrue(Event("movement-blocked", "mover") in frame.events)
                }
                val samples = context.drainMotionDiagnostics(context.world).filter { it.unitId == "mover" }
                assertEquals(10, samples.size)
                assertTrue(samples.all { sample ->
                    sample.unitContactLimited && sample.terrainDisposition == MotionTerrainDisposition.DIRECT &&
                        sample.candidate == sample.terrainResolved && sample.finalPosition == origin
                })
            }
        }
    }

    @Test
    fun a_passenger_can_board_a_landed_dropship_at_hull_contact() = WaterTestScene.withMap { map ->
        val scene = DropshipScene.scenario(map, listOf(
            UnitSpawn("air", "p1", "dropship", Position(190.0, 150.0), 90.0),
            UnitSpawn("worker", "p1", "builder", Position(270.0, 150.0), 270.0)))
        DropshipScene.open(scene).useContext { context ->
            assertIs<ActionResult.Accepted>(context.submit(LoadAction("board", "p1", "worker", "air",
                LoadDirection.INTO, context.world)))
            val frames = (0L..250L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val loaded = frames.first { Event("unit-loaded", "air") in it.events }
            val passenger = loaded.units.single { it.unitId == "worker" }
            assertEquals("air", passenger.carriedBy)
            assertEquals(220.0, passenger.position.x, 1e-9)
            assertEquals(Position(190.0, 150.0), loaded.units.single { it.unitId == "air" }.position)
        }
    }

    @Test
    fun a_hovercraft_contacts_land_and_water_occupants_in_the_same_move() = WaterTestScene.withMap { map ->
        val hover = contactUnit("hover", MovementType.HOVER)
        val land = contactUnit("land", MovementType.LAND)
        val water = contactUnit("water", MovementType.WATER)
        val start = Position(350.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.HOVER, start).copy(
            unitDefinitions = listOf(hover, land, water).associateBy { it.id },
            players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            units = listOf(UnitSpawn("hover", "p1", hover.id, start, 90.0),
                UnitSpawn("land", "p2", land.id, Position(386.0, 190.0), 180.0),
                UnitSpawn("water", "p2", water.id, Position(408.0, 190.0), 180.0)))
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
            move(context, "hover", Position(515.0, 190.0))
            repeat(110) { context.advance(Step(it.toLong(), 1.0, "sim-tick")) }
            assertEquals(374.0, context.observe().units.single { it.unitId == "hover" }.position.x, 1e-9)
            move(context, "land", Position(386.0, 310.0), owner = "p2")
            for (tick in 110L..220L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertEquals(396.0, context.observe().units.single { it.unitId == "hover" }.position.x, 1e-9)
            move(context, "water", Position(408.0, 310.0), owner = "p2")
            for (tick in 221L..380L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertTrue(context.observe().units.single { it.unitId == "hover" }.position.x > 460.0)
        }
    }

    @Test
    fun a_submarine_waits_below_the_surface_then_crosses_after_the_blocker_moves() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val surface = contactUnit("surface", MovementType.WATER)
            val center = Position(500.0, 190.0)
            for (blockerId in listOf("first-surface", "surface")) {
                val scene = WaterTestScene.scenario(map, MovementType.WATER, center).copy(
                    unitDefinitions = pack.units + (surface.id to surface),
                    units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", center, 90.0, -8.0),
                        UnitSpawn(blockerId, "p1", surface.id, center, 90.0)),
                    combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, blockerId to 9L), 10L))
                assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
                    assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("surface-request", "p1", "sub", true, context.world)))
                    val waiting = (0L..180L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    assertTrue(waiting.all { f -> f.units.single { it.unitId == "sub" }.height < -1.0 })
                    val held = waiting.last().units.single { it.unitId == "sub" }.height
                    assertEquals(-1.027004599571228, held)
                    assertTrue(waiting.takeLast(20).all { f -> f.units.single { it.unitId == "sub" }.height == held })
                    assertTrue(waiting.any { Event("movement-blocked", "sub") in it.events })
                    assertTrue(waiting.none { Event("submarine-threshold-crossed", "sub") in it.events })
                    move(context, blockerId, Position(650.0, 190.0))
                    val release = (181L..330L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                    val transition = release.single { Event("submarine-threshold-crossed", "sub") in it.events }
                    val clear = release.first { f -> f.units.single { it.unitId == blockerId }.position.x >= 521.0 - 1e-9 }
                    val stepsAfterClearance = if (blockerId < "sub") 0L else 1L
                    assertEquals(clear.stepIndex + stepsAfterClearance, transition.stepIndex)
                    assertEquals(1.0, release.last().units.single { it.unitId == "sub" }.height)
                }
            }
        } }

    @Test
    fun a_submarine_waits_until_a_sea_factory_footprint_is_reclaimed() = WaterTestScene.withMap { map ->
        val opening = SeaFactoryScene.scenario(map)
        val site = Position(450.0, 150.0)
        // The center is outside the footprint's x=480 edge; the 15-unit hull overlaps it.
        val submarine = Position(490.0, 150.0)
        val scene = opening.copy(units = listOf(
            opening.units.single().copy(position = site),
            UnitSpawn("builder", "p1", "builder", Position(390.0, 150.0), 90.0),
            UnitSpawn("sub", "p1", "attackSubmarine", submarine, 90.0, -8.0)),
            combatContract = CombatContract(12345, -1, mapOf("factory" to 8L, "builder" to 9L, "sub" to 10L), 11L),
            maintenanceContract = MaintenanceContract(mapOf("seaFactory" to 1000L, "seaFactoryT2" to 3000L)))
        assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
            assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("surface", "p1", "sub", true, context.world)))
            fun Observation.sub() = units.single { it.unitId == "sub" }
            val crossing = Event("submarine-threshold-crossed", "sub")
            val waiting = (0L..180L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            val held = waiting.last().sub().height
            assertTrue(held < -1.0, "Surface footprint released the submarine at height $held")
            assertTrue(waiting.takeLast(20).all { it.sub().height == held && it.sub().position == submarine })
            assertTrue(waiting.none { crossing in it.events })
            assertTrue(waiting.any { Event("movement-blocked", "sub") in it.events })

            assertIs<ActionResult.Accepted>(context.submit(ReclaimUnitAction("clear", "p1", "builder", "factory", context.world)))
            var clearedAt: Long? = null
            var crossedAt: Long? = null
            for (tick in 181L..5000L) {
                val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                if (Event("building-reclaimed", "factory") in frame.events) clearedAt = tick
                if (crossing in frame.events) {
                    assertTrue(clearedAt != null, "The submarine surfaced before the footprint was released")
                    assertEquals(null, crossedAt)
                    crossedAt = tick
                }
                if (frame.sub().height == 1.0) break
            }
            assertTrue(crossedAt != null, "The original surface request did not resume after reclaim")
            assertEquals(1.0, context.observe().sub().height)
            println("sea-factory clearance: held=$held reclaimedStep=$clearedAt crossedStep=$crossedAt")
        }
    }

    @Test
    fun submerged_motion_passes_below_a_surface_body_and_pushes_only_the_submerged_occupant() =
        WaterTestScene.withMap { map -> withSubmarinePackage { root ->
            val pack = assertIs<ContentResult.Success<LoadedContentPack>>(ContentLoader.loadContentPack(root)).value
            val surface = contactUnit("surface", MovementType.WATER)
            val center = Position(500.0, 190.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, center).copy(
                unitDefinitions = pack.units + (surface.id to surface),
                players = listOf(Player("p1", PlayerControl.LOCAL, "allies"), Player("p2", PlayerControl.LOCAL, "allies")),
                units = listOf(UnitSpawn("sub", "p1", "attackSubmarine", center, 90.0, -8.0),
                    UnitSpawn("surface", "p1", surface.id, Position(520.0, 190.0), 90.0),
                    UnitSpawn("blocker", "p2", "attackSubmarine", Position(580.0, 190.0), 90.0, -8.0)),
                combatContract = CombatContract(12345, -1, mapOf("sub" to 8L, "surface" to 9L, "blocker" to 10L), 11L))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context.useContext { context ->
                move(context, "sub", Position(650.0, 190.0))
                val approaching = (0L..270L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                assertTrue(approaching.any { Event("movement-blocked", "sub") in it.events })
                val pastSurface = approaching.last().units.single { it.unitId == "sub" }
                val pushed = approaching.last().units.single { it.unitId == "blocker" }
                assertTrue(pastSurface.position.x > 550.0)
                val gap = pushed.position.x - pastSurface.position.x
                assertTrue(gap in (30.0 - 1e-8)..34.0)
                assertTrue(approaching.all { frame -> frame.units.single { it.unitId == "surface" }.position == Position(520.0, 190.0) })
                assertEquals(-8.0, pastSurface.height)
                assertIs<ActionResult.Accepted>(context.submit(SubmarineDepthAction("raise-blocker", "p2", "blocker", true, context.world)))
                val clear = (271L..900L).map { context.advance(Step(it, 1.0, "sim-tick")) }
                val surfaced = clear.single { Event("submarine-threshold-crossed", "blocker") in it.events }
                val onward = clear.firstOrNull { it.stepIndex >= surfaced.stepIndex &&
                    it.units.single { unit -> unit.unitId == "sub" }.position.x > 610.0 }
                assertTrue(onward != null, "Original submerged move did not continue after the blocker's layer changed")
                println("Layer clearance at ${surfaced.stepIndex}, continued past 610 at ${onward.stepIndex}")
            }
        } }

    @Test
    fun an_aircraft_waits_to_land_above_an_occupant_and_airborne_bodies_pass_through_one_another() {
        val source = HelicopterTestScene.unit()
        val ground = contactUnit("land", MovementType.LAND)
        fun aircraft(landing: Boolean): UnitDefinition {
            val profile = checkNotNull(source.movement.flight).copy(startingHeightOffset = 12.0, landWhenStationary = landing)
            return UnitDefinition(source.id, source.className, source.radius, source.displayRadius, source.totalFrames,
                source.imagePath, source.imageScale, source.movement.copy(flight = profile), source.retainedUnconsumed,
                source.fogOfWarSightRange, rotor = source.rotor)
        }
        val opening = HelicopterTestScene.scenario(aircraft(landing = true))
        val blocker = UnitSpawn("land", "p1", ground.id, opening.units.single().position, 90.0)
        val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (ground.id to ground), units = opening.units + blocker)
        HelicopterTestScene.open(scene).useContext { context ->
            val waiting = (0L..130L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(waiting.all { f -> f.units.single { it.unitId == "h1" }.height >= 4.0 })
            assertTrue(waiting.takeLast(20).map { f -> f.units.single { it.unitId == "h1" }.height }.distinct().size == 1)
            assertTrue(waiting.any { Event("movement-blocked", "h1") in it.events })
            move(context, "land", Position(350.0, 200.0))
            for (tick in 131L..270L) context.advance(Step(tick, 1.0, "sim-tick"))
            assertEquals(2.0, context.observe().units.single { it.unitId == "h1" }.height)
        }
        val cruise = HelicopterTestScene.scenario(aircraft(landing = false))
        val other = UnitSpawn("air", "p2", source.id, Position(250.0, 200.0), 90.0)
        HelicopterTestScene.open(cruise.copy(units = cruise.units + other)).useContext { context ->
            move(context, "h1", Position(340.0, 200.0))
            val frames = (0L..110L).map { context.advance(Step(it, 1.0, "sim-tick")) }
            assertTrue(frames.any { f ->
                val pose = f.units.single { it.unitId == "h1" }
                kotlin.math.abs(pose.position.x - other.position.x) < 1.0 && pose.height >= 4.0
            })
            assertTrue(frames.none { Event("movement-blocked", "h1") in it.events })
            assertTrue(frames.last().units.single { it.unitId == "h1" }.position.x > 310.0)
        }
    }

    @Test
    fun a_foundation_placed_earlier_in_the_same_step_blocks_landing() {
        val source = HelicopterTestScene.unit()
        val profile = checkNotNull(source.movement.flight).copy(targetHeight = 4.05, heightDrift = 0.0,
            startingHeightOffset = 4.05, landWhenStationary = true)
        val aircraft = UnitDefinition("landing-helicopter", source.className, source.radius, source.displayRadius,
            source.totalFrames, source.imagePath, source.imageScale, source.movement.copy(flight = profile),
            source.retainedUnconsumed, source.fogOfWarSightRange, rotor = source.rotor)
        val opening = HelicopterFactoryScene.scenario()
        val site = Position(270.0, 150.0)
        for (workerId in listOf("a-worker", "z-worker")) {
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + (aircraft.id to aircraft),
                units = listOf(opening.units.first().copy(unitId = workerId),
                    UnitSpawn("h1", "p1", aircraft.id, site, 90.0)),
                combatContract = CombatContract(12345, -1, mapOf(workerId to 8L, "h1" to 9L), 10L))
            HelicopterTestScene.open(scene).useContext { context ->
                assertIs<ActionResult.Accepted>(context.submit(BuildAction("foundation", "p1", workerId, "airFactory",
                    site, "a".repeat(32), context.world, atStep = 3)))
                for (tick in 0L..2L) context.advance(Step(tick, 1.0, "sim-tick"))
                assertEquals(4.05, context.observe().units.single { it.unitId == "h1" }.height)
                val step = context.advance(Step(3, 1.0, "sim-tick"))
                assertEquals(site, step.units.single { it.typeId == "airFactory" }.position)
                val blocked = workerId < "h1"
                val aircraftHeight = step.units.single { it.unitId == "h1" }.height
                if (blocked) assertEquals(4.05, aircraftHeight) else assertTrue(aircraftHeight < 4.0)
                assertEquals(blocked, Event("movement-blocked", "h1") in step.events)
            }
        }
    }

    @Test
    fun a_hidden_body_changes_neither_the_route_nor_receipts_before_contact() = WaterTestScene.withMap { map ->
        for (pushable in listOf(false, true)) {
            val center = Position(500.0, 190.0)
            val mover = contactUnit("mover", MovementType.WATER, mass = 3000.0)
            val blocker = contactUnit("hidden", MovementType.WATER, MovementCollisionShape.Rectangle(120.0, 8.0), mass = if (pushable) 3000.0 else null)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, center).copy(
                unitDefinitions = listOf(mover, blocker).associateBy { it.id },
                units = listOf(UnitSpawn("mover", "p1", mover.id, center, 90.0)),
                automaticVision = KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val occupied = opening.copy(units = opening.units +
                UnitSpawn("hidden", "p2", blocker.id, Position(620.0, 190.0), 90.0))
            assertIs<InitResult.Accepted>(SimulationContext.initialize(opening)).context.useContext { clear ->
                assertIs<InitResult.Accepted>(SimulationContext.initialize(occupied)).context.useContext { hidden ->
                    val target = Position(710.0, 190.0)
                    hidden.enablePathDiagnostics(hidden.world)
                    assertEquals(move(clear, "mover", target).events, move(hidden, "mover", target).events)
                    var contact: Long? = null
                    for (tick in 0L..160L) {
                        clear.advance(Step(tick, 1.0, "sim-tick"))
                        hidden.advance(Step(tick, 1.0, "sim-tick"))
                        val unoccupiedView = clear.observePlayer(clear.world, "p1")
                        val occupiedView = hidden.observePlayer(hidden.world, "p1")
                        val pose = occupiedView.units.single()
                        assertEquals("mover", pose.unitId)
                        assertEquals(unoccupiedView.units.single().pathPoints, pose.pathPoints)
                        if (Event("movement-blocked", "mover") in occupiedView.events) {
                            assertEquals(listOf(Event("movement-blocked", "mover")), occupiedView.events)
                            val pushed = hidden.observe().units.single { it.unitId == "hidden" }
                            if (pushable) assertTrue(pushed.position.x > 620.0) else assertEquals(620.0, pushed.position.x)
                            assertEquals(66.0, pushed.position.x - pose.position.x, 1e-9)
                            contact = tick
                            break
                        }
                        assertEquals(unoccupiedView.units, occupiedView.units)
                        assertEquals(unoccupiedView.events, occupiedView.events)
                    }
                    val blockedAt = checkNotNull(contact)
                    for (tick in (blockedAt + 1)..(blockedAt + 130)) {
                        hidden.advance(Step(tick, 1.0, "sim-tick"))
                        val view = hidden.observePlayer(hidden.world, "p1")
                        assertEquals(listOf(target), view.units.single().pathPoints)
                        assertEquals(listOf(Event("movement-blocked", "mover")), view.events)
                    }
                    val retries = hidden.drainPathDiagnostics(hidden.world).filter {
                        it.reason == com.rustedwarfare.reforge.core.path.PathSearchReason.ROUTE_REPAIR
                    }
                    assertEquals(pushable, retries.isEmpty())
                    assertTrue(retries.all { it.pathPointCount == 1 && it.usedPlayerKnowledge })
                    println("Hidden blocker contacted at $blockedAt, retries=${retries.map { it.executionStep }}")
                }
            }
        }
    }

    private fun contactUnit(id: String, type: MovementType, shape: MovementCollisionShape = MovementCollisionShape.Circle(6.0),
                            turnSpeed: Double = 90.0, mass: Double? = null): UnitDefinition {
        val source = WaterTestScene.unit(type)
        return UnitDefinition(id, source.className, source.radius, source.displayRadius, source.totalFrames,
            source.imagePath, source.imageScale, source.movement.copy(maxTurnSpeed = turnSpeed, turnAcceleration = 0.0),
            emptySet(), fogOfWarSightRange = 1, movementCollision = shape, mass = mass)
    }

    private fun move(context: SimulationContext, id: String, destination: Position, owner: String = "p1"): ActionResult.Accepted =
        assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-$id", owner, id, destination, context.world)))

    private inline fun SimulationContext.useContext(action: (SimulationContext) -> Unit) {
        try {
            action(this)
        } finally {
            close()
        }
    }
}
