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
    fun when_only_one_position_fits_the_member_already_there_can_keep_it() = WaterTestScene.withMap { water ->
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
            assertEquals(null, frame.ship("a").moveTarget)
            assertTrue(Event("group-destination-unavailable", "a") in frame.events)
            assertTrue(Event("movement-started", "z") in frame.events)
            assertEquals(approach, frame.ship("a").position)
            assertEquals(lastSlot, frame.ship("z").position)
            assertTrue(Event("movement-arrived", "z") in frame.events)
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
    fun a_visible_long_hull_can_confirm_occupation_before_the_mover_gets_within_a_tile() =
        WaterTestScene.withMap { map ->
            val click = Position(600.0, 200.0)
            val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(475.0, 187.0))
            val water = opening.unitDefinitions.getValue("water-test")
            val holder = variant(water, "long-holder", 0.001, MovementCollisionShape.Rectangle(102.0, 20.0))
            val scene = opening.copy(players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                unitDefinitions = opening.unitDefinitions + (holder.id to holder), units = listOf(
                    UnitSpawn("a", "p1", water.id, Position(475.0, 187.0), 90.0),
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
            val frame = context.advance(Step(initial.stepIndex + 2, 1.0, "sim-tick"))
            val replacement = assertNotNull(frame.ship("a").moveTarget)
            assertTrue(Event("group-destination-reassigned", "a") in frame.events)
            assertTrue(Event("group-destination-reassigned", "z") !in frame.events)
            assertTrue(replacement != click)
            assertEquals(otherTarget, frame.ship("z").moveTarget)
            println("Authorized static shore changed $click to $replacement; other remained $otherTarget")
        } finally {
            context.close()
        }
    }

    @Test
    fun a_confirmed_unreachable_route_can_find_a_nearby_destination_on_the_approach_side() =
        WaterTestScene.withMap { water ->
            val wall = (0 until water.heightTiles).map { VisibleTerrainCell(29, it, true, 1) }
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
                val blocked = context.advance(Step(initial.stepIndex + 1, 1.0, "sim-tick"))
                println("Disconnected original target $click, new target=${blocked.ship("a").moveTarget}, events=${blocked.events}")
                assertTrue(Event("group-destination-reassigned", "a") in blocked.events)
                assertTrue(assertNotNull(blocked.ship("a").moveTarget).x <= 574.0)
                assertEquals(otherTarget, blocked.ship("z").moveTarget)
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
            assertTrue(frames.takeWhile { it !== other }.all { it.ship("z").moveTarget == unaffectedTarget })
            assertNotNull(other)
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
            assertNotNull(arrived)
        } finally {
            context.close()
        }
    }

    @Test
    fun scarce_shore_destinations_get_priority_and_an_unavailable_member_reports_its_original_move_failure() =
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
                        assertEquals(null, frame.ship("z-land").moveTarget)
                        assertTrue(Event("group-destination-unavailable", "z-land") in frame.events)
                        assertTrue(Event("path-unreachable", "z-land") in frame.events)
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
        for (step in 0L..2400L) {
            val frame = context.advance(Step(step, 1.0, "sim-tick"))
            if (members.all { id -> frame.events.any { it.unitId == id &&
                    it.kind in setOf("movement-started", "group-destination-unavailable") } }) return frame
        }
        error("Group movement plan could not finish")
    }

    private fun variant(template: UnitDefinition, id: String, speed: Double = template.movement.moveSpeed,
                        shape: MovementCollisionShape = template.movementCollision) =
        UnitDefinition(id, template.className, template.radius, template.displayRadius, template.totalFrames,
            template.imagePath, template.imageScale, template.movement.copy(moveSpeed = speed), emptySet(), movementCollision = shape)

    private fun Observation.ship(id: String) = units.single { it.unitId == id }
}
