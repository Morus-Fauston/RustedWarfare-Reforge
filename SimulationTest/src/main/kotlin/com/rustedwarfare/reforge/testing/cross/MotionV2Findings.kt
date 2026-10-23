package com.rustedwarfare.reforge.testing.cross

/** Pure evidence accounting. This accepts no approval and emits no formal compatibility document. */
internal object MotionV2Findings {
    private val fixedCases = setOf("OM00","OM01E","OM01W","OM02S","OM02N","OM03SE","OM03NW","OM04","OM05","OM06","OM07","OM08")
    enum class Status { PASS, FAIL, INSUFFICIENT, EXECUTION_FAILED }
    data class Finding(val gateId: String, val status: Status, val detail: String)
    data class Run(val caseId: String, val repeat: Int, val runId: String, val findings: List<Finding>)
    data class Slot(val caseId: String, val repeat: Int, val runs: List<Run>, val findings: List<Finding>)
    data class Summary(val slots: List<Slot>, val unassignedRuns: List<Run>, val qualificationErrors: List<String>) {
        val findings get() = slots.flatMap { it.findings } + unassignedRuns.flatMap { it.findings }
        val hasFailure get() = findings.any { it.status == Status.FAIL }
        val hasInsufficient get() = findings.any { it.status == Status.INSUFFICIENT }
        val hasExecutionFailure get() = qualificationErrors.isNotEmpty() || findings.any { it.status == Status.EXECUTION_FAILED }
        val allPassed get() = slots.size == 36 && !hasFailure && !hasInsufficient && !hasExecutionFailure
    }
    fun summarize(required: Map<String, Set<String>>, runs: List<Run>): Summary {
        require(required.keys == fixedCases)
        require(required.values.all { gates -> gates.isNotEmpty() && gates.all { it.isNotBlank() } })
        // Snapshot all attempts. Neither duplicate slots nor duplicate runtime IDs
        // authorize choosing the better attempt, including two all-pass attempts.
        val attempts = runs.map { it.copy(findings = it.findings.toList()) }
        val errors = mutableListOf<String>()
        val assigned = attempts.groupBy { it.caseId to it.repeat }
        val unassigned = attempts.filter { it.caseId !in required || it.repeat !in 1..3 }
        for (run in unassigned) errors += "unexpected-slot:${run.caseId}:${run.repeat}:${run.runId}"
        for ((id, copies) in attempts.groupBy { it.runId }) {
            if (id.isBlank()) errors += "missing-runtime-identity"
            if (copies.size > 1) errors += "duplicate-runtime-identity:$id"
        }
        for (run in attempts) {
            val gates = required[run.caseId] ?: continue
            for (finding in run.findings) {
                if (finding.gateId !in gates) errors += "unexpected-gate:${run.caseId}:${run.repeat}:${finding.gateId}"
                if (finding.detail.isBlank()) errors += "missing-evidence-detail:${run.caseId}:${run.repeat}:${finding.gateId}"
            }
        }
        val slots = required.flatMap { (id, gates) -> (1..3).map { repeat ->
            val selected = assigned[id to repeat].orEmpty()
            if (selected.size > 1) errors += "duplicate-slot:$id:$repeat"
            val findings = selected.flatMap { it.findings }.toMutableList()
            // Account per attempt: complementary incomplete retries must not
            // erase each other's missing evidence even when the duplicate guard
            // already prevents an overall pass.
            if (selected.isEmpty()) {
                for (gate in gates) findings += Finding(gate, Status.INSUFFICIENT, "fixed run missing")
            } else for (run in selected) for (gate in gates) if (run.findings.none { it.gateId == gate }) {
                findings += Finding(gate, Status.INSUFFICIENT, "required gate missing in run ${run.runId}")
            }
            Slot(id, repeat, selected.toList(), findings.toList())
        } }
        return Summary(slots, unassigned.toList(), errors.toList())
    }
}
