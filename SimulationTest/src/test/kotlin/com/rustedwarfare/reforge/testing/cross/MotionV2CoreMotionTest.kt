package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure record controls; these do not claim the real core produced the injected faults. */
class MotionV2CoreMotionTest {
    private fun position(x: Number) = obj("x" to n(x), "y" to n(200))
    private fun observation(after: Boolean, broken: Boolean = false): JsonObject {
        val target = if (!after || broken) position(589) else JsonNull
        return obj("worldId" to s("w"), "stepIndex" to n(if(after) 1 else 0), "simulationTime" to n(if(after) 2 else 1),
            "units" to array(listOf(obj("unitId" to s("u1"), "ownerPlayerId" to s("p1"), "typeId" to s("tank"),
                "position" to position(if(after && broken) 590 else 570), "heading" to n(90),
                "speed" to n(if(after && !broken) 0 else 20), "angularVelocity" to n(0),
                "moveTarget" to target, "pathPoints" to array(if(target==JsonNull) emptyList() else listOf(target)),
                "movementState" to s(if(after && !broken) "BLOCKED" else "MOVING")))),
            "events" to array(if(after && !broken) listOf(obj("kind" to s("movement-boundary-blocked"), "unitId" to s("u1"))) else emptyList()))
    }
    private fun motion(broken: Boolean) = obj(
        "worldId" to s("w"), "unitId" to s("u1"), "ownerPlayerId" to s("p1"), "typeId" to s("tank"),
        "stepIndex" to n(1), "timeBefore" to n(1), "delta" to n(1), "originMoveId" to s("v2.0"),
        "positionBefore" to position(570), "headingBefore" to n(90), "speedBefore" to n(20), "angularVelocityBefore" to n(0),
        "commandTargetBefore" to position(589), "normalCompletion" to b(false),
        "steeringInvoked" to b(true), "steeringAim" to position(589), "finalLocalPoint" to b(true), "steeringDifference" to n(0),
        "targetFraction" to n(1), "integratedHeading" to n(90), "integratedSpeed" to n(20), "candidate" to position(590),
        "terrainResolved" to if(broken) position(590) else JsonNull,
        "terrainDisposition" to s(if(broken) "DIRECT" else "CANDIDATE_OUTSIDE_MAP"),
        "finalPosition" to position(if(broken) 590 else 570), "finalHeading" to n(90), "finalSpeed" to n(if(broken) 20 else 0),
        "finalAngularVelocity" to n(0), "localProgressBefore" to n(0), "localProgressAfter" to n(0),
        "correctionTimeBefore" to n(0), "correctionTimeAfter" to n(0))

    @Test fun synchronized_outside_candidate_write_is_behavior_failure_not_a_copy_error() {
        val bounds = MotionV2CoreMotion.Boundary(600.0,400.0,11.0)
        val good = MotionV2CoreMotion.assess(motion(false), observation(false), observation(true), bounds)
        assertNotNull(good.parsed)
        assertTrue(good.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL, MotionV2Findings.Status.EXECUTION_FAILED) })
        val bad = MotionV2CoreMotion.assess(motion(true), observation(false), observation(true,true), bounds)
        assertNotNull(bad.parsed)
        assertTrue(bad.issues.any { it.domain=="behavior" && it.status==MotionV2Findings.Status.FAIL && it.code=="BOUNDARY_WRITEBACK" })
        assertTrue(bad.issues.none { it.domain=="source" })
    }

    @Test fun malformed_late_fields_and_observation_copies_do_not_authorize_this_calls_behavior() {
        val original = motion(true)
        val variants = listOf(JsonObject(original - "correctionTimeAfter"),
            JsonObject(original + ("correctionTimeAfter" to s("0"))),
            JsonObject(original + ("correctionTimeAfter" to JsonPrimitive(Double.NaN))),
            JsonObject(original + ("terrainDisposition" to s("UNKNOWN"))),
            JsonObject(original + ("steeringInvoked" to b(false))),
            JsonObject(original + ("unexpected" to n(1))),
            JsonObject(original + ("finalSpeed" to n(19))))
        for(raw in variants) {
            val result = MotionV2CoreMotion.assess(raw,observation(false),observation(true,true),MotionV2CoreMotion.Boundary(600.0,400.0,11.0))
            assertNull(result.parsed)
            assertTrue(result.issues.any { it.domain=="source" && it.status==MotionV2Findings.Status.EXECUTION_FAILED })
            assertTrue(result.issues.none { it.domain=="behavior" })
        }
    }

    private fun withUnit(observation: JsonObject, changes: Map<String,JsonElement>) = JsonObject(observation +
        ("units" to array(listOf(JsonObject(observation.arrayAt("units").single().jsonObject+changes)))))

    @Test fun second_boundary_stage_preserves_the_actual_resolved_position_and_blocks_writeback() {
        val raw = JsonObject(motion(false) + mapOf("positionBefore" to position(580.1),"headingBefore" to n(270),
            "speedBefore" to n(.2),"commandTargetBefore" to position(40),"steeringAim" to position(40),
            "integratedHeading" to n(270),"integratedSpeed" to n(.2),"candidate" to position(579.9),
            "terrainResolved" to position(580.2),"terrainDisposition" to s("RESOLVED_OUTSIDE_MAP"),
            "finalPosition" to position(580.1),"finalHeading" to n(270)))
        val before = withUnit(observation(false),mapOf("position" to position(580.1),"heading" to n(270),"speed" to n(.2),
            "moveTarget" to position(40),"pathPoints" to array(listOf(position(40)))))
        val after = withUnit(observation(true),mapOf("position" to position(580.1),"heading" to n(270)))
        val bounds = MotionV2CoreMotion.Boundary(600.0,400.0,19.9)
        val good = MotionV2CoreMotion.assess(raw,before,after,bounds)
        assertEquals(580.2,assertNotNull(assertNotNull(good.parsed).terrainResolved).x)
        assertTrue(good.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
        val badRaw = JsonObject(raw + mapOf("finalPosition" to position(580.2),"finalSpeed" to n(.2)))
        val badAfter = JsonObject(withUnit(after,mapOf("position" to position(580.2),"speed" to n(.2),"moveTarget" to position(40),
            "pathPoints" to array(listOf(position(40))),"movementState" to s("MOVING"))) + ("events" to array(emptyList())))
        val bad = MotionV2CoreMotion.assess(badRaw,before,badAfter,bounds)
        assertNotNull(bad.parsed)
        assertTrue(bad.issues.any { it.code=="BOUNDARY_WRITEBACK" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(bad.issues.none { it.domain=="source" })
    }

    @Test fun missing_map_context_keeps_typed_facts_and_explicit_insufficiency() {
        val result = MotionV2CoreMotion.assess(motion(true),observation(false),observation(true,true),null)
        assertNotNull(result.parsed)
        assertTrue(result.issues.any { it.code=="BOUNDARY_CONTEXT_MISSING" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        assertTrue(result.issues.none { it.status==MotionV2Findings.Status.FAIL })
    }

    @Test fun no_steering_heading_law_applies_before_boundary_and_without_map_context() {
        val raw = JsonObject(motion(false) + mapOf("steeringInvoked" to b(false),"steeringAim" to JsonNull,
            "finalLocalPoint" to JsonNull,"steeringDifference" to JsonNull,"integratedHeading" to n(91),"finalHeading" to n(91)))
        val after = withUnit(observation(true),mapOf("heading" to n(91)))
        for(bounds in listOf(MotionV2CoreMotion.Boundary(600.0,400.0,11.0),null)) {
            val result = MotionV2CoreMotion.assess(raw,observation(false),after,bounds)
            assertNotNull(result.parsed)
            assertTrue(result.issues.any { it.code=="NO_STEERING_HEADING" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.none { it.domain=="source" })
        }
    }

    @Test fun legal_direct_and_corrected_writeback_preserve_nonzero_cg_and_reject_synchronized_faults() {
        for(corrected in listOf(false,true)) {
            val start = if(corrected) 279.5 else 570.0
            val candidate = if(corrected) 280.5 else 580.0
            val resolved = if(corrected) 279.8 else 580.0
            val speed = if(corrected) 1.0 else 10.0
            val raw = JsonObject(motion(false) + mapOf("originMoveId" to JsonNull,"positionBefore" to position(start),
                "speedBefore" to n(speed),"angularVelocityBefore" to n(2.25),"commandTargetBefore" to JsonNull,
                "steeringInvoked" to b(false),"steeringAim" to JsonNull,"finalLocalPoint" to JsonNull,"steeringDifference" to JsonNull,
                "targetFraction" to n(0),"integratedSpeed" to n(speed),"candidate" to position(candidate),
                "terrainResolved" to position(resolved),"terrainDisposition" to s(if(corrected) "CORRECTED" else "DIRECT"),
                "finalPosition" to position(resolved),"finalSpeed" to n(speed),"finalAngularVelocity" to n(2.25)))
            val before = withUnit(observation(false),mapOf("position" to position(start),"speed" to n(speed),"angularVelocity" to n(2.25),
                "moveTarget" to JsonNull,"pathPoints" to array(emptyList()),"movementState" to s("DECELERATING")))
            val after = JsonObject(withUnit(observation(true),mapOf("position" to position(resolved),"speed" to n(speed),
                "angularVelocity" to n(2.25),"movementState" to s("DECELERATING"))) + ("events" to array(
                    if(corrected) listOf(obj("kind" to s("movement-blocked"),"unitId" to s("u1"))) else emptyList())))
            val bounds = MotionV2CoreMotion.Boundary(600.0,400.0,11.0)
            val good = MotionV2CoreMotion.assess(raw,before,after,bounds)
            assertEquals(2.25,assertNotNull(good.parsed).finalAngularVelocity)
            assertTrue(good.issues.none { it.status in setOf(MotionV2Findings.Status.FAIL,MotionV2Findings.Status.EXECUTION_FAILED) })
            assertTrue(good.issues.any { it.code=="TERRAIN_RESOLVER_NOT_CHECKED" && it.status==MotionV2Findings.Status.INSUFFICIENT })
            for((field,unitField,value,code) in listOf(
                listOf("finalPosition","position",position(resolved+.25),"TERRAIN_WRITEBACK"),
                listOf("finalSpeed","speed",n(speed+1),"TERRAIN_WRITEBACK"),
                listOf("finalAngularVelocity","angularVelocity",n(3.25),"NO_STEERING_MOTION"))) {
                val bad = MotionV2CoreMotion.assess(JsonObject(raw+(field as String to value as JsonElement)),before,
                    withUnit(after,mapOf(unitField as String to value)),bounds)
                assertNotNull(bad.parsed)
                assertTrue(bad.issues.any { it.code==code && it.status==MotionV2Findings.Status.FAIL })
                assertTrue(bad.issues.none { it.domain=="source" })
            }
        }
    }
}
