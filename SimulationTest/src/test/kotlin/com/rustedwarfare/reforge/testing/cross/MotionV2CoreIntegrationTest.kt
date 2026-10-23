package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.test.*

/** Pure typed record controls; Observation pairing and runtime qualification are separate. */
class MotionV2CoreIntegrationTest {
    private val profile=MotionV2CoreIntegration.Profile(1.1,.07,.17,4.1,.25)
    private fun first()=MotionStepSample("w","u1","p1","tank",1,0.0,1.0,"m0",
        Position(100.0,100.0),90.0,0.0,0.0,Position(400.0,100.0),false,
        true,Position(400.0,100.0),true,0.0,1.0,90.0,0.07700000000000001,
        Position(100.077,100.0),Position(100.077,100.0),MotionTerrainDisposition.DIRECT,
        Position(100.077,100.0),90.0,0.07700000000000001,0.0,0.0,1.0,0.0,0.0)

    @Test fun first_proportional_acceleration_rejects_consistent_but_wrong_world_speed() {
        val result=MotionV2CoreIntegration.assess(first(),profile,mathRuntimeQualified=true)
        val expected=assertNotNull(result.expected)
        assertEquals(0.07700000000000001.toBits(),expected.speed.toBits())
        assertEquals(100.077.toBits(),expected.candidate.x.toBits())
        assertEquals(100.0.toBits(),expected.candidate.y.toBits())
        assertTrue(result.issues.isEmpty())
        // All affected typed copies agree. This is a behavior fault rather than a copy control.
        val bad=first().copy(integratedSpeed=.07,finalSpeed=.07,candidate=Position(100.07,100.0),
            terrainResolved=Position(100.07,100.0),finalPosition=Position(100.07,100.0))
        val failed=MotionV2CoreIntegration.assess(bad,profile,mathRuntimeQualified=true)
        assertTrue(failed.issues.any { it.status==MotionV2Findings.Status.FAIL && it.code=="INTEGRATED_SPEED" })
        assertTrue(failed.issues.any { it.status==MotionV2Findings.Status.FAIL && it.code=="INTEGRATED_CANDIDATE" })
        assertTrue(failed.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
    }

    private fun cleared()=first().copy(commandTargetBefore=Position(106.0,100.0),normalCompletion=true,
        steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null,speedBefore=1.1,
        angularVelocityBefore=2.25,targetFraction=0.0,integratedSpeed=.913,candidate=Position(100.913,100.0),
        terrainResolved=Position(100.913,100.0),finalPosition=Position(100.913,100.0),finalSpeed=.913,finalAngularVelocity=2.25)

    @Test fun unbound_math_keeps_known_braking_failures_and_normal_eligibility_insufficient() {
        val good=MotionV2CoreIntegration.assess(cleared(),profile)
        assertTrue(good.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
        assertTrue(good.issues.any { it.code=="NORMAL_COMPLETION_ELIGIBILITY_NOT_CHECKED" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        assertTrue(good.issues.any { it.code=="INTEGRATION_MATH_RUNTIME_UNBOUND" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        val bad=cleared().copy(integratedSpeed=1.1,finalSpeed=1.1,candidate=Position(101.1,100.0),
            terrainResolved=Position(101.1,100.0),finalPosition=Position(101.1,100.0),finalAngularVelocity=0.0)
        val failed=MotionV2CoreIntegration.assess(bad,profile)
        assertTrue(failed.issues.any { it.code=="INTEGRATED_SPEED" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(failed.issues.any { it.code=="INTEGRATED_ANGULAR_VELOCITY" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(failed.issues.any { it.code=="INTEGRATION_MATH_RUNTIME_UNBOUND" && it.status==MotionV2Findings.Status.INSUFFICIENT })
    }

    @Test fun turning_uses_pre_turn_difference_and_only_strict_overshoot_clears_angular_speed() {
        val start=Position(100.0,100.0)
        val north=first().copy(commandTargetBefore=Position(100.0,20.0),steeringAim=Position(100.0,20.0),
            steeringDifference=-90.0,targetFraction=0.0,integratedHeading=89.75,integratedSpeed=0.0,
            candidate=start,terrainResolved=start,finalPosition=start,finalHeading=89.75,finalSpeed=0.0,finalAngularVelocity=-.25)
        val good=MotionV2CoreIntegration.assess(north,profile,true)
        assertTrue(good.issues.isEmpty())
        assertEquals(-.25,assertNotNull(good.expected).angularVelocity)
        val bad=MotionV2CoreIntegration.assess(north.copy(finalAngularVelocity=0.0),profile,true)
        assertTrue(bad.issues.any { it.code=="INTEGRATED_ANGULAR_VELOCITY" && it.status==MotionV2Findings.Status.FAIL })
        for((before,after) in listOf(89.0 to 90.0,90.0 to 0.0)) {
            val turn=first().copy(headingBefore=0.0,angularVelocityBefore=before,steeringDifference=90.0,targetFraction=0.0,
                integratedSpeed=0.0,candidate=start,terrainResolved=start,finalPosition=start,finalSpeed=0.0,finalAngularVelocity=after)
            assertTrue(MotionV2CoreIntegration.assess(turn,profile.copy(turnSpeed=100.0,turnAcceleration=1.0),true).issues.isEmpty())
        }
        val aligned=MotionV2CoreIntegration.assess(first().copy(angularVelocityBefore=2.25,finalAngularVelocity=2.25),profile,true)
        assertTrue(aligned.issues.isEmpty()) // abs(diff)<.01 keeps the residual cg.
    }

    @Test fun zero_max_speed_does_not_force_fraction_zero_and_overflowing_amount_can_saturate() {
        val start=Position(100.0,100.0)
        val zero=first().copy(integratedSpeed=0.0,candidate=start,terrainResolved=start,finalPosition=start,finalSpeed=0.0)
        val stationary=MotionV2CoreIntegration.assess(zero,profile.copy(speed=0.0),true)
        assertTrue(stationary.issues.isEmpty())
        assertEquals(1.0,assertNotNull(stationary.expected).fraction)
        val huge=first().copy(headingBefore=0.0,commandTargetBefore=Position(100.0,20.0),steeringAim=Position(100.0,20.0),
            integratedHeading=0.0,integratedSpeed=Double.MAX_VALUE,candidate=Position(100.0,-Double.MAX_VALUE),
            terrainResolved=null,terrainDisposition=MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP,
            finalPosition=start,finalHeading=0.0,finalSpeed=0.0)
        val finite=MotionV2CoreIntegration.assess(huge,profile.copy(speed=Double.MAX_VALUE,acceleration=Double.MAX_VALUE),true)
        assertTrue(finite.issues.isEmpty())
        assertEquals(Double.MAX_VALUE,assertNotNull(finite.expected).speed)
    }

    @Test fun final_180_degree_gate_overrides_low_turn_limit_with_zero_turn_acceleration() {
        val raw=first().copy(headingBefore=270.0,speedBefore=.5,angularVelocityBefore=2.25,steeringDifference=-180.0,
            integratedHeading=270.0,integratedSpeed=.577,candidate=Position(99.423,100.0),terrainResolved=Position(99.423,100.0),
            finalPosition=Position(99.423,100.0),finalHeading=270.0,finalSpeed=.577,finalAngularVelocity=2.25)
        val good=MotionV2CoreIntegration.assess(raw,profile.copy(turnSpeed=0.0,turnAcceleration=0.0),true)
        assertTrue(good.issues.isEmpty())
        assertEquals(1.0,assertNotNull(good.expected).fraction)
        val wrong=MotionV2CoreIntegration.assess(raw.copy(targetFraction=0.0),profile.copy(turnSpeed=0.0,turnAcceleration=0.0),true)
        assertTrue(wrong.issues.any { it.code=="PROPULSION_FRACTION" && it.status==MotionV2Findings.Status.FAIL })
    }

    @Test fun final_half_speed_gates_multiply_and_keep_the_169_and_324_boundaries_strict() {
        data class Vector(val aimX:Double,val fraction:Double,val speed:Double,val x:Double)
        val vectors=listOf(Vector(110.0,.25,.275,100.275),Vector(Math.nextDown(113.0),.25,.275,100.275),
            Vector(113.0,.5,.377,100.377),Vector(Math.nextDown(118.0),.5,.377,100.377),Vector(118.0,1.0,.377,100.377))
        val p=profile.copy(deceleration=.1)
        for(v in vectors) {
            val raw=first().copy(commandTargetBefore=Position(v.aimX,100.0),steeringAim=Position(v.aimX,100.0),
                speedBefore=.3,targetFraction=v.fraction,integratedSpeed=v.speed,candidate=Position(v.x,100.0),
                terrainResolved=Position(v.x,100.0),finalPosition=Position(v.x,100.0),finalSpeed=v.speed)
            val good=MotionV2CoreIntegration.assess(raw,p,true)
            assertTrue(good.issues.isEmpty(),v.toString())
            val wrongFraction=if(v.fraction==.25) .5 else .25
            val wrongSpeed=if(v.fraction==.25) .377 else .275
            val wrongX=if(v.fraction==.25) 100.377 else 100.275
            val bad=MotionV2CoreIntegration.assess(raw.copy(targetFraction=wrongFraction,integratedSpeed=wrongSpeed,
                finalSpeed=wrongSpeed,candidate=Position(wrongX,100.0),terrainResolved=Position(wrongX,100.0),finalPosition=Position(wrongX,100.0)),p,true)
            assertTrue(bad.issues.any { it.code=="PROPULSION_FRACTION" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(bad.issues.none { it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        }
    }

    @Test fun missing_profile_or_math_never_hides_independent_marker_failures() {
        val skipped=first().copy(steeringInvoked=false,steeringAim=null,finalLocalPoint=null,steeringDifference=null)
        for(p in listOf(profile,null)) {
            val bad=MotionV2CoreIntegration.assess(skipped,p)
            assertNull(bad.expected)
            assertTrue(bad.issues.any { it.code=="STEERING_INVOCATION" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(bad.issues.any { it.status==MotionV2Findings.Status.INSUFFICIENT })
        }
        assertTrue(MotionV2CoreIntegration.assess(first(),profile).issues.any { it.code=="INTEGRATION_MATH_RUNTIME_UNBOUND" })
        assertTrue(MotionV2CoreIntegration.assess(first(),profile.copy(speed=Double.NaN),true).issues.any {
            it.code=="INTEGRATION_PROFILE" && it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        val impossible=cleared().copy(commandTargetBefore=null)
        assertTrue(MotionV2CoreIntegration.assess(impossible,profile,true).issues.any {
            it.code=="NORMAL_WITHOUT_COMMAND" && it.status==MotionV2Findings.Status.FAIL })
    }
}
