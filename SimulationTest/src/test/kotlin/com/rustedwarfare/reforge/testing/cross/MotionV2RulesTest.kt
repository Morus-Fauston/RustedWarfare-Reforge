package com.rustedwarfare.reforge.testing.cross

import com.rustedwarfare.reforge.testing.*
import kotlinx.serialization.json.*
import kotlin.test.*

private typealias Status = MotionV2Findings.Status
private typealias Facts = MotionV2DiagnosticAdapter.Facts

class MotionV2RulesTest {
    private val none=MotionV2Rules.Endpoints(null,null)
    private fun spec(id:String,vararg gates:String)=obj("id" to s(id),"start" to array(listOf(n(0),n(0))),
        "coreHeading" to n(90),"move" to array(listOf(n(10),n(0))),"applicable" to array(gates.map(::s)))
    private fun facts(clearAt:Int?=10,cause:MotionV2DiagnosticAdapter.Cause=MotionV2DiagnosticAdapter.Cause.NORMAL,
                      end:Int=32,offset:Double=0.0,driftAt:Int?=null, input:Boolean=true):Facts {
        val calls=(0..end).map { q->val x=if(q==0)0.0 else minOf(q,clearAt?:end).toDouble()
            val prior=if(q==0)0.0 else minOf(q-1,clearAt?:end).toDouble()
            val drift=if(driftAt!=null && q>=driftAt)1.0 else 0.0
            val beforeDrift=if(driftAt!=null && q>driftAt)1.0 else 0.0
            MotionV2Events.Call(maxOf(0,q-1).toDouble(),q.toDouble(),0,if(input)0 else -1,
                (if(input)prior else 0.0)+offset+beforeDrift,0.0,(if(input)x else 0.0)+offset+drift,0.0,
                input && (clearAt==null || q<=clearAt),input && (clearAt==null || q<clearAt)) }
        val points=calls.map { MotionV2Sampling.Point(it.exitQ,it.phase,it.input,it.x,it.y,90.0) }
        val consumption=if(input)listOf(MotionV2DiagnosticAdapter.InputConsumption(0,"move",0.0,
            MotionV2Events.Event(MotionV2Math.Interval(0.0,0.0),0),obj()))else emptyList()
        val clears=if(input && clearAt!=null && clearAt<=end)listOf(MotionV2DiagnosticAdapter.Clear(cause,0,
            MotionV2Events.Event(MotionV2Math.Interval((clearAt-1).toDouble(),clearAt.toDouble()),clearAt)))else emptyList()
        return Facts(points,calls,clears,if(input)listOf(0.0)else emptyList(),consumption)
    }
    private fun evaluate(spec:JsonObject,c:Facts=facts(),o:Facts=facts(),endpoints:MotionV2Rules.Endpoints=none,
                         complete:Boolean=true,external:List<MotionV2Findings.Finding> = emptyList(),
                         horizon:Double=minOf(600.0,o.points.last().q))=
        MotionV2Rules.evaluate(spec,MotionV2Rules.Side(c,complete,horizon),MotionV2Rules.Side(o,complete,horizon),endpoints,external)
    private fun assertGate(rows:List<MotionV2Findings.Finding>,gate:String,status:Status)=
        assertTrue(rows.any { it.gateId==gate && it.status==status },"$gate expected $status; got $rows")
    @Test fun ordinary_event_gates_use_first_actual_events_and_fixed_tail() {
        val result=evaluate(spec("OM01E","input-consumption","first-motion","normal-cause","normal-clear","normal-settle","stable-tail","final-position-difference"))
        for(gate in listOf("input-consumption","first-motion","normal-cause","normal-clear","normal-settle","stable-tail","final-position-difference"))assertGate(result,gate,Status.PASS)
    }
    @Test fun ordinary_time_events_preserve_interval_uncertainty_and_proved_failure() {
        val spec=spec("OM01E","normal-clear")
        assertGate(evaluate(spec,facts(10),facts(15)),"normal-clear",Status.PASS) // U=6
        assertGate(evaluate(spec,facts(10),facts(16)),"normal-clear",Status.INSUFFICIENT) // L=5,U=7
        assertGate(evaluate(spec,facts(10),facts(18)),"normal-clear",Status.FAIL) // L=7
    }
    @Test fun wrong_cause_is_failure_unknown_is_insufficient_and_neither_can_supply_normal_event() {
        val spec=spec("OM01E","normal-cause","normal-clear")
        assertGate(evaluate(spec,facts(cause=MotionV2DiagnosticAdapter.Cause.UNREACHABLE)),"normal-cause",Status.FAIL)
        val unknown=evaluate(spec,o=facts(cause=MotionV2DiagnosticAdapter.Cause.UNKNOWN))
        assertGate(unknown,"normal-cause",Status.INSUFFICIENT);assertGate(unknown,"normal-clear",Status.INSUFFICIENT)
    }
    @Test fun missing_core_tail_is_not_hidden_by_shorter_common_sampling_horizon() {
        val result=evaluate(spec("OM01E","position-rmse-max","heading-rmse-max","speed-window-rmse-max","stable-tail"),facts(end=12),facts(end=32))
        assertGate(result,"position-rmse-max",Status.INSUFFICIENT);assertGate(result,"stable-tail",Status.INSUFFICIENT)
    }
    @Test fun known_failure_and_separate_missing_evidence_are_both_retained() {
        val terrain=MotionV2Findings.Finding("terrain",Status.FAIL,"raw collision index7 illegal writeback")
        val result=evaluate(spec("OM01E","normal-cause","terrain","identity"),o=facts(cause=MotionV2DiagnosticAdapter.Cause.UNKNOWN),external=listOf(terrain))
        assertGate(result,"terrain",Status.FAIL);assertGate(result,"identity",Status.INSUFFICIENT);assertGate(result,"normal-cause",Status.INSUFFICIENT)
    }
    @Test fun earliest_s_later_drift_fails_even_when_a_late_stable_anchor_exists() {
        val result=evaluate(spec("OM01E","stable-tail"),facts(driftAt=19),facts())
        assertGate(result,"stable-tail",Status.FAIL)
    }
    @Test fun stationary_scene_needs_no_input_and_full_raw_budget_without_adding_heading_drift_rule() {
        val spec=spec("OM00","zero-input-integrity","stationary-position","initial-heading")
        val stationary=facts(clearAt=null,end=18,input=false)
        for(gate in listOf("zero-input-integrity","stationary-position","initial-heading"))assertGate(evaluate(spec,stationary,stationary),gate,Status.PASS)
        assertGate(evaluate(spec,facts(),stationary),"zero-input-integrity",Status.FAIL)
        assertGate(evaluate(spec,stationary.copy(points=stationary.points.map { it.copy(heading=if(it.q==0.0)90.0 else 92.0) }),stationary),"initial-heading",Status.PASS)
        assertGate(evaluate(spec,facts(clearAt=null,end=17,input=false),stationary),"stationary-position",Status.INSUFFICIENT)
    }
    @Test fun obstacle_has_no_same_time_event_or_route_requirement_and_endpoint_choice_is_mandatory() {
        val spec=spec("OM07","normal-cause","normal-settle-presence","stable-tail","final-position-difference","length-one-way-ratio","duration-one-way-ratio")
        assertFailsWith<IllegalArgumentException>{evaluate(spec)}
        assertFailsWith<IllegalArgumentException>{evaluate(spec,endpoints=MotionV2Rules.Endpoints("normal-clear",null))}
        val core=facts(10,end=32);val slower=facts(20,end=42).let { f->f.copy(
            calls=f.calls.map { it.copy(beforeX=it.beforeX/2,x=it.x/2) },points=f.points.map { it.copy(x=it.x/2) }) }
        val result=evaluate(spec,core,slower,MotionV2Rules.Endpoints("normal-clear","normal-clear"))
        for(gate in listOf("normal-cause","normal-settle-presence","stable-tail","final-position-difference","length-one-way-ratio","duration-one-way-ratio"))assertGate(result,gate,Status.PASS)
    }
    @Test fun unreachable_original_early_unknown_stays_insufficient_without_arrival_or_ratio_gates() {
        val spec=spec("OM08","core-unreachable-cancel","core-settle-presence","core-stable-tail","original-fallback-reference","original-full-600-if-ongoing")
        val result=evaluate(spec,facts(cause=MotionV2DiagnosticAdapter.Cause.UNREACHABLE),facts(cause=MotionV2DiagnosticAdapter.Cause.UNKNOWN))
        for(gate in listOf("core-unreachable-cancel","core-settle-presence","core-stable-tail"))assertGate(result,gate,Status.PASS)
        assertGate(result,"original-fallback-reference",Status.INSUFFICIENT)
        assertTrue(result.none { it.gateId.contains("ratio") || it.gateId=="final-position-difference" })
    }
    @Test fun ongoing_original_requires_600_while_core_must_actually_cancel_and_settle() {
        val spec=spec("OM08","core-unreachable-cancel","original-full-600-if-ongoing")
        assertGate(evaluate(spec,facts(cause=MotionV2DiagnosticAdapter.Cause.UNREACHABLE),facts(clearAt=null,end=600)),"original-full-600-if-ongoing",Status.PASS)
        assertGate(evaluate(spec,facts(cause=MotionV2DiagnosticAdapter.Cause.UNREACHABLE),facts(clearAt=null,end=599)),"original-full-600-if-ongoing",Status.INSUFFICIENT)
        assertGate(evaluate(spec,facts(clearAt=null,end=600),facts(clearAt=null,end=600)),"core-unreachable-cancel",Status.FAIL)
    }
    @Test fun duration_ratio_uses_worst_complete_interval_and_never_a_midpoint() {
        val spec=spec("OM07","duration-one-way-ratio");val chosen=MotionV2Rules.Endpoints("normal-clear","normal-clear")
        assertGate(evaluate(spec,facts(13,end=40),facts(10,end=40),chosen),"duration-one-way-ratio",Status.PASS)
        assertGate(evaluate(spec,facts(15,end=40),facts(10,end=40),chosen),"duration-one-way-ratio",Status.INSUFFICIENT)
        assertGate(evaluate(spec,facts(17,end=40),facts(10,end=40),chosen),"duration-one-way-ratio",Status.FAIL)
    }
    @Test fun known_numerical_failure_also_retains_the_missing_required_points() {
        val result=evaluate(spec("OM01E","position-rmse-max"),facts(end=12,offset=5.0),facts(end=32))
        assertGate(result,"position-rmse-max",Status.FAIL)
        assertGate(result,"position-rmse-max",Status.INSUFFICIENT)
    }
    @Test fun original_reversals_are_reference_but_core_reversals_are_a_failure() {
        fun reversing():Facts {
            val f=facts(clearAt=null,end=4)
            val calls=f.calls.mapIndexed { i,c->c.copy(beforeX=if(i==0 || i%2==1)0.0 else 1.0,x=if(i%2==1)1.0 else 0.0) }
            return f.copy(calls=calls,points=calls.map { MotionV2Sampling.Point(it.exitQ,0,0,it.x,it.y,90.0) })
        }
        val spec=spec("OM08","core-reversal-gate","original-reversal-reference")
        assertGate(evaluate(spec,facts(clearAt=null,end=4),reversing()),"original-reversal-reference",Status.PASS)
        assertGate(evaluate(spec,reversing(),facts(clearAt=null,end=4)),"core-reversal-gate",Status.FAIL)
    }
    @Test fun stop_scene_uses_stop_sequence_and_three_q_events_without_normal_completion_gate() {
        val f=facts(clearAt=4,end=28)
        val calls=f.calls.toMutableList();val stop=calls[4]
        calls[4]=stop.copy(targetAfter=true)
        calls.add(5,MotionV2Events.Call(4.0,4.0,0,1,4.0,0.0,4.0,0.0,true,false))
        for(i in 6 until calls.size)calls[i]=calls[i].copy(input=1)
        val consumption=f.consumptions+MotionV2DiagnosticAdapter.InputConsumption(1,"stop",4.0,
            MotionV2Events.Event(MotionV2Math.Interval(4.0,4.0),5),obj())
        val stopped=f.copy(calls=calls,points=calls.map { MotionV2Sampling.Point(it.exitQ,0,it.input,it.x,it.y,90.0) },
            inputs=listOf(0.0,4.0),consumptions=consumption,clears=listOf(MotionV2DiagnosticAdapter.Clear(
                MotionV2DiagnosticAdapter.Cause.EXTERNAL_STOP,1,MotionV2Events.Event(MotionV2Math.Interval(4.0,4.0),5))))
        val result=evaluate(spec("OM05","input-consumption","first-motion","stop-clear","stop-settle","stable-tail"),stopped,stopped)
        for(gate in listOf("input-consumption","first-motion","stop-clear","stop-settle","stable-tail"))assertGate(result,gate,Status.PASS)
        assertTrue(result.none { it.gateId.startsWith("normal-") })
        val wrong=stopped.copy(clears=stopped.clears.map { it.copy(cause=MotionV2DiagnosticAdapter.Cause.NORMAL) })
        assertGate(evaluate(spec("OM05","stop-clear"),wrong,stopped),"stop-clear",Status.FAIL)
    }
    @Test fun missing_qualified_deceleration_plateau_cannot_be_faked_by_normal_arrival() {
        val result=evaluate(spec("OM06","normal-cause","arrival-deceleration"))
        assertGate(result,"normal-cause",Status.PASS);assertGate(result,"arrival-deceleration",Status.INSUFFICIENT)
    }
    @Test fun complete_600_q_proves_a_required_18_q_tail_beyond_budget_failed() {
        assertGate(evaluate(spec("OM01E","stable-tail"),facts(clearAt=590,end=600),facts()),"stable-tail",Status.FAIL)
    }
    @Test fun a_later_normal_clear_cannot_replace_an_earlier_wrong_clear_of_the_same_input() {
        val f=facts();val calls=f.calls.toMutableList()
        calls[5]=calls[5].copy(targetAfter=false);calls[6]=calls[6].copy(targetBefore=false)
        val early=MotionV2DiagnosticAdapter.Clear(MotionV2DiagnosticAdapter.Cause.UNREACHABLE,0,
            MotionV2Events.Event(MotionV2Math.Interval(4.0,5.0),5))
        val revived=f.copy(calls=calls,clears=listOf(early)+f.clears)
        val result=evaluate(spec("OM01E","normal-cause","stable-tail"),revived,f)
        assertGate(result,"normal-cause",Status.FAIL)
        assertFalse(result.any { it.gateId=="normal-cause" && it.status==Status.PASS && it.detail.startsWith("core") })
    }
    @Test fun partial_raw_does_not_erase_an_already_observed_wrong_input() {
        val f=facts();val wrong=f.copy(consumptions=f.consumptions.map { it.copy(action="stop") })
        val result=evaluate(spec("OM01E","input-consumption"),wrong,f,complete=false)
        assertGate(result,"input-consumption",Status.FAIL);assertGate(result,"input-consumption",Status.INSUFFICIENT)
    }
    @Test fun observed_600_q_failure_survives_later_raw_incompleteness_and_execution_error() {
        val failedExecution=MotionV2Findings.Finding("identity",Status.EXECUTION_FAILED,"qualified prefix retained; later process failed")
        val result=evaluate(spec("OM01E","normal-cause","stable-tail","raw-integrity","identity"),
            facts(clearAt=null,end=600),facts(),complete=false,external=listOf(failedExecution))
        assertGate(result,"normal-cause",Status.FAIL)
        assertGate(result,"stable-tail",Status.FAIL)
        assertGate(result,"raw-integrity",Status.INSUFFICIENT)
        assertGate(result,"identity",Status.EXECUTION_FAILED)
    }
    @Test fun early_original_end_does_not_shorten_the_explicit_required_numerical_window() {
        val gates=listOf("position-rmse-max","heading-rmse-max","speed-window-rmse-max")
        val result=evaluate(spec("OM01E",*gates.toTypedArray()),facts(end=32),facts(end=12),horizon=32.0)
        for(gate in gates)assertGate(result,gate,Status.INSUFFICIENT)
    }
    @Test fun complete_numerical_points_do_not_hide_raw_incompleteness_or_known_failure() {
        val gates=listOf("position-rmse-max","heading-rmse-max","speed-window-rmse-max")
        val matching=evaluate(spec("OM01E",*gates.toTypedArray()),complete=false,horizon=32.0)
        for(gate in gates)assertGate(matching,gate,Status.INSUFFICIENT)
        val failed=evaluate(spec("OM01E",*gates.toTypedArray()),facts(offset=5.0),facts(),complete=false,horizon=32.0)
        assertGate(failed,"position-rmse-max",Status.FAIL)
        for(gate in gates)assertGate(failed,gate,Status.INSUFFICIENT)
    }
    @Test fun qualified_terminal_endpoint_failures_survive_later_raw_incompleteness() {
        val position=evaluate(spec("OM01E","final-position-difference"),facts(offset=5.0),facts(),complete=false)
        assertGate(position,"final-position-difference",Status.FAIL)
        assertGate(position,"final-position-difference",Status.INSUFFICIENT)
        // Same (10,0) final goal, reached via (5,10): longer than 1.5x the
        // straight reference and 20q versus 10q, with a full fixed tail.
        val f=facts(clearAt=20,end=42)
        fun height(x:Double)=minOf(x,20.0-x)
        val calls=f.calls.map { it.copy(beforeX=it.beforeX/2,beforeY=height(it.beforeX),x=it.x/2,y=height(it.x)) }
        val detour=f.copy(calls=calls,points=calls.map { MotionV2Sampling.Point(it.exitQ,it.phase,it.input,it.x,it.y,90.0) })
        val obstacle=evaluate(spec("OM07","length-one-way-ratio","duration-one-way-ratio"),detour,facts(),
            MotionV2Rules.Endpoints("declared-stable-tail-end","settle-start"),complete=false)
        for(gate in listOf("length-one-way-ratio","duration-one-way-ratio")) {
            assertGate(obstacle,gate,Status.FAIL)
            assertGate(obstacle,gate,Status.INSUFFICIENT)
        }
    }
    @Test fun an_extra_input_after_the_planned_numerical_window_keeps_its_failure_without_aborting_other_gates() {
        val f=facts(end=42)
        val calls=f.calls.toMutableList()
        calls.add(41,MotionV2Events.Call(40.0,40.0,0,1,10.0,0.0,10.0,0.0,false,false))
        for(i in 42 until calls.size)calls[i]=calls[i].copy(input=1)
        val lateStop=MotionV2DiagnosticAdapter.InputConsumption(1,"stop",40.0,
            MotionV2Events.Event(MotionV2Math.Interval(40.0,40.0),41),obj())
        val extra=f.copy(calls=calls,points=calls.map { MotionV2Sampling.Point(it.exitQ,0,it.input,it.x,it.y,90.0) },
            inputs=listOf(0.0,40.0),consumptions=f.consumptions+lateStop)
        val result=evaluate(spec("OM01E","input-consumption","position-rmse-max"),f,extra,horizon=32.0)
        assertGate(result,"input-consumption",Status.FAIL)
        assertGate(result,"position-rmse-max",Status.PASS)
    }
    @Test fun incomplete_599_and_600_q_prefixes_have_distinct_budget_findings_without_qualifying_the_original() {
        val required=spec("OM08","core-unreachable-cancel","core-stable-tail","original-full-600-if-ongoing","raw-integrity")
        val observedOriginal=facts(clearAt=null,end=600)
        val short=evaluate(required,facts(clearAt=null,end=599),observedOriginal,complete=false)
        assertGate(short,"core-unreachable-cancel",Status.INSUFFICIENT)
        assertFalse(short.any { it.gateId=="core-unreachable-cancel" && it.status==Status.FAIL })
        val covered=evaluate(required,facts(clearAt=null,end=600),observedOriginal,complete=false)
        assertGate(covered,"core-unreachable-cancel",Status.FAIL)
        assertGate(covered,"core-stable-tail",Status.FAIL)
        for(result in listOf(short,covered)) {
            assertGate(result,"original-full-600-if-ongoing",Status.INSUFFICIENT)
            assertFalse(result.any { it.gateId=="original-full-600-if-ongoing" && it.status==Status.PASS })
        }
    }
    @Test fun one_incomplete_side_stays_visible_even_with_complete_numerical_points_and_external_raw_pass() {
        val f=facts();val gates=listOf("position-rmse-max","heading-rmse-max","speed-window-rmse-max","raw-integrity")
        val result=MotionV2Rules.evaluate(spec("OM01E",*gates.toTypedArray()),MotionV2Rules.Side(f,true,32.0),
            MotionV2Rules.Side(f,false,32.0),none,listOf(MotionV2Findings.Finding("raw-integrity",Status.PASS,"external paired copies checked")))
        for(gate in gates)assertGate(result,gate,Status.INSUFFICIENT)
        assertTrue(result.any { it.gateId=="raw-integrity" && it.status==Status.INSUFFICIENT && it.detail.startsWith("original") })
    }
    @Test fun numerical_plan_horizon_must_be_explicit_finite_in_budget_and_equal_on_both_sides() {
        val f=facts()
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,600.1))
            assertFailsWith<IllegalArgumentException> { MotionV2Rules.Side(f,true,value) }
        assertFailsWith<IllegalArgumentException> {
            MotionV2Rules.evaluate(spec("OM01E","position-rmse-max"),MotionV2Rules.Side(f,true,32.0),
                MotionV2Rules.Side(f,true,12.0),none,emptyList())
        }
    }
    @Test fun obstacle_ratios_do_not_assume_a_unique_input_after_input_structure_has_failed() {
        val f=facts(end=42)
        // Pure API defensive control, not a source-qualified raw record:
        // the extra consumption has not been proved against a matching call.
        val extra=f.copy(inputs=listOf(0.0,40.0),consumptions=f.consumptions+
            MotionV2DiagnosticAdapter.InputConsumption(1,"stop",40.0,
                MotionV2Events.Event(MotionV2Math.Interval(40.0,40.0),40),obj()))
        val result=evaluate(spec("OM07","input-consumption","length-one-way-ratio","duration-one-way-ratio"),extra,f,
            MotionV2Rules.Endpoints("declared-stable-tail-end","settle-start"))
        assertGate(result,"input-consumption",Status.FAIL)
        for(gate in listOf("length-one-way-ratio","duration-one-way-ratio"))assertGate(result,gate,Status.INSUFFICIENT)
    }
}
