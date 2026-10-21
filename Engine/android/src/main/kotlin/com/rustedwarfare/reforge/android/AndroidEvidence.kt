package com.rustedwarfare.reforge.android

import com.rustedwarfare.reforge.client.ClientEvidence
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
    private var sequence = 0L
    private var closed = false

    @Synchronized override fun record(kind: String, values: Map<String, Any?>) {
        check(!closed) { "Evidence is closed" }
        writer.write(JSONObject(AndroidKnowledgeEncoding.record(kind, values) +
            mapOf("kind" to kind, "sequence" to sequence++, "timeMillis" to System.currentTimeMillis())).toString())
        writer.newLine()
        writer.flush()
    }

    @Synchronized override fun close() {
        if (closed) return
        try { record("evidence-completed", mapOf("status" to "normal")) }
        finally { closed = true; writer.close() }
    }
}
