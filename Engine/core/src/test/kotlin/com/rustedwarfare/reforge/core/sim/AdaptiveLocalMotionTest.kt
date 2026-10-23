package com.rustedwarfare.reforge.core.sim

import com.rustedwarfare.reforge.core.content.*
import kotlin.test.*

class AdaptiveLocalMotionTest {
    private fun profile(v: Double, turn: Double, acceleration: Double) = MovementProfile(
        MovementType.LAND,v,.05,.02,turn,acceleration,false,false)

    @Test fun invalid_step_preserves_adaptive_segment_and_pending_replacement_while_diagnostics_stay_observational() {
        val source=TestContentFactory.openScenario();val base=source.unitDefinitions.getValue("tank")
        fun open()=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
            map=MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,BooleanArray(600))),
            unitDefinitions=mapOf("tank" to TestContentFactory.unitWith(base,movement=profile(1.1,3.0,.01))),
            units=listOf(UnitSpawn("u1","p1","tank",Position(150.0,150.0),90.0))))).context
        val measured=open();val plain=open()
        try {
            measured.enableMotionDiagnostics(measured.world)
            for(c in listOf(measured,plain))c.submit(MoveAction("old","p1","u1",Position(450.0,250.0),c.world))
            for(q in 0L..50L) {
                if(q==10L)for(c in listOf(measured,plain)) {
                    c.submit(StopAction("stop","p1","u1",c.world,atStep=q))
                    c.submit(MoveAction("new","p1","u1",Position(450.0,100.0),c.world,atStep=q))
                    val before=c.observe();val local=c.localMotionSnapshots(c.world)
                    assertFailsWith<IllegalArgumentException> { c.advance(Step(q,.5,"sim-tick")) }
                    assertSame(before,c.observe());assertEquals(local,c.localMotionSnapshots(c.world))
                }
                val before=measured.observe().units.single()
                val a=measured.advance(Step(q,1.0,"sim-tick"));val b=plain.advance(Step(q,1.0,"sim-tick"))
                assertEquals(a.units,b.units);assertEquals(a.events,b.events);assertEquals(a.actionResults,b.actionResults)
                assertEquals(measured.observePlayer(measured.world,"p1").units,plain.observePlayer(plain.world,"p1").units)
                assertEquals(a.units.single().position,measured.drainMotionDiagnostics(measured.world).single().finalPosition)
                if(q==10L) {
                    val local=measured.localMotionSnapshots(measured.world).getValue("u1")
                    assertEquals("new",local.commandId);assertEquals(before.position,local.origin)
                    assertEquals(Position(450.0,100.0),local.aim)
                    assertTrue(a.actionResults.any { it.actionId=="stop" });assertTrue(a.actionResults.any { it.actionId=="new" })
                }
            }
        } finally { measured.close();plain.close() }
    }

    @Test fun original_spacing_and_zero_or_extreme_parameters_keep_finite_immutable_segments() {
        val origin=Position(50.0,50.0);val goal=Position(250.0,50.0)
        for(p in listOf(profile(1.1,4.1,.25),profile(1.1,4.1,0.0),profile(0.0,0.0,0.0),
            profile(Double.MIN_VALUE,1.0,Double.MAX_VALUE))) {
            var old:LocalMotionSegment?=LocalMotionSegment.create(origin,goal)
            var current:LocalMotionSegment?=LocalMotionSegment.create(origin,goal,p)
            while(old!=null) {
                assertEquals(old.aim,assertNotNull(current).aim)
                assertEquals(old.isLast,current.isLast)
                old=old.next();current=current.next()
            }
            assertNull(current)
        }
        for(p in listOf(profile(1.1,3.0,.01),profile(1.1,0.0,.01),
            profile(Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE),
            profile(Double.MAX_VALUE,Double.MIN_VALUE,0.0))) {
            val segment=LocalMotionSegment.create(origin,goal,p)
            assertEquals(goal,segment.aim);assertTrue(segment.isLast);assertNull(segment.next())
            assertEquals(origin,LocalMotionSegment.create(origin,origin,p).aim)
        }
        val subnormal=LocalMotionSegment.create(origin,goal,profile(Double.MIN_VALUE,Double.MIN_VALUE,0.0))
        assertEquals(50.0+180.0/Math.PI,subnormal.aim.x,1e-12)
        val faster=LocalMotionSegment.create(origin,goal,profile(2.2,4.1,.25))
        assertEquals(122.16,faster.aim.x,1e-12)
        assertEquals(158.24,assertNotNull(faster.next()).aim.x,1e-12)
        assertEquals(122.16,faster.aim.x,1e-12)
    }

    @Test fun different_motion_abilities_complete_a_turning_move_and_keep_the_segment_frozen_until_consumed() {
        for(p in listOf(profile(.55,1.5,.005),profile(1.1,3.0,.01),profile(2.2,6.0,.04),profile(1.1,4.1,0.0),profile(2.2,4.1,.25))) {
            val source=TestContentFactory.openScenario();val base=source.unitDefinitions.getValue("tank")
            val context=assertIs<InitResult.Accepted>(SimulationContext.initialize(source.copy(
                map=MapDefinition(30,20,20,20,emptyList(),IntArray(600),BlockingGrid(30,20,BooleanArray(600))),
                unitDefinitions=mapOf("tank" to TestContentFactory.unitWith(base,movement=p)),
                units=listOf(UnitSpawn("u1","p1","tank",Position(150.0,150.0),90.0))))).context
            try {
                val goal=Position(450.0,250.0)
                context.submit(MoveAction("move","p1","u1",goal,context.world))
                var arrived=false;var stable=0;var previous=context.observe().units.single()
                var first:LocalMotionSnapshot?=null
                var previousLocal:LocalMotionSnapshot?=null
                var adaptivePointConsumed=false
                for(q in 0L until 2500L) {
                    val observation=context.advance(Step(q,1.0,"sim-tick"));val now=observation.units.single()
                    if(q==0L)first=context.localMotionSnapshots(context.world).getValue("u1")
                    val local=context.localMotionSnapshots(context.world)["u1"]
                    if(p.moveSpeed==2.2 && p.maxTurnSpeed==4.1 && local!=null && previousLocal!=null &&
                        local.origin==previousLocal.origin && local.index>previousLocal.index && local.index<=local.intermediateCount) {
                        adaptivePointConsumed=true
                        assertEquals(36.08*local.index,kotlin.math.hypot(local.aim.x-local.origin.x,local.aim.y-local.origin.y),1e-10)
                    }
                    previousLocal=local
                    if(q<20L && p.turnAcceleration>0.0) {
                        assertEquals(first!!.origin,assertNotNull(local).origin);assertEquals(first.aim,local.aim)
                    }
                    assertTrue(now.position.x in 11.0..589.0 && now.position.y in 11.0..389.0)
                    if(observation.events.any { it.kind=="movement-arrived" })arrived=true
                    assertTrue(observation.events.none { it.kind in setOf("path-unreachable","movement-boundary-blocked") })
                    if(arrived) {
                        assertNull(now.moveTarget);assertTrue(now.pathPoints.isEmpty())
                        assertEquals(previous.heading,now.heading);assertEquals(previous.angularVelocity,now.angularVelocity)
                        assertEquals(maxOf(0.0,previous.speed-.02*p.moveSpeed),now.speed,1e-12)
                        stable=if(now.speed==0.0 && now.position==previous.position)stable+1 else 0
                        if(stable>=19)break
                    }
                    previous=now
                }
                assertTrue(arrived,"No normal completion for $p");assertTrue(stable>=19,"Missing stable tail for $p")
                if(p.moveSpeed==2.2 && p.maxTurnSpeed==4.1)assertTrue(adaptivePointConsumed,"Adaptive intermediate point was never consumed")
            } finally { context.close() }
        }
    }
}
