package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

class MotionV2DiagnosticAdapterTest {
    private fun position(x: Number)=obj("x" to n(x),"y" to n(200))
    private fun unit(x: Number,target: JsonElement?)=obj("unitId" to s("u1"),"ownerPlayerId" to s("p1"),
        "typeId" to s("tank"),"position" to position(x),"heading" to n(90),"speed" to n(0),
        "angularVelocity" to n(0),"moveTarget" to target)
    private fun observation(q: Int,x: Number,target: JsonElement?,events: List<String> = emptyList())=obj(
        "worldId" to s("w"),"stepIndex" to n(maxOf(0,q-1)),"simulationTime" to n(q),"units" to array(listOf(unit(x,target))),
        "events" to array(events.map { obj("kind" to s(it),"unitId" to s("u1")) }))
    private fun sample(index: Int,q: Int,seq: Int,x: Number,target: JsonElement?,kind: String,obs: JsonObject)=obj(
        "index" to n(index),"q" to n(q),"phase" to n(0),"inputSequence" to n(seq),
        "state" to obj("id" to s("u1"),"owner" to s("p1"),"definition" to s("tank"),
            "x" to n(x),"y" to n(200),"heading" to n(90),"target" to target),
        "raw" to obj("kind" to s(kind),"observation" to obs))
    private fun core():JsonObject {
        val target=position(360);val initial=observation(0,300,null)
        val submitted=observation(0,300,target,listOf("movement-started"))
        val arrived=observation(1,301,null,listOf("movement-arrived"))
        val motion=obj("worldId" to s("w"),"stepIndex" to n(0),"unitId" to s("u1"),"ownerPlayerId" to s("p1"),
            "typeId" to s("tank"),"timeBefore" to n(0),"delta" to n(1),"originMoveId" to s("diag.0"),
            "positionBefore" to position(300),"headingBefore" to n(90),"speedBefore" to n(0),"angularVelocityBefore" to n(0),
            "commandTargetBefore" to position(360.0),"normalCompletion" to b(true),
            "finalPosition" to position(301),"finalHeading" to n(90),"finalSpeed" to n(0),"finalAngularVelocity" to n(0))
        return obj("kind" to s("motion-diagnostic-evidence"),"schemaVersion" to n(1),"role" to s("core"),
            "sourceKind" to s("core-diagnostic-runtime"),"formalCompatibility" to b(false),"complete" to b(true),"exitCode" to n(0),
            "recordCount" to n(3),"motionInputSchemaVersion" to n(1),
            "samples" to array(listOf(sample(0,0,-1,300,null,"initial",initial),sample(1,0,0,300,target,"input-post",submitted),
                sample(2,1,0,301,null,"advance",arrived))),"motionInputs" to array(listOf(motion)),
            "inputs" to array(listOf(obj("sequence" to n(0),"q" to n(0),"action" to s("move"),"target" to target,
                "raw" to obj("before" to initial,"after" to submitted,"submitResult" to accepted())))))
    }
    private fun mutateMotion(doc:JsonObject,change:(JsonObject)->JsonObject)=JsonObject(doc+(
        "motionInputs" to array(doc.arrayAt("motionInputs").map { change(it.jsonObject) })))
    private fun withAcknowledgement(doc: JsonObject, result: JsonElement): JsonObject = JsonObject(doc+(
        "inputs" to array(doc.arrayAt("inputs").map { input -> val i=input.jsonObject
            JsonObject(i+("raw" to JsonObject(i.objectAt("raw")+("submitResult" to result)))) })))
    private fun accepted(sequence: Int = 0, queued: JsonElement = JsonNull) = obj(
        "actionId" to s("diag.$sequence"), "status" to s("accepted"), "queuedForStep" to queued)
    @Test fun record_normalization_has_no_source_authority_and_requires_the_trusted_action_prefix() {
        val doc=core()
        assertEquals(MotionV2DiagnosticAdapter.core(doc),MotionV2RecordNormalizer.core(doc,"diag."))
        for(prefix in listOf("wrong.","", "v2.other-run."))
            assertFails { MotionV2RecordNormalizer.core(doc,prefix) }
        for(changed in listOf(JsonObject(doc+("formalCompatibility" to b(true))),
            JsonObject(doc+("sourceKind" to s("core-v2-final-runtime")))))
            assertFails { MotionV2DiagnosticAdapter.core(changed) }
    }
    @Test fun input_post_requires_accepted_immediate_acknowledgement_for_the_same_action() {
        for(result in listOf(JsonNull, accepted(9), accepted(0,n(1)),
            JsonObject(accepted()+("status" to s("rejected"))))) {
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(withAcknowledgement(core(),result)) }
        }
        MotionV2DiagnosticAdapter.core(withAcknowledgement(core(),accepted()))
    }
    @Test fun core_normal_requires_actual_arrived_event_and_move_identity_with_numeric_target_binding() {
        val facts=MotionV2DiagnosticAdapter.core(core())
        assertEquals(2,facts.calls.size);assertEquals(3,facts.points.size)
        val clear=facts.clears.single();assertEquals(MotionV2DiagnosticAdapter.Cause.NORMAL,clear.cause)
        assertEquals(MotionV2Math.Interval(0.0,1.0),clear.event.interval)
        assertEquals(1,clear.event.index)
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(mutateMotion(core()) {
            JsonObject(it+("originMoveId" to s("diag.9"))) }) }
        val doc=core();val rows=doc.arrayAt("samples").toMutableList();val last=rows.last().jsonObject
        val raw=last.objectAt("raw");val obs=raw.objectAt("observation")
        rows[rows.lastIndex]=JsonObject(last+("raw" to JsonObject(raw+("observation" to JsonObject(obs+("events" to JsonArray(emptyList())))))))
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(JsonObject(doc+("samples" to JsonArray(rows)))) }
    }
    @Test fun core_missing_reordered_or_rewritten_diagnostics_are_rejected_before_rule_use() {
        for(key in listOf("positionBefore","finalPosition","commandTargetBefore"))
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(mutateMotion(core()) { JsonObject(it+(key to position(999))) }) }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(mutateMotion(core()) { JsonObject(it+("normalCompletion" to b(false))) }) }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(JsonObject(core()+("motionInputs" to JsonArray(emptyList())))) }
        val doc=core();assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(JsonObject(doc+("samples" to JsonArray(doc.arrayAt("samples").reversed())))) }
        val rows=doc.arrayAt("samples").toMutableList();rows[2]=JsonObject(rows[2].jsonObject+("phase" to n(1)))
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.core(JsonObject(doc+("samples" to JsonArray(rows)))) }
    }
    @Test fun actual_step_zero_at_q1_then_step_one_at_q2_and_stop_then_next_step_bind_in_order() {
        val base=core();val target=position(360);val rows=base.arrayAt("samples").toMutableList()
        val m0=JsonObject(base.arrayAt("motionInputs").single().jsonObject+("normalCompletion" to b(false)))
        val after1=observation(1,301,target)
        rows[2]=sample(2,1,0,301,target,"advance",after1)
        val afterStop=observation(1,301,null,listOf("movement-cancelled"))
        rows+=sample(3,1,1,301,null,"input-post",afterStop)
        val after2=observation(2,301,null)
        rows+=sample(4,2,1,301,null,"advance",after2)
        val m1=JsonObject(m0+("stepIndex" to n(1))+("timeBefore" to n(1))+("originMoveId" to JsonNull)+
            ("positionBefore" to position(301))+("commandTargetBefore" to JsonNull))
        val stop=obj("sequence" to n(1),"q" to n(1),"action" to s("stop"),"target" to JsonNull,
            "raw" to obj("before" to after1,"after" to afterStop,"submitResult" to accepted(1)))
        val doc=JsonObject(base+("recordCount" to n(5))+("samples" to JsonArray(rows))+(
            "motionInputs" to array(listOf(m0,m1)))+("inputs" to array(base.arrayAt("inputs")+stop)))
        val facts=MotionV2DiagnosticAdapter.core(doc)
        assertEquals(4,facts.calls.size);assertEquals(MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP,facts.clears.single().cause)
        assertEquals(MotionV2Math.Interval(1.0,1.0),facts.clears.single().event.interval)
        for(badIndex in listOf(0,2)) {
            val bad=JsonObject(m1+("stepIndex" to n(badIndex)))
            assertFailsWith<IllegalArgumentException>{MotionV2DiagnosticAdapter.core(JsonObject(doc+("motionInputs" to array(listOf(m0,bad)))))}
        }
    }
    private fun original(normal: Boolean, consumptionDelta: Double = 0.0):JsonObject {
        val target=position(360)
        fun state(x: Int,t: JsonElement?)=obj("id" to s("u1"),"owner" to s("p1"),"definition" to s("tank"),
            "x" to n(x),"y" to n(200),"heading" to n(90),"target" to t)
        val values=MutableList(134){n(0)}
        for(index in listOf(2,69,3+2,70+2))values[index]=n(1f.toRawBits())
        for(offset in listOf(3,70)) {
            values[offset+6]=n((if(offset==3)300f else 301f).toRawBits())
            values[offset+7]=n(200f.toRawBits())
        }
        values[3+16]=n(1);values[3+17]=n(1);values[3+19]=n(360f.toRawBits());values[3+20]=n(200f.toRawBits())
        values[70+28]=n(if(normal)1 else 0);values[70+29]=n(if(normal)1 else 0)
        val raw=obj("phase" to n(0),"callIndex" to n(1),"values" to JsonArray(values))
        val consumed=values.toMutableList()
        for(j in 0 until 64) consumed[70+j]=values[3+j]
        for(index in listOf(2,69,3+2,70+2))consumed[index]=n(consumptionDelta.toFloat().toRawBits())
        consumed[3+13]=n(1);consumed[3+14]=n(1)
        consumed[3+16]=n(0);consumed[3+17]=n(0)
        consumed[70+27]=n(1)
        val consumptionRaw=obj("phase" to n(0),"callIndex" to n(0),"values" to JsonArray(consumed))
        fun bits(value:Float)=obj("bits" to s(value.toRawBits().toUInt().toString(16).padStart(8,'0')),"value" to n(value),"finite" to b(true))
        val input=obj("sequence" to n(0),"q" to n(0),"action" to s("move"),"target" to target,
            "raw" to JsonObject(consumptionRaw+("consumptionEntryQ" to n(0))+("callExitQ" to n(consumptionDelta))+
                ("stateBefore" to state(300,null))+("stateAfter" to state(300,target))+(
                "command" to obj("inputSequence" to n(0),"action" to s("move"),"selectedCount" to n(1),
                    "ownerSame" to b(true),"queueContainsSameOnly" to b(true),"queueSize" to n(1),"p" to b(false),
                    "waypointPresent" to b(true),"waypointIsMove" to b(true),"x" to bits(360f),"y" to bits(200f)))))
        return obj("kind" to s("motion-evidence"),"schemaVersion" to n(1),"role" to s("original"),
            "sourceKind" to s("native-diagnostic-scene-runtime"),"complete" to b(true),"exitCode" to n(0),"recordCount" to n(3),
            "samples" to array(listOf(obj("index" to n(0),"q" to n(0),"phase" to n(0),"inputSequence" to n(-1),"state" to state(300,null),"raw" to obj()),
                obj("index" to n(1),"q" to n(consumptionDelta),"phase" to n(0),"inputSequence" to n(0),"state" to state(300,target),"raw" to consumptionRaw),
                obj("index" to n(2),"q" to n(consumptionDelta+1),"phase" to n(0),"inputSequence" to n(0),"state" to state(301,null),"raw" to raw))),
            "nativeRows" to array(listOf(JsonObject(consumptionRaw+("qBefore" to n(0))+("qAfter" to n(consumptionDelta))),JsonObject(raw+("qBefore" to n(consumptionDelta))+("qAfter" to n(consumptionDelta+1))))),
            "inputs" to array(listOf(input)),
            "normalCauseFacts" to array(listOf(obj("cause" to s(if(normal)"normal-completion" else "unknown-clear"),
                "inputSequence" to n(0),"phase" to n(0),"callIndex" to n(1),"intervalQ" to array(listOf(n(consumptionDelta),n(consumptionDelta+1)))))))
    }
    @Test fun input_entry_quantization_is_separate_from_complete_native_consumption_interval() {
        for(delta in listOf(0.0,.5)) {
            val f=MotionV2DiagnosticAdapter.original(original(false,delta));val c=f.consumptions.single()
            assertEquals(0.0,c.quantizationEntryQ);assertEquals(listOf(0.0),f.inputs)
            assertEquals(MotionV2Math.Interval(0.0,delta),c.event.interval)
            assertEquals(0,c.event.index);assertEquals(0L,c.sequence);assertEquals("move",c.action)
            assertEquals(MotionV2Math.Interval(delta,delta+1),f.clears.single().event.interval)
        }
        assertEquals(MotionV2Math.Interval(0.0,0.0),MotionV2DiagnosticAdapter.core(core()).consumptions.single().event.interval)
    }
    @Test fun self_consistent_relabelled_q_cannot_shrink_the_actual_effective_I_interval() {
        val wide=original(false,.5);val zero=original(false,0.0)
        // Retain all actual .5-I raw values while consistently rewriting every
        // claimed clock to zero-I timing. Header-to-header agreement is no proof.
        val samples=zero.arrayAt("samples").toMutableList()
        val one=samples[1].jsonObject
        samples[1]=JsonObject(one+("raw" to wide.arrayAt("samples")[1].jsonObject.required("raw")))
        val rows=zero.arrayAt("nativeRows").toMutableList()
        rows[0]=JsonObject(rows[0].jsonObject+("values" to wide.arrayAt("nativeRows")[0].jsonObject.required("values")))
        val doc=mutateOriginalInput(JsonObject(zero+("samples" to JsonArray(samples))+("nativeRows" to JsonArray(rows)))) {
            JsonObject(it+("values" to wide.arrayAt("inputs")[0].jsonObject.objectAt("raw").required("values"))) }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(doc) }
    }
    private fun mutateOriginalInput(doc:JsonObject,change:(JsonObject)->JsonObject)=JsonObject(doc+(
        "inputs" to array(doc.arrayAt("inputs").map { val input=it.jsonObject
            JsonObject(input+("raw" to change(input.objectAt("raw")))) })))
    @Test fun original_consumption_requires_unique_actual_call_and_same_command_readback() {
        val doc=original(false)
        for((key,value)in listOf("callIndex" to n(1),"callExitQ" to n(1),"consumptionEntryQ" to n(1),"values" to JsonArray(emptyList())))
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(mutateOriginalInput(doc) { JsonObject(it+(key to value)) }) }
        for((key,value)in listOf("inputSequence" to n(1),"action" to s("stop"),"selectedCount" to n(2),"ownerSame" to b(false),
            "queueContainsSameOnly" to b(false),"queueSize" to n(0),"p" to b(true))) {
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(mutateOriginalInput(doc) {
                JsonObject(it+("command" to JsonObject(it.objectAt("command")+(key to value)))) }) }
        }
    }
    @Test fun original_unknown_clear_stays_unknown_and_missing_or_relabelled_facts_do_not_become_normal() {
        assertEquals(MotionV2DiagnosticAdapter.Cause.UNKNOWN,MotionV2DiagnosticAdapter.original(original(false)).clears.single().cause)
        assertEquals(MotionV2DiagnosticAdapter.Cause.NORMAL,MotionV2DiagnosticAdapter.original(original(true)).clears.single().cause)
        val doc=original(false);val fact=doc.arrayAt("normalCauseFacts").single().jsonObject
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(doc+(
            "normalCauseFacts" to array(listOf(JsonObject(fact+("cause" to s("normal-completion")))))))) }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(doc+("normalCauseFacts" to JsonArray(emptyList())))) }
        val rows=doc.arrayAt("samples").toMutableList();val last=rows.last().jsonObject
        rows[1]=JsonObject(last+("q" to n(2)))
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(doc+("samples" to JsonArray(rows)))) }
    }
    @Test fun original_initial_position_heading_and_target_are_bound_to_first_actual_entry() {
        val doc=original(false)
        for((key,value)in listOf("x" to n(999),"heading" to n(91),"target" to position(360))) {
            val rows=doc.arrayAt("samples").toMutableList();val first=rows.first().jsonObject
            rows[0]=JsonObject(first+("state" to JsonObject(first.objectAt("state")+(key to value))))
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(doc+("samples" to JsonArray(rows)))) }
        }
    }
    @Test fun q0_unreachable_cancellation_has_its_own_cause_without_a_normal_completion_or_movement() {
        val base=core();val rows=base.arrayAt("samples").toMutableList()
        val after=observation(0,300,null,listOf("movement-cancelled","path-unreachable"))
        val after1=observation(1,300,null)
        rows[1]=sample(1,0,0,300,null,"input-post",after);rows[2]=sample(2,1,0,300,null,"advance",after1)
        val input=base.arrayAt("inputs").single().jsonObject;val inputRaw=input.objectAt("raw")
        val updatedInput=JsonObject(input+("raw" to JsonObject(inputRaw+("after" to after))))
        val m=JsonObject(base.arrayAt("motionInputs").single().jsonObject+("normalCompletion" to b(false))+(
            "originMoveId" to JsonNull)+("commandTargetBefore" to JsonNull)+("finalPosition" to position(300)))
        val doc=JsonObject(base+("samples" to JsonArray(rows))+("inputs" to array(listOf(updatedInput)))+
            ("motionInputs" to array(listOf(m))))
        val f=MotionV2DiagnosticAdapter.core(doc)
        assertEquals(MotionV2DiagnosticAdapter.Cause.UNREACHABLE,f.clears.single().cause)
        assertEquals(MotionV2Math.Interval(0.0,0.0),f.clears.single().event.interval)
        assertNull(MotionV2Events.firstMove(f.calls,0))
    }
    private fun localClearOriginal(): JsonObject {
        val base=original(false)
        val rows=base.arrayAt("nativeRows").mapIndexed { index,element ->
            val row=element.jsonObject;val values=row.arrayAt("values").toMutableList()
            values[1]=n(91);values[68]=n(91);values[3+5]=n(55);values[70+5]=n(55)
            if(index==1) {
                values[3+21]=n(1);values[3+27]=n(1)
                values[70+43]=n(1);values[70+58]=n(1)
                values[70+59]=n(360f.toRawBits());values[70+60]=n(200f.toRawBits())
            }
            JsonObject(row+("values" to JsonArray(values)))
        }
        val event=obj("phase" to n(0),"callIndex" to n(1),"threadId" to n(91),"branchId" to s("bp.P@0297"),
            "unitEj" to n(55),"oldToken" to n(1),"targetXBits" to n(360f.toRawBits()),"targetYBits" to n(200f.toRawBits()),
            "kindOrdinal" to n(0),"beforeXBits" to n(300f.toRawBits()),"beforeYBits" to n(200f.toRawBits()),
            "beforeCountO" to n(1),"s" to n(0),"afPresent" to n(0),"afterUnitEj" to n(55),"afterToken" to n(0),
            "afterCountO" to n(0),"afterXBits" to n(300f.toRawBits()),"afterYBits" to n(200f.toRawBits()),"beforeCount" to n(1),"afterCount" to n(1))
        val fact=JsonObject(base.arrayAt("normalCauseFacts").single().jsonObject+
            ("cause" to s("move-cleared-at-last-local-waypoint"))+("branchProof" to event))
        val flat=JsonObject(fact-("cause")-("branchProof")+("event" to event))
        val side=obj("schemaVersion" to n(1),"phase" to n(0),"completedCallCount" to n(2),"eventCount" to n(1),
            "events" to array(listOf(event)),"collectionEnabled" to b(true),"firstError" to JsonNull)
        val meta=obj("phase" to n(0),"entered" to n(2),"exited" to n(2),"threadId" to s("91"),"enabled" to b(true),
            "enumNames" to array(listOf(s("move"))),"minimalObservation" to obj("schemaVersion" to n(2),"firstError" to JsonNull,"localPathClear" to side))
        val samples=base.arrayAt("samples").mapIndexed { index,element ->
            val sample=element.jsonObject
            if(index==0)sample else JsonObject(sample+("raw" to JsonObject(sample.objectAt("raw")+("values" to rows[index-1].required("values")))))
        }
        val input=base.arrayAt("inputs").single().jsonObject
        return JsonObject(base+("sourceKind" to s("native-local-clear-diagnostic-scene-runtime"))+("motionObservationSchemaVersion" to n(2))+
            ("nativeRows" to array(rows))+("samples" to array(samples))+("normalCauseFacts" to array(listOf(fact)))+
            ("localClearFacts" to array(listOf(flat)))+("phases" to array(listOf(obj("summary" to obj("phase" to n(0)),"observationMetadata" to meta))))+
            ("inputs" to array(listOf(JsonObject(input+("raw" to JsonObject(input.objectAt("raw")+("values" to rows.first().required("values")))))))))
    }
    @Test fun qualified_local_path_clear_keeps_full_call_interval_and_is_not_normal_completion() {
        val facts=MotionV2DiagnosticAdapter.original(localClearOriginal())
        assertEquals("LOCAL_PATH_END",facts.clears.single().cause.name)
        assertEquals(MotionV2Math.Interval(0.0,1.0),facts.clears.single().event.interval)
        assertEquals(MotionV2DiagnosticAdapter.Cause.UNKNOWN,MotionV2DiagnosticAdapter.original(original(false)).clears.single().cause)
    }
    @Test fun local_cause_requires_raw_sidecar_flat_fact_and_branch_proof_to_match() {
        val base=localClearOriginal()
        for(key in listOf("localClearFacts","phases"))
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(base+(key to JsonArray(emptyList())))) }
        val fact=base.arrayAt("normalCauseFacts").single().jsonObject
        for((key,value)in listOf("oldToken" to n(2),"threadId" to n(92),"targetXBits" to n(0),"s" to n(1),"afterToken" to n(1))) {
            val bad=JsonObject(fact+("branchProof" to JsonObject(fact.objectAt("branchProof")+(key to value))))
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(base+("normalCauseFacts" to array(listOf(bad))))) }
        }
        val flat=base.arrayAt("localClearFacts").single().jsonObject
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(JsonObject(base+(
            "localClearFacts" to array(listOf(JsonObject(flat+("intervalQ" to array(listOf(n(.5),n(1)))))))))) }
    }
    private fun rewriteLocalProof(base:JsonObject,eventChange:(JsonObject)->JsonObject,rawChange:(MutableList<JsonElement>)->Unit = {}):JsonObject {
        val fact=base.arrayAt("normalCauseFacts").single().jsonObject;val event=eventChange(fact.objectAt("branchProof"))
        val flat=base.arrayAt("localClearFacts").single().jsonObject
        val phase=base.arrayAt("phases").single().jsonObject;val meta=phase.objectAt("observationMetadata")
        val minimal=meta.objectAt("minimalObservation");val side=minimal.objectAt("localPathClear")
        val changedSide=JsonObject(side+("events" to array(listOf(event))))
        val changedMinimal=JsonObject(minimal+("localPathClear" to changedSide))
        val changedMeta=JsonObject(meta+("minimalObservation" to changedMinimal))
        val phases=array(listOf(JsonObject(phase+("observationMetadata" to changedMeta))))
        val rows=base.arrayAt("nativeRows").toMutableList();val row=rows.last().jsonObject
        val values=row.arrayAt("values").toMutableList();rawChange(values);rows[rows.lastIndex]=JsonObject(row+("values" to JsonArray(values)))
        val samples=base.arrayAt("samples").toMutableList();val sample=samples.last().jsonObject
        samples[samples.lastIndex]=JsonObject(sample+("raw" to JsonObject(sample.objectAt("raw")+("values" to JsonArray(values)))))
        return JsonObject(base+("phases" to phases)+("normalCauseFacts" to array(listOf(JsonObject(fact+("branchProof" to event)))))+
            ("localClearFacts" to array(listOf(JsonObject(flat+("event" to event)))))+("nativeRows" to JsonArray(rows))+("samples" to JsonArray(samples)))
    }
    @Test fun coordinated_derived_P_proof_rewrite_cannot_override_actual_raw_call() {
        val base=localClearOriginal()
        for((key,value)in listOf("targetXBits" to n(400f.toRawBits()),"oldToken" to n(2),"threadId" to n(92),"beforeXBits" to n(0))) {
            val doc=rewriteLocalProof(base,{JsonObject(it+(key to value))})
            assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(doc) }
        }
    }
    @Test fun coordinated_raw_and_P_target_rewrite_still_must_match_actual_Move_consumption() {
        val doc=rewriteLocalProof(localClearOriginal(),{JsonObject(it+("targetXBits" to n(400f.toRawBits())))}) {
            it[3+19]=n(400f.toRawBits());it[70+59]=n(400f.toRawBits())
        }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(doc) }
    }
    @Test fun coordinated_raw_and_P_token_rewrite_still_must_match_actual_Move_consumption() {
        val doc=rewriteLocalProof(localClearOriginal(),{JsonObject(it+("oldToken" to n(2)))}) {
            it[3+27]=n(2);it[70+58]=n(2)
        }
        assertFailsWith<IllegalArgumentException> { MotionV2DiagnosticAdapter.original(doc) }
    }
    @Test fun accepted_stop_without_an_active_move_is_consumption_without_a_fabricated_clear() {
        val base=core();val initial=observation(0,300,null)
        val after=observation(0,300,null,listOf("movement-cancelled"));val after1=observation(1,300,null)
        val rows=array(listOf(sample(0,0,-1,300,null,"initial",initial),sample(1,0,0,300,null,"input-post",after),
            sample(2,1,0,300,null,"advance",after1)))
        val input=obj("sequence" to n(0),"q" to n(0),"action" to s("stop"),"target" to JsonNull,
            "raw" to obj("before" to initial,"after" to after,"submitResult" to accepted()))
        val m=JsonObject(base.arrayAt("motionInputs").single().jsonObject+("normalCompletion" to b(false))+
            ("originMoveId" to JsonNull)+("commandTargetBefore" to JsonNull)+("finalPosition" to position(300)))
        val f=MotionV2DiagnosticAdapter.core(JsonObject(base+("samples" to rows)+("inputs" to array(listOf(input)))+
            ("motionInputs" to array(listOf(m)))))
        assertEquals("stop",f.consumptions.single().action);assertTrue(f.clears.isEmpty())
    }
}
