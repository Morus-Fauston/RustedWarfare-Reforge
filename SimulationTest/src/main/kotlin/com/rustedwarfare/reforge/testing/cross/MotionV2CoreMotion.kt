package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.core.sim.MotionStepSample
import com.rustedwarfare.reforge.core.sim.MotionTerrainDisposition
import com.rustedwarfare.reforge.core.sim.Position
import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*

/** Typed F32 and D-083/writeback only. No source admission, integrator or terrain oracle grant. */
internal object MotionV2CoreMotion {
    data class Boundary(val width: Double, val height: Double, val radius: Double)
    data class Result(val parsed: MotionStepSample?, val issues: List<MotionV2CoreAssessment.Issue>)
    private val fields = setOf("worldId", "unitId", "ownerPlayerId", "typeId", "stepIndex", "timeBefore", "delta", "originMoveId",
        "positionBefore", "headingBefore", "speedBefore", "angularVelocityBefore", "commandTargetBefore", "normalCompletion",
        "steeringInvoked", "steeringAim", "finalLocalPoint", "steeringDifference", "targetFraction", "integratedHeading", "integratedSpeed",
        "candidate", "terrainResolved", "terrainDisposition", "finalPosition", "finalHeading", "finalSpeed", "finalAngularVelocity",
        "localProgressBefore", "localProgressAfter", "correctionTimeBefore", "correctionTimeAfter")
    private fun source(ok: Boolean, detail: String) { if (!ok) throw InputError(detail) }
    private fun flag(v: JsonObject, key: String): Boolean {
        val p = v.required(key) as? JsonPrimitive ?: throw InputError("Expected boolean: $key")
        source(!p.isString && p.booleanOrNull != null,"Expected boolean: $key")
        return p.boolean
    }
    private fun point(v: JsonElement): Position {
        val p = v as? JsonObject ?: throw InputError("Expected position")
        source(p.keys==setOf("x","y"),"Position fields")
        return Position(p.number("x"),p.number("y"))
    }
    private fun optionalPoint(v: JsonElement) = if(v==JsonNull) null else point(v)
    private fun same(a: Position?, b: Position?): Boolean = if(a==null || b==null) a==b else
        a.x.toBits()==b.x.toBits() && a.y.toBits()==b.y.toBits()
    private fun parse(v: JsonObject): MotionStepSample {
        source(v.keys==fields,"Exact F32 fields")
        val origin = if(v.required("originMoveId")==JsonNull) null else v.text("originMoveId")
        val finalPoint = if(v.required("finalLocalPoint")==JsonNull) null else flag(v,"finalLocalPoint")
        val difference = if(v.required("steeringDifference")==JsonNull) null else v.number("steeringDifference")
        val invoked = flag(v,"steeringInvoked"); val aim = optionalPoint(v.required("steeringAim"))
        source(if(invoked) aim!=null && finalPoint!=null && difference!=null else aim==null && finalPoint==null && difference==null,
            "Steering nullable fields")
        val disposition = v.text("terrainDisposition")
        source(disposition in setOf("DIRECT","CORRECTED","CANDIDATE_OUTSIDE_MAP","RESOLVED_OUTSIDE_MAP"),"Terrain enum")
        return MotionStepSample(v.text("worldId"),v.text("unitId"),v.text("ownerPlayerId"),v.text("typeId"),
            v.integer("stepIndex"),v.number("timeBefore"),v.number("delta"),origin,
            point(v.required("positionBefore")),v.number("headingBefore"),v.number("speedBefore"),v.number("angularVelocityBefore"),
            optionalPoint(v.required("commandTargetBefore")),flag(v,"normalCompletion"),invoked,aim,finalPoint,difference,
            v.number("targetFraction"),v.number("integratedHeading"),v.number("integratedSpeed"),point(v.required("candidate")),
            optionalPoint(v.required("terrainResolved")),MotionTerrainDisposition.valueOf(disposition),
            point(v.required("finalPosition")),v.number("finalHeading"),v.number("finalSpeed"),v.number("finalAngularVelocity"),
            v.number("localProgressBefore"),v.number("localProgressAfter"),v.number("correctionTimeBefore"),v.number("correctionTimeAfter"))
    }
    private fun unit(observation: JsonObject, m: MotionStepSample): JsonObject {
        val units = observation.arrayAt("units"); source(units.size==1,"Expected one paired unit")
        return units.single().jsonObject.also {
            source(it.text("unitId")==m.unitId && it.text("ownerPlayerId")==m.ownerPlayerId && it.text("typeId")==m.typeId,"F32 unit identity")
        }
    }

    fun assess(raw: JsonObject, before: JsonObject, after: JsonObject, boundary: Boundary?): Result {
        val issues = mutableListOf<MotionV2CoreAssessment.Issue>()
        val m: MotionStepSample
        val post: JsonObject
        try {
            m = parse(raw)
            source(m.stepIndex>=0 && m.timeBefore>=0 && m.delta>0,"Invocation domain")
            source(before.text("worldId")==m.worldId && after.text("worldId")==m.worldId &&
                before.number("simulationTime")==m.timeBefore && after.number("simulationTime")==m.timeBefore+m.delta &&
                before.integer("stepIndex")==maxOf(0L,m.stepIndex-1L) && after.integer("stepIndex")==m.stepIndex,"F32 invocation pairing")
            val pre = unit(before,m); post = unit(after,m)
            source(same(point(pre.required("position")),m.positionBefore) &&
                same(optionalPoint(pre.required("moveTarget")),m.commandTargetBefore) &&
                pre.number("heading").toBits()==m.headingBefore.toBits() && pre.number("speed").toBits()==m.speedBefore.toBits() &&
                pre.number("angularVelocity").toBits()==m.angularVelocityBefore.toBits(),"F32 pre observation copy")
            source(same(point(post.required("position")),m.finalPosition) && post.number("heading").toBits()==m.finalHeading.toBits() &&
                post.number("speed").toBits()==m.finalSpeed.toBits() && post.number("angularVelocity").toBits()==m.finalAngularVelocity.toBits(),
                "F32 final observation copy")
            optionalPoint(post.required("moveTarget")); post.arrayAt("pathPoints").forEach(::point)
            source(post.text("movementState") in setOf("IDLE","MOVING","DECELERATING","BLOCKED"),"Movement state")
            for(e in after.arrayAt("events")) { e.jsonObject.text("unitId"); e.jsonObject.text("kind") }
            if(boundary!=null) source(boundary.width.isFinite() && boundary.height.isFinite() && boundary.radius.isFinite() &&
                boundary.width>0 && boundary.height>0 && boundary.radius>=0,"Boundary metadata")
        } catch(error: RuntimeException) {
            if(error !is InputError && error !is IllegalArgumentException && error !is IllegalStateException) throw error
            return Result(null,listOf(MotionV2CoreAssessment.Issue("source",MotionV2Findings.Status.EXECUTION_FAILED,
                "F32_PAIRING",null,error.message ?: error.javaClass.name)))
        }
        fun behavior(ok: Boolean, code: String) {
            if(!ok) issues += MotionV2CoreAssessment.Issue("behavior",MotionV2Findings.Status.FAIL,code,null)
        }
        fun missing(code: String) { issues += MotionV2CoreAssessment.Issue("coverage",MotionV2Findings.Status.INSUFFICIENT,code,null) }
        if(!m.steeringInvoked) behavior(m.integratedHeading.toBits()==m.headingBefore.toBits(),"NO_STEERING_HEADING")
        if(boundary==null) missing("BOUNDARY_CONTEXT_MISSING") else {
            fun inside(p: Position) = p.x>=boundary.radius && p.y>=boundary.radius &&
                p.x<=boundary.width-boundary.radius && p.y<=boundary.height-boundary.radius
            fun boundaryWriteback() {
                behavior(same(m.finalPosition,m.positionBefore) && m.finalSpeed.toBits()==0.0.toBits() &&
                    m.finalAngularVelocity.toBits()==0.0.toBits() && m.finalHeading.toBits()==m.integratedHeading.toBits() &&
                    post.required("moveTarget")==JsonNull && post.arrayAt("pathPoints").isEmpty() && post.text("movementState")=="BLOCKED" &&
                    after.arrayAt("events").count { it.jsonObject.text("unitId")==m.unitId &&
                        it.jsonObject.text("kind")=="movement-boundary-blocked" }==1,"BOUNDARY_WRITEBACK")
            }
            if(!inside(m.candidate)) {
                behavior(m.terrainDisposition==MotionTerrainDisposition.CANDIDATE_OUTSIDE_MAP && m.terrainResolved==null,"BOUNDARY_BRANCH")
                boundaryWriteback()
            } else {
                val resolved = m.terrainResolved
                if(resolved==null) behavior(false,"TERRAIN_RESOLVED_MISSING") else if(!inside(resolved)) {
                    behavior(m.terrainDisposition==MotionTerrainDisposition.RESOLVED_OUTSIDE_MAP,"BOUNDARY_BRANCH")
                    boundaryWriteback()
                } else {
                    val expected = if(same(resolved,m.candidate)) MotionTerrainDisposition.DIRECT else MotionTerrainDisposition.CORRECTED
                    behavior(m.terrainDisposition==expected,"TERRAIN_DISPOSITION")
                    behavior(same(m.finalPosition,resolved) && m.finalHeading.toBits()==m.integratedHeading.toBits() &&
                        m.finalSpeed.toBits()==m.integratedSpeed.toBits(),"TERRAIN_WRITEBACK")
                    if(!m.steeringInvoked) behavior(m.finalAngularVelocity.toBits()==m.angularVelocityBefore.toBits(),"NO_STEERING_MOTION")
                }
                missing("TERRAIN_RESOLVER_NOT_CHECKED")
            }
        }
        missing("MOTION_INTEGRATION_NOT_CHECKED"); missing("LOCAL_HISTORY_NOT_CHECKED")
        return Result(m,issues)
    }
}
