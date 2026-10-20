package com.rustedwarfare.reforge.core.path

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class PathPlannerTest {
    private val start=Position(30.0,50.0)
    private val target=Position(70.0,50.0)
    private fun terrain(vararg blocked: Pair<Int,Int>): PlanningTerrain {
        val cells=BooleanArray(25)
        blocked.forEach { (column,row)->cells[row*5+column]=true }
        return PlanningTerrain(5,5,20,20,cells)
    }
    private fun map(vararg blocked: Pair<Int,Int>): MapDefinition {
        val cells=BooleanArray(25);blocked.forEach { (c,r)->cells[r*5+c]=true }
        return MapDefinition(5,5,20,20,emptyList(),IntArray(25),BlockingGrid(5,5,cells))
    }
    private fun knowledge(vararg visible:VisibleTerrainCell): PlanningTerrain {
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(TestContentFactory.openScenario())).context
        try {
            val store=PlayerTerrainKnowledge.create(context.world,"p1",5,5,KnowledgeRules(TerrainRetention.MEMORY,PathInformationSource.PLAYER_KNOWLEDGE,false))
            store.accept(context.world,visible.toList())
            return PlanningTerrain.fromKnowledge(store.snapshot(context.world),20,20).also { store.close() }
        } finally { context.close() }
    }

    @Test fun ki03_truth_adapter_misuse_changes_the_same_public_planner_result() {
        val straight=listOf(Position(70.0,50.0))
        val upper=listOf(Position(30.0,30.0),Position(70.0,30.0),Position(70.0,50.0))
        // Both authorized histories are empty; true maps are intentionally never arguments to fromKnowledge.
        assertEquals(straight,PathPlanner.findPath(knowledge(),start,target,1.0))
        assertEquals(straight,PathPlanner.findPath(knowledge(),start,target,1.0))
        val wronglySuppliedTruth=PathPlanner.findPath(PlanningTerrain.fromNoFog(map(2 to 2)),start,target,1.0)
        assertEquals(upper,wronglySuppliedTruth)
        assertNotEquals(straight,wronglySuppliedTruth) // Sensitive wrong-adapter control, not a security claim.
        assertEquals(straight,PathPlanner.findPath(PlanningTerrain.fromNoFog(map()),start,target,1.0))
    }
    @Test fun ki04_unknown_goal_is_planned_but_known_blocked_goal_is_unreachable() {
        assertEquals(listOf(Position(70.0,50.0)),PathPlanner.findPath(knowledge(),start,target,1.0))
        assertNull(PathPlanner.findPath(knowledge(VisibleTerrainCell(3,2,true)),start,target,1.0))
        assertNull(PathPlanner.findPath(PlanningTerrain.fromNoFog(map(3 to 2)),start,target,1.0))
    }
    @Test fun ki05_exact_target_tangency_uses_only_planning_obstacles() {
        val precise=Position(70.0,41.0)
        val literal=listOf(precise)
        assertEquals(literal,PathPlanner.findPath(knowledge(),start,precise,1.0))
        assertNotNull(PathPlanner.findPath(knowledge(VisibleTerrainCell(3,1,true)),start,target,1.0))
        assertNull(PathPlanner.findPath(knowledge(VisibleTerrainCell(3,1,true)),start,precise,1.0))
        val wronglySuppliedTruth=PathPlanner.findPath(PlanningTerrain.fromNoFog(map(3 to 1)),start,precise,1.0)
        assertNull(wronglySuppliedTruth);assertNotEquals(literal,wronglySuppliedTruth)
    }
    @Test fun ki06_candidate_tangency_uses_only_planning_obstacles() {
        val straight=listOf(Position(70.0,50.0))
        val lower=listOf(Position(70.0,70.0),Position(70.0,50.0))
        assertEquals(straight,PathPlanner.findPath(knowledge(),start,target,10.0))
        assertEquals(lower,PathPlanner.findPath(knowledge(VisibleTerrainCell(2,1,true)),start,target,10.0))
        val wronglySuppliedTruth=PathPlanner.findPath(PlanningTerrain.fromNoFog(map(2 to 1)),start,target,10.0)
        assertEquals(lower,wronglySuppliedTruth);assertNotEquals(straight,wronglySuppliedTruth)
    }

    @Test fun ki07_same_cell_exact_target_and_finite_outside_coordinates() {
        assertEquals(listOf(Position(35.0,55.0)),PathPlanner.findPath(terrain(),start,Position(35.0,55.0),1.0))
        assertEquals(listOf(Position(70.25,50.5)),PathPlanner.findPath(terrain(),start,Position(70.25,50.5),1.0))
        for(outside in listOf(Position(-1.0,50.0),Position(100.0,50.0),Position(50.0,-1.0),Position(50.0,100.0))) {
            assertNull(PathPlanner.findPath(terrain(),outside,target,1.0))
            assertNull(PathPlanner.findPath(terrain(),start,outside,1.0))
        }
    }
    @Test fun ki08_public_planner_rejects_nonfinite_and_negative_parameters_in_declared_order() {
        val terrain=terrain()
        fun rejected(code:String,s:Position,t:Position,r:Double) {
            assertEquals(code,assertFailsWith<PlanningException> { PathPlanner.findPath(terrain,s,t,r) }.code)
        }
        for(bad in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            rejected("invalid-coordinate",Position(bad,50.0),target,1.0)
            rejected("invalid-coordinate",Position(30.0,bad),target,1.0)
            rejected("invalid-coordinate",start,Position(bad,50.0),1.0)
            rejected("invalid-coordinate",start,Position(70.0,bad),1.0)
            rejected("invalid-radius",start,target,bad)
        }
        rejected("invalid-radius",start,target,-1.0)
        rejected("invalid-coordinate",Position(Double.NaN,50.0),target,-1.0)
        rejected("invalid-radius",Position(-1.0,50.0),target,-1.0)
        for(radius in listOf(0.0,-0.0)) assertEquals(listOf(Position(70.0,50.0)),PathPlanner.findPath(terrain,start,target,radius))
        assertNull(PathPlanner.findPath(terrain(3 to 2),start,target,0.0))
        assertEquals(listOf(Position(70.0,50.0)),PathPlanner.findPath(terrain,start,target,1.0))
    }

    @Test fun ki09_truth_collision_pair_and_zero_radius_core_remain_unchanged() {
        val source=TestContentFactory.openScenario()
        val original=source.unitDefinitions.getValue("tank")
        val fast=TestContentFactory.unitWith(original,movement=original.movement.copy(moveSpeed=20.0,moveAccelerationSpeed=20.0))
        fun truth(blocked:Boolean):MapDefinition {
            val cells=BooleanArray(600)
            if(blocked) cells[5*30+8]=true
            return MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,cells))
        }
        for(blocked in listOf(false,true)) {
            val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(map=truth(blocked),
                unitDefinitions=mapOf("tank" to fast),units=listOf(UnitSpawn("u1","p1","tank",Position(110.0,110.0),90.0))))).context
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false))
                val accepted=assertIs<ActionResult.Accepted>(context.submit(MoveAction("move","p1","u1",Position(250.0,110.0),context.world)))
                assertEquals(listOf("movement-started"),accepted.events.map { it.kind })
                assertEquals(listOf(Position(250.0,110.0)),context.observe().units.single().pathPoints)
                assertEquals(Position(130.0,110.0),context.advance(Step(0,1.0,"sim-tick")).units.single().position)
                context.submit(MoveAction("turn","p1","u1",Position(250.0,112.0),context.world))
                assertEquals(listOf(Position(250.0,112.0)),context.observe().units.single().pathPoints)
                val sample=context.advance(Step(1,1.0,"sim-tick"));val unit=sample.units.single()
                // Direct target now supplies a real turn; the truth pair has identical unknown terrain.
                val expectedSpeed=20.0
                val angle=Math.toRadians(90.25)
                val emptyPosition=Position(130.0+kotlin.math.sin(angle)*expectedSpeed,110.0-kotlin.math.cos(angle)*expectedSpeed)
                assertEquals(if(blocked) Position(130.0,110.0) else emptyPosition,unit.position)
                assertEquals(if(blocked) 0.0 else expectedSpeed,unit.speed)
                assertEquals(if(blocked) 0.0 else .25,unit.angularVelocity)
                if(!blocked) assertEquals(90.25,unit.heading)
                assertEquals(if(blocked) MovementState.BLOCKED else MovementState.MOVING,unit.movementState)
                assertEquals(blocked,sample.events.any { it.kind == "movement-blocked" })
            } finally {context.close()}
        }
        for(radius in listOf(0.0,-0.0)) {
            val point=TestContentFactory.unitWith(original,radius=radius)
            val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(map=truth(false),
                unitDefinitions=mapOf("tank" to point),units=listOf(UnitSpawn("u1","p1","tank",Position(110.0,110.0),90.0))))).context
            try {
                assertIs<ActionResult.Accepted>(context.submit(MoveAction("point","p1","u1",Position(170.0,110.0),context.world)))
                assertEquals(listOf(Position(170.0,110.0)),context.observe().units.single().pathPoints)
                assertTrue(context.advance(Step(0,1.0,"sim-tick")).units.single().position.x>110.0)
            } finally {context.close()}
        }
    }

    @Test fun ki02_known_center_uses_literal_upper_parent_chain() {
        assertEquals(listOf(Position(30.0,30.0),Position(70.0,30.0),Position(70.0,50.0)),
            PathPlanner.findPath(terrain(2 to 2),start,target,1.0))
    }

    @Test fun ki01_empty_terrain_returns_literal_straight_path() {
        assertEquals(listOf(Position(70.0,50.0)),PathPlanner.findPath(terrain(),start,target,1.0))
    }
}
