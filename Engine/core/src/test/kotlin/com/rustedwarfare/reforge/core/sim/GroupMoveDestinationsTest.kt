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
    fun a_narrow_shore_expands_beyond_the_old_radius_for_both_reachable_members() = WaterTestScene.withMap { water ->
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
        try {
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            val frame = awaitPlanning(context, "a", "z")
            val first = assertNotNull(frame.ship("a").moveTarget)
            val second = assertNotNull(frame.ship("z").moveTarget)
            assertTrue(listOf(first, second).any { ScreenPlane.distanceSquared(it, click) > 60.0 * 60.0 })
            assertTrue(ScreenPlane.distanceSquared(first, second) >= 16.0 * 16.0)
            assertTrue(frame.events.none { it.kind == "group-destination-unavailable" })
            assertTrue(Event("movement-started", "z") in frame.events)
        } finally {
            context.close()
        }
    }

    @Test
    fun temporary_occupation_keeps_the_original_group_destination() {
        occupiedDestination(holderSpeed = 0.22, reassign = false)
    }

    @Test
    fun sustained_occupation_reassigns_only_the_unit_whose_destination_is_invalid() {
        occupiedDestination(holderSpeed = 0.001, reassign = true)
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
        try {
            for ((id, target) in listOf("b-passer" to Position(600.0, 380.0), "c-holder" to Position(730.0, 200.0)))
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("initial-$id", "p1", id, target, context.world)))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            val initial = awaitPlanning(context, "a", "z")
            assertEquals(click, initial.ship("a").moveTarget)
            assertIs<ActionResult.Accepted>(context.submit(StopAction("passer-waits", "p1", "b-passer", context.world)))
            var simultaneous = false
            var closestPassingDistanceSquared = Double.POSITIVE_INFINITY
            var closestPassingPose: Position? = null
            var reassigned = false
            context.enablePlanningWorkDiagnostics(context.world)
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
                reassigned = reassigned || Event("group-destination-reassigned", "a") in frame.events
            }
            assertTrue(simultaneous, "The transient holder must actually share the occupied slot buffer: closest=$closestPassingDistanceSquared at $closestPassingPose")
            assertTrue(reassigned, "A transient earlier holder must not restart the persistent holder's 420-step wait")
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
            try {
                for ((id, target) in listOf("first" to Position(730.0, 200.0), "second" to Position(600.0, 380.0)))
                    assertIs<ActionResult.Accepted>(context.submit(MoveAction("initial-$id", "p1", id, target, context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = awaitPlanning(context, "a", "z")
                assertEquals(click, initial.ship("a").moveTarget)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("first-waits", "p1", "first", context.world)))
                assertIs<ActionResult.Accepted>(context.submit(StopAction("second-waits", "p1", "second", context.world)))
                var changes = 0
                val consecutive = mutableMapOf<String, Int>()
                var longestOccupation = 0
                context.enablePlanningWorkDiagnostics(context.world)
                for (offset in 1L..650L) {
                    if (offset == 250L) {
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("first-leaves", "p1", "first", Position(730.0, 200.0), context.world)))
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("second-enters", "p1", "second", click, context.world)))
                    }
                    if (offset == 500L) assertIs<ActionResult.Accepted>(context.submit(
                        MoveAction("second-leaves", "p1", "second", Position(600.0, 380.0), context.world)))
                    val frame = context.advance(Step(initial.stepIndex + offset, 1.0, "sim-tick"))
                    assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                    changes += frame.events.count { it.kind == "group-destination-reassigned" && it.unitId == "a" }
                    for (id in listOf("first", "second")) {
                        val duration = if (ScreenPlane.distanceSquared(frame.ship(id).position, click) < 256.0)
                            (consecutive[id] ?: 0) + 1 else 0
                        consecutive[id] = duration
                        longestOccupation = maxOf(longestOccupation, duration)
                    }
                    assertTrue(frame.events.none { it.unitId == "a" && it.kind == "movement-cancelled" })
                }
                assertTrue(longestOccupation < 420, "The fixture must only contain short-lived individual holders: $longestOccupation")
                assertEquals(0, changes, "Neither passing body occupies this member's slot for 420 consecutive steps")
                assertEquals(click, context.observe().ship("a").moveTarget)
            } finally { context.close() }
        }

    @Test
    fun a_confirmed_stationary_holder_invalidates_the_slot_before_the_slow_member_reaches_it() =
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
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("holder-leaving", "p2", "holding",
                    Position(730.0, 200.0), context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = awaitPlanning(context, "a", "z")
                assertEquals(click, initial.ship("a").moveTarget)
                assertIs<ActionResult.Accepted>(context.submit(StopAction("holder-stopped", "p2", "holding", context.world)))
                context.enablePlanningWorkDiagnostics(context.world)
                var replacement: Observation? = null
                for (tick in initial.stepIndex + 1..initial.stepIndex + 1800) {
                    val frame = context.advance(Step(tick, 1.0, "sim-tick"))
                    assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
                    assertTrue(frame.events.none { it.unitId in setOf("a", "z") &&
                        (it.kind == "movement-cancelled" || it.kind == "group-destination-unavailable") })
                    if (Event("group-destination-reassigned", "a") in frame.events) {
                        replacement = frame
                        break
                    }
                }
                val changed = assertNotNull(replacement, "A stationary known holder must not wait for the slow member to approach")
                assertTrue(ScreenPlane.distanceSquared(changed.ship("a").position, click) > 100.0 * 100.0)
                assertTrue(changed.ship("a").moveTarget != click)
                assertEquals(0.0, changed.ship("holding").speed)
                assertTrue(changed.stepIndex - initial.stepIndex >= 420)
            } finally { context.close() }
        }

    @Test
    fun a_visible_long_hull_can_confirm_occupation_before_the_mover_gets_within_a_tile() =
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
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("slow-long", "p2", "holding",
                    Position(730.0, 200.0), context.world)))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = awaitPlanning(context, "a", "z")
                assertEquals(click, initial.ship("a").moveTarget)
                val frames = listOf(initial) + (1L..900L).map { context.advance(Step(initial.stepIndex + it, 1.0, "sim-tick")) }
                val changedIndex = frames.indexOfFirst { Event("group-destination-reassigned", "a") in it.events }
                assertTrue(changedIndex > 0)
                val changed = frames[changedIndex]
                val before = frames[changedIndex - 1].ship("a").position
                val distance = kotlin.math.sqrt(ScreenPlane.distanceSquared(before, click))
                println("Long hull blocked the original goal from ${distance} units away; replacement at ${changed.stepIndex}")
                assertTrue(distance > 41.0)
                assertTrue(changed.ship("a").moveTarget != null && changed.ship("a").moveTarget != click)
                assertTrue(changed.stepIndex - initial.stepIndex + 1 >= 420)
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
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("z", "a"), click))
            submitSelection(context, group, "a", "z")
            val initial = awaitPlanning(context, "a", "z")
            assertEquals(click, initial.ship("a").moveTarget)
            val otherTarget = assertNotNull(initial.ship("z").moveTarget)
            val shore = listOf(VisibleTerrainCell(30, 10, true, 1))
            context.acceptVisibleTerrain(context.world, "p2", shore)
            assertEquals(click, context.advance(Step(initial.stepIndex + 1, 1.0, "sim-tick")).ship("a").moveTarget)
            context.acceptVisibleTerrain(context.world, "p1", shore)
            val frame = awaitReplacement(context, initial.stepIndex + 2, "a", "z" to otherTarget)
            val replacement = assertNotNull(frame.ship("a").moveTarget)
            assertTrue(Event("group-destination-reassigned", "a") in frame.events)
            assertTrue(Event("group-destination-reassigned", "z") !in frame.events)
            assertTrue(replacement != click)
            println("Authorized static shore changed $click to $replacement; other remained $otherTarget")
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
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
                val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
                submitSelection(context, group, "a", "z")
                val initial = awaitPlanning(context, "a", "z")
                assertEquals(click, initial.ship("a").moveTarget)
                val otherTarget = assertNotNull(initial.ship("z").moveTarget)
                context.acceptVisibleTerrain(context.world, "p1", wall)
                val blocked = awaitReplacement(context, initial.stepIndex + 1, "a", "z" to otherTarget)
                println("Disconnected original target $click, new target=${blocked.ship("a").moveTarget}, events=${blocked.events}")
                assertTrue(Event("group-destination-reassigned", "a") in blocked.events)
                assertTrue(assertNotNull(blocked.ship("a").moveTarget).x <= 554.0)
            } finally {
                context.close()
            }
        }

    private fun occupiedDestination(holderSpeed: Double, reassign: Boolean) = WaterTestScene.withMap { map ->
        val click = Position(600.0, 200.0)
        val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(560.0, 200.0))
        val holder = variant(WaterTestScene.unit(MovementType.WATER), "holder", holderSpeed)
        val scene = opening.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
            unitDefinitions = opening.unitDefinitions + (holder.id to holder),
            units = listOf(UnitSpawn("a", "p1", "water-test", Position(640.0, 200.0), 270.0),
                UnitSpawn("z", "p1", "water-test", Position(590.0, 130.0), 180.0),
                UnitSpawn("holding", "p2", "holder", click, 180.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enablePlanningWorkDiagnostics(context.world)
            assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-away", "p2", "holding",
                Position(600.0, 340.0), context.world)))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("z", "a"), click))
            submitSelection(context, group, "a", "z")
            val initial = awaitPlanning(context, "a", "z")
            assertEquals(click, initial.ship("a").moveTarget)
            val unaffectedTarget = assertNotNull(initial.ship("z").moveTarget)
            assertTrue(unaffectedTarget != click)
            val frames = (1L..1251L).map { context.advance(Step(initial.stepIndex + it, 1.0, "sim-tick")) }
            val changed = frames.firstOrNull { Event("group-destination-reassigned", "a") in it.events }
            val arrived = frames.firstOrNull { Event("movement-arrived", "a") in it.events }
            val other = frames.firstOrNull { Event("movement-arrived", "z") in it.events }
            println("Holder speed=$holderSpeed reassigned=${changed?.stepIndex} target=${changed?.ship("a")?.moveTarget} arrived=${arrived?.stepIndex} other=${other?.stepIndex}")
            val work = context.drainPlanningWorkDiagnostics(context.world)
            assertTrue(work.all { it.used <= it.limit })
            println("Replacement work: ${work.filter { it.slices.any { s -> s.restarted || s.completed } }}")
            assertTrue(frames.takeWhile { it !== other }.all { it.ship("z").moveTarget == unaffectedTarget })
            assertNotNull(other, "Last occupied frame=${frames.last().units}")
            if (reassign) {
                assertNotNull(changed)
                assertTrue(changed.stepIndex - initial.stepIndex + 1 >= 420)
                assertTrue(changed.ship("a").moveTarget != null && changed.ship("a").moveTarget != click)
                assertTrue(frames.takeWhile { it !== changed }.all { it.ship("a").moveTarget == click })
                assertTrue(frames.none { Event("group-destination-reassigned", "z") in it.events })
            } else {
                assertEquals(null, changed)
                assertTrue(frames.takeWhile { it !== arrived }.all { it.ship("a").moveTarget == click })
            }
            assertNotNull(arrived, "Last occupied frame=${frames.last().units}")
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
                try {
                    val group = assertNotNull(context.requestGroupMove("p1", listOf("a-hover", "z-land"), click))
                    submitSelection(context, group, "a-hover", "z-land")
                    val frame = awaitPlanning(context, "a-hover", "z-land")
                    if (click == shore) {
                        assertEquals(shore, frame.ship("z-land").moveTarget)
                        assertTrue(frame.ship("a-hover").moveTarget != null && frame.ship("a-hover").moveTarget != shore)
                    } else {
                        assertEquals(offshore, frame.ship("a-hover").moveTarget)
                        assertTrue(assertNotNull(frame.ship("z-land").moveTarget).x < 400.0)
                        assertTrue(frame.events.none { it.kind == "group-destination-unavailable" || it.kind == "path-unreachable" })
                    }
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

    private fun awaitPlanning(context: SimulationContext, vararg members: String): Observation {
        for (step in 0L..12000L) {
            val frame = context.advance(Step(step, 1.0, "sim-tick"))
            if (members.all { id -> frame.events.any { it.unitId == id &&
                    it.kind in setOf("movement-started", "group-destination-unavailable") } }) return frame
        }
        error("Group movement plan could not finish")
    }

    private fun awaitReplacement(context: SimulationContext, firstStep: Long, id: String,
                                 preserved: Pair<String, Position>): Observation {
        context.enablePlanningWorkDiagnostics(context.world)
        for (step in firstStep until firstStep + 12000) {
            val frame = context.advance(Step(step, 1.0, "sim-tick"))
            assertTrue(context.drainPlanningWorkDiagnostics(context.world).all { it.used <= it.limit })
            val other = frame.ship(preserved.first)
            assertTrue(Event("group-destination-reassigned", preserved.first) !in frame.events)
            assertTrue(other.moveTarget == preserved.second || other.moveTarget == null &&
                ScreenPlane.distanceSquared(other.position, preserved.second) <= 49.0,
                "Unaffected member must keep its original slot until arriving within the existing seven-unit radius: $other")
            if (Event("group-destination-reassigned", id) in frame.events) return frame
            assertTrue(Event("group-destination-unavailable", id) !in frame.events)
        }
        error("Replacement did not finish")
    }

    private fun variant(template: UnitDefinition, id: String, speed: Double = template.movement.moveSpeed,
                        shape: MovementCollisionShape = template.movementCollision) =
        UnitDefinition(id, template.className, template.radius, template.displayRadius, template.totalFrames,
            template.imagePath, template.imageScale, template.movement.copy(moveSpeed = speed), emptySet(), movementCollision = shape)

    private fun Observation.ship(id: String) = units.single { it.unitId == id }
}
