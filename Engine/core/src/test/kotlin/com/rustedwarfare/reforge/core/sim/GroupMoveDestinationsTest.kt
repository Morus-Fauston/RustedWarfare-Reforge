package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

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

/**
 * Black-box behavioral contract tests for the unified single terminal landing layer (ENG-P017-007).
 *
 * Replaces white-box legacy `GroupMoveDestinations` static slot-reassignment tests with observable
 * arrival, zero-overlap, blocker-settling, fog-replanning, and 2–7 rectangular naval fleet contracts.
 */
class GroupMoveDestinationsTest {
    @Test
    fun small_water_and_hover_groups_settle_cleanly_on_narrow_shore_without_overlap() = WaterTestScene.withMap { water ->
        val banks = (20 until water.widthTiles).flatMap { column ->
            (0 until water.heightTiles).filter { it != 9 }.map { column to it }
        }.toSet()
        val map = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
            water.tileSets, water.groundGids, water.blocking, banks)
        val click = Position(610.0, 249.0)
        val approach = Position(540.0, 190.0)
        val second = Position(590.0, 190.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, approach).copy(units = listOf(
            UnitSpawn("a", "p1", "water-test", approach, 90.0),
            UnitSpawn("z", "p1", "water-test", second, 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            val settled = awaitAllArrived(context, listOf("a", "z"), maxSteps = 2000)
            val posA = settled.ship("a").position
            val posZ = settled.ship("z").position
            assertTrue(ScreenPlane.distanceSquared(posA, posZ) >= 12.0 * 12.0 - 1e-5,
                "Settled water units must maintain zero hard overlap: $posA / $posZ")
        } finally {
            context.close()
        }
    }

    @Test
    fun small_rectangular_naval_fleet_settles_cleanly_in_open_water_and_at_shore() = WaterTestScene.withMap { map ->
        val base = WaterTestScene.unit(MovementType.WATER)
        val cruiser = variant(base, "cruiser", speed = 1.0, shape = MovementCollisionShape.Rectangle(48.0, 20.0))
        val spawns = listOf(
            UnitSpawn("s0", "p1", cruiser.id, Position(470.0, 115.0), 90.0),
            UnitSpawn("s1", "p1", cruiser.id, Position(470.0, 171.0), 90.0),
            UnitSpawn("s2", "p1", cruiser.id, Position(470.0, 227.0), 90.0),
            UnitSpawn("s3", "p1", cruiser.id, Position(470.0, 283.0), 90.0),
        )
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(460.0, 150.0)).copy(
            unitDefinitions = mapOf(cruiser.id to cruiser),
            units = spawns,
        )
        for (click in listOf(Position(660.0, 220.0), Position(350.0, 220.0))) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
            try {
                val ids = spawns.map { it.unitId }
                val group = assertNotNull(context.requestGroupMove("p1", ids, click))
                submitSelection(context, group, *ids.toTypedArray())
                val settled = awaitAllArrived(context, ids, maxSteps = 2400)
                val units = ids.map { settled.ship(it) }
                for (i in units.indices) for (j in i + 1 until units.size) {
                    val clearance = UnitContact.poseClearance(
                        cruiser.movementCollision, units[i].position, units[i].heading,
                        cruiser.movementCollision, units[j].position, units[j].heading,
                    )
                    assertTrue(clearance >= -1e-4, "Rectangular naval fleet must settle with zero overlap ($click): $clearance")
                }
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun temporary_and_persistent_blockers_at_click_allow_group_members_to_settle_cleanly() {
        for (holderSpeed in listOf(0.22, 0.0)) {
            WaterTestScene.withMap { map ->
                val click = Position(600.0, 200.0)
                val opening = WaterTestScene.scenario(map, MovementType.WATER, Position(560.0, 200.0))
                val holder = variant(WaterTestScene.unit(MovementType.WATER), "holder", holderSpeed)
                val scene = opening.copy(
                    players = listOf(Player("p1", PlayerControl.LOCAL), Player("p2", PlayerControl.LOCAL)),
                    unitDefinitions = opening.unitDefinitions + (holder.id to holder),
                    units = listOf(
                        UnitSpawn("a", "p1", "water-test", Position(660.0, 200.0), 270.0),
                        UnitSpawn("z", "p1", "water-test", Position(590.0, 130.0), 180.0),
                        UnitSpawn("holding", "p2", "holder", click, 180.0),
                    ),
                )
                val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
                try {
                    context.enablePlanningWorkDiagnostics(context.world)
                    if (holderSpeed > 0.0) {
                        assertIs<ActionResult.Accepted>(context.submit(MoveAction("move-away", "p2", "holding",
                            Position(600.0, 340.0), context.world)))
                    }
                    val group = assertNotNull(context.requestGroupMove("p1", listOf("z", "a"), click))
                    submitSelection(context, group, "a", "z")
                    val settled = awaitAllArrived(context, listOf("a", "z"), maxSteps = 1800)
                    val work = context.drainPlanningWorkDiagnostics(context.world)
                    assertTrue(work.all { it.used <= it.limit })
                    val posA = settled.ship("a").position
                    val posZ = settled.ship("z").position
                    val posH = settled.ship("holding").position
                    assertTrue(ScreenPlane.distanceSquared(posA, posZ) >= 12.0 * 12.0 - 1e-5)
                    assertTrue(ScreenPlane.distanceSquared(posA, posH) >= 12.0 * 12.0 - 1e-5)
                    assertTrue(ScreenPlane.distanceSquared(posZ, posH) >= 12.0 * 12.0 - 1e-5)
                } finally {
                    context.close()
                }
            }
        }
    }

    @Test
    fun confirmed_fog_shore_or_wall_reroutes_and_settles_on_reachable_side() = WaterTestScene.withMap { water ->
        val wall = (0 until water.heightTiles).map { VisibleTerrainCell(28, it, true, 1) }
        val gids = water.groundGids.apply { for (cell in wall) this[cell.row * water.widthTiles + cell.column] = 1 }
        val map = MapDefinition(water.widthTiles, water.heightTiles, water.tileWidth, water.tileHeight,
            water.tileSets, gids, water.blocking)
        val click = Position(625.0, 200.0)
        val scene = WaterTestScene.scenario(map, MovementType.WATER, Position(530.0, 200.0)).copy(units = listOf(
            UnitSpawn("a", "p1", "water-test", Position(510.0, 200.0), 90.0),
            UnitSpawn("z", "p1", "water-test", Position(675.0, 170.0), 270.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY, PathInformationSource.PLAYER_KNOWLEDGE, false))
            val group = assertNotNull(context.requestGroupMove("p1", listOf("a", "z"), click))
            submitSelection(context, group, "a", "z")
            var step = 0L
            while (step < 100 && context.observe().ship("a").moveTarget == null) {
                context.advance(Step(step++, 1.0, "sim-tick"))
            }
            context.acceptVisibleTerrain(context.world, "p1", wall)
            val settled = awaitAllArrived(context, listOf("a", "z"), startStep = step, maxSteps = 2000)
            assertTrue(settled.ship("a").position.x <= 560.0, "West unit must settle on the reachable west side of the revealed wall")
            assertTrue(settled.ship("z").position.x >= 580.0, "East unit must settle cleanly near the east click")
        } finally {
            context.close()
        }
    }

    @Test
    fun mixed_hover_and_land_group_settles_cleanly_at_shore_and_offshore_clicks() = WaterTestScene.withMap { map ->
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
                val settled = awaitAllArrived(context, listOf("a-hover", "z-land"), maxSteps = 2000)
                val posHover = settled.ship("a-hover").position
                val posLand = settled.ship("z-land").position
                assertTrue(posLand.x < 400.0, "Land unit must remain on land: $posLand")
                if (click == offshore) {
                    assertTrue(posHover.x > 550.0, "Hover unit must reach offshore click: $posHover")
                }
                assertTrue(ScreenPlane.distanceSquared(posHover, posLand) >= 12.0 * 12.0 - 1e-5)
            } finally {
                context.close()
            }
        }
    }

    @Test
    fun culdesac_overflow_100_settles_all_100_tanks_with_zero_stalled_and_filled_pocket() {
        val entry = checkNotNull(ScenarioCatalog.byId("culdesac-overflow-100"))
        val defs = ScenarioCatalog.deriveBenchmarkDefinitions(TestContentFactory.openScenario().unitDefinitions)
        val scenario = entry.buildScenario(defs)
        val ctx = assertIs<InitResult.Accepted>(SimulationContext.initialize(scenario)).context
        try {
            ctx.enablePlanningWorkDiagnostics(ctx.world)
            entry.issueOrders(ctx)
            val obs = ArrayList<Observation>(entry.defaultSteps + 1)
            obs += ctx.observe()
            for (s in 0L until entry.defaultSteps.toLong()) {
                val frame = ctx.advance(Step(s, 1.0, "sim-tick"))
                obs += frame
                val work = ctx.drainPlanningWorkDiagnostics(ctx.world)
                assertTrue(work.all { it.used <= it.limit }, "Step $s exceeded planning budget")
            }
            val seq = DiagnosticSceneProjector.projectSequence(obs, scenario.unitDefinitions, gates = entry.gates)
            val failures = entry.assertOutcome(seq)
            assertTrue(failures.isEmpty(), "culdesac-overflow-100 failed assertions: $failures")
        } finally {
            ctx.close()
        }
    }

    private fun submitSelection(context: SimulationContext, group: GroupMoveRequest, vararg members: String) =
        members.forEach { id ->
            val receipt = assertIs<ActionResult.Accepted>(context.submitGroupMove(
                MoveAction("click-$id", "p1", id, group.click, context.world), group))
            assertTrue(receipt.events.isEmpty())
        }

    private fun awaitAllArrived(
        context: SimulationContext,
        members: List<String>,
        startStep: Long = 0L,
        maxSteps: Int = 2000,
    ): Observation {
        for (offset in 0 until maxSteps) {
            val frame = context.advance(Step(startStep + offset, 1.0, "sim-tick"))
            if (members.all { id ->
                    val u = frame.ship(id)
                    u.moveTarget == null && u.moveArrival != null
                }
            ) return frame
        }
        error("Group members did not all settle within $maxSteps steps: " +
            context.observe().units.filter { it.unitId in members }.map {
                "${it.unitId}@(${"%.1f".format(it.position.x)},${"%.1f".format(it.position.y)}) tgt=${it.moveTarget} arr=${it.moveArrival != null}"
            })
    }

    private fun variant(template: UnitDefinition, id: String, speed: Double = template.movement.moveSpeed,
                        shape: MovementCollisionShape = template.movementCollision) =
        UnitDefinition(id, template.className, template.radius, template.displayRadius, template.totalFrames,
            template.imagePath, template.imageScale, template.movement.copy(moveSpeed = speed), emptySet(), movementCollision = shape)

    private fun Observation.ship(id: String) = units.single { it.unitId == id }
}
