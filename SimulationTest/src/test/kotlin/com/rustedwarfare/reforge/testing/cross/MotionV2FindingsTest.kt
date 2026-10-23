package com.rustedwarfare.reforge.testing.cross

import kotlin.test.*
import com.rustedwarfare.reforge.testing.cross.MotionV2Findings.Finding
import com.rustedwarfare.reforge.testing.cross.MotionV2Findings.Run
import com.rustedwarfare.reforge.testing.cross.MotionV2Findings.Status

class MotionV2FindingsTest {
    private val ids = listOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08")
    private val required = ids.associateWith { setOf("identity", "terrain", "applicable-rule") }
    private fun good() = ids.flatMap { id -> (1..3).map { repeat ->
        Run(id,repeat,"actual-$id-$repeat",required.getValue(id).map { Finding(it,Status.PASS,"qualified evidence") }) } }

    @Test fun no_runs_still_has_all36_missing_slots() {
        val summary=MotionV2Findings.summarize(required,emptyList())
        assertEquals(36,summary.slots.size);assertTrue(summary.hasInsufficient);assertFalse(summary.allPassed)
        assertEquals(ids.flatMap { id->(1..3).map { id to it } },summary.slots.map { it.caseId to it.repeat })
    }
    @Test fun all_fixed_slots_and_all_applicable_gates_are_required() {
        assertTrue(MotionV2Findings.summarize(required,good()).allPassed)
        val absent=good().dropLast(1)
        val summary=MotionV2Findings.summarize(required,absent)
        assertEquals(36,summary.slots.size);assertTrue(summary.hasInsufficient);assertFalse(summary.allPassed)
        val missingGate=good().mapIndexed { i,r->if(i==0)r.copy(findings=r.findings.dropLast(1))else r }
        assertTrue(MotionV2Findings.summarize(required,missingGate).hasInsufficient)
    }
    @Test fun proven_failure_is_retained_with_missing_evidence_and_execution_error() {
        val altered=good().mapIndexed { i,r->if(i==0)r.copy(findings=listOf(
            Finding("terrain",Status.FAIL,"proven illegal writeback row7"),
            Finding("applicable-rule",Status.INSUFFICIENT,"normal marker absent"),
            Finding("raw-integrity",Status.EXECUTION_FAILED,"later record truncated")))else r }
        val summary=MotionV2Findings.summarize(required,altered)
        assertTrue(summary.hasFailure);assertTrue(summary.hasInsufficient);assertTrue(summary.hasExecutionFailure)
        assertTrue(summary.findings.any { it.detail=="proven illegal writeback row7" });assertFalse(summary.allPassed)
    }
    @Test fun duplicate_attempts_are_retained_and_never_selected_for_a_better_result() {
        val runs=good();val extra=runs[0].copy(runId="second-attempt",findings=listOf(Finding("terrain",Status.FAIL,"first or later failure remains")))
        val summary=MotionV2Findings.summarize(required,runs+extra)
        assertEquals(2,summary.slots.first().runs.size);assertTrue(summary.hasFailure)
        assertTrue(summary.hasExecutionFailure);assertFalse(summary.allPassed)
        assertTrue(MotionV2Findings.summarize(required,runs+runs.first().copy(runId="all-pass-extra")).hasExecutionFailure)
    }
    @Test fun duplicate_runtime_identity_across_distinct_slots_is_rejected() {
        val runs=good().mapIndexed { i,r->if(i==1)r.copy(runId=good()[0].runId)else r }
        val summary=MotionV2Findings.summarize(required,runs)
        assertTrue(summary.hasExecutionFailure);assertFalse(summary.allPassed)
    }
    @Test fun wrong_case_repeat_and_unknown_gate_cannot_disappear_from_accounting() {
        val extra=Run("not-fixed",4,"foreign-run",listOf(Finding("terrain",Status.FAIL,"unassigned proven failure")))
        val summary=MotionV2Findings.summarize(required,good()+extra)
        assertEquals(listOf(extra),summary.unassignedRuns);assertTrue(summary.hasFailure);assertTrue(summary.hasExecutionFailure)
        val unknown=good().mapIndexed { i,r->if(i==0)r.copy(findings=r.findings+Finding("invented",Status.PASS,"not required"))else r }
        assertTrue(MotionV2Findings.summarize(required,unknown).hasExecutionFailure)
    }
    @Test fun malformed_or_incomplete_fixed_catalog_is_rejected_before_summarizing() {
        assertFailsWith<IllegalArgumentException>{ MotionV2Findings.summarize(required-ids.last(),good()) }
        assertFailsWith<IllegalArgumentException>{ MotionV2Findings.summarize(required+(ids[0] to emptySet()),good()) }
        assertFailsWith<IllegalArgumentException>{ MotionV2Findings.summarize(required+(ids[0] to setOf("")),good()) }
    }
    @Test fun fixed_catalog_cannot_replace_a_required_case_with_a_different_twelfth_case() {
        val changed=(required-"OM08")+("OTHER" to required.getValue("OM08"))
        val changedRuns=good().map { if(it.caseId=="OM08")it.copy(caseId="OTHER")else it }
        assertFailsWith<IllegalArgumentException>{ MotionV2Findings.summarize(changed,changedRuns) }
    }
    @Test fun complementary_incomplete_attempts_preserve_each_attempts_missing_gates() {
        val runs=good().mapIndexed { i,r->if(i==0)r.copy(findings=r.findings.take(1))else r }
        val complementary=good().first().copy(runId="complementary",findings=good().first().findings.drop(1))
        val summary=MotionV2Findings.summarize(required,runs+complementary)
        assertTrue(summary.hasExecutionFailure);assertTrue(summary.hasInsufficient);assertFalse(summary.allPassed)
        val missing=summary.slots.first().findings.filter { it.status==Status.INSUFFICIENT }
        assertEquals(3,missing.size)
        assertTrue(missing.any { "actual-OM00-1" in it.detail });assertTrue(missing.any { "complementary" in it.detail })
    }
}
