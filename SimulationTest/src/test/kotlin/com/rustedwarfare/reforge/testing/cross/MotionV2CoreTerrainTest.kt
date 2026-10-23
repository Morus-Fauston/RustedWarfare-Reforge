package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Literal record controls. Faults injected here are not claimed as actual core faults. */
class MotionV2CoreTerrainTest {
    private fun grid(vararg walls: Pair<Int, Int>) = MotionV2CoreTerrain.Grid(30,20,20,20,
        walls.map { MotionV2CoreTerrain.Cell(it.first,it.second) }.toSet())
    private fun sample(start: Position, candidate: Position, resolved: Position? = candidate) = MotionStepSample(
        worldId="w",unitId="u1",ownerPlayerId="p1",typeId="tank",stepIndex=1,timeBefore=0.0,delta=1.0,originMoveId=null,
        positionBefore=start,headingBefore=90.0,speedBefore=1.0,angularVelocityBefore=2.25,commandTargetBefore=null,
        normalCompletion=false,steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null,
        targetFraction=0.0,integratedHeading=90.0,integratedSpeed=1.0,candidate=candidate,terrainResolved=resolved,
        terrainDisposition=if(resolved==candidate) MotionTerrainDisposition.DIRECT else MotionTerrainDisposition.CORRECTED,
        finalPosition=resolved ?: start,finalHeading=90.0,finalSpeed=1.0,finalAngularVelocity=2.25,
        localProgressBefore=0.0,localProgressAfter=0.0,correctionTimeBefore=0.0,correctionTimeAfter=0.0)
    private fun assertBits(expected: Position, actual: Position?) {
        assertNotNull(actual)
        assertEquals(expected.x.toBits(),actual.x.toBits(),"x")
        assertEquals(expected.y.toBits(),actual.y.toBits(),"y")
    }

    @Test fun edge_correction_round_trips_both_axes_and_rejects_synchronized_resolved_fault() {
        val literal=Position(279.8,200.09999999999997)
        val good=sample(Position(279.5,200.1),Position(280.5,200.1),literal)
        val result=MotionV2CoreTerrain.assess(good,grid(14 to 10),11.0)
        assertBits(literal,result.expectedResolved)
        assertEquals(MotionV2CoreTerrain.Branch.EDGE_CORRECTION,result.branch)
        assertTrue(result.issues.isEmpty())
        val mutated=good.copy(terrainResolved=Position(279.8,200.1),finalPosition=Position(279.8,200.1))
        val paired=pair(mutated)
        assertTrue(paired.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
        val bad=MotionV2CoreTerrain.assess(assertNotNull(paired.parsed),grid(14 to 10),11.0)
        assertTrue(bad.issues.any { it.domain=="behavior" && it.status==MotionV2Findings.Status.FAIL && it.code=="TERRAIN_RESOLVED" })
        assertTrue(bad.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
    }

    @Test fun multi_cell_guard_uses_exact_center_cells_and_closed_outer_edges() {
        data class Vector(val a: Position,val b: Position,val wall: Pair<Int,Int>,val hit: Boolean)
        val vectors=listOf(
            Vector(Position(600.0,10.0),Position(600.0,390.0),29 to 10,true),
            Vector(Position(10.0,400.0),Position(590.0,400.0),15 to 19,true),
            Vector(Position(10.0,50.0),Position(90.0,50.0),2 to 2,true),
            Vector(Position(10.0,70.0),Position(70.0,10.0),2 to 2,true),
            Vector(Position(10.0,60.0),Position(90.0,60.0),2 to 2,false),
            Vector(Position(60.0,10.0),Position(60.0,90.0),2 to 2,false),
            Vector(Position(13.0,16.0),Position(Math.nextDown(248.0),136.0),2 to 2,true),
            Vector(Position(13.0,16.0),Position(248.0,136.0),2 to 2,false),
            Vector(Position(13.0,16.0),Position(Math.nextUp(248.0),136.0),2 to 2,false))
        for(v in vectors) for((a,b) in listOf(v.a to v.b,v.b to v.a)) {
            val expected=if(v.hit) a else b
            val raw=sample(a,b,expected)
            val good=MotionV2CoreTerrain.assess(raw,grid(v.wall),0.0)
            assertBits(expected,good.expectedResolved)
            assertEquals(if(v.hit) MotionV2CoreTerrain.Branch.MULTI_BLOCKED else MotionV2CoreTerrain.Branch.MULTI_CLEAR,good.branch)
            assertTrue(good.issues.isEmpty(),"$v / $a -> $b")
            val wrong=if(v.hit) b else a
            val bad=MotionV2CoreTerrain.assess(raw.copy(terrainResolved=wrong,finalPosition=wrong),grid(v.wall),0.0)
            assertTrue(bad.issues.any { it.status==MotionV2Findings.Status.FAIL && it.code=="TERRAIN_RESOLVED" })
            assertTrue(bad.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
            val clear=MotionV2CoreTerrain.assess(sample(a,b),grid(),0.0)
            assertBits(b,clear.expectedResolved)
            assertTrue(clear.issues.isEmpty())
        }
    }

    @Test fun multi_cell_integer_grid_boundaries_do_not_round_through_binary64() {
        // The wall's exact right edge is 9007201009007201, one above this
        // representable x; rounding that edge to Double would exclude this line.
        val a=Position(9007201009007200.0,10.0);val b=Position(a.x,110.0)
        val terrain=MotionV2CoreTerrain.Grid(9007204,10,1000000001,20,setOf(MotionV2CoreTerrain.Cell(9007200,2)))
        val result=MotionV2CoreTerrain.assess(sample(a,b,a),terrain,0.0)
        assertBits(a,result.expectedResolved)
        assertEquals(MotionV2CoreTerrain.Branch.MULTI_BLOCKED,result.branch)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun adjacent_cell_order_does_not_inherit_original_old_wall_or_multi_cell_gates() {
        data class Vector(val a: Position,val b: Position,val walls: Set<Pair<Int,Int>>,val expected: Position,val branch: MotionV2CoreTerrain.Branch)
        val vectors=listOf(
            Vector(Position(281.0,201.0),Position(282.0,202.0),setOf(14 to 10),Position(282.0,202.0),MotionV2CoreTerrain.Branch.DIRECT),
            Vector(Position(281.0,201.0),Position(279.0,201.0),setOf(14 to 10),Position(279.0,201.0),MotionV2CoreTerrain.Branch.DIRECT),
            Vector(Position(279.0,199.0),Position(281.0,201.0),setOf(13 to 10,14 to 9),Position(281.0,201.0),MotionV2CoreTerrain.Branch.DIRECT),
            Vector(Position(279.0,199.0),Position(281.0,201.0),setOf(14 to 10),Position(279.8,201.0),MotionV2CoreTerrain.Branch.EDGE_CORRECTION),
            Vector(Position(279.0,199.0),Position(281.0,201.0),setOf(14 to 10,13 to 10),Position(281.0,199.8),MotionV2CoreTerrain.Branch.EDGE_CORRECTION),
            Vector(Position(279.0,199.0),Position(281.0,201.0),setOf(14 to 10,14 to 9),Position(279.8,201.0),MotionV2CoreTerrain.Branch.EDGE_CORRECTION),
            Vector(Position(279.0,199.0),Position(281.0,201.0),setOf(14 to 10,13 to 10,14 to 9),Position(279.0,199.0),MotionV2CoreTerrain.Branch.SUPPRESS_BOTH_SIDES),
            Vector(Position(279.0,201.0),Position(281.0,201.0),setOf(13 to 10,14 to 10),Position(279.0,201.0),MotionV2CoreTerrain.Branch.SUPPRESS_NO_EDGE),
            Vector(Position(599.0,200.0),Position(600.0,200.0),setOf(29 to 10),Position(600.0,200.0),MotionV2CoreTerrain.Branch.DIRECT))
        for(v in vectors) {
            val good=MotionV2CoreTerrain.assess(sample(v.a,v.b,v.expected),grid(*v.walls.toTypedArray()),0.0)
            assertBits(v.expected,good.expectedResolved)
            assertEquals(v.branch,good.branch)
            assertTrue(good.issues.isEmpty(),v.toString())
        }
        val scaled=MotionV2CoreTerrain.assess(sample(Position(31.5,8.1),Position(32.5,8.1),Position(31.68,8.1)),
            MotionV2CoreTerrain.Grid(10,10,32,16,setOf(MotionV2CoreTerrain.Cell(1,0))),0.0)
        assertBits(Position(31.68,8.1),scaled.expectedResolved)
        assertTrue(scaled.issues.isEmpty())
    }

    @Test fun signed_zero_is_preserved_for_raw_direct_and_suppressed_results() {
        val start=Position(-0.0,10.0);val end=Position(0.0,390.0)
        val blocked=MotionV2CoreTerrain.assess(sample(start,end,start),grid(0 to 10),0.0)
        assertBits(start,blocked.expectedResolved)
        assertTrue(blocked.issues.isEmpty())
        val bad=MotionV2CoreTerrain.assess(sample(start,end,Position(0.0,10.0)),grid(0 to 10),0.0)
        assertTrue(bad.issues.any { it.code=="TERRAIN_RESOLVED" && it.status==MotionV2Findings.Status.FAIL })
        val direct=MotionV2CoreTerrain.assess(sample(Position(0.0,10.0),Position(-0.0,11.0)),grid(0 to 0),0.0)
        assertBits(Position(-0.0,11.0),direct.expectedResolved)
        assertTrue(direct.issues.isEmpty())
    }

    @Test fun candidate_boundary_skips_resolver_but_second_boundary_keeps_real_resolved_value() {
        val raw=sample(Position(570.0,200.0),Position(590.0,200.0),null)
        val skipped=MotionV2CoreTerrain.assess(raw,grid(),11.0)
        assertNull(skipped.expectedResolved)
        assertEquals(MotionV2CoreTerrain.Branch.NOT_INVOKED,skipped.branch)
        assertTrue(skipped.issues.isEmpty())
        assertTrue(MotionV2CoreTerrain.assess(raw.copy(terrainResolved=raw.candidate),grid(),11.0).issues.any {
            it.status==MotionV2Findings.Status.FAIL && it.code=="TERRAIN_RESOLVED" })
        val actual=Position(580.2,200.0)
        val second=MotionV2CoreTerrain.assess(sample(Position(580.1,200.0),Position(579.9,200.0),actual),grid(28 to 10),19.9)
        assertBits(actual,second.expectedResolved)
        assertTrue(second.issues.isEmpty())
    }

    @Test fun missing_context_remains_insufficient_and_bad_geometry_is_execution_error() {
        val raw=sample(Position(270.0,200.0),Position(271.0,200.0))
        for((g,r) in listOf(grid() to null,null to 11.0)) {
            val missing=MotionV2CoreTerrain.assess(raw,g,r)
            assertNull(missing.branch)
            assertTrue(missing.issues.single().status==MotionV2Findings.Status.INSUFFICIENT)
        }
        for((s,g,r) in listOf(Triple(raw,grid().copy(width=0),11.0),Triple(raw,grid(30 to 0),11.0),
            Triple(raw,grid(),Double.NaN),Triple(raw,grid(),-1.0),
            Triple(raw.copy(candidate=Position(Double.POSITIVE_INFINITY,200.0)),grid(),11.0))) {
            val invalid=MotionV2CoreTerrain.assess(s,g,r)
            assertNull(invalid.branch)
            assertEquals(MotionV2Findings.Status.EXECUTION_FAILED,invalid.issues.single().status)
        }
    }

    private fun pair(m: MotionStepSample): MotionV2CoreMotion.Result {
        fun p(v: Position?) = v?.let { obj("x" to n(it.x),"y" to n(it.y)) } ?: JsonNull
        val raw=obj("worldId" to s(m.worldId),"unitId" to s(m.unitId),"ownerPlayerId" to s(m.ownerPlayerId),"typeId" to s(m.typeId),
            "stepIndex" to n(m.stepIndex),"timeBefore" to n(m.timeBefore),"delta" to n(m.delta),"originMoveId" to JsonNull,
            "positionBefore" to p(m.positionBefore),"headingBefore" to n(m.headingBefore),"speedBefore" to n(m.speedBefore),
            "angularVelocityBefore" to n(m.angularVelocityBefore),"commandTargetBefore" to JsonNull,"normalCompletion" to b(false),
            "steeringInvoked" to b(false),"steeringAim" to JsonNull,"finalLocalPoint" to JsonNull,"steeringDifference" to JsonNull,
            "targetFraction" to n(m.targetFraction),"integratedHeading" to n(m.integratedHeading),"integratedSpeed" to n(m.integratedSpeed),
            "candidate" to p(m.candidate),"terrainResolved" to p(m.terrainResolved),"terrainDisposition" to s(m.terrainDisposition.name),
            "finalPosition" to p(m.finalPosition),"finalHeading" to n(m.finalHeading),"finalSpeed" to n(m.finalSpeed),
            "finalAngularVelocity" to n(m.finalAngularVelocity),"localProgressBefore" to n(m.localProgressBefore),
            "localProgressAfter" to n(m.localProgressAfter),"correctionTimeBefore" to n(m.correctionTimeBefore),"correctionTimeAfter" to n(m.correctionTimeAfter))
        fun observation(post: Boolean)=obj("worldId" to s(m.worldId),"stepIndex" to n(if(post) 1 else 0),
            "simulationTime" to n(if(post) 1 else 0),"events" to array(emptyList()),"units" to array(listOf(obj(
                "unitId" to s(m.unitId),"ownerPlayerId" to s(m.ownerPlayerId),"typeId" to s(m.typeId),
                "position" to p(if(post) m.finalPosition else m.positionBefore),"heading" to n(if(post) m.finalHeading else m.headingBefore),
                "speed" to n(if(post) m.finalSpeed else m.speedBefore),"angularVelocity" to n(if(post) m.finalAngularVelocity else m.angularVelocityBefore),
                "moveTarget" to JsonNull,"pathPoints" to array(emptyList()),"movementState" to s("DECELERATING")))))
        return MotionV2CoreMotion.assess(raw,observation(false),observation(true),MotionV2CoreMotion.Boundary(600.0,400.0,11.0))
    }
}
