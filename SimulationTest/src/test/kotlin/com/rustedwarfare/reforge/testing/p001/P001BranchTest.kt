package com.rustedwarfare.reforge.testing.p001

import com.rustedwarfare.reforge.core.sim.*
import com.rustedwarfare.reforge.testing.*
import java.nio.file.Path
import kotlin.test.*

/** Fixed arithmetic examples, never collected from the core under test. */
class P001BranchTest {
    @Test fun `natural M02 uses the real direct path and preserves legacy rejection`() {
        val input = input("M02")
        val context = assertIs<InitResult.Accepted>(SimulationContext.initialize(input.core)).context
        val target = Position(100.0, 140.0)
        try {
            val samples = mutableListOf(context.observe())
            context.submit(MoveAction("move", "p1", "u1", target, context.world, 0))
            (0L..20L).forEach { samples += context.advance(Step(it, 1.0, "sim-tick")) }
            assertNull(P001Relations.evaluate(input, "M02", samples, naturalTurn = true))
            assertEquals("turn-prefix-literals", P001Relations.evaluate(input, "M02", samples)?.ruleId)
            val extra = samples.mapIndexed { index, row -> if (index != 1) row else Observation(row.sampleId, row.worldId, row.scenarioId,
                row.stepIndex, row.simulationTime, row.units.map { it.copy(pathPoints = listOf(Position(100.0, 130.0), target)) }, row.events, row.actionResults) }
            assertNotNull(P001Relations.evaluate(input, "M02", extra, naturalTurn = true))
            val wrongTurn = samples.mapIndexed { index, row -> if (index != 21) row else Observation(row.sampleId, row.worldId, row.scenarioId,
                row.stepIndex, row.simulationTime, row.units.map { it.copy(heading = 54.5) }, row.events, row.actionResults) }
            assertNotNull(P001Relations.evaluate(input, "M02", wrongTurn, naturalTurn = true))
        } finally { context.close() }
    }

    private val root=Path.of(System.getProperty("repo.root"))
    private fun input(label:String)=ScenarioReader.load(root.resolve("TestLab/scenarios/P001-self-behavior/$label.v1.json"),root.resolve("TestLab/contracts/P001-self-behavior/$label.basic.v1.json"))
    private fun frame(step:Int,x:Double,y:Double=110.0,heading:Double=90.0,speed:Double=0.0,omega:Double=0.0,
                      target:Position?=null,points:List<Position> = if(target==null) emptyList() else listOf(target),events:List<Event> = emptyList()):Observation = Observation(
        "literal:$step","literal-world:1","literal",maxOf(step,0).toLong(),if(step<0) 0.0 else step+1.0,
        listOf(UnitObservation("u1","p1","p001.weaponless-tank",Position(x,y),heading,speed,omega,target,points,
            if(target!=null) MovementState.MOVING else if(speed!=0.0) MovementState.DECELERATING else MovementState.IDLE)),events,emptyList())

    @Test fun `M02 prefix branch accepts literal worked example and rejects wrong terminal heading`() {
        val omega=listOf(.25,.50,.75,1.0,1.25,1.50,1.75,2.0,2.25,2.50,2.75,3.0,3.25,3.50,3.75,4.0,4.1,4.1,4.1,4.1,4.1)
        val heading=listOf(.25,.75,1.50,2.50,3.75,5.25,7.0,9.0,11.25,13.75,16.50,19.50,22.75,26.25,30.0,34.0,38.1,42.2,46.3,50.4,54.5)
        val target=Position(100.0,140.0)
        val samples=listOf(frame(-1,100.0,100.0,0.0)) + heading.indices.map { i->frame(i,100.0,100.0,heading[i],omega=omega[i],target=target,points=listOf(Position(100.0,130.0),target),events=if(i==0) listOf(Event("movement-started","u1")) else emptyList()) }
        assertNull(P001Relations.evaluate(input("M02"),"M02",samples))
        val broken=samples.toMutableList();broken[21]=frame(20,100.0,100.0,54.6,omega=4.1,target=target)
        assertNotNull(P001Relations.evaluate(input("M02"),"M02",broken))
        val advancing=samples.toMutableList();advancing[1]=frame(0,100.01,100.0,.25,speed=.01,omega=.25,target=target)
        assertNotNull(P001Relations.evaluate(input("M02"),"M02",advancing))
    }

    @Test fun `M03 full branch checks event boundary speed literals distance and exact idle window`() {
        val positions=listOf(110.07,110.21,110.42,110.70,111.05,111.47,111.96,112.52,113.15,113.85,114.62,115.46,116.37,117.35,118.40,119.50,120.43,121.19,121.78,122.20,122.45,122.53,122.53)
        val speeds=listOf(.07,.14,.21,.28,.35,.42,.49,.56,.63,.70,.77,.84,.91,.98,1.05,1.1,.93,.76,.59,.42,.25,.08,0.0)
        val samples=listOf(frame(-1,110.0))+ (0..42).map { step->frame(step,positions.getOrElse(step){122.53},speed=speeds.getOrElse(step){0.0},target=if(step<16) Position(250.0,110.0) else null,events=when(step) {0->listOf(Event("movement-started","u1"));16->listOf(Event("movement-cancelled","u1"));else->emptyList()}) }
        assertNull(P001Relations.evaluate(input("M03"),"M03",samples))
        val missing=samples.toMutableList();missing[17]=frame(16,120.43,speed=.93)
        assertEquals("movement-event-sequence",P001Relations.evaluate(input("M03"),"M03",missing)?.ruleId)
        val drift=samples.toMutableList();drift[43]=frame(42,122.5300000001)
        assertEquals("idle-tail",P001Relations.evaluate(input("M03"),"M03",drift)?.ruleId)
    }

    @Test fun `M05 full branch rejects a nearby replacement and a fabricated arrival`() {
        val samples=listOf(frame(-1,110.0),frame(0,110.07,speed=.07,target=Position(250.0,110.0),events=listOf(Event("movement-started","u1"))))+
            (1..26).map { step->frame(step,110.07,events=if(step==1) listOf(Event("movement-cancelled","u1"),Event("path-unreachable","u1")) else emptyList()) }
        assertNull(P001Relations.evaluate(input("M05"),"M05",samples))
        // Permanent counterexample: two unreachable commands must never stand in for moving -> unreachable.
        val doubleCancel=listOf(frame(-1,110.0),frame(0,110.0,events=listOf(Event("movement-cancelled","u1"),Event("path-unreachable","u1"))))+
            (1..26).map { step->frame(step,110.0,events=if(step==1) listOf(Event("movement-cancelled","u1"),Event("path-unreachable","u1")) else emptyList()) }
        assertEquals("movement-event-sequence",P001Relations.evaluate(input("M05"),"M05",doubleCancel)?.ruleId)
        val replaced=samples.toMutableList();replaced[2]=frame(1,110.07,target=Position(390.0,100.0))
        assertNotNull(P001Relations.evaluate(input("M05"),"M05",replaced))
        val arrived=samples.toMutableList();arrived[3]=frame(2,110.07,events=listOf(Event("movement-arrived","u1")))
        assertEquals("movement-event-sequence",P001Relations.evaluate(input("M05"),"M05",arrived)?.ruleId)
    }
}
