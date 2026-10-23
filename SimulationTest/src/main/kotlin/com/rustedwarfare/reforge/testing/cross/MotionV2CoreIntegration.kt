package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position
import kotlin.math.abs

/** Scalar integration of paired calls only, not local aim choice or source admission. */
internal object MotionV2CoreIntegration {
    data class Profile(val speed: Double,val acceleration: Double,val deceleration: Double,val turnSpeed: Double,val turnAcceleration: Double)
    data class Expected(val fraction: Double,val heading: Double,val angularVelocity: Double,val speed: Double,val candidate: Position)
    data class Result(val expected: Expected?,val issues: List<MotionV2CoreAssessment.Issue>)
    fun assess(sample: MotionStepSample, profile: Profile?, mathRuntimeQualified: Boolean = false): Result {
        val issues=mutableListOf<MotionV2CoreAssessment.Issue>()
        fun add(domain:String,status:MotionV2Findings.Status,code:String)=issues.add(MotionV2CoreAssessment.Issue(domain,status,code,null))
        fun missing(code:String) { add("coverage",MotionV2Findings.Status.INSUFFICIENT,code) }
        fun behavior(ok:Boolean,code:String) { if(!ok) add("behavior",MotionV2Findings.Status.FAIL,code) }
        fun bits(actual:Double,expected:Double,code:String) { behavior(actual.toBits()==expected.toBits(),code) }
        val m=sample
        if(listOf(m.delta,m.headingBefore,m.speedBefore,m.angularVelocityBefore,m.positionBefore.x,m.positionBefore.y,
                m.integratedHeading,m.integratedSpeed,m.targetFraction,m.candidate.x,m.candidate.y,m.finalAngularVelocity).any { !it.isFinite() } || m.delta<=0 ||
            m.steeringAim?.let { !it.x.isFinite() || !it.y.isFinite() }==true || m.steeringDifference?.isFinite()==false ||
            if(m.steeringInvoked) m.steeringAim==null || m.finalLocalPoint==null || m.steeringDifference==null
            else m.steeringAim!=null || m.finalLocalPoint!=null || m.steeringDifference!=null) {
            add("source",MotionV2Findings.Status.EXECUTION_FAILED,"INTEGRATION_TYPED_INPUT");return Result(null,issues)
        }
        val shouldSteer=m.commandTargetBefore!=null && !m.normalCompletion
        behavior(m.steeringInvoked==shouldSteer,"STEERING_INVOCATION")
        behavior(!m.normalCompletion || m.commandTargetBefore!=null,"NORMAL_WITHOUT_COMMAND")
        if(m.normalCompletion) missing("NORMAL_COMPLETION_ELIGIBILITY_NOT_CHECKED")
        if(profile==null) { missing("INTEGRATION_PROFILE_MISSING");return Result(null,issues) }
        if(listOf(profile.speed,profile.acceleration,profile.deceleration,profile.turnSpeed,profile.turnAcceleration).any { !it.isFinite() || it<0.0 }) {
            add("source",MotionV2Findings.Status.EXECUTION_FAILED,"INTEGRATION_PROFILE");return Result(null,issues)
        }
        if(shouldSteer && !m.steeringInvoked) { missing("STEERING_AIM_MISSING");return Result(null,issues) }
        // This flag must be supplied by a separate qualified runtime source layer.
        // A raw record cannot assert it, and this assessor never grants that source qualification.
        if(!mathRuntimeQualified && shouldSteer) { missing("INTEGRATION_MATH_RUNTIME_UNBOUND");return Result(null,issues) }
        var heading=m.headingBefore;var angular=m.angularVelocityBefore;var fraction=0.0
        if(shouldSteer) {
            val aim=checkNotNull(m.steeringAim)
            val dx=aim.x-m.positionBefore.x;val dy=aim.y-m.positionBefore.y
            val d2=dx*dx+dy*dy
            val desired=wrap(Math.toDegrees(Math.atan2(dx,-dy)))
            val rawDiff=((desired-m.headingBefore+180.0)%360.0+360.0)%360.0-180.0
            val diff=if(abs(rawDiff)<.01) 0.0 else rawDiff
            if(abs(rawDiff)>=.01) {
                val sign=if(rawDiff<0.0) -1.0 else 1.0
                val turn=if(profile.turnAcceleration==0.0) {
                    val maximum=profile.turnSpeed*m.delta
                    sign*if(maximum<abs(rawDiff)) maximum else abs(rawDiff)
                } else {
                    val goal=sign*if(abs(rawDiff)<abs(angular)/profile.turnAcceleration) profile.turnAcceleration else profile.turnSpeed
                    angular=boundedStep(angular,goal,profile.turnAcceleration*m.delta)
                    val increment=angular*m.delta
                    if(abs(increment)>abs(rawDiff)) { angular=0.0;rawDiff } else increment
                }
                heading=wrap(m.headingBefore+turn)
            }
            bits(checkNotNull(m.steeringDifference),diff,"STEERING_DIFFERENCE")
            fraction=fraction(profile,m.speedBefore,d2,diff,checkNotNull(m.finalLocalPoint))
        }
        val target=profile.speed*fraction
        val rate=if(m.speedBefore<target) profile.acceleration else profile.deceleration
        // Do not reject an intermediate infinity: the specified clamp can still
        // saturate it to a finite target for a valid finite profile.
        val speed=boundedStep(m.speedBefore,target,(rate*profile.speed)*m.delta)
        bits(m.targetFraction,fraction,"PROPULSION_FRACTION")
        bits(m.integratedHeading,heading,"INTEGRATED_HEADING")
        bits(m.integratedSpeed,speed,"INTEGRATED_SPEED")
        val boundary=m.terrainDisposition in setOf(MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP,MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP)
        bits(m.finalAngularVelocity,if(boundary) 0.0 else angular,"INTEGRATED_ANGULAR_VELOCITY")
        if(!mathRuntimeQualified) { missing("INTEGRATION_MATH_RUNTIME_UNBOUND");return Result(null,issues) }
        val distance=speed*m.delta;val radians=Math.toRadians(heading)
        val candidate=Position(m.positionBefore.x+Math.sin(radians)*distance,m.positionBefore.y+-Math.cos(radians)*distance)
        behavior(m.candidate.x.toBits()==candidate.x.toBits() && m.candidate.y.toBits()==candidate.y.toBits(),"INTEGRATED_CANDIDATE")
        return Result(Expected(fraction,heading,angular,speed,candidate),issues)
    }
    private fun wrap(a:Double)=((a%360.0)+360.0)%360.0
    private fun boundedStep(current:Double,target:Double,amount:Double):Double {
        if(current<target) { val proposed=current+amount;return if(proposed>target) target else proposed }
        if(current>target) { val proposed=current-amount;return if(proposed<target) target else proposed }
        return current
    }
    private fun fraction(p:Profile,speed:Double,d2:Double,diff:Double,last:Boolean):Double {
        val ratio=if(p.speed==0.0) 0.0 else speed/p.speed
        var limit=when { d2>3600.0 -> 89.0;d2>361.0 -> 46.0;else -> 20.0 }
        if(p.turnSpeed<1.4) limit=if(d2>6400.0) limit*.5 else 17.0
        if(p.speed>5.0 && ratio<.01) limit=1.0
        if(p.turnSpeed<1.1) limit*=.7
        if(ratio>.4 && d2>16900.0) limit=180.0
        if(abs(diff)>limit || last && d2<16.0 || d2<9.0) return 0.0
        var value=1.0
        if(last) {
            if(d2<324.0 && p.deceleration<.13 && p.speed>1.0) value*=.5
            if(d2<169.0 && p.deceleration<.15 && p.speed>.9) value*=.5
            if(p.speed>5.0) {
                if(d2<324.0 && value>.5) value=.5
                if(d2<81.0 && value>.25) value=.25
            }
        }
        return value
    }
}
