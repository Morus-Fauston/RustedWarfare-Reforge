package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.navigation.*

import com.rustedwarfare.reforge.core.content.BlockingGrid
import com.rustedwarfare.reforge.core.content.MapDefinition
import com.rustedwarfare.reforge.core.navigation.PlanningTerrain
import kotlin.test.*

class NearestMoveRollbackTest {
    @Test fun failed_candidate_steps_restore_nearest_search_and_individual_group_rejection() {
        val base = TestContentFactory.openScenario()
        val light = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        val boxed = TestContentFactory.unitWith(light, radius = 10.0)
        val holder = com.rustedwarfare.reforge.core.content.UnitDefinition("holder", light.className, 6.0,
            light.displayRadius, light.totalFrames, light.imagePath, light.imageScale,
            light.movement.copy(moveSpeed = 0.0), emptySet(), mass = 1000.0)
        val width = 40; val height = 24
        val map = MapDefinition(width, height, 20, 20, emptyList(), IntArray(width * height),
            BlockingGrid(width, height, BooleanArray(width * height) {
                val col = it % width; val row = it / width
                col == 16 || col in 4..6 && row in 4..6 && !(col == 5 && row == 5)
            }))
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to light, "boxed" to boxed, "holder" to holder), units = listOf(
            UnitSpawn("boxed", "p1", "boxed", Position(110.0, 110.0), 90.0),
            UnitSpawn("left", "p1", "tank", Position(100.0, 350.0), 90.0),
            UnitSpawn("right", "p1", "tank", Position(500.0, 100.0), 180.0),
            UnitSpawn("holder", "p1", "holder", Position(548.0, 348.9), 0.0)))
        val origin = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val click = Position(550.0, 350.0)
            val moving = scene.units.filter { it.unitId != "holder" }
            val group = assertNotNull(origin.requestGroupMove("p1", moving.map { it.unitId }, click))
            val terrain = PlanningTerrain.fromNoFog(map)
            val members = moving.map { spawn ->
                val definition = scene.unitDefinitions.getValue(spawn.typeId)
                val body = MutableUnit(spawn.unitId, "p1", spawn.typeId, definition, spawn.position, spawn.heading)
                MovePlanningMember(MoveAction("go-${spawn.unitId}", "p1", spawn.unitId, click, origin.world), body,
                    MovePlanningPose(spawn.position, 0.0, ContactLayer.SURFACE, definition.movementCollision),
                    spawn.position, definition.radius, terrain, HullRoute.Circle(definition.radius), false, 0L)
            }
            val fixed = MutableUnit("holder", "p1", "holder", holder, Position(548.0, 348.9), 0.0)
            val input = MovePlanningInput(map, members, listOf(MovePlanningOccupant(fixed,
                MovePlanningPose(fixed.position, 0.0, ContactLayer.SURFACE, holder.movementCollision), null,
                idle = true, obstructsRoute = true, fixed = true)), false)
            data class Replay(val step: Long, val plans: Map<String, Pair<Boolean, GroupMoveSlot?>>, val work: List<Int>)
            fun replay(retry: Boolean): Replay {
                val steps = CandidateSteps()
                members.forEach { steps.current.movePlanning.enqueue(it.action, group) }
                val consumed = mutableListOf<Int>()
                for (tick in 0L..12000L) {
                    fun advance(fail: Boolean): Pair<Map<String, Pair<Boolean, GroupMoveSlot?>>, Int> {
                        val published = linkedMapOf<String, Pair<Boolean, GroupMoveSlot?>>()
                        var used = 0
                        steps.advance(scene, Step(tick, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                            if (fail) error("observer failure")
                            Observation("nearest-$index", origin.world.id, scene.scenarioId, index, time,
                                emptyList(), emptyList(), emptyList())
                        }) { candidate ->
                            val budget = PathWorkBudget(128)
                            candidate.state.movePlanning.advance(ClearanceAStarPolicy.DEFAULT, budget, { _, _, _ -> input },
                                { member, _, _, _, plan -> published[member.action.unitId] = plan.nearest to plan.slot })
                            used = budget.used
                            assertTrue(used in 0..128)
                        }
                        return published to used
                    }
                    if (retry && tick % 7 == 1L) assertEquals("observer failure",
                        assertFailsWith<IllegalStateException> { advance(true) }.message)
                    val (plans, used) = advance(false)
                    consumed += used
                    if (plans.isNotEmpty()) {
                        assertEquals(setOf("boxed", "left", "right"), plans.keys)
                        assertEquals(true to null, plans.getValue("boxed"))
                        assertTrue(plans.getValue("left").first)
                        assertEquals(Position(314.0, 350.0), assertNotNull(plans.getValue("left").second).position)
                        assertNotNull(plans.getValue("right").second)
                        return Replay(tick, plans, consumed)
                    }
                }
                error("Nearest planning did not finish")
            }
            assertEquals(replay(false), replay(true))
        } finally { origin.close() }
    }

    @Test fun failed_steps_restore_the_continuous_occupied_boundary_cursor() {
        val base = TestContentFactory.openScenario()
        val moving = TestContentFactory.unitWith(base.unitDefinitions.getValue("tank"), radius = 6.0)
        val map = MapDefinition(4, 4, 20, 20, emptyList(), IntArray(16), BlockingGrid(4, 4, BooleanArray(16)))
        val scene = base.copy(map = map, unitDefinitions = mapOf("tank" to moving), units = listOf(
            UnitSpawn("mover", "p1", "tank", Position(10.0, 10.0), 90.0)))
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(scene)).context
        try {
            val body = MutableUnit("mover", "p1", "tank", moving, Position(10.0, 10.0), 90.0)
            val fixed = MutableUnit("holder", "p1", "tank", moving, Position(40.0, 40.0), 0.0)
            val action = MoveAction("occupied", "p1", "mover", Position(42.0, 41.1), context.world)
            val member = MovePlanningMember(action, body,
                MovePlanningPose(body.position, 0.0, ContactLayer.SURFACE, moving.movementCollision), body.position,
                6.0, PlanningTerrain.fromNoFog(map), HullRoute.Circle(6.0), false, 0L)
            val input = MovePlanningInput(map, listOf(member), listOf(MovePlanningOccupant(fixed,
                MovePlanningPose(fixed.position, 0.0, ContactLayer.SURFACE, moving.movementCollision), null,
                idle = true, obstructsRoute = true, fixed = true)), false)
            fun replay(failures: Boolean): Triple<Long, Position, List<Int>> {
                val steps = CandidateSteps()
                steps.current.movePlanning.enqueue(action, null)
                val work = mutableListOf<Int>()
                for (tick in 0L..6000L) {
                    fun advance(fail: Boolean): Pair<PlannedMove?, Int> {
                        var published: PlannedMove? = null
                        var used = 0
                        steps.advance(scene, Step(tick, 1.0, "sim-tick"), emptyList(), { index, time, _ ->
                            if (fail) error("boundary observer failure")
                            Observation("boundary-$index", context.world.id, scene.scenarioId, index, time,
                                emptyList(), emptyList(), emptyList())
                        }) { candidate ->
                            val budget = PathWorkBudget(8)
                            candidate.state.movePlanning.advance(ClearanceAStarPolicy.DEFAULT, budget, { _, _, _ -> input },
                                { _, _, _, _, plan -> published = plan })
                            used = budget.used
                            assertTrue(used in 0..8)
                        }
                        return published to used
                    }
                    if (failures && tick % 7 == 1L) assertFailsWith<IllegalStateException> { advance(true) }
                    val (plan, used) = advance(false)
                    work += used
                    if (plan != null) {
                        assertTrue(plan.nearest)
                        val position = assertNotNull(plan.slot).position
                        assertEquals(50.5145909041198, position.x, 0.000001)
                        assertEquals(45.7830249972659, position.y, 0.000001)
                        return Triple(tick, position, work)
                    }
                }
                error("Occupied boundary planning did not finish")
            }
            assertEquals(replay(false), replay(true))
        } finally { context.close() }
    }
}
