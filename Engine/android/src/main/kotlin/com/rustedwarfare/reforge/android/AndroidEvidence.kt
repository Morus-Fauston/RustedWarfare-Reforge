package com.rustedwarfare.reforge.android

import com.rustedwarfare.reforge.client.ClientEvidence
import com.rustedwarfare.reforge.client.DiagnosticLineOutput
import com.rustedwarfare.reforge.client.FixedDiagnosticRecord
import com.rustedwarfare.reforge.client.SynchronousDiagnosticWriter
import com.rustedwarfare.reforge.core.sim.PlayerObservation
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** App-owned diagnostic stream; not a compatibility or performance verdict. */
class AndroidEvidence(parent: File) : ClientEvidence, AutoCloseable {
    private val directory = File(parent, "run-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}")
    private val writer = run {
        check(directory.mkdirs()) { "Cannot create evidence directory: $directory" }
        File(directory, "client-diagnostic.jsonl").bufferedWriter()
    }
    private val diagnostics = SynchronousDiagnosticWriter(DiagnosticLineOutput(writer) { sequence, record ->
        JSONObject(AndroidKnowledgeEncoding.record(record.kind, record.values) +
            mapOf("kind" to record.kind, "sequence" to sequence, "timeMillis" to record.metadata["timeMillis"])).toString()
    })
    private var closed = false

    @Synchronized override fun recordObservation(phase: String, observation: PlayerObservation, generation: Long?) {
        diagnostics.checkFailure()
        check(!closed) { "Evidence is closed" }
        diagnostics.record(FixedDiagnosticRecord.observation(phase, observation, generation,
            mapOf("timeMillis" to System.currentTimeMillis())), true)
    }

    @Synchronized override fun record(kind: String, values: Map<String, Any?>) {
        diagnostics.checkFailure()
        check(!closed) { "Evidence is closed" }
        diagnostics.record(FixedDiagnosticRecord.capture(kind, values,
            mapOf("timeMillis" to System.currentTimeMillis())), true)
    }

    @Synchronized override fun close() {
        if (closed) { diagnostics.close(); return }
        var first: Throwable? = null
        try { record("evidence-completed", mapOf("status" to "normal")) }
        catch (error: Throwable) { first = error }
        finally {
            closed = true
            try { diagnostics.close() } catch (error: Throwable) {
                if (first == null) first = error else if (first !== error) first.addSuppressed(error)
            }
        }
        first?.let { throw it }
    }
}
