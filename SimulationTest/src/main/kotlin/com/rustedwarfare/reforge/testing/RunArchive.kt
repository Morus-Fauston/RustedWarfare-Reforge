package com.rustedwarfare.reforge.testing

import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import com.rustedwarfare.reforge.core.sim.Observation
import kotlinx.serialization.json.*

internal class RunArchive(val path: Path) : AutoCloseable {
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
    fun write(record: JsonObject, input: LoadedScenario? = null) {
        raw.write(record.toString()); raw.newLine(); raw.flush()
        lineNumber++
        val fields = record.toMutableMap()
        fields["source"] = obj("path" to s("reforge/raw.jsonl"), "line" to n(lineNumber))
        fields["layer"] = s("normalized-gameplay")
        if (input != null) {
            fields["coordinateModel"] = s(input.core.coordinateModel.id)
            fields["timeModel"] = s(input.core.timeModel.id)
            fields["angleUnit"] = s("degree-clockwise-zero-up")
        }
        val observation = record["observation"] as? JsonObject
        if (observation != null) {
            fields["observation"] = JsonObject(observation + ("units" to array(observation.arrayAt("units").map { value ->
                val unit = value.jsonObject
                JsonObject(unit + mapOf(
                    "owner" to unit.required("ownerPlayerId"), "type" to unit.required("typeId"),
                    "movementState" to s(unit.text("movementState").lowercase()),
                    "rawMovementState" to unit.required("movementState"),
                ))
            })))
        }
        normalized.write(JsonObject(fields).toString()); normalized.newLine(); normalized.flush()
    }
    fun observation(value: Observation, phase: String, input: LoadedScenario) {
        write(record("observation", "phase" to s(phase), "observation" to value.json()), input)
        value.events.forEachIndexed { index, event ->
            write(record("event", "eventId" to s("${value.sampleId}:event:$index"),
                "sampleId" to s(value.sampleId), "stepIndex" to n(value.stepIndex),
                "event" to event.json()), input)
        }
    }
    fun snapshot(source: Path, name: String) {
        if (Files.isRegularFile(source)) Files.copy(source, path.resolve("inputs/$name"))
    }
    fun document(name: String, value: JsonObject) {
        Files.writeString(path.resolve(name), value.toString(), CREATE_NEW)
    }
    override fun close() {
        try { raw.close() } finally { normalized.close() }
    }
}
