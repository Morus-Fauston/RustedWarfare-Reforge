package com.rustedwarfare.reforge.testing

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import com.rustedwarfare.reforge.core.sim.Observation
import kotlinx.serialization.json.*

internal class RunArchive(val path: Path, private val costs: RunCosts? = null) : AutoCloseable {
    private val raw: BufferedWriter
    private val normalized: BufferedWriter
    private var lineNumber = 0
    init {
        Files.createDirectories(path.parent)
        Files.createDirectory(path) // Refuse even an existing empty batch.
        listOf("inputs", "reforge", "comparison", "logs").forEach { Files.createDirectory(path.resolve(it)) }
        raw = Files.newBufferedWriter(path.resolve("reforge/raw.jsonl"), CREATE_NEW)
        normalized = Files.newBufferedWriter(path.resolve("reforge/normalized.jsonl"), CREATE_NEW)
    }
    private fun <T> detail(stage: String, block: () -> T): T = if (costs == null) block() else costs.detail(stage, block)
    fun write(record: JsonObject, input: LoadedScenario? = null) {
        val rawText = detail("serialization") { record.toString() }
        detail("writing") { raw.write(rawText); raw.newLine() }
        lineNumber++
        val standardText = detail("serialization") {
            normalizeRecord(record, lineNumber, input?.core?.coordinateModel?.id, input?.core?.timeModel?.id).toString()
        }
        detail("writing") { normalized.write(standardText); normalized.newLine() }
    }
    fun observation(value: Observation, phase: String, input: LoadedScenario, players: JsonObject? = null): List<JsonObject> {
        val emitted = ArrayList<JsonObject>(1 + value.events.size)
        val row = detail("serialization") {
            record("observation", "phase" to s(phase), "observation" to
                (if (players == null) value.json() else JsonObject(value.json() + ("players" to players))))
        }
        write(row, input)
        emitted += row
        value.events.forEachIndexed { index, event ->
            val eventRow = record("event", "eventId" to s("${value.sampleId}:event:$index"),
                "sampleId" to s(value.sampleId), "stepIndex" to n(value.stepIndex),
                "event" to event.json())
            write(eventRow, input)
            emitted += eventRow
        }
        return emitted
    }
    fun snapshot(source: Path, name: String) {
        if (Files.isRegularFile(source)) Files.copy(source, path.resolve("inputs/$name"))
    }
    fun document(name: String, value: JsonObject) {
        val text = detail("serialization") { value.toString() }
        detail("writing") { Files.writeString(path.resolve(name), text, CREATE_NEW) }
    }
    fun flush() {
        raw.flush()
        normalized.flush()
    }
    override fun close() {
        try { raw.close() } finally { normalized.close() }
    }
}
