package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.content.MovementCollisionShape
import com.rustedwarfare.reforge.core.content.MovementType
import com.rustedwarfare.reforge.core.content.UnitDefinition
import com.rustedwarfare.reforge.core.knowledge.KnowledgeRules
import com.rustedwarfare.reforge.core.knowledge.PathInformationSource
import com.rustedwarfare.reforge.core.knowledge.TerrainRetention
import com.rustedwarfare.reforge.core.knowledge.VisibleTerrainCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroupMoveDestinationsTest {
    @Test
    fun both_members_park_at_distinct_reachable_positions_on_a_narrow_shore() = WaterTestScene.withMap { water ->
        val banks = (20 until water.widthTiles).flatMap { column ->
            (0 until water.heightTiles).filter { it != 9 }.map { column to it }
        }.toSet()
        val map = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
            water.tileSets, water.groundGids, water.blocking, banks)
        val click = Position(610.0, 249.0)
        val lastSlot = Position(610.0, 189.0)
        val approach = Position(540.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, approach).copy(units = listOf(
            UnitSpawn("a", "p1", "water-test", approach, 90.0),
            UnitSpawn("z", "p1", "water-test", lastSlot, 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        context.enablePlanningWorkDiagnostics(context.world)
        try {
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            val frame = awaitArrival(context, scene, click, listOf("a", "z"), 0)
            assertTrue(frame.units.all { it.position.y in 186.0..194.0 })
            assertTrue(frame.units.all { kotlin.math.abs(it.position.x - click.x) <= 40.0 })
        } finally {
            context.close()
        }
    }

    @Test
    fun moving_holder_releases_space_for_the_original_order() {
        occupiedDestination(holderSpeed = 0.22)
    }

    @Test
    fun sustained_occupation_allows_both_members_to_finish_the_original_order() {
        occupiedDestination(holderSpeed = 0.001)
    }

    @Test
    fun a_brief_second_holder_does_not_reset_the_continuous_first_holder() = WaterTestScene.withMap { map ->
        val click = Position(600.0, 200.0)
        val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(575.0, 200.0))
        val source = opening.unitDefinitions.getValue("water-test")
        val member = variant(source, "slow-member", 0.01, MovementCollisionShape.Circle(12.0))
        val holder = variant(source, "holder", 0.001, MovementCollisionShape.Circle(6.0))
        val passer = UnitDefinition("passer", source.className, 2.0, 2.0, source.totalFrames,
            source.imagePath, source.imageScale, source.movement.copy(moveSpeed = 0.7,
                maxTurnSpeed = 180.0, turnAcceleration = 180.0), emptySet(),
            movementCollision = MovementCollisionShape.Circle(2.0))
        val scene = opening.copy(unitDefinitions = opening.unitDefinitions +
            listOf(member, holder, passer).associateBy { it.id }, units = listOf(
            UnitSpawn("a", "p1", member.id, Position(570.0, 200.0), 90.0),
            UnitSpawn("z", "p1", source.id, Position(750.0, 300.0), 270.0),
            UnitSpawn("b-passer", "p1", passer.id, Position(600.0, 225.0), 180.0),
            UnitSpawn("c-holder", "p1", holder.id, click, 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        context.enablePlanningWorkDiagnostics(context.world)
        try {
            for ((id, target) in listOf("b-passer" to Position(600.0, 380.0), "c-holder" to Position(730.0, 200.0)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("initial-$id", "p1", id, target, context.world)))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            val initial = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals("click-a", initial.ship("a").tasks.first().actionId)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("passer-waits", "p1", "b-passer", context.world)))
            var simultaneous = false
            var closestPassingDistanceSquared = Double.POSITIVE_INFINITY
            var closestPassingPose: Position? = null
            var last = initial

            for (offset in 1L..650L) {
                if (offset == 300L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("passer-enters", "p1", "b-passer", Position(600.0, 210.0), context.world)))
                if (offset == 400L) assertIs<ActionResult.Accepted>(context.submit(
                    MoveAction("passer-leaves", "p1", "b-passer", Position(600.0, 380.0), context.world)))
                val frame = context.advance(Step(initial.stepIndex + offset, 1.0, "sim-tick"))
                assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                val held = frame.ship("c-holder").position
                val passing = frame.ship("b-passer").position
                val passingDistanceSquared = ScreenPlane.distanceSquared(passing, click)
                if (passingDistanceSquared < closestPassingDistanceSquared) {
                    closestPassingDistanceSquared = passingDistanceSquared
                    closestPassingPose = passing
                }
                assertTrue(ScreenPlane.distanceSquared(held, click) < 256.0, "The first holder must remain continuously in the slot buffer")
                assertTrue(ScreenPlane.distanceSquared(held, passing) >= 64.0 - 1e-7)
                simultaneous = simultaneous || ScreenPlane.distanceSquared(passing, click) < 324.0
                checkHulls(scene, frame)
                last = frame
            }
            assertTrue(simultaneous, "The transient holder must actually share the occupied slot buffer: closest=$closestPassingDistanceSquared at $closestPassingPose")
            awaitArrival(context, scene, click, listOf("a", "z"), last.stepIndex + 1)
        } finally { context.close() }
    }

    @Test
    fun successive_short_lived_holders_do_not_turn_passing_traffic_into_one_persistent_blocker() =
        WaterTestScene.withMap { map ->
            val click = Position(600.0, 200.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(575.0, 200.0))
            val source = opening.unitDefinitions.getValue("water-test")
            val slow = variant(source, "slow-member", 0.01, MovementCollisionShape.Circle(6.0))
            val traffic = variant(source, "traffic", 0.7, MovementCollisionShape.Circle(6.0))
            val scene = opening.copy(unitDefinitions = opening.unitDefinitions + mapOf(slow.id to slow, traffic.id to traffic),
                units = listOf(UnitSpawn("a", "p1", slow.id, Position(575.0, 200.0), 90.0),
                    UnitSpawn("z", "p1", source.id, Position(750.0, 300.0), 270.0),
                    UnitSpawn("first", "p1", traffic.id, click, 90.0),
                    UnitSpawn("second", "p1", traffic.id, Position(600.0, 216.0), 0.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            context.enablePlanningWorkDiagnostics(context.world)
            try {
                for ((id, target) in listOf("first" to Position(730.0, 200.0), "second" to Position(600.0, 380.0)))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("initial-$id", "p1", id, target, context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals("click-a", initial.ship("a").tasks.first().actionId)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("first-waits", "p1", "first", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(StopAction("second-waits", "p1", "second", context.world)))
                var last = initial
                val consecutive = mutableMapOf<String, Int>()
                var longestOccupation = 0

                for (offset in 1L..650L) {
                    if (offset == 250L) {
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("first-leaves", "p1", "first", Position(730.0, 200.0), context.world)))
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("second-enters", "p1", "second", click, context.world)))
                    }
                    if (offset == 500L) assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("second-leaves", "p1", "second", Position(600.0, 380.0), context.world)))
                    val frame = context.advance(Step(initial.stepIndex + offset, 1.0, "sim-tick"))
                    assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                    checkHulls(scene, frame)
                    last = frame
                    for (id in listOf("first", "second")) {
                        val duration = if (ScreenPlane.distanceSquared(frame.ship(id).position, click) < 256.0)
                            (consecutive[id] ?: 0) + 1 else 0
                        consecutive[id] = duration
                        longestOccupation = maxOf(longestOccupation, duration)
                    }
                    assertTrue(frame.events.none { it.unitId == "a" && it.kind == "movement-cancelled" })
                }
                assertTrue(longestOccupation < 420, "The fixture must only contain short-lived individual holders: $longestOccupation")
                awaitArrival(context, scene, click, listOf("a", "z"), last.stepIndex + 1)
            } finally { context.close() }
        }

    @Test
    fun a_confirmed_stationary_holder_leaves_both_slow_members_a_safe_arrival() =
        WaterTestScene.withMap { map ->
            val click = Position(600.0, 200.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(475.0, 187.0))
            val source = opening.unitDefinitions.getValue("water-test")
            val slow = variant(source, "slow-member", 0.01, MovementCollisionShape.Circle(6.0))
            val holder = variant(source, "slow-holder", 0.001, MovementCollisionShape.Circle(6.0))
            val scene = opening.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                unitDefinitions = opening.unitDefinitions + mapOf(slow.id to slow, holder.id to holder), units = listOf(
                    UnitSpawn("a", "p1", slow.id, Position(475.0, 187.0), 90.0),
                    UnitSpawn("z", "p1", source.id, Position(610.0, 130.0), 180.0),
                    UnitSpawn("holding", "p2", holder.id, click, 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            context.enablePlanningWorkDiagnostics(context.world)
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("holder-leaving", "p2", "holding",
                    Position(730.0, 200.0), context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals("click-a", initial.ship("a").tasks.first().actionId)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("holder-stopped", "p2", "holding", context.world)))

                val last = awaitArrival(context, scene, click, listOf("a", "z"), initial.stepIndex + 1)
                assertEquals(0.0, last.ship("holding").speed)
            } finally { context.close() }
        }

    @Test
    fun a_visible_long_hull_leaves_space_for_each_members_actual_stopped_pose() =
        WaterTestScene.withMap { map ->
            val click = Position(600.0, 200.0)
            // Start within the long hull's approach range and stay outside the ordinary
            // confirmation range throughout the 420-tick observation window.
            val approach = Position(545.0, 183.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, approach)
            val water = opening.unitDefinitions.getValue("water-test")
            val observer = variant(water, "observer", 0.025)
            val holder = variant(water, "long-holder", 0.001, MovementCollisionShape.Rectangle(102.0, 20.0))
            val scene = opening.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                unitDefinitions = opening.unitDefinitions + listOf(observer, holder).associateBy { it.id }, units = listOf(
                    UnitSpawn("a", "p1", observer.id, approach, 90.0),
                    UnitSpawn("z", "p1", water.id, Position(610.0, 130.0), 180.0),
                    UnitSpawn("holding", "p2", holder.id, click, 90.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            context.enablePlanningWorkDiagnostics(context.world)
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("slow-long", "p2", "holding",
                    Position(730.0, 200.0), context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals("click-a", initial.ship("a").tasks.first().actionId)
                awaitArrival(context, scene, click, listOf("a", "z"), initial.stepIndex + 1)
            } finally {
                context.close()
            }
        }

    @Test
    fun only_confirmed_invalid_terrain_changes_the_assigned_destination() = WaterTestScene.withMap { water ->
        val gid = water.groundGids.apply { this[10 * water.widthTiles + 30] = 1 }
        val map = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
            water.tileSets, gid, water.blocking)
        val click = Position(600.1, 218.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(480.0, 218.0)).copy(units = listOf(
            UnitSpawn("a", "p1", "water-test", Position(480.0, 218.0), 90.0),
            UnitSpawn("z", "p1", "water-test", Position(500.0, 150.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        context.enablePlanningWorkDiagnostics(context.world)
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("z", "a"), click))
            submitSelection(context, group, "a", "z")
            val initial = context.advance(Step(0, 1.0, "sim-tick"))
            assertEquals("click-a", initial.ship("a").tasks.first().actionId)
            val shore = listOf(VisibleTerrainCell(30, 10, true, 1))
            context.acceptVisibleTerrain(context.world, "p2", shore)
            assertEquals(click, context.advance(Step(initial.stepIndex + 1, 1.0, "sim-tick")).ship("a").moveTarget)
            context.acceptVisibleTerrain(context.world, "p1", shore)
            val frame = awaitArrival(context, scene, click, listOf("a", "z"), initial.stepIndex + 2)
            for (car in frame.units) {
                val nearest = Position(car.position.x.coerceIn(600.0, 620.0), car.position.y.coerceIn(200.0, 220.0))
                assertTrue(ScreenPlane.distanceSquared(car.position, nearest) >= 36.0 - 1e-6)
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun a_confirmed_unreachable_route_can_find_a_nearby_destination_on_the_approach_side() =
        WaterTestScene.withMap { water ->
            // Keep z's buffer-spaced slot on valid terrain: this fixture changes
            // only a's connectivity, rather than invalidating both destinations.
            val wall = (0 until water.heightTiles).map { VisibleTerrainCell(28, it, true, 1) }
            val gids = water.groundGids.apply { for (cell in wall) this[cell.row * water.widthTiles + cell.column] = 1 }
            val map = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
                water.tileSets, gids, water.blocking)
            val click = Position(625.0, 200.0)
            val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(530.0, 200.0)).copy(units = listOf(
                UnitSpawn("a", "p1", "water-test", Position(530.0, 200.0), 90.0),
                UnitSpawn("z", "p1", "water-test", Position(675.0, 170.0), 270.0)))
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            context.enablePlanningWorkDiagnostics(context.world)
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = context.advance(Step(0, 1.0, "sim-tick"))
                assertEquals("click-a", initial.ship("a").tasks.first().actionId)
                context.acceptVisibleTerrain(context.world, "p1", wall)
                val frame = awaitArrival(context, scene, click, listOf("a", "z"), initial.stepIndex + 1)
                assertTrue(frame.ship("a").position.x <= 554.0 + 1e-6)
                assertTrue(frame.ship("z").position.x >= 586.0 - 1e-6)
            } finally {
                context.close()
            }
        }

    private fun occupiedDestination(holderSpeed: Double) = WaterTestScene.withMap { map ->
        val click = Position(600.0, 200.0)
        val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(560.0, 200.0))
        val holder = variant(WaterTestScene.unit(MovementType.WATER), "holder", holderSpeed)
        val scene = opening.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = opening.unitDefinitions + (holder.id to holder),
            units = listOf(UnitSpawn("a", "p1", "water-test", Position(640.0, 200.0), 270.0),
                UnitSpawn("z", "p1", "water-test", Position(590.0, 130.0), 180.0),
                UnitSpawn("holding", "p2", "holder", click, 180.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        context.enablePlanningWorkDiagnostics(context.world)
        try {

            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-away", "p2", "holding",
                Position(600.0, 340.0), context.world)))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("z", "a"), click))
            submitSelection(context, group, "a", "z")
            awaitArrival(context, scene, click, listOf("a", "z"), 0)
        } finally {
            context.close()
        }
    }

    @Test
    fun scarce_shore_destinations_get_priority_and_farther_members_expand_back_to_reachable_land() =
        WaterTestScene.withMap { map ->
            val shore = Position(390.0, 190.0)
            val offshore = Position(600.0, 190.0)
            val hover = variant(WaterTestScene.unit(MovementType.HOVER), "hover")
            val land = variant(WaterTestScene.unit(MovementType.LAND), "land")
            val opening = WaterTestScene.scenario(map, MovementType.WATER, shore)
            val scene = opening.copy(unitDefinitions = mapOf(hover.id to hover, land.id to land), units = listOf(
                UnitSpawn("a-hover", "p1", hover.id, Position(325.0, 235.0), 90.0),
                UnitSpawn("z-land", "p1", land.id, Position(260.0, 190.0), 90.0)))
            for (click in listOf(shore, offshore)) {
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                context.enablePlanningWorkDiagnostics(context.world)
                try {
                    val group = assertNotNull(context.requestGroupMove("p1", listOf("a-hover", "z-land"), click))
                    submitSelection(context, group, "a-hover", "z-land")
                    val frame = awaitArrival(context, scene, click, listOf("a-hover", "z-land"), 0)
                    val landAt = frame.ship("z-land").position
                    assertTrue(landAt.x <= 394.0 + 1e-6 && landAt.x >= 345.0, "Land stopped at $landAt")
                    if (click == offshore) assertTrue(frame.ship("a-hover").position.x > 550.0)
                } finally {
                    context.close()
                }
            }
        }

    private fun submitSelection(context: SimulationContext, group: GroupMoveRequest, vararg members: String) =
        members.forEach { id ->
            val receipt = assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("click-$id", "p1", id, group.click, context.world), group))
            assertTrue(receipt.events.isEmpty())
        }

    private fun awaitArrival(context: SimulationContext, scene: Scenario, click: Position,
                             ids: List<String>, firstStep: Long): Observation {

        var quiet = 0
        var previous = context.observe()
        for (tick in firstStep until firstStep + 18000) {
            val frame = context.advance(Step(tick, 1.0, "sim-tick"))
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
            checkHulls(scene, frame)
            val settled = ids.all { id ->
                val car = frame.ship(id)
                val prior = previous.ship(id)
                car.moveArrival?.originalTarget == click && car.tasks.isEmpty() && car.speed == 0.0 &&
                    car.position == prior.position && car.heading == prior.heading
            }
            quiet = if (settled) quiet + 1 else 0
            if (quiet == 60) return frame
            previous = frame
        }
        error("Orders did not settle: ${ids.map { previous.ship(it).let { u -> listOf(u.unitId, u.position, u.moveTarget, u.tasks, u.moveArrival) } }}")
    }

    private fun checkHulls(scene: Scenario, frame: Observation) {
        for ((i, a) in frame.units.withIndex()) for (b in frame.units.drop(i + 1)) {
            val first = scene.unitDefinitions.getValue(a.typeId).movementCollision
            val second = scene.unitDefinitions.getValue(b.typeId).movementCollision
            if (first is MovementCollisionShape.Circle && second is MovementCollisionShape.Circle) {
                val rounding = 4 * Math.ulp(maxOf(kotlin.math.abs(a.position.x), kotlin.math.abs(a.position.y),
                    kotlin.math.abs(b.position.x), kotlin.math.abs(b.position.y)))
                assertTrue(kotlin.math.hypot(a.position.x - b.position.x, a.position.y - b.position.y) + rounding >=
                    first.radius + second.radius, "Hard overlap at ${frame.stepIndex} between ${a.unitId} and ${b.unitId}")
                continue
            }
            assertTrue(!UnitContact.posesOverlap(first, a.position, a.heading, second, b.position, b.heading),
                "Hard overlap at ${frame.stepIndex} between ${a.unitId} ${a.position}/$first and ${b.unitId} ${b.position}/$second")
        }
    }

    private fun variant(template: UnitDefinition, id: String, speed: Double = template.movement.moveSpeed,
                        shape: MovementCollisionShape = template.movementCollision) =
        UnitDefinition(id, template.className, template.radius, template.displayRadius, template.totalFrames,
            template.imagePath, template.imageScale, template.movement.copy(moveSpeed = speed), emptySet(), movementCollision = shape)

    private fun Observation.ship(id: String) = units.single { it.unitId == id }
}
