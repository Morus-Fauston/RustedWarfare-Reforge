package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MotionV2CoreAssessmentTest {
    private fun position(x: Number) = obj("x" to n(x), "y" to n(200))
    private fun unit(target: JsonElement?) = obj("unitId" to s("u1"), "ownerPlayerId" to s("p1"),
        "typeId" to s("tank"), "position" to position(300), "heading" to n(90), "speed" to n(0),
        "angularVelocity" to n(0), "moveTarget" to target, "pathPoints" to array(if(target==JsonNull) emptyList() else listOf(position(360))),
        "movementState" to s(if(target==JsonNull) "IDLE" else "MOVING"))
    private fun observation(target: JsonElement?, events: List<String> = emptyList()) = obj("worldId" to s("w"),
        "stepIndex" to n(0), "simulationTime" to n(0), "units" to array(listOf(unit(target))),
        "events" to array(events.map { obj("kind" to s(it), "unitId" to s("u1")) }))
    private fun sample(index: Int, sequence: Int, target: JsonElement?, kind: String, observation: JsonObject) = obj(
        "index" to n(index), "q" to n(0), "phase" to n(0), "inputSequence" to n(sequence),
        "state" to obj("id" to s("u1"), "owner" to s("p1"), "definition" to s("tank"),
            "x" to n(300), "y" to n(200), "heading" to n(90), "target" to target),
        "raw" to obj("kind" to s(kind), "observation" to observation))
    private fun body(status: String = "accepted", queued: JsonElement = JsonNull): JsonObject {
        val before = observation(JsonNull)
        val after = observation(if(status=="accepted" && queued==JsonNull) position(360) else JsonNull,
            if(status=="accepted" && queued==JsonNull) listOf("movement-started") else emptyList())
        return obj("complete" to b(true), "exitCode" to n(0), "recordCount" to n(2), "motionInputSchemaVersion" to n(1),
            "samples" to array(listOf(sample(0,-1,JsonNull,"initial",before),
                sample(1,0,after.arrayAt("units").single().jsonObject.required("moveTarget"),"input-post",after))),
            "motionInputs" to array(emptyList()), "inputs" to array(listOf(obj("sequence" to n(0), "q" to n(0),
                "action" to s("move"), "target" to position(360), "raw" to obj("before" to before, "after" to after,
                    "submitResult" to obj("actionId" to s("v2.0"), "status" to s(status), "queuedForStep" to queued))))))
    }

    @Test fun real_queued_or_rejected_returns_keep_paired_facts_and_fail_without_invented_consumption() {
        for (changed in listOf(body("rejected"), body(queued=n(3)))) {
            val result = MotionV2CoreAssessment.assessRecords(changed, "v2.", 10.0)
            assertNotNull(result.fullFacts)
            assertEquals(1, result.inputAttempts.size)
            assertTrue(result.fullFacts.consumptions.isEmpty())
            assertTrue(result.issues.any { it.domain=="behavior" && it.status==MotionV2Findings.Status.FAIL && it.code=="INPUT_NOT_IMMEDIATE" })
            assertTrue(result.issues.none { it.domain=="source" })
            assertTrue(result.issues.any { it.code=="REQUIRED_HORIZON_MISSING" })
        }
    }

    private fun advanced(base: JsonObject = body(), normal: Boolean = true, arrived: Boolean = true,
                         keepTarget: Boolean = false, origin: String = "v2.0"): JsonObject {
        val before = base.arrayAt("samples").last().jsonObject.objectAt("raw").objectAt("observation")
        val old = before.arrayAt("units").single().jsonObject
        val target = if(keepTarget) old.required("moveTarget") else JsonNull
        val after = JsonObject(observation(target, if(arrived) listOf("movement-arrived") else emptyList()) + ("simulationTime" to n(1)))
        val row = JsonObject(sample(2,0,target,"advance",after) + ("q" to n(1)))
        val motion = obj("worldId" to s("w"), "stepIndex" to n(0), "unitId" to s("u1"), "ownerPlayerId" to s("p1"),
            "typeId" to s("tank"), "timeBefore" to n(0), "delta" to n(1), "originMoveId" to s(origin),
            "positionBefore" to position(300), "headingBefore" to n(90), "speedBefore" to n(0), "angularVelocityBefore" to n(0),
            "commandTargetBefore" to old.required("moveTarget"), "normalCompletion" to b(normal),
            "finalPosition" to position(300), "finalHeading" to n(90), "finalSpeed" to n(0), "finalAngularVelocity" to n(0))
        return JsonObject(base + ("samples" to array(base.arrayAt("samples") + row)) + ("recordCount" to n(3)) +
            ("motionInputs" to array(listOf(motion))))
    }

    @Test fun normal_requires_the_actual_event_same_move_and_both_target_and_path_clear() {
        val good = MotionV2CoreAssessment.assessRecords(advanced(), "v2.", 1.0)
        assertEquals(MotionV2DiagnosticAdapter.Cause.NORMAL, assertNotNull(good.fullFacts).clears.single().cause)
        assertTrue(good.issues.isEmpty())
        for (changed in listOf(advanced(normal=false), advanced(arrived=false), advanced(origin="v2.9"), advanced(keepTarget=true))) {
            val result = MotionV2CoreAssessment.assessRecords(changed, "v2.", 1.0)
            val facts = assertNotNull(result.fullFacts)
            assertTrue(facts.clears.none { it.cause==MotionV2DiagnosticAdapter.Cause.NORMAL })
            assertTrue(result.issues.any { it.domain=="behavior" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.none { it.domain=="source" })
        }
        val unknown = MotionV2CoreAssessment.assessRecords(advanced(normal=false,arrived=false), "v2.", 1.0)
        assertEquals(MotionV2DiagnosticAdapter.Cause.UNKNOWN, assertNotNull(unknown.fullFacts).clears.single().cause)
        assertTrue(unknown.issues.any { it.status==MotionV2Findings.Status.INSUFFICIENT && it.code=="CLEAR_REASON_UNKNOWN" })
    }

    private fun nextAdvance(base: JsonObject, target: JsonElement = JsonNull, normal: Boolean = false,
                            events: List<String> = emptyList()): JsonObject {
        val before = base.arrayAt("samples").last().jsonObject.objectAt("raw").objectAt("observation")
        val q = before.number("simulationTime") + 1.0
        val step = base.arrayAt("motionInputs").size
        val after = JsonObject(observation(target, events) + ("simulationTime" to n(q)) + ("stepIndex" to n(step)))
        val size = base.arrayAt("samples").size
        val sequence = base.arrayAt("samples").last().jsonObject.integer("inputSequence").toInt()
        val row = JsonObject(sample(size,sequence,target,"advance",after) + ("q" to n(q)))
        val old = before.arrayAt("units").single().jsonObject
        val motion = obj("worldId" to s("w"), "stepIndex" to n(step), "unitId" to s("u1"), "ownerPlayerId" to s("p1"),
            "typeId" to s("tank"), "timeBefore" to n(q-1), "delta" to n(1), "originMoveId" to s("v2.0"),
            "positionBefore" to position(300), "headingBefore" to n(90), "speedBefore" to n(0), "angularVelocityBefore" to n(0),
            "commandTargetBefore" to old.required("moveTarget"), "normalCompletion" to b(normal),
            "finalPosition" to position(300), "finalHeading" to n(90), "finalSpeed" to n(0), "finalAngularVelocity" to n(0))
        return JsonObject(base + ("samples" to array(base.arrayAt("samples") + row)) + ("recordCount" to n(size+1)) +
            ("motionInputs" to array(base.arrayAt("motionInputs") + motion)))
    }

    @Test fun command_resurrection_and_second_clear_without_new_move_are_both_retained_failures() {
        val resurrected = nextAdvance(advanced(), position(360))
        val twice = nextAdvance(resurrected, normal=true, events=listOf("movement-arrived"))
        val result = MotionV2CoreAssessment.assessRecords(twice, "v2.", 3.0)
        val facts = assertNotNull(result.fullFacts)
        assertEquals(1, facts.clears.count { it.cause==MotionV2DiagnosticAdapter.Cause.NORMAL })
        assertTrue(result.issues.any { it.code=="COMMAND_RESURRECTED" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(result.issues.any { it.code=="REPEATED_CLEAR" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(result.issues.none { it.domain=="source" })
    }

    @Test fun nonzero_exit_incomplete_and_late_copy_damage_cannot_erase_an_earlier_behavior_failure() {
        val failedInput = body("rejected")
        val partial = JsonObject(failedInput + ("complete" to b(false)) + ("exitCode" to n(4)))
        val first = MotionV2CoreAssessment.assessRecords(partial, "v2.", 10.0)
        assertNull(first.fullFacts); assertNotNull(first.prefix)
        assertTrue(first.issues.any { it.code=="INPUT_NOT_IMMEDIATE" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(first.issues.any { it.code=="NONZERO_EXIT" && it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        assertTrue(first.issues.any { it.code=="RAW_INCOMPLETE" && it.status==MotionV2Findings.Status.INSUFFICIENT })
        val appended = nextAdvance(failedInput)
        val samples = appended.arrayAt("samples").toMutableList()
        val last = samples.last().jsonObject
        samples[samples.lastIndex] = JsonObject(last + ("state" to JsonObject(last.objectAt("state") + ("x" to n(999)))))
        val damaged = MotionV2CoreAssessment.assessRecords(JsonObject(appended + ("samples" to JsonArray(samples))), "v2.", 10.0)
        assertNull(damaged.fullFacts)
        assertEquals(1, assertNotNull(damaged.prefix).lastSample)
        assertEquals(1, damaged.prefix.facts.calls.size)
        assertTrue(damaged.issues.any { it.code=="INPUT_NOT_IMMEDIATE" && it.status==MotionV2Findings.Status.FAIL })
        assertTrue(damaged.issues.any { it.domain=="source" && it.status==MotionV2Findings.Status.EXECUTION_FAILED })
        assertEquals(0.0, damaged.observedEndQ)
        assertTrue(damaged.issues.any { it.code=="REQUIRED_HORIZON_MISSING" })
    }

    private fun secondAttempt(action: String, queued: Boolean): JsonObject {
        val base = body(); val before = base.arrayAt("samples").last().jsonObject.objectAt("raw").objectAt("observation")
        val after = JsonObject(before + ("events" to array(emptyList())))
        val returned = if(queued) obj("actionId" to s("v2.1"), "status" to s("accepted"), "queuedForStep" to n(5))
            else obj("actionId" to s("v2.1"), "status" to s("rejected"), "error" to obj("code" to s("fixture"), "message" to s("Constructed rejection")))
        val input = obj("sequence" to n(1), "q" to n(0), "action" to s(action),
            "target" to if(action=="move") position(400) else JsonNull,
            "raw" to obj("before" to before, "after" to after, "submitResult" to returned))
        return JsonObject(base + ("recordCount" to n(3)) + ("inputs" to array(base.arrayAt("inputs")+input)) +
            ("samples" to array(base.arrayAt("samples")+sample(2,1,position(360),"input-post",after))))
    }

    @Test fun an_unconsumed_attempt_does_not_steal_the_previous_moves_normal_clear_or_its_call_axis() {
        for(action in listOf("move","stop")) for(queued in listOf(false,true)) {
            val doc = nextAdvance(secondAttempt(action,queued), normal=true, events=listOf("movement-arrived"))
            val result = MotionV2CoreAssessment.assessRecords(doc,"v2.",1.0)
            val facts = assertNotNull(result.fullFacts)
            val normal = facts.clears.single { it.cause==MotionV2DiagnosticAdapter.Cause.NORMAL }
            assertEquals(0L,normal.input)
            assertEquals(0L,facts.calls.last().input)
            assertEquals(listOf(0L),facts.consumptions.map { it.sequence })
            assertEquals(listOf(0L,1L),result.inputAttempts.map { it.sequence })
            assertTrue(result.issues.any { it.code=="INPUT_NOT_IMMEDIATE" })
            assertTrue(result.issues.none { it.code=="NORMAL_MOVE_BINDING" || it.domain=="source" })
        }
    }

    @Test fun successful_move_needs_a_finite_goal_and_a_nonempty_route_ending_at_that_goal() {
        val base = body()
        for(route in listOf(array(emptyList()),array(listOf(position(400))))) {
            val samples = base.arrayAt("samples").toMutableList(); val post = samples.last().jsonObject
            val raw = post.objectAt("raw"); val old = raw.objectAt("observation")
            val changed = JsonObject(old + ("units" to array(listOf(JsonObject(old.arrayAt("units").single().jsonObject + ("pathPoints" to route))))))
            samples[samples.lastIndex] = JsonObject(post + ("raw" to JsonObject(raw + ("observation" to changed))))
            val input = base.arrayAt("inputs").single().jsonObject
            val doc = JsonObject(base + ("samples" to JsonArray(samples)) + ("inputs" to array(listOf(
                JsonObject(input + ("raw" to JsonObject(input.objectAt("raw") + ("after" to changed))))))))
            val result = MotionV2CoreAssessment.assessRecords(doc,"v2.",0.0)
            assertNotNull(result.fullFacts)
            assertTrue(result.issues.any { it.code=="MOVE_NOT_EFFECTIVE" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.none { it.domain=="source" })
        }
        val input = base.arrayAt("inputs").single().jsonObject
        val invalid = MotionV2CoreAssessment.assessRecords(JsonObject(base + ("inputs" to array(listOf(
            JsonObject(input + ("target" to JsonNull)))))),"v2.",0.0)
        assertNull(invalid.fullFacts)
        assertTrue(invalid.issues.any { it.domain=="source" })
    }

    private fun stop(clear: Boolean = true, cancel: Boolean = true): JsonObject {
        val base = body(); val before = base.arrayAt("samples").last().jsonObject.objectAt("raw").objectAt("observation")
        val target = if(clear) JsonNull else position(360)
        val after = observation(target, if(cancel) listOf("movement-cancelled") else emptyList())
        val input = obj("sequence" to n(1), "q" to n(0), "action" to s("stop"), "target" to JsonNull,
            "raw" to obj("before" to before,"after" to after,"submitResult" to obj("actionId" to s("v2.1"),
                "status" to s("accepted"),"queuedForStep" to JsonNull)))
        return JsonObject(base + ("recordCount" to n(3)) + ("inputs" to array(base.arrayAt("inputs")+input)) +
            ("samples" to array(base.arrayAt("samples")+sample(2,1,target,"input-post",after))))
    }

    @Test fun actual_stop_effect_and_unknown_clear_are_kept_separate_from_record_pairing() {
        val good = MotionV2CoreAssessment.assessRecords(stop(),"v2.",0.0)
        val cleared = assertNotNull(good.fullFacts).clears.single()
        assertEquals(MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP,cleared.cause); assertEquals(1L,cleared.input)
        assertEquals(MotionV2Math.Interval(0.0,0.0),cleared.event.interval)
        assertTrue(good.issues.isEmpty())
        for(changed in listOf(stop(clear=false),stop(cancel=false))) {
            val result = MotionV2CoreAssessment.assessRecords(changed,"v2.",0.0)
            val facts = assertNotNull(result.fullFacts)
            assertTrue(facts.clears.none { it.cause==MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP })
            assertTrue(result.issues.any { it.code=="STOP_NOT_CLEARED" && it.status==MotionV2Findings.Status.FAIL })
            assertTrue(result.issues.none { it.domain=="source" })
        }
        val unknown = MotionV2CoreAssessment.assessRecords(stop(cancel=false),"v2.",0.0)
        assertEquals(MotionV2DiagnosticAdapter.Cause.UNKNOWN,assertNotNull(unknown.fullFacts).clears.single().cause)
        assertTrue(unknown.issues.any { it.code=="CLEAR_REASON_UNKNOWN" && it.status==MotionV2Findings.Status.INSUFFICIENT })
    }

    @Test fun a_bad_last_sequence_copy_cannot_publish_that_calls_attempt_consumption_cause_or_failure() {
        for(base in listOf(secondAttempt("stop",false), advanced())) {
            val samples = base.arrayAt("samples").toMutableList()
            samples[samples.lastIndex] = JsonObject(samples.last().jsonObject + ("inputSequence" to n(99)))
            val result = MotionV2CoreAssessment.assessRecords(JsonObject(base + ("samples" to JsonArray(samples))),"v2.",1.0)
            assertNull(result.fullFacts)
            val prefix = assertNotNull(result.prefix)
            assertEquals(1,prefix.lastSample); assertEquals(1,prefix.facts.calls.size)
            assertEquals(1,result.inputAttempts.size); assertEquals(1,prefix.facts.consumptions.size)
            assertTrue(prefix.facts.clears.isEmpty())
            assertTrue(result.issues.any { it.domain=="source" })
            assertTrue(result.issues.none { it.domain=="behavior" })
        }
    }

    @Test fun coverage_uses_paired_calls_even_if_the_process_later_fails_and_never_shrinks_the_required_horizon() {
        var full = body()
        repeat(600) { full = nextAdvance(full,position(360)) }
        val result = MotionV2CoreAssessment.assessRecords(JsonObject(full + ("complete" to b(false)) + ("exitCode" to n(4))),"v2.",600.0)
        assertNull(result.fullFacts); assertNotNull(result.prefix)
        assertEquals(600.0,result.observedEndQ)
        assertEquals(600.0,result.requiredHorizonQ)
        assertTrue(result.issues.any { it.code=="NONZERO_EXIT" })
        assertTrue(result.issues.none { it.code=="REQUIRED_HORIZON_MISSING" || it.domain=="source" })
        val early = MotionV2CoreAssessment.assessRecords(advanced(),"v2.",600.0)
        assertEquals(1.0,early.observedEndQ); assertEquals(600.0,early.requiredHorizonQ)
        assertTrue(early.issues.any { it.code=="REQUIRED_HORIZON_MISSING" })
        for(horizon in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,600.1))
            assertFailsWith<IllegalArgumentException> { MotionV2CoreAssessment.assessRecords(body(),"v2.",horizon) }
        val empty = obj("complete" to b(false),"exitCode" to n(4),"recordCount" to n(0),"motionInputSchemaVersion" to n(1),
            "samples" to array(emptyList()),"inputs" to array(emptyList()),"motionInputs" to array(emptyList()))
        val noCalls = MotionV2CoreAssessment.assessRecords(empty,"v2.",0.0)
        assertNull(noCalls.fullFacts); assertNull(noCalls.prefix); assertNull(noCalls.observedEndQ)
        assertTrue(noCalls.issues.any { it.code=="REQUIRED_HORIZON_MISSING" })
    }
}
