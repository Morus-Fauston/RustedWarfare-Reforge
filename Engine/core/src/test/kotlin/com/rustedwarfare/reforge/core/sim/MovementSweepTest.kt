package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import com.rustedwarfare.reforge.core.knowledge.*
import kotlin.test.*

class MovementSweepTest {
    @Test fun different_high_speeds_stay_unrestricted_in_empty_corridors_with_or_without_diagnostics() {
        for(speed in listOf(60.0,145.0)) for((heading,goal) in listOf(
            90.0 to Position(550.0,200.0),270.0 to Position(50.0,200.0),
            180.0 to Position(300.0,350.0),0.0 to Position(300.0,50.0))) {
            val source=TestContentFactory.openScenario();val base=source.unitDefinitions.getValue("tank")
            fun open()=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map=MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,BooleanArray(600))),
                unitDefinitions=mapOf("tank" to TestContentFactory.unitWith(base,movement=base.movement.copy(
                    moveSpeed=speed,moveAccelerationSpeed=1.0))),
                units=listOf(UnitSpawn("u1","p1","tank",Position(300.0,200.0),heading))))).context
            val measured=open();val plain=open()
            try {
                measured.enableMotionDiagnostics(measured.world)
                for(c in listOf(measured,plain))c.submit(MoveAction("go","p1","u1",goal,c.world))
                val a=measured.advance(Step(0,1.0,"sim-tick"));val b=plain.advance(Step(0,1.0,"sim-tick"))
                assertEquals(a.units,b.units);assertEquals(a.events,b.events);assertEquals(a.actionResults,b.actionResults)
                val unit=a.units.single();assertEquals(speed,unit.speed)
                assertEquals(300.0+when(heading){90.0->speed;270.0->-speed;else->0.0},unit.position.x,1e-10)
                assertEquals(200.0+when(heading){180.0->speed;0.0->-speed;else->0.0},unit.position.y,1e-10)
                assertTrue(a.events.none { it.kind=="movement-blocked" })
                val trace=measured.drainMotionDiagnostics(measured.world).single()
                assertEquals(MotionTerrainDisposition.DIRECT,trace.terrainDisposition)
                assertEquals(trace.candidate,unit.position);assertEquals(0.0,trace.correctionTimeAfter)
            } finally { measured.close();plain.close() }
        }
    }

    @Test fun multi_cell_guard_retains_nonzero_steering_through_stop_and_three_clear_steps_reset_history() {
        val source=TestContentFactory.openScenario();val base=source.unitDefinitions.getValue("tank")
        val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map=MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,
                BooleanArray(600) { it%30==17 && it/30 in 9..11 })),
            unitDefinitions=mapOf("tank" to TestContentFactory.unitWith(base,movement=base.movement.copy(
                moveSpeed=60.0,moveAccelerationSpeed=1.0,moveDecelerationSpeed=.5))),
            units=listOf(UnitSpawn("u1","p1","tank",Position(300.0,200.0),89.5))))).context
        try {
            context.enableKnowledge(context.world,KnowledgeRules(TerrainRetention.MEMORY,PathInformationSource.PLAYER_KNOWLEDGE,false))
            context.enableMotionDiagnostics(context.world)
            context.submit(MoveAction("go","p1","u1",Position(550.0,200.0),context.world))
            val hit=context.advance(Step(0,1.0,"sim-tick")).units.single()
            assertEquals(Position(300.0,200.0),hit.position);assertEquals(60.0,hit.speed)
            assertEquals(89.75,hit.heading);assertEquals(.25,hit.angularVelocity)
            val first=context.drainMotionDiagnostics(context.world).single()
            assertEquals(MotionTerrainDisposition.CORRECTED,first.terrainDisposition)
            assertEquals(1.0,first.correctionTimeAfter)
            context.submit(StopAction("stop","p1","u1",context.world))
            for(q in 1L..3L) {
                val now=context.advance(Step(q,1.0,"sim-tick")).units.single()
                assertEquals(hit.heading,now.heading);assertEquals(hit.angularVelocity,now.angularVelocity)
                assertEquals(if(q==1L)30.0 else 0.0,now.speed)
                val trace=context.drainMotionDiagnostics(context.world).single()
                assertEquals(MotionTerrainDisposition.DIRECT,trace.terrainDisposition)
                assertEquals(if(q<3L)1.0 else 0.0,trace.correctionTimeAfter)
                assertEquals(trace.candidate,trace.finalPosition)
            }
        } finally { context.close() }
    }

    @Test fun multiple_speeds_and_directions_preserve_unknown_knowledge_and_stop_braking_after_a_guard() {
        data class Direction(val heading:Double,val goal:Position,val walls:Set<Pair<Int,Int>>)
        for(speed in listOf(60.0,100.0,145.0)) for(direction in listOf(
            Direction(90.0,Position(550.0,200.0),(9..11).map { 17 to it }.toSet()),
            Direction(270.0,Position(50.0,200.0),(9..11).map { 12 to it }.toSet()),
            Direction(180.0,Position(300.0,350.0),(14..16).map { it to 12 }.toSet()),
            Direction(0.0,Position(300.0,50.0),(14..16).map { it to 7 }.toSet()),
        )) {
            val source=TestContentFactory.openScenario();val base=source.unitDefinitions.getValue("tank")
            val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map=MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,
                    BooleanArray(600) { (it%30 to it/30) in direction.walls })),
                unitDefinitions=mapOf("tank" to TestContentFactory.unitWith(base,movement=base.movement.copy(
                    moveSpeed=speed,moveAccelerationSpeed=1.0,moveDecelerationSpeed=.5))),
                units=listOf(UnitSpawn("u1","p1","tank",Position(300.0,200.0),direction.heading))))).context
            try {
                context.enableKnowledge(context.world,KnowledgeRules(TerrainRetention.MEMORY,PathInformationSource.PLAYER_KNOWLEDGE,false))
                context.enableMotionDiagnostics(context.world)
                val knowledge=context.knowledgeSnapshot(context.world,"p1")
                context.submit(MoveAction("go","p1","u1",direction.goal,context.world))
                val path=context.observe().units.single().pathPoints
                val result=context.advance(Step(0,1.0,"sim-tick"));val now=result.units.single()
                assertEquals(Position(300.0,200.0),now.position);assertEquals(speed,now.speed)
                assertEquals(path,now.pathPoints);assertEquals(direction.goal,now.moveTarget)
                assertEquals(knowledge.cells,context.knowledgeSnapshot(context.world,"p1").cells)
                val trace=context.drainMotionDiagnostics(context.world).single()
                assertEquals(MotionTerrainDisposition.CORRECTED,trace.terrainDisposition)
                assertEquals(now.position,trace.terrainResolved);assertEquals(now.position,trace.finalPosition)
                assertEquals(speed,trace.integratedSpeed);assertEquals(speed,trace.finalSpeed)
                assertEquals(1.0,context.localMotionSnapshots(context.world).getValue("u1").correctionTime)
                assertEquals(listOf("movement-started","movement-blocked"),result.events.map { it.kind })
                context.submit(StopAction("stop","p1","u1",context.world))
                val stopped=context.advance(Step(1,1.0,"sim-tick")).units.single()
                assertNull(stopped.moveTarget);assertTrue(stopped.pathPoints.isEmpty());assertEquals(speed*.5,stopped.speed)
                assertEquals(now.heading,stopped.heading);assertEquals(now.angularVelocity,stopped.angularVelocity)
                assertEquals(knowledge.cells,context.knowledgeSnapshot(context.world,"p1").cells)
                val idle=context.advance(Step(2,1.0,"sim-tick")).units.single()
                assertEquals(0.0,idle.speed);assertEquals(MovementState.IDLE,idle.movementState)
            } finally { context.close() }
        }
    }

    @Test fun hidden_wall_between_clear_endpoints_blocks_actual_motion_without_changing_the_plan() {
        val source = TestContentFactory.openScenario()
        val tank = source.unitDefinitions.getValue("tank")
        val fast = TestContentFactory.unitWith(tank, radius = 1.0, movement = tank.movement.copy(
            moveSpeed = 100.0, moveAccelerationSpeed = 100.0))
        val paths = mutableListOf<List<Position>>()
        for (blocked in listOf(false, true)) {
            val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map = MapDefinition(30, 20, 20, 20, emptyList(), IntArray(600),
                    BlockingGrid(30, 20, BooleanArray(600) { blocked && it == 5 * 30 + 8 })),
                unitDefinitions = mapOf("tank" to fast),
                units = listOf(UnitSpawn("u1", "p1", "tank", Position(110.0, 110.0), 90.0))))).context
            try {
                context.enableKnowledge(context.world, KnowledgeRules(TerrainRetention.MEMORY,
                    PathInformationSource.PLAYER_KNOWLEDGE, false))
                context.submit(MoveAction("move", "p1", "u1", Position(350.0, 110.0), context.world))
                paths += context.observe().units.single().pathPoints
                val result = context.advance(Step(0, 1.0, "sim-tick"))
                val unit = result.units.single()
                // Hidden tile spans x=160..180. Both x=110 and x=210 are independently clear.
                assertEquals(if (blocked) Position(110.0, 110.0) else Position(210.0, 110.0), unit.position)
                // D-095 multi-cell protection retains the ordinary integrated propulsion/command.
                assertEquals(100.0, unit.speed)
                assertEquals(MovementState.MOVING, unit.movementState)
                assertEquals(Position(350.0, 110.0), unit.moveTarget)
                assertEquals(paths.last(), unit.pathPoints)
                assertEquals(0.0, unit.angularVelocity)
                assertTrue(result.events.none { it.kind in setOf("movement-arrived", "path-unreachable") })
                assertEquals(blocked, result.events.any { it.kind == "movement-blocked" })
            } finally { context.close() }
        }
        assertEquals(listOf(listOf(Position(350.0, 110.0)), listOf(Position(350.0, 110.0))), paths)
    }
}
